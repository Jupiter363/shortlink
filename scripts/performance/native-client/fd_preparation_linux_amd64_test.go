//go:build linux && amd64

package main

import (
	"errors"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"syscall"
	"testing"
)

// This fake records ownership operations. No OS descriptor or resource limit is
// changed by unit tests; the real syscall check runs in a separate test process.
type fdPreparationFixture struct {
	beforeSize, afterSize                              int
	sourceFlags, afterSourceFlags                      int
	allocated, allocatedFlags                          int
	beforeLimit, afterLimit                            syscall.Rlimit
	beforeSizeErr, afterSizeErr                        error
	beforeLimitErr, afterLimitErr                      error
	sourceErr, allocatedErr, duplicateErr, closeErr    error
	sizeCalls, limitCalls, sourceCalls, duplicateCalls int
	closed                                             []int
}

func newFDPreparationFixture() *fdPreparationFixture {
	return &fdPreparationFixture{
		beforeSize: 64, afterSize: 1024,
		allocated: 1023, allocatedFlags: syscall.FD_CLOEXEC,
		beforeLimit: syscall.Rlimit{Cur: 4096, Max: 8192},
		afterLimit:  syscall.Rlimit{Cur: 4096, Max: 8192},
	}
}

func (f *fdPreparationFixture) operations(t *testing.T) fdPreparationOps {
	t.Helper()
	return fdPreparationOps{
		pid: func() int { return 401 },
		fdSize: func() (int, error) {
			f.sizeCalls++
			if f.sizeCalls == 1 {
				return f.beforeSize, f.beforeSizeErr
			}
			return f.afterSize, f.afterSizeErr
		},
		limit: func() (syscall.Rlimit, error) {
			f.limitCalls++
			if f.limitCalls == 1 {
				return f.beforeLimit, f.beforeLimitErr
			}
			return f.afterLimit, f.afterLimitErr
		},
		getFD: func(fd int) (int, error) {
			if fd == 2 {
				f.sourceCalls++
				if f.sourceCalls == 1 {
					return f.sourceFlags, f.sourceErr
				}
				return f.afterSourceFlags, f.sourceErr
			}
			if fd != f.allocated {
				t.Errorf("read flags of unrelated descriptor: %d", fd)
			}
			return f.allocatedFlags, f.allocatedErr
		},
		dup: func(source, minimum int) (int, error) {
			f.duplicateCalls++
			if source != 2 || minimum != 1023 {
				t.Errorf("unexpected duplicate request: source=%d minimum=%d", source, minimum)
			}
			return f.allocated, f.duplicateErr
		},
		close: func(fd int) error {
			f.closed = append(f.closed, fd)
			if fd != f.allocated || fd == 2 {
				t.Errorf("attempted to close unowned descriptor: %d", fd)
			}
			return f.closeErr
		},
	}
}

func checkFDPreparationFailure(t *testing.T, result *FDPreparation, err error) {
	t.Helper()
	if err == nil {
		t.Fatal("preparation failure was reported as success")
	}
	if result == nil {
		t.Fatal("failed attempt must retain diagnostic metadata")
	}
	if !result.Attempted || result.Ready || result.FailureReason == "" {
		t.Fatalf("failed attempt is not explicitly unsuccessful: %+v", result)
	}
	if result.BusinessRequests != 0 {
		t.Fatalf("preparation recorded business requests: %+v", result)
	}
}

func TestFDPreparationDisabledHasNoMetadata(t *testing.T) {
	result, err := maybePrepareFDTable(false)
	if err != nil || result != nil {
		t.Fatalf("disabled preparation must be an absent optional result: result=%+v err=%v", result, err)
	}
}

func TestFDPreparationSuccessPreservesSourceAndLimits(t *testing.T) {
	for _, flags := range []int{0, syscall.FD_CLOEXEC} {
		t.Run(strconv.Itoa(flags), func(t *testing.T) {
			f := newFDPreparationFixture()
			f.sourceFlags, f.afterSourceFlags = flags, flags
			result, err := prepareFDTableWith(f.operations(t))
			if err != nil || result == nil {
				t.Fatalf("prepare: result=%+v err=%v", result, err)
			}
			if !result.Attempted || !result.Ready || !result.CreatedFD ||
				!result.CloseAttempted || !result.ClosedOwnedFD || !result.NoLimitChange ||
				result.BusinessRequests != 0 || result.FailureReason != "" {
				t.Fatalf("incomplete successful result: %+v", result)
			}
			if result.PID != 401 || result.BeforeFDSize != 64 || result.AfterFDSize != 1024 ||
				result.RequestedMinimumFD != 1023 || result.SourceFD != 2 || result.AllocatedFD != 1023 ||
				result.SourceFlagsBefore != flags || result.SourceFlagsAfter != flags ||
				result.LimitBefore != f.beforeLimit || result.LimitAfter != f.beforeLimit {
				t.Fatalf("incorrect preparation evidence: %+v", result)
			}
			if f.duplicateCalls != 1 || len(f.closed) != 1 || f.closed[0] != 1023 {
				t.Fatalf("descriptor ownership not discharged exactly once: dup=%d closed=%v", f.duplicateCalls, f.closed)
			}
		})
	}
}

