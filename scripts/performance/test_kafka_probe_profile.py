"""Pure dictionaries and mocked script/command readers; no Docker or JVM."""
import copy
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest import mock
import kafka_probe_profile as p

# Fixed upstream launcher fixture; this test never executes its contents.
STOCK = b'''#!/bin/bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#\x20
#    http://www.apache.org/licenses/LICENSE-2.0
#\x20
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

exec $(dirname $0)/kafka-run-class.sh org.apache.kafka.tools.TopicCommand "$@"
'''
GUARD = b'''# Bounded exact local topic-list probe; other Kafka commands keep their original environment.
if [ "$#" -eq 3 ] && [ "$1" = "--bootstrap-server" ] && [ "$2" = "localhost:9092" ] && [ "$3" = "--list" ]; then
  export KAFKA_HEAP_OPTS='-Xms16m -Xmx64m'
  export KAFKA_JVM_PERFORMANCE_OPTS='-XX:ActiveProcessorCount=1 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Djava.awt.headless=true'
fi
'''
BOUNDED = STOCK[:12] + GUARD + STOCK[12:]


def inspect(command=p.COMPOSE_COMMAND):
    return dict(Id='a'*64,Image='sha256:'+'b'*64,
        State=dict(Running=True,Paused=False,Restarting=False,Pid=1978,
                   StartedAt='2026-09-09T10:00:00.000000000Z',Health=dict(Status='healthy')),
        Config=dict(Image='apache/kafka:3.9.0',Env=['KAFKA_HEAP_OPTS=-Xms256m -Xmx512m','APP_SECRET=not-for-output'],
            Healthcheck=dict(Test=['CMD-SHELL',command],Interval=10_000_000_000,
                             Timeout=10_000_000_000,Retries=20)))


class ProfileTests(unittest.TestCase):
    def test_fixed_fixture_hashes_match_reviewed_original_and_guard(self):
        self.assertEqual(p.sha(STOCK),p.STOCK_SCRIPT_SHA256)
        self.assertEqual(p.sha(BOUNDED),p.GUARDED_SCRIPT_SHA256)

    def test_exact_compose_profile_accepts_original_and_guard(self):
        for script in (STOCK,BOUNDED):
            reader=mock.Mock(return_value=script)
            result=p.validate_profile(inspect(),reader)
            self.assertEqual(result['profile'],'COMPOSE_INLINE_BOUNDED_JVM_V1')
            reader.assert_called_once_with('a'*64,p.TOPICS_SCRIPT,p.SCRIPT_LIMIT)
            self.assertNotIn('not-for-output',json.dumps(result))
            self.assertNotIn('Env',result)

    def test_legacy_requires_actual_guard_not_improved_compose_or_receipt(self):
        current=inspect(p.LEGACY_COMMAND)
        with self.assertRaisesRegex(p.ProbeProfileError,'SCRIPT_PROFILE_UNRECOGNIZED'):
            p.validate_profile(current,lambda *args:STOCK)
        self.assertEqual(p.validate_profile(current,lambda *args:BOUNDED)['profile'],
                         'LEGACY_LIST_WITH_AUDITED_GUARD_V1')

    def test_tampering_either_script_fails(self):
        for command in (p.COMPOSE_COMMAND,p.LEGACY_COMMAND):
            for raw in (BOUNDED+b'\n',BOUNDED.replace(b'Xmx64m',b'Xmx640m')):
                with self.subTest(command=command==p.COMPOSE_COMMAND),self.assertRaises(p.ProbeProfileError):
                    p.validate_profile(inspect(command),lambda *args:raw)

    def test_unknown_secret_command_is_neither_read_nor_echoed(self):
        secret='super-secret-should-not-appear'
        for command in ('echo '+secret,p.COMPOSE_COMMAND+'; echo '+secret,p.COMPOSE_COMMAND+' '):
            reader=mock.Mock()
            with self.assertRaises(p.ProbeProfileError) as error:
                p.validate_profile(inspect(command),reader)
            self.assertEqual(str(error.exception),'KAFKA_PROBE_COMMAND_UNRECOGNIZED')
            self.assertNotIn(secret,str(error.exception))
            reader.assert_not_called()

    def test_nonrunning_restarting_paused_unhealthy_and_bad_pid_fail(self):
        for key,value in (('Running',False),('Paused',True),('Restarting',True),('Pid',0),('Pid',True),('Health',{'Status':'unhealthy'})):
            data=inspect();data['State'][key]=value
            with self.subTest(key=key),self.assertRaises(p.ProbeProfileError):
                p.validate_profile(data,lambda *args:self.fail('no script read'))

    def test_jvm_override_channels_fail_without_exposing_values(self):
        for key in p.OPTION_INJECTION_KEYS:
            data=inspect();data['Config']['Env'].append(key+'=-Xmx8g private-password')
            with self.assertRaises(p.ProbeProfileError) as error:
                p.validate_profile(data,lambda *args:self.fail('no script read'))
            self.assertEqual(str(error.exception),'KAFKA_PROBE_JVM_OVERRIDE_CHANNEL')

    def test_unknown_or_unbounded_health_timing_fails(self):
        for key,value in (('Interval',0),('Interval',1),('Timeout',0),('Timeout',20_000_000_000),
                          ('Retries',0),('StartInterval',1),('StartPeriod',1)):
            data=inspect();data['Config']['Healthcheck'][key]=value
            with self.subTest(key=key),self.assertRaises(p.ProbeProfileError):
                p.validate_profile(data,lambda *args:self.fail('no script read'))

    def test_reader_exception_or_oversize_never_echoes_payload(self):
        reader=mock.Mock(side_effect=RuntimeError('secret payload'))
        with self.assertRaises(p.ProbeProfileError) as error:
            p.validate_profile(inspect(),reader)
        self.assertEqual(str(error.exception),'KAFKA_PROBE_SCRIPT_READ_FAILED')
        for raw in (b'',b'x'*(p.SCRIPT_LIMIT+1),'not bytes'):
            with self.assertRaises(p.ProbeProfileError):p.validate_profile(inspect(),lambda *args:raw)

    def test_container_binding_is_required(self):
        with self.assertRaisesRegex(p.ProbeProfileError,'CONTAINER_CHANGED'):
            p.validate_profile(inspect(),lambda *args:BOUNDED,expected_container_id='c'*64)

    def test_invalid_environment_unicode_is_sanitized_without_script_read(self):
        data=inspect();data['Config']['Env'].append('PRIVATE=secret\ud800')
        reader=mock.Mock()
        with self.assertRaises(p.ProbeProfileError) as error:p.validate_profile(data,reader)
        self.assertEqual(str(error.exception),'KAFKA_PROBE_ENV_SCHEMA')
        reader.assert_not_called()


