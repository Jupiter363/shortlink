"""One bounded k6 stage in the existing isolated APISIX network namespace.

This module never starts/stops application services or changes fixture/SQL state.
run(args) returns the persisted, sanitized result. Merely importing it performs no I/O.
"""
from __future__ import annotations

import argparse
import ctypes
import datetime as dt
import importlib.util
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
K6 = Path('/opt/shortlink-perf/tools/k6-v2.2.0')
WORKLOAD = Path(__file__).with_name('workload.js')
MODES = ('create', 'idempotent', 'batch', 'update', 'recycle', 'restore',
         'redirect', 'head', 'unknown_fixed', 'unknown_random')
REDIRECT_MODES = frozenset(('redirect', 'head', 'unknown_fixed', 'unknown_random'))
METADATA_INTENT_MODES = frozenset(('create', 'batch', 'idempotent')) | REDIRECT_MODES
SERVICES = ('gateway', 'shortlink-command', 'admin', 'shortlink-redirect')
INTERVAL = 5.0
DRAIN_TIMEOUT = 300.0
LOG_BYTES = 4 * 1024 * 1024


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def duration_ms(value):
    value = str(value)
    parts = re.findall(r'(\d+(?:\.\d+)?)(ms|s|m|h)', value)
    require(parts and ''.join(n + u for n, u in parts) == value, 'INVALID_DURATION')
    total = sum(float(n) * {'ms': 1, 's': 1000, 'm': 60000, 'h': 3600000}[u] for n, u in parts)
    require(math.isfinite(total), 'INVALID_DURATION')
    return total


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('state', type=Path)
    p.add_argument('--label', required=True)
    p.add_argument('--mode', required=True, choices=MODES)
    p.add_argument('--rate', required=True, type=int)
    p.add_argument('--time-unit', default='1s')
    p.add_argument('--warmup', default='30s')
    p.add_argument('--warmup-start-rate', type=int,
                   help='Redirect only: ramp from this rate during nonzero warmup; default stays constant')
    p.add_argument('--duration', default='90s')
    p.add_argument('--metric-seed-mode', choices=('per-request', 'per-vu-phase'), default='per-request',
                   help='Redirect only: optionally seed zero counters once per VU and entered phase')
    p.add_argument('--http-phase-timings', action='store_true',
                   help='Retain native k6 HTTP phase timing submetrics; no additional per-request custom metrics')
    p.add_argument('--redirect-input-mode', choices=('shared-array', 'vu-precomputed'), default='shared-array')
    p.add_argument('--skip-redirect-zero-rows', action='store_true')
    p.add_argument('--client-timeline', action='store_true',
                   help='Sample only the owned k6 PID independently of slower service collectors, to bounded JSONL')
    p.add_argument('--accounts', type=int, choices=(1, 20), default=1)
    p.add_argument('--workset', type=int, default=10)
    p.add_argument('--distribution', choices=('uniform', 'hot80', 'single80'), default='uniform')
    p.add_argument('--batch-size', type=int, choices=(10, 100, 500), default=10)
    p.add_argument('--vus', type=int, default=64)
    p.add_argument('--path', choices=('admin', 'direct', 'edge'), required=True)
    p.add_argument('--source-ip-count', type=int, choices=(1, 16, 64), default=1,
                   help='16/64 bind real loopback source addresses for edge redirect only')
    failure_policy = p.add_mutually_exclusive_group()
    failure_policy.add_argument('--allow-rejections', action='store_true')
    failure_policy.add_argument('--fail-fast-unexpected', action='store_true',
                                help='Abort k6 immediately on any unexpected response or client failure')
    p.add_argument('--p99-budget-ms', type=float, required=True)
    p.add_argument('--expected-status', type=int)
    return p


def validate_args(args):
    require(not (args.allow_rejections and getattr(args, 'fail_fast_unexpected', False)),
            'FAIL_FAST_CONFLICTS_WITH_ALLOWED_REJECTIONS')
    require(bool(re.fullmatch(r'[A-Za-z0-9_-]{1,40}', args.label)), 'INVALID_STAGE_LABEL')
    require(args.mode in MODES and args.path in ('admin', 'direct', 'edge'), 'INVALID_MODE_OR_PATH')
    require(1 <= args.rate <= 1000000 and type(args.rate) is int, 'INVALID_RATE')
    require(type(args.vus) is int and 1 <= args.vus <= 512, 'INVALID_VU_BUDGET')
    require(args.accounts in (1, 20) and 1 <= args.workset <= 200000, 'INVALID_FIXTURE_BUDGET')
    require(args.batch_size in (10, 100, 500), 'INVALID_BATCH_SIZE')
    require(args.distribution in ('uniform', 'hot80', 'single80'), 'INVALID_DISTRIBUTION')
    require((args.path == 'admin') == (args.mode not in REDIRECT_MODES), 'MODE_PATH_MISMATCH')
    source_ip_selection(args)
    seed_mode = getattr(args, 'metric_seed_mode', 'per-request')
    require(seed_mode in ('per-request', 'per-vu-phase'), 'INVALID_METRIC_SEED_MODE')
    require(seed_mode == 'per-request' or args.mode == 'redirect', 'PHASE_METRIC_SEED_REQUIRES_REDIRECT')
    input_mode = getattr(args, 'redirect_input_mode', 'shared-array')
    require(input_mode in ('shared-array', 'vu-precomputed'), 'INVALID_REDIRECT_INPUT_MODE')
    require(input_mode == 'shared-array' or (args.mode == 'redirect' and args.workset <= 10),
            'PRECOMPUTED_INPUT_REQUIRES_SMALL_REDIRECT_WORKSET')
    require(not getattr(args, 'skip_redirect_zero_rows', False) or args.mode == 'redirect',
            'SKIP_ZERO_ROWS_REQUIRES_REDIRECT')
    require(math.isfinite(args.p99_budget_ms) and args.p99_budget_ms > 0, 'INVALID_P99_BUDGET')
    warmup, measure, unit = map(duration_ms, (args.warmup, args.duration, args.time_unit))
    require(warmup >= 0 and measure >= 1000 and unit >= 1 and warmup + measure <= 28800000,
            'INVALID_STAGE_DURATION_BUDGET')
    start_rate = getattr(args, 'warmup_start_rate', None)
    if start_rate is not None:
        require(args.mode == 'redirect' and warmup > 0, 'WARMUP_RAMP_REQUIRES_REDIRECT_AND_NONZERO_WARMUP')
        require(type(start_rate) is int and 1 <= start_rate < args.rate, 'INVALID_WARMUP_START_RATE')
    if args.expected_status is not None:
        require(type(args.expected_status) is int and 100 <= args.expected_status <= 599,
                'INVALID_EXPECTED_STATUS')
        require(args.mode in REDIRECT_MODES or args.expected_status == 200,
                'MANAGEMENT_EXPECTS_SUCCESS')
    return {'warmupMs': warmup, 'measureMs': measure, 'unitMs': unit,
            'targetMeasureRequests': args.rate * measure / unit}


