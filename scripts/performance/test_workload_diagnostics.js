'use strict';
// Native-k6-shaped summaries and an in-memory transport. No private files or network calls.
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');
const source = fs.readFileSync(path.join(__dirname, 'workload.js'), 'utf8');
const EPOCH = 1788792000000;
let groups = 0;
function check(name, action) { action(); groups++; }
function plain(value) { return JSON.parse(JSON.stringify(value)); }

function run(steps = [], env = {}, native = {}, vuCount = 2, fixtureOptions = {}) {
  const calls = [], metrics = new Map(), requests = [], contexts = [];
  let accountReads = 0, linkReads = 0, headerScans = 0, aborts = 0;
  const fixture = {internalToken: 'offline-placeholder-with-at-least-32-characters',
    managementHost: 'admin.synthetic.test', redirectHost: 's.synthetic.test', normalizedClientIp: '127.0.0.1',
    accounts: [{tenantId: '1', username: 'synthetic', authVersion: 2, gid: 'group',
      idempotent: {body: {requestId: 'stable', originUrl: 'https://example.invalid/stable'},
        linkId: 99, shortUri: '000000099'}}],
    links: Array.from({length: fixtureOptions.linkCount || 10}, (_, i) => ({accountIndex: 0, linkId: i + 1,
      shortUri: String(i + 1).padStart(9, '0'), originUrl: `https://example.invalid/${i}` +
        'x'.repeat(fixtureOptions.originPadding || 0)})),
    mutationLinks: Array.from({length: vuCount}, (_, i) => ({accountIndex: 0, linkId: i + 1,
      fullShortUrl: `https://s.synthetic.test/${String(i + 1).padStart(9, '0')}`, gid: 'group',
      routeVersion: 1, state: env.PERF_MODE === 'restore' ? 'DISABLED' : 'ACTIVE'}))};
  function add(type, name, value, tags, vu) {
    calls.push({type, name, value, phase: tags && tags.phase, vu});
    const keys = [name];
    if (tags && tags.phase) keys.push(`${name}{phase:${tags.phase}}`);
    for (const key of keys) {
      if (!metrics.has(key)) metrics.set(key, {type, samples: []});
      metrics.get(key).samples.push(value);
    }
  }
  for (let vu = 0; vu < vuCount; vu++) {
    const control = {now: EPOCH, outcome: 'ok', interruptAfterSent: false};
    function metric(type) { return class { constructor(name) { this.name = name; }
      add(value, tags) { add(type, this.name, value, tags, vu); } }; }
    const context = vm.createContext({__HARNESS: control, __ENV: {PERF_MODE: 'redirect', PERF_RATE: '10',
      PERF_VUS: String(vuCount), PERF_WARMUP: '1s', PERF_DURATION: '1s', PERF_WORKSET: '10',
      PERF_BASE_URL: 'http://127.0.0.1:1', PERF_RUN_LABEL: 'offline-diagnostics',
      PERF_OUTPUT: 'unused-output', PERF_FIXTURE: 'synthetic-only', ...env},
      Counter: metric('Counter'), Rate: metric('Rate'), Trend: metric('Trend'),
      Date: class extends Date { static now() { return control.now; } },
      open: name => { assert.strictEqual(name, 'synthetic-only'); return JSON.stringify(fixture); },
      SharedArray: class { constructor(name, make) {
        const rows = make();
        return ['perf-private-accounts', 'perf-link-workset'].includes(name) ? new Proxy(rows, {get(target, key) {
          if (/^\d+$/.test(String(key))) {
            if (name === 'perf-private-accounts') accountReads++;
            else linkReads++;
          }
          return target[key];
        }}) : rows;
      }},
      exec: {scenario: {startTime: EPOCH, iterationInTest: 0}, vu: {idInTest: vu + 1},
        test: {abort() { aborts++; throw new Error('SYNTHETIC_ABORT'); }}},
      http: {request(method, url, rawBody, options) {
        const body = rawBody ? JSON.parse(rawBody) : null;
        const mode = context.__ENV.PERF_MODE;
        const redirect = ['redirect', 'head', 'unknown_fixed', 'unknown_random'].includes(mode);
        requests.push({method, url, body, options});
        assert.strictEqual(options.timeout, '10s'); assert.strictEqual(options.redirects, 0);
        assert.strictEqual(options.headers['User-Agent'], 'shortlink-performance/1.0');
        assert.strictEqual(options.headers.Referer, 'https://shortlink-perf.local/source');
        if (redirect) {
          assert.strictEqual(options.headers['X-Internal-Token'], undefined);
          assert.deepStrictEqual(plain(options.cookies), {sl_uv: {value: '0123456789abcdef0123456789abcdef', replace: true}});
        } else {
          assert.strictEqual(options.headers['x-shortlink-tenant-id'], '1');
          assert.strictEqual(options.headers['x-shortlink-auth-version'], '2');
        }
        if (control.outcome === 'throw') throw new Error('offline transport failure');
        const status = control.outcome === '503' ? 503 : control.outcome === '429' ? 429 :
          control.outcome === 'eof' ? 0 : !redirect ? 200 : mode.startsWith('unknown_') ? 404 : 302;
        const selected = fixture.links.find(x => url.endsWith('/' + x.shortUri));
        let headers = status === 302 ? {[vu % 2 ? 'location' : 'Location']:
          control.outcome === 'wrong-location' ? 'https://example.invalid/wrong' : selected.originUrl} : {};
        if (fixtureOptions.responseHeaders)
          headers = fixtureOptions.responseHeaders(headers, selected);
        if (control.outcome === 'unexpected-location') headers.Location = 'https://example.invalid/wrong';
        const responseHeaders = new Proxy(headers, {ownKeys(target) { headerScans++; return Reflect.ownKeys(target); }});
        const created = {linkId: 99, shortUri: '000000099', fullShortUrl: 'https://s.synthetic.test/000000099',
          originUrl: body && body.originUrl, gid: body && body.gid, routeVersion: 1, targetRevision: 1};
        const data = mode === 'batch' ? {state: 'SUCCEEDED', total: body.originUrls.length,
          baseLinkInfos: body.originUrls.map((originUrl, i) => ({linkId: i + 1, originUrl,
            fullShortUrl: `https://s.synthetic.test/${String(i + 1).padStart(9, '0')}`}))} : created;
        control.now += 2;
        return {status, headers: responseHeaders, timings: {duration: 1},
          body: control.outcome === 'head-body' ? 'unexpected' : '', json: () => ({code: '0', data})};
      }}});
    vm.runInContext(source.replace(/^import .*;\r?$/gm, '')
      .replace('export const options', 'const options')
      .replace('export default function ()', 'function requestOne()')
      .replace('export function handleSummary', 'function handleSummary')
      .replace('sent.add(1, tags);', 'sent.add(1, tags); if (__HARNESS.interruptAfterSent) return;') +
      '\nglobalThis.api = {requestOne, handleSummary, options, header};', context, {timeout: 1000});
    contexts.push({context, control});
  }
  const initializedLinkReads = linkReads;
  steps.forEach((step, i) => {
    const {context, control} = contexts[step.vu || 0];
    control.now = EPOCH + step.at; control.outcome = step.outcome || 'ok';
    control.interruptAfterSent = step.outcome === 'interrupted';
    context.exec.scenario.iterationInTest = i;
    if (step.startOffset !== undefined) context.exec.scenario.startTime = EPOCH + step.startOffset;
    try { vm.runInContext('api.requestOne()', context, {timeout: 1000}); }
    catch (error) { if (error.message !== 'SYNTHETIC_ABORT') throw error; }
  });
  const recorded = {};
  for (const [name, entry] of metrics) {
    const v = entry.samples, ordered = v.slice().sort((a, b) => a - b), pct = p => ordered[Math.floor((v.length - 1) * p)];
    recorded[name] = {values: entry.type === 'Counter' ? {count: v.reduce((a, b) => a + b, 0)} :
      entry.type === 'Rate' ? {rate: v.filter(Boolean).length / v.length} :
        {avg: v.reduce((a, b) => a + b, 0) / v.length, min: ordered[0], max: ordered[v.length - 1],
          'p(50)': pct(.5), 'p(95)': pct(.95), 'p(99)': pct(.99), 'p(99.9)': pct(.999)}};
  }
  Object.assign(recorded, native);
  const summary = JSON.parse(contexts[0].context.api.handleSummary({metrics: recorded,
    state: {testRunDurationMs: 2000}})['unused-output']);
  return {summary, requests, calls, accountReads, linkReads, initializedLinkReads,
    requestLinkReads: linkReads - initializedLinkReads, headerScans, aborts, options: plain(contexts[0].context.api.options),
    lookupHeader: (headers, name) => contexts[0].context.api.header({headers}, name)};
}

