package main

import "time"

const (
	diagConnect = iota
	diagWrite
	diagHeader
	diagBody
)

// Numeric observations plus one validated, fixed-size gateway correlation ID.
// Never retain other headers, invalid ID bytes, request bytes, or URLs.
// The request path stores values, not per-phase heap allocations. JSON conversion
// takes place after all worker sessions have completed.
type exchangeDiagnostics struct {
	gatewayRequestID                               gatewayRequestID
	started                                        time.Time
	durations                                      [4]time.Duration
	completed                                      [4]bool
	active                                         int
	phaseStart                                     time.Time
	freshConnection                                bool
	localPort, connectionSequence, requestSequence int
}

func newExchangeDiagnostics(start time.Time, fresh bool) exchangeDiagnostics {
	return exchangeDiagnostics{started: start, durations: [4]time.Duration{-1, -1, -1, -1}, active: -1, freshConnection: fresh}
}
func (d *exchangeDiagnostics) begin(stage int) { d.active = stage; d.phaseStart = time.Now() }
func (d *exchangeDiagnostics) end(ok bool) {
	if d.active < 0 {
		return
	}
	d.durations[d.active] = time.Since(d.phaseStart)
	d.completed[d.active] = ok
	d.active = -1
}

type StageDiagnostic struct {
	Millis *float64 `json:"millis"`
	State  string   `json:"state"`
}

func (d exchangeDiagnostics) stage(index int) StageDiagnostic {
	if d.durations[index] < 0 {
		return StageDiagnostic{State: "NOT_ATTEMPTED"}
	}
	value := float64(d.durations[index]) / float64(time.Millisecond)
	state := "FAILED"
	if d.completed[index] {
		state = "COMPLETED"
	}
	return StageDiagnostic{Millis: &value, State: state}
}

type RequestDiagnostic struct {
	GatewayRequestID             string          `json:"gatewayRequestId"`
	GatewayRequestIDState        string          `json:"gatewayRequestIdState"`
	ArrivalIndex                 int             `json:"arrivalIndex"`
	SourceIndex                  int             `json:"sourceIndex"`
	OwnerIndex                   int             `json:"ownerIndex"`
	Phase                        string          `json:"phase"`
	ScheduledElapsedMillis       float64         `json:"scheduledElapsedMillis"`
	DispatchedElapsedMillis      float64         `json:"dispatchedElapsedMillis"`
	WorkerStartedElapsedMillis   float64         `json:"workerStartedElapsedMillis"`
	ExchangeStartedElapsedMillis float64         `json:"exchangeStartedElapsedMillis"`
	RoundtripMillis              float64         `json:"roundtripMillis"`
	FreshConnection              bool            `json:"freshConnection"`
	LocalPort                    int             `json:"localPort"`
	ConnectionSequence           int             `json:"connectionSequence"`
	ConnectionRequestSequence    int             `json:"connectionRequestSequence"`
	Status                       int             `json:"status"`
	Received                     bool            `json:"received"`
	Correct                      bool            `json:"correct"`
	FailureReason                string          `json:"failureReason"`
	Connect                      StageDiagnostic `json:"connect"`
	Write                        StageDiagnostic `json:"write"`
	Header                       StageDiagnostic `json:"header"`
	Body                         StageDiagnostic `json:"body"`
}
