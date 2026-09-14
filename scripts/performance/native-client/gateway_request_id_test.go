package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"reflect"
	"strings"
	"testing"
	"time"
	"unsafe"
)

const testGatewayHex = "0123456789abcdefABCDEF0123456789"
const testGatewayUUID = "12345678-9aBC-4DEF-8123-456789abcdef"

func TestGatewayRequestIDParserIsBoundedAndPreservesWireSpelling(t *testing.T) {
	for _, tc := range []struct {
		name   string
		values []string
		state  gatewayRequestIDState
		want   string
	}{
		{"hex32", []string{testGatewayHex}, gatewayIDHex32, testGatewayHex},
		{"uuid", []string{testGatewayUUID}, gatewayIDUUID, testGatewayUUID},
		{"nil_uuid", []string{"00000000-0000-0000-0000-000000000000"}, gatewayIDUUID, "00000000-0000-0000-0000-000000000000"},
		{"missing", nil, gatewayIDMissing, ""},
		{"empty", []string{""}, gatewayIDInvalidLength, ""},
		{"same_duplicate", []string{testGatewayHex, testGatewayHex}, gatewayIDDuplicate, ""},
		{"different_duplicate", []string{testGatewayHex, testGatewayUUID}, gatewayIDDuplicate, ""},
		{"combined", []string{testGatewayHex + "," + testGatewayHex}, gatewayIDInvalidLength, ""},
		{"short", []string{testGatewayHex[:31]}, gatewayIDInvalidLength, ""},
		{"long", []string{testGatewayHex + "0"}, gatewayIDInvalidLength, ""},
		{"uuid_short", []string{testGatewayUUID[:35]}, gatewayIDInvalidLength, ""},
		{"uuid_long", []string{testGatewayUUID + "0"}, gatewayIDInvalidLength, ""},
		{"not_hex", []string{"g" + testGatewayHex[1:]}, gatewayIDInvalidFormat, ""},
		{"wrong_hyphen", []string{strings.Replace(testGatewayUUID, "-", "0", 1)}, gatewayIDInvalidFormat, ""},
		{"internal_space", []string{" " + testGatewayHex[1:]}, gatewayIDInvalidFormat, ""},
		{"unicode", []string{"é" + testGatewayHex[2:]}, gatewayIDInvalidFormat, ""},
		{"braces", []string{"{" + testGatewayUUID + "}"}, gatewayIDInvalidLength, ""},
		{"urn", []string{"urn:uuid:" + testGatewayUUID}, gatewayIDInvalidLength, ""},
		{"oversize", []string{strings.Repeat("secret-DO-NOT-RETAIN", 4000)}, gatewayIDInvalidLength, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got := parseGatewayRequestID(tc.values)
			if got.state != tc.state || got.value() != tc.want {
				t.Fatalf("state=%s value=%q", got.state.String(), got.value())
			}
			if !got.valid() && (got.length != 0 || got.bytes != [36]byte{}) {
				t.Fatal("invalid header bytes retained")
			}
		})
	}
	if unsafe.Sizeof(gatewayRequestID{}) != 38 {
		t.Fatal("correlation storage no longer fixed 38 bytes")
	}
}

var gatewayIDSink gatewayRequestID

func TestGatewayRequestIDParsingAllocatesNoMapOrHeaderCopy(t *testing.T) {
	for _, values := range [][]string{{testGatewayHex}, {testGatewayUUID}, {strings.Repeat("x", 65536)}, nil} {
		if n := testing.AllocsPerRun(100, func() { gatewayIDSink = parseGatewayRequestID(values) }); n != 0 {
			t.Fatalf("allocations=%g", n)
		}
	}
}

func TestGatewayRequestIDUsesHTTPHeaderMultiplicity(t *testing.T) {
	for _, tc := range []struct {
		name, headers string
		state         gatewayRequestIDState
	}{
		{"mixed_case", "x-ReQuEsT-iD: " + testGatewayHex + "\r\n", gatewayIDHex32},
		{"ows", "X-Request-ID:\t " + testGatewayUUID + " \t\r\n", gatewayIDUUID},
		{"repeated_case", "X-Request-ID: " + testGatewayHex + "\r\nx-request-id: " + testGatewayHex + "\r\n", gatewayIDDuplicate},
		{"joined", "X-Request-ID: " + testGatewayHex + ", " + testGatewayHex + "\r\n", gatewayIDInvalidLength},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r, err := http.ReadResponse(bufio.NewReader(strings.NewReader("HTTP/1.1 302 Found\r\n"+tc.headers+"Content-Length: 0\r\n\r\n")), nil)
			if err != nil {
				t.Fatal(err)
			}
			defer r.Body.Close()
			if got := parseGatewayRequestID(r.Header.Values("X-Request-ID")); got.state != tc.state {
				t.Fatal(got.state.String())
			}
		})
	}
}