class AdapterTests(unittest.TestCase):
    def run_mock(self,after=None):
        before=inspect(p.LEGACY_COMMAND)
        responses=[json.dumps([before]).encode(),BOUNDED,json.dumps([after or before]).encode(),BOUNDED]
        runner=mock.Mock(side_effect=[SimpleNamespace(returncode=0,stdout=value,stderr=b'private') for value in responses])
        with mock.patch.dict(p.os.environ,{'WSL_DISTRO_NAME':'shortlink-refactor-it'}):
            return p.inspect_actual_profile(runner=runner),runner

    def test_only_fixed_readonly_argv_and_dedicated_socket(self):
        result,runner=self.run_mock()
        self.assertEqual(runner.call_count,4)
        self.assertTrue(result['identityAndScriptStableAcrossTwoReads'])
        for call in runner.call_args_list:
            argv=call.args[0]
            self.assertEqual(argv[:3],['docker','--host',p.DOCKER_SOCKET])
            self.assertIn(argv[3],('inspect','exec'))
            self.assertNotIn('shell',call.kwargs)
            self.assertNotIn(p.LEGACY_COMMAND,argv)
            if argv[3]=='exec':self.assertEqual(argv[4:],['a'*64,'/usr/bin/head','-c','16385',p.TOPICS_SCRIPT])

    def test_container_restart_between_reads_refuses(self):
        after=inspect(p.LEGACY_COMMAND);after['State']['Pid']=9000
        with self.assertRaisesRegex(p.ProbeProfileError,'CHANGED_DURING_READ'):self.run_mock(after)

    def test_secret_command_error_is_sanitized(self):
        runner=mock.Mock(side_effect=RuntimeError('token=secret'))
        with mock.patch.dict(p.os.environ,{'WSL_DISTRO_NAME':'shortlink-refactor-it'}):
            with self.assertRaises(p.ProbeProfileError) as error:p.inspect_actual_profile(runner=runner)
        self.assertEqual(str(error.exception),'KAFKA_PROBE_READ_COMMAND_FAILED')

    def test_wrong_host_never_calls_runner(self):
        runner=mock.Mock()
        with mock.patch.dict(p.os.environ,{'WSL_DISTRO_NAME':'wrong'}):
            with self.assertRaises(p.ProbeProfileError):p.inspect_actual_profile(runner=runner)
        runner.assert_not_called()


if __name__=='__main__':
    with mock.patch.object(p.subprocess,'run',side_effect=AssertionError('NO_DOCKER')):
        unittest.main()
