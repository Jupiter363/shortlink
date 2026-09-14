package main

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func localConfig(base string) Config {
	return Config{BaseURL: base, Host: "short.test", Sources: []string{"127.0.0.1"},
		Links: []Link{{ShortURI: "012345678", OriginURL: "https://shortlink-perf.local/target/test"}},
		Rate:  100, DurationMillis: 40, Workers: 1, Seed: 42, UserAgent: "shortlink-performance/1.0",
		Referer: "https://shortlink-perf.local/source", Cookie: "sl_uv=0123456789abcdef0123456789abcdef",
		RequestTimeoutMillis: 100, BodyLimit: 1024}
}

func preparedClient(t *testing.T, c Config) (*connectionClient, []byte) {
	t.Helper()
	wire, address, err := requestBytes(c)
	if err != nil {
		t.Fatal(err)
	}
	client := newConnectionClient(address, c.Sources[0], c)
	t.Cleanup(client.close)
	return client, wire[0]
}

func TestCorrect302KeepAliveFixedHeadersAndNoFollow(t *testing.T) {
	var targetHits, sourceHits, connections atomic.Int64
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { targetHits.Add(1); w.WriteHeader(200) }))
	defer target.Close()
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sourceHits.Add(1)
		for name, want := range map[string]string{"User-Agent": "shortlink-performance/1.0", "Referer": "https://shortlink-perf.local/source",
			"Cookie": "sl_uv=0123456789abcdef0123456789abcdef", "Accept": "application/json", "Content-Type": "application/json", "X-Forwarded-Proto": "http"} {
			if r.Header.Get(name) != want {
				t.Errorf("fixed header %s changed", name)
			}
		}
		if r.Host != "short.test" || r.Method != "GET" || r.Proto != "HTTP/1.1" || r.ContentLength != 0 || len(r.TransferEncoding) != 0 || r.Header.Get("X-Forwarded-For") != "" {
			t.Error("wire protocol changed")
		}
		w.Header().Set("Set-Cookie", "sl_uv=ffffffffffffffffffffffffffffffff")
		w.Header().Set("Location", target.URL)
		w.WriteHeader(302)
	}))
	server.Config.ConnState = func(_ net.Conn, state http.ConnState) {
		if state == http.StateNew {
			connections.Add(1)
		}
	}
	server.Start()
	defer server.Close()
	c := localConfig(server.URL)
	c.Links[0].OriginURL = target.URL
	client, wire := preparedClient(t, c)
	for i := 0; i < 2; i++ {
		got := client.exchange(wire, target.URL, nil)
		if !got.correct || got.clientError || !got.received {
			t.Fatalf("invalid 302 result: %+v", got)
		}
	}
	if targetHits.Load() != 0 || sourceHits.Load() != 2 || connections.Load() != 1 {
		t.Fatalf("follow/reuse counts target=%d source=%d conn=%d", targetHits.Load(), sourceHits.Load(), connections.Load())
	}
}

func TestInvalidHTTPStopsAtHeadersButReadsBody(t *testing.T) {
	for _, tc := range []struct {
		name, location, reason string
		status                 int
	}{
		{"wrong_location", "https://wrong.invalid/", "WRONG_REDIRECT_TARGET", 302},
		{"429", "", "HTTP_STATUS_429", 429}, {"503", "", "HTTP_STATUS_503", 503}, {"unexpected200", "", "HTTP_STATUS_OTHER", 200},
	} {
		t.Run(tc.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Location", tc.location)
				w.WriteHeader(tc.status)
				_, _ = io.WriteString(w, "body")
			}))
			defer server.Close()
			c := localConfig(server.URL)
			client, wire := preparedClient(t, c)
			notified := 0
			got := client.exchange(wire, c.Links[0].OriginURL, func(reason string, status int) {
				notified++
				if reason != tc.reason || status != tc.status {
					t.Error("wrong fixed reason")
				}
			})
			if got.correct || got.clientError || !got.received || got.reason != tc.reason || notified != 1 {
				t.Fatalf("bad result: %+v notices=%d", got, notified)
			}
		})
	}
}

func rawServer(t *testing.T, handler func(net.Conn)) net.Listener {
	t.Helper()
	l, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = l.Close() })
	go func() {
		for {
			conn, err := l.Accept()
			if err != nil {
				return
			}
			go handler(conn)
		}
	}()
	return l
}
func consumeRequest(conn net.Conn) error {
	r, err := http.ReadRequest(bufio.NewReader(conn))
	if err != nil {
		return err
	}
	return r.Body.Close()
}

func TestReusedConnectionFailureIsNeverRetried(t *testing.T) {
	var requests, dials atomic.Int64
	closed := make(chan struct{})
	l := rawServer(t, func(conn net.Conn) {
		defer conn.Close()
		if consumeRequest(conn) != nil {
			return
		}
		requests.Add(1)
		_, _ = io.WriteString(conn, "HTTP/1.1 302 Found\r\nLocation: https://shortlink-perf.local/target/test\r\nContent-Length: 0\r\n\r\n")
		_ = conn.Close()
		close(closed)
	})
	c := localConfig("http://" + l.Addr().String())
	client, wire := preparedClient(t, c)
	originalDial := client.dial
	client.dial = func(ctx context.Context) (net.Conn, error) { dials.Add(1); return originalDial(ctx) }
	first := client.exchange(wire, c.Links[0].OriginURL, nil)
	if !first.correct {
		t.Fatalf("first request failed: %+v", first)
	}
	<-closed
	second := client.exchange(wire, c.Links[0].OriginURL, nil)
	if second.correct || !second.clientError || second.received || dials.Load() != 1 || requests.Load() != 1 {
		t.Fatalf("GET may have been retried: result=%+v dials=%d requests=%d", second, dials.Load(), requests.Load())
	}
}

