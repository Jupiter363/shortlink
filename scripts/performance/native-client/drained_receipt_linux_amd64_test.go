package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func receiptConfig(t *testing.T) Config {
	c := validConfig()
	c.RunID, c.Label = "drain-test", "finite"
	c.RequestsDrainedReceipt = filepath.Join(t.TempDir(), "drained.json")
	return c
}

func TestDrainStartTicksHandlesSpacesAndNestedParentheses(t *testing.T) {
	n, err := processStartTicks([]byte("123 (native (worker) name) R " + strings.Repeat("0 ", 18) + "1234 0"))
	if err != nil || n != 1234 {
		t.Fatal(n, err)
	}
	if _, err := processStartTicks([]byte("123 malformed")); err == nil {
		t.Fatal("malformed identity accepted")
	}
}

func TestDrainReceiptAtomicExclusiveAndFailureCountsAreNotPass(t *testing.T) {
	c := receiptConfig(t)
	w, err := prepareDrainReceipt(c)
	if err != nil {
		t.Fatal(err)
	}
	defer w.cleanup()
	if _, err := os.Stat(c.RequestsDrainedReceipt); !os.IsNotExist(err) {
		t.Fatal("partial receipt visible")
	}
	workers := make([]workerState, c.Workers)
	workers[0].phase[measure].sent = 1
	workers[0].phase[measure].completed = 1
	counts := scheduleCounters{}
	counts.scheduled[measure] = 1
	if err := w.publish(c, workers, counts, newPlan(c), stopInfo{Reason: "HTTP_503"}, false, 0, time.Millisecond); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(c.RequestsDrainedReceipt)
	if err != nil {
		t.Fatal(err)
	}
	var r drainedReceipt
	if json.Unmarshal(raw, &r) != nil || len(raw) > 4096 || r.Errors != 1 || r.MeasurementComplete || !r.RequestsDrained || r.PID != os.Getpid() || r.StartTicks == 0 || r.DrainedMonotonicNanos <= 0 {
		t.Fatalf("invalid failure receipt: %s", raw)
	}
	if strings.Contains(string(raw), c.Cookie) || strings.Contains(string(raw), c.Links[0].OriginURL) {
		t.Fatal("private input leaked")
	}
	if err := w.publish(c, workers, counts, newPlan(c), stopInfo{Reason: "COMPLETED"}, true, 0, time.Millisecond); err == nil {
		t.Fatal("receipt overwritten")
	}
	if again, _ := os.ReadFile(c.RequestsDrainedReceipt); string(again) != string(raw) {
		t.Fatal("old receipt modified")
	}
	if old, err := prepareDrainReceipt(c); err == nil {
		old.cleanup()
		t.Fatal("old receipt accepted before workload")
	}
}

func TestDrainReceiptRejectsUnfinishedRequestAndPublicationRace(t *testing.T) {
	for _, mode := range []string{"active", "race"} {
		t.Run(mode, func(t *testing.T) {
			c := receiptConfig(t)
			w, err := prepareDrainReceipt(c)
			if err != nil {
				t.Fatal(err)
			}
			defer w.cleanup()
			workers := make([]workerState, c.Workers)
			active := int64(1)
			if mode == "race" {
				active = 0
				if os.WriteFile(c.RequestsDrainedReceipt, []byte("old evidence"), 0600) != nil {
					t.Fatal("seed failed")
				}
			}
			if w.publish(c, workers, scheduleCounters{}, newPlan(c), stopInfo{Reason: "COMPLETED"}, true, active, 0) == nil {
				t.Fatal("invalid drain published")
			}
			if mode == "race" {
				raw, _ := os.ReadFile(c.RequestsDrainedReceipt)
				if string(raw) != "old evidence" {
					t.Fatal("raced evidence overwritten")
				}
			}
		})
	}
}

func TestDrainExternalStopStillProducesOriginalIncompleteSummary(t *testing.T) {
	c := receiptConfig(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	s, err := runWorkload(ctx, c)
	if err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(c.RequestsDrainedReceipt)
	if err != nil {
		t.Fatal(err)
	}
	var r drainedReceipt
	if json.Unmarshal(raw, &r) != nil {
		t.Fatal("JSON")
	}
	if s.MeasurementComplete || r.MeasurementComplete || s.Stop.Reason != "EXTERNAL_STOP" || r.StopReason != s.Stop.Reason || r.Sent != 0 || s.All.Cancelled != r.Cancelled || !r.RequestsDrained {
		t.Fatal("drain converted external stop into success")
	}
}

func TestDrainHTTPFailureRetainsCompleteOriginalFailureSummary(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(503) }))
	defer server.Close()
	c := localConfig(server.URL)
	c.RunID = "http-failure"
	c.Label = "drain"
	c.RequestsDrainedReceipt = filepath.Join(t.TempDir(), "receipt.json")
	s, err := runWorkload(context.Background(), c)
	if err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(c.RequestsDrainedReceipt)
	if err != nil {
		t.Fatal(err)
	}
	var r drainedReceipt
	if json.Unmarshal(raw, &r) != nil {
		t.Fatal("JSON")
	}
	if s.All.Errors == 0 || s.All.Status503 == 0 || r.Errors != s.All.Errors || r.Completed != s.All.Completed || r.MeasurementComplete || !s.Conservation {
		t.Fatal("HTTP failure evidence lost")
	}
}