def read_json(path, limit=1048576):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= limit, 'INVALID_JSON_FILE')
    return json.loads(path.read_text(encoding='utf-8-sig'))


def validate_metadata_intent_mode(state, mode):
    require(type(state.get('pipelineFailureObservationRequired', False)) is bool,
            'INVALID_PIPELINE_FAILURE_OBSERVATION_FLAG')
    required = state.get('metadataIntentObservationRequired', False)
    require(type(required) is bool, 'INVALID_METADATA_INTENT_OBSERVATION_FLAG')
    if required:
        require(mode in METADATA_INTENT_MODES, 'METADATA_INTENT_PROFILE_FORBIDS_MUTATION_MODE')
        limit = state.get('budgets', {}).get('maximumPersistentPending')
        require(type(limit) is int and limit > 0, 'METADATA_INTENT_PENDING_BUDGET_UNAVAILABLE')


def write_json(path, value):
    encoded = json.dumps(value, ensure_ascii=False, allow_nan=False, indent=2) + '\n'
    temp = path.with_name('.' + path.name + '.tmp')
    with os.fdopen(os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'w', encoding='utf-8') as f:
        f.write(encoded)
    os.replace(temp, path)


def load_state(path):
    require(sys.platform.startswith('linux') and os.environ.get('WSL_DISTRO_NAME') == 'shortlink-refactor-it',
            'DEDICATED_LINUX_HOST_REQUIRED')
    path = Path(path).resolve(strict=True)
    state = read_json(path)
    require(state.get('phase') == 'READY' and state.get('servicesStopped') is not True, 'STATE_NOT_READY')
    require(bool(re.fullmatch(r'[A-Za-z0-9_-]{1,80}', state.get('runId', ''))), 'INVALID_RUN_ID')
    folder = Path(state.get('folder', '')).resolve(strict=True)
    require(folder.parent == (ROOT / '.work/performance').resolve() and folder.name == state['runId']
            and path == folder / 'state.json', 'STATE_OUTSIDE_ISOLATED_RUN')
    require(bool(re.fullmatch(r'shortlink_perf_[a-f0-9_]{6,48}', state.get('database', ''))), 'INVALID_SCHEMA')
    require(state.get('agentsStarted') is False and state.get('analyticsStarted') is False, 'UNEXPECTED_TOPOLOGY')
    pid = state.get('apisixPid')
    require(type(pid) is int and pid > 1 and Path(f'/proc/{pid}/ns/net').exists(), 'APISIX_NAMESPACE_UNAVAILABLE')
    require(not os.path.samefile('/proc/self/ns/net', f'/proc/{pid}/ns/net'), 'APISIX_NETNS_MUST_BE_DISTINCT')
    require(set(range(12, 16)).issubset(os.sched_getaffinity(0)), 'LOAD_GENERATOR_CPUSET_UNAVAILABLE')
    ipaddress.IPv4Address(state['networkGateway'])
    require(K6.is_file() and os.access(K6, os.X_OK) and WORKLOAD.is_file(), 'VERIFIED_K6_OR_WORKLOAD_MISSING')
    private = Path('/var/lib/shortlink-perf') / state['runId']
    fixture = Path(state.get('fixturePath', '')).absolute()
    require(fixture == private / 'fixture.json' and not private.is_symlink()
            and stat.S_IMODE(private.stat().st_mode) == 0o700
            and not fixture.is_symlink() and stat.S_IMODE(fixture.stat().st_mode) == 0o600,
            'PRIVATE_FIXTURE_PERMISSIONS_REQUIRED')
    data = read_json(fixture, 256 * 1024 * 1024)
    require(data.get('fixtureStatus') == 'READY' and data.get('runId') == state['runId']
            and data.get('database') == state['database'], 'FIXTURE_NOT_READY_OR_WRONG_RUN')
    require(data.get('normalizedClientIp') == '127.0.0.1', 'FIXTURE_CLIENT_IDENTITY_MISMATCH')
    require(data.get('managementHost') == state.get('managementHost')
            and data.get('redirectHost') == state.get('redirectHost'), 'FIXTURE_HOST_MISMATCH')
    maximum = state.get('maximumCreatedRows')
    minimum_disk = state.get('budgets', {}).get('minimumFreeDiskBytes')
    require(type(maximum) is int and maximum > 0 and type(minimum_disk) is int and minimum_disk > 0,
            'EXPLICIT_RESOURCE_BUDGETS_REQUIRED')
    require(set(SERVICES).issubset(state.get('jvmArtifacts', {})), 'SERVICE_PID_ROSTER_INCOMPLETE')
    return path, state, folder