check('header lookup keeps prior own/enumerable/order semantics including unusual casing', () => {
  const lookup = run().lookupHeader;
  const inherited = Object.assign(Object.create({Location: 'inherited-wrong'}), {'X-Test': '1', lOcAtIoN: 'own-correct'});
  const hidden = Object.defineProperty({LOCATION: 'enumerable-correct'}, 'Location', {value:'hidden-wrong', enumerable:false});
  const noPrototype = Object.assign(Object.create(null), {location: 'null-prototype-correct', hasOwnProperty: 123});
  for (const headers of [{}, {Location:'upper'}, {location:'lower'}, {LOCATION:'capital'},
    {location:'first',Location:'second'}, {Location:'first',location:'second'},
    inherited, Object.create({location:'inherited-only'}), hidden, noPrototype,
    {'0':'numeric', 'Set-Cookie':'value', Location:undefined}, 'primitive', 1]) {
    const key = Object.keys(headers).find(k => k.toLowerCase() === 'location');
    const expected = key === undefined ? undefined : headers[key];
    assert.strictEqual(lookup(headers, 'location'), expected);
  }
  for (const headers of [null, undefined]) assert.throws(() => lookup(headers, 'location'));
});
check('header getter failures stay failures and unmatched getters are never invoked', () => {
  const lookup = run().lookupHeader;
  const headers = {get Other() {throw new Error('must not read');}, Location:'correct'};
  assert.strictEqual(lookup(headers, 'location'), 'correct');
  assert.throws(() => lookup({get Location() {throw new Error('invalid header');}}, 'location'), /invalid header/);
});
for (const [name, headers, correctCount] of [
  ['unusual casing', (old, link) => ({lOcAtIoN:link.originUrl}), 1],
  ['first own match wins', (old, link) => ({location:link.originUrl,Location:'https://wrong.invalid'}), 1],
  ['wrong first match aborts', (old, link) => ({location:'https://wrong.invalid',Location:link.originUrl}), 0],
  ['inherited match is ignored', (old, link) => Object.create({Location:link.originUrl}), 0],
  ['missing match aborts', () => ({}), 0]]) {
  check('response validation remains exact after header lookup change: ' + name, () => {
    const r = run([{at:1010}], {PERF_REDIRECT_INPUT_MODE:'vu-precomputed',PERF_SKIP_REDIRECT_ZERO_ROWS:'true',
      PERF_METRIC_SEED_MODE:'per-vu-phase',PERF_FAIL_FAST_UNEXPECTED:'true'}, {}, 1, {responseHeaders:headers});
    assert.strictEqual(r.summary.all.actual_sent, 1); assert.strictEqual(r.summary.all.completed, 1);
    assert.strictEqual(r.summary.all.received_http, 1); assert.strictEqual(r.summary.all.correct, correctCount);
    assert.strictEqual(r.summary.all.not_completed, 0); assert.strictEqual(r.aborts, 1-correctCount);
  });
}

