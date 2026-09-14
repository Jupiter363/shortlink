package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type diagnosticPortConn struct {
	net.Conn
	reads        *atomic.Int64
	writeStarted chan struct{}
	readStarted  chan struct{}
	firstWrite   sync.Once
}

func (c *diagnosticPortConn) LocalAddr() net.Addr { c.reads.Add(1); return &net.TCPAddr{Port: 43210} }
func (c *diagnosticPortConn) Write(b []byte) (int, error) {
	c.firstWrite.Do(func() { close(c.writeStarted) })
	return c.Conn.Write(b)
}
func (c *diagnosticPortConn) Read(b []byte) (int, error) {
	c.readStarted <- struct{}{}
	return c.Conn.Read(b)
}

func TestDiagnosticPipeSeparatesStagesAndCachesConnectionIdentity(t *testing.T) {
	c := localConfig("http://127.0.0.1:1")
	c.RequestTimeoutMillis = 2000
	client, wire := preparedClient(t, c)
	left, right := net.Pipe()
	defer right.Close()
	var reads atomic.Int64
	writeStarted := make(chan struct{})
	readStarted := make(chan struct{}, 4)
	client.dial = func(context.Context) (net.Conn, error) {
		<-time.After(12 * time.Millisecond)
		return &diagnosticPortConn{Conn: left, reads: &reads, writeStarted: writeStarted, readStarted: readStarted}, nil
	}
	serverDone := make(chan error, 1)
	go func() {
		// Each deliberate pause belongs to exactly one blocking phase.
		<-writeStarted
		<-time.After(12 * time.Millisecond)
		if err := consumeRequest(right); err != nil {
			serverDone <- err
			return
		}
		<-readStarted
		<-time.After(12 * time.Millisecond)
		if _, err := io.WriteString(right, "HTTP/1.1 302 Found\r\nLocation: "+c.Links[0].OriginURL+"\r\nContent-Length: 1\r\n\r\n"); err != nil {
			serverDone <- err
			return
		}
		<-readStarted
		<-time.After(12 * time.Millisecond)
		if _, err := io.WriteString(right, "x"); err != nil {
			serverDone <- err
			return
		}
		if err := consumeRequest(right); err != nil {
			serverDone <- err
			return
		}
		_, err := io.WriteString(right, "HTTP/1.1 302 Found\r\nLocation: "+c.Links[0].OriginURL+"\r\nContent-Length: 0\r\n\r\n")
		serverDone <- err
	}()
	first := client.exchange(wire, c.Links[0].OriginURL, nil)
	if !first.correct {
		t.Fatalf("first exchange failed: %s", first.reason)
	}
	for i := 0; i < 4; i++ {
		stage := first.diagnostic.stage(i)
		if stage.State != "COMPLETED" || stage.Millis == nil || *stage.Millis < 5 {
			t.Fatalf("stage %d did not retain delayed phase: %+v", i, stage)
		}
	}
	if !first.diagnostic.freshConnection || first.diagnostic.localPort != 43210 || first.diagnostic.connectionSequence != 1 || first.diagnostic.requestSequence != 1 {
		t.Fatal("first connection identity missing")
	}
	second := client.exchange(wire, c.Links[0].OriginURL, nil)
	if !second.correct || second.diagnostic.freshConnection || second.diagnostic.stage(diagConnect).Millis != nil || second.diagnostic.stage(diagConnect).State != "NOT_ATTEMPTED" || second.diagnostic.requestSequence != 2 || second.diagnostic.connectionSequence != 1 || reads.Load() != 1 {
		t.Fatal("reused connection diagnostics changed identity or redialed")
	}
	if err := <-serverDone; err != nil {
		t.Fatal(err)
	}
}