func TestFDPreparationPreallocationFailuresNeverCloseUnownedFD(t *testing.T) {
	cases := []struct {
		name   string
		change func(*fdPreparationFixture)
	}{
		{"fd_size_unavailable", func(f *fdPreparationFixture) { f.beforeSizeErr = syscall.EIO }},
		{"rlimit_unavailable", func(f *fdPreparationFixture) { f.beforeLimitErr = syscall.EPERM }},
		{"rlimit_too_small", func(f *fdPreparationFixture) { f.beforeLimit.Cur = 1023 }},
		{"invalid_rlimit", func(f *fdPreparationFixture) { f.beforeLimit.Cur = f.beforeLimit.Max + 1 }},
		{"stderr_unavailable", func(f *fdPreparationFixture) { f.sourceErr = syscall.EBADF }},
		{"duplicate_failed", func(f *fdPreparationFixture) { f.allocated = -1; f.duplicateErr = syscall.EMFILE }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			f := newFDPreparationFixture()
			tc.change(f)
			result, err := prepareFDTableWith(f.operations(t))
			checkFDPreparationFailure(t, result, err)
			if len(f.closed) != 0 || result.CreatedFD || result.CloseAttempted || result.ClosedOwnedFD {
				t.Fatalf("failure before creation touched a descriptor: closed=%v result=%+v", f.closed, result)
			}
			if tc.name != "duplicate_failed" && f.duplicateCalls != 0 {
				t.Fatalf("invalid prerequisite still allocated: calls=%d", f.duplicateCalls)
			}
		})
	}
}

func TestFDPreparationPostallocationFailuresCloseOwnedFDOnce(t *testing.T) {
	cases := []struct {
		name   string
		change func(*fdPreparationFixture)
	}{
		{"owned_flags_unavailable", func(f *fdPreparationFixture) { f.allocatedErr = syscall.EBADF }},
		{"owned_cloexec_missing", func(f *fdPreparationFixture) { f.allocatedFlags = 0 }},
		{"capacity_not_retained", func(f *fdPreparationFixture) { f.afterSize = 512 }},
		{"capacity_read_failed", func(f *fdPreparationFixture) { f.afterSizeErr = syscall.EIO }},
		{"limit_read_failed", func(f *fdPreparationFixture) { f.afterLimitErr = syscall.EIO }},
		{"limit_changed", func(f *fdPreparationFixture) { f.afterLimit.Cur-- }},
		{"source_flags_changed", func(f *fdPreparationFixture) { f.afterSourceFlags = syscall.FD_CLOEXEC }},
		{"close_failed_no_retry", func(f *fdPreparationFixture) { f.closeErr = syscall.EINTR }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			f := newFDPreparationFixture()
			tc.change(f)
			result, err := prepareFDTableWith(f.operations(t))
			checkFDPreparationFailure(t, result, err)
			if f.duplicateCalls != 1 || len(f.closed) != 1 || f.closed[0] != f.allocated ||
				!result.CreatedFD || !result.CloseAttempted {
				t.Fatalf("owned descriptor not closed exactly once: dup=%d closed=%v result=%+v", f.duplicateCalls, f.closed, result)
			}
			if result.ClosedOwnedFD != (f.closeErr == nil) {
				t.Fatalf("close result misreported: closeErr=%v result=%+v", f.closeErr, result)
			}
		})
	}
}

func TestFDPreparationDoesNotAssumeMinimumFDWasFree(t *testing.T) {
	f := newFDPreparationFixture()
	f.beforeSize, f.afterSize, f.allocated = 1024, 2048, 1024
	result, err := prepareFDTableWith(f.operations(t))
	if err != nil || result == nil || !result.Ready {
		t.Fatalf("occupied minimum: result=%+v err=%v", result, err)
	}
	if result.AllocatedFD != 1024 || len(f.closed) != 1 || f.closed[0] != 1024 {
		t.Fatalf("did not close returned owned descriptor: closed=%v result=%+v", f.closed, result)
	}
}

