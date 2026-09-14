//go:build linux && amd64

package main

import (
	"errors"
	"os"
	"os/exec"
	"strings"
	"syscall"
	"testing"
)

func TestCorrelatedFDPreparation4096TargetAndDisabledPath(t *testing.T) {
	minimum, err := fdPreparationMinimum(4096)
	if err != nil || minimum != 8191 {
		t.Fatalf("4096-owner FD target=%d want=8191 err=%v", minimum, err)
	}
	if result, err := maybePrepareFDTableForWorkers(false, 4096); result != nil || err != nil {
		t.Fatal("disabled 4096-owner preparation performed work or emitted metadata")
	}
	for _, workers := range []int{4095, 4097, 8192} {
		if _, err := fdPreparationMinimum(workers); err == nil {
			t.Fatalf("unsupported owner count produced a target: %d", workers)
		}
		if result, err := maybePrepareFDTableForWorkers(true, workers); result != nil || err == nil {
			t.Fatal("unsupported owner count reached real FD preparation")
		}
	}
	for _, minimum := range []int{8190, 8192, 16383} {
		f := newFDPreparationFixture()
		result, err := prepareFDTableWithMinimum(f.operations(t), minimum)
		checkFDPreparationFailure(t, result, err)
		if result.FailureReason != "MINIMUM_INVALID" || f.sizeCalls != 0 || f.limitCalls != 0 ||
			f.sourceCalls != 0 || f.duplicateCalls != 0 || len(f.closed) != 0 {
			t.Fatal("invalid target reached an FD or resource-limit operation")
		}
	}
}

func TestCorrelatedFDPreparation8191ExactLimitAndOccupiedMinimum(t *testing.T) {
	for _, occupied := range []bool{false, true} {
		f := newFDPreparationFixture()
		f.allocated, f.afterSize = 8191, 8192
		f.beforeLimit = syscall.Rlimit{Cur: 8192, Max: 16384}
		if occupied {
			f.allocated, f.beforeSize, f.afterSize = 8192, 8192, 16384
			f.beforeLimit.Cur = 8193
		}
		f.afterLimit = f.beforeLimit
		result, err := prepareFDTableWithMinimum(scaledFixtureOps(t, f, 8191), 8191)
		if err != nil || result == nil || !result.Ready || !result.CreatedFD || !result.CloseAttempted ||
			!result.ClosedOwnedFD || !result.NoLimitChange || result.RequestedMinimumFD != 8191 ||
			result.AllocatedFD != f.allocated || result.AfterFDSize != f.afterSize ||
			result.LimitBefore != f.beforeLimit || result.LimitAfter != f.beforeLimit || result.BusinessRequests != 0 ||
			f.duplicateCalls != 1 || len(f.closed) != 1 || f.closed[0] != f.allocated {
			t.Fatalf("8191 preparation ownership/limit mismatch: occupied=%v result=%+v err=%v closed=%v", occupied, result, err, f.closed)
		}
	}
	for _, limit := range []syscall.Rlimit{{Cur: 8191, Max: 16384}, {Cur: 8192, Max: 8191}} {
		f := newFDPreparationFixture()
		f.beforeLimit = limit
		result, err := prepareFDTableWithMinimum(scaledFixtureOps(t, f, 8191), 8191)
		checkFDPreparationFailure(t, result, err)
		if result.FailureReason != "RLIMIT_INSUFFICIENT_OR_INVALID" || result.CreatedFD || result.CloseAttempted ||
			f.sourceCalls != 0 || f.duplicateCalls != 0 || len(f.closed) != 0 {
			t.Fatalf("insufficient or invalid RLIMIT touched a descriptor: limit=%+v result=%+v", limit, result)
		}
	}
}

func TestCorrelatedFDPreparation8191ClosesOnlyOwnedDescriptorOnce(t *testing.T) {
	for _, mode := range []string{"dup_error", "returns_source", "returns_negative", "below_minimum", "at_limit", "missing_cloexec", "close_eintr", "limit_changed", "capacity_missing", "source_changed"} {
		t.Run(mode, func(t *testing.T) {
			f := newFDPreparationFixture()
			f.allocated, f.afterSize = 8191, 8192
			f.beforeLimit, f.afterLimit = syscall.Rlimit{Cur: 16384, Max: 16384}, syscall.Rlimit{Cur: 16384, Max: 16384}
			wantClose := 1
			switch mode {
			case "dup_error":
				f.allocated, f.duplicateErr, wantClose = -1, syscall.EMFILE, 0
			case "returns_source":
				f.allocated, wantClose = 2, 0
			case "returns_negative":
				f.allocated, wantClose = -1, 0
			case "below_minimum":
				f.allocated = 8190
			case "at_limit":
				f.allocated, f.afterSize = 16384, 32768
			case "missing_cloexec":
				f.allocatedFlags = 0
			case "close_eintr":
				f.closeErr = syscall.EINTR
			case "limit_changed":
				f.afterLimit.Cur--
			case "capacity_missing":
				f.afterSize = 8191
			case "source_changed":
				f.afterSourceFlags = syscall.FD_CLOEXEC
			}
			result, err := prepareFDTableWithMinimum(scaledFixtureOps(t, f, 8191), 8191)
			checkFDPreparationFailure(t, result, err)
			if f.duplicateCalls != 1 || len(f.closed) != wantClose || result.CloseAttempted != (wantClose == 1) ||
				result.CreatedFD != (wantClose == 1) {
				t.Fatalf("8191 ownership boundary changed: mode=%s closed=%v result=%+v", mode, f.closed, result)
			}
			if wantClose == 1 && (f.closed[0] != f.allocated || f.closed[0] == 2) {
				t.Fatal("closed an unowned source instead of the returned duplicate")
			}
			if result.ClosedOwnedFD != (wantClose == 1 && f.closeErr == nil) {
				t.Fatal("close confirmation or terminal EINTR semantics changed")
			}
		})
	}
}

