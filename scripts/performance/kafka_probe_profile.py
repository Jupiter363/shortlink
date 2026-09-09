"""Read-only preflight for the actual isolated Kafka health-probe profile.

Inspect data are untrusted. Never execute, normalize or interpolate Healthcheck
Test text. Unknown profiles fail with fixed, non-secret error codes.
"""
import datetime as dt
import hashlib
import json
import os
import re
import subprocess

DOCKER_SOCKET = 'unix:///var/run/shortlink-docker.sock'
CONTAINER_NAME = 'shortlink-refactor-it-kafka-1'
TOPICS_SCRIPT = '/opt/kafka/bin/kafka-topics.sh'
SCRIPT_LIMIT = 16384
INSPECT_LIMIT = 256 * 1024
STOCK_SCRIPT_SHA256 = 'bec7535636637038a6901f8ff22f49f4514e10debe2d19d752c04ddd792a37cd'
GUARDED_SCRIPT_SHA256 = '11caf6334e107907ccdaf18c177b46ae3c3f763116a0cc9c42f542e3bc6fcbd9'
LEGACY_COMMAND = '/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null'
COMPOSE_COMMAND = ("KAFKA_HEAP_OPTS='-Xms16m -Xmx64m' "
    "KAFKA_JVM_PERFORMANCE_OPTS='-XX:ActiveProcessorCount=1 -XX:+UseSerialGC "
    "-XX:TieredStopAtLevel=1 -Djava.awt.headless=true' " + LEGACY_COMMAND)
OPTION_INJECTION_KEYS = frozenset(('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS',
                                  'JAVA_OPTS', 'KAFKA_OPTS'))
CID = re.compile('[a-f0-9]{64}')
IMAGE = re.compile('sha256:[a-f0-9]{64}')


class ProbeProfileError(ValueError):
    """Messages are fixed codes; never attach raw inspect, script or stderr."""


def require(condition, code):
    if not condition:
        raise ProbeProfileError(code)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _environment(config):
    env = config.get('Env')
    require(type(env) is list and len(env) <= 256, 'KAFKA_PROBE_ENV_SCHEMA')
    total, seen = 0, set()
    for item in env:
        require(type(item) is str and '=' in item and '\x00' not in item, 'KAFKA_PROBE_ENV_SCHEMA')
        try:
            total += len(item.encode('utf-8'))
        except UnicodeError:
            raise ProbeProfileError('KAFKA_PROBE_ENV_SCHEMA') from None
        require(total <= 65536, 'KAFKA_PROBE_ENV_BUDGET')
        key, value = item.split('=',1)
        require(re.fullmatch('[A-Za-z_][A-Za-z0-9_]*',key) and key not in seen, 'KAFKA_PROBE_ENV_SCHEMA')
        seen.add(key)
        # These channels are appended independently of the two explicitly
        # overridden Kafka options; they could replace heap/GC/APC settings.
        require(not (key in OPTION_INJECTION_KEYS and value.strip()), 'KAFKA_PROBE_JVM_OVERRIDE_CHANNEL')


def _identity(inspected):
    require(type(inspected) is dict, 'KAFKA_PROBE_INSPECT_SCHEMA')
    cid, image = inspected.get('Id'), inspected.get('Image')
    state, config = inspected.get('State'), inspected.get('Config')
    require(type(cid) is str and CID.fullmatch(cid), 'KAFKA_PROBE_CONTAINER_ID')
    require(type(image) is str and IMAGE.fullmatch(image), 'KAFKA_PROBE_IMAGE_ID')
    require(type(state) is dict and type(config) is dict, 'KAFKA_PROBE_INSPECT_SCHEMA')
    require(state.get('Running') is True and state.get('Paused') is False
            and state.get('Restarting') is False, 'KAFKA_PROBE_NOT_RUNNING')
    require(type(state.get('Pid')) is int and state['Pid'] > 1, 'KAFKA_PROBE_INIT_PID')
    started = state.get('StartedAt')
    require(type(started) is str and len(started) <= 40, 'KAFKA_PROBE_STARTED_AT')
    try:
        instant = dt.datetime.fromisoformat(started.replace('Z','+00:00'))
        require(instant.tzinfo is not None and instant.year >= 2000, 'KAFKA_PROBE_STARTED_AT')
    except (ValueError,TypeError,OverflowError):
        raise ProbeProfileError('KAFKA_PROBE_STARTED_AT') from None
    require(config.get('Image') == 'apache/kafka:3.9.0', 'KAFKA_PROBE_IMAGE_PROFILE_UNRECOGNIZED')
    health = state.get('Health')
    require(type(health) is dict and health.get('Status') == 'healthy', 'KAFKA_PROBE_NOT_HEALTHY')
    return cid,image,state,config


