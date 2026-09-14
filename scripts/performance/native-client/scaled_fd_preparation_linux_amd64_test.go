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

func scaledFixtureOps(t *testing.T, f *fdPreparationFixture, minimum int) fdPreparationOps {
	ops := f.operations(t)
	ops.dup = func(source, requested int) (int, error) {
		f.duplicateCalls++
		if source != 2 || requested != minimum {
			t.Errorf("unexpected scaled duplicate source=%d minimum=%d want=%d", source, requested, minimum)
		}
		return f.allocated, f.duplicateErr
	}
	return ops
}

func TestScaledFDPreparationTargetsAndUnchangedDisabledPath(t *testing.T) {
	for workers, want := range map[int]int{512: 1023, 1024: 2047, 2048: 4095} {
		minimum, err := fdPreparationMinimum(workers)
		if err != nil || minimum != want || minimum+1 < workers+128 {
			t.Fatalf("worker=%d minimum=%d want=%d err=%v", workers, minimum, want, err)
		}
		result, err := maybePrepareFDTableForWorkers(false, workers)
		if err != nil || result != nil {
			t.Fatal("disabled scaled preparation emitted metadata or an error")
		}
	}
	for _, workers := range []int{-1, 0, 1, 513, 1023, 1025, 2049} {
		if _, err := fdPreparationMinimum(workers); err == nil {
			t.Fatalf("invalid workers %d yielded a preparation target", workers)
		}
		result, err := maybePrepareFDTableForWorkers(true, workers)
		if err == nil || result != nil {
			t.Fatal("invalid worker selection attempted real FD preparation")
		}
	}
	if result, err := maybePrepareFDTableForWorkers(false, 0); result != nil || err != nil {
		t.Fatal("disabled path must not begin preparation or validate a target")
	}
	for _, minimum := range []int{-1, 0, 2, 1022, 1024, 2048, 4096} {
		f := newFDPreparationFixture()
		result, err := prepareFDTableWithMinimum(f.operations(t), minimum)
		checkFDPreparationFailure(t, result, err)
		if f.sizeCalls != 0 || f.limitCalls != 0 || f.duplicateCalls != 0 || len(f.closed) != 0 {
			t.Fatal("invalid internal minimum reached any FD or resource-limit operation")
		}
	}
}

func TestScaledFDPreparationExactTargetsAndInsufficientRlimit(t *testing.T) {
	for _, minimum := range []int{1023, 2047, 4095} {
		t.Run(strconv.Itoa(minimum), func(t *testing.T) {
			f := newFDPreparationFixture()
			f.allocated, f.afterSize = minimum, minimum+1
			f.beforeLimit = syscall.Rlimit{Cur: uint64(minimum + 1), Max: uint64(2 * (minimum + 1))}
			f.afterLimit = f.beforeLimit
			result, err := prepareFDTableWithMinimum(scaledFixtureOps(t, f, minimum), minimum)
			if err != nil || !result.Ready || result.RequestedMinimumFD != minimum || result.AfterFDSize != minimum+1 ||
				!result.NoLimitChange || !result.ClosedOwnedFD || f.duplicateCalls != 1 || len(f.closed) != 1 || f.closed[0] != minimum {
				t.Fatalf("scaled preparation did not own/close exactly its returned descriptor: result=%+v err=%v", result, err)
			}
			f = newFDPreparationFixture()
			f.beforeLimit = syscall.Rlimit{Cur: uint64(minimum), Max: uint64(2 * (minimum + 1))}
			result, err = prepareFDTableWithMinimum(scaledFixtureOps(t, f, minimum), minimum)
			checkFDPreparationFailure(t, result, err)
			if result.FailureReason != "RLIMIT_INSUFFICIENT_OR_INVALID" || f.duplicateCalls != 0 || len(f.closed) != 0 || result.CreatedFD {
				t.Fatal("insufficient existing limit allocated or closed a descriptor")
			}
		})
	}
}

func TestScaledFDPreparationRetainsEveryOwnershipFailureBoundary(t *testing.T) {
	for _, minimum := range []int{2047, 4095} {
		for _, mode := range []string{"dup_error", "returns_source", "returns_negative", "below_minimum", "missing_cloexec", "close_eintr", "limit_changed", "capacity_missing"} {
			t.Run(strconv.Itoa(minimum)+"/"+mode, func(t *testing.T) {
				f := newFDPreparationFixture()
				f.allocated, f.afterSize = minimum, minimum+1
				f.beforeLimit, f.afterLimit = syscall.Rlimit{Cur: 8192, Max: 8192}, syscall.Rlimit{Cur: 8192, Max: 8192}
				wantClose := 1
				switch mode {
				case "dup_error":
					f.allocated, f.duplicateErr, wantClose = -1, syscall.EMFILE, 0
				case "returns_source":
					f.allocated, wantClose = 2, 0
				case "returns_negative":
					f.allocated, wantClose = -1, 0
				case "below_minimum":
					f.allocated = minimum - 1
				case "missing_cloexec":
					f.allocatedFlags = 0
				case "close_eintr":
					f.closeErr = syscall.EINTR
				case "limit_changed":
					f.afterLimit.Cur--
				case "capacity_missing":
					f.afterSize = minimum
				}
				result, err := prepareFDTableWithMinimum(scaledFixtureOps(t, f, minimum), minimum)
				checkFDPreparationFailure(t, result, err)
				if f.duplicateCalls != 1 || len(f.closed) != wantClose || result.CloseAttempted != (wantClose == 1) {
					t.Fatalf("ownership/close count changed for %s: closed=%v result=%+v", mode, f.closed, result)
				}
				if wantClose == 1 && (f.closed[0] != f.allocated || f.closed[0] == 2) {
					t.Fatal("closed an unowned source instead of the returned duplicate")
				}
				if mode == "close_eintr" && result.ClosedOwnedFD {
					t.Fatal("interrupted close must remain terminal and unconfirmed, never retried")
				}
			})
		}
	}
}

