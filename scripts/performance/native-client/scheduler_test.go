package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestArrivalPlanBoundariesAndLinearIntegral(t *testing.T) {
	c := localConfig("")
	c.Rate = 5000
	c.WarmupMillis = 30000
	c.WarmupStartRate = 100
	c.DurationMillis = 90000
	p := newPlan(c)
	if p.warmN != 76500 || p.total != 526500 || p.at(0) != 0 || p.at(76500) != 30*time.Second || p.at(p.total-1) >= p.end() {
		t.Fatalf("incorrect arrival plan: %+v", p)
	}
	for _, i := range []int{1, 100, 10000, 76499} {
		s := p.at(i).Seconds()
		integral := 100*s + (4900.0/60)*s*s
		if delta := integral - float64(i); delta > 0.00001 || delta < -.00001 {
			t.Fatalf("inverse integral error at %d: %f", i, delta)
		}
	}
	previous := time.Duration(-1)
	for i := 0; i < p.total; i++ {
		now := p.at(i)
		if now <= previous {
			t.Fatal("arrival order is not strictly increasing")
		}
		previous = now
	}
	c.Rate = 10000
	c.DurationMillis = 90000
	p = newPlan(c)
	if p.total > maxSamples {
		t.Fatal("maximum sample memory exceeded")
	}
}

func TestBoundedCatchupPreservesOriginalDueAndRejectsExpiredArrivals(t *testing.T) {
	c := localConfig("")
	c.Rate = 1000
	c.DurationMillis = 200
	p := newPlan(c)
	for _, tc := range []struct {
		index   int
		elapsed time.Duration
		want    bool
	}{
		{0, 900 * time.Microsecond, true}, {0, 20 * time.Millisecond, true}, {0, 80 * time.Millisecond, true},
		{0, 80*time.Millisecond + time.Nanosecond, false}, {3, 82 * time.Millisecond, true},
		{199, 199 * time.Millisecond, true}, {199, 200 * time.Millisecond, false}, {100, 99 * time.Millisecond, false},
	} {
		if got := p.canStart(tc.index, tc.elapsed); got != tc.want {
			t.Fatalf("canStart(%d,%v)=%v want=%v", tc.index, tc.elapsed, got, tc.want)
		}
	}
	// A simulated 100ms scheduler pause does not shift any due time: 0..19 expire,
	// 20..100 are bounded catch-up candidates, and future 101..199 are not due.
	expired, candidates := 0, 0
	for i := 0; i <= 100; i++ {
		if p.canStart(i, 100*time.Millisecond) {
			candidates++
		} else {
			expired++
		}
	}
	if expired != 20 || candidates != 81 || p.at(20) != 20*time.Millisecond {
		t.Fatalf("pause accounting expired=%d catchup=%d", expired, candidates)
	}
}

func TestSeed42MatchesExistingWorkloadGoldenSequence(t *testing.T) {
	want := []int{9, 4, 4, 4, 6, 6, 0, 6, 1, 2, 1, 3, 8, 7, 6, 9, 6, 2, 4, 8}
	got := make([]int, len(want))
	for i := range got {
		got[i] = linkIndex(i, seedHash(42), 10)
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("uniform choice changed: %v", got)
	}
}

func TestRunCorrectAndCountConservation(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Location", "https://shortlink-perf.local/target/test")
		w.WriteHeader(302)
	}))
	defer server.Close()
	c := localConfig(server.URL)
	got, err := runWorkload(context.Background(), c)
	if err != nil {
		t.Fatal(err)
	}
	if !got.MeasurementComplete || got.Stop.Reason != "COMPLETED" || !got.Conservation || got.All.Sent == 0 || got.All.Correct != got.All.Sent || got.All.Received != got.All.Sent || got.All.NotCompleted != 0 || got.All.Scheduled != 4 {
		t.Fatalf("invalid result: %+v", got)
	}
	if got.All.Scheduled != got.All.Sent+got.All.Dropped || got.Warmup.Sent != 0 || got.Measure.Roundtrip.Count != got.Measure.Completed {
		t.Fatal("phase conservation failed")
	}
	if got.Config["automaticRetries"] != false || got.Runtime["gomaxprocs"].(int) < 1 {
		t.Fatal("runtime protocol identity missing")
	}
}

