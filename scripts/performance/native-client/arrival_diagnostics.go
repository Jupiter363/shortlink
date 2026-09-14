package main

import (
	"sync/atomic"
	"time"
)

// Detailed diagnostics retain an original-arrival prefix, never a reservoir of
// successful requests. Aggregate counters/latencies still cover the full run.
// The pre-existing maxSamples bound remains 1,200,000 arrivals.
const maxDiagnosticArrivals = 1_000_000

type dropReason uint8

const (
	dropNone dropReason = iota
	dropSourceBusy
	dropDispatchLate
	dropDispatchWindow
	dropWorkerLate
	dropWorkerWindow
	dropWorkerStopped
)

func (r dropReason) String() string {
	switch r {
	case dropSourceBusy:
		return "SOURCE_BUSY"
	case dropDispatchLate:
		return "DISPATCH_LATE"
	case dropDispatchWindow:
		return "DISPATCH_WINDOW_CLOSED"
	case dropWorkerLate:
		return "WORKER_LATE"
	case dropWorkerWindow:
		return "WORKER_WINDOW_CLOSED"
	case dropWorkerStopped:
		return "WORKER_STOPPED"
	default:
		return "UNCLASSIFIED_DIAGNOSTIC_GAP"
	}
}

// One writer at a time: dispatcher initializes ownership before its channel
// handoff; the owner subsequently records its rejection and return. The summary
// reads only after sessions.Wait. A return is a bracket because the channel send
// linearization can be observed by the dispatcher before the sender resumes.
type arrivalTrace struct {
	decisionElapsed                        time.Duration
	returnStarted, returnCompleted         time.Duration
	globalActive                           int32
	owner                                  int32
	sourceIdle                             int16
	reason                                 dropReason
	attempted, snapshot, claimed, returned bool
}

func diagnosticPrefixSize(total int) int {
	if total > maxDiagnosticArrivals {
		return maxDiagnosticArrivals
	}
	return total
}

func traceAt(traces []arrivalTrace, index int) *arrivalTrace {
	if index < 0 || index >= len(traces) {
		return nil
	}
	return &traces[index]
}

func (t *arrivalTrace) reject(reason dropReason, at time.Duration, active int64, sourceIdle int) {
	if t == nil {
		return
	}
	t.reason, t.decisionElapsed = reason, at
	t.attempted, t.snapshot = true, true
	t.globalActive, t.sourceIdle = int32(active), int16(sourceIdle)
}

func (t *arrivalTrace) windowClosed(at time.Duration, active int64, sourceIdle int) {
	if t == nil {
		return
	}
	t.reason, t.decisionElapsed = dropDispatchWindow, at
	t.snapshot = true
	t.globalActive, t.sourceIdle = int32(active), int16(sourceIdle)
	// These remaining arrivals are accounted for without actual dispatch
	// attempts. Their attempt timestamp must be null, not invented.
}

func returnOwner(id int, idle chan int, active *atomic.Int64, start time.Time, trace *arrivalTrace) {
	if trace != nil {
		trace.returnStarted = time.Since(start)
	}
	active.Add(-1)
	idle <- id
	if trace != nil {
		trace.returnCompleted = time.Since(start)
		trace.returned = true
	}
}

type UnsentArrivalDiagnostic struct {
	ArrivalIndex           int      `json:"arrivalIndex"`
	SourceIndex            int      `json:"sourceIndex"`
	OwnerIndex             int      `json:"ownerIndex"`
	Phase                  string   `json:"phase"`
	Reason                 string   `json:"reason"`
	Disposition            string   `json:"disposition"`
	AttemptStage           string   `json:"attemptStage"`
	ScheduledElapsedMillis float64  `json:"scheduledElapsedMillis"`
	AttemptElapsedMillis   *float64 `json:"attemptElapsedMillis"`
	DecisionElapsedMillis  *float64 `json:"decisionElapsedMillis"`
	GlobalActive           *int     `json:"globalActive"`
	SourceIdle             *int     `json:"sourceIdle"`
}

