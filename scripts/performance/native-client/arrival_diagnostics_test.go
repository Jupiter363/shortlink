package main

import (
	"context"
	"encoding/json"
	"sync/atomic"
	"testing"
	"time"
)

func TestWorkerAdmissionDiagnosticsDistinguishLateAndWindowFromBusy(t *testing.T) {
	for _, tc := range []struct {
		name         string
		at           time.Duration
		wantReason   string
		late, window int
	}{
		{"late", 80*time.Millisecond + time.Nanosecond, "WORKER_LATE", 1, 0},
		{"window", 500 * time.Millisecond, "WORKER_WINDOW_CLOSED", 0, 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			c := localConfig("")
			c.Rate, c.DurationMillis = 1000, 500
			p := newPlan(c)
			var active atomic.Int64
			active.Store(1)
			idle := make(chan int, 1)
			w := workerState{source: 0}
			traces := make([]arrivalTrace, p.total)
			traces[0].claimed, traces[0].owner = true, 7
			if w.admitAt(p, job{index: 0, p: measure}, tc.at, 0, &traces[0], &active, idle) {
				t.Fatal("expired owner started a network exchange")
			}
			if w.phase[measure].sent != 0 || w.phase[measure].dropped != 1 || w.phase[measure].lateDropped != tc.late || w.phase[measure].windowDropped != tc.window {
				t.Fatalf("admission counters misclassified: %+v", w.phase[measure])
			}
			start := time.Now().Add(-tc.at)
			returnOwner(7, idle, &active, start, &traces[0])
			got := summarize(c, p, []workerState{w}, make([]sample, p.total), scheduleCounters{scheduled: [2]int{0, 1}}, stopInfo{Reason: "EXTERNAL_STOP", ElapsedMillis: 501}, false, start, 501*time.Millisecond, 2, 2, traces)
			if !got.Conservation || !got.Diagnostics.AccountingPassed || !got.Diagnostics.CoverageComplete || got.All.Sent != 0 || got.All.Dropped != 1 || got.All.Cancelled != 499 || len(got.Diagnostics.Requests) != 0 {
				t.Fatalf("worker rejection fabricated a sent request: %+v", got.All)
			}
			d := got.Diagnostics.UnsentArrivals[0]
			if d.Reason != tc.wantReason || d.AttemptStage != "WORKER_ADMISSION" || d.OwnerIndex != 7 || d.AttemptElapsedMillis == nil || *d.AttemptElapsedMillis != float64(tc.at)/float64(time.Millisecond) || d.GlobalActive == nil || *d.GlobalActive != 1 || d.SourceIdle == nil || *d.SourceIdle != 0 {
				t.Fatalf("missing worker admission cause/snapshot: %+v", d)
			}
			if len(got.Diagnostics.OwnerReturns) != 1 || got.Diagnostics.OwnerReturns[0].Sent || got.Diagnostics.OwnerReturns[0].OwnerIndex != 7 || active.Load() != 0 || <-idle != 7 {
				t.Fatal("rejected worker did not return exactly one slot")
			}
		})
	}
}

func TestOwnerReturnTimestampBracketsChannelAvailability(t *testing.T) {
	var active atomic.Int64
	active.Store(1)
	idle := make(chan int, 1)
	idle <- 99 // Force a controlled send wait; no runtime scheduler is involved.
	start := time.Now()
	trace := arrivalTrace{claimed: true, owner: 7}
	done := make(chan struct{})
	go func() { returnOwner(7, idle, &active, start, &trace); close(done) }()
	deadline := time.NewTimer(time.Second)
	defer deadline.Stop()
	for active.Load() != 0 {
		select {
		case <-deadline.C:
			t.Fatal("return did not reach slot release")
		default:
			time.Sleep(time.Millisecond)
		}
	}
	select {
	case <-done:
		t.Fatal("return completed before an idle slot was available")
	default:
	}
	beforeAvailability := time.Since(start)
	if <-idle != 99 {
		t.Fatal("fixture slot changed")
	}
	select {
	case <-done:
	case <-deadline.C:
		t.Fatal("return did not finish")
	}
	if <-idle != 7 || !trace.returned || trace.returnStarted > beforeAvailability || trace.returnCompleted < beforeAvailability || trace.returnStarted > trace.returnCompleted {
		t.Fatalf("availability must be bracketed, not equated with HTTP completion: %+v", trace)
	}
}