func TestFDPreparationInvalidOwnershipNeverClosesStderr(t *testing.T) {
	for _, fd := range []int{-1, 2} {
		t.Run(strconv.Itoa(fd), func(t *testing.T) {
			f := newFDPreparationFixture()
			f.allocated = fd
			result, err := prepareFDTableWith(f.operations(t))
			checkFDPreparationFailure(t, result, err)
			if len(f.closed) != 0 || result.CreatedFD || result.CloseAttempted {
				t.Fatalf("impossible return treated as owned: fd=%d closed=%v result=%+v", fd, f.closed, result)
			}
		})
	}
}

func TestFDPreparationStatusParserRejectsMissingOrAmbiguousEvidence(t *testing.T) {
	for _, status := range []string{"Name:\tfixture\nFDSize:\t64\nThreads:\t4\n", "FDSize: 2048\n"} {
		got, err := fdSizeFromStatus(status)
		if err != nil || got <= 0 {
			t.Fatalf("valid status rejected: value=%d err=%v", got, err)
		}
	}
	for _, status := range []string{
		"Name:\tfixture\n", "FDSize:\n", "FDSize: 0\n", "FDSize: -1\n",
		"FDSize: nope\n", "FDSize: 64 extra\n", "FDSize: 64\nFDSize: 128\n",
		"FDSize: 999999999999999999999999999999999999999\n",
	} {
		if value, err := fdSizeFromStatus(status); err == nil {
			t.Fatalf("invalid status yielded successful evidence: input=%q value=%d", status, value)
		}
	}
}

func fdPreparationTestGetFlags(fd int) (int, error) {
	value, _, errno := syscall.Syscall(syscall.SYS_FCNTL, uintptr(fd), uintptr(syscall.F_GETFD), 0)
	if errno != 0 {
		return 0, errno
	}
	return int(value), nil
}

func fdPreparationTestFDSize(t *testing.T) int {
	t.Helper()
	data, err := os.ReadFile("/proc/self/status")
	if err != nil {
		t.Fatal(err)
	}
	for _, line := range strings.Split(string(data), "\n") {
		fields := strings.Fields(line)
		if len(fields) == 2 && fields[0] == "FDSize:" {
			value, err := strconv.Atoi(fields[1])
			if err != nil {
				t.Fatal(err)
			}
			return value
		}
	}
	t.Fatal("/proc/self/status lacks FDSize")
	return 0
}