type OwnerReturnDiagnostic struct {
	ArrivalIndex               int     `json:"arrivalIndex"`
	SourceIndex                int     `json:"sourceIndex"`
	OwnerIndex                 int     `json:"ownerIndex"`
	Sent                       bool    `json:"sent"`
	ReturnStartedElapsedMillis float64 `json:"returnStartedElapsedMillis"`
	ReturnedElapsedMillis      float64 `json:"returnedElapsedMillis"`
}

type DiagnosticReport struct {
	SchemaVersion                      int                       `json:"schemaVersion"`
	ArrivalLimit                       int                       `json:"arrivalLimit"`
	CoveredArrivalPrefix               int                       `json:"coveredArrivalPrefix"`
	OmittedArrivals                    int                       `json:"omittedArrivals"`
	CoverageComplete                   bool                      `json:"coverageComplete"`
	AccountingPassed                   bool                      `json:"accountingPassed"`
	UnclassifiedArrivals               int                       `json:"unclassifiedArrivals"`
	SnapshotConsistency                string                    `json:"snapshotConsistency"`
	ReturnTimestampSemantics           string                    `json:"returnTimestampSemantics"`
	CapturedRequests                   int                       `json:"capturedRequests"`
	OmittedRequests                    int                       `json:"omittedRequests"`
	CapturedUnsentArrivals             int                       `json:"capturedUnsentArrivals"`
	OmittedUnsentArrivals              int                       `json:"omittedUnsentArrivals"`
	CapturedOwnerReturns               int                       `json:"capturedOwnerReturns"`
	OmittedOwnerReturns                int                       `json:"omittedOwnerReturns"`
	MissingOwnerReturnsInCoveredPrefix int                       `json:"missingOwnerReturnsInCoveredPrefix"`
	OwnerReturnBoundsValid             bool                      `json:"ownerReturnBoundsValid"`
	DroppedArrivalsHaveNoRequestSample bool                      `json:"droppedArrivalsHaveNoRequestSample"`
	Requests                           []RequestDiagnostic       `json:"requests"`
	UnsentArrivals                     []UnsentArrivalDiagnostic `json:"unsentArrivals"`
	OwnerReturns                       []OwnerReturnDiagnostic   `json:"ownerReturns"`
}

