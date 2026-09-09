"""Pure Prometheus contract tests. No HTTP, subprocess, service or load is used."""
import copy
import datetime as dt
import importlib.util
import json
from pathlib import Path
import sys
import time
import unittest

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("publisher_observe_test", Path(__file__).with_name("observe.py"))
observe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(observe)
PREFIX = "shortlink_events_diagnostic_"


def exposition():
    # Independent wire fixture: all fixed fields from the publisher, not parser schema iteration.
    phases = "offer_lock_wait offer_lock_hold queue_to_send send_call ack_terminal_lock_wait terminal_lock_hold reservation_hold".split()
    first = "present epoch_millis reason_code event_bytes reserved_count queued_count outside_queue_count reserved_bytes awaiting_callback send_active terminal_active last_send_return_ago_nanos last_callback_ago_nanos last_terminal_ago_nanos".split()
    native = "batch-size-avg batch-size-max batch-split-total compression-rate-avg record-queue-time-avg record-queue-time-max request-latency-avg request-latency-max requests-in-flight request-total bufferpool-wait-time-total bufferpool-wait-ratio buffer-available-bytes buffer-total-bytes waiting-threads metadata-age metadata-wait-time-ns-total record-retry-total record-error-total record-send-total records-per-request-avg".split()
    rows = []
    def emit(name, lane, value=0, **labels):
        tags = dict(lane=lane, **labels)
        rows.append(name + "{" + ",".join('{}="{}"'.format(k, v) for k, v in tags.items()) + "} " + str(value))
    for lane in ("click", "result"):
        for name in ("send_calls_total", "send_exceptions_total"):
            emit(PREFIX + name, lane)
        for outcome in ("success", "failed"):
            emit(PREFIX + "callbacks_total", lane, outcome=outcome)
        for suffix in ("count", "sum", "max"):
            for phase in phases:
                emit(PREFIX + "duration_seconds_" + suffix, lane, phase=phase)
        for name, kinds in (("limit", "count bytes event_bytes timing_sample_every"), ("high_watermark", "count bytes"),
                            ("state", "reserved queued outside_queue awaiting_callback send_active terminal_active")):
            for kind in kinds.split():
                emit(PREFIX + name, lane, 1000 if name == "limit" else 0, kind=kind)
        for phase in ("send_return", "callback", "terminal"):
            emit(PREFIX + "progress_age", lane, -1, phase=phase)
        for field in first:
            emit(PREFIX + "first_rejection", lane, field=field)
        for name in native:
            emit(PREFIX + "kafka", lane, metric=name)
            emit(PREFIX + "kafka_available", lane, 1, metric=name)
        for reason in ("closed", "event_size", "slots", "bytes", "queue_offer"):
            emit("shortlink_events_rejected_reason_total", lane, reason=reason)
    return rows


def snapshot(rows=None):
    redirect = observe._prometheus("hikaricp_connections_pending{pool=\"redirect\"} 0\n" + "\n".join(rows or []))
    edge = observe._prometheus("\n".join([
        "shortlink_edge_events_attempted 0", "shortlink_edge_events_delivered 0", "shortlink_edge_events_failed 0", "shortlink_edge_events_rejected 0",
        "shortlink_edge_pending_count 0", "shortlink_edge_pending_bytes 0", "shortlink_edge_observation_complete 1",
        "shortlink_edge_observation_faults 0", "shortlink_edge_started_at_seconds 1",
        'shortlink_edge_instance_info{instance="fixture",boot_id="fixture-boot"} 1']), edge=True)
    services = {name: {"status": "AVAILABLE", "metrics": []} for name in observe.SERVICES}
    services["shortlink-redirect"] = redirect
    return {"observedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "services": services, "apisix": edge,
            "redirectQuality": {"status": "AVAILABLE", "data": {"observedAt": int(time.time() * 1000),
                "lanes": {lane: dict(attempted=0, delivered=0, failed=0, rejected=0, pending=0) for lane in ("click", "result")}}},
            "mysql": {"status": "AVAILABLE", "data": {"outbox": {"pending": 0}, "metadata": {"pending": 0}}}}


