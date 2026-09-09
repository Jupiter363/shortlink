"""Pure tests of the optional, fixed GET302 APISIX request/upstream timing schema."""
import importlib.util
import json
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('timing_test_fixture', Path(__file__).with_name('test_publisher_diagnostic_metrics.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
observe = fixture.observe
PREFIX = 'shortlink_edge_http_'


def exposition():
    return [
        PREFIX + 'seconds_count{phase="request"} 4', PREFIX + 'seconds_sum{phase="request"} 0.08',
        PREFIX + 'seconds_max{phase="request"} 0.05', PREFIX + 'seconds_count{phase="upstream"} 4',
        PREFIX + 'seconds_sum{phase="upstream"} 0.04', PREFIX + 'seconds_max{phase="upstream"} 0.03',
        PREFIX + 'samples_total{kind="eligible"} 4', PREFIX + 'samples_total{kind="request_missing"} 0',
        PREFIX + 'samples_total{kind="upstream_missing"} 0', PREFIX + 'samples_total{kind="upstream_multiple"} 0',
        PREFIX + 'samples_total{kind="upstream_invalid"} 0', PREFIX + 'observed_worker_generations 1',
        PREFIX + 'observation_complete 1']


def snapshot(rows):
    sample = fixture.snapshot()
    existing = sample['apisix']['metrics']
    edge = observe._prometheus('\n'.join(rows), edge=True)
    edge['metrics'] = existing + edge.get('metrics', [])
    edge['status'] = 'AVAILABLE'
    sample['apisix'] = edge
    return sample


class HttpTimingTests(unittest.TestCase):
    def test_all_13_values_preserve_seconds_and_scope(self):
        self.assertEqual(13, len(exposition()))
        report = observe.edge_http_timing_metrics(snapshot(exposition()))
        self.assertEqual('AVAILABLE', report['status'])
        self.assertEqual(13, report['seriesCount'])
        self.assertEqual(0.02, report['phases']['request']['meanSeconds'])
        self.assertEqual(0.01, report['phases']['upstream']['meanSeconds'])
        self.assertIn('GET_302_MATCHED_SHORTLINK_REDIRECT_ONLY', report['meaning'])

    def test_no_samples_has_no_mean_and_missing_upstream_not_zero(self):
        rows = exposition()
        rows = [line.rsplit(' ', 1)[0] + ' 0' if 'phase="upstream"' in line else line for line in rows]
        rows = [line.replace('} 0', '} 4') if 'kind="upstream_missing"' in line else line for line in rows]
        report = observe.edge_http_timing_metrics(snapshot(rows))
        self.assertEqual(4, report['samples']['upstream_missing'])
        self.assertIsNone(report['phases']['upstream']['meanSeconds'])
        self.assertEqual(0.02, report['phases']['request']['meanSeconds'])

    def test_old_or_disabled_without_series_is_not_present(self):
        sample = fixture.snapshot()
        self.assertEqual('NOT_PRESENT', observe.edge_http_timing_metrics(sample)['status'])
        self.assertTrue(observe.drained(sample)['drained'])

    def test_each_missing_series_is_explicitly_unavailable(self):
        for index in range(13):
            with self.subTest(index=index):
                rows = exposition(); rows.pop(index)
                sample = snapshot(rows)
                self.assertEqual('NOT_AVAILABLE', observe.edge_http_timing_metrics(sample)['status'])
                self.assertTrue(observe.drained(sample)['drained'])

    def test_duplicate_and_extra_labels_are_not_accepted_or_echoed(self):
        variants = [exposition() + [exposition()[0]], [exposition()[0].replace('}', ',uri="do-not-emit"}')] + exposition()[1:],
                    [exposition()[0].replace('}', ',phase="request"}')] + exposition()[1:]]
        for rows in variants:
            sample = snapshot(rows)
            self.assertEqual('NOT_AVAILABLE', observe.edge_http_timing_metrics(sample)['status'])
            self.assertNotIn('do-not-emit', json.dumps(sample))

    def test_nan_infinity_negative_fractional_count_and_bad_complete(self):
        for index, value in ((0, 'NaN'), (1, '+Inf'), (1, '-0.1'), (0, '1.5'), (12, '2'), (12, '-1')):
            with self.subTest(index=index, value=value):
                rows = exposition(); rows[index] = rows[index].rsplit(' ', 1)[0] + ' ' + value
                sample = snapshot(rows)
                self.assertEqual('NOT_AVAILABLE', observe.edge_http_timing_metrics(sample)['status'])
                json.dumps(sample, allow_nan=False)

    def test_incomplete_timing_does_not_gate_event_drain(self):
        rows = exposition(); rows[-1] = PREFIX + 'observation_complete 0'
        sample = snapshot(rows)
        self.assertEqual('EDGE_HTTP_TIMING_SNAPSHOT_INCOMPLETE', observe.edge_http_timing_metrics(sample)['reason'])
        self.assertTrue(observe.drained(sample)['drained'])

    def test_unknown_field_or_raw_upstream_cannot_escape_allowlist(self):
        for line in (PREFIX + 'seconds_sum{phase="connect"} 0.1', PREFIX + 'samples_total{kind="raw-upstream"} 0',
                     PREFIX + 'raw_upstream 1'):
            sample = snapshot(exposition() + [line])
            self.assertEqual('NOT_AVAILABLE', observe.edge_http_timing_metrics(sample)['status'])
            self.assertNotIn('raw-upstream', json.dumps(sample))
        self.assertNotIn('phase', observe.ALLOWED_LABELS)
        self.assertNotIn('kind', observe.ALLOWED_LABELS)

    def test_structured_inputs_are_revalidated(self):
        sample = snapshot(exposition())
        sample['apisix']['metrics'].append(dict(name=PREFIX + 'seconds_sum', labels=dict(phase='request'), value=0.1))
        self.assertEqual('EDGE_HTTP_TIMING_SERIES_DUPLICATED', observe.edge_http_timing_metrics(sample)['reason'])
        sample = snapshot(exposition())
        next(x for x in sample['apisix']['metrics'] if x['name'] == PREFIX + 'seconds_count')['value'] = True
        self.assertEqual('NOT_AVAILABLE', observe.edge_http_timing_metrics(sample)['status'])


if __name__ == '__main__':
    unittest.main()
