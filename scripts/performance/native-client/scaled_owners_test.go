package main

import (
	"context"
	"encoding/json"
	"reflect"
	"strconv"
	"sync/atomic"
	"testing"
	"time"
)

func TestScaledOwnerConfigPreservesSourcesArrivalAndWire(t *testing.T) {
	baseline := validConfig()
	basePlan := newPlan(baseline)
	baseWire, baseAddress, err := requestBytes(baseline)
	if err != nil {
		t.Fatal(err)
	}
	for _, workers := range []int{512, 1024, 2048} {
		t.Run(strconv.Itoa(workers), func(t *testing.T) {
			c := validConfig()
			c.Workers = workers
			if err := c.validate(); err != nil {
				t.Fatal(err)
			}
			wire, address, err := requestBytes(c)
			if err != nil || address != baseAddress || !reflect.DeepEqual(wire, baseWire) || !reflect.DeepEqual(newPlan(c), basePlan) {
				t.Fatal("worker scaling changed HTTP bytes, arrival plan, or endpoint")
			}
			if len(c.Sources) != 64 || !reflect.DeepEqual(c.Sources, baseline.Sources) || c.PreallocateFDTable {
				t.Fatal("source fixture or opt-in preparation changed")
			}
		})
	}
	for _, workers := range []int{-1, 0, 1, 513, 1023, 1025, 2049} {
		c := validConfig()
		c.Workers = workers
		if err := c.validate(); err == nil || err.Error() != "CONFIG_WORKLOAD_BOUNDS" {
			t.Fatalf("unsupported owner count accepted: %d err=%v", workers, err)
		}
	}
}

func TestScaledOwnerInitializationHasExactSourcePoolsAndCancelsWithoutHTTP(t *testing.T) {
	for _, workers := range []int{512, 1024, 2048} {
		t.Run(strconv.Itoa(workers), func(t *testing.T) {
			c := validConfig()
			c.Workers = workers
			c.Rate, c.DurationMillis = 64, 1000
			if err := c.validate(); err != nil {
				t.Fatal(err)
			}
			// All per-source pools are prefilled before the cancellation gate.
			// A residual fixed capacity of eight would deadlock above 512 owners.
			// An already-cancelled context prevents every request/connection.
			c.BaseURL = "http://127.0.0.1:1"
			ctx, cancel := context.WithCancel(context.Background())
			cancel()
			type outcome struct {
				result Summary
				err    error
			}
			done := make(chan outcome, 1)
			go func() { result, err := runWorkload(ctx, c); done <- outcome{result, err} }()
			var got Summary
			select {
			case result := <-done:
				if result.err != nil {
					t.Fatal(result.err)
				}
				got = result.result
			case <-time.After(5 * time.Second):
				t.Fatal("owner/source initialization blocked before cancellation")
			}
			if got.All.Sent != 0 || got.All.Planned != 64 || got.All.Cancelled != 64 || !got.Conservation ||
				!got.Diagnostics.CoverageComplete || !got.Diagnostics.AccountingPassed || len(got.Diagnostics.Requests) != 0 ||
				got.Config["workers"] != workers || got.Scheduler["maxActiveLimit"] != workers || got.Scheduler["handoffSlots"] != workers {
				t.Fatalf("scaled pre-run cancellation lost workers or sent a request: %+v", got.All)
			}
			seen := make([]int, 64)
			for _, row := range got.Diagnostics.UnsentArrivals {
				if row.SourceIndex != row.ArrivalIndex%64 || row.OwnerIndex != -1 || row.AttemptStage != "NONE" {
					t.Fatal("arrival source order or unsent ownership changed")
				}
				seen[row.SourceIndex]++
			}
			for source, count := range seen {
				if count != 1 || got.Sources[source].Index != source || got.Sources[source].Address != c.Sources[source] {
					t.Fatal("64-source distribution changed")
				}
			}
		})
	}
}

func TestScaledOwnerSummaryIncludesEveryOwnerPerSource(t *testing.T) {
	for _, count := range []int{512, 1024, 2048} {
		c := validConfig()
		c.Workers, c.DurationMillis = count, 1000
		workers := make([]workerState, count)
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
		if len(got.Sources) != 64 || got.All.Sent != total || got.All.Correct != total || got.Scheduler["maxActiveLimit"] != count {
			t.Fatalf("truncated aggregate for %d owners", count)
		}
		for source := range got.Sources {
			want := 0
			for owner := source; owner < count; owner += 64 {
				want += owner%3 + 1
			}
			row := got.Sources[source]
			if row.Sent != want || row.Completed != want || row.Correct != want {
				t.Fatalf("ownerCount=%d source=%d got=%+v want=%d", count, source, row, want)
			}
		}
	}
}