class PublisherDiagnosticTests(unittest.TestCase):
    def test_complete_fixed_202_and_no_samples_mean(self):
        rows = exposition()
        self.assertEqual(202, len(rows))
        report = observe.publisher_diagnostic_metrics(snapshot(rows))
        self.assertEqual("AVAILABLE", report["status"])
        self.assertEqual(202, report["seriesCount"])
        self.assertEqual("READY", report["nativeReadiness"])
        self.assertIsNone(report["lanes"]["click"]["timings"]["send_call"]["meanSeconds"])
        self.assertEqual(-1, report["lanes"]["click"]["progressAgeNanos"]["callback"])

    def test_real_nan_text_with_explicit_availability_zero(self):
        rows = exposition()
        rows = [line.replace('} 0', '} NaN') if line.startswith(PREFIX + 'kafka{lane="click",metric="request-latency-avg"') else line
                for line in rows]
        rows = [line.replace('} 1', '} 0') if line.startswith(PREFIX + 'kafka_available{lane="click",metric="request-latency-avg"') else line
                for line in rows]
        sample = snapshot(rows)
        sample["publisherDiagnosticObservationRequired"] = True
        observe._apply_publisher_requirement(sample)
        report = sample["publisherDiagnostics"]
        self.assertEqual("AVAILABLE", report["status"])
        self.assertEqual("PARTIAL", report["nativeReadiness"])
        self.assertEqual(1, report["nativeUnavailableCount"])
        self.assertIsNone(report["lanes"]["click"]["native"]["request-latency-avg"]["value"])
        self.assertEqual("AVAILABLE", sample["services"]["shortlink-redirect"]["status"])
        self.assertTrue(observe.drained(sample)["drained"])
        json.dumps(sample, allow_nan=False)

    def test_native_pair_mismatch_and_infinity(self):
        for actual, availability in (("NaN", "1"), ("0", "0"), ("+Inf", "0"), ("-Inf", "0"), ("0", "2")):
            with self.subTest(actual=actual, availability=availability):
                rows = exposition()
                rows = [line.rsplit(" ", 1)[0] + " " + actual if line.startswith(PREFIX + 'kafka{lane="click",metric="batch-size-avg"') else line for line in rows]
                rows = [line.rsplit(" ", 1)[0] + " " + availability if line.startswith(PREFIX + 'kafka_available{lane="click",metric="batch-size-avg"') else line for line in rows]
                self.assertEqual("NOT_AVAILABLE", observe.publisher_diagnostic_metrics(snapshot(rows))["status"])

    def test_missing_native_row_is_not_explained_by_zero_availability(self):
        rows = [line for line in exposition() if not line.startswith(PREFIX + 'kafka{lane="click",metric="batch-size-avg"')]
        rows = [line.replace('} 1', '} 0') if line.startswith(PREFIX + 'kafka_available{lane="click",metric="batch-size-avg"') else line for line in rows]
        self.assertEqual("PUBLISHER_DIAGNOSTIC_SERIES_INCOMPLETE", observe.publisher_diagnostic_metrics(snapshot(rows))["reason"])

    def test_each_family_missing_is_incomplete(self):
        for index in (0, 2, 4, 25, 29, 31, 37, 40, 54, 55, 96, 201):
            with self.subTest(index=index):
                rows = exposition(); rows.pop(index)
                self.assertEqual("NOT_AVAILABLE", observe.publisher_diagnostic_metrics(snapshot(rows))["status"])

    def test_duplicate_is_invalid(self):
        rows = exposition(); rows.append(rows[0])
        self.assertEqual("NOT_AVAILABLE", observe.publisher_diagnostic_metrics(snapshot(rows))["status"])

    def test_extra_label_or_bad_label_encoding_is_invalid_not_echoed(self):
        for suffix in (',client="do-not-emit"', ',lane="click"', ',phase="do-not-emit"', ',bad syntax'):
            with self.subTest(suffix=suffix):
                rows = exposition(); rows[0] = rows[0].replace('}', suffix + '}')
                sample = snapshot(rows)
                self.assertEqual("NOT_AVAILABLE", observe.publisher_diagnostic_metrics(sample)["status"])
                self.assertNotIn("do-not-emit", json.dumps(sample))

    def test_unknown_metric_or_lane_is_not_silently_ignored(self):
        for line in (PREFIX + 'unexpected{lane="click"} 1', PREFIX + 'state{lane="third",kind="reserved"} 1'):
            self.assertEqual("NOT_AVAILABLE", observe.publisher_diagnostic_metrics(snapshot(exposition() + [line]))["status"])

    def test_invalid_count_negative_and_timer_nan(self):
        for value in ("-1", "0.5", "NaN", "garbage"):
            rows = exposition(); rows[0] = rows[0].rsplit(' ', 1)[0] + ' ' + value
            self.assertEqual("NOT_AVAILABLE", observe.publisher_diagnostic_metrics(snapshot(rows))["status"])

    def test_structured_duplicate_or_native_marker_corruption(self):
        sample = snapshot(exposition())
        sample['services']['shortlink-redirect']['metrics'].append(copy.deepcopy(sample['services']['shortlink-redirect']['metrics'][1]))
        self.assertEqual("PUBLISHER_DIAGNOSTIC_SERIES_DUPLICATED", observe.publisher_diagnostic_metrics(sample)["reason"])
        sample = snapshot(exposition())
        sample['services']['shortlink-redirect']['publisherDiagnosticMissing'] = [{"name": "untrusted", "labels": {}}]
        self.assertEqual("NOT_AVAILABLE", observe.publisher_diagnostic_metrics(sample)["status"])

    def test_old_jar_optional_and_required_schema_gate(self):
        sample = snapshot()
        self.assertEqual("NOT_PRESENT", observe.publisher_diagnostic_metrics(sample)["status"])
        self.assertTrue(observe.drained(sample)["drained"])
        sample["publisherDiagnosticObservationRequired"] = True
        observe._apply_publisher_requirement(sample)
        self.assertEqual("NOT_AVAILABLE", sample["services"]["shortlink-redirect"]["status"])
        self.assertIn("PUBLISHER_DIAGNOSTICS_UNAVAILABLE", observe.drained(sample)["reasons"])

    def test_invalid_flag_and_required_complete_schema(self):
        sample = snapshot(exposition()); sample['publisherDiagnosticObservationRequired'] = 'true'
        self.assertIn('INVALID_PUBLISHER_DIAGNOSTIC_OBSERVATION_FLAG', observe.drained(sample)['reasons'])
        sample['publisherDiagnosticObservationRequired'] = True
        observe._apply_publisher_requirement(sample)
        self.assertTrue(observe.drained(sample)['drained'])

    def test_partial_optional_does_not_reclassify_other_metrics(self):
        sample = snapshot(exposition()[:-1]); observe._apply_publisher_requirement(sample)
        self.assertEqual('NOT_AVAILABLE', sample['publisherDiagnostics']['status'])
        self.assertEqual('AVAILABLE', sample['services']['shortlink-redirect']['status'])
        self.assertTrue(observe.drained(sample)['drained'])

    def test_fixed_labels_do_not_expand_global_allowlist(self):
        self.assertTrue({'phase', 'kind', 'field', 'metric', 'reason'}.isdisjoint(observe.ALLOWED_LABELS))
        report = observe._prometheus('jvm_memory_used_bytes{kind="unexpected"} 99\nprocess_cpu_usage 0.1')
        self.assertEqual(['process_cpu_usage'], [x['name'] for x in report['metrics']])

    def test_existing_pipeline_failure_contract_and_event_drain_are_unchanged(self):
        sample = snapshot(exposition())
        sample['services']['shortlink-command'] = observe._prometheus('\n'.join([
            'shortlink_outbox_claim_failures_total 2',
            'shortlink_metadata_intake_batches_total{outcome="failed"} 3',
            'shortlink_metadata_intake_offset_commits_total{outcome="failed"} 4',
            'shortlink_metadata_intake_records_total{outcome="rejected"} 5']))
        self.assertEqual({'outbox.claim_failures': 2, 'metadata.intake_batches_failed': 3,
                          'metadata.intake_offset_commits_failed': 4, 'metadata.intake_records_rejected': 5},
                         observe.pipeline_failure_counters(sample))
        self.assertTrue(observe.drained(sample)['drained'])  # Drain is quiescence, not zero cumulative failures.
        sample['redirectQuality']['data']['lanes']['click']['pending'] = 1
        self.assertIn('REDIRECT_CLICK_NOT_DRAINED', observe.drained(sample)['reasons'])


if __name__ == '__main__':
    unittest.main()
