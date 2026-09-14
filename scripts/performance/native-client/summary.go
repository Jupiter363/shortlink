package main

import (
	"math"
	"sort"
	"time"
)

type Latencies struct {
	Count int      `json:"count"`
	Mean  *float64 `json:"mean"`
	P50   *float64 `json:"p50"`
	P95   *float64 `json:"p95"`
	P99   *float64 `json:"p99"`
	P999  *float64 `json:"p99_9"`
	Max   *float64 `json:"max"`
}
type PhaseSummary struct {
	Planned             int       `json:"planned_arrivals"`
	Scheduled           int       `json:"scheduled"`
	Sent                int       `json:"sent"`
	Completed           int       `json:"completed"`
	Received            int       `json:"received_http"`
	Correct             int       `json:"correct"`
	IncorrectHTTP       int       `json:"incorrect_http"`
	ClientErrors        int       `json:"client_errors"`
	Errors              int       `json:"errors"`
	Status429           int       `json:"status429"`
	Status503           int       `json:"status503"`
	NotCompleted        int       `json:"not_completed"`
	Dropped             int       `json:"dropped"`
	Cancelled           int       `json:"cancelled_arrivals"`
	Roundtrip           Latencies `json:"roundtrip_ms"`
	ScheduledCompletion Latencies `json:"scheduled_to_completion_ms"`
}
type Window struct {
	ScheduledMillis float64 `json:"scheduledMillis"`
	ElapsedMillis   float64 `json:"elapsedMillis"`
	Start           string  `json:"start"`
	End             string  `json:"end"`
}
type SourceSummary struct {
	Index     int    `json:"index"`
	Address   string `json:"address"`
	Sent      int    `json:"sent"`
	Completed int    `json:"completed"`
	Correct   int    `json:"correct"`
}
type Summary struct {
	RequestsDrainedReceiptError string                  `json:"requestsDrainedReceiptError,omitempty"`
	GatewayRequestIDs           GatewayRequestIDSummary `json:"gatewayRequestIds"`
	Preparation                 *FDPreparation          `json:"preparation,omitempty"`
	Kind                        string                  `json:"kind"`
	SchemaVersion               int                     `json:"schemaVersion"`
	Version                     string                  `json:"version"`
	RunID                       string                  `json:"runId"`
	Label                       string                  `json:"label"`
	Config                      map[string]any          `json:"config"`
	Runtime                     map[string]any          `json:"runtime"`
	Windows                     map[string]Window       `json:"windows"`
	All                         PhaseSummary            `json:"all"`
	Warmup                      PhaseSummary            `json:"warmup"`
	Measure                     PhaseSummary            `json:"measure"`
	Scheduler                   map[string]any          `json:"scheduler"`
	Stop                        stopInfo                `json:"stop"`
	MeasurementComplete         bool                    `json:"measurementComplete"`
	SchedulingComplete          bool                    `json:"scheduling_complete"`
	FixtureValid                bool                    `json:"fixture_valid"`
	Sources                     []SourceSummary         `json:"sources"`
	SourceCountUsed             int                     `json:"sourceCountUsed"`
	Conservation                bool                    `json:"conservationPassed"`
	Diagnostics                 DiagnosticReport        `json:"diagnostics"`
}

func pointer(v float64) *float64 { return &v }

// Nearest-rank empirical quantiles, explicitly independent of k6's estimator.
func summarizeLatency(values []float64) Latencies {
	if len(values) == 0 {
		return Latencies{}
	}
	sort.Float64s(values)
	sum := 0.0
	for _, v := range values {
		sum += v
	}
	quantile := func(p float64) *float64 { return pointer(values[int(math.Ceil(p*float64(len(values))))-1]) }
	return Latencies{Count: len(values), Mean: pointer(sum / float64(len(values))), P50: quantile(.5), P95: quantile(.95), P99: quantile(.99), P999: quantile(.999), Max: pointer(values[len(values)-1])}
}

