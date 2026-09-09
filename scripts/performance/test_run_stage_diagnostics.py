"""Pure launcher/summary contract tests. No runtime, private state, SQL or HTTP."""
import ast
import importlib.util
import json
import os
import tempfile
import threading
from pathlib import Path
import unittest
from unittest.mock import patch, sentinel

MODULE_PATH = Path(__file__).with_name('run_stage.py')
SPEC = importlib.util.spec_from_file_location('diagnostic_stage_under_test', str(MODULE_PATH))
stage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(stage)


def args(*extra):
    return stage.parser().parse_args(['unused-state.json', '--label', 'offline-diagnostics',
        '--mode', 'redirect', '--path', 'edge', '--rate', '5000', '--warmup', '30s',
        '--duration', '90s', '--vus', '512', '--p99-budget-ms', '80', *extra])


class RunStageDiagnosticsTest(unittest.TestCase):
    def test_gomaxprocs_positive_config_is_not_runtime_measurement(self):
        for raw in ('1', '4', '16'):
            value = stage.gomaxprocs_configuration({'GOMAXPROCS': raw})
            self.assertEqual(value['status'], 'CONFIGURED')
            self.assertEqual(value['configuredValue'], int(raw))
            self.assertIsNone(value['runtimeActual'])
            self.assertEqual(value['runtimeActualStatus'], 'NOT_AVAILABLE')

    def test_unset_does_not_guess_cpu_affinity_or_runtime_default(self):
        value = stage.gomaxprocs_configuration({})
        self.assertEqual(value['status'], 'NOT_CONFIGURED')
        self.assertIsNone(value['configuredValue'])
        self.assertIsNone(value['runtimeActual'])

    def test_unparsed_configuration_never_exposes_raw_contents(self):
        for raw in ('', '0', '-1', '4.0', '0004', '9999999999', 'synthetic-private-marker'):
            value = stage.gomaxprocs_configuration({'GOMAXPROCS': raw})
            self.assertEqual(value['status'], 'PRESENT_UNPARSED')
            self.assertIsNone(value['configuredValue'])
            if raw:
                self.assertNotIn('"' + raw + '"', json.dumps(value))

    def test_environment_preserves_gomaxprocs_without_changing_traffic_inputs(self):
        state = {'fixturePath': 'unused-synthetic-fixture', 'networkGateway': '192.0.2.1',
                 'redirectHost': 's.synthetic.test', 'managementHost': 'admin.synthetic.test'}
        with patch.dict(os.environ, {'GOMAXPROCS': '4', 'PERF_RATE': '999999', 'K6_OUT': 'unsafe'}, clear=True):
            env = stage.build_env(args('--source-ip-count', '64'), state, Path('unused-stage'))
        self.assertEqual(env['GOMAXPROCS'], '4')
        self.assertEqual(env['PERF_RATE'], '5000')
        self.assertNotIn('K6_OUT', env)
        self.assertEqual(env['PERF_BASE_URL'], 'http://127.0.0.1:9080')
        self.assertEqual(env['PERF_HOST'], 's.synthetic.test')

    def test_launch_records_bounds_and_sanitized_config_but_never_environment(self):
        result = {}
        env = {'GOMAXPROCS': '4', 'SYNTHETIC_PRIVATE_VALUE': 'do-not-record'}
        command = ['offline-k6', 'run']
        with patch.object(stage.subprocess, 'Popen', return_value=sentinel.process) as popen, \
                patch.object(stage.time, 'monotonic', side_effect=[10.0, 10.5]):
            process = stage.start_k6(command, env, sentinel.log, result)
        self.assertIs(process, sentinel.process)
        self.assertIs(popen.call_args[1]['env'], env)
        self.assertEqual(popen.call_args[0][0], command)
        self.assertTrue(popen.call_args[1]['start_new_session'])
        self.assertTrue(callable(popen.call_args[1]['preexec_fn']))
        bounds = result['k6Launch']
        self.assertEqual(bounds['beforePopenMonotonicSeconds'], 10.0)
        self.assertEqual(bounds['afterPopenMonotonicSeconds'], 10.5)
        self.assertLessEqual(bounds['beforePopenUtc'], bounds['afterPopenUtc'])
        self.assertEqual(bounds['scope'], 'PARENT_PROCESS_SPAWN_BOUNDS_NOT_SCENARIO_START')
        self.assertNotIn('do-not-record', json.dumps(result))
        self.assertIsNone(result['generatorConfiguration']['gomaxprocs']['runtimeActual'])

    def test_failed_launch_keeps_before_bound_without_inventing_scenario(self):
        result = {}
        with patch.object(stage.subprocess, 'Popen', side_effect=OSError('synthetic')):
            with self.assertRaises(OSError):
                stage.start_k6(['offline-k6'], {}, sentinel.log, result)
        self.assertIn('beforePopenUtc', result['k6Launch'])
        self.assertNotIn('afterPopenUtc', result['k6Launch'])
        self.assertNotIn('scenarioStart', result['k6Launch'])

    def test_legacy_or_absent_summary_fields_are_explicitly_unavailable(self):
        for summary in (None, {}, {'schema_version': 1, 'dropped_iterations': 0},
                        {'iteration_duration_ms': 0, 'scenario_time_anchor': {'status': 'invented'}}):
            result = stage.recorded_generator_diagnostics(summary)
            self.assertEqual(set(result), {'iteration_duration_ms', 'scenario_time_anchor', 'http_timing_breakdown'})
            self.assertTrue(all(value['status'] == 'NOT_AVAILABLE' for value in result.values()))

    def test_new_summary_diagnostics_are_retained_without_upgrading_missing_data(self):
        value = {'status': 'AVAILABLE', 'values': {'avg': 12}, 'scope': 'WHOLE_SINGLE_SCENARIO_COMPLETED_ITERATIONS'}
        missing = {'status': 'NOT_AVAILABLE', 'reason': 'SCENARIO_START_NOT_OBSERVED_OR_INCONSISTENT'}
        summary = {'iteration_duration_ms': value, 'scenario_time_anchor': missing}
        result = stage.recorded_generator_diagnostics(summary)
        self.assertEqual({key: result[key] for key in summary}, summary)
        self.assertEqual(result['http_timing_breakdown']['status'], 'NOT_AVAILABLE')

    def test_new_generator_options_are_independent_and_frozen(self):
        parameters = args('--http-phase-timings', '--redirect-input-mode', 'vu-precomputed',
                          '--skip-redirect-zero-rows', '--client-timeline')
        timing = stage.validate_args(parameters)
        frozen = stage.stage_config(parameters, timing)
        self.assertTrue(frozen['httpPhaseTimings'])
        self.assertTrue(frozen['clientTimeline'])
        self.assertEqual(frozen['redirectInputMode'], 'vu-precomputed')
        self.assertEqual(stage.stage_config(args(), stage.validate_args(args()))['redirectInputMode'], 'shared-array')
        parameters.workset = 11
        with self.assertRaisesRegex(ValueError, 'SMALL_REDIRECT_WORKSET'):
            stage.validate_args(parameters)

    def test_phase_timing_structure_is_retained_without_replacing_missing_phase(self):
        timing = {'enabled': True, 'source': 'K6_NATIVE_HTTP_TIMINGS', 'unit': 'ms',
                  'all': {'status': 'AVAILABLE'}, 'warmup': {'status': 'NOT_AVAILABLE'},
                  'measure': {'status': 'AVAILABLE'}}
        self.assertEqual(stage.recorded_generator_diagnostics({'http_timing_breakdown': timing})['http_timing_breakdown'], timing)

    def test_disabled_phase_timing_roundtrip_preserves_exact_workload_structure(self):
        timing = {'enabled': False, 'source': 'K6_NATIVE_HTTP_TIMINGS', 'unit': 'ms',
                  'phase_assignment': 'Request keeps its iteration-start phase, including completion across the boundary',
                  'meaning': 'Component percentiles must not be summed.',
                  **{phase: {'status': 'NOT_ENABLED', 'requests': None, 'components_ms': None}
                     for phase in ('all', 'warmup', 'measure')}}
        summary = json.loads(json.dumps({'http_timing_breakdown': timing}))
        recorded = stage.recorded_generator_diagnostics(summary)['http_timing_breakdown']
        self.assertEqual(json.loads(json.dumps(recorded)), timing)
        self.assertFalse(recorded['enabled'])

    def test_disabled_phase_timing_rejects_unknown_or_inconsistent_phase_shapes(self):
        for phase in ('all', 'warmup', 'measure'):
            for invalid in ({'status': 'UNKNOWN', 'requests': None, 'components_ms': None},
                            {'status': 'AVAILABLE', 'requests': None, 'components_ms': None},
                            {'status': 'NOT_ENABLED', 'components_ms': None},
                            {'status': 'NOT_ENABLED', 'requests': 0, 'components_ms': None},
                            {'status': 'NOT_ENABLED', 'requests': None, 'components_ms': {}}):
                with self.subTest(phase=phase, invalid=invalid):
                    timing = {'enabled': False, 'source': 'K6_NATIVE_HTTP_TIMINGS', 'unit': 'ms',
                              **{name: {'status': 'NOT_ENABLED', 'requests': None, 'components_ms': None}
                                 for name in ('all', 'warmup', 'measure')}}
                    timing[phase] = invalid
                    self.assertEqual(stage.recorded_generator_diagnostics(
                        {'http_timing_breakdown': timing})['http_timing_breakdown'],
                        {'status': 'NOT_AVAILABLE', 'reason': 'SUMMARY_FIELD_MISSING_OR_INVALID'})

    def test_enabled_phase_timing_does_not_accept_disabled_status(self):
        timing = {'enabled': True, 'source': 'K6_NATIVE_HTTP_TIMINGS', 'unit': 'ms',
                  **{phase: {'status': 'NOT_ENABLED', 'requests': None, 'components_ms': None}
                     for phase in ('all', 'warmup', 'measure')}}
        self.assertEqual(stage.recorded_generator_diagnostics(
            {'http_timing_breakdown': timing})['http_timing_breakdown'],
            {'status': 'NOT_AVAILABLE', 'reason': 'SUMMARY_FIELD_MISSING_OR_INVALID'})

    def test_cpu_uses_actual_intervals_and_rejects_pid_reuse(self):
        client = stage.ClientProcessStats()
        points = [{'pid': 10, 'startTicks': 7, 'ticks': 100, 'rssBytes': 4096},
                  {'pid': 10, 'startTicks': 7, 'ticks': 150, 'rssBytes': 8192},
                  {'pid': 10, 'startTicks': 8, 'ticks': 151, 'rssBytes': 8192}]
        with patch.object(stage, 'process_stat', side_effect=points), \
                patch.object(stage.time, 'monotonic', side_effect=[10.0, 12.5, 13.0]), \
                patch.object(stage.os, 'sysconf', return_value=100, create=True):
            first = client.capture(10)
            second = client.capture(10)
            self.assertIsNone(client.capture(10))
        self.assertIsNone(first['cpuPercent'])
        self.assertEqual(second['actualIntervalSeconds'], 2.5)
        self.assertEqual(second['cpuPercent'], 20.0)
        self.assertEqual(client.samples, 2)
        self.assertEqual(client.fault, 'CLIENT_PROCESS_IDENTITY_OR_COUNTER_CHANGED')

    def test_independent_sampler_records_while_main_collector_is_idle_and_stops(self):
        entered = threading.Event()
        def sample(pid):
            entered.set()
            return {'pid': pid, 'startTicks': 7, 'ticks': 100, 'rssBytes': 4096}
        client = stage.ClientProcessStats()
        with tempfile.TemporaryDirectory() as folder, patch.object(stage, 'process_stat', side_effect=sample), \
                patch.object(stage.os, 'sysconf', return_value=100, create=True):
            path = Path(folder) / 'k6-process.jsonl'
            try:
                client.start(10, path)
                self.assertTrue(entered.wait(timeout=2))
            finally:
                client.stop()
            rows = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
            self.assertGreaterEqual(len(rows), 1)
            self.assertEqual(rows[0]['pid'], 10)
            self.assertEqual(rows[0]['startTicks'], 7)
            self.assertIsNone(rows[0]['cpuPercent'])
            self.assertFalse(client._thread.is_alive())
            self.assertIsNone(client.fault)

    def test_original_zero_drop_gate_remains_strict_for_legacy_and_new_summaries(self):
        parameters = args()
        timing = stage.validate_args(parameters)
        summary = {'test_run_duration_ms': 120000, 'dropped_iterations': 0, 'fixture_valid': True,
                   'measure': {'phase_duration_ms': 90000, 'actual_sent': 450000,
                               'completed': 450000, 'correct_rate': 1, 'roundtrip_ms': {'p99': 20}}}
        with patch.object(stage, 'counter_delta', return_value={'edge.failed': 0}):
            self.assertTrue(stage.assess(parameters, timing, summary, {}, {}, True, 0, None)['capacityPassed'])
            summary['dropped_iterations'] = 1
            summary['iteration_duration_ms'] = {'status': 'AVAILABLE'}
            verdict = stage.assess(parameters, timing, summary, {}, {}, True, 0, None)
        self.assertFalse(verdict['capacityPassed'])
        self.assertFalse(verdict['checks']['noDroppedIterations'])

    def test_real_source_ip_binding_and_generator_cpu_command_are_unchanged(self):
        parameters = args('--source-ip-count', '64')
        command = stage.build_k6_command(parameters, {'apisixPid': 123})
        self.assertEqual(command[:3], ['taskset', '-c', '12-15'])
        self.assertEqual(command[command.index('--local-ips') + 1], '127.0.0.2-127.0.0.65')
        self.assertNotIn('--out', command)
        self.assertNotIn('--no-thresholds', command)

    def test_python_sources_parse_without_running_entry_points(self):
        for path in (MODULE_PATH, Path(__file__)):
            ast.parse(path.read_text(encoding='utf-8'), filename=str(path))


if __name__ == '__main__':
    unittest.main(verbosity=2)
