package main

import (
	"context"
	"encoding/json"
	"reflect"
	"sync/atomic"
	"testing"
	"time"
)

func TestCorrelatedOwner4096ConfigPreservesArrivalSourcesAndWire(t *testing.T) {
	baseline := validConfig()
	c := baseline
	c.Workers = 4096
	if err := c.validate(); err != nil {
		t.Fatal(err)
	}
	oldWire, oldAddress, oldErr := requestBytes(baseline)
	newWire, newAddress, newErr := requestBytes(c)
	if oldErr != nil || newErr != nil || oldAddress != newAddress || !reflect.DeepEqual(oldWire, newWire) ||
		!reflect.DeepEqual(newPlan(baseline), newPlan(c)) || !reflect.DeepEqual(baseline.Sources, c.Sources) ||
		len(c.Sources) != 64 || c.PreallocateFDTable {
		t.Fatal("4096 owners changed arrival, sources, wire, or preparation default")
	}
	raw, err := json.Marshal(c)
	if err != nil {
		t.Fatal(err)
	}
	var decoded Config
	if err := json.Unmarshal(raw, &decoded); err != nil || decoded.Workers != 4096 {
		t.Fatalf("4096 owners did not survive config JSON: workers=%d err=%v", decoded.Workers, err)
	}
	for _, count := range []int{4095, 4097, 8192} {
		c.Workers = count
		if err := c.validate(); err == nil || err.Error() != "CONFIG_WORKLOAD_BOUNDS" {
			t.Fatalf("unsupported owner count accepted: workers=%d err=%v", count, err)
		}
	}
}

func TestCorrelatedOwner4096InitializationCancelsBeforeAnyRequest(t *testing.T) {
	c := validConfig()
	c.Workers, c.Rate, c.DurationMillis = 4096, 64, 1000
	if err := c.validate(); err != nil {
		t.Fatal(err)
	}
	// Each of the 64 pools must accept its 64 owners before cancellation is
	// checked. This catches a residual 8/16/32-entry pool without sending HTTP.
	c.BaseURL = "http://127.0.0.1:1"
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	type outcome struct {
		result Summary
		err    error
	}
	done := make(chan outcome, 1)
	go func() {
		result, err := runWorkload(ctx, c)
		done <- outcome{result, err}
	}()
	var got Summary
	select {
	case result := <-done:
		if result.err != nil {
			t.Fatal(result.err)
		}
		got = result.result
	case <-time.After(5 * time.Second):
		t.Fatal("4096 owners blocked while initializing the 64 source pools")
	}
	if got.All.Planned != 64 || got.All.Scheduled != 0 || got.All.Sent != 0 || got.All.Completed != 0 ||
		got.All.Received != 0 || got.All.ClientErrors != 0 || got.All.Cancelled != 64 || !got.Conservation ||
		got.Preparation != nil || got.SourceCountUsed != 0 || len(got.Sources) != 64 ||
		got.Config["workers"] != 4096 || got.Scheduler["maxActiveLimit"] != 4096 || got.Scheduler["handoffSlots"] != 4096 ||
		!got.Diagnostics.CoverageComplete || !got.Diagnostics.AccountingPassed ||
		len(got.Diagnostics.Requests) != 0 || len(got.Diagnostics.OwnerReturns) != 0 || len(got.Diagnostics.UnsentArrivals) != 64 {
		t.Fatalf("pre-cancelled 4096-owner initialization sent work or lost accounting: all=%+v", got.All)
	}
	seen := make([]int, 64)
	for _, row := range got.Diagnostics.UnsentArrivals {
		if row.SourceIndex != row.ArrivalIndex%64 || row.OwnerIndex != -1 || row.AttemptStage != "NONE" ||
			row.AttemptElapsedMillis != nil || row.Disposition != "CANCELLED" {
			t.Fatalf("pre-cancelled arrival acquired an owner or attempt: %+v", row)
		}
		seen[row.SourceIndex]++
	}
	for source, count := range seen {
		row := got.Sources[source]
		if count != 1 || row.Index != source || row.Address != c.Sources[source] || row.Sent != 0 || row.Completed != 0 {
			t.Fatalf("64-source cancellation distribution changed: source=%d count=%d row=%+v", source, count, row)
		}
	}
}

func TestCorrelatedOwner4096SummaryIncludesAllSixtyFourOwnersPerSource(t *testing.T) {
	c := validConfig()
	c.Workers, c.DurationMillis = 4096, 1000
	workers := make([]workerState, c.Workers)
	total := 0
	for owner := range workers {
		n := owner%3 + 1
		workers[owner].source = owner % 64
		workers[owner].phase[measure] = workerCounters{sent: n, completed: n, received: n, correct: n}
		total += n
	}
	c.Rate = total
	got := summarize(c, newPlan(c), workers, nil, scheduleCounters{scheduled: [2]int{0, total}},
		stopInfo{Reason: "COMPLETED"}, true, time.Now(), time.Second, 2, 16)
	if len(got.Sources) != 64 || got.All.Sent != total || got.All.Correct != total || got.Scheduler["maxActiveLimit"] != 4096 {
		t.Fatal("4096-owner aggregate omitted owners")
	}
	for source, row := range got.Sources {
		want, owners := 0, 0
		for owner := source; owner < c.Workers; owner += 64 {
			want += owner%3 + 1
			owners++
		}
		if owners != 64 || row.Sent != want || row.Completed != want || row.Correct != want {
			t.Fatalf("source=%d owners=%d row=%+v want=%d", source, owners, row, want)
		}
	}
}