check('reuses per-VU/per-phase immutable options without account reads and scans Location once', () => {
  const r = run([{at:10}, {at:20}, {at:1010}, {at:1020}, {vu:1,at:1030}]);
  assert.strictEqual(r.accountReads, 0); assert.strictEqual(r.headerScans, 5);
  assert.strictEqual(r.requests[0].options, r.requests[1].options);
  assert.strictEqual(r.requests[2].options, r.requests[3].options);
  assert.notStrictEqual(r.requests[0].options, r.requests[2].options);
  assert(Object.isFrozen(r.requests[0].options)); assert(Object.isFrozen(r.requests[0].options.cookies.sl_uv));
  assert.strictEqual(r.summary.all.actual_sent, 5); assert.strictEqual(r.summary.all.correct, 5);
  assert.strictEqual(r.summary.all.correct_rows, 0);
  assert.strictEqual(r.calls.filter(c => c.name === 'perf_scenario_start_epoch_ms').length, 2);
});
check('native consistent scenario epoch gives exact scheduled anchors across VUs', () => {
  const r = run([{at:10}, {vu:1,at:1010}]); const anchor = r.summary.scenario_time_anchor;
  assert.strictEqual(anchor.status, 'AVAILABLE'); assert.strictEqual(anchor.scenario_start_epoch_ms, EPOCH);
  assert.strictEqual(anchor.scenario_start_utc, new Date(EPOCH).toISOString());
  assert.strictEqual(anchor.scheduled_measure_start_epoch_ms, EPOCH + 1000);
  assert.strictEqual(anchor.scheduled_end_epoch_ms, EPOCH + 2000);
});
check('inconsistent native starts are unavailable rather than choosing one', () => {
  const r = run([{at:10}, {vu:1,at:20,startOffset:1}]);
  assert.strictEqual(r.summary.scenario_time_anchor.status, 'NOT_AVAILABLE');
  assert.strictEqual(r.summary.scenario_time_anchor.scenario_start_epoch_ms, null);
});
check('no iterations emits no anchor or fake duration', () => {
  const r = run(); assert.strictEqual(r.calls.length, 0);
  assert.strictEqual(r.summary.scenario_time_anchor.status, 'NOT_AVAILABLE');
  assert.strictEqual(r.summary.iteration_duration_ms.status, 'NOT_AVAILABLE');
  assert.strictEqual(r.summary.iteration_duration_ms.values, null);
});
check('complete native iteration metric remains distinct from HTTP and phase duration', () => {
  const values = {avg: 7, min: 4, 'p(50)': 6, 'p(95)': 10, 'p(99)': 12, 'p(99.9)': 14, max: 15};
  const r = run([{at:1010}], {}, {iterations:{values:{count:1}}, iteration_duration:{values}});
  assert.strictEqual(r.summary.iteration_duration_ms.status, 'AVAILABLE');
  assert.strictEqual(r.summary.iteration_duration_ms.values.avg, 7);
  assert.strictEqual(r.summary.iteration_duration_ms.values.p99, 12);
  assert.strictEqual(r.summary.measure.roundtrip_ms.p99, 2);
  assert.strictEqual(r.summary.measure.latency_ms.p99, 1);
  assert.strictEqual(r.summary.measure.phase_duration_ms, 12);
});
for (const value of [undefined, {avg:1}, {avg:-1,min:0,max:2,'p(50)':1,'p(95)':1,'p(99)':2,'p(99.9)':2}]) {
  check('missing/partial/invalid duration stays unavailable ' + JSON.stringify(value), () => {
    const r = run([{at:10}], {}, {iterations:{values:{count:1}}, iteration_duration:{values:value || {}}});
    assert.strictEqual(r.summary.iteration_duration_ms.status, 'NOT_AVAILABLE');
  });
}
check('zero native completed iterations is not a zero duration observation', () => {
  const r = run([], {}, {iterations:{values:{count:0}}});
  assert.strictEqual(r.summary.iteration_duration_ms.completed_iterations, 0);
  assert.strictEqual(r.summary.iteration_duration_ms.values, null);
});
check('existing per-VU phase seeding and interrupted counts retain their meaning', () => {
  const r = run([{at:10}, {at:20}, {vu:1,at:30}, {at:1010}, {at:1020,outcome:'interrupted'}],
    {PERF_METRIC_SEED_MODE:'per-vu-phase'});
  assert.strictEqual(r.calls.filter(c => c.name === 'perf_actual_sent' && c.value === 0).length, 3);
  assert.strictEqual(r.summary.all.actual_sent, 5); assert.strictEqual(r.summary.all.completed, 4);
  assert.strictEqual(r.summary.all.not_completed, 1);
});
check('zero warmup never emits warmup request samples', () => {
  const r = run([{at:10}], {PERF_WARMUP:'0s',PERF_METRIC_SEED_MODE:'per-vu-phase'});
  assert.strictEqual(r.summary.warmup.actual_sent, 0);
  assert(!r.calls.some(c => c.phase === 'warmup'));
});
for (const [mode, outcome] of [['redirect','wrong-location'],['head','head-body'],['unknown_fixed','unexpected-location']]) {
  check('critical response contract preserved ' + mode, () => {
    const r = run([{at:1010,outcome}], {PERF_MODE:mode,PERF_FAIL_FAST_UNEXPECTED:'true'});
    assert.strictEqual(r.aborts, 1); assert.strictEqual(r.summary.all.correct, 0);
    assert.strictEqual(r.summary.all.completed, 1);
  });
}
for (const outcome of ['503','eof','throw']) {
  check('fail-fast preserves evidence ' + outcome, () => {
    const r = run([{at:1010,outcome}], {PERF_FAIL_FAST_UNEXPECTED:'true'});
    assert.strictEqual(r.aborts, 1); assert.strictEqual(r.summary.all.completed, 1);
    assert.strictEqual(r.summary.all.correct, 0);
    assert.strictEqual(outcome === '503' ? r.summary.all.status_503 : r.summary.all.client_errors, 1);
  });
}
check('allowed 429 stays completed but incorrect', () => {
  const r = run([{at:1010,outcome:'429'}], {PERF_ALLOW_REJECTIONS:'true'});
  assert.strictEqual(r.summary.all.status_429, 1); assert.strictEqual(r.summary.all.correct, 0);
  assert.strictEqual(r.aborts, 0);
});
for (const mode of ['head','unknown_fixed','unknown_random','create','idempotent','batch','update','recycle','restore']) {
  check('original mode contract preserved ' + mode, () => {
    const r = run([{at:1010}], {PERF_MODE:mode});
    assert.strictEqual(r.summary.all.correct, 1); assert.strictEqual(r.aborts, 0);
    if (['head','unknown_fixed','unknown_random'].includes(mode)) assert.strictEqual(r.accountReads, 0);
    else assert(r.accountReads > 0);
  });
}
check('ramp, VUs and whole-scenario drop scope unchanged', () => {
  const r = run([{at:10}], {PERF_WARMUP_START_RATE:'1'}, {dropped_iterations:{values:{count:9}}});
  assert.strictEqual(r.options.scenarios.redirect.executor, 'ramping-arrival-rate');
  assert.strictEqual(r.options.scenarios.redirect.maxVUs, 2);
  assert.strictEqual(r.summary.dropped_iterations, 9);
  assert.strictEqual(r.summary.dropped_iterations_scope, 'whole_single_scenario');
  assert(r.summary.phase_drop_attribution.startsWith('unavailable:'));
  assert.strictEqual(r.summary.schema_version, 1);
});
check('new controls default to compatible behavior and add no native phase submetrics', () => {
  const r = run([{at:10}, {at:1010}]);
  assert.strictEqual(r.summary.config.http_phase_timings, false);
  assert.strictEqual(r.summary.config.redirect_input_mode, 'shared-array');
  assert.strictEqual(r.summary.config.skip_redirect_zero_rows, false);
  assert.strictEqual(r.summary.http_timing_breakdown.measure.status, 'NOT_ENABLED');
  assert.strictEqual(r.summary.http_timing_breakdown.measure.components_ms, null);
  assert(!Object.keys(r.options.thresholds).some(k => k.startsWith('http_req')));
  assert.strictEqual(r.requestLinkReads, 2);
});
const timingNames = ['http_req_sending', 'http_req_waiting', 'http_req_receiving',
  'http_req_blocked', 'http_req_connecting', 'http_req_tls_handshaking'];