func TestErrorStopsNewArrivalsAndDoesNotRefillMissingTarget(t *testing.T) {
	for _, code := range []int{429, 503} {
		t.Run(fmt.Sprint(code), func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(code) }))
			defer server.Close()
			c := localConfig(server.URL)
			c.Rate = 10
			c.DurationMillis = 1000
			got, err := runWorkload(context.Background(), c)
			if err != nil {
				t.Fatal(err)
			}
			if got.MeasurementComplete || got.All.Sent != 1 || got.All.Completed != 1 || got.All.Received != 1 || got.All.Correct != 0 || got.All.Errors != 1 || got.All.ClientErrors != 0 || got.All.Cancelled != 9 || !got.Conservation {
				t.Fatalf("incorrect stop counts: %+v", got)
			}
			if code == 429 && got.All.Status429 != 1 || code == 503 && got.All.Status503 != 1 {
				t.Fatal("status missing")
			}
		})
	}
}

func TestBusySourceIsBoundedAndDroppedIsNotSent(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(30 * time.Millisecond)
		w.Header().Set("Location", "https://shortlink-perf.local/target/test")
		w.WriteHeader(302)
	}))
	defer server.Close()
	c := localConfig(server.URL)
	c.Rate = 1000
	c.DurationMillis = 25
	c.RequestTimeoutMillis = 200
	got, err := runWorkload(context.Background(), c)
	if err != nil {
		t.Fatal(err)
	}
	if got.All.Sent != 1 || got.All.Correct != 1 || got.All.Dropped != 24 || got.Scheduler["maxActive"].(int) != 1 || !got.Conservation {
		t.Fatalf("unbounded backlog or fake sent: %+v", got)
	}
	if got.All.Roundtrip.P99 == nil || *got.All.Roundtrip.P99 < 25 {
		t.Fatal("roundtrip excluded response wait")
	}
	if !got.Diagnostics.CoverageComplete || !got.Diagnostics.AccountingPassed || len(got.Diagnostics.UnsentArrivals) != 24 || len(got.Diagnostics.OwnerReturns) != 1 {
		t.Fatalf("busy arrivals/return diagnostic conservation failed: %+v", got.Diagnostics)
	}
	for _, drop := range got.Diagnostics.UnsentArrivals {
		if drop.Reason != "SOURCE_BUSY" || drop.Disposition != "DROPPED" || drop.OwnerIndex != -1 || drop.AttemptStage != "DISPATCH" || drop.AttemptElapsedMillis == nil || drop.GlobalActive == nil || *drop.GlobalActive != 1 || drop.SourceIdle == nil || *drop.SourceIdle != 0 {
			t.Fatalf("busy drop was confused with a sent/late request: %+v", drop)
		}
	}
}

func TestQuantilesEmptyAndNearestRank(t *testing.T) {
	empty := summarizeLatency(nil)
	raw, _ := json.Marshal(empty)
	if !strings.Contains(string(raw), `"p99":null`) {
		t.Fatal("empty latency invented zero")
	}
	values := make([]float64, 1000)
	for i := range values {
		values[i] = float64(1000 - i)
	}
	got := summarizeLatency(values)
	if *got.P50 != 500 || *got.P95 != 950 || *got.P99 != 990 || *got.P999 != 999 || *got.Mean != 500.5 {
		t.Fatal("quantile definition changed")
	}
}

func validConfig() Config {
	c := localConfig("http://127.0.0.1:9080")
	c.Workers = 512
	c.Rate = 64
	c.DurationMillis = 2000
	c.Output = "unused-result.json"
	c.Sources = nil
	for i := 0; i < 64; i++ {
		c.Sources = append(c.Sources, fmt.Sprintf("127.0.0.%d", i+2))
	}
	c.Links = nil
	for i := 0; i < 10; i++ {
		c.Links = append(c.Links, Link{ShortURI: fmt.Sprintf("%09d", i), OriginURL: "https://shortlink-perf.local/target/test"})
	}
	return c
}

func TestConfigStrictBoundsAndActualSourceDistribution(t *testing.T) {
	c := validConfig()
	if err := c.validate(); err != nil {
		t.Fatal(err)
	}
	p := newPlan(c)
	if p.total != 128 {
		t.Fatal("calibration is not exactly 128 planned requests")
	}
	sourceCounts := make([]int, 64)
	for i := 0; i < p.total; i++ {
		sourceCounts[i%64]++
	}
	for _, n := range sourceCounts {
		if n != 2 {
			t.Fatal("sources require supplement requests")
		}
	}
	for _, tc := range []struct {
		name   string
		mutate func(*Config)
	}{
		{"remote_target", func(c *Config) { c.BaseURL = "http://example.com:9080" }},
		{"rate", func(c *Config) { c.Rate = 20001 }}, {"duration", func(c *Config) { c.DurationMillis = 90001 }},
		{"workers", func(c *Config) { c.Workers = 513 }}, {"header_injection", func(c *Config) { c.Host = "ok\r\nAuthorization: secret" }},
		{"source_spoof", func(c *Config) { c.Sources[0] = "10.0.0.1" }}, {"body", func(c *Config) { c.BodyLimit = 1025 }},
		{"duplicate_link", func(c *Config) { c.Links[1] = c.Links[0] }}, {"cookie", func(c *Config) { c.Cookie = "wrong" }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			c := validConfig()
			tc.mutate(&c)
			if c.validate() == nil {
				t.Fatal("invalid config accepted")
			}
		})
	}
}