func TestCorrelatedFDPreparation8191RealProcess(t *testing.T) {
	const marker = "SHORTLINK_CORRELATED_8191_FD_TEST_CHILD"
	mode := os.Getenv(marker)
	if mode == "" {
		for _, value := range []string{"fresh", "occupied8191"} {
			t.Run(value, func(t *testing.T) {
				cmd := exec.Command(os.Args[0], "-test.run=^TestCorrelatedFDPreparation8191RealProcess$", "-test.v", "-test.timeout=20s")
				cmd.Env = append(os.Environ(), marker+"="+value)
				cmd.Stderr = os.Stderr
				output, err := cmd.Output()
				if err != nil || !strings.Contains(string(output), "FD_CORRELATED_8191_REAL_PASS "+value) {
					t.Fatalf("8191 FD child failed: mode=%s err=%v\n%s", value, err, output)
				}
				t.Logf("%s", output)
			})
		}
		return
	}
	if mode != "fresh" && mode != "occupied8191" {
		t.Fatalf("unexpected child mode %q", mode)
	}
	const minimum = 8191
	var beforeLimit syscall.Rlimit
	if err := syscall.Getrlimit(syscall.RLIMIT_NOFILE, &beforeLimit); err != nil ||
		beforeLimit.Cur <= minimum || (mode == "occupied8191" && beforeLimit.Cur <= minimum+1) {
		t.Fatalf("existing child rlimit cannot support the 8191 fixture: limit=%+v err=%v", beforeLimit, err)
	}
	_, minimumErr := fdPreparationTestGetFlags(minimum)
	if mode == "fresh" {
		if !errors.Is(minimumErr, syscall.EBADF) {
			t.Fatalf("fresh fixture requires an unoccupied 8191, err=%v", minimumErr)
		}
	} else if errors.Is(minimumErr, syscall.EBADF) {
		fd, _, errno := syscall.Syscall(syscall.SYS_FCNTL, 2, uintptr(syscall.F_DUPFD_CLOEXEC), uintptr(minimum))
		if errno != 0 {
			t.Fatal(errno)
		}
		defer syscall.Close(int(fd)) // Only this child-owned fixture descriptor.
		if int(fd) != minimum {
			t.Fatalf("fixture minimum unexpectedly occupied: allocated=%d", fd)
		}
	} else if minimumErr != nil {
		t.Fatal(minimumErr)
	}
	existing := scaledExistingFDs(t)
	beforeSize := fdPreparationTestFDSize(t)
	result, err := maybePrepareFDTableForWorkers(true, 4096)
	if err != nil || result == nil || !result.Attempted || !result.Ready || !result.NoLimitChange || !result.ClosedOwnedFD ||
		!result.CreatedFD || !result.CloseAttempted || result.BusinessRequests != 0 || result.RequestedMinimumFD != minimum ||
		result.SourceFD != 2 || result.PID != os.Getpid() || result.BeforeFDSize != beforeSize ||
		result.AfterFDSize < minimum+1 || result.AfterFDSize < beforeSize {
		t.Fatalf("8191 real preparation failed: result=%+v err=%v", result, err)
	}
	if _, ownedBefore := existing[result.AllocatedFD]; ownedBefore {
		t.Fatal("8191 preparation treated an existing descriptor as newly owned")
	}
	// Check closure before opening /proc, which could recycle the duplicate.
	if _, err := fdPreparationTestGetFlags(result.AllocatedFD); !errors.Is(err, syscall.EBADF) {
		t.Fatalf("owned duplicate remained open: fd=%d err=%v", result.AllocatedFD, err)
	}
	if (mode == "fresh" && result.AllocatedFD != minimum) || (mode == "occupied8191" && result.AllocatedFD <= minimum) {
		t.Fatalf("fresh/occupied 8191 allocation violated fixture ownership: mode=%s allocated=%d", mode, result.AllocatedFD)
	}
	for fd, before := range existing {
		flags, err := fdPreparationTestGetFlags(fd)
		var after syscall.Stat_t
		statErr := syscall.Fstat(fd, &after)
		if err != nil || statErr != nil || flags != before.flags || after.Dev != before.stat.Dev ||
			after.Ino != before.stat.Ino || after.Mode != before.stat.Mode {
			t.Fatalf("pre-existing FD changed or closed: fd=%d flagErr=%v statErr=%v", fd, err, statErr)
		}
	}
	var afterLimit syscall.Rlimit
	if err := syscall.Getrlimit(syscall.RLIMIT_NOFILE, &afterLimit); err != nil || beforeLimit != afterLimit ||
		result.LimitBefore != beforeLimit || result.LimitAfter != beforeLimit {
		t.Fatal("8191 preparation altered the existing rlimit")
	}
	if got := fdPreparationTestFDSize(t); got != result.AfterFDSize || got < minimum+1 {
		t.Fatalf("8191 FD table expansion was not retained: got=%d result=%+v", got, result)
	}
	t.Logf("FD_CORRELATED_8191_REAL_PASS %s pid=%d beforeFDSize=%d afterFDSize=%d minimum=%d allocated=%d preservedFDs=%d businessRequests=%d",
		mode, result.PID, beforeSize, result.AfterFDSize, minimum, result.AllocatedFD, len(existing), result.BusinessRequests)
}