function timingValues(value) {
  return {avg:value,min:value,max:value,'p(50)':value,'p(95)':value,'p(99)':value,'p(99.9)':value};
}
function nativeTimings() {
  const result = {};
  for (const [phase, count, base] of [['all',5,100], ['warmup',1,10], ['measure',4,20]]) {
    const key = name => phase === 'all' ? name : `${name}{phase:${phase}}`;
    result[key('http_reqs')] = {values:{count}};
    timingNames.forEach((name, i) => result[key(name)] = {values:timingValues(base + i)});
  }
  return result;
}
check('native sending TTFB receiving retain independent phase values without extra custom adds', () => {
  const steps = [{at:999},{at:1010},{at:1020},{at:1030},{at:1040}];
  const normal = run(steps), measured = run(steps, {PERF_HTTP_PHASE_TIMINGS:'true'}, nativeTimings());
  assert.deepStrictEqual(measured.calls, normal.calls);
  assert.deepStrictEqual(plain(measured.requests), plain(normal.requests));
  assert.strictEqual(measured.requests[0].options.tags.phase, 'warmup'); // Completes after 1000ms.
  const b = measured.summary.http_timing_breakdown;
  assert.strictEqual(b.warmup.requests, 1); assert.strictEqual(b.measure.requests, 4);
  assert.strictEqual(b.warmup.components_ms.sending.values.p99, 10);
  assert.strictEqual(b.measure.components_ms.waiting_ttfb.values.p99, 21);
  assert.strictEqual(b.measure.components_ms.receiving.values.p99, 22);
  assert.strictEqual(b.all.components_ms.waiting_ttfb.values.p99, 101);
  assert.strictEqual(b.measure.status, 'AVAILABLE');
  assert.strictEqual(Object.keys(measured.options.thresholds).filter(k => k.startsWith('http_req')).length, 14);
  for (const [key,value] of Object.entries(normal.options.thresholds))
    assert.deepStrictEqual(measured.options.thresholds[key], value);
});
check('phase timing never substitutes overall data for a missing phase', () => {
  const metrics = nativeTimings(); delete metrics['http_req_waiting{phase:measure}'];
  const r = run([{at:1010}], {PERF_HTTP_PHASE_TIMINGS:'true'}, metrics);
  const b = r.summary.http_timing_breakdown;
  assert.strictEqual(b.all.status, 'AVAILABLE');
  assert.strictEqual(b.measure.status, 'NOT_AVAILABLE');
  assert.strictEqual(b.measure.components_ms.waiting_ttfb.values, null);
  assert.strictEqual(b.measure.components_ms.receiving.status, 'AVAILABLE');
});
for (const bad of [undefined, NaN, Infinity, -1]) {
  check('invalid native part is absent instead of zero ' + String(bad), () => {
    const metrics = nativeTimings();
    metrics['http_req_receiving{phase:measure}'] = {values:timingValues(bad)};
    const r = run([{at:1010}], {PERF_HTTP_PHASE_TIMINGS:'true'}, metrics);
    assert.strictEqual(r.summary.http_timing_breakdown.measure.components_ms.receiving.status, 'NOT_AVAILABLE');
    assert.strictEqual(r.summary.http_timing_breakdown.measure.components_ms.receiving.values, null);
  });
}
for (const count of [undefined, 0, -1, 1.5]) {
  check('missing or zero native request population is not measured zero time ' + String(count), () => {
    const metrics = nativeTimings(); metrics['http_reqs{phase:measure}'] = {values:{count}};
    const r = run([], {PERF_HTTP_PHASE_TIMINGS:'true'}, metrics);
    assert.strictEqual(r.summary.http_timing_breakdown.measure.status, 'NOT_AVAILABLE');
    assert.strictEqual(r.summary.http_timing_breakdown.measure.components_ms.sending.values, null);
  });
}
check('measured zero timing remains available with positive native request count', () => {
  const metrics = nativeTimings(); metrics['http_req_sending{phase:measure}'] = {values:timingValues(0)};
  const r = run([{at:1010}], {PERF_HTTP_PHASE_TIMINGS:'true'}, metrics);
  assert.strictEqual(r.summary.http_timing_breakdown.measure.components_ms.sending.status, 'AVAILABLE');
  assert.strictEqual(r.summary.http_timing_breakdown.measure.components_ms.sending.values.p99, 0);
});
check('precomputation preserves frozen seed42 golden sequence and removes request SharedArray reads', () => {
  const steps = Array.from({length:20}, (_,i) => ({at:10+i}));
  const expected = [10,5,5,5,7,7,1,7,2,3,2,4,9,8,7,10,7,3,5,9];
  const original = run(steps), cached = run(steps, {PERF_REDIRECT_INPUT_MODE:'vu-precomputed'});
  assert.deepStrictEqual(cached.requests.map(r => Number(r.url.split('/').pop())), expected);
  assert.deepStrictEqual(plain(cached.requests), plain(original.requests));
  assert.strictEqual(cached.initializedLinkReads, 20); // 10 inputs in each of 2 VUs.
  assert.strictEqual(cached.requestLinkReads, 0); assert.strictEqual(original.requestLinkReads, 20);
  assert.deepStrictEqual(cached.summary.all, original.summary.all);
  assert.deepStrictEqual(cached.options, original.options);
});
for (const distribution of ['uniform','hot80','single80']) {
  check('precomputed mode preserves distribution and headers ' + distribution, () => {
    const steps = Array.from({length:256}, (_,i) => ({at:i%2 ? 1010+i : 10+i, vu:i%2}));
    const original = run(steps, {PERF_DISTRIBUTION:distribution});
    const cached = run(steps, {PERF_DISTRIBUTION:distribution,PERF_REDIRECT_INPUT_MODE:'vu-precomputed'});
    assert.deepStrictEqual(plain(cached.requests), plain(original.requests));
    assert.strictEqual(cached.requestLinkReads, 0);
    assert.deepStrictEqual(cached.summary.measure, original.summary.measure);
  });
}
check('precomputed storage has a hard count and retained-character budget', () => {
  assert.throws(() => run([], {PERF_REDIRECT_INPUT_MODE:'vu-precomputed',PERF_WORKSET:'11'}, {}, 1,
    {linkCount:11}), /exceeds 10 links/);
  assert.throws(() => run([], {PERF_REDIRECT_INPUT_MODE:'vu-precomputed'}, {}, 1,
    {originPadding:4000}), /character budget exceeded/);
});
for (const seed of ['per-request','per-vu-phase']) {
  check('skip zero rows removes only redundant success samples ' + seed, () => {
    const steps = [{at:10},{at:20},{at:1010},{at:1020,outcome:'503'},{at:1030,outcome:'interrupted'}];
    const env = {PERF_METRIC_SEED_MODE:seed};
    const original = run(steps, env), lean = run(steps, {...env,PERF_SKIP_REDIRECT_ZERO_ROWS:'true'});
    for (const phase of ['all','warmup','measure']) assert.deepStrictEqual(lean.summary[phase], original.summary[phase]);
    assert.deepStrictEqual(plain(lean.requests), plain(original.requests));
    assert.strictEqual(original.calls.length - lean.calls.length, 3);
    assert.deepStrictEqual(lean.calls.filter(c => c.name !== 'perf_correct_rows'),
      original.calls.filter(c => c.name !== 'perf_correct_rows'));
    assert.strictEqual(lean.summary.all.not_completed, 1);
  });
}
for (const outcome of ['wrong-location','503','eof','throw']) {
  check('all controls preserve critical/error accounting ' + outcome, () => {
    const env = {PERF_HTTP_PHASE_TIMINGS:'true',PERF_REDIRECT_INPUT_MODE:'vu-precomputed',
      PERF_SKIP_REDIRECT_ZERO_ROWS:'true',PERF_METRIC_SEED_MODE:'per-vu-phase',PERF_FAIL_FAST_UNEXPECTED:'true'};
    const r = run([{at:1010,outcome}], env);
    assert.strictEqual(r.aborts, 1); assert.strictEqual(r.summary.all.correct, 0);
    assert.strictEqual(r.summary.all.actual_sent, 1); assert.strictEqual(r.summary.all.completed, 1);
    assert.strictEqual(r.summary.all.critical_errors, 1);
  });
}
for (const env of [{PERF_REDIRECT_INPUT_MODE:''},{PERF_REDIRECT_INPUT_MODE:'unbounded'},
  {PERF_HTTP_PHASE_TIMINGS:'1'},{PERF_SKIP_REDIRECT_ZERO_ROWS:'1'},
  {PERF_MODE:'head',PERF_REDIRECT_INPUT_MODE:'vu-precomputed'},
  {PERF_MODE:'create',PERF_SKIP_REDIRECT_ZERO_ROWS:'true'}]) {
  check('invalid or out-of-scope performance control fails early ' + JSON.stringify(env), () => {
    assert.throws(() => run([], env));
  });
}
console.log(JSON.stringify({status:'PASS',groups,httpRequests:0,privateFilesRead:0,k6Processes:0,
  limits:'Mock transport; real k6 input-object compatibility and engine cancellation need the authorized smoke'}));
