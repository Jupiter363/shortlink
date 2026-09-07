import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

// No fixture contents, credentials, target URLs or per-request diagnostics are printed.
const MODES = ['create', 'idempotent', 'batch', 'update', 'recycle', 'restore',
  'redirect', 'head', 'unknown_fixed', 'unknown_random'];
const MODE = __ENV.PERF_MODE || 'redirect';
if (!MODES.includes(MODE)) throw new Error('Unsupported PERF_MODE');
const REDIRECT = ['redirect', 'head', 'unknown_fixed', 'unknown_random'].includes(MODE);
const UNKNOWN = MODE.startsWith('unknown_');
const METRIC_SEED_MODE = __ENV.PERF_METRIC_SEED_MODE === undefined ? 'per-request' : __ENV.PERF_METRIC_SEED_MODE;
if (!['per-request', 'per-vu-phase'].includes(METRIC_SEED_MODE) ||
    (METRIC_SEED_MODE === 'per-vu-phase' && MODE !== 'redirect'))
  throw new Error('Metric seed mode must be per-request, or per-vu-phase for redirect');
function integer(name, fallback, min, max) {
  const raw = __ENV[name] === undefined ? String(fallback) : __ENV[name];
  if (!/^\d+$/.test(raw)) throw new Error(`${name} must be an integer`);
  const n = Number(raw);
  if (!Number.isSafeInteger(n) || n < min || n > max) throw new Error(`${name} is outside its budget`);
  return n;
}
function boolean(name, fallback) {
  const raw = __ENV[name] === undefined ? String(fallback) : __ENV[name];
  if (raw !== 'true' && raw !== 'false') throw new Error(`${name} must be true or false`);
  return raw === 'true';
}
function milliseconds(raw) {
  const parts = String(raw).match(/\d+(?:\.\d+)?(?:ms|s|m|h)/g);
  if (!parts || parts.join('') !== String(raw)) throw new Error('Invalid duration');
  const factors = { ms: 1, s: 1000, m: 60000, h: 3600000 };
  return parts.reduce((n, part) => {
    const m = /^(\d+(?:\.\d+)?)(ms|s|m|h)$/.exec(part);
    return n + Number(m[1]) * factors[m[2]];
  }, 0);
}
const RATE = integer('PERF_RATE', 1, 1, 1000000);
const VUS = integer('PERF_VUS', 64, 1, 512);
const ACCOUNT_COUNT = integer('PERF_ACCOUNTS', 1, 1, 20);
if (![1, 20].includes(ACCOUNT_COUNT)) throw new Error('PERF_ACCOUNTS must be 1 or 20');
const WORKSET = integer('PERF_WORKSET', 10, 1, 200000);
const BATCH_SIZE = integer('PERF_BATCH_SIZE', 10, 1, 500);
if (![10, 100, 500].includes(BATCH_SIZE)) throw new Error('Unsupported PERF_BATCH_SIZE');
const TIME_UNIT = __ENV.PERF_TIME_UNIT || '1s';
const WARMUP_MS = milliseconds(__ENV.PERF_WARMUP || '30s');
const MEASURE_MS = milliseconds(__ENV.PERF_DURATION || '90s');
const WARMUP_START_RATE = __ENV.PERF_WARMUP_START_RATE === undefined ? null :
  integer('PERF_WARMUP_START_RATE', 1, 1, 1000000);
if (WARMUP_START_RATE !== null &&
    (MODE !== 'redirect' || WARMUP_MS <= 0 || WARMUP_START_RATE >= RATE))
  throw new Error('Warmup ramp requires redirect, positive warmup and start rate below target');
if (milliseconds(TIME_UNIT) < 1 || MEASURE_MS < 1 || WARMUP_MS + MEASURE_MS > 86400000)
  throw new Error('Invalid duration budget');