func TestFDPreparationRealProcess(t *testing.T) {
	const marker = "SHORTLINK_FD_PREPARATION_TEST_CHILD"
	mode := os.Getenv(marker)
	if mode == "" {
		for _, value := range []string{"fresh", "occupied1023"} {
			t.Run(value, func(t *testing.T) {
				cmd := exec.Command(os.Args[0], "-test.run=^TestFDPreparationRealProcess$", "-test.v", "-test.timeout=20s")
				cmd.Env = append(os.Environ(), marker+"="+value)
				cmd.Stderr = os.Stderr // The actual source descriptor must exist in the child.
				output, err := cmd.Output()
				if err != nil {
					t.Fatalf("child failed: %v\n%s", err, output)
				}
				if !strings.Contains(string(output), "FD_PREPARATION_REAL_PASS "+value) {
					t.Fatalf("child supplied no successful syscall evidence:\n%s", output)
				}
				t.Logf("%s", output)
			})
		}
		return
	}
	if mode != "fresh" && mode != "occupied1023" {
		t.Fatalf("unexpected child mode %q", mode)
	}
	var beforeLimit syscall.Rlimit
	if err := syscall.Getrlimit(syscall.RLIMIT_NOFILE, &beforeLimit); err != nil {
		t.Fatal(err)
	}
	if beforeLimit.Cur <= 1024 {
		t.Fatalf("real syscall fixture requires existing RLIMIT_NOFILE > 1024, got %+v", beforeLimit)
	}
	sourceFlags, err := fdPreparationTestGetFlags(2)
	if err != nil {
		t.Fatalf("child stderr: %v", err)
	}
	var sourceStat syscall.Stat_t
	if err := syscall.Fstat(2, &sourceStat); err != nil {
		t.Fatal(err)
	}

	var occupiedFlags int
	var occupiedStat syscall.Stat_t
	if mode == "occupied1023" {
		// Do not overwrite an existing 1023. If absent, create a descriptor owned
		// only by this test child and retain it throughout prepareFDTable.
		_, err := fdPreparationTestGetFlags(1023)
		if errors.Is(err, syscall.EBADF) {
			fd, _, errno := syscall.Syscall(syscall.SYS_FCNTL, 2, uintptr(syscall.F_DUPFD_CLOEXEC), 1023)
			if errno != 0 {
				t.Fatal(errno)
			}
			defer syscall.Close(int(fd))
			if fd != 1023 {
				t.Fatalf("unexpected concurrent occupation of fixture FD: got %d", fd)
			}
		} else if err != nil {
			t.Fatal(err)
		}
		occupiedFlags, err = fdPreparationTestGetFlags(1023)
		if err != nil {
			t.Fatal(err)
		}
		if err := syscall.Fstat(1023, &occupiedStat); err != nil {
			t.Fatal(err)
		}
	}
	beforeSize := fdPreparationTestFDSize(t)
	result, err := prepareFDTable()
	if err != nil || result == nil || !result.Attempted || !result.Ready ||
		!result.CreatedFD || !result.CloseAttempted || !result.ClosedOwnedFD ||
		!result.NoLimitChange || result.FailureReason != "" || result.BusinessRequests != 0 {
		t.Fatalf("real preparation failed: result=%+v err=%v", result, err)
	}
	if result.PID != os.Getpid() || result.SourceFD != 2 || result.RequestedMinimumFD != 1023 ||
		result.BeforeFDSize != beforeSize || result.AfterFDSize < 1024 || result.AfterFDSize < beforeSize {
		t.Fatalf("invalid real metadata: beforeSize=%d result=%+v", beforeSize, result)
	}
	if result.AllocatedFD < 1023 || (mode == "occupied1023" && result.AllocatedFD < 1024) {
		t.Fatalf("allocated FD violates occupied-minimum contract: %+v", result)
	}
	// Check closure before opening /proc again, which could otherwise reuse a
	// descriptor and make a successful close appear to be a leak.
	if _, err := fdPreparationTestGetFlags(result.AllocatedFD); !errors.Is(err, syscall.EBADF) {
		t.Fatalf("owned duplicate remains open (or unexpected error): fd=%d err=%v", result.AllocatedFD, err)
	}
	afterSourceFlags, err := fdPreparationTestGetFlags(2)
	if err != nil || afterSourceFlags != sourceFlags || result.SourceFlagsBefore != sourceFlags || result.SourceFlagsAfter != sourceFlags {
		t.Fatalf("source descriptor flags changed: before=%d after=%d err=%v result=%+v", sourceFlags, afterSourceFlags, err, result)
	}
	var afterSourceStat syscall.Stat_t
	if err := syscall.Fstat(2, &afterSourceStat); err != nil {
		t.Fatal(err)
	}
	if sourceStat.Dev != afterSourceStat.Dev || sourceStat.Ino != afterSourceStat.Ino || sourceStat.Mode != afterSourceStat.Mode {
		t.Fatal("source descriptor no longer identifies the original object")
	}
	if mode == "occupied1023" {
		flags, err := fdPreparationTestGetFlags(1023)
		var after syscall.Stat_t
		statErr := syscall.Fstat(1023, &after)
		if err != nil || statErr != nil || flags != occupiedFlags || after.Dev != occupiedStat.Dev || after.Ino != occupiedStat.Ino || after.Mode != occupiedStat.Mode {
			t.Fatalf("pre-existing 1023 was changed or closed: flags=%d flagErr=%v statErr=%v", flags, err, statErr)
		}
	}
	var afterLimit syscall.Rlimit
	if err := syscall.Getrlimit(syscall.RLIMIT_NOFILE, &afterLimit); err != nil {
		t.Fatal(err)
	}
	if beforeLimit != afterLimit || result.LimitBefore != beforeLimit || result.LimitAfter != afterLimit {
		t.Fatalf("real rlimit changed or metadata mismatch: before=%+v after=%+v result=%+v", beforeLimit, afterLimit, result)
	}
	if actual := fdPreparationTestFDSize(t); actual < 1024 || actual != result.AfterFDSize {
		t.Fatalf("FDSize expansion was not retained after close: actual=%d result=%+v", actual, result)
	}
	t.Logf("FD_PREPARATION_REAL_PASS %s pid=%d beforeFDSize=%d afterFDSize=%d allocated=%d businessRequests=%d", mode, result.PID, result.BeforeFDSize, result.AfterFDSize, result.AllocatedFD, result.BusinessRequests)
}