func TestDiagnosticFailurePreservesCompletedAndUnenteredStages(t *testing.T) {
	for _, failure := range []string{"connect", "write", "header", "body"} {
		t.Run(failure, func(t *testing.T) {
			c := localConfig("http://127.0.0.1:1")
			c.RequestTimeoutMillis = 1000
			client, wire := preparedClient(t, c)
			left, right := net.Pipe()
			defer right.Close()
			calls := 0
			writeCalls := 0
			client.dial = func(context.Context) (net.Conn, error) {
				calls++
				if failure == "connect" {
					return nil, fmt.Errorf("private URL token must not be copied")
				}
				if failure == "write" {
					return &failingWriteConn{Conn: left, calls: &writeCalls}, nil
				}
				return left, nil
			}
			defer left.Close()
			if failure == "header" || failure == "body" {
				go func() {
					defer right.Close()
					if consumeRequest(right) != nil {
						return
					}
					if failure == "body" {
						_, _ = io.WriteString(right, "HTTP/1.1 302 Found\r\nLocation: "+c.Links[0].OriginURL+"\r\nContent-Length: 2\r\n\r\nx")
					}
				}()
			}
			got := client.exchange(wire, c.Links[0].OriginURL, nil)
			failed := map[string]int{"connect": 0, "write": 1, "header": 2, "body": 3}[failure]
			if !got.clientError || got.correct || calls != 1 || got.received != (failure == "body") {
				t.Fatal("failure/retry/receipt semantics changed")
			}
			for i := 0; i < 4; i++ {
				stage := got.diagnostic.stage(i)
				want := "COMPLETED"
				if i == failed {
					want = "FAILED"
				}
				if i > failed {
					want = "NOT_ATTEMPTED"
				}
				if stage.State != want || (stage.Millis == nil) != (i > failed) {
					t.Fatalf("phase %d want %s got %+v", i, want, stage)
				}
			}
			encoded, _ := json.Marshal(RequestDiagnostic{FailureReason: got.reason, Connect: got.diagnostic.stage(0), Write: got.diagnostic.stage(1), Header: got.diagnostic.stage(2), Body: got.diagnostic.stage(3)})
			if strings.Contains(string(encoded), "private") || strings.Contains(string(encoded), "http://") {
				t.Fatal("sensitive error copied")
			}
		})
	}
}

func TestDiagnosticSummaryRetainsArrivalOwnershipAndOriginalCounts(t *testing.T) {
	c := localConfig("http://127.0.0.1:1")
	c.Rate = 100
	c.DurationMillis = 10
	p := newPlan(c)
	start := time.Now()
	d := newExchangeDiagnostics(start.Add(time.Millisecond), true)
	d.localPort = 43210
	d.connectionSequence = 1
	d.requestSequence = 1
	d.durations = [4]time.Duration{1, 2, 3, 4}
	d.completed = [4]bool{true, true, true, true}
	w := workerState{source: 0}
	w.phase[measure] = workerCounters{sent: 1, completed: 1, received: 1, correct: 1}
	s := sample{valid: true, roundtrip: 2, scheduledCompletion: 3, diagnostic: d, owner: 7, source: 0, status: 302, received: true, correct: true, dispatched: time.Millisecond, workerStarted: time.Millisecond}
	counts := scheduleCounters{scheduled: [2]int{0, 1}, elapsed: 10 * time.Millisecond}
	traces := []arrivalTrace{{claimed: true, owner: 7, returned: true, returnStarted: 3 * time.Millisecond, returnCompleted: 3*time.Millisecond + 1}}
	got := summarize(c, p, []workerState{w}, []sample{s}, counts, stopInfo{Reason: "COMPLETED"}, true, start, 10*time.Millisecond, 4, 4, traces)
	if !got.Conservation || got.All.Sent != 1 || got.All.Correct != 1 || got.All.Dropped != 0 || got.Diagnostics.CapturedRequests != 1 {
		t.Fatal("diagnostic changed counter contract")
	}
	r := got.Diagnostics.Requests[0]
	if r.ArrivalIndex != 0 || r.OwnerIndex != 7 || r.SourceIndex != 0 || r.Phase != "measure" || r.ExchangeStartedElapsedMillis != 1 || r.LocalPort != 43210 || !r.Received || !r.Correct {
		t.Fatalf("missing request correlation: %+v", r)
	}
	encoded, _ := json.Marshal(got.Diagnostics)
	if strings.Contains(string(encoded), "Cookie") || strings.Contains(string(encoded), "http") || strings.Contains(string(encoded), "Location") {
		t.Fatal("diagnostic payload leaked wire fields")
	}
}
