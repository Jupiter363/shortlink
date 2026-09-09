"""Pure mocked EP12 tests. Never calls HTTP, Docker, nsenter or services."""
from contextlib import nullcontext
import http.client
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location('edge_path', Path(__file__).with_name('verify_edge_path.py'))
v = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(v)


def response(status=200, body=b'', headers=None):
    return dict(status=status,body=body,headers=headers or [],socketSource='127.0.0.1',elapsedMs=0)


def observation(attempted=100, delivered=None, pending=0, boot='boot-a', instance='edge', started=1000):
    delivered = attempted-pending if delivered is None else delivered
    fields = dict.fromkeys(v.METRIC_NAMES,0)
    fields.update(shortlink_edge_events_attempted=attempted,shortlink_edge_events_delivered=delivered,
                  shortlink_edge_pending_count=pending,shortlink_edge_pending_bytes=pending*100,
                  shortlink_edge_started_at_seconds=started,shortlink_edge_retained_worker_generations=1,
                  shortlink_edge_observation_complete=1)
    lines = [f'{key} {value}' for key,value in fields.items()]
    lines += [f'shortlink_edge_instance_info{{instance="{instance}",boot_id="{boot}"}} 1',
              f'shortlink_edge_worker_pending_count{{worker_id="0",generation="43:abc"}} {pending}',
              f'shortlink_edge_worker_pending_bytes{{worker_id="0",generation="43:abc"}} {pending*100}']
    return response(body=('\n'.join(lines)+'\n').encode())


class ObservationTests(unittest.TestCase):
    def setUp(self):
        self.timer = mock.patch.object(v,'_observation_budget',side_effect=lambda:nullcontext())
        self.timer.start()
        self.addCleanup(self.timer.stop)
        self.now = 0.0
        self.sleeps = []
        self.before = v._metrics(observation())

    def sleep(self,seconds):
        self.sleeps.append(seconds)
        self.now += seconds

    def run_after(self,read):
        return v._observe_after(self.before,read,clock=lambda:self.now,sleep=self.sleep)

    def reads(self,*values):
        get = mock.Mock(side_effect=values)
        return get,lambda:v._metrics(get())

    def test_three_insufficient_observations_then_four_events_pass(self):
        get,read=self.reads(*(observation(n) for n in (100,101,103,104)))
        result=self.run_after(read)
        self.assertEqual(get.call_count,4)
        self.assertEqual(result['metricsReadAttempts'],4)
        self.assertEqual(result['additionalMetricsReads'],3)
        self.assertEqual(result['attemptedDeltas'],[0,1,3,4])
        self.assertEqual(self.sleeps,[.025]*3)
        self.assertFalse(result['concurrentTrafficPossible'])

    def test_enough_immediately_needs_no_retry(self):
        get,read=self.reads(observation(105))
        result=self.run_after(read)
        self.assertEqual(get.call_count,1)
        self.assertEqual(result['metricsReadAttempts'],1)
        self.assertEqual(result['additionalMetricsReads'],0)
        self.assertTrue(result['concurrentTrafficPossible'])
        self.assertEqual(self.sleeps,[])

    def test_four_reads_never_enough_remains_failure_and_never_fifth(self):
        get,read=self.reads(*(observation(n) for n in (100,101,102,103,104)))
        with self.assertRaises(v._AfterObservationError) as error:
            self.run_after(read)
        self.assertEqual(get.call_count,4)
        self.assertEqual(error.exception.evidence['attemptedDeltas'],[0,1,2,3])
        self.assertEqual(error.exception.evidence['metricsReadAttempts'],4)
        self.assertEqual(len(self.sleeps),3)

    def test_boot_instance_and_start_change_fail_without_retry(self):
        for changed in (dict(boot='boot-b'),dict(instance='other'),dict(started=1001)):
            get,read=self.reads(observation(101),observation(102,**changed),observation(104))
            with self.subTest(changed=changed),self.assertRaises(v._AfterObservationError) as error:
                self.run_after(read)
            self.assertEqual(get.call_count,2)
            self.assertIn('boot changed',str(error.exception))

    def test_attempted_regression_between_retries_is_not_hidden_by_baseline(self):
        get,read=self.reads(observation(102),observation(101),observation(104))
        with self.assertRaises(v._AfterObservationError) as error:
            self.run_after(read)
        self.assertEqual(get.call_count,2)
        self.assertIn('regressed',str(error.exception))

    def test_negative_delta_against_before_fails_immediately(self):
        get,read=self.reads(observation(99),observation(104))
        with self.assertRaises(v._AfterObservationError) as error:
            self.run_after(read)
        self.assertEqual(get.call_count,1)
        self.assertEqual(error.exception.evidence['attemptedDeltas'],[])

    def test_delivered_regression_fails_even_when_attempted_increases(self):
        get,read=self.reads(observation(101,delivered=99,pending=2),observation(104))
        with self.assertRaises(v._AfterObservationError):
            self.run_after(read)
        self.assertEqual(get.call_count,1)

    def test_structure_and_conservation_errors_are_never_retried(self):
        invalids=[response(body=b'not metrics'),observation(101,delivered=100),observation(-1)]
        for invalid in invalids:
            get,read=self.reads(invalid,observation(104))
            with self.subTest(invalid=invalid['body'][:80]),self.assertRaises(v._AfterObservationError):
                self.run_after(read)
            self.assertEqual(get.call_count,1)

    def test_transport_errors_fail_once(self):
        for error in (OSError('private transport text'),http.client.HTTPException('private response text')):
            read=mock.Mock(side_effect=error)
            with self.subTest(error=type(error)),self.assertRaises(v._AfterObservationError) as caught:
                self.run_after(read)
            self.assertEqual(read.call_count,1)
            self.assertEqual(str(caught.exception),type(error).__name__)
            self.assertEqual(caught.exception.evidence['metricsReadAttempts'],1)

    def test_deadline_rejects_late_success_and_blocks_further_reads(self):
        def read():
            self.now += .6
            return v._metrics(observation(104 if self.now>1 else 101))
        get=mock.Mock(side_effect=read)
        with self.assertRaises(v._AfterObservationError) as error:
            self.run_after(get)
        self.assertEqual(get.call_count,2)
        self.assertIn('deadline',str(error.exception))

    def test_does_not_sleep_if_interval_cannot_fit_remaining_budget(self):
        def read():
            self.now=.98
            return v._metrics(observation(101))
        get=mock.Mock(side_effect=read)
        with self.assertRaises(v._AfterObservationError):
            self.run_after(get)
        self.assertEqual(get.call_count,1)
        self.assertEqual(self.sleeps,[])

    def test_missing_before_never_reads_and_case_reports_attempts_on_failure(self):
        read=mock.Mock()
        rows=[]
        v._case(rows,'EP12_metrics_after',lambda:v._observe_after(None,read))
        self.assertFalse(rows[0]['passed'])
        self.assertEqual(rows[0]['details']['metricsReadAttempts'],0)
        read.assert_not_called()

    def test_all_ten_namespace_cases_remain_and_only_metrics_are_retried(self):
        context=dict(apisixPid=42,apisixIp='172.18.0.5',networkGateway='172.18.0.1',
            managementHost='m.perf.test',redirectHost='s.perf.test',shortUri='abc123456',expectedLocation='https://target.invalid/')
        metrics=iter(observation(n) for n in (100,100,101,103,104))
        def http(host,port,path,headers,method='GET',expected_source=None):
            if port==9099:return next(metrics)
            if '/admin/' in path:return response(401)
            if '/internal/' in path:return response(404)
            if port==8003 and headers.get('X-Forwarded-For')=='invalid-ip':return response(400)
            fields=[('Location',context['expectedLocation'])]
            if method!='HEAD':
                cookie='sl_uv='+'a'*32+'; Path=/'
                if port==8003 and headers.get('X-Forwarded-Proto')=='https':cookie+='; Secure'
                fields.append(('Set-Cookie',cookie))
            return response(302,headers=fields)
        with mock.patch.object(v.os,'stat',return_value=SimpleNamespace(st_ino=1)), \
             mock.patch.object(v,'_http',side_effect=http) as network, \
             mock.patch.object(v.time,'sleep'):
            rows=v._inside(context)
        self.assertEqual(len(rows),10)
        self.assertTrue(all(x['passed'] for x in rows),rows)
        self.assertEqual(network.call_count,13)  # Two parent probes bring the maximum to 15.
        self.assertEqual(sum(call.args[1]==9099 for call in network.call_args_list),5)
        self.assertEqual(rows[-1]['details']['metricsReadAttempts'],4)