func TestDiagnosticsCoveragePrefixAndCancellationHaveNoFakeRequest(t *testing.T) {
	if diagnosticPrefixSize(1_000_001) != 1_000_000 || diagnosticPrefixSize(999_999) != 999_999 || traceAt(nil, 0) != nil {
		t.Fatal("diagnostic array cap missing")
	}
	c := localConfig("")
	c.Rate, c.DurationMillis = 10, 500
	p := newPlan(c)
	start := time.Now()
	samples := make([]sample, p.total)
	samples[0] = sample{valid: true, owner: 0, source: 0, diagnostic: newExchangeDiagnostics(start, true)}
	traces := make([]arrivalTrace, 3) // A reduced prefix exercises the exact cap path cheaply.
	traces[0] = arrivalTrace{claimed: true, returned: true, owner: 0, returnStarted: time.Millisecond, returnCompleted: time.Millisecond + 1}
	traces[1].reject(dropSourceBusy, 100*time.Millisecond, 1, 0)
	all := PhaseSummary{Planned: 5, Scheduled: 2, Sent: 1, Completed: 1, Dropped: 1, Cancelled: 3}
	d := summarizeDiagnostics(c, p, samples, traces, all, 0, stopInfo{Reason: "EXTERNAL_STOP", ElapsedMillis: 101}, start)
	if !d.AccountingPassed || d.CoverageComplete || d.CoveredArrivalPrefix != 3 || d.OmittedArrivals != 2 || d.CapturedRequests != 1 || d.CapturedUnsentArrivals != 2 || d.OmittedUnsentArrivals != 2 || d.CapturedOwnerReturns != 1 {
		t.Fatalf("coverage omission not explicit: %+v", d)
	}
	u := d.UnsentArrivals[1]
	if u.Reason != "CANCELLED_AFTER_STOP" || u.Disposition != "CANCELLED" || u.AttemptElapsedMillis != nil || u.GlobalActive != nil || u.SourceIdle != nil || u.OwnerIndex != -1 || u.DecisionElapsedMillis == nil || *u.DecisionElapsedMillis != 101 {
		t.Fatalf("unattempted arrival invented timing/owner: %+v", u)
	}
	encoded, err := json.Marshal(d)
	if err != nil || !json.Valid(encoded) {
		t.Fatal("bounded report is not valid JSON")
	}
}

func TestDiagnosticsMissingCoveredReturnAndUnclassifiedDropFailCoverage(t *testing.T) {
	c := localConfig("")
	c.Rate, c.DurationMillis = 100, 20
	p := newPlan(c)
	start := time.Now()
	samples := []sample{{valid: true, diagnostic: newExchangeDiagnostics(start, true)}, {}}
	traces := []arrivalTrace{{claimed: true}, {}}
	d := summarizeDiagnostics(c, p, samples, traces, PhaseSummary{Planned: 2, Scheduled: 2, Sent: 1, Completed: 1, Dropped: 1}, 0, stopInfo{Reason: "COMPLETED"}, start)
	if d.CoverageComplete || d.AccountingPassed || d.UnclassifiedArrivals != 1 || d.MissingOwnerReturnsInCoveredPrefix != 1 || d.UnsentArrivals[0].Reason != "UNCLASSIFIED_DIAGNOSTIC_GAP" {
		t.Fatal("missing evidence silently considered complete")
	}
}

func TestCancelledBeforeRunCapturesAllUnsentWithoutAttempt(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	c := localConfig("http://127.0.0.1:1")
	got, err := runWorkload(ctx, c)
	if err != nil {
		t.Fatal(err)
	}
	if !got.Diagnostics.CoverageComplete || !got.Diagnostics.AccountingPassed || got.Diagnostics.CapturedRequests != 0 || got.Diagnostics.CapturedUnsentArrivals != got.All.Planned || got.Diagnostics.CapturedOwnerReturns != 0 {
		t.Fatal("pre-run cancellation coverage incomplete")
	}
	for _, d := range got.Diagnostics.UnsentArrivals {
		if d.Disposition != "CANCELLED" || d.AttemptStage != "NONE" || d.AttemptElapsedMillis != nil || d.GlobalActive != nil || d.SourceIdle != nil || d.OwnerIndex != -1 {
			t.Fatal("cancelled arrival invented dispatch")
		}
	}
}

func TestExistingWarmupConfigurationAndDefaultRemainUnchanged(t *testing.T) {
	c := validConfig()
	if c.WarmupMillis != 0 || c.Workers != 512 || len(c.Sources) != 64 || newPlan(c).warmN != 0 {
		t.Fatal("zero-warmup/source defaults changed")
	}
	c.WarmupMillis, c.WarmupStartRate = 30_000, 10
	if err := c.validate(); err != nil {
		t.Fatal(err)
	}
	p := newPlan(c)
	if p.warmN != 1110 || p.at(p.warmN) != 30*time.Second || p.phase(p.warmN-1) != warmup || p.phase(p.warmN) != measure {
		t.Fatal("explicit linear warmup phase changed")
	}
	c.WarmupMillis = 5000
	if c.validate() == nil {
		t.Fatal("unapproved new warmup duration silently accepted")
	}
}