func summarizeDiagnostics(c Config, p arrivalPlan, samples []sample, traces []arrivalTrace, all PhaseSummary, workerDropped int, stop stopInfo, start time.Time) DiagnosticReport {
	limit := diagnosticPrefixSize(p.total)
	if len(traces) < limit {
		limit = len(traces)
	}
	capacity := func(total int) int {
		if total < 0 {
			return 0
		}
		if total > limit {
			return limit
		}
		return total
	}
	result := DiagnosticReport{SchemaVersion: 2, ArrivalLimit: maxDiagnosticArrivals, OwnerReturnBoundsValid: true,
		CoveredArrivalPrefix: limit, OmittedArrivals: p.total - limit,
		DroppedArrivalsHaveNoRequestSample: true,
		SnapshotConsistency:                "SEPARATE_ATOMIC_AND_CHANNEL_OBSERVATIONS_NOT_GLOBAL_TRANSACTION",
		ReturnTimestampSemantics:           "IDLE_CHANNEL_SEND_LINEARIZES_WITHIN_RETURN_STARTED_AND_RETURNED_BRACKET",
		Requests:                           make([]RequestDiagnostic, 0, capacity(all.Completed)), UnsentArrivals: make([]UnsentArrivalDiagnostic, 0, capacity(p.total-all.Sent)), OwnerReturns: make([]OwnerReturnDiagnostic, 0, capacity(all.Sent+workerDropped))}
	for i := 0; i < limit; i++ {
		t := traces[i]
		s := sample{}
		if i < len(samples) {
			s = samples[i]
		}
		if s.valid {
			d := s.diagnostic
			result.Requests = append(result.Requests, RequestDiagnostic{
				GatewayRequestID: d.gatewayRequestID.value(), GatewayRequestIDState: d.gatewayRequestID.state.String(),
				ArrivalIndex: i, SourceIndex: s.source, OwnerIndex: s.owner, Phase: p.phase(i).String(),
				ScheduledElapsedMillis: float64(p.at(i)) / float64(time.Millisecond), DispatchedElapsedMillis: float64(s.dispatched) / float64(time.Millisecond),
				WorkerStartedElapsedMillis: float64(s.workerStarted) / float64(time.Millisecond), ExchangeStartedElapsedMillis: float64(d.started.Sub(start)) / float64(time.Millisecond),
				RoundtripMillis: s.roundtrip, FreshConnection: d.freshConnection, LocalPort: d.localPort, ConnectionSequence: d.connectionSequence, ConnectionRequestSequence: d.requestSequence,
				Status: s.status, Received: s.received, Correct: s.correct, FailureReason: s.reason,
				Connect: d.stage(diagConnect), Write: d.stage(diagWrite), Header: d.stage(diagHeader), Body: d.stage(diagBody)})
		} else {
			u := UnsentArrivalDiagnostic{ArrivalIndex: i, SourceIndex: i % len(c.Sources), OwnerIndex: -1,
				Phase: p.phase(i).String(), ScheduledElapsedMillis: float64(p.at(i)) / float64(time.Millisecond),
				Reason: t.reason.String(), Disposition: "DROPPED", AttemptStage: "NONE"}
			if t.claimed {
				u.OwnerIndex = int(t.owner)
			}
			if t.reason != dropNone {
				u.DecisionElapsedMillis = pointer(float64(t.decisionElapsed) / float64(time.Millisecond))
				if t.attempted {
					u.AttemptElapsedMillis = pointer(float64(t.decisionElapsed) / float64(time.Millisecond))
					u.AttemptStage = "DISPATCH"
					if t.claimed {
						u.AttemptStage = "WORKER_ADMISSION"
					}
				}
				if t.snapshot {
					active, sourceIdle := int(t.globalActive), int(t.sourceIdle)
					u.GlobalActive, u.SourceIdle = &active, &sourceIdle
				}
			} else if i >= all.Scheduled {
				u.Reason, u.Disposition = "CANCELLED_AFTER_STOP", "CANCELLED"
				u.DecisionElapsedMillis = pointer(stop.ElapsedMillis)
			} else {
				result.UnclassifiedArrivals++
			}
			result.UnsentArrivals = append(result.UnsentArrivals, u)
		}
		if t.returned {
			if !t.claimed || t.returnStarted < 0 || t.returnCompleted < t.returnStarted {
				result.OwnerReturnBoundsValid = false
			}
			result.OwnerReturns = append(result.OwnerReturns, OwnerReturnDiagnostic{
				ArrivalIndex: i, SourceIndex: i % len(c.Sources), OwnerIndex: int(t.owner), Sent: s.valid,
				ReturnStartedElapsedMillis: float64(t.returnStarted) / float64(time.Millisecond), ReturnedElapsedMillis: float64(t.returnCompleted) / float64(time.Millisecond)})
		} else if t.claimed || s.valid {
			result.MissingOwnerReturnsInCoveredPrefix++
		}
	}
	result.CapturedRequests, result.CapturedUnsentArrivals, result.CapturedOwnerReturns = len(result.Requests), len(result.UnsentArrivals), len(result.OwnerReturns)
	result.OmittedRequests = all.Completed - result.CapturedRequests
	result.OmittedUnsentArrivals = p.total - all.Sent - result.CapturedUnsentArrivals
	result.OmittedOwnerReturns = all.Sent + workerDropped - result.CapturedOwnerReturns
	result.AccountingPassed = result.CapturedRequests+result.CapturedUnsentArrivals == limit &&
		result.CapturedRequests+result.OmittedRequests == all.Completed && result.OmittedRequests >= 0 &&
		result.CapturedUnsentArrivals+result.OmittedUnsentArrivals == all.Dropped+all.Cancelled && result.OmittedUnsentArrivals >= 0 &&
		result.OmittedOwnerReturns >= 0 && result.UnclassifiedArrivals == 0 && result.MissingOwnerReturnsInCoveredPrefix == 0 && result.OwnerReturnBoundsValid
	result.CoverageComplete = result.AccountingPassed && result.OmittedArrivals == 0 && result.OmittedRequests == 0 && result.OmittedUnsentArrivals == 0 && result.OmittedOwnerReturns == 0
	return result
}