const DISTRIBUTION = __ENV.PERF_DISTRIBUTION || 'uniform';
if (!['uniform', 'hot80', 'single80'].includes(DISTRIBUTION)) throw new Error('Invalid distribution');
const SAMPLE_PHASE = __ENV.PERF_SAMPLE_PHASE || 'measure';
if (!['warmup', 'measure', 'all'].includes(SAMPLE_PHASE)) throw new Error('Invalid sample phase');
const RUN = __ENV.PERF_RUN_LABEL || '';
if (!/^[A-Za-z0-9_-]{1,40}$/.test(RUN)) throw new Error('PERF_RUN_LABEL must be 1..40 safe characters');
const SEED_INPUT = __ENV.PERF_SEED === undefined ? '42' : __ENV.PERF_SEED;
if (!/^[A-Za-z0-9_-]{1,64}$/.test(SEED_INPUT)) throw new Error('PERF_SEED must be 1..64 safe characters');
const BASE = (__ENV.PERF_BASE_URL || '').replace(/\/$/, '');
if (!/^https?:\/\/[^/?#@]+(?::\d+)?$/.test(BASE)) throw new Error('PERF_BASE_URL must be an HTTP origin');
const PATH_KIND = __ENV.PERF_PATH_KIND || (REDIRECT ? 'redirect' : 'admin');
if (PATH_KIND !== (REDIRECT ? 'redirect' : 'admin')) throw new Error('PERF_PATH_KIND does not match mode');
const OUTPUT = __ENV.PERF_OUTPUT;
if (!OUTPUT || !__ENV.PERF_FIXTURE) throw new Error('PERF_OUTPUT and PERF_FIXTURE are required');
const ABORT_ON_FAILURE = boolean('PERF_ABORT_ON_FAILURE', true);
const ALLOW_REJECTIONS = boolean('PERF_ALLOW_REJECTIONS', false);
const FAIL_FAST_UNEXPECTED = boolean('PERF_FAIL_FAST_UNEXPECTED', false);
if (FAIL_FAST_UNEXPECTED && ALLOW_REJECTIONS)
  throw new Error('PERF_FAIL_FAST_UNEXPECTED conflicts with PERF_ALLOW_REJECTIONS');
const EXPECTED = integer('PERF_EXPECTED_STATUS', REDIRECT ? (UNKNOWN ? 404 : 302) : 200, 100, 599);
if (!REDIRECT && EXPECTED !== 200) throw new Error('Management modes verify successful HTTP 200 only');
const fixturePath = __ENV.PERF_FIXTURE;
const metadata = new SharedArray('perf-private-metadata', () => {
  const f = JSON.parse(open(fixturePath));
  return [{ internalToken: f.internalToken, managementHost: f.managementHost,
    redirectHost: f.redirectHost, normalizedClientIp: f.normalizedClientIp }];
})[0];
const accounts = new SharedArray('perf-private-accounts', () => JSON.parse(open(fixturePath)).accounts);
const links = new SharedArray('perf-link-workset', () => {
  const f = JSON.parse(open(fixturePath));
  return (f.links || []).filter(x => x.accountIndex < ACCOUNT_COUNT).slice(0, WORKSET);
});
const mutations = new SharedArray('perf-mutation-links', () => {
  const rows = (JSON.parse(open(fixturePath)).mutationLinks || [])
    .filter(x => x.accountIndex >= 0 && x.accountIndex < ACCOUNT_COUNT);
  if (new Set(rows.map(x => String(x.linkId))).size !== rows.length)
    throw new Error('Mutation fixture contains duplicate link identities');
  return rows;
});
const HOST = __ENV.PERF_HOST || (REDIRECT ? metadata.redirectHost : metadata.managementHost);
const CLIENT_IP = __ENV.PERF_CLIENT_IP || metadata.normalizedClientIp;
if (!HOST || /[\s/\r\n]/.test(HOST) || !CLIENT_IP || /[\s,\r\n]/.test(CLIENT_IP))
  throw new Error('Fixture Host/client IP is missing or invalid');
if (accounts.length < ACCOUNT_COUNT || !metadata.internalToken || metadata.internalToken.length < 32)
  throw new Error('Fixture does not supply the requested identities');
if (['redirect', 'head'].includes(MODE) && links.length !== WORKSET)
  throw new Error('Fixture has fewer authorized workset links than requested');
if (['update', 'recycle', 'restore'].includes(MODE) && mutations.length < VUS)
  throw new Error('Mutation fixture needs at least one exclusive link per VU');
if (MODE === 'idempotent') {
  for (let i = 0; i < ACCOUNT_COUNT; i++) {
    const known = accounts[i].idempotent;
    if (!known || !known.body || !known.body.requestId || !known.linkId || !known.shortUri)
      throw new Error('Missing committed idempotency fixture');
  }
}

const sent = new Counter('perf_actual_sent');
const completed = new Counter('perf_completed');
const received = new Counter('perf_received_http');
const correct = new Counter('perf_correct');
const correctRows = new Counter('perf_correct_rows');
const correctRate = new Rate('perf_correct_rate');
const status429 = new Counter('perf_status_429');
const status503 = new Counter('perf_status_503');
const clientErrors = new Counter('perf_client_errors');
const fixtureExhausted = new Counter('perf_fixture_exhausted');
const mutationUnconfirmed = new Counter('perf_mutation_unconfirmed');
const writeUnconfirmed = new Counter('perf_write_unconfirmed');
const criticalErrors = new Counter('perf_critical_errors');
const latency = new Trend('perf_http_latency_ms', true);
const roundtrip = new Trend('perf_roundtrip_ms', true);
const clientElapsed = new Trend('perf_client_elapsed_ms', true);
const counterNames = ['perf_actual_sent', 'perf_completed', 'perf_received_http', 'perf_correct',
  'perf_correct_rows', 'perf_status_429', 'perf_status_503', 'perf_client_errors',
  'perf_fixture_exhausted', 'perf_mutation_unconfirmed', 'perf_write_unconfirmed', 'perf_critical_errors'];
const thresholds = {};
for (const phase of ['warmup', 'measure']) {
  for (const name of counterNames) thresholds[`${name}{phase:${phase}}`] = ['count>=0'];
  thresholds[`perf_correct_rate{phase:${phase}}`] = ['rate>=0'];
  thresholds[`perf_http_latency_ms{phase:${phase}}`] = ['max>=0'];
  thresholds[`perf_roundtrip_ms{phase:${phase}}`] = ['max>=0'];
  thresholds[`perf_client_elapsed_ms{phase:${phase}}`] = ['max>=0'];
}
if (ABORT_ON_FAILURE && !ALLOW_REJECTIONS) {
  thresholds['perf_correct_rate{phase:measure}'] = [{ threshold: 'rate>=0.99',
    abortOnFail: true, delayAbortEval: `${Math.ceil(WARMUP_MS + 30000)}ms` }];
}
// Phase submetrics are always exposed. Abort protection is separate from final capacity/SLO gates.
// One scenario preserves VU-local mutation versions across the warmup boundary.
const arrivalScenario = WARMUP_START_RATE === null ?
  { executor: 'constant-arrival-rate', rate: RATE, timeUnit: TIME_UNIT,
    duration: `${Math.ceil(WARMUP_MS + MEASURE_MS)}ms`, preAllocatedVUs: VUS, maxVUs: VUS,
    gracefulStop: '10s' } :
  { executor: 'ramping-arrival-rate', startRate: WARMUP_START_RATE, timeUnit: TIME_UNIT,
    stages: [{ duration: `${WARMUP_MS}ms`, target: RATE },
      { duration: `${MEASURE_MS}ms`, target: RATE }],
    preAllocatedVUs: VUS, maxVUs: VUS, gracefulStop: '10s' };
export const options = {
  scenarios: { [MODE]: arrivalScenario },
  systemTags: ['scenario'], thresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)', 'p(99.9)'],
  maxRedirects: 0,
};
const PREFIX = '/api/short-link/admin/v1';
const ALPHABET = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz';
function hash(text) {
  let value = 2166136261;
  for (let i = 0; i < text.length; i++) value = Math.imul(value ^ text.charCodeAt(i), 16777619);
  return value >>> 0;
}
function mixed(value) {
  value = Math.imul(value ^ (value >>> 16), 0x45d9f3b);
  value = Math.imul(value ^ (value >>> 16), 0x45d9f3b);
  return (value ^ (value >>> 16)) >>> 0;
}
const SEED = hash(SEED_INPUT);
function choose(index) {
  const a = mixed(index ^ SEED), b = mixed(a ^ 0x9e3779b9), n = links.length;
  if (DISTRIBUTION === 'uniform' || n === 1) return links[b % n];
  const hot = DISTRIBUTION === 'single80' ? 1 : Math.min(10, n);
  if (hot === n) return links[b % n];
  return links[a % 100 < 80 ? b % hot : hot + b % (n - hot)];
}
function unknownCode(index) {
  let number = (SEED % 67108864) * 67108864 + index;
  if (!Number.isSafeInteger(number)) throw new Error('Unknown-key sequence exhausted');
  let code = '';
  for (let i = 0; i < 9; i++) { code = ALPHABET[number % 62] + code; number = Math.floor(number / 62); }
  return code;
}
function headers(account) {
  const result = { Host: HOST, 'Content-Type': 'application/json',
    Accept: 'application/json', 'User-Agent': 'shortlink-performance/1.0',
    Referer: 'https://shortlink-perf.local/source',
    'X-Forwarded-Proto': 'http', 'X-Forwarded-For': CLIENT_IP };
  if (!REDIRECT) {
    result['X-Internal-Token'] = metadata.internalToken;
    result['x-shortlink-tenant-id'] = String(account.tenantId);
    result['x-shortlink-username'] = account.username;
    result['x-shortlink-auth-version'] = String(account.authVersion);
  }
  return result;
}
function creation(account, unique) {
  return { requestId: `${RUN}:${MODE}:${unique}`, domain: metadata.redirectHost,
    originUrl: `https://shortlink-perf.local/target/${RUN}/${unique}`, gid: account.gid,
    createdType: 0, validDateType: 0, validDate: null, describe: 'performance metadata-denied diagnostic' };
}
function header(response, name) {
  const key = Object.keys(response.headers).find(k => k.toLowerCase() === name);
  return key === undefined ? undefined : response.headers[key];
}
function sameId(a, b) { return a !== null && a !== undefined && String(a) === String(b); }
function created(data, body) {
  return data && /^[A-Za-z0-9]{9}$/.test(data.shortUri || '') && Number(data.linkId) > 0 &&
    data.fullShortUrl === `https://${metadata.redirectHost}/${data.shortUri}` &&
    data.originUrl === body.originUrl && data.gid === body.gid &&
    Number(data.routeVersion) === 1 && Number(data.targetRevision) === 1;
}
// k6 gives each VU an independent module instance; no cross-VU mutable version sharing.
let mutationIndex = 0;
let updateLink = null;
let mutationBlocked = false;
// Module state is VU-local; an unentered phase emits no synthetic samples.
const seededMetricPhases = { warmup: false, measure: false };
export default function () {
  const start = Date.now();
  const elapsed = start - exec.scenario.startTime;
  const phase = elapsed < WARMUP_MS ? 'warmup' : 'measure';
  const tags = { phase };
  const phaseStart = phase === 'warmup' ? 0 : WARMUP_MS;
  if (METRIC_SEED_MODE === 'per-request' || !seededMetricPhases[phase]) {
    for (const metric of [sent, completed, received, correct, correctRows, status429, status503,
      clientErrors, fixtureExhausted, mutationUnconfirmed, writeUnconfirmed, criticalErrors]) metric.add(0, tags);
    if (METRIC_SEED_MODE === 'per-vu-phase') seededMetricPhases[phase] = true;
  }
  const iteration = exec.scenario.iterationInTest;
  let account = accounts[iteration % ACCOUNT_COUNT], body, selected, method = 'POST', path;
  let expectedRows = 0;
  if (REDIRECT) {
    method = MODE === 'head' ? 'HEAD' : 'GET';
    selected = UNKNOWN ? null : choose(iteration);
    path = '/' + (UNKNOWN ? unknownCode(MODE === 'unknown_fixed' ? 0 : iteration + 1) : selected.shortUri);
  } else if (MODE === 'create' || MODE === 'idempotent') {
    path = PREFIX + '/create';
    body = MODE === 'idempotent' ? account.idempotent.body : creation(account, iteration);
    expectedRows = MODE === 'create' ? 1 : 0;
  } else if (MODE === 'batch') {
    path = PREFIX + '/create/batch';
    body = creation(account, iteration);
    body.originUrls = Array.from({ length: BATCH_SIZE }, (_, i) => body.originUrl + '/' + i);
    body.describes = Array(BATCH_SIZE).fill(body.describe);
    delete body.originUrl;
    delete body.describe;
    expectedRows = BATCH_SIZE;
  } else {
    if (mutationBlocked) {
      mutationUnconfirmed.add(1, tags);
      clientElapsed.add(Date.now() - exec.scenario.startTime - phaseStart, tags);
      return;
    }
    const slot = exec.vu.idInTest - 1;
    if (MODE === 'update') {
      if (!updateLink && slot < mutations.length) updateLink = { ...mutations[slot] };
      selected = updateLink;
    } else {
      const index = slot + mutationIndex++ * VUS;
      selected = index < mutations.length ? mutations[index] : null;
    }
    const state = MODE === 'restore' ? 'DISABLED' : 'ACTIVE';
    if (!selected || !Number.isSafeInteger(Number(selected.routeVersion)) ||
        Number(selected.routeVersion) < 1 ||
        (MODE !== 'update' && selected.state !== state) ||
        (MODE === 'update' && selected.state && selected.state !== state)) {
      fixtureExhausted.add(1, tags);
      clientElapsed.add(Date.now() - exec.scenario.startTime - phaseStart, tags);
      return;
    }
    account = accounts[selected.accountIndex];
    body = { fullShortUrl: selected.fullShortUrl, gid: selected.gid,
      expectedVersion: Number(selected.routeVersion) };
    if (MODE === 'update') {
      path = PREFIX + '/update';
      Object.assign(body, { originGid: selected.gid, originUrl:
        `https://shortlink-perf.local/target/${RUN}/update-${iteration}`,
      validDateType: 0, validDate: null, describe: 'performance versioned update' });
    } else path = PREFIX + '/recycle-bin/' + (MODE === 'recycle' ? 'save' : 'recover');
    expectedRows = 1;
  }
  const requestBody = body ? JSON.stringify(body) : null;
  const requestOptions = { headers: headers(account), redirects: 0, timeout: '10s', tags };
  // Per-request replacement overrides the jar's sl_uv, including any prior Set-Cookie.
  if (REDIRECT) requestOptions.cookies = {
    sl_uv: { value: '0123456789abcdef0123456789abcdef', replace: true },
  };
  sent.add(1, tags);
  let response, ok = false, roundtripRecorded = false, criticalReason = null;
  let completionRecorded = false, clientFailure = false;
  const roundtripStarted = Date.now();
  try {
    response = http.request(method, BASE + path, requestBody, requestOptions);
    roundtrip.add(Date.now() - roundtripStarted, tags);
    roundtripRecorded = true;
    completed.add(1, tags);
    completionRecorded = true;
    if (response.status > 0) received.add(1, tags);
    else { clientErrors.add(1, tags); clientFailure = true; }
    if (response.status === 429) status429.add(1, tags);
    if (response.status === 503) status503.add(1, tags);
    latency.add(response.timings.duration, tags);
    if (REDIRECT) {
      if (!UNKNOWN && response.status === 302 && header(response, 'location') !== selected.originUrl)
        criticalReason = 'wrong redirect target';
      if (MODE === 'head' && response.body !== '' && response.body !== null)
        criticalReason = 'HEAD body invariant';
      ok = response.status === EXPECTED;
      if (EXPECTED === 302) ok = ok && !!selected && header(response, 'location') === selected.originUrl;
      else ok = ok && header(response, 'location') === undefined;
      if (MODE === 'head') ok = ok && (response.body === '' || response.body === null);
    } else if (response.status === 200) {
      let value;
      try { value = response.json(); } catch (_) { value = null; }
      ok = !!value && value.code === '0';
      if (ok && MODE === 'create') ok = created(value.data, body);
      if (ok && MODE === 'idempotent') ok = value.data &&
        sameId(value.data.linkId, account.idempotent.linkId) &&
        value.data.shortUri === account.idempotent.shortUri && value.data.originUrl === body.originUrl;
      if (ok && MODE === 'batch') {
        const data = value.data, rows = data && data.baseLinkInfos;
        ok = !!data && data.state === 'SUCCEEDED' && data.total === BATCH_SIZE &&
          Array.isArray(rows) && rows.length === BATCH_SIZE &&
          new Set(rows.map(r => String(r.linkId))).size === BATCH_SIZE &&
          rows.every((r, i) => Number(r.linkId) > 0 && r.originUrl === body.originUrls[i] &&
            typeof r.fullShortUrl === 'string' &&
            r.fullShortUrl.startsWith(`https://${metadata.redirectHost}/`) &&
            /^[A-Za-z0-9]{9}$/.test(r.fullShortUrl.split('/').pop()));
      }
    }
  } catch (_) {
    if (!roundtripRecorded) roundtrip.add(Date.now() - roundtripStarted, tags);
    if (!completionRecorded) { completed.add(1, tags); clientErrors.add(1, tags); }
    clientFailure = true;
    ok = false;
  }
  if (clientFailure) ok = false;
  // Keep the completed failing attempt and its timing/status before aborting other VUs.
  // The reason contains only status and deterministic iteration, never a response body or URL.
  if (!ok && FAIL_FAST_UNEXPECTED && !criticalReason) {
    const reason = clientFailure ? 'client failure' : response.status !== EXPECTED ?
      `unexpected HTTP status ${response.status}` : 'response contract failure';
    criticalReason = `fail-fast ${reason} at iteration ${iteration}`;
  }
  correctRate.add(ok, tags);
  if (ok) {
    correct.add(1, tags);
    correctRows.add(expectedRows, tags);
    if (MODE === 'update') updateLink.routeVersion = Number(updateLink.routeVersion) + 1;
  } else if (MODE === 'update') {
    // A timeout may follow a DB commit. Do not retry or guess the next version.
    mutationBlocked = true;
    mutationUnconfirmed.add(1, tags);
  }
  // These writes lack a successful response; this is not a claim that they committed.
  // Requests aborted in other VUs may additionally appear in sent minus completed.
  if (!ok && !REDIRECT && MODE !== 'idempotent') writeUnconfirmed.add(1, tags);
  clientElapsed.add(Date.now() - exec.scenario.startTime - phaseStart, tags);
  if (criticalReason) {
    criticalErrors.add(1, tags);
    exec.test.abort(criticalReason);
    return;
  }
}

export function handleSummary(data) {
  function values(name, phase) {
    const metric = data.metrics[phase === 'all' ? name : `${name}{phase:${phase}}`];
    return metric ? metric.values : {};
  }
  function count(name, phase) { return values(name, phase).count || 0; }
  function percentiles(trend) {
    return { p50: trend['p(50)'] ?? null, p95: trend['p(95)'] ?? null,
      p99: trend['p(99)'] ?? null, p99_9: trend['p(99.9)'] ?? null, max: trend.max ?? null };
  }
  function section(phase) {
    const c = count('perf_completed', phase);
    const duration = phase === 'all' ? data.state.testRunDurationMs :
      (values('perf_client_elapsed_ms', phase).max || 0);
    return { actual_sent: count('perf_actual_sent', phase), completed: c,
      not_completed: Math.max(0, count('perf_actual_sent', phase) - c),
      received_http: count('perf_received_http', phase), correct: count('perf_correct', phase),
      correct_rate: c ? count('perf_correct', phase) / c : null,
      correct_rows: count('perf_correct_rows', phase), status_429: count('perf_status_429', phase),
      status_503: count('perf_status_503', phase), client_errors: count('perf_client_errors', phase),
      fixture_exhausted: count('perf_fixture_exhausted', phase),
      mutation_unconfirmed: count('perf_mutation_unconfirmed', phase),
      write_unconfirmed: count('perf_write_unconfirmed', phase),
      critical_errors: count('perf_critical_errors', phase),
      client_elapsed_ms: duration, phase_duration_ms: duration,
      latency_ms: percentiles(values('perf_http_latency_ms', phase)),
      roundtrip_ms: percentiles(values('perf_roundtrip_ms', phase)) };
  }
  const all = section('all'), warmup = section('warmup'), measure = section('measure');
  const dropped = (data.metrics.dropped_iterations || { values: {} }).values.count || 0;
  const summary = { schema_version: 1, run_label: RUN, mode: MODE, sample_phase: SAMPLE_PHASE,
    test_run_duration_ms: data.state.testRunDurationMs,
    config: { rate: RATE, time_unit: TIME_UNIT, warmup_ms: WARMUP_MS, measure_ms: MEASURE_MS,
      executor: arrivalScenario.executor, warmup_start_rate: WARMUP_START_RATE,
      metric_seed_mode: METRIC_SEED_MODE,
      preallocated_vus: VUS, max_vus: VUS, accounts: ACCOUNT_COUNT, workset: WORKSET,
      fixture_workset_links: links.length, fixture_mutation_links: mutations.length,
      distribution: DISTRIBUTION, hot_workset: DISTRIBUTION === 'hot80' ? Math.min(10, WORKSET) :
        DISTRIBUTION === 'single80' ? 1 : null, seed: SEED_INPUT, request_timeout_ms: 10000,
      visitor_mode: REDIRECT ? 'fixed-repeat-sl_uv-per-request-replace' : null,
      header_mode: 'fixed-UA-Referer-and-application-json-Accept',
      batch_size: MODE === 'batch' ? BATCH_SIZE : null,
      expected_status: EXPECTED, metadata_fixture: REDIRECT ? null : 'local-host-rejected' },
    abort_policy: { enabled: ABORT_ON_FAILURE && !ALLOW_REJECTIONS,
      fail_fast_unexpected: FAIL_FAST_UNEXPECTED,
      allow_rejections: ALLOW_REJECTIONS, measure_correct_rate_minimum: 0.99,
      evaluation_delay_from_test_start_ms: WARMUP_MS + 30000 },
    measurement_correctness_thresholds:
      (data.metrics['perf_correct_rate{phase:measure}'] || {}).thresholds || {},
    fixture_valid: all.fixture_exhausted === 0 && all.mutation_unconfirmed === 0 && all.critical_errors === 0,
    dropped_iterations: dropped, dropped_iterations_scope: 'whole_single_scenario',
    phase_drop_attribution: 'unavailable: k6 drops occur before iteration phase tags exist',
    overall_transport_ms: { blocked: percentiles(values('http_req_blocked', 'all')),
      connecting: percentiles(values('http_req_connecting', 'all')),
      tls_handshaking: percentiles(values('http_req_tls_handshaking', 'all')) },
    all, warmup, measure, selected: SAMPLE_PHASE === 'all' ? all : SAMPLE_PHASE === 'warmup' ? warmup : measure,
    notes: ['correct_rows counts HTTP-confirmed created/mutated rows; idempotent reads and redirects contribute zero',
      '429/503 responses remain in received/completed and latency metrics',
      'write_unconfirmed counts completed writes without confirmed success, not proven commits; reconcile by requestId',
      'Abort can interrupt other in-flight requests: sent minus completed remains unknown and must be reconciled',
      'Refresh mutation fixtures from authority before reusing them in another run',
      'phase_duration_ms is elapsed phase time; roundtrip_ms times only the HTTP call including connection work',
      'No per-request URLs, credentials or fixture contents are included'] };
  return { [OUTPUT]: JSON.stringify(summary, null, 2) + '\n',
    stdout: `${MODE}: sent=${all.actual_sent} completed=${all.completed} correct=${all.correct}` +
      ` dropped=${dropped} fixture_valid=${summary.fixture_valid}\n` };
}