def observer_module():
    # Do not rely on the caller's sys.path when run(args) is imported by a matrix runner.
    spec = importlib.util.spec_from_file_location('shortlink_perf_stage_observer', Path(__file__).with_name('observe.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def metric(snapshot, name):
    return [x for x in snapshot.get('apisix', {}).get('metrics', []) if x.get('name') == name]


def scalar(snapshot, name):
    entries = metric(snapshot, name)
    if len(entries) != 1 or not isinstance(entries[0].get('value'), (int, float)):
        return None
    return entries[0]['value']


def event_counters(snapshot, partial=False):
    result = {}
    expected = 6
    pipeline_required = snapshot.get('pipelineFailureObservationRequired', False)
    if type(pipeline_required) is not bool:
        return None
    if pipeline_required:
        pipeline = observer_module().pipeline_failure_counters(snapshot)
        if pipeline is None:
            return None
        result.update(pipeline)
        expected += len(pipeline)
    if snapshot.get('apisix', {}).get('status') == 'AVAILABLE':
        for kind in ('failed', 'rejected'):
            number = scalar(snapshot, 'shortlink_edge_events_' + kind)
            if number is not None:
                result['edge.' + kind] = number
    quality = snapshot.get('redirectQuality', {})
    if quality.get('status') == 'AVAILABLE':
        for lane in ('click', 'result'):
            for kind in ('failed', 'rejected'):
                number = quality.get('data', {}).get('lanes', {}).get(lane, {}).get(kind)
                if type(number) is int and number >= 0:
                    result['redirect.' + lane + '.' + kind] = number
    if snapshot.get('outboxObservationRequired'):
        expected += 4
        command = snapshot.get('services', {}).get('shortlink-command', {})
        if command.get('status') == 'AVAILABLE':
            for kind in ('ack_failures', 'broker_failures', 'retry_failures', 'fenced_acknowledgements'):
                values = [m.get('value') for m in command.get('metrics', [])
                          if m.get('name') == 'shortlink_outbox_' + kind + '_total']
                if len(values) == 1 and type(values[0]) in (int, float) and values[0] >= 0:
                    result['outbox.' + kind] = values[0]
    return result if partial or len(result) == expected else None


def counter_delta(before, after):
    a, b = event_counters(before or {}), event_counters(after or {})
    return None if a is None or b is None or a.keys() != b.keys() else {k: b[k] - a[k] for k in a}


def process_stat(pid):
    try:
        fields = Path(f'/proc/{int(pid)}/stat').read_text().rsplit(') ', 1)[1].split()
        if fields[0] in ('Z', 'X'):
            return None
        return {'pid': int(pid), 'ticks': int(fields[11]) + int(fields[12]),
                'startTicks': int(fields[19]), 'rssBytes': int(fields[21]) * os.sysconf('SC_PAGE_SIZE')}
    except (OSError, ValueError, IndexError):
        return None


class Monitor:
    def __init__(self, state, baseline=None, draining=False):
        self.state, self.baseline = state, baseline
        self.draining = draining
        self.incomplete_since = None
        self.pending_since = None
        self.starts = {}
        self.edge_identity = None
        self.growth = {}
        self.intent_observer = (observer_module()
                                if state.get('metadataIntentObservationRequired') is True else None)

    def inspect(self, snapshot, now):
        """Return a stable reason; required intent counts fail closed without a grace period."""
        intent = None
        pipeline_required = self.state.get('pipelineFailureObservationRequired', False)
        if type(pipeline_required) is not bool:
            return 'INVALID_PIPELINE_FAILURE_OBSERVATION_FLAG'
        if pipeline_required:
            if snapshot.get('pipelineFailureObservationRequired') is not True:
                return 'PIPELINE_FAILURE_OBSERVATION_FLAG_MISMATCH'
            if event_counters(snapshot) is None:
                return 'PIPELINE_FAILURE_COUNTERS_UNAVAILABLE'
            if self.baseline is not None and (self.baseline.get('pipelineFailureObservationRequired') is not True
                    or event_counters(self.baseline) is None):
                return 'PIPELINE_FAILURE_BASELINE_UNAVAILABLE'
        if type(self.state.get('metadataIntentObservationRequired', False)) is not bool:
            return 'INVALID_METADATA_INTENT_OBSERVATION_FLAG'
        if self.intent_observer is not None:
            if snapshot.get('metadataIntentObservationRequired') is not True:
                return 'METADATA_INTENT_OBSERVATION_FLAG_MISMATCH'
            intent = self.intent_observer.metadata_intent(snapshot)
            if intent['status'] != 'AVAILABLE':
                return intent['reason']
        disk = snapshot.get('host', {}).get('data', {}).get('disk', {}).get('freeBytes')
        disk_total = snapshot.get('host', {}).get('data', {}).get('disk', {}).get('totalBytes')
        rows = snapshot.get('mysql', {}).get('data', {}).get('routeCount')
        minimum_disk = max(self.state['budgets']['minimumFreeDiskBytes'], 10 * 1024 ** 3,
                           disk_total * .15 if disk_total is not None else 0)
        if disk is not None and disk < minimum_disk:
            return 'DISK_BUDGET_EXCEEDED'
        if rows is not None and rows > self.state['maximumCreatedRows']:
            return 'ROW_BUDGET_EXCEEDED'
        outbox_failed = snapshot.get('mysql', {}).get('data', {}).get('outbox', {}).get('terminalFailed')
        if outbox_failed is not None and outbox_failed > 0:
            return 'OUTBOX_TERMINAL_FAILURE'
        pending_limit = self.state['budgets'].get('maximumPersistentPending')
        if intent is not None and (type(pending_limit) is not int or pending_limit <= 0):
            return 'METADATA_INTENT_PENDING_BUDGET_UNAVAILABLE'
        outbox_pending = snapshot.get('mysql', {}).get('data', {}).get('outbox', {}).get('pending')
        if intent is not None and (type(outbox_pending) is not int or outbox_pending < 0):
            return 'OUTBOX_PENDING_INVALID_OR_UNAVAILABLE'
        if not self.draining and pending_limit is not None:
            for queue in ('outbox',) if intent is not None else ('outbox', 'metadata'):
                pending = snapshot.get('mysql', {}).get('data', {}).get(queue, {}).get('pending')
                if pending is not None and pending >= pending_limit:
                    return queue.upper() + '_PENDING_DIAGNOSTIC_BUDGET_EXCEEDED'
            if intent is not None and intent['totalUnfinished'] >= pending_limit:
                return 'METADATA_TOTAL_UNFINISHED_DIAGNOSTIC_BUDGET_EXCEEDED'
        for name, artifact in self.state['jvmArtifacts'].items():
            if name not in SERVICES:
                continue
            current = process_stat(artifact['pid'])
            if current is None:
                return 'SERVICE_PROCESS_DEAD'
            if name in self.starts and self.starts[name] != current['startTicks']:
                return 'SERVICE_PROCESS_IDENTITY_CHANGED'
            self.starts[name] = current['startTicks']
        if process_stat(self.state['apisixPid']) is None:
            return 'EDGE_PROCESS_DEAD'
        edge = snapshot.get('apisix', {})
        if edge.get('pidChangedSinceStart') or (edge.get('containerPid') not in (None, self.state['apisixPid'])):
            return 'EDGE_PROCESS_IDENTITY_CHANGED'
        identity = metric(snapshot, 'shortlink_edge_instance_info')
        if len(identity) == 1:
            identity = identity[0].get('labels')
            if self.edge_identity is not None and identity != self.edge_identity:
                return 'EDGE_BOOT_IDENTITY_CHANGED'
            self.edge_identity = identity
        if self.baseline is not None:
            initial, current = event_counters(self.baseline, True), event_counters(snapshot, True)
            if initial is None or current is None:
                return 'PIPELINE_FAILURE_COUNTERS_UNAVAILABLE'
            if any(v != initial[k] for k, v in current.items() if k in initial):
                return 'EVENT_FAILURE_OR_COUNTER_RESET'
            before_q = self.baseline.get('redirectQuality', {}).get('data', {})
            now_q = snapshot.get('redirectQuality', {}).get('data', {})
            if now_q and any(now_q.get(k) != before_q.get(k) for k in ('producerInstanceId', 'startedAt')):
                return 'REDIRECT_PRODUCER_IDENTITY_CHANGED'
        complete = (all(snapshot.get('services', {}).get(n, {}).get('status') == 'AVAILABLE' for n in SERVICES)
                    and all(snapshot.get(n, {}).get('status') == 'AVAILABLE'
                            for n in ('apisix', 'redirectQuality', 'mysql', 'redis', 'host'))
                    and scalar(snapshot, 'shortlink_edge_observation_complete') == 1
                    and scalar(snapshot, 'shortlink_edge_observation_faults') == 0
                    and scalar(snapshot, 'shortlink_edge_pending_count') is not None
                    and event_counters(snapshot) is not None and disk is not None and rows is not None)
        self.incomplete_since = None if complete else (now if self.incomplete_since is None else self.incomplete_since)
        if self.incomplete_since is not None and now - self.incomplete_since >= 30:
            return 'OBSERVATION_INCOMPLETE_30_SECONDS'
        aggregate = scalar(snapshot, 'shortlink_edge_pending_count')
        workers = metric(snapshot, 'shortlink_edge_worker_pending_count')
        worker_bytes = metric(snapshot, 'shortlink_edge_worker_pending_bytes')
        congested = ((aggregate is not None and aggregate >= 1600) or any(x['value'] >= 800 for x in workers)
                     or any(x['value'] >= .8 * 8388608 for x in worker_bytes))
        self.pending_since = (now if self.pending_since is None else self.pending_since) if congested else None
        if self.pending_since is not None and now - self.pending_since >= 30:
            return 'EDGE_PENDING_HIGH_30_SECONDS'
        # After input stops, an old queued job can age while pending is shrinking.
        # The bounded 300-second drain deadline governs recovery, not load-growth gates.
        queues = ('outbox', 'metadata') + (('metadata_intent',) if intent is not None else ())
        for queue in (() if self.draining else queues):
            for field in (('pending',) if queue == 'metadata_intent' else ('pending', 'oldestPendingAgeMs')):
                key = queue + '.' + field
                value = (intent['totalUnfinished'] if queue == 'metadata_intent' else
                         snapshot.get('mysql', {}).get('data', {}).get(queue, {}).get(field))
                if not isinstance(value, (int, float)):
                    self.growth.pop(key, None)
                    continue
                old = self.growth.get(key)
                if old is None or value < old[2]:
                    self.growth[key] = (now, value, value)
                else:
                    self.growth[key] = (old[0], old[1], value)
                    if now - old[0] >= 180 and value > old[1]:
                        return queue.upper() + '_BACKLOG_GROWING_180_SECONDS'
        return None


class ClientProcessStats:
    def __init__(self):
        self.first = self.last = None
        self.peak_cpu = 0.0
        self.peak_rss = self.rss_sum = self.samples = 0
        self.identity = None
        self.fault = None
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread = None
        self.timeline_bytes = 0

    def start(self, pid, destination):
        require(self._thread is None, 'CLIENT_SAMPLER_ALREADY_STARTED')
        # Start only AFTER Popen/preexec_fn, never fork while this sampler runs.
        self._thread = threading.Thread(target=self._sample_loop, args=(pid, destination),
                                        name='shortlink-owned-k6-sampler', daemon=True)
        self._thread.start()

    def _sample_loop(self, pid, destination):
        try:
            with destination.open('x', encoding='utf-8', buffering=1) as stream:
                while not self._stop.is_set():
                    began = time.monotonic()
                    point = self.capture(pid)
                    if point is not None:
                        line = json.dumps(point, allow_nan=False, separators=(',', ':')) + '\n'
                        size = len(line.encode('utf-8'))
                        if self.timeline_bytes + size > 16 * 1024 * 1024:
                            self.fault = 'CLIENT_TIMELINE_BUDGET_EXCEEDED'
                            return
                        stream.write(line)
                        self.timeline_bytes += size
                    if self.fault:
                        return
                    self._stop.wait(max(0.01, 1.0 - (time.monotonic() - began)))
        except Exception:
            self.fault = 'CLIENT_TIMELINE_UNAVAILABLE'

    def stop(self):
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=2)
            if self._thread.is_alive():
                self.fault = 'CLIENT_TIMELINE_DID_NOT_STOP'

    def capture(self, pid):
        current = process_stat(pid)
        if current is None:
            return
        now = time.monotonic()
        with self._lock:
            identity = (current['pid'], current['startTicks'])
            if (self.identity is not None and identity != self.identity) or (
                    self.last is not None and current['ticks'] < self.last[1]):
                self.fault = 'CLIENT_PROCESS_IDENTITY_OR_COUNTER_CHANGED'
                return
            self.identity = identity
            point = (now, current['ticks'])
            cpu, interval = None, None
            if self.last and now > self.last[0]:
                interval = now - self.last[0]
                cpu = (point[1] - self.last[1]) / os.sysconf('SC_CLK_TCK') / interval * 100
                self.peak_cpu = max(self.peak_cpu, cpu)
            self.first = self.first or point
            self.last = point
            self.samples += 1
            self.rss_sum += current['rssBytes']
            self.peak_rss = max(self.peak_rss, current['rssBytes'])
            return {'schemaVersion': 1, 'observedAt': dt.datetime.now(dt.timezone.utc).isoformat(),
                    'monotonicSeconds': now, **current, 'clockTicksPerSecond': os.sysconf('SC_CLK_TCK'),
                    'cpuPercent': cpu, 'actualIntervalSeconds': interval}

    def summary(self):
        with self._lock:
            return self._summary()

    def _summary(self):
        average = None
        if self.first and self.last[0] > self.first[0]:
            average = ((self.last[1] - self.first[1]) / os.sysconf('SC_CLK_TCK') /
                       (self.last[0] - self.first[0]) * 100)
        return {'samples': self.samples, 'cpuPercentMean': average,
                'cpuPercentPeak': self.peak_cpu if self.samples > 1 else None,
                'cpuPercentMeaning': '100 percent is one logical CPU; allowed CPUs are 12-15',
                'rssBytesPeak': self.peak_rss if self.samples else None,
                'rssBytesSampleMean': self.rss_sum / self.samples if self.samples else None,
                'samplingIntervalSeconds': 1,
                'samplingMode': 'INDEPENDENT_OWNED_PID_THREAD' if self._thread else 'STAGE_LOOP',
                'actualSampleSpanSeconds': self.last[0] - self.first[0] if self.first else None,
                'timelineBytes': self.timeline_bytes, 'fault': self.fault}


def child_parent_death_signal(expected_parent):
    # taskset and nsenter exec k6 in this same child PID. SIGKILL of the runner must not orphan load.
    if ctypes.CDLL(None, use_errno=True).prctl(1, signal.SIGINT, 0, 0, 0) != 0:
        raise OSError('Unable to set child parent-death signal')
    if os.getppid() != expected_parent:
        os.kill(os.getpid(), signal.SIGINT)


def stop_owned(process):
    if process is None or process.poll() is not None:
        return
    process.send_signal(signal.SIGINT)
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def build_env(args, state, stage):
    env = {k: v for k, v in os.environ.items() if not k.startswith(('PERF_', 'K6_'))}
    redirect = args.mode in REDIRECT_MODES
    base = ('http://127.0.0.1:9080' if args.path == 'edge' else
            'http://' + state['networkGateway'] + (':8002' if args.path == 'admin' else ':8003'))
    values = {'PERF_FIXTURE': state['fixturePath'], 'PERF_OUTPUT': str(stage / 'k6-summary.json'),
              'PERF_BASE_URL': base, 'PERF_HOST': state['redirectHost'] if redirect else state['managementHost'],
              'PERF_CLIENT_IP': '127.0.0.1', 'PERF_MODE': args.mode,
              'PERF_PATH_KIND': 'redirect' if redirect else 'admin', 'PERF_RUN_LABEL': args.label,
              'PERF_RATE': args.rate, 'PERF_TIME_UNIT': args.time_unit, 'PERF_WARMUP': args.warmup,
              'PERF_DURATION': args.duration, 'PERF_ACCOUNTS': args.accounts, 'PERF_WORKSET': args.workset,
              'PERF_DISTRIBUTION': args.distribution, 'PERF_BATCH_SIZE': args.batch_size,
              'PERF_VUS': args.vus, 'PERF_SEED': '42', 'PERF_SAMPLE_PHASE': 'measure',
              'PERF_ABORT_ON_FAILURE': 'true', 'PERF_ALLOW_REJECTIONS': str(args.allow_rejections).lower(),
              'PERF_FAIL_FAST_UNEXPECTED': str(getattr(args, 'fail_fast_unexpected', False)).lower(),
              'PERF_METRIC_SEED_MODE': getattr(args, 'metric_seed_mode', 'per-request'),
              'PERF_HTTP_PHASE_TIMINGS': str(getattr(args, 'http_phase_timings', False)).lower(),
              'PERF_REDIRECT_INPUT_MODE': getattr(args, 'redirect_input_mode', 'shared-array'),
              'PERF_SKIP_REDIRECT_ZERO_ROWS': str(getattr(args, 'skip_redirect_zero_rows', False)).lower(),
              'K6_NO_USAGE_REPORT': 'true', 'K6_LOG_OUTPUT': 'stderr'}
    if args.expected_status is not None:
        values['PERF_EXPECTED_STATUS'] = args.expected_status
    if getattr(args, 'warmup_start_rate', None) is not None:
        values['PERF_WARMUP_START_RATE'] = args.warmup_start_rate
    env.update({k: str(v) for k, v in values.items()})
    return env


def gomaxprocs_configuration(env):
    """Record the launch input only; never inspect or print the complete environment."""
    raw = env.get('GOMAXPROCS')
    valid = isinstance(raw, str) and re.fullmatch(r'[1-9][0-9]{0,8}', raw) is not None
    return {'status': 'CONFIGURED' if valid else 'NOT_CONFIGURED' if raw is None else 'PRESENT_UNPARSED',
            'configuredValue': int(raw) if valid else None,
            'runtimeActual': None, 'runtimeActualStatus': 'NOT_AVAILABLE',
            'source': 'child process launch environment; unchanged',
            'meaning': 'Configuration is not the measured Go runtime GOMAXPROCS or a CPU quota'}


def recorded_generator_diagnostics(summary):
    result = {}
    for key in ('iteration_duration_ms', 'scenario_time_anchor'):
        value = summary.get(key) if isinstance(summary, dict) else None
        result[key] = value if isinstance(value, dict) and value.get('status') in ('AVAILABLE', 'NOT_AVAILABLE') else {
            'status': 'NOT_AVAILABLE', 'reason': 'SUMMARY_FIELD_MISSING_OR_INVALID'}
    timing = summary.get('http_timing_breakdown') if isinstance(summary, dict) else None
    result['http_timing_breakdown'] = timing if (
        isinstance(timing, dict) and type(timing.get('enabled')) is bool
        and timing.get('source') == 'K6_NATIVE_HTTP_TIMINGS' and timing.get('unit') == 'ms'
        and all(isinstance(timing.get(phase), dict)
                and (timing[phase].get('status') in ('AVAILABLE', 'NOT_AVAILABLE')
                     if timing['enabled'] else timing[phase] == {
                         'status': 'NOT_ENABLED', 'requests': None, 'components_ms': None})
                for phase in ('all', 'warmup', 'measure'))) else {
                    'status': 'NOT_AVAILABLE', 'reason': 'SUMMARY_FIELD_MISSING_OR_INVALID'}
    return result


def start_k6(command, env, log, result):
    """Capture parent-side spawn bounds separately from the native scenario anchor."""
    result['generatorConfiguration'] = {'gomaxprocs': gomaxprocs_configuration(env)}
    result['k6Launch'] = {'beforePopenUtc': dt.datetime.now(dt.timezone.utc).isoformat(),
                          'beforePopenMonotonicSeconds': time.monotonic(),
                          'scope': 'PARENT_PROCESS_SPAWN_BOUNDS_NOT_SCENARIO_START'}
    process = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT,
                               start_new_session=True,
                               preexec_fn=lambda parent_pid=os.getpid(): child_parent_death_signal(parent_pid))
    result['k6Launch'].update(afterPopenUtc=dt.datetime.now(dt.timezone.utc).isoformat(),
                             afterPopenMonotonicSeconds=time.monotonic())
    return process


