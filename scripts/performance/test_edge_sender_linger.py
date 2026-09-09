"""Canonical deterministic-clock linger regression; no service or network requests.

Includes the 52 unchanged EM/SC/BC cases against the canonical logger, then LG cases.
--emit-lua produces a self-contained program for an existing LuaJIT interpreter.
--static-only checks generation, not Lua execution. No execution is automatic.
"""
import argparse
import contextlib
import hashlib
import io
import json
from pathlib import Path
import shlex
import subprocess
import sys
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/performance"))
from test_edge_sender_batch import build_program as existing_program

LOGGER = ROOT / "deploy/apisix/plugins/apisix/plugins/shortlink-request-logger.lua"


class SupervisorLingerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import supervisor
        cls.supervisor = supervisor
        cls.manifest = (ROOT / "deploy/apisix/apisix.yaml").read_text(encoding="utf-8-sig")
        cls.config = (ROOT / "deploy/apisix/config.yaml").read_text(encoding="utf-8")

    def test_cli_default_and_existing_values_plus_ten(self):
        self.assertEqual(self.supervisor.parse_args([]).edge_send_linger_millis, 5)
        self.assertEqual(self.manifest.count("        send_linger_ms: 5\n"), 1)
        for millis in (*range(6), 10):
            args = self.supervisor.parse_args(["--edge-send-linger-millis", str(millis)])
            self.assertEqual(args.edge_send_linger_millis, millis)
            self.assertEqual((args.edge_workers, args.edge_send_concurrency), (2, 1))

    def test_invalid_cli_values_never_reach_serve(self):
        with patch.object(self.supervisor, "serve") as serve:
            for value in ("-1", "6", "7", "8", "9", "11", "100", "true", "1.0"):
                with self.subTest(value=value), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    self.supervisor.parse_args(["--edge-send-linger-millis", value])
            serve.assert_not_called()

    def test_programmatic_linger_requires_exact_integer(self):
        for value in (None, True, False, 0.0, 1.0, "0", -1, 6, 7, 8, 9, 11):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.supervisor.render_edge_linger_configuration(self.manifest, value)
        with self.assertRaises(ValueError):
            self.supervisor.render_edge_linger_configuration(None, 0)

    def test_default_preserves_worker_and_sender_manifest_bytes(self):
        for workers in (2, 4, 8):
            for senders in (1, 2, 4):
                manifest, config, profile = self.supervisor.render_edge_worker_configuration(
                    self.manifest, self.config, workers, senders)
                with self.subTest(workers=workers, senders=senders):
                    self.assertEqual(self.supervisor.render_edge_linger_configuration(manifest, 5), manifest)
                    self.assertEqual(manifest.count("send_linger_ms:"), 1)
                    self.assertEqual(profile["edgeSenderSlotsNode"], workers * senders)
                    self.assertEqual(profile["edgeQueueCountNode"], 2000)
                    self.assertEqual(profile["edgeQueueBytesNode"], 16 * 1024 * 1024)

    def test_explicit_values_override_only_one_logger_field_for_every_profile(self):
        for workers in (2, 4, 8):
            for senders in (1, 2, 4):
                manifest, config, profile = self.supervisor.render_edge_worker_configuration(
                    self.manifest, self.config, workers, senders)
                for millis in (*range(6), 10):
                    with self.subTest(workers=workers, senders=senders, millis=millis):
                        actual = self.supervisor.render_edge_linger_configuration(manifest, millis)
                        expected = manifest.replace("        send_linger_ms: 5\n", "        send_linger_ms: %d\n" % millis, 1)
                        self.assertEqual(actual, expected)
                        self.assertEqual(actual.count("send_linger_ms:"), 1)

    def test_crlf_comments_and_timing_option_are_preserved(self):
        source = self.manifest.replace("send_concurrency: 1", "send_concurrency: 1 # keep")
        source = source.replace("send_linger_ms: 5", "send_linger_ms: 5 # target, not hard latency bound")
        for ending in ("\n", "\r\n"):
            manifest = source.replace("\n", ending)
            rendered = self.supervisor.render_edge_linger_configuration(manifest, 0)
            self.assertEqual(rendered, manifest.replace("send_linger_ms: 5 #", "send_linger_ms: 0 #", 1))
            if ending == "\r\n":
                self.assertNotIn("\n", rendered.replace("\r\n", ""))
        rendered = self.supervisor.render_edge_linger_configuration(self.manifest, 5)
        timed = self.supervisor.render_http_timing_configuration(rendered, True)
        self.assertIn("        send_linger_ms: 5\n", timed)
        self.assertIn("        http_timing_enabled: true\n", timed)

    def test_existing_or_ambiguous_linger_source_fails_closed(self):
        header = "      shortlink-request-logger:\n"
        bad = [self.manifest.replace(header, header + "        send_linger_ms: 0\n", 1),
               self.manifest.replace(header, header + "        send_batch_linger_ms: 5\n", 1),
               self.manifest + "\nother: {send_linger_ms: 5}\n",
               self.manifest + "\n" + header,
               self.manifest.replace(header, "      renamed-logger:\n", 1),
               self.manifest.replace("send_linger_ms", "send_batch_linger_ms", 1),
               self.manifest.replace("        send_linger_ms: 5\n", "", 1)
               + "\nother:\n        send_linger_ms: 5\n"]
        for source in bad:
            for millis in (0, 5):
                with self.subTest(millis=millis), self.assertRaises(ValueError):
                    self.supervisor.render_edge_linger_configuration(source, millis)

    def test_legacy_absent_field_keeps_zero_bytes_but_inserts_nonzero(self):
        legacy = self.manifest.replace("        send_linger_ms: 5\n", "", 1)
        for ending in ("\n", "\r\n"):
            source = legacy.replace("\n", ending)
            self.assertEqual(self.supervisor.render_edge_linger_configuration(source, 0), source)
            for millis in (*range(1, 6), 10):
                header = "      shortlink-request-logger:" + ending
                expected = source.replace(header, header + "        send_linger_ms: %d" % millis + ending, 1)
                self.assertEqual(self.supervisor.render_edge_linger_configuration(source, millis), expected)

    def test_all_valid_source_values_are_explicitly_overridden(self):
        for before in (*range(6), 10):
            source = self.manifest.replace("send_linger_ms: 5\n", "send_linger_ms: %d\n" % before, 1)
            for after in (*range(6), 10):
                with self.subTest(before=before, after=after):
                    self.assertEqual(self.supervisor.render_edge_linger_configuration(source, after),
                                     self.manifest.replace("send_linger_ms: 5\n", "send_linger_ms: %d\n" % after, 1))

    def test_invalid_source_types_and_yaml_aliases_are_rejected(self):
        bad_values = ("true", "false", "null", "~", "1.0", "01", "010", "+1", "-1", "6", "7", "8", "9", "11", '"1"', "'1'", "*wait", "&wait 1", "[1]", "{value: 1}")
        bad = [self.manifest.replace("send_linger_ms: 5\n", "send_linger_ms: " + value + "\n", 1)
               for value in bad_values]
        bad += [self.manifest.replace("send_linger_ms: 5", '"send_linger_ms": 1', 1),
                self.manifest.replace("send_linger_ms: 5", "'send_linger_ms': 1", 1),
                self.manifest.replace("        send_linger_ms: 5\n", "          send_linger_ms: 5\n", 1),
                self.manifest.replace("        send_linger_ms: 5\n", "\t        send_linger_ms: 5\n", 1),
                self.manifest.replace("        send_linger_ms: 5\n", "        <<: *settings\n", 1),
                self.manifest + '\nother: {"send_linger_ms": 1}\n']
        for source in bad:
            for millis in (0, 1, 5):
                with self.subTest(source=source[-80:], millis=millis), self.assertRaises(ValueError):
                    self.supervisor.render_edge_linger_configuration(source, millis)

    def test_global_logger_must_be_unique_and_inside_global_block(self):
        header = "      shortlink-request-logger:\n"
        bad = [self.manifest + "\nglobal_rules:\n  - id: duplicate\n",
               self.manifest + '\n"global_rules": []\n',
               self.manifest + '\nother: {"shortlink-request-logger": {}}\n',
               self.manifest.replace(header, '      "shortlink-request-logger":\n', 1),
               "global_rules:\n  - id: unrelated\n" + self.manifest.replace("global_rules:\n", "other_routes:\n", 1),
               self.manifest.replace("    plugins:\n", "    plugins: &settings\n", 1)]
        for source in bad:
            with self.subTest(source=source[-80:]), self.assertRaises(ValueError):
                self.supervisor.render_edge_linger_configuration(source, 0)

    def test_commented_field_names_are_not_live_settings(self):
        source = "# send_linger_ms: 5; global_rules: and shortlink-request-logger: example\n" + self.manifest
        source = source.replace("send_linger_ms: 5\n", "send_linger_ms: 5 # former send_batch_linger_ms: 5\n", 1)
        self.assertEqual(self.supervisor.render_edge_linger_configuration(source, 0),
                         source.replace("send_linger_ms: 5 #", "send_linger_ms: 0 #", 1))

    def test_linger_renderer_is_pure(self):
        with patch.object(Path, "read_text") as read, patch.object(Path, "write_text") as write, \
                patch.object(Path, "mkdir") as mkdir, patch.object(self.supervisor, "command") as command, \
                patch.object(self.supervisor.subprocess, "Popen") as popen:
            self.supervisor.render_edge_linger_configuration(self.manifest, 5)
            for mocked in (read, write, mkdir, command, popen):
                mocked.assert_not_called()

    def test_serve_binds_manifest_and_state_profile_before_any_runtime(self):
        for selected in (None, 0, 1, 5, 10):
            args = self.supervisor.parse_args(["--allow-test-database", "--edge-workers", "8"])
            if selected is None:
                del args.edge_send_linger_millis  # Legacy programmatic caller.
            else:
                args.edge_send_linger_millis = selected
            millis = 5 if selected is None else selected
            def read_source(path, *unused_args, **unused_kwargs):
                return self.manifest if path.name == "apisix.yaml" else self.config
            with patch.object(self.supervisor.sys, "platform", "linux"), \
                    patch.object(Path, "read_text", read_source), \
                    patch.object(Path, "mkdir", side_effect=RuntimeError("OFFLINE_STOP_BEFORE_CREATION")), \
                    patch.object(Path, "write_text") as write, patch.object(self.supervisor, "sql") as sql, \
                    patch.object(self.supervisor, "command") as command, \
                    patch.object(self.supervisor.subprocess, "Popen") as popen, \
                    patch.object(self.supervisor.socket, "socket") as socket:
                try:
                    self.supervisor.serve(args)
                except RuntimeError as error:
                    self.assertEqual(str(error), "OFFLINE_STOP_BEFORE_CREATION")
                    trace = error.__traceback__
                    while trace is not None and trace.tb_frame.f_code.co_name != "serve":
                        trace = trace.tb_next
                    self.assertIsNotNone(trace)
                    values = trace.tb_frame.f_locals
                    self.assertEqual(values["edge_profile"]["edgeSendLingerMillis"], millis)
                    self.assertEqual(values["edge_profile"]["edgeSenderSlotsNode"], 8)
                    self.assertEqual(values["manifest"].count("send_linger_ms:"), 1)
                    self.assertIn("        send_linger_ms: %d\n" % millis, values["manifest"])
                else:
                    self.fail("serve did not reach the mocked pre-creation boundary")
                for mocked in (write, sql, command, popen, socket):
                    mocked.assert_not_called()

    def test_invalid_serve_argument_stops_before_creating_anything(self):
        args = self.supervisor.parse_args(["--allow-test-database"])
        args.edge_send_linger_millis = 11
        with patch.object(self.supervisor.sys, "platform", "linux"), patch.object(Path, "read_text") as read, \
                patch.object(Path, "mkdir") as mkdir, patch.object(Path, "write_text") as write, \
                patch.object(self.supervisor, "command") as command, patch.object(self.supervisor, "sql") as sql, \
                patch.object(self.supervisor.subprocess, "Popen") as popen:
            with self.assertRaisesRegex(ValueError, "EDGE_SEND_LINGER_MILLIS"):
                self.supervisor.serve(args)
            for mocked in (read, mkdir, write, command, sql, popen):
                mocked.assert_not_called()