func TestConnectionCloseAllowsOnlyNextNewRequestToDial(t *testing.T) {
	var connections atomic.Int64
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Connection", "close")
		w.Header().Set("Location", "https://shortlink-perf.local/target/test")
		w.WriteHeader(302)
	}))
	server.Config.ConnState = func(_ net.Conn, s http.ConnState) {
		if s == http.StateNew {
			connections.Add(1)
		}
	}
	server.Start()
	defer server.Close()
	c := localConfig(server.URL)
	client, wire := preparedClient(t, c)
	for i := 0; i < 2; i++ {
		if got := client.exchange(wire, c.Links[0].OriginURL, nil); !got.correct {
			t.Fatal(got)
		}
	}
	if connections.Load() != 2 {
		t.Fatal("next logical request must use a new connection after explicit close")
	}
}

func TestResponseFailuresPreserveReceivedHTTPAndBoundBytes(t *testing.T) {
	for _, tc := range []struct {
		name, reply, reason string
		received            bool
	}{
		{"truncated_length", "HTTP/1.1 302 Found\r\nLocation: https://shortlink-perf.local/target/test\r\nContent-Length: 8\r\n\r\nshort", "BODY_READ_FAILED", true},
		{"truncated_chunk", "HTTP/1.1 302 Found\r\nLocation: https://shortlink-perf.local/target/test\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nabc", "BODY_READ_FAILED", true},
		{"body_limit", "HTTP/1.1 302 Found\r\nLocation: https://shortlink-perf.local/target/test\r\nContent-Length: 1025\r\n\r\n" + strings.Repeat("x", 1025), "RESPONSE_BODY_LIMIT", true},
		{"header_limit", "HTTP/1.1 302 Found\r\nX-Too-Large: " + strings.Repeat("x", maxResponseWireBytes+1), "RESPONSE_WIRE_LIMIT", false},
		{"malformed_header", "not HTTP\r\n\r\n", "RESPONSE_READ_FAILED", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			l := rawServer(t, func(conn net.Conn) {
				defer conn.Close()
				if consumeRequest(conn) == nil {
					_, _ = io.WriteString(conn, tc.reply)
				}
			})
			c := localConfig("http://" + l.Addr().String())
			client, wire := preparedClient(t, c)
			out := client.exchange(wire, c.Links[0].OriginURL, nil)
			if out.correct || !out.clientError || out.received != tc.received || out.reason != tc.reason || client.conn != nil {
				t.Fatalf("unexpected failure: %+v", out)
			}
		})
	}
}

func TestTimeoutIncludesBodyAndKeepsHeaderReceipt(t *testing.T) {
	release := make(chan struct{})
	defer close(release)
	l := rawServer(t, func(conn net.Conn) {
		defer conn.Close()
		if consumeRequest(conn) == nil {
			_, _ = io.WriteString(conn, "HTTP/1.1 302 Found\r\nLocation: https://shortlink-perf.local/target/test\r\nContent-Length: 8\r\n\r\n")
			<-release
		}
	})
	c := localConfig("http://" + l.Addr().String())
	c.RequestTimeoutMillis = 25
	client, wire := preparedClient(t, c)
	out := client.exchange(wire, c.Links[0].OriginURL, nil)
	if !out.clientError || !out.received || out.reason != "BODY_READ_TIMEOUT" || out.roundtrip < 20*time.Millisecond || out.roundtrip > time.Second {
		t.Fatalf("invalid body timeout: %+v", out)
	}
}

func TestDialFailureAndPartialWriteNeverReplay(t *testing.T) {
	c := localConfig("http://127.0.0.1:1")
	client, wire := preparedClient(t, c)
	calls := 0
	client.dial = func(context.Context) (net.Conn, error) {
		calls++
		return nil, fmt.Errorf("private http://secret.invalid token")
	}
	out := client.exchange(wire, c.Links[0].OriginURL, nil)
	if calls != 1 || out.reason != "DIAL_FAILED" || out.received {
		t.Fatalf("dial retried or raw error leaked: %+v", out)
	}
	left, right := net.Pipe()
	defer right.Close()
	writeCalls := 0
	client.dial = func(context.Context) (net.Conn, error) {
		calls++
		return &failingWriteConn{Conn: left, calls: &writeCalls}, nil
	}
	out = client.exchange(wire, c.Links[0].OriginURL, nil)
	if calls != 2 || writeCalls != 1 || out.reason != "WRITE_FAILED" {
		t.Fatalf("partial request replayed: %+v calls=%d", out, writeCalls)
	}
}

type failingWriteConn struct {
	net.Conn
	calls *int
}

func (c *failingWriteConn) Write(p []byte) (int, error) {
	*c.calls++
	return len(p) / 2, io.ErrUnexpectedEOF
}