def source_ip_selection(args):
    count = getattr(args, 'source_ip_count', 1)
    require(type(count) is int and count in (1, 16, 64), 'INVALID_SOURCE_IP_COUNT')
    require(count == 1 or (args.mode == 'redirect' and args.path == 'edge'),
            'MULTI_SOURCE_IP_REQUIRES_EDGE_REDIRECT')
    return {'sourceIpCount': count,
            'sourceIpRange': {16: '127.0.0.2-127.0.0.17', 64: '127.0.0.2-127.0.0.65'}.get(count),
            # The default preserves the existing socket routing. In particular,
            # direct/admin cross the bridge; this range is not their measured peer.
            'sourceIpBinding': 'K6_LOCAL_IPS' if count != 1 else 'OS_ROUTE_DEFAULT',
            'sourceIpRangeScope': 'EDGE_LOOPBACK_SELECTION' if args.path == 'edge' else 'BRIDGE_ROUTE_DEFAULT'}


def build_k6_command(args, state):
    selection = source_ip_selection(args)
    command = ['taskset', '-c', '12-15', 'nsenter', '--target', str(state['apisixPid']), '--net',
               str(K6), 'run', '--quiet', '--no-usage-report']
    if selection['sourceIpCount'] != 1:
        command.extend(['--local-ips', selection['sourceIpRange']])
    command.append(str(WORKLOAD))
    return command


