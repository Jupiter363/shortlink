package main

import (
	"bufio"
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"time"
)

var errWireLimit = errors.New("RESPONSE_WIRE_LIMIT")

type boundedConnReader struct {
	conn      net.Conn
	remaining int
}

func (r *boundedConnReader) Read(p []byte) (int, error) {
	if r.remaining <= 0 {
		return 0, errWireLimit
	}
	if len(p) > r.remaining {
		p = p[:r.remaining]
	}
	n, err := r.conn.Read(p)
	r.remaining -= n
	return n, err
}

type exchangeResult struct {
	status      int
	received    bool
	correct     bool
	clientError bool
	reason      string
	roundtrip   time.Duration
	diagnostic  exchangeDiagnostics
}

// Exactly one owner and one outstanding request per connection. No HTTP Transport
// exists here: dial/write/read failures are terminal, never replayed automatically.
type connectionClient struct {
	requireGatewayRequestID                        bool
	address                                        string
	dial                                           func(context.Context) (net.Conn, error)
	conn                                           net.Conn
	limited                                        *boundedConnReader
	reader                                         *bufio.Reader
	body                                           []byte
	request                                        http.Request
	timeout                                        time.Duration
	localPort, connectionSequence, requestSequence int
}

func newConnectionClient(address, source string, c Config) *connectionClient {
	d := net.Dialer{Timeout: timeout(c), KeepAlive: 30 * time.Second, LocalAddr: &net.TCPAddr{IP: net.ParseIP(source)}}
	return &connectionClient{address: address, dial: func(ctx context.Context) (net.Conn, error) {
		return d.DialContext(ctx, "tcp4", address)
	}, body: make([]byte, c.BodyLimit+1), request: http.Request{Method: http.MethodGet}, timeout: timeout(c), requireGatewayRequestID: c.RequireGatewayRequestID}
}

func (c *connectionClient) close() {
	if c.conn != nil {
		_ = c.conn.Close()
		c.conn = nil
		c.reader = nil
		c.limited = nil
	}
}

func classifyNetwork(err error, stage string) string {
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		return stage + "_TIMEOUT"
	}
	if errors.Is(err, errWireLimit) {
		return "RESPONSE_WIRE_LIMIT"
	}
	return stage + "_FAILED"
}

func (c *connectionClient) exchange(wire []byte, expected string, onInvalidHeaders func(string, int)) (out exchangeResult) {
	start := time.Now()
	diagnostic := newExchangeDiagnostics(start, c.conn == nil)
	defer func() {
		diagnostic.end(false)
		diagnostic.localPort = c.localPort
		diagnostic.connectionSequence = c.connectionSequence
		diagnostic.requestSequence = c.requestSequence
		out.roundtrip = time.Since(start)
		out.diagnostic = diagnostic
	}()
	fail := func(reason string) exchangeResult {
		diagnostic.end(false)
		c.close()
		return exchangeResult{
			status: out.status, received: out.received, clientError: true, reason: reason,
		}
	}
	deadline := start.Add(c.timeout)
	if c.conn == nil {
		c.connectionSequence++
		c.requestSequence = 0
		c.localPort = 0
		diagnostic.begin(diagConnect)
		ctx, cancel := context.WithDeadline(context.Background(), deadline)
		conn, err := c.dial(ctx)
		cancel()
		diagnostic.end(err == nil)
		if err != nil {
			return fail(classifyNetwork(err, "DIAL"))
		}
		c.conn = conn
		if address, ok := conn.LocalAddr().(*net.TCPAddr); ok {
			c.localPort = address.Port
		}
		c.limited = &boundedConnReader{conn: conn}
		c.reader = bufio.NewReaderSize(c.limited, 4096)
	}
	c.requestSequence++
	if c.conn.SetDeadline(deadline) != nil {
		return fail("DEADLINE_FAILED")
	}
	c.limited.remaining = maxResponseWireBytes
	diagnostic.begin(diagWrite)
	for offset := 0; offset < len(wire); {
		n, err := c.conn.Write(wire[offset:])
		if err != nil {
			return fail(classifyNetwork(err, "WRITE"))
		}
		if n <= 0 {
			return fail("WRITE_NO_PROGRESS")
		}
		offset += n
	}
	diagnostic.end(true)
	diagnostic.begin(diagHeader)
	response, err := http.ReadResponse(c.reader, &c.request)
	diagnostic.end(err == nil)
	if err != nil {
		return fail(classifyNetwork(err, "RESPONSE_READ"))
	}
	out.status = response.StatusCode
	out.received = true
	diagnostic.gatewayRequestID = parseGatewayRequestID(response.Header.Values("X-Request-ID"))
	if response.ProtoMajor != 1 || response.ProtoMinor != 1 {
		out.reason = "HTTP_VERSION_INVALID"
	} else if out.status != http.StatusFound {
		switch out.status {
		case 429:
			out.reason = "HTTP_STATUS_429"
		case 503:
			out.reason = "HTTP_STATUS_503"
		default:
			out.reason = "HTTP_STATUS_OTHER"
		}
	} else if len(response.Header.Values("Location")) != 1 || response.Header.Get("Location") != expected {
		out.reason = "WRONG_REDIRECT_TARGET"
	}
	if out.reason == "" && c.requireGatewayRequestID && !diagnostic.gatewayRequestID.valid() {
		out.reason = "GATEWAY_REQUEST_ID_" + diagnostic.gatewayRequestID.state.String()
	}
	if out.reason != "" && onInvalidHeaders != nil {
		onInvalidHeaders(out.reason, out.status)
	}
	// Reuse the small worker buffer; preserve body/trailer errors (including
	// unexpected EOF), instead of conflating them with ReadFull's short-buffer EOF.
	diagnostic.begin(diagBody)
	n := 0
	for {
		read, readErr := response.Body.Read(c.body[n:])
		n += read
		if n == len(c.body) {
			c.close()
			_ = response.Body.Close()
			return fail("RESPONSE_BODY_LIMIT")
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			c.close()
			_ = response.Body.Close()
			return fail(classifyNetwork(readErr, "BODY_READ"))
		}
		if read == 0 {
			c.close()
			_ = response.Body.Close()
			return fail("BODY_READ_NO_PROGRESS")
		}
	}
	if response.ContentLength >= 0 && int64(n) != response.ContentLength {
		c.close()
		_ = response.Body.Close()
		return fail("BODY_TRUNCATED")
	}
	if closeErr := response.Body.Close(); closeErr != nil {
		return fail("BODY_CLOSE_FAILED")
	}
	if c.reader.Buffered() != 0 {
		return fail("UNEXPECTED_RESPONSE_BYTES")
	}
	if response.Close {
		c.close()
	}
	diagnostic.end(true)
	out.correct = out.reason == ""
	return out
}