type scaledFDState struct {
	flags int
	stat  syscall.Stat_t
}

func scaledExistingFDs(t *testing.T) map[int]scaledFDState {
	t.Helper()
	entries, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		t.Fatal(err)
	}
	result := make(map[int]scaledFDState)
	for _, entry := range entries {
		fd, err := strconv.Atoi(entry.Name())
		if err != nil {
			t.Fatal(err)
		}
		flags, err := fdPreparationTestGetFlags(fd)
		if errors.Is(err, syscall.EBADF) {
			continue // ReadDir's own already-closed directory descriptor.
		}
		if err != nil {
			t.Fatal(err)
		}
		var stat syscall.Stat_t
		if err := syscall.Fstat(fd, &stat); err != nil {
			t.Fatal(err)
		}
		result[fd] = scaledFDState{flags, stat}
	}
	if _, ok := result[2]; !ok {
		t.Fatal("real test child requires an existing stderr descriptor")
	}
	return result
}

func TestScaledFDPreparationRealProcess(t *testing.T) {
	const marker = "SHORTLINK_SCALED_FD_TEST_CHILD"
	mode := os.Getenv(marker)
	if mode == "" {
		for _, value := range []string{"1024:fresh", "1024:occupied", "2048:fresh", "2048:occupied"} {
			t.Run(value, func(t *testing.T) {
				cmd := exec.Command(os.Args[0], "-test.run=^TestScaledFDPreparationRealProcess$", "-test.v", "-test.timeout=20s")
				cmd.Env = append(os.Environ(), marker+"="+value)
				cmd.Stderr = os.Stderr
				output, err := cmd.Output()
				if err != nil || !strings.Contains(string(output), "FD_SCALED_REAL_PASS "+value) {
					t.Fatalf("scaled FD child failed: mode=%s err=%v\n%s", value, err, output)
				}
				t.Logf("%s", output)
			})
		}
		return
	}
	parts := strings.Split(mode, ":")
	if len(parts) != 2 || (parts[0] != "1024" && parts[0] != "2048") || (parts[1] != "fresh" && parts[1] != "occupied") {
		t.Fatalf("unexpected child mode %q", mode)
	}
	workers, _ := strconv.Atoi(parts[0])
	minimum, err := fdPreparationMinimum(workers)
	if err != nil {
		t.Fatal(err)
	}
	var beforeLimit syscall.Rlimit
	if err := syscall.Getrlimit(syscall.RLIMIT_NOFILE, &beforeLimit); err != nil || beforeLimit.Cur <= uint64(minimum+1) {
		t.Fatalf("existing child rlimit cannot support this smoke: limit=%+v err=%v", beforeLimit, err)
	}
	if parts[1] == "occupied" {
		if _, err := fdPreparationTestGetFlags(minimum); errors.Is(err, syscall.EBADF) {
			fd, _, errno := syscall.Syscall(syscall.SYS_FCNTL, 2, uintptr(syscall.F_DUPFD_CLOEXEC), uintptr(minimum))
			if errno != 0 {
				t.Fatal(errno)
			}
			defer syscall.Close(int(fd)) // Only this test-owned fixture descriptor.
			if int(fd) != minimum {
				t.Fatalf("fixture minimum unexpectedly occupied: allocated=%d", fd)
			}
		} else if err != nil {
			t.Fatal(err)
		}
	}
	existing := scaledExistingFDs(t)
	beforeSize := fdPreparationTestFDSize(t)
	result, err := maybePrepareFDTableForWorkers(true, workers)
	if err != nil || result == nil || !result.Ready || !result.NoLimitChange || !result.ClosedOwnedFD ||
		!result.CreatedFD || !result.CloseAttempted || result.BusinessRequests != 0 || result.RequestedMinimumFD != minimum ||
		result.PID != os.Getpid() || result.BeforeFDSize != beforeSize || result.AfterFDSize < minimum+1 || result.AfterFDSize < beforeSize {
		t.Fatalf("scaled real preparation failed: result=%+v err=%v", result, err)
	}
	if _, ownedBefore := existing[result.AllocatedFD]; ownedBefore {
		t.Fatal("preparation treated an existing descriptor as newly owned")
	}
	// Test closure before any /proc open could recycle the returned descriptor.
	if _, err := fdPreparationTestGetFlags(result.AllocatedFD); !errors.Is(err, syscall.EBADF) {
		t.Fatalf("owned duplicate remained open: fd=%d err=%v", result.AllocatedFD, err)
	}
	if parts[1] == "occupied" && result.AllocatedFD <= minimum {
		t.Fatal("preparation did not preserve the already occupied minimum")
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
		t.Fatal("scaled preparation altered the existing rlimit")
	}
	if got := fdPreparationTestFDSize(t); got != result.AfterFDSize || got < minimum+1 {
		t.Fatalf("expanded FD table not retained: got=%d result=%+v", got, result)
	}
	t.Logf("FD_SCALED_REAL_PASS %s pid=%d beforeFDSize=%d afterFDSize=%d minimum=%d allocated=%d preservedFDs=%d businessRequests=%d",
		mode, result.PID, beforeSize, result.AfterFDSize, minimum, result.AllocatedFD, len(existing), result.BusinessRequests)
}