type gatewayIDMemoryConn struct {
	response          *bytes.Reader
	written           bytes.Buffer
	closes, deadlines int
}

func (c *gatewayIDMemoryConn) Read(b []byte) (int, error)  { return c.response.Read(b) }
func (c *gatewayIDMemoryConn) Write(b []byte) (int, error) { return c.written.Write(b) }
func (c *gatewayIDMemoryConn) Close() error                { c.closes++; return nil }
func (c *gatewayIDMemoryConn) LocalAddr() net.Addr {
	return &net.TCPAddr{IP: net.IPv4(127, 0, 0, 2), Port: 43210}
}
func (c *gatewayIDMemoryConn) RemoteAddr() net.Addr {
	return &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 9080}
}
func (c *gatewayIDMemoryConn) SetDeadline(time.Time) error      { c.deadlines++; return nil }
func (c *gatewayIDMemoryConn) SetReadDeadline(time.Time) error  { return nil }
func (c *gatewayIDMemoryConn) SetWriteDeadline(time.Time) error { return nil }

func TestGatewayRequestIDOptInFailureAndBodyEvidence(t *testing.T) {
	for _, required := range []bool{false, true} {
		for _, tc := range []struct {
			name, header, body string
			status, length     int
			reason             string
			state              gatewayRequestIDState
		}{
			{"valid", "X-Request-ID: " + testGatewayHex + "\r\n", "body", 302, 4, "", gatewayIDHex32},
			{"uuid", "X-Request-ID: " + testGatewayUUID + "\r\n", "body", 302, 4, "", gatewayIDUUID},
			{"missing", "", "body", 302, 4, "", gatewayIDMissing},
			{"duplicate", "X-Request-ID: " + testGatewayHex + "\r\nX-Request-ID: " + testGatewayHex + "\r\n", "body", 302, 4, "", gatewayIDDuplicate},
			{"invalid", "X-Request-ID: secret-DO-NOT-RETAIN\r\n", "body", 302, 4, "", gatewayIDInvalidLength},
			{"body_failure", "X-Request-ID: " + testGatewayHex + "\r\n", "x", 302, 4, "BODY_READ_FAILED", gatewayIDHex32},
			{"http_priority", "", "body", 429, 4, "HTTP_STATUS_429", gatewayIDMissing},
			{"wire_limit", "X-Request-ID: " + strings.Repeat("z", 70000) + "\r\n", "", 302, 0, "RESPONSE_WIRE_LIMIT", gatewayIDNotReceived},
		} {
			t.Run(fmt.Sprintf("%t/%s", required, tc.name), func(t *testing.T) {
				c := localConfig("http://127.0.0.1:1")
				c.RequireGatewayRequestID = required
				client, wire := preparedClient(t, c)
				raw := fmt.Sprintf("HTTP/1.1 %d Result\r\nLocation: %s\r\n%sSet-Cookie: OTHER-SECRET-NOT-RETAINED\r\nContent-Length: %d\r\n\r\n%s", tc.status, c.Links[0].OriginURL, tc.header, tc.length, tc.body)
				conn := &gatewayIDMemoryConn{response: bytes.NewReader([]byte(raw))}
				dials, notices := 0, 0
				client.dial = func(context.Context) (net.Conn, error) { dials++; return conn, nil }
				got := client.exchange(wire, c.Links[0].OriginURL, func(reason string, status int) {
					notices++
					if status != tc.status {
						t.Error("status overwritten")
					}
				})
				want := tc.reason
				if want == "" && required && tc.state != gatewayIDHex32 && tc.state != gatewayIDUUID {
					want = "GATEWAY_REQUEST_ID_" + tc.state.String()
				}
				if got.reason != want || got.correct != (want == "") || got.diagnostic.gatewayRequestID.state != tc.state {
					t.Fatalf("reason=%s correct=%t state=%s want=%s", got.reason, got.correct, got.diagnostic.gatewayRequestID.state.String(), want)
				}
				if dials != 1 || !bytes.Equal(conn.written.Bytes(), wire) || conn.deadlines != 1 {
					t.Fatal("changed dial/write/deadline or retried")
				}
				if tc.state != gatewayIDNotReceived && conn.response.Len() != 0 {
					t.Fatal("did not drain body after validation")
				}
				wantNotices := 0
				if want != "" && tc.reason != "BODY_READ_FAILED" && tc.state != gatewayIDNotReceived {
					wantNotices = 1
				}
				if notices != wantNotices {
					t.Fatalf("notices=%d want=%d", notices, wantNotices)
				}
				if tc.name == "body_failure" && got.diagnostic.gatewayRequestID.value() != testGatewayHex {
					t.Fatal("body failure discarded gateway ID")
				}
			})
		}
	}
}