def stage_config(args, timing):
    return {'mode': args.mode, 'path': args.path, 'rate': args.rate,
            'executor': 'constant-arrival-rate' if getattr(args, 'warmup_start_rate', None) is None else 'ramping-arrival-rate',
            'warmupStartRate': getattr(args, 'warmup_start_rate', None),
            'metricSeedMode': getattr(args, 'metric_seed_mode', 'per-request'),
            'httpPhaseTimings': getattr(args, 'http_phase_timings', False),
            'redirectInputMode': getattr(args, 'redirect_input_mode', 'shared-array'),
            'skipRedirectZeroRows': getattr(args, 'skip_redirect_zero_rows', False),
            'clientTimeline': getattr(args, 'client_timeline', False),
            'timeUnit': args.time_unit, 'warmupMs': timing['warmupMs'],
            'measureMs': timing['measureMs'], 'vus': args.vus,
            'accounts': args.accounts, 'workset': args.workset,
            'distribution': args.distribution, **source_ip_selection(args)}


def assess(args, timing, summary, before, after, drained_after, returncode, stopped):
    measure = (summary or {}).get('measure', {})
    elapsed = measure.get('phase_duration_ms', measure.get('client_elapsed_ms', 0)) or 0
    p99 = measure.get('roundtrip_ms', {}).get('p99')
    delta = counter_delta(before, after)
    checks = {
        'measurementComplete': returncode == 0 and
            (summary or {}).get('test_run_duration_ms', 0) >= timing['warmupMs'] + timing['measureMs'] - 1000,
        'sentAtLeast99PercentOfTarget': measure.get('actual_sent', 0) >= timing['targetMeasureRequests'] * .99,
        'allSentCompleted': measure.get('actual_sent', 0) == measure.get('completed', -1),
        'correctRateAtLeast999': (measure.get('correct_rate') or 0) >= .999,
        'noDroppedIterations': summary is not None and summary.get('dropped_iterations') == 0,
        'fixtureValid': summary is not None and summary.get('fixture_valid') is True,
        'roundtripP99WithinBudget': isinstance(p99, (int, float)) and math.isfinite(p99) and p99 <= args.p99_budget_ms,
        'eventFailuresUnchanged': delta is not None and all(v == 0 for v in delta.values()),
        'afterDrained': drained_after, 'k6ExitedSuccessfully': returncode == 0,
        'noMonitorStop': stopped is None,
    }
    passed = all(checks.values())
    if args.allow_rejections:
        status = 'PROTECTION_DIAGNOSTIC_COMPLETED' if returncode == 0 and stopped is None else 'PROTECTION_DIAGNOSTIC_STOPPED'
    else:
        status = 'CAPACITY_STAGE_PASSED' if passed else 'CAPACITY_STAGE_FAILED'
    return {'status': status, 'capacityPassed': False if args.allow_rejections else passed,
            'checks': checks, 'eventFailureDeltas': delta,
            'targetMeasureRequests': timing['targetMeasureRequests'], 'p99BudgetMs': args.p99_budget_ms,
            'roundtripP99Ms': p99, 'httpDurationP99Ms': measure.get('latency_ms', {}).get('p99')}