func TestCorrelatedOwner4096AdmissionRetainsEightyMillisecondsAndSourceCapacity(t *testing.T) {
	c := validConfig()
	c.Workers, c.Rate, c.DurationMillis = 4096, 1000, 500
	p := newPlan(c)
	for _, lag := range []time.Duration{80 * time.Millisecond, 80*time.Millisecond + time.Nanosecond} {
		elapsed := p.at(63) + lag
		idle, other := make(chan int, 64), make(chan int, 64)
		for i := 0; i < 63; i++ {
			idle <- i*64 + 63
		}
		for i := 0; i < 64; i++ {
			other <- i * 64
		}
		trace := arrivalTrace{claimed: true, owner: 4095}
		var active atomic.Int64
		active.Store(4096)
		w := workerState{source: 63}
		started := w.admitAt(p, job{index: 63, p: measure}, elapsed, 0, &trace, &active, idle)
		if started != (lag == 80*time.Millisecond) {
			t.Fatal("4096-owner admission changed the 80 ms inclusive boundary")
		}
		if !started && (trace.reason != dropWorkerLate || trace.sourceIdle != 63 || trace.globalActive != 4096) {
			t.Fatalf("high owner admission truncated diagnostics: %+v", trace)
		}
		returnOwner(4095, idle, &active, time.Now().Add(-elapsed), &trace)
		if active.Load() != 4095 || len(idle) != 64 || len(other) != 64 || !trace.returned {
			t.Fatal("return changed the 64-owner per-source capacity or another source pool")
		}
	}
}

func TestCorrelatedOwner4095AndFullPoolDiagnosticsSurviveJSON(t *testing.T) {
	c := validConfig()
	c.Workers, c.Rate, c.DurationMillis = 4096, 64, 1000
	p, start := newPlan(c), time.Now()
	samples, traces := make([]sample, p.total), make([]arrivalTrace, p.total)
	for index := range traces {
		traces[index].reject(dropSourceBusy, p.at(index), 4096, 0)
	}
	traces[0] = arrivalTrace{claimed: true, returned: true, owner: 4032, returnStarted: 81 * time.Millisecond, returnCompleted: 82 * time.Millisecond}
	traces[0].reject(dropWorkerLate, 80*time.Millisecond+time.Nanosecond, 4096, 63)
	traces[1] = arrivalTrace{}
	traces[1].windowClosed(time.Second, 4096, 64)
	samples[63] = sample{valid: true, owner: 4095, source: 63, correct: true, received: true, status: 302,
		diagnostic: newExchangeDiagnostics(start.Add(p.at(63)), false)}
	traces[63] = arrivalTrace{claimed: true, returned: true, owner: 4095, returnStarted: time.Second, returnCompleted: time.Second + 1}
	all := PhaseSummary{Planned: 64, Scheduled: 64, Sent: 1, Completed: 1, Received: 1, Correct: 1, Dropped: 63}
	d := summarizeDiagnostics(c, p, samples, traces, all, 1, stopInfo{Reason: "COMPLETED"}, start)
	if !d.AccountingPassed || !d.CoverageComplete || len(d.Requests) != 1 || len(d.OwnerReturns) != 2 || len(d.UnsentArrivals) != 63 {
		t.Fatal("4096-owner diagnostics lost coverage or event accounting")
	}
	raw, err := json.Marshal(d)
	if err != nil {
		t.Fatal(err)
	}
	var decoded DiagnosticReport
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded.Requests[0].OwnerIndex != 4095 || decoded.Requests[0].SourceIndex != 63 ||
		decoded.OwnerReturns[0].OwnerIndex != 4032 || decoded.OwnerReturns[1].OwnerIndex != 4095 ||
		decoded.UnsentArrivals[0].SourceIdle == nil || *decoded.UnsentArrivals[0].SourceIdle != 63 ||
		decoded.UnsentArrivals[0].GlobalActive == nil || *decoded.UnsentArrivals[0].GlobalActive != 4096 ||
		decoded.UnsentArrivals[1].SourceIdle == nil || *decoded.UnsentArrivals[1].SourceIdle != 64 ||
		decoded.UnsentArrivals[1].GlobalActive == nil || *decoded.UnsentArrivals[1].GlobalActive != 4096 ||
		decoded.UnsentArrivals[1].AttemptElapsedMillis != nil {
		t.Fatal("4095 owner, 4096 global active, or 63/64 source idle truncated in JSON")
	}
}

func TestCorrelatedOwner4096RetainsDiagnosticAndArrivalBoundsWithoutLargeAllocation(t *testing.T) {
	if maxStartLateness != 80*time.Millisecond || maxDiagnosticArrivals != 1_000_000 || maxSamples != 1_200_000 {
		t.Fatal("owner expansion changed the original timing or evidence budgets")
	}
	for _, tc := range []struct{ total, want int }{
		{0, 0}, {999_999, 999_999}, {1_000_000, 1_000_000}, {1_000_001, 1_000_000}, {1_200_000, 1_000_000},
	} {
		if got := diagnosticPrefixSize(tc.total); got != tc.want {
			t.Fatalf("prefix(%d)=%d want=%d", tc.total, got, tc.want)
		}
	}
	c := validConfig()
	c.Workers, c.Rate, c.DurationMillis = 4096, 20_000, 60_000
	if err := c.validate(); err != nil || newPlan(c).total != 1_200_000 {
		t.Fatalf("original arrival budget boundary rejected: err=%v", err)
	}
	c.DurationMillis++
	if err := c.validate(); err == nil || err.Error() != "CONFIG_SAMPLE_LIMIT" {
		t.Fatalf("owner expansion removed the total sample budget: err=%v", err)
	}
}