func TestGatewayRequestIDConfigAndJSONConservation(t *testing.T) {
	c := validConfig()
	base := c
	c.RequireGatewayRequestID = true
	if err := c.validate(); err != nil {
		t.Fatal(err)
	}
	wa, aa, ea := requestBytes(base)
	wb, ab, eb := requestBytes(c)
	if ea != nil || eb != nil || aa != ab || !reflect.DeepEqual(wa, wb) || newPlan(base) != newPlan(c) || timeout(base) != timeout(c) || base.RequireGatewayRequestID {
		t.Fatal("correlation changed request plan/wire/default")
	}
	encoded, err := json.Marshal(c)
	if err != nil {
		t.Fatal(err)
	}
	var decoded Config
	if err = json.Unmarshal(encoded, &decoded); err != nil || !decoded.RequireGatewayRequestID {
		t.Fatal("config flag not encoded")
	}
	c.Rate, c.DurationMillis, c.WarmupMillis = 4, 1000, 0
	p := newPlan(c)
	start := time.Unix(1, 0)
	states := []gatewayRequestID{parseGatewayRequestID([]string{testGatewayHex}), parseGatewayRequestID([]string{testGatewayUUID}), parseGatewayRequestID(nil), parseGatewayRequestID([]string{"secret-DO-NOT-RETAIN"})}
	samples := make([]sample, 4)
	traces := make([]arrivalTrace, 4)
	for i, id := range states {
		d := newExchangeDiagnostics(start.Add(p.at(i)), false)
		d.gatewayRequestID = id
		samples[i] = sample{valid: true, received: true, correct: true, status: 302, diagnostic: d, owner: i, source: i}
		traces[i] = arrivalTrace{owner: int32(i), claimed: true, returned: true, returnStarted: p.at(i), returnCompleted: p.at(i)}
	}
	workers := make([]workerState, 4)
	for i := range workers {
		workers[i].source = i
		workers[i].phase[measure] = workerCounters{sent: 1, completed: 1, received: 1, correct: 1}
	}
	got := summarize(c, p, workers, samples, scheduleCounters{scheduled: [2]int{0, 4}, elapsed: time.Second}, stopInfo{Reason: "COMPLETED"}, true, start, time.Second, 2, 16, traces)
	if !got.Conservation || !got.Diagnostics.CoverageComplete || got.SchemaVersion != 1 || got.Diagnostics.SchemaVersion != 2 || got.All.Correct != 4 || got.Config["requireGatewayRequestId"] != true {
		t.Fatal("base counting/schema/config changed")
	}
	idStats := got.GatewayRequestIDs
	if !idStats.AccountingPassed || idStats.RequiredPassed || idStats.ValidHex32 != 1 || idStats.ValidUUID != 1 || idStats.Missing != 1 || idStats.InvalidLength != 1 || idStats.CompletedExchanges != 4 {
		t.Fatalf("ID counts %+v", idStats)
	}
	data, err := json.Marshal(got)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(data, []byte("secret-DO-NOT-RETAIN")) || !bytes.Contains(data, []byte("\"gatewayRequestId\"")) || !bytes.Contains(data, []byte(testGatewayHex)) {
		t.Fatal("JSON omitted valid ID or retained invalid bytes")
	}
	var public Summary
	if err = json.Unmarshal(data, &public); err != nil {
		t.Fatal(err)
	}
	if public.Diagnostics.Requests[0].GatewayRequestID != testGatewayHex || public.Diagnostics.Requests[2].GatewayRequestID != "" || public.Diagnostics.Requests[2].GatewayRequestIDState != "MISSING" {
		t.Fatal("request ID/state JSON mismatch")
	}
	if got.Windows["measure"].Start != start.UTC().Format(time.RFC3339Nano) || got.Windows["measure"].End != start.Add(time.Second).UTC().Format(time.RFC3339Nano) {
		t.Fatal("phase window changed")
	}
}

func TestGatewayRequestIDCounterClassifiesMissingUnknownAndNoResponse(t *testing.T) {
	s := GatewayRequestIDSummary{Required: true}
	for state := gatewayIDNotReceived; state <= gatewayIDInvalidFormat; state++ {
		s.observe(state)
	}
	s.finish(7)
	if !s.AccountingPassed || s.RequiredPassed || s.NotReceived != 1 || s.Duplicate != 1 || s.InvalidFormat != 1 {
		t.Fatalf("incomplete states %+v", s)
	}
	s.observe(gatewayRequestIDState(255))
	s.finish(8)
	if s.AccountingPassed || s.UnknownState != 1 {
		t.Fatal("unknown ID state hidden")
	}
}
