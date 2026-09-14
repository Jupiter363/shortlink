package main

import (
	"context"
	"math"
	"runtime"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
)

const schedulerSpinMicros = 1000
const maxStartLateness = 80 * time.Millisecond

type phase uint8

const (
	warmup phase = iota
	measure
)

func (p phase) String() string {
	if p == warmup {
		return "warmup"
	}
	return "measure"
}

type arrivalPlan struct {
	warmN, total                                 int
	warmSeconds, measureSeconds, startRate, rate float64
}

func newPlan(c Config) arrivalPlan {
	w := float64(c.WarmupMillis) / 1000
	d := float64(c.DurationMillis) / 1000
	wn := int(math.Ceil(w * (float64(c.WarmupStartRate) + float64(c.Rate)) / 2))
	return arrivalPlan{warmN: wn, total: wn + int(math.Ceil(d*float64(c.Rate))),
		warmSeconds: w, measureSeconds: d, startRate: float64(c.WarmupStartRate), rate: float64(c.Rate)}
}
func (p arrivalPlan) at(i int) time.Duration {
	var seconds float64
	if i >= p.warmN {
		seconds = p.warmSeconds + float64(i-p.warmN)/p.rate
	} else if p.rate == p.startRate {
		seconds = float64(i) / p.rate
	} else { // Stable inverse integral of the linear rate ramp.
		slope := (p.rate - p.startRate) / p.warmSeconds
		seconds = 2 * float64(i) / (p.startRate + math.Sqrt(p.startRate*p.startRate+2*slope*float64(i)))
	}
	return time.Duration(seconds * float64(time.Second))
}
func (p arrivalPlan) end() time.Duration {
	return time.Duration((p.warmSeconds + p.measureSeconds) * float64(time.Second))
}
func (p arrivalPlan) phase(i int) phase {
	if i < p.warmN {
		return warmup
	}
	return measure
}

// Catch-up is explicitly bounded by original due time, never by a shifted clock.
// This predicate is checked both at dispatch and at the actual worker start.
func (p arrivalPlan) canStart(index int, elapsed time.Duration) bool {
	return elapsed >= p.at(index) && elapsed < p.end() && elapsed-p.at(index) <= maxStartLateness
}

func mixed32(x uint32) uint32 {
	x = ((x >> 16) ^ x) * 0x45d9f3b
	x = ((x >> 16) ^ x) * 0x45d9f3b
	return (x >> 16) ^ x
}
func seedHash(seed uint32) uint32 {
	h := uint32(2166136261)
	for _, b := range []byte(strconv.FormatUint(uint64(seed), 10)) {
		h = (h ^ uint32(b)) * 16777619
	}
	return h
}
func linkIndex(index int, seed uint32, n int) int {
	a := mixed32(uint32(index) ^ seed)
	return int(mixed32(a^0x9e3779b9) % uint32(n))
}

type stopInfo struct {
	Reason        string  `json:"reason"`
	Phase         string  `json:"phase"`
	Status        int     `json:"status"`
	ElapsedMillis float64 `json:"elapsedMillis"`
}
type admissionGate struct {
	mu      sync.Mutex
	stopped bool
	info    stopInfo
	done    chan struct{}
	start   time.Time
}

func (g *admissionGate) stop(reason string, p phase, status int) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if !g.stopped {
		g.stopped = true
		g.info = stopInfo{Reason: reason, Phase: p.String(), Status: status, ElapsedMillis: float64(time.Since(g.start)) / float64(time.Millisecond)}
		close(g.done)
	}
}
func (g *admissionGate) admit(action func()) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.stopped {
		return false
	}
	action()
	return true
}

func waitUntil(g *admissionGate, deadline time.Time) bool {
	for {
		select {
		case <-g.done:
			return false
		default:
		}
		remaining := time.Until(deadline)
		if remaining <= 0 {
			return true
		}
		if remaining > schedulerSpinMicros*time.Microsecond {
			t := time.NewTimer(remaining - schedulerSpinMicros*time.Microsecond)
			select {
			case <-g.done:
				if !t.Stop() {
					select {
					case <-t.C:
					default:
					}
				}
				return false
			case <-t.C:
			}
		}
	}
}

type job struct {
	index, link int
	p           phase
	due         time.Time
	admitted    time.Time
	catchup     bool
}
type sample struct {
	roundtrip, scheduledCompletion float64
	valid                          bool
	diagnostic                     exchangeDiagnostics
	owner, source, status          int
	dispatched, workerStarted      time.Duration
	received, correct              bool
	reason                         string
}
type workerCounters struct {
	sent, completed, received, correct, clientErrors, incorrectHTTP, status429, status503 int
	dropped, lateDropped, windowDropped, stoppedDropped, catchupSent                      int
}
type workerState struct {
	phase             [2]workerCounters
	source            int
	jobs              chan job
	client            *connectionClient
	invalidTarget     bool
	maxStartLag       time.Duration
	startsAfterWindow int
	crossPhaseStarts  int
}
type scheduleCounters struct {
	scheduled, dropped, lateDropped, busyDropped [2]int
	maxLag                                       time.Duration
	maxActive                                    int
	elapsed                                      time.Duration
}