def validate_profile(inspected, script_reader, *, expected_container_id=None):
    """Pure inspect validation plus a bounded caller-supplied script reader.

    reader(container_id, TOPICS_SCRIPT, SCRIPT_LIMIT) must return bytes read
    from that actual container, never from compose or a local receipt.
    """
    cid,image,state,config = _identity(inspected)
    if expected_container_id is not None:
        require(cid == expected_container_id, 'KAFKA_PROBE_CONTAINER_CHANGED')
    _environment(config)
    health = config.get('Healthcheck')
    require(type(health) is dict, 'KAFKA_PROBE_HEALTHCHECK_MISSING')
    test = health.get('Test')
    require(type(test) is list and len(test) == 2 and all(type(x) is str for x in test),
            'KAFKA_PROBE_COMMAND_UNRECOGNIZED')
    if test == ['CMD-SHELL',COMPOSE_COMMAND]:
        profile = 'COMPOSE_INLINE_BOUNDED_JVM_V1'
        allowed_scripts = {STOCK_SCRIPT_SHA256,GUARDED_SCRIPT_SHA256}
    elif test == ['CMD-SHELL',LEGACY_COMMAND]:
        profile = 'LEGACY_LIST_WITH_AUDITED_GUARD_V1'
        allowed_scripts = {GUARDED_SCRIPT_SHA256}
    else:
        raise ProbeProfileError('KAFKA_PROBE_COMMAND_UNRECOGNIZED')
    # The exact command profiles use a ten-second interval. A shorter per-call
    # timeout remains bounded; no inherited/default zero timeout is accepted.
    interval, timeout, retries = (health.get(k) for k in ('Interval','Timeout','Retries'))
    require(type(interval) is int and interval == 10_000_000_000,
            'KAFKA_PROBE_INTERVAL_UNRECOGNIZED')
    require(type(timeout) is int and 0 < timeout <= 10_000_000_000,
            'KAFKA_PROBE_TIMEOUT_UNBOUNDED')
    require(type(retries) is int and 1 <= retries <= 20, 'KAFKA_PROBE_RETRIES_UNRECOGNIZED')
    start_period, start_interval = health.get('StartPeriod',0), health.get('StartInterval',0)
    require(type(start_period) is int and start_period == 0 and type(start_interval) is int
            and start_interval == 0, 'KAFKA_PROBE_START_TIMING_UNRECOGNIZED')
    try:
        raw = script_reader(cid,TOPICS_SCRIPT,SCRIPT_LIMIT)
    except Exception:
        raise ProbeProfileError('KAFKA_PROBE_SCRIPT_READ_FAILED') from None
    require(type(raw) is bytes and 0 < len(raw) <= SCRIPT_LIMIT, 'KAFKA_PROBE_SCRIPT_BUDGET')
    digest = sha(raw)
    require(digest in allowed_scripts, 'KAFKA_PROBE_SCRIPT_PROFILE_UNRECOGNIZED')
    return dict(kind='KAFKA_HEALTH_PROBE_PROFILE',schemaVersion=1,passed=True,profile=profile,
        containerId=cid,imageId=image,initPid=state['Pid'],containerStartedAt=state['StartedAt'],
        healthStatus='healthy',commandSha256=sha(json.dumps(test,separators=(',',':')).encode()),
        scriptPath=TOPICS_SCRIPT,scriptBytes=len(raw),scriptSha256=digest,
        intervalNanos=interval,timeoutNanos=timeout,retries=retries,
        startupTimingOverride=False,jvmOptionOverrideChannelsChecked=True,
        declaredJvmBounds=dict(initialHeapMiB=16,maximumHeapMiB=64,activeProcessorCount=1,
                               garbageCollector='SerialGC',tieredStopAtLevel=1),
        readOnly=True,configurationChanged=False,
        scope='Actual inspect and actual script bytes; not proof of future immutability or observed JVM CPU/heap usage. No broker/data/offset changes.')