LINGER_CASES = r'''
-- Only LG cases install the deadline-aware timer. Old cases stay unchanged.
local function linger_begin(concurrency,batch_size,linger,padding)
  local plugin,c=batch_begin(concurrency,batch_size,padding)
  c.send_linger_ms=linger
  -- Keep deterministic sub-millisecond arithmetic away from epoch-double ULPs.
  -- No production clock changes: this is the isolated in-memory fixture only.
  state.now=1000;ngx.shared.shortlink_edge_metrics:safe_set("started_at",state.now)
  state.timer_clock=0;state.timer_history={}
  ngx.timer.at=function(delay,callback,...)
    assert(type(delay)=="number" and delay>=0 and delay<math.huge,"finite nonnegative timer delay")
    state.timer_calls=state.timer_calls+1
    local args={...};args.n=select("#",...)
    local timer={delay=delay,due=state.timer_clock+delay,wall_due=state.now+delay,callback=callback,args=args,
      id=state.worker_id,pid=state.pid,status="scheduled"}
    state.timer_history[#state.timer_history+1]=timer
    if state.fail_timer or state.fail_timer_at==state.timer_calls then
      timer.status="rejected";return nil,"too many pending timers"
    end
    state.timers[#state.timers+1]=timer;return true
  end
  return plugin,c
end
local function advance_ms(milliseconds)
  assert(milliseconds>=0,"test timer clock never runs backwards")
  state.timer_clock=state.timer_clock+milliseconds/1000
  state.now=state.now+milliseconds/1000
end
local function due_index()
  local found
  for index,timer in ipairs(state.timers) do
    if timer.due<=state.timer_clock+1e-9 and (not found or timer.due<state.timers[found].due) then found=index end
  end
  return found
end
local function fire_due()
  local index=due_index();assert(index,"no timer has reached its scheduled deadline")
  local timer=state.timers[index];timer.status="running"
  if not state.preserve_wall_retreat and state.now<timer.wall_due then
    assert(timer.wall_due-state.now<1e-9,"only floating-point roundoff may be corrected")
    state.now=timer.wall_due
  end
  local task=start_timer(index);task.timer=timer
  timer.status=coroutine.status(task.co)=="dead" and "finished" or "running"
  return task
end
local function acknowledge(task,action)
  resume_task(task,action)
  task.timer.status=coroutine.status(task.co)=="dead" and "finished" or "running"
end
local function no_due()
  eq(due_index(),nil,"no send timer may fire before the fixed deadline")
end
local function near(actual,expected)
  assert(math.abs(actual-expected)<1e-6,"deadline changed: "..actual.." vs "..expected)
end
local function rounded_wakeup(deadline)
  local timer=state.timers[1]
  assert(timer.wall_due>=deadline-1e-9,"rounded wakeup must not precede original deadline")
  assert(timer.wall_due<deadline+0.001+1e-9,"rounding adds less than one millisecond")
  assert(math.floor(timer.delay*1000)>=1,"future wakeup must survive integer-ms truncation")
  return timer
end
local function finish_idle_timers()
  local attempts=0
  while #state.timers>0 do
    attempts=attempts+1;assert(attempts<=8,"obsolete wakeup must not rearm forever")
    local due=state.timers[1].due
    for _,timer in ipairs(state.timers) do due=math.min(due,timer.due) end
    if due>state.timer_clock then advance_ms((due-state.timer_clock)*1000) end
    dead(fire_due())
  end
end

case("LG01","bounded schema and absent/zero linger retain immediate batch behavior",function()
  for _,setting in ipairs({{}, {value=0}}) do
    local plugin,c=linger_begin(1,32,setting.value)
    local field=plugin.schema.properties.send_linger_ms
    eq(field.type,"integer");eq(field.minimum,0);eq(field.maximum,10);eq(field.default,0)
    local allowed={};for _,value in ipairs(field.enum) do allowed[value]=true end
    -- This is a schema declaration oracle, not a replacement APISIX validator.
    for _,value in ipairs({-1,0,1,2,3,4,5,6,7,8,9,10,11,0.5}) do
      local in_schema=value%1==0 and value>=field.minimum and value<=field.maximum and allowed[value]==true
      eq(in_schema,value==0 or value==1 or value==2 or value==3 or value==4 or value==5
        or value==10)
    end
    add_events(plugin,c,1);eq(state.timers[1].delay,0)
    local task=fire_due();batch_point(task,1);acknowledge(task,true);dead(task)
    empty_batch_queue(plugin,1)
  end
end)
case("LG02","single-item compatibility path ignores positive batch linger",function()
  local plugin,c=linger_begin(1,1,5);add_events(plugin,c,1)
  eq(state.timers[1].delay,0);local task=fire_due();suspended(task)
  eq(#state.sends,1);eq(#state.batch_calls,0);acknowledge(task,true);dead(task)
  empty_batch_queue(plugin,1)
end)
case("LG03","one wakeup uses the original head deadline and needs no later request",function()
  for _,linger in ipairs({2,10}) do
    local plugin,c=linger_begin(1,32,linger);local original=state.now
    add_events(plugin,c,1);eq(#state.timers,1);near(state.timers[1].delay,linger/1000)
    local m=healthy(plugin);eq(m.shortlink_edge_active_senders,0);eq(m.shortlink_edge_queued_count,1)
    advance_ms(1);add_events(plugin,c,1,2);eq(#state.timers,1);eq(state.timer_calls,1);no_due()
    if linger>2 then advance_ms(linger-2);no_due() end -- 10ms case remains idle through 9ms.
    advance_ms(1);local task=fire_due();local call=batch_point(task,2)
    near(call.attempted[1].created,original);eq(state.timer_calls,1,"wakeup enters sender without another timer")
    eq(healthy(plugin).shortlink_edge_events_delivered,0)
    local writes=state.ledger_writes
    acknowledge(task,true);dead(task);eq(state.ledger_writes,writes+1,"wakeup must not duplicate drain's final publication")
    empty_batch_queue(plugin,2)
  end
end)
case("LG04","count threshold sends before deadline even with one sender",function()
  local plugin,c=linger_begin(1,3,10);add_events(plugin,c,1);no_due()
  add_events(plugin,c,2,2);eq(#state.timers,2)
  local task=fire_due();batch_point(task,3);eq(state.timer_clock,0)
  acknowledge(task,true);dead(task);eq(#state.timers,1,"old wakeup is not cancelled")
  finish_idle_timers();eq(#state.batch_calls,1);empty_batch_queue(plugin,3)
end)
case("LG05","exact complete-item byte threshold sends before the deadline",function()
  local plugin,c=linger_begin(1,128,10,9000);add_events(plugin,c,1)
  local first_size=healthy(plugin).shortlink_edge_pending_bytes
  c.send_batch_bytes=first_size*2
  assert(c.send_batch_bytes>=16384 and c.send_batch_bytes<=1048576)
  add_events(plugin,c,1,2);local task=fire_due();local call=batch_point(task,2)
  eq(call.attempted[1].size+call.attempted[2].size,c.send_batch_bytes)
  acknowledge(task,true);dead(task);finish_idle_timers();empty_batch_queue(plugin,2)
end)
case("LG06","a following item exceeding byte budget seals only the fitting prefix",function()
  local plugin,c=linger_begin(1,128,5,6000);c.send_batch_bytes=16384
  add_events(plugin,c,2);no_due();add_events(plugin,c,1,3)
  local task=fire_due();local call=batch_point(task,2)
  local m=healthy(plugin);eq(m.shortlink_edge_queued_count,1);eq(m.shortlink_edge_inflight_count,2)
  assert(call.attempted[1].size+call.attempted[2].size<=16384)
  acknowledge(task,true);dead(task);eq(healthy(plugin).shortlink_edge_events_delivered,2)
  advance_ms(5);task=fire_due();batch_point(task,1);acknowledge(task,true);dead(task)
  empty_batch_queue(plugin,3)
end)
case("LG07","configuration boundary sends its closed prefix without mixing equal-valued configs",function()
  local plugin,c=linger_begin(1,32,5);local other=copy(c)
  add_events(plugin,c,2);no_due();add_events(plugin,other,1,3)
  local task=fire_due();local call=batch_point(task,2)
  for _,item in ipairs(call.received_items) do eq(item.conf,c) end
  acknowledge(task,true);dead(task);eq(healthy(plugin).shortlink_edge_queued_count,1)
  advance_ms(5);task=fire_due();call=batch_point(task,1);eq(call.received_items[1].conf,other)
  acknowledge(task,true);dead(task);empty_batch_queue(plugin,3)
end)
case("LG08","ACK of a busy sender yields a young tail and publishes the prior terminal",function()
  local plugin,c=linger_begin(1,2,2);add_events(plugin,c,2)
  local task=fire_due();batch_point(task,2)
  advance_ms(0.5);add_events(plugin,c,1,3);local tail_created=state.now
  advance_ms(0.5);acknowledge(task,true);dead(task)
  local record=shared_worker_record();eq(record.delivered,2);eq(record.pending_count,1)
  eq(record.queued_count,1);eq(record.inflight_count,0);eq(record.active_senders,0)
  no_due();advance_ms(1);dead(fire_due()) -- Old head's wakeup is earlier than the new head's deadline.
  eq(#state.timers,1);local timer=rounded_wakeup(tail_created+0.002);no_due()
  advance_ms(0.5);no_due() -- The original fractional deadline is not a zero-ms wakeup.
  advance_ms((timer.due-state.timer_clock)*1000);task=fire_due();local call=batch_point(task,1)
  near(call.attempted[1].created,tail_created);acknowledge(task,true);dead(task)
  empty_batch_queue(plugin,3)
end)
case("LG09","a timer firing after wall-clock retreat retains the original head deadline",function()
  local plugin,c=linger_begin(1,32,2);add_events(plugin,c,1)
  advance_ms(2);state.now=state.now-0.001;state.preserve_wall_retreat=true
  -- Timer clock is monotonic; ngx.now moved back independently.
  dead(fire_due());eq(#state.timers,1);near(state.timers[1].delay,0.001)
  state.preserve_wall_retreat=false
  advance_ms(1);local task=fire_due();batch_point(task,1)
  acknowledge(task,true);dead(task);empty_batch_queue(plugin,1)
end)
case("LG10","initial wakeup failure rejects only the newly admitted item without a reservation leak",function()
  local plugin,c=linger_begin(1,32,2);state.fail_timer=true;add_events(plugin,c,1)
  local m=healthy(plugin);eq(reason(m,"timer_unavailable"),1);eq(m.shortlink_edge_timer_failures,1)
  eq(m.shortlink_edge_pending_count,0);eq(m.shortlink_edge_pending_bytes,0);eq(#state.timers,0)
  state.fail_timer=false;add_events(plugin,c,1,2);advance_ms(2)
  local task=fire_due();batch_point(task,1);acknowledge(task,true);dead(task)
  m=empty_batch_queue(plugin,1);eq(m.shortlink_edge_events_rejected,1)
end)
case("LG11","a ready wakeup starts sending even when creating any further timer is forbidden",function()
  local plugin,c=linger_begin(1,32,2);add_events(plugin,c,1);state.fail_timer=true
  advance_ms(2);local task=fire_due();batch_point(task,1);eq(state.timer_calls,1)
  acknowledge(task,true);dead(task);local m=empty_batch_queue(plugin,1);eq(m.shortlink_edge_timer_failures,0)
end)
case("LG12","failed rearm uses the current timer once and does not strand a young new head",function()
  local plugin,c=linger_begin(1,2,10);add_events(plugin,c,2)
  local task=fire_due();batch_point(task,2);advance_ms(0.5);add_events(plugin,c,1,3)
  acknowledge(task,true);dead(task);state.fail_timer=true;advance_ms(9.5)
  task=fire_due();batch_point(task,1)
  eq(healthy(plugin).shortlink_edge_timer_failures,1);eq(state.timer_calls,3)
  acknowledge(task,true);dead(task);local m=empty_batch_queue(plugin,3)
  eq(m.shortlink_edge_events_rejected,0)
end)
case("LG13","ACK path with no armed timer shortens only linger if arming fails",function()
  local plugin,c=linger_begin(1,32,2);add_events(plugin,c,1);advance_ms(2)
  local task=fire_due();batch_point(task,1);advance_ms(0.5);add_events(plugin,c,1,2)
  eq(#state.timers,0);state.fail_timer=true;acknowledge(task,true);batch_point(task,1)
  local record=shared_worker_record();eq(record.delivered,1);eq(record.pending_count,1);eq(record.inflight_count,1)
  acknowledge(task,true);dead(task);local m=empty_batch_queue(plugin,2)
  eq(m.shortlink_edge_timer_failures,1);eq(m.shortlink_edge_events_rejected,0)
end)
case("LG14","failed immediate sender timer preserves a full batch owned by the old wakeup",function()
  local plugin,c=linger_begin(1,2,5);add_events(plugin,c,1);state.fail_timer_at=2
  add_events(plugin,c,1,2);local m=healthy(plugin)
  eq(m.shortlink_edge_active_senders,0);eq(m.shortlink_edge_pending_count,2)
  eq(m.shortlink_edge_events_rejected,0);eq(m.shortlink_edge_timer_failures,1)
  advance_ms(5);local task=fire_due();batch_point(task,2)
  acknowledge(task,true);dead(task);empty_batch_queue(plugin,2)
end)
case("LG15","wakeup finding full senders cannot exceed concurrency or create an empty timer loop",function()
  local plugin,c=linger_begin(2,2,5);add_events(plugin,c,4)
  local a,b=fire_due(),fire_due();batch_point(a,2);batch_point(b,2)
  advance_ms(1);add_events(plugin,c,1,5);advance_ms(5);dead(fire_due())
  eq(#state.timers,0);eq(state.timer_calls,3);eq(healthy(plugin).shortlink_edge_active_senders,2)
  acknowledge(a,true);batch_point(a,1);eq(healthy(plugin).shortlink_edge_active_senders,2)
  acknowledge(b,true);dead(b);acknowledge(a,true);dead(a);empty_batch_queue(plugin,5)
end)
case("LG16","partial ACK and adapter exception never wait again or replay the confirmed prefix",function()
  local plugin,c=linger_begin(1,32,10);add_events(plugin,c,3);advance_ms(10)
  local task=fire_due();local first=batch_point(task,3)
  local occupied=healthy(plugin).shortlink_edge_pending_bytes
  acknowledge(task,{ack={1},throw=true});local retry=batch_point(task,2)
  eq(retry.received_items,first.received_items);eq(retry.received_items[1].acked,true)
  snapshot_equal(retry.attempted[1],first.attempted[2]);eq(state.timer_calls,1)
  eq(healthy(plugin).shortlink_edge_pending_bytes,occupied)
  acknowledge(task,true);dead(task);empty_batch_queue(plugin,3)
end)
case("LG17","a late wakeup and partial-ACK age expiry preserve the original thirty-second contract",function()
  local plugin,c=linger_begin(1,32,10);add_events(plugin,c,2);advance_ms(30001)
  dead(fire_due());eq(#state.batch_calls,0);empty_batch_queue(plugin,0,2)
  plugin,c=linger_begin(1,32,10);add_events(plugin,c,2);advance_ms(10)
  local task=fire_due();batch_point(task,2);advance_ms(30000)
  acknowledge(task,{ack={1}});dead(task);eq(#state.batch_calls,1);empty_batch_queue(plugin,1,1)
end)
case("LG18","premature or exiting wakeups retain queued bytes in their original worker generation",function()
  for _,mode in ipairs({"premature","exiting"}) do
    local plugin,c=linger_begin(1,32,10);add_events(plugin,c,2)
    local original_bytes=healthy(plugin).shortlink_edge_pending_bytes
    local old=state.timers[1];advance_ms(10)
    if mode=="exiting" then state.exiting=true;dead(fire_due())
    else old.status="running";local task=start_timer(1,true);dead(task);old.status="finished" end
    local m=healthy(plugin);eq(m.shortlink_edge_pending_count,2);eq(m.shortlink_edge_pending_bytes,original_bytes)
    eq(m.shortlink_edge_queued_count,2);eq(m.shortlink_edge_active_senders,0);eq(#state.batch_calls,0)
    state.exiting=false;local fresh=worker(0,101);local fresh_conf=copy(c);fresh_conf.send_linger_ms=0
    add_events(fresh,fresh_conf,1,3);local task=fire_due();batch_point(task,1)
    acknowledge(task,true);dead(task);m=healthy(fresh)
    eq(m.shortlink_edge_retained_worker_generations,2);eq(m.shortlink_edge_events_delivered,1)
    eq(m.shortlink_edge_pending_count,2);eq(m.shortlink_edge_pending_bytes,original_bytes)
  end
end)
case("LG19","concurrency reduction keeps old ownership and waits only for the new head deadline",function()
  local plugin,c=linger_begin(2,2,5);add_events(plugin,c,4)
  local a,b=fire_due(),fire_due();batch_point(a,2);batch_point(b,2)
  advance_ms(0.5);local lower=copy(c);lower.send_concurrency=1;add_events(plugin,lower,1,5)
  acknowledge(a,true);dead(a);eq(healthy(plugin).shortlink_edge_active_senders,1)
  acknowledge(b,true);dead(b);eq(healthy(plugin).shortlink_edge_active_senders,0)
  advance_ms(4.5);dead(fire_due());eq(#state.timers,1)
  local timer=rounded_wakeup(1000.0055);advance_ms(0.5);no_due()
  advance_ms((timer.due-state.timer_clock)*1000)
  local task=fire_due();local call=batch_point(task,1);eq(call.received_items[1].conf,lower)
  eq(healthy(plugin).shortlink_edge_active_senders,1)
  acknowledge(task,true);dead(task);empty_batch_queue(plugin,5)
end)
case("LG20","waiting wakeup occupies original count and byte budgets but no sender",function()
  for _,limit in ipairs({"count","bytes"}) do
    local plugin,c=linger_begin(1,32,10)
    if limit=="count" then c.queue_count=2 end
    add_events(plugin,c,2);local before=healthy(plugin)
    if limit=="bytes" then c.queue_bytes=before.shortlink_edge_pending_bytes end
    add_events(plugin,c,1,3);local m=healthy(plugin)
    eq(reason(m,"queue_"..limit),1);eq(m.shortlink_edge_pending_count,2)
    eq(m.shortlink_edge_pending_bytes,before.shortlink_edge_pending_bytes)
    eq(m.shortlink_edge_active_senders,0);eq(#state.timers,1)
    advance_ms(10);local task=fire_due();batch_point(task,2)
    acknowledge(task,true);dead(task);m=empty_batch_queue(plugin,2);eq(m.shortlink_edge_events_rejected,1)
  end
end)
case("LG21","one pending wakeup can coexist with an actual sender but never duplicates itself",function()
  local plugin,c=linger_begin(2,32,2);add_events(plugin,c,1);advance_ms(2)
  local first=fire_due();batch_point(first,1);advance_ms(0.5);add_events(plugin,c,4,2)
  eq(#state.timers,1);eq(state.timer_calls,2);eq(healthy(plugin).shortlink_edge_active_senders,1)
  acknowledge(first,true);dead(first);eq(#state.timers,1)
  advance_ms(2);local task=fire_due();batch_point(task,4)
  acknowledge(task,true);dead(task);empty_batch_queue(plugin,5)
end)
case("LG22","an obsolete wakeup token cannot clear the next token or settle items twice",function()
  local plugin,c=linger_begin(1,32,2);add_events(plugin,c,1);local old=state.timers[1]
  advance_ms(2);local first=fire_due();batch_point(first,1);acknowledge(first,true);dead(first)
  add_events(plugin,c,1,2);local current=state.timers[1]
  old.callback(false,unpack(old.args,1,old.args.n)) -- Defensive duplicate callback, no time advancement.
  eq(state.timers[1],current);eq(#state.timers,1);eq(healthy(plugin).shortlink_edge_pending_count,1)
  advance_ms(2);local task=fire_due();batch_point(task,1);acknowledge(task,true);dead(task)
  empty_batch_queue(plugin,2)
end)
case("LG23","drain error preserves partial confirmation and leaves a young tail owned by its wakeup",function()
  local plugin,c=linger_begin(1,2,2);add_events(plugin,c,3)
  local first=fire_due();batch_point(first,2)
  acknowledge(first,{ack={1},throw_stats_after=true});dead(first)
  local m=healthy(plugin);eq(m.shortlink_edge_events_delivered,1);eq(m.shortlink_edge_events_failed,1)
  eq(m.shortlink_edge_pending_count,1);eq(m.shortlink_edge_queued_count,1)
  eq(m.shortlink_edge_active_senders,0);eq(m.shortlink_edge_drain_errors,1)
  no_due();advance_ms(2);local task=fire_due();batch_point(task,1)
  acknowledge(task,true);dead(task);empty_batch_queue(plugin,2,1)
end)
case("LG24","constructor failure at the wakeup settles the batch once without claiming success",function()
  for _,failure in ipairs({"nil","throw"}) do
    local plugin,c=linger_begin(1,32,2);state.batch_constructor_failure=failure
    add_events(plugin,c,2);advance_ms(2);dead(fire_due())
    eq(#state.batch_calls,0);local m=empty_batch_queue(plugin,0,2)
    eq(m.shortlink_edge_producer_failures,1)
  end
end)
case("LG25","shorter hot configuration cannot inherit a later wakeup deadline",function()
  local plugin,c=linger_begin(1,32,10);add_events(plugin,c,1)
  local original_wakeup=state.timers[1]
  advance_ms(1);local shorter=copy(c);shorter.send_linger_ms=1
  add_events(plugin,shorter,1,2);local task=fire_due();local first=batch_point(task,1)
  eq(first.received_items[1].conf,c)
  acknowledge(task,true);local next_batch=batch_point(task,1)
  eq(next_batch.received_items[1].conf,shorter)
  assert(state.now<next_batch.attempted[1].created+0.001,
    "late old token must shorten optional wait, not push the new head beyond its deadline")
  eq(#state.timers,1);eq(state.timers[1],original_wakeup);eq(state.timer_calls,2)
  acknowledge(task,true);dead(task);finish_idle_timers();empty_batch_queue(plugin,2)
end)
case("LG26","longer hot configuration still waits when the retained wakeup is earlier",function()
  local plugin,c=linger_begin(1,32,1);add_events(plugin,c,1)
  advance_ms(0.5);local longer=copy(c);longer.send_linger_ms=10
  add_events(plugin,longer,1,2);local task=fire_due();batch_point(task,1)
  acknowledge(task,true);dead(task);eq(healthy(plugin).shortlink_edge_queued_count,1)
  advance_ms(0.5);dead(fire_due());eq(#state.timers,1);local timer=rounded_wakeup(1000.0105)
  advance_ms(9.5);no_due();advance_ms((timer.due-state.timer_clock)*1000)
  task=fire_due();local call=batch_point(task,1)
  eq(call.received_items[1].conf,longer);acknowledge(task,true);dead(task);empty_batch_queue(plugin,2)
end)
case("LG27","millisecond-truncated timers and cached ngx.now cannot spin on a future deadline",function()
  local plugin,c=linger_begin(1,32,5)
  local raw_timer=ngx.timer.at
  local zero_future=0
  ngx.timer.at=function(delay,callback,...)
    local args={...}
    local actual_delay=math.floor(delay*1000)/1000
    if args[1] and args[1].deadline and args[1].deadline>state.now and actual_delay==0 then
      zero_future=zero_future+1
    end
    return raw_timer(actual_delay,callback,...)
  end
  add_events(plugin,c,1)
  local sent=false
  for iteration=1,8 do -- Hard bound: the buggy implementation must not hang this test.
    local delay=math.max(0,state.timers[1].due-state.timer_clock)
    state.timer_clock=state.timer_clock+delay
    state.now=1000+math.floor(state.timer_clock*1000)/1000
    local task=fire_due()
    if coroutine.status(task.co)~="dead" then
      batch_point(task,1);acknowledge(task,true);dead(task);sent=true;break
    end
  end
  assert(sent,"quantized linger stalled after 8 callbacks; future zero-delay timers="..zero_future)
  eq(zero_future,0,"a future deadline must never become a zero-ms timer")
  empty_batch_queue(plugin,1)
end)
case("LG28","every positive linger rounds up within one millisecond across fractional clock positions",function()
  for _,linger in ipairs({1,2,3,4,5,10}) do
    for _,fraction in ipairs({0,0.25,0.5,0.999}) do
      local plugin,c=linger_begin(1,32,linger)
      advance_ms(fraction);local created=state.now;add_events(plugin,c,1)
      local timer=rounded_wakeup(created+linger/1000)
      no_due();advance_ms((timer.due-state.timer_clock)*1000)
      local task=fire_due();local call=batch_point(task,1)
      assert(state.now>=created+linger/1000-1e-9,"no send before original deadline")
      near(call.attempted[1].created,created)
      acknowledge(task,true);dead(task);empty_batch_queue(plugin,1)
    end
  end
end)
'''


