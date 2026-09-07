"""Bounded, sequential HTTP proof of one Current/Next ID segment transition.

Preparation/correctness evidence, never a throughput measurement. Only Admin's
real synchronous batch API writes business data. SQL and observations are read
only. No ID resets, direct generator calls, request retries, or target fetches.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import http.client
import json
import os
from pathlib import Path
import re
import signal
import stat
import time
from urllib.parse import urlsplit
import uuid

import evidence
import observe


METRICS = {
    'remaining': 'shortlink_id_current_remaining',
    'nextReady': 'shortlink_id_next_ready',
    'issued': 'shortlink_id_issued',
    'requestedStep': 'shortlink_id_requested_step',
    'failures': 'shortlink_id_refill_failures',
    'rejections': 'shortlink_id_executor_rejections',
    'discarded': 'shortlink_id_discarded',
}
MAX_BODY = 2 * 1024 * 1024
ID_LIMIT = 2 ** 52


class BoundaryError(RuntimeError):
    """Only static, credential-free reason codes reach the report."""


def require(condition, reason):
    if not condition:
        raise BoundaryError(reason)


def number(value):
    require(type(value) in (int, float) and value >= 0 and value == int(value),
            'INVALID_OR_MISSING_INTEGER_METRIC')
    return int(value)


def alignment(remaining, preferred=250):
    """Return legal 2..500 preparation batches, leaving 1..499 before a 500 batch."""
    require(type(remaining) is int and remaining >= 0, 'INVALID_REMAINING')
    require(type(preferred) is int and 1 <= preferred <= 499, 'INVALID_PREFERRED_REMAINING')
    if remaining == 0:
        return None  # One real calibration batch is needed to initialize/promote Current.
    target = min(remaining, preferred)
    amount = remaining - target
    if amount == 1:
        target -= 1
        amount += 1
    batches = []
    while amount:
        take = min(500, amount)
        if amount - take == 1:
            take -= 1
        require(2 <= take <= 500, 'ALIGNMENT_REQUIRES_ILLEGAL_SINGLE_ROW')
        batches.append(take)
        amount -= take
    require(1 <= target <= 499, 'INVALID_ALIGNED_REMAINING')
    return batches


def compact(snapshot, tenant):
    service = snapshot.get('services', {}).get('shortlink-command', {})
    require(service.get('status') == 'AVAILABLE', 'COMMAND_METRICS_UNAVAILABLE')
    gauges = {}
    for key, metric in METRICS.items():
        values = [m['value'] for m in service.get('metrics', []) if m.get('name') == metric]
        require(len(values) == 1, 'ID_METRICS_MISSING_OR_AMBIGUOUS')
        gauges[key] = number(values[0])
    require(gauges['nextReady'] in (0, 1), 'INVALID_NEXT_READY')
    mysql = snapshot.get('mysql', {})
    require(mysql.get('status') == 'AVAILABLE', 'MYSQL_OBSERVATION_UNAVAILABLE')
    data = mysql['data']
    allocator = data.get('idAllocator', {})
    require(allocator.get('namespace') == 'shortlink_global', 'WRONG_ID_NAMESPACE')
    end = number(allocator.get('endExclusive'))
    require(1 <= end <= ID_LIMIT, 'INVALID_DATABASE_HIGH_WATER')
    digest = data.get('idStatementDigest', {})
    require(digest.get('status') == 'AVAILABLE', 'ID_STATEMENT_DIGEST_UNAVAILABLE')
    quotas = [q for q in data.get('quota', []) if str(q.get('tenantId')) == str(tenant)]
    require(len(quotas) == 1, 'TENANT_QUOTA_MISSING_OR_AMBIGUOUS')
    require(all(number(q.get('activeJobs')) == number(q.get('validationJobs')) == 0
                and number(q.get('reservedRows')) == 0 for q in data['quota']),
            'OTHER_ASYNC_OR_RESERVED_JOB_PRESENT')
    process = snapshot.get('processes', {}).get('shortlink-command', {})
    require(process.get('status') == 'AVAILABLE', 'COMMAND_PROCESS_UNAVAILABLE')
    process = process['data']
    return {'at': snapshot.get('observedAt'), 'gauges': gauges,
            'allocator': {'endExclusive': end, 'configuredStep': number(allocator.get('configuredStep'))},
            'idUpdateStatements': number(digest.get('countStar')),
            'routeCount': number(data.get('routeCount')), 'linkCount': number(data.get('linkCount')),
            'quota': {key: number(quotas[0].get(key)) for key in
                      ('usedRows', 'reservedRows', 'activeJobs', 'validationJobs', 'validationBytes')},
            'outbox': data['outbox'], 'metadata': data['metadata'],
            'command': {'pid': process.get('pid'), 'startTicks': process.get('startTicks')}}


def deltas(before, after):
    return {'issued': after['gauges']['issued'] - before['gauges']['issued'],
            'routeRows': after['routeCount'] - before['routeCount'],
            'linkRows': after['linkCount'] - before['linkCount'],
            'quotaUsedRows': after['quota']['usedRows'] - before['quota']['usedRows'],
            'databaseClaimedIds': after['allocator']['endExclusive'] - before['allocator']['endExclusive'],
            'idUpdateStatements': after['idUpdateStatements'] - before['idUpdateStatements'],
            **{key: after['gauges'][key] - before['gauges'][key]
               for key in ('failures', 'rejections', 'discarded')}}


def consistent(before, after, rows):
    delta = deltas(before, after)
    require(before['command'] == after['command'], 'COMMAND_INSTANCE_CHANGED')
    require(all(delta[key] == rows for key in ('issued', 'routeRows', 'linkRows', 'quotaUsedRows')),
            'ISSUED_DATABASE_QUOTA_DELTA_MISMATCH_OR_CONCURRENT_WRITER')
    require(all(delta[key] == 0 for key in ('failures', 'rejections', 'discarded')),
            'ID_FAILURE_REJECTION_OR_DISCARD_INCREMENT')
    require(delta['databaseClaimedIds'] >= 0 and delta['idUpdateStatements'] >= 0,
            'ALLOCATOR_OR_DIGEST_REGRESSION')
    require(after['quota']['reservedRows'] == before['quota']['reservedRows'] == 0
            and after['quota']['activeJobs'] == after['quota']['validationJobs'] == 0,
            'ASYNC_OR_RESERVED_WORK_PRESENT')
    require(after['outbox']['terminalFailed'] == before['outbox']['terminalFailed'],
            'OUTBOX_TERMINAL_FAILURE_INCREMENT')
    return delta


def range_summary(ids):
    ranges = []
    for value in sorted(ids):
        if ranges and ranges[-1]['endExclusive'] == value:
            ranges[-1]['endExclusive'] += 1
        else:
            ranges.append({'startInclusive': value, 'endExclusive': value + 1})
    return ranges


class Verifier:
    def __init__(self, args):
        require(500 <= args.max_rows <= 10000 and 60 <= args.max_seconds <= 1200
                and 10 <= args.http_timeout <= 60 and 0 <= args.account_index <= 99
                and args.exclusive_writer is True and re.fullmatch('[A-Za-z0-9_-]{1,40}', args.label),
                'INVALID_EXECUTION_ARGUMENTS')
        require(os.environ.get('WSL_DISTRO_NAME') == 'shortlink-refactor-it', 'DEDICATED_WSL_REQUIRED')
        self.args = args
        self.path = Path(args.state).resolve(strict=True)
        self.state, self.folder = observe._load(self.path)
        state = self.state
        require(state.get('phase') == 'READY' and state.get('servicesStopped') is not True,
                'STATE_NOT_READY')
        require(state.get('agentsStarted') is False and state.get('analyticsStarted') is False,
                'UNEXPECTED_TOPOLOGY')
        require(state.get('metadataMode') == 'local-host-policy-rejection', 'METADATA_PROFILE_MISMATCH')
        require(state.get('managementHost') == 'admin.perf.test' and state.get('redirectHost') == 's.perf.test',
                'UNEXPECTED_PERFORMANCE_HOSTS')
        private = Path('/var/lib/shortlink-perf') / state['runId']
        fixture_path = Path(state['fixturePath'])
        require(fixture_path == private / 'fixture.json' and not private.is_symlink()
                and not fixture_path.is_symlink() and stat.S_IMODE(private.stat().st_mode) == 0o700
                and stat.S_IMODE(fixture_path.stat().st_mode) == 0o600
                and fixture_path.stat().st_size <= 256 * 1024 * 1024, 'PRIVATE_FIXTURE_INVALID')
        fixture = json.loads(fixture_path.read_text(encoding='utf-8'))
        require(fixture.get('fixtureStatus') == 'READY' and fixture.get('runId') == state['runId']
                and fixture.get('database') == state['database'], 'FIXTURE_NOT_READY_OR_WRONG_RUN')
        require(0 <= args.account_index < len(fixture['accounts']), 'ACCOUNT_INDEX_OUT_OF_RANGE')
        self.account = fixture['accounts'][args.account_index]
        self.token = fixture['internalToken']
        require(isinstance(self.token, str) and len(self.token) >= 32, 'INTERNAL_CREDENTIAL_INVALID')
        require(str(self.account['tenantId']).isdigit() and str(self.account['authVersion']).isdigit(),
                'INVALID_FIXTURE_IDENTITY')
        self.command_pid = int(state['jvmArtifacts']['shortlink-command']['pid'])
        self.output = self.folder / 'id-boundary-verification.json'
        # Exclusive file creation fences duplicate verifier invocations and accidental reruns.
        fd = os.open(self.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        os.close(fd)
        self.started = time.monotonic()
        self.deadline = self.started + args.max_seconds
        self.nonce = args.label + '-' + uuid.uuid4().hex[:16]
        self.ids = set()
        self.attempted = 0
        self.report = {'schemaVersion': 1, 'runId': state['runId'], 'label': args.label,
                       'nonce': self.nonce, 'status': 'PREPARING', 'passed': False,
                       'observedCrossSegment': False, 'startedAt': datetime.now(timezone.utc).isoformat(),
                       'limits': {'maximumAdditionalRows': args.max_rows, 'maximumSeconds': args.max_seconds,
                                  'drainSecondsPerBatch': 300, 'httpTimeoutSeconds': args.http_timeout},
                       'accountIndex': args.account_index, 'batches': [],
                       'meaning': 'Sequential correctness preparation; not a throughput measurement',
                       'boundaryEvidence': 'Current end derived from returned raw IDs plus a quiescent remaining gauge; '
                           'never inferred by subtracting configuredStep from the DB reservation high water',
                       'concurrencyAssumption': 'One Command process, no known writer process, stable initial counts, '
                           'and exact per-batch global issued/route/link/tenant-quota deltas. '
                           'Operator must ensure no unrecognized external HTTP writer.'}
        self.save()

    def save(self):
        self.report.update(elapsedSeconds=round(time.monotonic() - self.started, 3),
                           attemptedRows=self.attempted, committedVerifiedRows=len(self.ids))
        temporary = self.output.with_suffix('.tmp')
        with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'w', encoding='utf-8') as f:
            json.dump(self.report, f, ensure_ascii=False, indent=2, allow_nan=False)
            f.write('\n')
        os.replace(temporary, self.output)

    def guard(self):
        require(time.monotonic() < self.deadline, 'OVERALL_TIME_BUDGET_EXHAUSTED')
        state = json.loads(self.path.read_text(encoding='utf-8-sig'))
        require(state.get('phase') == 'READY' and state.get('jvmArtifacts') == self.state.get('jvmArtifacts'),
                'SUPERVISOR_STATE_CHANGED')
        command_pids = []
        for entry in Path('/proc').iterdir():
            if not entry.name.isdigit() or int(entry.name) == os.getpid():
                continue
            try:
                cmd = (entry / 'cmdline').read_bytes().split(b'\0')
            except (OSError, ProcessLookupError):
                continue
            words = [x.decode('utf-8', errors='replace') for x in cmd if x]
            if not words:
                continue
            executable = Path(words[0]).name
            if executable == 'java' and any('shortlink-command' in x and x.endswith('.jar') for x in words):
                command_pids.append(int(entry.name))
            writer = any(Path(x).name in {'run_stage.py', 'run_wave.py', 'prepare_fixtures.py',
                                          'verify_id_boundary.py', 'workload.js'} for x in words)
            require(not writer and not (executable.startswith('k6') and 'run' in words),
                    'ANOTHER_KNOWN_LOAD_OR_PREPARATION_PROCESS_IS_RUNNING')
        require(command_pids == [self.command_pid], 'EXACTLY_ONE_EXPECTED_COMMAND_REQUIRED')

    def sample(self):
        self.guard()
        snap = observe.sample(self.path)
        data = compact(snap, self.account['tenantId'])
        disk = snap.get('host', {}).get('data', {}).get('disk', {})
        free, total = number(disk.get('freeBytes')), number(disk.get('totalBytes'))
        require(free >= max(10 * 1024 ** 3, self.state['budgets']['minimumFreeDiskBytes'], total * .15),
                'DISK_BUDGET_EXCEEDED')
        require(data['routeCount'] <= self.state['maximumCreatedRows'], 'RUN_ROW_BUDGET_EXCEEDED')
        return snap, data

    def drain(self):
        started = time.monotonic()
        deadline = min(self.deadline, started + 300)
        while True:
            snap, data = self.sample()
            drained = observe.drained(snap)
            if drained['drained']:
                return data, round(time.monotonic() - started, 3)
            require(time.monotonic() < deadline, 'QUEUES_DID_NOT_DRAIN_WITHIN_BUDGET')
            time.sleep(min(2, max(0, deadline - time.monotonic())))

    def post(self, payload):
        self.last_http_status = None
        headers = {'Host': self.state['managementHost'], 'X-Internal-Token': self.token,
                   'x-shortlink-tenant-id': str(self.account['tenantId']),
                   'x-shortlink-username': self.account['username'],
                   'x-shortlink-auth-version': str(self.account['authVersion']),
                   'Accept': 'application/json', 'Content-Type': 'application/json',
                   'User-Agent': 'Shortlink-ID-Boundary-Verification/1.0', 'Connection': 'close'}
        body = json.dumps(payload, separators=(',', ':')).encode('utf-8')
        # Same trusted loopback Admin entry as fixture preparation; never follow any redirect.
        conn = http.client.HTTPConnection('127.0.0.1', 8002, timeout=self.args.http_timeout)
        try:
            conn.request('POST', '/api/short-link/admin/v1/create/batch', body, headers)
            response = conn.getresponse()
            self.last_http_status = response.status
            raw = response.read(MAX_BODY + 1)
            require(response.status == 200, 'HTTP_NON_200_UNKNOWN_COMMIT_NO_RETRY')
            require(len(raw) <= MAX_BODY, 'HTTP_RESPONSE_OVER_BUDGET_UNKNOWN_COMMIT_NO_RETRY')
            try:
                result = json.loads(raw)
            except ValueError:
                raise BoundaryError('HTTP_INVALID_JSON_UNKNOWN_COMMIT_NO_RETRY') from None
            require(isinstance(result, dict) and result.get('code') == '0'
                    and result.get('success', True) is True, 'HTTP_BUSINESS_ERROR_NO_RETRY')
            return result.get('data')
        except (OSError, http.client.HTTPException):
            raise BoundaryError('HTTP_TRANSPORT_UNKNOWN_COMMIT_NO_RETRY') from None
        finally:
            conn.close()

    def verify_rows(self, rows, origins):
        require(isinstance(rows, list) and len(rows) == len(origins), 'HTTP_RESULT_ROW_COUNT_MISMATCH')
        records = {}
        for row, origin in zip(rows, origins):
            identity = row.get('linkId')
            require(type(identity) is int and 0 < identity < ID_LIMIT
                    and identity not in self.ids and identity not in records, 'DUPLICATE_OR_INVALID_RESPONSE_ID')
            address = urlsplit(row.get('fullShortUrl', ''))
            require(address.scheme == 'https' and address.netloc == self.state['redirectHost']
                    and re.fullmatch('/[A-Za-z0-9]{9}', address.path) and not address.query and not address.fragment
                    and row.get('originUrl') == origin, 'RESPONSE_SHORT_CODE_OR_TARGET_MISMATCH')
            records[identity] = (address.path[1:], origin)
        ids_sql = ','.join(str(x) for x in records)
        require(self.deadline - time.monotonic() >= 31, 'INSUFFICIENT_TIME_FOR_READ_ONLY_DB_VERIFICATION')
        rows = evidence.sql(self.state, 'SELECT link_id,tenant_id,current_gid,domain_norm,short_uri,origin_url,'
                            'route_version,route_status FROM t_link_route WHERE link_id IN (' + ids_sql + ')')
        require(len(rows) == len(records), 'DATABASE_ROUTE_ROWS_MISSING')
        for row in rows:
            identity = int(row[0])
            require(identity in records and row[1:4] == [str(self.account['tenantId']), self.account['gid'],
                    self.state['redirectHost']] and tuple(row[4:6]) == records[identity]
                    and row[6:] == ['1', 'ACTIVE'], 'DATABASE_ROUTE_RESPONSE_MISMATCH')
        # Each route must have exactly one matching gid-sharded business row.
        union = ' UNION ALL '.join('SELECT id,tenant_id,gid,domain,short_uri,origin_url FROM t_link_' + str(i)
                                  + ' WHERE id IN (' + ids_sql + ')' for i in range(16))
        require(self.deadline - time.monotonic() >= 31, 'INSUFFICIENT_TIME_FOR_READ_ONLY_DB_VERIFICATION')
        missing = evidence.sql(self.state, 'SELECT COUNT(*),COUNT(DISTINCT l.id) FROM (' + union + ') l JOIN t_link_route r'
            ' ON r.link_id=l.id AND r.tenant_id=l.tenant_id AND r.current_gid=l.gid AND r.domain_norm=l.domain'
            ' AND r.short_uri=l.short_uri AND r.origin_url=l.origin_url')
        require(missing == [[str(len(records)), str(len(records))]], 'DATABASE_SHARDED_ROWS_MISMATCH')
        return sorted(records)

    def batch(self, count, purpose, before):
        require(2 <= count <= 500, 'ILLEGAL_BATCH_SIZE')
        require(self.attempted + count <= self.args.max_rows, 'ADDITIONAL_ROW_BUDGET_EXHAUSTED')
        require(before['routeCount'] + count <= self.state['maximumCreatedRows'], 'RUN_ROW_BUDGET_EXCEEDED')
        require(self.deadline - time.monotonic() >= self.args.http_timeout + 35,
                'INSUFFICIENT_TIME_FOR_ANOTHER_BATCH')
        self.guard()
        index = len(self.report['batches'])
        request_id = 'id-boundary-' + self.nonce + '-b' + str(index)
        require(len(request_id) <= 96, 'REQUEST_ID_TOO_LONG')
        origins = ['https://shortlink-perf.local/target/' + self.nonce + '/' + str(index) + '/' + str(i)
                   for i in range(count)]
        payload = {'requestId': request_id, 'domain': self.state['redirectHost'], 'gid': self.account['gid'],
                   'createdType': 0, 'validDateType': 0, 'validDate': None, 'originUrls': origins,
                   'describes': ['ID boundary verification; metadata policy rejection'] * count}
        item = {'index': index, 'purpose': purpose, 'requestId': request_id, 'rows': count,
                'before': before, 'state': 'SENT_OUTCOME_NOT_YET_KNOWN',
                'cumulativeAttemptedRows': self.attempted + count,
                'cumulativeVerifiedRows': len(self.ids)}
        self.report['batches'].append(item)
        self.attempted += count
        self.save()  # A crash after send retains the exact idempotency identity for diagnosis.
        started = time.monotonic()
        error = None
        try:
            data = self.post(payload)
            require(isinstance(data, dict) and data.get('state') == 'SUCCEEDED' and data.get('jobId') is None
                    and data.get('total') == count, 'SYNC_BATCH_NOT_ALL_SUCCEEDED_NO_RETRY')
            item['state'] = 'HTTP_CONFIRMED_PENDING_DATABASE_VERIFICATION'
        except BoundaryError as exc:
            error = exc
        finally:
            item['httpElapsedMs'] = round((time.monotonic() - started) * 1000, 3)
            item['httpStatus'] = self.last_http_status
            if error:
                item.update(state='FAILED_UNKNOWN_OR_REJECTED_NO_RETRY', reason=str(error))
            self.save()
        # Even an unknown HTTP outcome is followed by bounded quiescence before stopping.
        after, drain_seconds = self.drain()
        item.update(after=after, drainSeconds=drain_seconds, deltas=deltas(before, after))
        self.report['after'] = after
        self.save()
        if error:
            raise error
        ids = self.verify_rows(data.get('baseLinkInfos'), origins)
        consistent(before, after, count)
        require(after['metadata']['terminalFailed'] - before['metadata']['terminalFailed'] == count,
                'METADATA_REJECTION_PROFILE_ROW_COUNT_MISMATCH')
        self.ids.update(ids)
        item.update(state='COMMITTED_AND_VERIFIED', idRanges=range_summary(ids), uniqueIds=len(ids),
                    cumulativeVerifiedRows=len(self.ids),
                    totalElapsedMs=round((time.monotonic() - started) * 1000, 3))
        self.save()
        return after, ids

    def execute(self):
        before, seconds = self.drain()
        self.report.update(before=before, initialDrainSeconds=seconds)
        time.sleep(1)
        stable, _ = self.drain()
        consistent(before, stable, 0)
        require(stable['gauges']['remaining'] == before['gauges']['remaining'], 'ID_CURSOR_NOT_QUIESCENT')
        current = stable
        self.report['status'] = 'RUNNING'
        self.save()
        remaining = current['gauges']['remaining']
        planned = alignment(remaining)
        if planned is not None and sum(planned) + 500 > self.args.max_rows:
            self.report.update(status='NOT_OBSERVED_BUDGET', reason='CURRENT_SEGMENT_TOO_FAR_FROM_BOUNDARY',
                               minimumRowsForBoundary=sum(planned) + 500)
            return
        # For an already near-end Current, use the 500-row challenge directly;
        # a 2-row calibration could otherwise consume the only reachable boundary.
        known_end = None
        if remaining == 0 or remaining >= 500:
            current, ids = self.batch(2, 'CALIBRATE_CURRENT', current)
            known_end = ids[-1] + 1 + current['gauges']['remaining']
            self.report['calibratedCurrentEndExclusive'] = known_end
        plan = alignment(current['gauges']['remaining'])
        require(plan is not None, 'NO_CURRENT_AFTER_CALIBRATION')
        if self.attempted + sum(plan) + 500 > self.args.max_rows:
            self.report.update(status='NOT_OBSERVED_BUDGET', reason='CURRENT_SEGMENT_TOO_FAR_FROM_BOUNDARY',
                               minimumAdditionalRowsForBoundary=sum(plan) + 500)
            return
        for count in plan:
            previous = current
            current, ids = self.batch(count, 'ALIGN_CURRENT', previous)
            require(current['gauges']['remaining'] == previous['gauges']['remaining'] - count,
                    'UNEXPECTED_SEGMENT_TRANSITION_DURING_ALIGNMENT')
            derived_end = ids[-1] + 1 + current['gauges']['remaining']
            require(known_end is None or known_end == derived_end, 'CURRENT_END_CHANGED_DURING_ALIGNMENT')
            known_end = derived_end
        if current['gauges']['nextReady'] == 0 and current['gauges']['remaining'] >= 3:
            # A small/dynamically reduced Current may not reach its prefetch
            # threshold at 250 remaining. Leave one ID using a legal batch;
            # never assume requestedStep describes the already allocated slot.
            previous = current
            current, ids = self.batch(current['gauges']['remaining'] - 1, 'ALIGN_FOR_PREFETCH', previous)
            require(current['gauges']['remaining'] == 1, 'UNEXPECTED_TRANSITION_WHILE_ENABLING_PREFETCH')
            derived_end = ids[-1] + 2
            require(known_end is None or known_end == derived_end, 'CURRENT_END_CHANGED_DURING_ALIGNMENT')
            known_end = derived_end
        # Prefetch must be observed ready before the challenge, not merely inferred after it.
        deadline = min(self.deadline, time.monotonic() + 30)
        while current['gauges']['nextReady'] != 1:
            require(time.monotonic() < deadline, 'PREFETCH_NEXT_NOT_READY_BEFORE_CHALLENGE')
            time.sleep(1)
            value, _ = self.drain()
            consistent(current, value, 0)
            require(current['gauges']['remaining'] == value['gauges']['remaining'], 'ID_CURSOR_NOT_QUIESCENT')
            current = value
        old = current
        remaining = old['gauges']['remaining']
        require(1 <= remaining <= 499, 'CURRENT_NOT_ALIGNED')
        current, ids = self.batch(500, 'CROSS_CURRENT_NEXT_500', old)
        boundary = known_end if known_end is not None else ids[0] + remaining
        left, right = [i for i in ids if i < boundary], [i for i in ids if i >= boundary]
        require(len(left) == remaining and len(right) == 500 - remaining
                and left == list(range(boundary - remaining, boundary))
                and right == list(range(right[0], right[0] + len(right)))
                and right[0] >= boundary, 'RESPONSE_DID_NOT_PROVE_EXPECTED_TWO_RANGES')
        require(boundary <= old['allocator']['endExclusive'] and max(ids) < current['allocator']['endExclusive'],
                'RESPONSE_EXCEEDS_COMMITTED_DATABASE_RESERVATIONS')
        consistent(self.report['before'], current, len(self.ids))
        self.report.update(status='PASSED', passed=True, observedCrossSegment=True, after=current,
                           totals=deltas(self.report['before'], current),
                           crossing={'beforeRemaining': remaining, 'prefetchedNextObservedReady': True,
                                     'currentEndExclusive': boundary,
                                     'boundarySource': 'CALIBRATION_IDS_PLUS_REMAINING' if known_end is not None
                                         else 'CHALLENGE_FIRST_ID_PLUS_PREEXISTING_REMAINING',
                                     'oldRangeRows': len(left), 'nextRangeRows': len(right),
                                     'nextRangeStartInclusive': right[0], 'responseIds': ids,
                                     'afterCurrentEndExclusive': ids[-1] + 1 + current['gauges']['remaining']})


def bounded_int(low, high):
    def parse(value):
        try:
            number = int(value)
        except ValueError:
            raise argparse.ArgumentTypeError('integer required') from None
        if not low <= number <= high:
            raise argparse.ArgumentTypeError(f'must be {low}..{high}')
        return number
    return parse


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('state', type=Path)
    p.add_argument('--label', default='id-boundary',
                   type=lambda v: v if re.fullmatch('[A-Za-z0-9_-]{1,40}', v) else
                   (_ for _ in ()).throw(argparse.ArgumentTypeError('safe label of 1..40 characters required')))
    p.add_argument('--account-index', type=bounded_int(0, 99), default=0)
    p.add_argument('--max-rows', type=bounded_int(500, 10000), default=10000)
    p.add_argument('--max-seconds', type=bounded_int(60, 1200), default=1200)
    p.add_argument('--http-timeout', type=bounded_int(10, 60), default=60)
    p.add_argument('--exclusive-writer', action='store_true', required=True,
                   help='operator confirms all other creation traffic has stopped; live guards also verify this')
    return p


def run(args):
    verifier = Verifier(args)
    old_handler = signal.getsignal(signal.SIGALRM)
    def deadline(_signal, _frame):
        raise BoundaryError('OVERALL_TIME_BUDGET_EXHAUSTED_NO_RETRY')
    signal.signal(signal.SIGALRM, deadline)
    signal.alarm(args.max_seconds)
    try:
        verifier.execute()
    except BoundaryError as exc:
        verifier.report.update(status='FAILED', passed=False, reason=str(exc))
        if verifier.report['batches'] and verifier.report['batches'][-1]['state'] != 'COMMITTED_AND_VERIFIED':
            verifier.report['batches'][-1]['verificationFailure'] = str(exc)
    except KeyboardInterrupt:
        verifier.report.update(status='FAILED', passed=False, reason='EXTERNAL_INTERRUPT_POSSIBLE_UNKNOWN_COMMIT')
    except Exception:
        verifier.report.update(status='FAILED', passed=False, reason='UNEXPECTED_LOCAL_OR_OBSERVATION_ERROR')
    finally:
        signal.alarm(0)
        signal.signal(signal.SIGALRM, old_handler)
        verifier.save()
    return verifier.report


if __name__ == '__main__':
    try:
        result = run(parser().parse_args())
        print(json.dumps({key: result.get(key) for key in
            ('status', 'passed', 'reason', 'observedCrossSegment', 'attemptedRows',
             'committedVerifiedRows', 'elapsedSeconds')}, ensure_ascii=False))
        raise SystemExit(0 if result['passed'] else 1)
    except (BoundaryError, OSError, ValueError, KeyError):
        print(json.dumps({'status': 'BLOCKED_BEFORE_LOAD', 'passed': False,
                          'reason': 'INVALID_STATE_FIXTURE_OR_EXISTING_EVIDENCE'}))
        raise SystemExit(2)
