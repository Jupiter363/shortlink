package main

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

const drainReceiptKind = "NATIVE_GO_REQUESTS_DRAINED"
const maxDrainReceiptBytes = 4096

type drainedReceipt struct {
	SchemaVersion         int    `json:"schemaVersion"`
	Kind                  string `json:"kind"`
	Version               string `json:"version"`
	RunID                 string `json:"runId"`
	Label                 string `json:"label"`
	PID                   int    `json:"pid"`
	StartTicks            uint64 `json:"startTicks"`
	DrainedMonotonicNanos int64  `json:"drainedMonotonicNanos"`
	RequestElapsedNanos   int64  `json:"requestElapsedNanos"`
	RequestsDrained       bool   `json:"requestsDrained"`
	WorkersFinished       int    `json:"workersFinished"`
	ActiveRequests        int64  `json:"activeRequests"`
	MeasurementComplete   bool   `json:"measurementComplete"`
	StopReason            string `json:"stopReason"`
	Planned               int    `json:"planned"`
	Scheduled             int    `json:"scheduled"`
	Sent                  int    `json:"sent"`
	Completed             int    `json:"completed"`
	Correct               int    `json:"correct"`
	Errors                int    `json:"errors"`
	Dropped               int    `json:"dropped"`
	Cancelled             int    `json:"cancelled"`
}

type drainReceiptWriter struct {
	file   *os.File
	target string
	ticks  uint64
}

func processStartTicks(raw []byte) (uint64, error) {
	end := strings.LastIndexByte(string(raw), ')')
	if end < 0 {
		return 0, errors.New("PROC_STAT_INVALID")
	}
	fields := strings.Fields(string(raw[end+1:]))
	if len(fields) < 20 {
		return 0, errors.New("PROC_STAT_INVALID")
	}
	value, err := strconv.ParseUint(fields[19], 10, 64)
	if err != nil || value == 0 {
		return 0, errors.New("PROC_START_TICKS_INVALID")
	}
	return value, nil
}

func monotonicNanos() (int64, error) {
	var ts syscall.Timespec
	_, _, errno := syscall.Syscall(syscall.SYS_CLOCK_GETTIME, 1, uintptr(unsafe.Pointer(&ts)), 0)
	if errno != 0 {
		return 0, errno
	}
	return ts.Sec*int64(time.Second) + ts.Nsec, nil
}

func prepareDrainReceipt(c Config) (*drainReceiptWriter, error) {
	if c.RequestsDrainedReceipt == "" {
		return nil, nil
	}
	if _, err := os.Lstat(c.RequestsDrainedReceipt); !os.IsNotExist(err) {
		return nil, errors.New("DRAIN_RECEIPT_EXCLUSIVE_CREATE_REQUIRED")
	}
	raw, err := os.ReadFile("/proc/self/stat")
	if err != nil {
		return nil, errors.New("DRAIN_RECEIPT_IDENTITY_UNAVAILABLE")
	}
	ticks, err := processStartTicks(raw)
	if err != nil {
		return nil, err
	}
	f, err := os.CreateTemp(filepath.Dir(c.RequestsDrainedReceipt), ".requests-drained-*.pending")
	if err != nil {
		return nil, errors.New("DRAIN_RECEIPT_TEMP_CREATE_FAILED")
	}
	return &drainReceiptWriter{f, c.RequestsDrainedReceipt, ticks}, nil
}

func (w *drainReceiptWriter) cleanup() {
	if w != nil {
		w.file.Close()
		os.Remove(w.file.Name())
	}
}

func (w *drainReceiptWriter) publish(c Config, workers []workerState, counts scheduleCounters, p arrivalPlan,
	stop stopInfo, complete bool, active int64, elapsed time.Duration) error {
	if w == nil {
		return nil
	}
	mono, err := monotonicNanos()
	if err != nil {
		return err
	}
	r := drainedReceipt{SchemaVersion: 1, Kind: drainReceiptKind, Version: version, RunID: c.RunID, Label: c.Label,
		PID: os.Getpid(), StartTicks: w.ticks, DrainedMonotonicNanos: mono, RequestElapsedNanos: int64(elapsed),
		RequestsDrained: true, WorkersFinished: len(workers), ActiveRequests: active,
		MeasurementComplete: complete, StopReason: stop.Reason, Planned: p.total}
	for _, worker := range workers {
		for _, phase := range worker.phase {
			r.Sent += phase.sent
			r.Completed += phase.completed
			r.Correct += phase.correct
			r.Dropped += phase.dropped
		}
	}
	for i := range counts.scheduled {
		r.Scheduled += counts.scheduled[i]
		r.Dropped += counts.dropped[i]
	}
	r.Errors = r.Completed - r.Correct
	r.Cancelled = r.Planned - r.Scheduled
	if active != 0 || r.Sent != r.Completed {
		return errors.New("DRAIN_INCOMPLETE")
	}
	data, err := json.Marshal(r)
	if err != nil || len(data)+1 > maxDrainReceiptBytes {
		return errors.New("DRAIN_RECEIPT_SIZE")
	}
	if _, err = w.file.Write(append(data, '\n')); err != nil {
		return err
	}
	if err = w.file.Sync(); err != nil {
		return err
	}
	if err = w.file.Close(); err != nil {
		return err
	}
	// link is atomic and fails if the destination exists; rename could overwrite
	// prior evidence. Same-directory temp means the link stays on one filesystem.
	if err = os.Link(w.file.Name(), w.target); err != nil {
		return err
	}
	return nil
}