// Keep the admission predicate and counters in one path so deterministic tests
// can distinguish worker lateness from a busy source without timing sleeps.
func (w *workerState) admitAt(p arrivalPlan, j job, elapsed, warmupEnd time.Duration, trace *arrivalTrace, active *atomic.Int64, idle chan int) bool {
	stat := &w.phase[j.p]
	if lag := elapsed - p.at(j.index); lag > w.maxStartLag {
		w.maxStartLag = lag
	}
	if !p.canStart(j.index, elapsed) {
		stat.dropped++
		if elapsed >= p.end() {
			stat.windowDropped++
			trace.reject(dropWorkerWindow, elapsed, active.Load(), len(idle))
		} else {
			stat.lateDropped++
			trace.reject(dropWorkerLate, elapsed, active.Load(), len(idle))
		}
		return false
	}
	if j.p == warmup && elapsed >= warmupEnd {
		w.crossPhaseStarts++
	}
	stat.sent++
	if j.catchup {
		stat.catchupSent++
	}
	return true
}

func runWorkload(ctx context.Context, c Config) (Summary, error) {
	receipt, err := prepareDrainReceipt(c)
	if err != nil {
		return Summary{}, err // Fail before starting any request if its evidence path is occupied.
	}
	defer receipt.cleanup()
	wire, address, err := requestBytes(c)
	if err != nil {
		return Summary{}, err
	}
	preparation, err := maybePrepareFDTableForWorkers(c.PreallocateFDTable, c.Workers)
	if err != nil {
		return Summary{Kind: kind, SchemaVersion: 1, Version: version, RunID: c.RunID, Label: c.Label, Preparation: preparation}, err
	}
	plan := newPlan(c)
	samples := make([]sample, plan.total)
	traces := make([]arrivalTrace, diagnosticPrefixSize(plan.total))
	workers := make([]workerState, c.Workers)
	idle := make([]chan int, len(c.Sources))
	for i := range idle {
		idle[i] = make(chan int, c.Workers/len(c.Sources))
	}
	gate := &admissionGate{done: make(chan struct{})}
	var ready, sessions sync.WaitGroup
	var active atomic.Int64
	ready.Add(c.Workers)
	sessions.Add(c.Workers)
	for i := range workers {
		w := &workers[i]
		w.source = i % len(c.Sources)
		w.jobs = make(chan job, 1)
		w.client = newConnectionClient(address, c.Sources[w.source], c)
		idle[w.source] <- i
		go func(w *workerState, id int) {
			defer sessions.Done()
			defer w.client.close()
			ready.Done()
			for j := range w.jobs {
				stat := &w.phase[j.p]
				trace := traceAt(traces, j.index)
				started := false
				var workerStarted time.Time
				if !gate.admit(func() {
					actualStart := time.Now()
					workerStarted = actualStart
					elapsed := actualStart.Sub(gate.start)
					started = w.admitAt(plan, j, elapsed, time.Duration(c.WarmupMillis)*time.Millisecond, trace, &active, idle[w.source])
				}) {
					stat.dropped++
					stat.stoppedDropped++
					trace.reject(dropWorkerStopped, time.Since(gate.start), active.Load(), len(idle[w.source]))
				}
				if !started {
					returnOwner(id, idle[w.source], &active, gate.start, trace)
					continue
				}
				out := w.client.exchange(wire[j.link], c.Links[j.link].OriginURL, func(reason string, status int) {
					if reason == "WRONG_REDIRECT_TARGET" {
						w.invalidTarget = true
					}
					gate.stop(reason, j.p, status)
				})
				stat.completed++
				if out.received {
					stat.received++
				}
				if out.correct {
					stat.correct++
				}
				if out.clientError {
					stat.clientErrors++
				} else if !out.correct {
					stat.incorrectHTTP++
				}
				if out.status == 429 {
					stat.status429++
				}
				if out.status == 503 {
					stat.status503++
				}
				samples[j.index] = sample{roundtrip: float64(out.roundtrip) / float64(time.Millisecond), scheduledCompletion: float64(time.Since(j.due)) / float64(time.Millisecond), valid: true,
					diagnostic: out.diagnostic, owner: id, source: w.source, status: out.status, received: out.received, correct: out.correct, reason: out.reason,
					dispatched: j.admitted.Sub(gate.start), workerStarted: workerStarted.Sub(gate.start)}
				if !out.correct {
					gate.stop(out.reason, j.p, out.status)
				}
				returnOwner(id, idle[w.source], &active, gate.start, trace)
			}
		}(w, i)
	}
	ready.Wait()
	gate.start = time.Now()
	if ctx.Err() != nil {
		gate.stop("EXTERNAL_STOP", measure, 0)
	}
	watchDone := make(chan struct{})
	go func() {
		select {
		case <-ctx.Done():
			p := warmup
			if time.Since(gate.start) >= time.Duration(c.WarmupMillis)*time.Millisecond {
				p = measure
			}
			gate.stop("EXTERNAL_STOP", p, 0)
		case <-watchDone:
		}
	}()
	counts := scheduleCounters{}
	seed := seedHash(c.Seed)
	sentOrdinal := 0
	for index := 0; index < plan.total; {
		if !waitUntil(gate, gate.start.Add(plan.at(index))) {
			break
		}
		now := time.Now()
		elapsed := now.Sub(gate.start)
		// Original due-time lag stays visible even when an arrival is dropped.
		if lag := elapsed - plan.at(index); lag > counts.maxLag {
			counts.maxLag = lag
		}
		if elapsed >= plan.end() { // No requests are dispatched outside the configured window.
			for ; index < plan.total; index++ {
				p := plan.phase(index)
				counts.scheduled[p]++
				counts.dropped[p]++
				counts.lateDropped[p]++
				traceAt(traces, index).windowClosed(elapsed, active.Load(), len(idle[index%len(idle)]))
			}
			break
		}
		p := plan.phase(index)
		due := gate.start.Add(plan.at(index))
		lag := now.Sub(due)
		if lag > counts.maxLag {
			counts.maxLag = lag
		}
		// Source selection is scheduled-index modulo 64. A busy source cannot borrow
		// another source's slot; each source retains its configured Workers/64 bound.
		source := index % len(idle)
		admitted := gate.admit(func() {
			counts.scheduled[p]++
			if !plan.canStart(index, elapsed) {
				counts.dropped[p]++
				counts.lateDropped[p]++
				traceAt(traces, index).reject(dropDispatchLate, time.Since(gate.start), active.Load(), len(idle[source]))
				return
			}
			select {
			case id := <-idle[source]:
				if trace := traceAt(traces, index); trace != nil {
					trace.claimed, trace.owner = true, int32(id)
				}
				current := int(active.Add(1))
				if current > counts.maxActive {
					counts.maxActive = current
				}
				catchup := index+1 < plan.total && plan.at(index+1) <= elapsed
				workers[id].jobs <- job{index: index, link: linkIndex(sentOrdinal, seed, len(c.Links)), p: p, due: due, admitted: now, catchup: catchup}
				sentOrdinal++
			default:
				counts.dropped[p]++
				counts.busyDropped[p]++
				// The selected receive saw an empty source pool. Later len(idle)
				// could already differ after a concurrent owner return.
				traceAt(traces, index).reject(dropSourceBusy, time.Since(gate.start), active.Load(), 0)
			}
		})
		if !admitted {
			break
		}
		index++
	}
	complete := waitUntil(gate, gate.start.Add(plan.end()))
	counts.elapsed = time.Since(gate.start)
	for i := range workers {
		close(workers[i].jobs)
	}
	sessions.Wait() // Each outstanding exchange has its original absolute <=10s deadline.
	close(watchDone)
	gate.mu.Lock()
	info := gate.info
	stopped := gate.stopped
	gate.mu.Unlock()
	if !stopped {
		info = stopInfo{Reason: "COMPLETED", Phase: "none", ElapsedMillis: float64(counts.elapsed) / float64(time.Millisecond)}
	}
	// All exchanges, owner returns and client closes completed before this point.
	// Publish BEFORE summarize scans/sorts samples and creates the large JSON model.
	drainedElapsed := time.Since(gate.start)
	receiptError := ""
	if err := receipt.publish(c, workers, counts, plan, info, complete && !stopped, active.Load(), drainedElapsed); err != nil {
		receiptError = "REQUESTS_DRAINED_RECEIPT_WRITE_FAILED"
	}
	result := summarize(c, plan, workers, samples, counts, info, complete && !stopped, gate.start, drainedElapsed, runtime.GOMAXPROCS(0), runtime.NumCPU(), traces)
	result.RequestsDrainedReceiptError = receiptError
	result.Preparation = preparation
	if c.PreallocateFDTable {
		result.Config["preallocateFDTable"] = true
	}
	return result, nil
}