func TestExclusiveOutputPreventsAnyRunAndSummaryRedactsInputs(t *testing.T) {
	c := validConfig()
	dir := t.TempDir()
	c.Output = filepath.Join(dir, "existing.json")
	if os.WriteFile(c.Output, []byte("old evidence"), 0600) != nil {
		t.Fatal("fixture write")
	}
	config := filepath.Join(dir, "private.json")
	encoded, _ := json.Marshal(c)
	if os.WriteFile(config, encoded, 0600) != nil {
		t.Fatal("fixture write")
	}
	if command([]string{"--config", config}) != 64 {
		t.Fatal("existing output must stop before network")
	}
	old, _ := os.ReadFile(c.Output)
	if string(old) != "old evidence" {
		t.Fatal("overwrote old output")
	}
	p := newPlan(c)
	summary := summarize(c, p, nil, nil, scheduleCounters{}, stopInfo{Reason: "EXTERNAL_STOP"}, false, time.Now(), 0, 2, 16)
	raw, _ := json.Marshal(summary)
	for _, secret := range []string{c.BaseURL, c.Host, c.Cookie, c.Referer, c.UserAgent, c.Links[0].OriginURL, c.Links[0].ShortURI} {
		if strings.Contains(string(raw), secret) {
			t.Fatalf("private input leaked (%d bytes)", len(secret))
		}
	}
}

func TestCalibrationUsesEveryRealSourceTwiceWithoutSupplement(t *testing.T) {
	var mu sync.Mutex
	seen := make(map[string]int)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil {
			t.Error("missing real source")
		}
		mu.Lock()
		seen[ip]++
		mu.Unlock()
		w.Header().Set("Location", "https://shortlink-perf.local/target/test")
		w.WriteHeader(302)
	}))
	defer server.Close()
	c := validConfig()
	c.BaseURL = server.URL
	result, err := runWorkload(context.Background(), c)
	if err != nil {
		t.Fatal(err)
	}
	if result.All.Sent != 128 || result.All.Completed != 128 || result.All.Correct != 128 || result.All.Dropped != 0 || result.SourceCountUsed != 64 || !result.Conservation {
		t.Fatalf("calibration not complete: %+v", result.All)
	}
	mu.Lock()
	defer mu.Unlock()
	for i := 0; i < 64; i++ {
		source := fmt.Sprintf("127.0.0.%d", i+2)
		if seen[source] != 2 || result.Sources[i].Sent != 2 || result.Sources[i].Correct != 2 {
			t.Fatalf("source %d count actual=%d summary=%+v", i, seen[source], result.Sources[i])
		}
	}
}

func TestCancelledBeforeRunSendsNothing(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	c := localConfig("http://127.0.0.1:1")
	result, err := runWorkload(ctx, c)
	if err != nil {
		t.Fatal(err)
	}
	if result.All.Sent != 0 || result.All.Scheduled != 0 || result.All.Cancelled != 4 || result.Stop.Reason != "EXTERNAL_STOP" || result.MeasurementComplete || !result.Conservation {
		t.Fatalf("cancelled request dispatched: %+v", result.All)
	}
}

func TestSummaryPreservesUnknownAndPhaseAddition(t *testing.T) {
	c := localConfig("")
	c.WarmupMillis = 10
	c.WarmupStartRate = 100
	c.Rate = 100
	c.DurationMillis = 20
	p := newPlan(c)
	workers := []workerState{{source: 0, phase: [2]workerCounters{{sent: 1, completed: 1, received: 1, correct: 1}, {sent: 1}}}}
	counts := scheduleCounters{scheduled: [2]int{1, 2}, dropped: [2]int{0, 1}, elapsed: 30 * time.Millisecond}
	samples := []sample{{roundtrip: 1, scheduledCompletion: 2, valid: true}, {}, {}}
	result := summarize(c, p, workers, samples, counts, stopInfo{Reason: "EXTERNAL_STOP"}, false, time.Now(), 40*time.Millisecond, 2, 16)
	if result.All.Sent != 2 || result.All.Completed != 1 || result.All.NotCompleted != 1 || result.All.Dropped != 1 || result.Measure.NotCompleted != 1 || result.Measure.Roundtrip.P99 != nil || !result.Conservation {
		t.Fatalf("unknown silently completed: %+v", result.All)
	}
}