def run(args):
    timing = validate_args(args)
    path, state, folder = load_state(args.state)
    validate_metadata_intent_mode(state, args.mode)
    runtime_budget = state['budgets'].get('maxRuntimeSeconds')
    require(isinstance(runtime_budget, (int, float)) and (timing['warmupMs'] + timing['measureMs']) / 1000 <= runtime_budget,
            'STAGE_EXCEEDS_SUPERVISOR_RUNTIME_BUDGET')
    stages = folder / 'stages'
    stages.mkdir(mode=0o700, exist_ok=True)
    require(not stages.is_symlink(), 'INVALID_STAGES_DIRECTORY')
    stage = stages / args.label
    stage.mkdir(mode=0o700, exist_ok=False)
    observe = observer_module()
    process = None
    interrupted = False
    handlers = {}
    client = ClientProcessStats()
    log_path = stage / 'k6.log'
    before = after = summary = None
    after_drained = False
    stopped = None
    result = {'schemaVersion': 1, 'runId': state['runId'], 'label': args.label,
              'startedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'status': 'PREPARING',
              'capacityPassed': False, 'stopReasons': [], 'drained': False, 'k6ReturnCode': None,
              'summaryPath': str(stage / 'k6-summary.json'), 'measure': {},
              'mode': args.mode, 'path': args.path, 'allowRejections': args.allow_rejections,
              'failFastUnexpected': getattr(args, 'fail_fast_unexpected', False),
              'metadataIntentObservationRequired': state.get('metadataIntentObservationRequired', False),
              'pipelineFailureObservationRequired': state.get('pipelineFailureObservationRequired', False),
              'samplingOwner': 'run_stage_only', 'sampleIntervalSeconds': INTERVAL,
              'diskBudgetScope': 'evidence filesystem only; Kafka volume monitoring is separate'}
    result.update(source_ip_selection(args))
    result['stageConfig'] = stage_config(args, timing)

    def signal_stop(signum, frame):
        nonlocal interrupted
        interrupted = True

    def sample(phase):
        value = observe.sample(path)
        value['stagePhase'] = phase
        value['stageLabel'] = args.label
        value['k6Process'] = client.summary()
        with (stage / 'metrics.jsonl').open('a', encoding='utf-8') as output:
            output.write(json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(',', ':')) + '\n')
        return value

    def wait_drain(phase, baseline=None):
        guard = Monitor(state, baseline, draining=True)
        deadline, latest = time.monotonic() + DRAIN_TIMEOUT, None
        while time.monotonic() < deadline:
            if interrupted:
                return latest, False, 'INTERRUPTED'
            begin = time.monotonic()
            latest = sample(phase)
            problem = guard.inspect(latest, time.monotonic())
            if observe.drained(latest)['drained']:
                return latest, problem is None, problem
            if problem:
                return latest, False, problem
            time.sleep(min(max(0, INTERVAL - (time.monotonic() - begin)), max(0, deadline - time.monotonic())))
        return latest, False, 'DRAIN_TIMEOUT'

    try:
        if threading.current_thread() is threading.main_thread():
            for number in (signal.SIGINT, signal.SIGTERM):
                handlers[number] = signal.signal(number, signal_stop)
        before, ready, stopped = wait_drain('before')
        write_json(stage / 'before.json', before)
        if not ready:
            result.update(status='BLOCKED_BEFORE_LOAD', stopReason=stopped)
            return result
        added = (1 if args.mode == 'create' else args.batch_size if args.mode == 'batch' else 0)
        projected = math.ceil(args.rate * (timing['warmupMs'] + timing['measureMs']) / timing['unitMs']) * added
        if before['mysql']['data']['routeCount'] + projected > state['maximumCreatedRows']:
            stopped = 'PROJECTED_ROW_BUDGET_EXCEEDED'
            result.update(status='BLOCKED_BEFORE_LOAD', stopReason=stopped)
            return result
        require(read_json(path).get('phase') == 'READY', 'STATE_STOPPED_BEFORE_LOAD')
        guard = Monitor(state, before)
        require(guard.inspect(before, time.monotonic()) is None, 'PRELOAD_RESOURCE_CHECK_FAILED')
        require(before['apisix'].get('containerPid') == state['apisixPid'], 'APISIX_PID_NOT_PROVEN')
        command = build_k6_command(args, state)
        with log_path.open('wb') as log:
            process = start_k6(command, build_env(args, state, stage), log, result)
            if getattr(args, 'client_timeline', False):
                client.start(process.pid, stage / 'k6-process.jsonl')
            begin = time.monotonic()
            deadline = begin + (timing['warmupMs'] + timing['measureMs']) / 1000 + 45
            next_sample = begin
            while process.poll() is None:
                if not getattr(args, 'client_timeline', False):
                    client.capture(process.pid)
                now = time.monotonic()
                if interrupted:
                    stopped = 'INTERRUPTED'
                elif client.fault:
                    stopped = client.fault
                elif os.fstat(log.fileno()).st_size > LOG_BYTES:
                    stopped = 'K6_LOG_BUDGET_EXCEEDED'
                elif now >= deadline:
                    stopped = 'STAGE_WALLCLOCK_BUDGET_EXCEEDED'
                elif now >= next_sample:
                    latest = sample('running')
                    stopped = guard.inspect(latest, time.monotonic())
                    if read_json(path).get('phase') != 'READY':
                        stopped = 'SUPERVISOR_NOT_READY'
                    next_sample = max(next_sample + INTERVAL, time.monotonic())
                if stopped:
                    stop_owned(process)
                    break
                time.sleep(min(1, max(.01, next_sample - time.monotonic())))
            process.wait(timeout=5)
            client.stop()
            if client.fault and stopped is None:
                stopped = client.fault
        after, after_drained, drain_reason = wait_drain('after', before)
        write_json(stage / 'after.json', after)
        after_drained = bool(after is not None and observe.drained(after)['drained'])
        if stopped is None and (drain_reason or not after_drained):
            stopped = drain_reason or 'AFTER_NOT_DRAINED'
        summary_path = stage / 'k6-summary.json'
        if summary_path.is_file():
            summary = read_json(summary_path, 8 * 1024 * 1024)
        result.update(assess(args, timing, summary, before, after, after_drained, process.returncode, stopped))
        result.update(k6ReturnCode=process.returncode, stopReason=stopped, afterDrainReason=drain_reason,
                      summaryAvailable=summary is not None, drained=after_drained,
                      measure=(summary or {}).get('measure', {}))
    except Exception as error:
        # Preserve reason codes, never subprocess/HTTP exception strings or environment values.
        result.update(status='STAGE_EXECUTION_ERROR', errorType=type(error).__name__, stopReason=stopped)
    finally:
        stop_owned(process)
        client.stop()
        if log_path.is_file():
            result['k6LogObservedBytes'] = log_path.stat().st_size
            result['k6LogTruncated'] = result['k6LogObservedBytes'] > LOG_BYTES
            if result['k6LogTruncated']:
                with log_path.open('r+b') as log:
                    log.truncate(LOG_BYTES)
        result['k6LogRetentionLimitBytes'] = LOG_BYTES
        for number, handler in handlers.items():
            signal.signal(number, handler)
        result['finishedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
        reasons = [result.get('stopReason')]
        if result.get('errorType'):
            reasons.append('EXECUTION_' + result['errorType'])
        result['failedChecks'] = [key for key, value in result.get('checks', {}).items() if not value]
        if not args.allow_rejections:
            reasons.extend('CHECK_' + key for key in result['failedChecks'])
        result['stopReasons'] = list(dict.fromkeys(reason for reason in reasons if reason))
        result['k6Process'] = client.summary()
        result['generatorDiagnostics'] = recorded_generator_diagnostics(summary)
        result['stageDirectory'] = str(stage)
        write_json(stage / 'result.json', result)
    return result


def main():
    args = parser().parse_args()
    try:
        result = run(args)
    except Exception as error:
        print(json.dumps({'status': 'REFUSED_BEFORE_STAGE', 'errorType': type(error).__name__}))
        return 2
    print(json.dumps(result, ensure_ascii=False, allow_nan=False))
    return 0 if result.get('capacityPassed') is True or result.get('status') == 'PROTECTION_DIAGNOSTIC_COMPLETED' else 1


if __name__ == '__main__':
    raise SystemExit(main())