func summarize(c Config, p arrivalPlan, workers []workerState, samples []sample, counts scheduleCounters, stop stopInfo,
	complete bool, start time.Time, elapsed time.Duration, gomaxprocs, numCPU int, traces ...[]arrivalTrace) Summary {
	result := Summary{Kind: kind, SchemaVersion: 1, Version: version, RunID: c.RunID, Label: c.Label,
		Config: map[string]any{"rate": c.Rate, "warmupMillis": c.WarmupMillis, "durationMillis": c.DurationMillis,
			"requireGatewayRequestId": c.RequireGatewayRequestID,
			"warmupStartRate":         c.WarmupStartRate, "workers": c.Workers, "sourceCount": len(c.Sources), "linkCount": len(c.Links),
			"seed": c.Seed, "requestTimeoutMillis": c.RequestTimeoutMillis, "bodyLimit": c.BodyLimit,
			"method": "GET", "path": "edge", "expectedStatus": 302, "protocol": "HTTP/1.1", "keepAlive": true,
			"automaticRetries": false, "redirects": false, "proxy": false, "xffHeader": false,
			"fixedHeaderMode": "HOST_UA_REFERER_COOKIE_ACCEPT_CONTENT_TYPE_XFP",
			"inputMode":       "TEN_LINK_PREENCODED_REQUESTS", "sourceMode": "SCHEDULED_INDEX_MODULO_64",
			"selection": "FNV1A_SEED_MIXED32_DISPATCHED_REQUEST_INDEX", "percentileMethod": "nearest-rank",
			"phaseAssignment": "SCHEDULED_ARRIVAL_TIME", "maxResponseWireBytes": maxResponseWireBytes},
		Runtime: map[string]any{"gomaxprocs": gomaxprocs, "numCPU": numCPU, "totalElapsedMillis": float64(elapsed) / float64(time.Millisecond)},
		Windows: make(map[string]Window), Stop: stop, MeasurementComplete: complete, SchedulingComplete: complete,
		FixtureValid: stop.Reason != "WRONG_REDIRECT_TARGET", Conservation: true}
	phases := []*PhaseSummary{&result.Warmup, &result.Measure}
	result.Warmup.Planned = p.warmN
	result.Measure.Planned = p.total - p.warmN
	result.Sources = make([]SourceSummary, len(c.Sources))
	for i, s := range c.Sources {
		result.Sources[i] = SourceSummary{Index: i, Address: s}
	}
	maxStartLag := time.Duration(0)
	startsAfterWindow, crossPhaseStarts := 0, 0
	workerDropped, workerLateDropped, workerWindowDropped, workerStoppedDropped, catchupSent := 0, 0, 0, 0, 0
	for _, w := range workers {
		if w.maxStartLag > maxStartLag {
			maxStartLag = w.maxStartLag
		}
		startsAfterWindow += w.startsAfterWindow
		crossPhaseStarts += w.crossPhaseStarts
		if w.invalidTarget {
			result.FixtureValid = false
		}
		for phase, stat := range w.phase {
			s := phases[phase]
			s.Sent += stat.sent
			s.Completed += stat.completed
			s.Received += stat.received
			s.Correct += stat.correct
			s.ClientErrors += stat.clientErrors
			s.IncorrectHTTP += stat.incorrectHTTP
			s.Status429 += stat.status429
			s.Status503 += stat.status503
			s.Dropped += stat.dropped
			workerDropped += stat.dropped
			workerLateDropped += stat.lateDropped
			workerWindowDropped += stat.windowDropped
			workerStoppedDropped += stat.stoppedDropped
			catchupSent += stat.catchupSent
			source := &result.Sources[w.source]
			source.Sent += stat.sent
			source.Completed += stat.completed
			source.Correct += stat.correct
		}
	}
	for _, s := range result.Sources {
		if s.Sent > 0 {
			result.SourceCountUsed++
		}
	}
	var rts, scheduled [2][]float64
	allRT := make([]float64, 0, p.total)
	allSC := make([]float64, 0, p.total)
	for i, s := range samples {
		if s.valid {
			result.GatewayRequestIDs.observe(s.diagnostic.gatewayRequestID.state)
			ph := p.phase(i)
			rts[ph] = append(rts[ph], s.roundtrip)
			scheduled[ph] = append(scheduled[ph], s.scheduledCompletion)
			allRT = append(allRT, s.roundtrip)
			allSC = append(allSC, s.scheduledCompletion)
		}
	}
	result.GatewayRequestIDs.Required = c.RequireGatewayRequestID
	for i, s := range phases {
		s.Scheduled = counts.scheduled[i]
		s.Dropped += counts.dropped[i]
		s.Cancelled = s.Planned - s.Scheduled
		s.NotCompleted = s.Sent - s.Completed
		s.Errors = s.Completed - s.Correct
		s.Roundtrip = summarizeLatency(rts[i])
		s.ScheduledCompletion = summarizeLatency(scheduled[i])
		if s.Scheduled != s.Sent+s.Dropped || s.NotCompleted < 0 || s.Cancelled < 0 || s.Errors != s.ClientErrors+s.IncorrectHTTP || s.Received > s.Completed {
			result.Conservation = false
		}
		a := &result.All
		a.Planned += s.Planned
		a.Scheduled += s.Scheduled
		a.Sent += s.Sent
		a.Completed += s.Completed
		a.Received += s.Received
		a.Correct += s.Correct
		a.IncorrectHTTP += s.IncorrectHTTP
		a.ClientErrors += s.ClientErrors
		a.Errors += s.Errors
		a.Status429 += s.Status429
		a.Status503 += s.Status503
		a.NotCompleted += s.NotCompleted
		a.Dropped += s.Dropped
		a.Cancelled += s.Cancelled
	}
	result.GatewayRequestIDs.finish(result.All.Completed)
	result.All.Roundtrip = summarizeLatency(allRT)
	result.All.ScheduledCompletion = summarizeLatency(allSC)
	var arrivalTraces []arrivalTrace
	if len(traces) != 0 {
		arrivalTraces = traces[0]
	}
	result.Diagnostics = summarizeDiagnostics(c, p, samples, arrivalTraces, result.All, workerDropped, stop, start)
	schedulingMillis := float64(counts.elapsed) / float64(time.Millisecond)
	warmMillis := float64(c.WarmupMillis)
	measureMillis := float64(c.DurationMillis)
	for i, name := range []string{"warmup", "measure"} {
		offset, duration := 0.0, warmMillis
		if i == 1 {
			offset, duration = warmMillis, measureMillis
		}
		actual := math.Max(0, math.Min(duration, schedulingMillis-offset))
		result.Windows[name] = Window{ScheduledMillis: duration, ElapsedMillis: actual,
			Start: start.Add(time.Duration(offset) * time.Millisecond).UTC().Format(time.RFC3339Nano),
			End:   start.Add(time.Duration(offset+duration) * time.Millisecond).UTC().Format(time.RFC3339Nano)}
	}
	result.Scheduler = map[string]any{"mode": "MONOTONIC_FIXED_ARRIVAL_LINEAR_WARMUP_BOUNDED_CATCHUP",
		"spinMicros": schedulerSpinMicros, "maxLagMs": float64(counts.maxLag) / float64(time.Millisecond),
		"maxSchedulerLagMs": float64(counts.maxLag) / float64(time.Millisecond), "maxStartLatenessMillis": 80, "catchupArrivals": catchupSent,
		"maxActive": counts.maxActive, "maxActiveLimit": c.Workers, "queueCapacity": 0, "handoffSlots": c.Workers,
		"maxRequestStartLagMs": float64(maxStartLag) / float64(time.Millisecond), "startsAfterScheduledWindow": startsAfterWindow, "crossPhaseStarts": crossPhaseStarts,
		"workerStartDropped": workerDropped, "workerLateDropped": workerLateDropped, "workerWindowDropped": workerWindowDropped, "workerStoppedDropped": workerStoppedDropped,
		"lateDropped": counts.lateDropped[0] + counts.lateDropped[1], "busyDropped": counts.busyDropped[0] + counts.busyDropped[1],
		"warmupLateDropped": counts.lateDropped[0], "measureLateDropped": counts.lateDropped[1],
		"warmupBusyDropped": counts.busyDropped[0], "measureBusyDropped": counts.busyDropped[1],
		"schedulingElapsedMillis": schedulingMillis, "latePolicy": "ORIGINAL_DUE_ORDER_CATCHUP_UP_TO_80MS_NO_START_OUTSIDE_WINDOW"}
	return result
}