func TestScaledOwnerAdmissionKeepsEightyMillisecondBoundaryAndSourceReturn(t *testing.T) {
	for _, count := range []int{512, 1024, 2048} {
		for _, elapsed := range []time.Duration{80 * time.Millisecond, 80*time.Millisecond + time.Nanosecond} {
			c := validConfig()
			c.Workers, c.Rate, c.DurationMillis = count, 1000, 500
			perSource := count / 64
			idle, other := make(chan int, perSource), make(chan int, perSource)
			for i := 0; i < perSource-1; i++ {
				idle <- i * 64
			}
			for i := 0; i < perSource; i++ {
				other <- i*64 + 1
			}
			owner := count - 64
			trace := arrivalTrace{claimed: true, owner: int32(owner)}
			var active atomic.Int64
			active.Store(int64(count))
			w := workerState{source: 0}
			started := w.admitAt(newPlan(c), job{index: 0, p: measure}, elapsed, 0, &trace, &active, idle)
			if started != (elapsed == 80*time.Millisecond) {
				t.Fatal("worker scaling changed the original admission deadline")
			}
			if !started && (trace.reason != dropWorkerLate || int(trace.sourceIdle) != perSource-1 || int(trace.globalActive) != count) {
				t.Fatalf("scaled admission diagnostics truncated: %+v", trace)
			}
			returnOwner(owner, idle, &active, time.Now().Add(-elapsed), &trace)
			if active.Load() != int64(count-1) || len(idle) != perSource || len(other) != perSource || !trace.returned {
				t.Fatal("return exceeded or borrowed a source's configured owner budget")
			}
		}
	}
}

func TestScaledOwnerDiagnosticsRetainHighOwnerIDsAndSourceIdle(t *testing.T) {
	c := validConfig()
	c.Workers, c.Rate, c.DurationMillis = 2048, 64, 1000
	p, start := newPlan(c), time.Now()
	samples, traces := make([]sample, p.total), make([]arrivalTrace, p.total)
	for index := range traces {
		traces[index].reject(dropSourceBusy, p.at(index), 2048, 0)
	}
	traces[0] = arrivalTrace{claimed: true, returned: true, owner: 1984, returnStarted: 81 * time.Millisecond, returnCompleted: 82 * time.Millisecond}
	traces[0].reject(dropWorkerLate, 80*time.Millisecond+time.Nanosecond, 2048, 31)
	traces[1] = arrivalTrace{}
	traces[1].windowClosed(time.Second, 2048, 32)
	samples[63] = sample{valid: true, owner: 2047, source: 63, correct: true, received: true, status: 302,
		diagnostic: newExchangeDiagnostics(start.Add(p.at(63)), false)}
	traces[63] = arrivalTrace{claimed: true, returned: true, owner: 2047, returnStarted: time.Second, returnCompleted: time.Second + 1}
	all := PhaseSummary{Planned: 64, Scheduled: 64, Sent: 1, Completed: 1, Received: 1, Correct: 1, Dropped: 63}
	d := summarizeDiagnostics(c, p, samples, traces, all, 1, stopInfo{Reason: "COMPLETED"}, start)
	if !d.AccountingPassed || !d.CoverageComplete || len(d.Requests) != 1 || len(d.OwnerReturns) != 2 || len(d.UnsentArrivals) != 63 {
		t.Fatalf("high-owner coverage or event accounting changed: %+v", d)
	}
	raw, err := json.Marshal(d)
	if err != nil {
		t.Fatal(err)
	}
	var decoded DiagnosticReport
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded.Requests[0].OwnerIndex != 2047 || decoded.Requests[0].SourceIndex != 63 || decoded.OwnerReturns[0].OwnerIndex != 1984 ||
		decoded.OwnerReturns[1].OwnerIndex != 2047 || decoded.UnsentArrivals[0].SourceIdle == nil || *decoded.UnsentArrivals[0].SourceIdle != 31 ||
		decoded.UnsentArrivals[0].GlobalActive == nil || *decoded.UnsentArrivals[0].GlobalActive != 2048 ||
		decoded.UnsentArrivals[1].SourceIdle == nil || *decoded.UnsentArrivals[1].SourceIdle != 32 || decoded.UnsentArrivals[1].AttemptElapsedMillis != nil {
		t.Fatal("high owner/global active/source idle values or no-attempt window evidence were truncated")
	}
}