def program(source):
    base = existing_program(source)
    marker = "local failures=0\n"
    assert base.count(marker) == 1
    return base.replace(marker, LINGER_CASES + "\n" + marker)


def decode_output(raw):
    # WSL can prepend a UTF-16LE proxy warning to otherwise UTF-8 tool output.
    # Preserve that diagnostic without letting it hide the actual Lua result.
    if raw.startswith(b"w\x00s\x00l\x00:"):
        end = raw.find(b"\n\x00")
        if end >= 0:
            return raw[:end + 2].decode("utf-16-le", errors="replace") + decode_output(raw[end + 2:])
    return raw.decode("utf-8", errors="replace")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--emit-lua", action="store_true")
    modes.add_argument("--static-only", action="store_true")
    modes.add_argument("--lua-command", help="Existing LuaJIT command; no shell, installation or service start")
    modes.add_argument("--supervisor-tests", action="store_true", help="Run offline CLI/render/wiring tests only")
    args = parser.parse_args()
    if args.supervisor_tests:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(SupervisorLingerTests)
        result = unittest.TextTestRunner(verbosity=2).run(suite)
        return 0 if result.wasSuccessful() else 1
    source = LOGGER.read_text(encoding="utf-8")
    rendered = program(source)
    new_count = LINGER_CASES.count('case("LG')
    assert new_count == 28
    info = {"suite": "edge_sender_linger", "existing_cases": 52, "new_cases": new_count,
            "case_count": 52 + new_count, "source_sha256": hashlib.sha256(LOGGER.read_bytes()).hexdigest()}
    if args.emit_lua:
        sys.stdout.write(rendered)
        return 0
    if args.static_only:
        print(json.dumps(dict(info, lua_executed=False, generated=True)))
        return 0
    try:
        result = subprocess.run(shlex.split(args.lua_command), input=rendered.encode("utf-8"),
                                capture_output=True, timeout=45, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        print(json.dumps(dict(info, passed=False, lua_executed=False, error_type=type(error).__name__)))
        return 2
    output, stderr = decode_output(result.stdout), decode_output(result.stderr)
    passed = result.returncode == 0 and "CASES %d FAILURES 0" % info["case_count"] in output.splitlines()
    print(json.dumps(dict(info, passed=passed, lua_executed=True,
                         output=output.splitlines(), stderr=stderr, process_exit=result.returncode)))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