class TimerAndSuiteTests(unittest.TestCase):
    def test_deadline_is_disarmed_and_handler_restored_even_after_timeout(self):
        previous=object()
        with mock.patch.object(v.signal,'getitimer',return_value=(0.,0.)), \
             mock.patch.object(v.signal,'getsignal',return_value=previous), \
             mock.patch.object(v.signal,'signal') as handler, \
             mock.patch.object(v.signal,'setitimer') as timer:
            with self.assertRaises(TimeoutError):
                with v._observation_budget():
                    expired=handler.call_args_list[0].args[1]
                    expired(v.signal.SIGALRM,None)
        self.assertEqual(timer.call_args_list,[mock.call(v.signal.ITIMER_REAL,1.0),mock.call(v.signal.ITIMER_REAL,0)])
        self.assertEqual(handler.call_args_list[-1],mock.call(v.signal.SIGALRM,previous))

    def test_existing_timer_is_never_overwritten(self):
        with mock.patch.object(v.signal,'getitimer',return_value=(.5,0.)), \
             mock.patch.object(v.signal,'signal') as handler, \
             mock.patch.object(v.signal,'setitimer') as timer:
            with self.assertRaises(ValueError):
                with v._observation_budget():self.fail('must not enter')
        handler.assert_not_called()
        timer.assert_not_called()

    def test_suite_budget_is_fifteen_but_case_count_stays_twelve(self):
        context=dict(runId='run',apisixPid=42,apisixIp='172.18.0.5',shortUri='abc123456',
                     managementHost='m.perf.test',redirectHost='s.perf.test')
        child={'cases':[dict(id=f'EP{i:02d}',passed=True) for i in range(3,13)]}
        with mock.patch.object(v,'_load',return_value=context), \
             mock.patch.object(v,'_http',return_value=response(403)) as network, \
             mock.patch.object(v.subprocess,'run',return_value=SimpleNamespace(returncode=0,stdout=json.dumps(child))):
            result=v.verify(Path('unused'))
        self.assertTrue(result['passed'])
        self.assertEqual(result['maximumHttpRequests'],15)
        self.assertEqual(result['caseCount'],12)
        self.assertEqual(network.call_count,2)


if __name__=='__main__':
    # A missed mock must fail instead of touching any real service or namespace.
    with (mock.patch.object(v.http.client,'HTTPConnection',side_effect=AssertionError('NO_HTTP')),
          mock.patch.object(v.subprocess,'run',side_effect=AssertionError('NO_PROCESS'))):
        unittest.main()
