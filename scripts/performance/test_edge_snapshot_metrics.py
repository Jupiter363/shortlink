"""Pure collector-v3 validation; never re-scrapes or chooses a different sample."""
import importlib.util
import json
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('edge_snapshot_fixture', Path(__file__).with_name('test_publisher_diagnostic_metrics.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
observe = fixture.observe


def exposition():
    return ['shortlink_edge_snapshot_version 3',
            'shortlink_edge_raw_events_attempted 0', 'shortlink_edge_raw_events_delivered 0',
            'shortlink_edge_raw_events_failed 0', 'shortlink_edge_raw_events_rejected 0',
            'shortlink_edge_raw_global_reconciled 1', 'shortlink_edge_registered_worker_generations 1',
            'shortlink_edge_registration_pending 0']


def snapshot(rows=None):
    sample = fixture.snapshot()
    edge = observe._prometheus('\n'.join(exposition() if rows is None else rows), edge=True)
    edge['metrics'] = sample['apisix']['metrics'] + edge.get('metrics', []) + [dict(name='shortlink_edge_active_senders', labels={}, value=0.0)]
    edge['status'] = 'AVAILABLE'
    sample['apisix'] = edge
    return sample


def replace_metric(sample, name, value):
    next(x for x in sample['apisix']['metrics'] if x['name'] == name)['value'] = value


class EdgeSnapshotTests(unittest.TestCase):
    def test_version3_complete_and_idle_reconciliation(self):
        sample = snapshot(); sample['edgeSnapshotVersionRequired'] = 3
        observe._apply_edge_snapshot_requirement(sample)
        self.assertEqual('AVAILABLE', sample['edgeSnapshot']['status'])
        self.assertEqual(8, sample['edgeSnapshot']['seriesCount'])
        self.assertTrue(sample['edgeSnapshot']['rawGlobalReconciled'])
        self.assertTrue(observe.drained(sample)['drained'])

    def test_old_run_absent_optional_and_new_run_requires_version(self):
        sample = fixture.snapshot()
        self.assertEqual('NOT_PRESENT', observe.edge_snapshot_metrics(sample)['status'])
        self.assertTrue(observe.drained(sample)['drained'])
        sample['edgeSnapshotVersionRequired'] = 3
        observe._apply_edge_snapshot_requirement(sample)
        self.assertEqual('NOT_AVAILABLE', sample['apisix']['status'])
        self.assertIn('EDGE_SNAPSHOT_UNAVAILABLE', observe.drained(sample)['reasons'])

    def test_missing_and_duplicate_scalar_are_unavailable(self):
        for index in range(8):
            with self.subTest(index=index):
                rows = exposition(); rows.pop(index)
                self.assertEqual('NOT_AVAILABLE', observe.edge_snapshot_metrics(snapshot(rows))['status'])
        self.assertEqual('NOT_AVAILABLE', observe.edge_snapshot_metrics(snapshot(exposition() + [exposition()[0]]))['status'])

    def test_invalid_raw_nan_or_value_does_not_become_zero(self):
        for value in ('NaN', '+Inf', '-1', '0.5', 'garbage'):
            rows = exposition(); rows[1] = 'shortlink_edge_raw_events_attempted ' + value
            sample = snapshot(rows)
            self.assertEqual('NOT_AVAILABLE', observe.edge_snapshot_metrics(sample)['status'])
            self.assertIn('EDGE_SNAPSHOT_UNAVAILABLE', observe.drained(sample)['reasons'])
            json.dumps(sample, allow_nan=False)

    def test_invalid_version_reconciliation_and_labels(self):
        for line in ('shortlink_edge_snapshot_version 2', 'shortlink_edge_snapshot_version{worker="do-not-emit"} 3'):
            rows = exposition(); rows[0] = line
            sample = snapshot(rows)
            self.assertEqual('NOT_AVAILABLE', observe.edge_snapshot_metrics(sample)['status'])
            self.assertNotIn('do-not-emit', json.dumps(sample))
        rows = exposition(); rows[5] = 'shortlink_edge_raw_global_reconciled 2'
        self.assertEqual('NOT_AVAILABLE', observe.edge_snapshot_metrics(snapshot(rows))['status'])

    def test_active_cross_time_raw_difference_is_not_quality_failure(self):
        sample = snapshot(); sample['edgeSnapshotVersionRequired'] = 3
        replace_metric(sample, 'shortlink_edge_pending_count', 4)
        replace_metric(sample, 'shortlink_edge_pending_bytes', 400)
        replace_metric(sample, 'shortlink_edge_events_attempted', 4)
        replace_metric(sample, 'shortlink_edge_raw_global_reconciled', 0)
        replace_metric(sample, 'shortlink_edge_raw_events_attempted', 5)
        observe._apply_edge_snapshot_requirement(sample)
        self.assertEqual('AVAILABLE', sample['apisix']['status'])
        self.assertEqual('AVAILABLE', sample['edgeSnapshot']['status'])
        reasons = observe.drained(sample)['reasons']
        self.assertIn('EDGE_PENDING_COUNT_NOT_DRAINED', reasons)
        self.assertNotIn('EDGE_RAW_GLOBAL_NOT_RECONCILED', reasons)
        self.assertNotIn('EDGE_RAW_GLOBAL_COUNTER_MISMATCH', reasons)

    def test_idle_requires_raw_match_and_no_active_senders(self):
        sample = snapshot(); replace_metric(sample, 'shortlink_edge_raw_global_reconciled', 0)
        self.assertIn('EDGE_RAW_GLOBAL_NOT_RECONCILED', observe.drained(sample)['reasons'])
        sample = snapshot(); replace_metric(sample, 'shortlink_edge_raw_events_attempted', 1)
        self.assertIn('EDGE_RAW_GLOBAL_COUNTER_MISMATCH', observe.drained(sample)['reasons'])
        sample = snapshot(); replace_metric(sample, 'shortlink_edge_active_senders', 1)
        self.assertIn('EDGE_ACTIVE_SENDERS_UNKNOWN_OR_NONZERO', observe.drained(sample)['reasons'])

    def test_registration_pending_and_missing_senders(self):
        sample = snapshot(); replace_metric(sample, 'shortlink_edge_registration_pending', 1)
        self.assertIn('EDGE_REGISTRATION_PENDING', observe.drained(sample)['reasons'])
        sample = snapshot(); sample['apisix']['metrics'] = [x for x in sample['apisix']['metrics'] if x['name'] != 'shortlink_edge_active_senders']
        self.assertIn('EDGE_ACTIVE_SENDERS_UNKNOWN_OR_NONZERO', observe.drained(sample)['reasons'])

    def test_invalid_requirement_types(self):
        for value in (True, '3', 3.0, 2):
            sample = snapshot(); sample['edgeSnapshotVersionRequired'] = value
            self.assertIn('INVALID_EDGE_SNAPSHOT_VERSION_REQUIREMENT', observe.drained(sample)['reasons'])

    def test_worker_complete_zero_keeps_existing_drain_failure(self):
        sample = snapshot(); replace_metric(sample, 'shortlink_edge_observation_complete', 0)
        self.assertIn('EDGE_OBSERVATION_INCOMPLETE', observe.drained(sample)['reasons'])


if __name__ == '__main__':
    unittest.main()
