"""Pure lifecycle regression tests: no network, services or generator execution."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

import native_lifecycle as n

NS=1_000_000_000


class NativeLifecycleTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.path=Path(self.tmp.name)/"drained.json"
        self.tracker=n.NativeLifecycle(run_id="run-1",label="stage-1",pid=123,start_ticks=99,
            spawned_monotonic_ns=10*NS,active_budget_seconds=55,workers=2048)
        self.receipt=dict(schemaVersion=1,kind=n.RECEIPT_KIND,version=n.VERSION,runId="run-1",label="stage-1",
            pid=123,startTicks=99,drainedMonotonicNanos=50*NS,requestElapsedNanos=30*NS,
            requestsDrained=True,workersFinished=2048,activeRequests=0,measurementComplete=True,
            stopReason="COMPLETED",planned=100,scheduled=100,sent=99,completed=99,correct=99,errors=0,dropped=1,cancelled=0)

    def write(self,**changes):
        self.receipt.update(changes); self.path.write_text(json.dumps(self.receipt),encoding="utf-8")

    def summary(self):
        return dict(kind="NATIVE_GO_REDIRECT_GENERATOR",schemaVersion=1,version=n.VERSION,runId="run-1",label="stage-1",
            measurementComplete=True,conservationPassed=True,
            all=dict(planned_arrivals=100,scheduled=100,sent=99,completed=99,correct=99,errors=0,dropped=1,cancelled_arrivals=0,not_completed=0))

    def test_26_second_export_exceeds_old_activity_budget_but_completes_without_capacity_pass(self):
        self.write()
        self.assertEqual(self.tracker.observe(self.path,now_ns=50*NS).state,"EXPORTING")
        self.assertEqual(self.tracker.observe(self.path,now_ns=76*NS,returncode=0).state,"EXITED")
        result=self.tracker.finalize(self.summary(),0,now_ns=77*NS)
        self.assertEqual(result.state,"COMPLETE"); self.assertFalse(result.capacity_pass)

    def test_no_receipt_means_http_timeout_even_if_output_file_exists(self):
        self.assertEqual(self.tracker.observe(self.path,now_ns=65*NS).state,"ACTIVE")
        self.assertEqual(self.tracker.observe(self.path,now_ns=65*NS+1).reason,"ACTIVITY_TIMEOUT_REQUESTS_NOT_DRAINED")
        self.write(); self.assertEqual(self.tracker.observe(self.path,now_ns=66*NS,returncode=0).state,"FAILED")

    def test_export_timeout_is_bounded_from_drain_not_last_observation(self):
        self.write()
        self.assertEqual(self.tracker.observe(self.path,now_ns=70*NS).state,"EXPORTING")
        self.assertEqual(self.tracker.observe(self.path,now_ns=110*NS+1).reason,"EXPORT_TIMEOUT")

    def test_wrong_identity_late_future_incomplete_and_inconsistent_receipts_fail(self):
        bad=[dict(pid=124),dict(startTicks=100),dict(runId="other"),dict(label="other"),dict(version="1.4.0"),
             dict(drainedMonotonicNanos=66*NS),dict(drainedMonotonicNanos=9*NS),dict(activeRequests=1),
             dict(requestsDrained=False),dict(workersFinished=512),dict(completed=98),dict(dropped=2),dict(errors=True)]
        for changes in bad:
            with self.subTest(changes=changes):
                tracker=n.NativeLifecycle(run_id="run-1",label="stage-1",pid=123,start_ticks=99,
                    spawned_monotonic_ns=10*NS,active_budget_seconds=55,workers=2048)
                self.path.write_text(json.dumps(self.receipt|changes),encoding="utf-8")
                self.assertEqual(tracker.observe(self.path,now_ns=65*NS).state,"FAILED")

    def test_partial_oversized_duplicate_and_nonregular_receipts_fail(self):
        for raw in (b'{',b' '*4097,b'{"pid":1,"pid":2}'):
            with self.subTest(raw=raw[:25]):
                self.path.write_bytes(raw)
                with self.assertRaises(n.LifecycleError): n.read_receipt(self.path)
        with self.assertRaises((n.LifecycleError,OSError)): n.read_receipt(self.path.parent)

    def test_receipt_mutation_cannot_restart_export_timer(self):
        self.write(); self.tracker.observe(self.path,now_ns=50*NS)
        self.write(drainedMonotonicNanos=55*NS)
        self.assertEqual(self.tracker.observe(self.path,now_ns=55*NS).state,"FAILED")

    def test_zero_exit_without_receipt_and_nonzero_exit_are_not_success(self):
        self.assertEqual(self.tracker.observe(self.path,now_ns=50*NS,returncode=0).state,"FAILED")
        self.setUp(); self.write()
        self.assertEqual(self.tracker.observe(self.path,now_ns=50*NS,returncode=2).reason,"PROCESS_EXIT_NONZERO")

    def test_failed_http_is_drained_but_never_promoted_by_successful_export(self):
        self.write(correct=98,errors=1,measurementComplete=False,stopReason="HTTP_503")
        self.assertEqual(self.tracker.observe(self.path,now_ns=50*NS).state,"EXPORTING")
        self.assertEqual(self.tracker.finalize(self.summary(),0,now_ns=70*NS).state,"FAILED")

    def test_complete_json_and_unchanged_counts_required_after_exit(self):
        for update in ({"measurementComplete":False},{"conservationPassed":False},{"runId":"other"},
                       {"requestsDrainedReceiptError":"WRITE_FAILED"},{"all":{}}, {"all":None}):
            with self.subTest(update=update):
                tracker=n.NativeLifecycle(run_id="run-1",label="stage-1",pid=123,start_ticks=99,
                    spawned_monotonic_ns=10*NS,active_budget_seconds=55,workers=2048)
                self.write(); tracker.observe(self.path,now_ns=50*NS,returncode=0)
                self.assertEqual(tracker.finalize(self.summary()|update,0,now_ns=70*NS).state,"FAILED")

    def test_summary_parsing_time_is_also_inside_export_budget(self):
        self.write(); self.tracker.observe(self.path,now_ns=50*NS,returncode=0)
        self.assertEqual(self.tracker.finalize(self.summary(),0,now_ns=111*NS).state,"FAILED")

    def test_abort_allows_timely_drain_and_26_second_export_but_never_accepts_run(self):
        self.assertEqual(self.tracker.request_stop(40*NS).state,"STOPPING")
        self.assertEqual(self.tracker.stop_deadline_ns,52*NS)
        self.write()
        # Poll first sees a valid receipt after stop+12s. Its own drain timestamp
        # proves all requests ended inside the unchanged deadline.
        self.assertEqual(self.tracker.observe(self.path,now_ns=60*NS).state,"EXPORTING")
        self.assertEqual(self.tracker.observe(self.path,now_ns=76*NS,returncode=0).state,"EXITED")
        result=self.tracker.finalize(self.summary(),0,now_ns=77*NS)
        self.assertEqual(result.reason,"STOP_REQUESTED_RUN_NOT_ACCEPTED")
        self.assertFalse(result.capacity_pass)

    def test_abort_no_receipt_times_out_at_12_seconds_and_cannot_be_rescued(self):
        self.tracker.request_stop(40*NS)
        self.assertEqual(self.tracker.observe(self.path,now_ns=52*NS).state,"STOPPING")
        result=self.tracker.observe(self.path,now_ns=52*NS+1)
        self.assertEqual(result.reason,"STOP_TIMEOUT_REQUESTS_NOT_DRAINED")
        self.write()
        self.assertEqual(self.tracker.observe(self.path,now_ns=53*NS,returncode=0),result)

    def test_abort_cannot_extend_original_activity_deadline(self):
        self.tracker.request_stop(60*NS)
        self.assertEqual(self.tracker.request_deadline_ns,65*NS)
        self.assertEqual(self.tracker.observe(self.path,now_ns=65*NS+1).reason,
                         "ACTIVITY_TIMEOUT_REQUESTS_NOT_DRAINED")

    def test_late_future_and_wrong_identity_drain_do_not_unlock_abort_export(self):
        for changes,now in ((dict(drainedMonotonicNanos=52*NS+1),53*NS),
                            (dict(drainedMonotonicNanos=51*NS),50*NS),
                            (dict(startTicks=100),50*NS),
                            (dict(activeRequests=1),50*NS),
                            (dict(completed=98),50*NS)):
            with self.subTest(changes=changes):
                tracker=n.NativeLifecycle(run_id="run-1",label="stage-1",pid=123,start_ticks=99,
                    spawned_monotonic_ns=10*NS,active_budget_seconds=55,workers=2048)
                tracker.request_stop(40*NS)
                self.path.write_text(json.dumps(self.receipt|changes),encoding="utf-8")
                self.assertEqual(tracker.observe(self.path,now_ns=now).state,"FAILED")

    def test_repeated_stop_cannot_move_activity_or_export_deadline(self):
        self.tracker.request_stop(40*NS)
        self.tracker.request_stop(49*NS)
        self.assertEqual(self.tracker.stop_requested_ns,40*NS)
        self.assertEqual(self.tracker.stop_deadline_ns,52*NS)
        self.write()
        self.tracker.observe(self.path,now_ns=50*NS)
        self.tracker.request_stop(80*NS)
        self.assertEqual(self.tracker.stop_deadline_ns,52*NS)
        self.assertEqual(self.tracker.observe(self.path,now_ns=110*NS+1).reason,"EXPORT_TIMEOUT")

    def test_abort_caps_export_at_60_seconds_even_if_normal_budget_is_larger(self):
        tracker=n.NativeLifecycle(run_id="run-1",label="stage-1",pid=123,start_ticks=99,
            spawned_monotonic_ns=10*NS,active_budget_seconds=55,export_budget_seconds=120,workers=2048)
        tracker.request_stop(40*NS)
        self.write()
        self.assertEqual(tracker.observe(self.path,now_ns=50*NS).state,"EXPORTING")
        self.assertEqual(tracker.observe(self.path,now_ns=110*NS+1).reason,"EXPORT_TIMEOUT")

    def test_invalid_stop_grace_and_reversed_clock_fail_closed(self):
        for grace in (0,-1,13,True,float("inf"),float("nan"),1e-20):
            with self.subTest(grace=grace):
                tracker=n.NativeLifecycle(run_id="run-1",label="stage-1",pid=123,start_ticks=99,
                    spawned_monotonic_ns=10*NS,active_budget_seconds=55,workers=2048)
                self.assertEqual(tracker.request_stop(40*NS,grace).reason,"INVALID_STOP_GRACE")
        self.tracker.request_stop(40*NS)
        self.assertEqual(self.tracker.request_stop(39*NS).reason,"OBSERVER_CLOCK_REVERSED")

    def test_abort_with_failed_http_can_export_but_stays_failed(self):
        self.tracker.request_stop(40*NS)
        self.write(correct=98,errors=1,measurementComplete=False,stopReason="HTTP_503")
        self.assertEqual(self.tracker.observe(self.path,now_ns=50*NS).state,"EXPORTING")
        self.assertEqual(self.tracker.observe(self.path,now_ns=76*NS,returncode=0).state,"EXITED")
        self.assertEqual(self.tracker.finalize(self.summary(),0,now_ns=77*NS).state,"FAILED")


if __name__=="__main__": unittest.main()