def _decode(raw):
    def pairs(items):
        result = {}
        for key,value in items:
            require(key not in result,'KAFKA_PROBE_INSPECT_JSON_INVALID')
            result[key] = value
        return result
    def constant(value):
        raise ProbeProfileError('KAFKA_PROBE_INSPECT_JSON_INVALID')
    try:
        return json.loads(raw,object_pairs_hook=pairs,parse_constant=constant)
    except Exception:
        raise ProbeProfileError('KAFKA_PROBE_INSPECT_JSON_INVALID') from None


def inspect_actual_profile(container=CONTAINER_NAME, *, runner=None):
    """Read-only integration entry point, explicit dedicated Docker socket.

    No shell, compose application, startup, replacement, removal or Kafka CLI.
    Call only during preflight (the function itself does not schedule work).
    """
    require(os.environ.get('WSL_DISTRO_NAME') == 'shortlink-refactor-it', 'KAFKA_PROBE_WRONG_HOST')
    require(type(container) is str and (container == CONTAINER_NAME or CID.fullmatch(container)),
            'KAFKA_PROBE_CONTAINER_TARGET_UNRECOGNIZED')
    runner = runner or subprocess.run
    def command(args, limit):
        try:
            result = runner(['docker','--host',DOCKER_SOCKET,*args],stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=5,check=False)
        except Exception:
            raise ProbeProfileError('KAFKA_PROBE_READ_COMMAND_FAILED') from None
        require(result.returncode == 0, 'KAFKA_PROBE_READ_COMMAND_FAILED')
        require(type(result.stdout) is bytes and len(result.stdout) <= limit,'KAFKA_PROBE_READ_OUTPUT_BUDGET')
        return result.stdout
    def inspect(target):
        rows = _decode(command(['inspect',target],INSPECT_LIMIT))
        require(type(rows) is list and len(rows) == 1,'KAFKA_PROBE_INSPECT_SCHEMA')
        return rows[0]
    def reader(cid,path,limit):
        # argv and path are fixed by validate_profile; Healthcheck.Test is never used.
        require(path == TOPICS_SCRIPT and limit == SCRIPT_LIMIT,'KAFKA_PROBE_SCRIPT_TARGET')
        return command(['exec',cid,'/usr/bin/head','-c',str(limit+1),TOPICS_SCRIPT],limit)
    before = inspect(container)
    profile = validate_profile(before,reader)
    cid = profile['containerId']
    after = inspect(cid)
    final = validate_profile(after,reader,expected_container_id=cid)
    require(profile == final,'KAFKA_PROBE_CHANGED_DURING_READ')
    # Environment values never enter evidence; compare privately to detect an
    # option/environment mutation during this small observation window.
    require(before['Config']['Env'] == after['Config']['Env'],'KAFKA_PROBE_CHANGED_DURING_READ')
    final.update(identityAndScriptStableAcrossTwoReads=True,inspectionCommands=2,scriptReadCommands=2,
                 observedAt=dt.datetime.now(dt.timezone.utc).isoformat())
    return final
