//go:build linux && amd64

package main

import (
	"errors"
	"os"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// Preparation belongs to the generator, not the gateway or HTTP latency window.
// No socket is created; no rlimit or existing descriptor is changed.
type FDPreparation struct {
	Attempted          bool           `json:"attempted"`
	Ready              bool           `json:"ready"`
	PID                int            `json:"pid"`
	BeforeFDSize       int            `json:"beforeFDSize"`
	AfterFDSize        int            `json:"afterFDSize"`
	RequestedMinimumFD int            `json:"requestedMinimumFD"`
	SourceFD           int            `json:"sourceFD"`
	SourceFlagsBefore  int            `json:"sourceFlagsBefore"`
	SourceFlagsAfter   int            `json:"sourceFlagsAfter"`
	AllocatedFD        int            `json:"allocatedFD"`
	CreatedFD          bool           `json:"createdFD"`
	CloseAttempted     bool           `json:"closeAttempted"`
	ClosedOwnedFD      bool           `json:"closedOwnedFD"`
	LimitBefore        syscall.Rlimit `json:"limitBefore"`
	LimitAfter         syscall.Rlimit `json:"limitAfter"`
	NoLimitChange      bool           `json:"noLimitChange"`
	BusinessRequests   int            `json:"businessRequests"`
	FailureReason      string         `json:"failureReason,omitempty"`
	StartedUTC         string         `json:"startedUTC"`
	FinishedUTC        string         `json:"finishedUTC"`
	ElapsedMillis      float64        `json:"elapsedMillis"`
}

type fdPreparationOps struct {
	pid    func() int
	fdSize func() (int, error)
	limit  func() (syscall.Rlimit, error)
	getFD  func(int) (int, error)
	dup    func(int, int) (int, error)
	close  func(int) error
}

func fdSizeFromStatus(status string) (int, error) {
	result := 0
	for _, line := range strings.Split(status, "\n") {
		if strings.HasPrefix(line, "FDSize:") {
			fields := strings.Fields(line)
			if len(fields) != 2 || result != 0 {
				return 0, errors.New("INVALID_FDSIZE")
			}
			value, err := strconv.Atoi(fields[1])
			if err != nil || value <= 0 {
				return 0, errors.New("INVALID_FDSIZE")
			}
			result = value
		}
	}
	if result == 0 {
		return 0, errors.New("MISSING_FDSIZE")
	}
	return result, nil
}

func defaultFDPreparationOps() fdPreparationOps {
	return fdPreparationOps{
		pid: os.Getpid,
		fdSize: func() (int, error) {
			status, err := os.ReadFile("/proc/self/status")
			if err != nil {
				return 0, err
			}
			return fdSizeFromStatus(string(status))
		},
		limit: func() (syscall.Rlimit, error) {
			var limit syscall.Rlimit
			err := syscall.Getrlimit(syscall.RLIMIT_NOFILE, &limit)
			return limit, err
		},
		getFD: func(fd int) (int, error) {
			flags, _, errno := syscall.Syscall(syscall.SYS_FCNTL, uintptr(fd), syscall.F_GETFD, 0)
			if errno != 0 {
				return 0, errno
			}
			return int(flags), nil
		},
		dup: func(fd, minimum int) (int, error) {
			// Unlike the standard library socket RawSyscall, this reports a
			// potentially blocking syscall to the Go runtime. Before measurement.
			duplicate, _, errno := syscall.Syscall(syscall.SYS_FCNTL, uintptr(fd), syscall.F_DUPFD_CLOEXEC, uintptr(minimum))
			if errno != 0 {
				return -1, errno
			}
			return int(duplicate), nil
		},
		close: syscall.Close,
	}
}

func prepareFDTableWith(ops fdPreparationOps) (result *FDPreparation, err error) {
	return prepareFDTableWithMinimum(ops, 1023)
}

func prepareFDTableWithMinimum(ops fdPreparationOps, minimum int) (result *FDPreparation, err error) {
	start := time.Now()
	result = &FDPreparation{Attempted: true, PID: ops.pid(), SourceFD: 2, RequestedMinimumFD: minimum,
		AllocatedFD: -1, SourceFlagsBefore: -1, SourceFlagsAfter: -1,
		StartedUTC: start.UTC().Format(time.RFC3339Nano)}
	defer func() {
		result.FinishedUTC = time.Now().UTC().Format(time.RFC3339Nano)
		result.ElapsedMillis = float64(time.Since(start)) / float64(time.Millisecond)
	}()
	fail := func(code string) (*FDPreparation, error) {
		result.FailureReason = code
		return result, errors.New("FD_PREPARATION_" + code)
	}
	if minimum != 1023 && minimum != 2047 && minimum != 4095 && minimum != 8191 {
		return fail("MINIMUM_INVALID")
	}
	if result.PID <= 0 {
		return fail("INVALID_PID")
	}
	result.BeforeFDSize, err = ops.fdSize()
	if err != nil || result.BeforeFDSize <= 0 {
		return fail("FDSIZE_BEFORE_FAILED")
	}
	result.LimitBefore, err = ops.limit()
	if err != nil {
		return fail("RLIMIT_BEFORE_FAILED")
	}
	if result.LimitBefore.Cur <= uint64(result.RequestedMinimumFD) || result.LimitBefore.Cur > result.LimitBefore.Max {
		return fail("RLIMIT_INSUFFICIENT_OR_INVALID")
	}
	result.SourceFlagsBefore, err = ops.getFD(result.SourceFD)
	if err != nil {
		return fail("SOURCE_FD_UNAVAILABLE")
	}
	duplicate, duplicateErr := ops.dup(result.SourceFD, result.RequestedMinimumFD)
	if duplicateErr != nil {
		return fail("DUPLICATE_FAILED")
	}
	// F_DUPFD_CLOEXEC cannot return the source or a negative FD on success.
	// Refuse these impossible values without ever closing an unowned source.
	if duplicate < 0 || duplicate == result.SourceFD {
		return fail("DUPLICATE_OWNERSHIP_INVALID")
	}
	result.AllocatedFD, result.CreatedFD = duplicate, true
	firstFailure := ""
	note := func(condition bool, code string) {
		if condition && firstFailure == "" {
			firstFailure = code
		}
	}
	note(duplicate < result.RequestedMinimumFD || uint64(duplicate) >= result.LimitBefore.Cur, "DUPLICATE_RANGE_INVALID")
	duplicateFlags, flagErr := ops.getFD(duplicate)
	note(flagErr != nil || duplicateFlags&syscall.FD_CLOEXEC == 0, "DUPLICATE_CLOEXEC_INVALID")
	// Close only the successfully returned owned descriptor, even if validation
	// failed. A close error is terminal and is NEVER retried (FD reuse hazard).
	result.CloseAttempted = true
	closeErr := ops.close(duplicate)
	result.ClosedOwnedFD = closeErr == nil
	note(closeErr != nil, "OWNED_CLOSE_FAILED")
	result.AfterFDSize, err = ops.fdSize()
	note(err != nil || result.AfterFDSize <= duplicate || result.AfterFDSize < result.BeforeFDSize, "FDSIZE_AFTER_INSUFFICIENT")
	result.LimitAfter, err = ops.limit()
	result.NoLimitChange = err == nil && result.LimitAfter == result.LimitBefore
	note(!result.NoLimitChange, "RLIMIT_CHANGED_OR_UNAVAILABLE")
	result.SourceFlagsAfter, err = ops.getFD(result.SourceFD)
	note(err != nil || result.SourceFlagsAfter != result.SourceFlagsBefore, "SOURCE_FD_CHANGED")
	if firstFailure != "" {
		return fail(firstFailure)
	}
	result.Ready = true
	return result, nil
}

func prepareFDTable() (*FDPreparation, error) { return prepareFDTableWith(defaultFDPreparationOps()) }

func maybePrepareFDTable(enabled bool) (*FDPreparation, error) {
	if !enabled {
		return nil, nil // No clock, syscall, allocation, or preparation metadata.
	}
	return prepareFDTable()
}

func fdPreparationMinimum(workers int) (int, error) {
	if workers != 512 && workers != 1024 && workers != 2048 && workers != 4096 {
		return 0, errors.New("FD_PREPARATION_WORKER_COUNT_INVALID")
	}
	// One socket per owner plus 128 descriptors for the runtime and output.
	// Touch the last FD in the required power-of-two table before measurement.
	size := 1
	for size < workers+128 {
		size <<= 1
	}
	return size - 1, nil
}

func maybePrepareFDTableForWorkers(enabled bool, workers int) (*FDPreparation, error) {
	if !enabled {
		return nil, nil
	}
	minimum, err := fdPreparationMinimum(workers)
	if err != nil {
		return nil, err
	}
	return prepareFDTableWithMinimum(defaultFDPreparationOps(), minimum)
}
