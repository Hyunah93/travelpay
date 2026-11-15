import json
import os
import signal
import subprocess
import sys
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import measure

from measure import (
    analyze_fault, cpu_delta, fault_problems, fault_timeline, metric_total, missing_samples, parse_cpu_stat,
    parse_k6_requests, percentile, redis_delta, require_current_jar, require_stack, sql_delta, validate_summary,
    written_requests,
)

CPU_STAT = """usage_usec 1000000
user_usec 600000
system_usec 400000
nr_periods 100
nr_throttled 25
throttled_usec 500000
"""


def request(start, name, status, duration=10.0):
    return {"start": start, "end": start + duration, "name": name, "status": status, "durationMs": duration}


class MeasurementTest(unittest.TestCase):
    def test_counts_only_the_requested_endpoint_and_method(self):
        metrics = '''# HELP http_server_requests_seconds_count Requests
http_server_requests_seconds_count{method="POST",uri="/internal/v1/auth/session",status="200"} 12
http_server_requests_seconds_count{method="POST",uri="/internal/v1/auth/session",status="503"} 2
http_server_requests_seconds_count{method="GET",uri="/api/v1/users/me",status="200"} 9
'''
        self.assertEqual(14, metric_total(metrics, "http_server_requests_seconds_count",
                                         {"uri": "/internal/v1/auth/session", "method": "POST"}))
        self.assertEqual(9, metric_total(metrics, "http_server_requests_seconds_count",
                                        {"uri": "/api/v1/users/me", "status": "200"}))

    def test_sql_counts_exclude_fixture_creation_before_the_run(self):
        before = [{"queryid": "1", "query": "select * from users where id=$1",
                   "calls": 40, "total_exec_time": 20}]
        after = [{"queryid": "1", "query": "select * from users where id=$1",
                  "calls": 70, "total_exec_time": 35},
                 {"queryid": "2", "query": "select * from user_credentials where user_id=$1",
                  "calls": 10, "total_exec_time": 3}]
        delta = sql_delta(before, after)
        self.assertEqual([30, 10], [row["calls"] for row in delta])
        self.assertEqual([15, 3], [row["total_exec_time_ms"] for row in delta])

    def test_reset_counters_invalidate_the_sample(self):
        before = [{"queryid": "1", "query": "select 1", "calls": 40, "total_exec_time": 20}]
        after = [{"queryid": "1", "query": "select 1", "calls": 2, "total_exec_time": 1}]
        with self.assertRaisesRegex(ValueError, "초기화"):
            sql_delta(before, after)

    def test_fast_errors_and_wrong_profiles_are_not_successful_measurements(self):
        self.assertTrue(validate_summary({"requests": 100, "successes": 99, "failures": 1}))
        self.assertTrue(validate_summary({"requests": 0, "successes": 0, "failures": 0}))
        self.assertFalse(validate_summary({"requests": 100, "successes": 100, "failures": 0}))

    def test_redis_measurement_excludes_its_own_queries_and_health_checks(self):
        result = redis_delta({"hgetall": 10, "auth": 2, "ping": 4, "info": 1},
                             {"hgetall": 30, "hset": 20, "auth": 3, "ping": 6, "info": 2})
        self.assertEqual({"hgetall": 20, "hset": 20}, result)

    def test_redis_restart_invalidates_the_sample(self):
        with self.assertRaisesRegex(ValueError, "초기화"):
            redis_delta({"hgetall": 30}, {"hgetall": 2})

    def test_rebuilt_jar_requires_a_new_app_container(self):
        with self.assertRaisesRegex(RuntimeError, "재생성"):
            require_current_jar("same-hash", "same-hash", modified_at=20, created_at=10)

    def test_host_and_container_jar_must_match(self):
        with self.assertRaisesRegex(RuntimeError, "다릅니다"):
            require_current_jar("new-host-jar", "old-mounted-jar", modified_at=10, created_at=20)
        require_current_jar("same-hash", "same-hash", modified_at=10, created_at=20)

    def test_cpu_stat_requires_throttling_fields(self):
        self.assertEqual({"usage_usec": 1000000, "nr_periods": 100, "nr_throttled": 25, "throttled_usec": 500000},
                         parse_cpu_stat(CPU_STAT))
        with self.assertRaisesRegex(ValueError, "cpu.stat"):
            parse_cpu_stat("usage_usec 1\nuser_usec 1\n")

    def test_cpu_time_is_divided_by_successful_requests(self):
        before = {"auth-user": parse_cpu_stat(CPU_STAT)}
        after = {"auth-user": {"usage_usec": 3000000, "nr_periods": 300, "nr_throttled": 75, "throttled_usec": 900000}}
        result = cpu_delta(before, after, successes=1000, seconds=2)["auth-user"]
        self.assertEqual(2.0, result["cpuMsPerSuccess"])
        self.assertEqual(1.0, result["averageCores"])
        self.assertEqual(0.25, result["throttledPeriodRatio"])
        self.assertEqual(400.0, result["throttledMs"])

    def test_restarted_container_invalidates_cpu_sample(self):
        before = {"auth-user": parse_cpu_stat(CPU_STAT)}
        after = {"auth-user": {"usage_usec": 10, "nr_periods": 1, "nr_throttled": 0, "throttled_usec": 0}}
        with self.assertRaisesRegex(ValueError, "재시작"):
            cpu_delta(before, after, successes=1, seconds=1)

    def test_scenario_a_runs_without_business_stack_and_b_requires_it(self):
        base = {"auth-gateway", "auth-user", "auth-eureka", "auth-postgres", "auth-redis"}
        business = {"auth-kafka", "auth-ledger", "auth-wallet"}
        require_stack(base, business=False)
        require_stack(base | business, business=True)
        with self.assertRaisesRegex(RuntimeError, "끈 상태"):
            require_stack(base | business, business=False)
        with self.assertRaisesRegex(RuntimeError, "업무 구성이 필요"):
            require_stack(base | {"auth-kafka"}, business=True)
        with self.assertRaisesRegex(RuntimeError, "기본 구성"):
            require_stack(base - {"auth-user"}, business=False)

    def test_k6_csv_keeps_only_request_durations_with_start_time(self):
        lines = ["metric_name,timestamp,metric_value,name,scenario,status,extra_tags,metadata",
                 "http_reqs,1000,1.000000,me,my_info,200,,",
                 "http_req_duration,1000,12.5,me,my_info,200,,",
                 "http_req_duration,2000,5000.0,wallet,wallet_me,503,,"]
        rows = parse_k6_requests(lines)
        self.assertEqual([("me", "200", 987.5), ("wallet", "503", -3000.0)],
                         [(row["name"], row["status"], row["start"]) for row in rows])

    def test_percentile_uses_nearest_rank_at_exact_boundaries(self):
        self.assertEqual(95, percentile(list(range(1, 101)), 0.95))
        self.assertEqual(1, percentile([1], 0.95))
        self.assertIsNone(percentile([], 0.95))

    def test_fault_windows_and_recovery_after_release(self):
        rows = [request(0, "me", "200"), request(500, "me", "200")]
        rows += [request(1000 + i * 100, "me", "503", 5000) for i in range(10)]
        rows += [request(2000, "me", "503")]                       # 해제 직후 첫 초에도 실패
        rows += [request(3000 + i * 1000, "me", "200") for i in range(6)]  # 두 번째 초부터 계속 성공
        report = analyze_fault(rows, fault_start=1000, fault_end=2000)["me"]
        self.assertEqual({"requests": 2, "successes": 2}, {k: report["before"][k] for k in ("requests", "successes")})
        self.assertEqual({"503": 10}, report["during"]["statusCounts"])
        self.assertEqual(5000, report["during"]["maxMs"])
        self.assertEqual(1, report["recoverySecondsAfterRelease"])
        # 실패 응답이 처음 '관측'된 시각입니다. 장애 중 요청은 5초 뒤에 끝나므로, 먼저 끝난 해제 직후 실패(2010ms)가 기준입니다.
        self.assertEqual(1010, report["firstFailureAfterFaultMs"])

    def test_no_recovery_when_failures_continue(self):
        rows = [request(0, "me", "200")] + [request(1000 + i * 1000, "me", "503") for i in range(8)]
        self.assertIsNone(analyze_fault(rows, fault_start=1000, fault_end=2000)["me"]["recoverySecondsAfterRelease"])

    def test_first_failure_observation_includes_requests_in_flight_at_fault_start(self):
        rows = [request(995, "me", "504"), request(1500, "me", "503")]

        report = analyze_fault(rows, fault_start=1000, fault_end=2000)["me"]

        self.assertEqual(5, report["firstFailureAfterFaultMs"])
        self.assertEqual(0, report["failedBeforeFault"])
        self.assertEqual(1, report["before"]["requests"])

    def test_transport_errors_are_counted_apart_from_status_codes(self):
        lines = ["metric_name,timestamp,metric_value,name,scenario,status,error_code,extra_tags,metadata",
                 "http_req_duration,2000,10,me,my_info,200,,,",
                 "http_req_duration,2500,10,me,my_info,200,1000,,",
                 "http_req_duration,2600,10,me,my_info,0,1211,,"]
        rows = parse_k6_requests(lines)
        report = analyze_fault(rows, fault_start=1000, fault_end=3000)["me"]
        # 응답이 200으로 시작했어도 도중에 끊기면 성공이 아닙니다.
        self.assertEqual({"200": 1, "200/전송오류1000": 1, "0/전송오류1211": 1}, report["during"]["statusCounts"])
        self.assertEqual(1, report["during"]["successes"])

    def test_k6_error_code_paired_with_http_status_is_a_plain_http_response(self):
        lines = ["metric_name,timestamp,metric_value,name,scenario,status,error_code,extra_tags,metadata",
                 "http_req_duration,2000,3000,me,my_info,503,1503,,",
                 "http_req_duration,2100,10,me,my_info,401,1401,,"]
        report = analyze_fault(parse_k6_requests(lines), fault_start=-2000, fault_end=3000)["me"]
        self.assertEqual({"401": 1, "503": 1}, report["during"]["statusCounts"])

    def test_timeline_groups_by_offset_from_fault_start(self):
        rows = [request(-4000, "me", "200"), request(1000, "me", "503"), request(4000, "me", "200")]
        timeline = fault_timeline(rows, fault_start=0, step_seconds=5)
        self.assertEqual([(-5, 1, 1), (0, 2, 1)],
                         [(item["offsetSeconds"], item["requests"], item["successes"]) for item in timeline])

    def test_injects_the_fault_only_after_both_apis_answered_normally(self):
        rows = [request(0, "me", "200"), request(1, "wallet", "503")]

        self.assertEqual(["wallet"], missing_samples(rows))
        self.assertEqual([], missing_samples(rows + [request(2, "wallet", "200")]))
        self.assertEqual(["me", "wallet"], missing_samples([]))

    def test_reads_only_the_complete_lines_k6_has_written_so_far(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "requests.csv"
            self.assertEqual([], written_requests(path))
            path.write_text("metric_name,timestamp,metric_value,name,status,error_code\n"
                            "http_req_duration,1000,10,me,200,\n"
                            "http_req_duration,1001,1", encoding="utf-8")

            self.assertEqual([("me", "200", 990.0)],
                             [(row["name"], row["status"], row["start"]) for row in written_requests(path)])

    def test_a_fault_run_is_valid_when_both_apis_have_samples_in_every_window(self):
        report = analyze_fault(fault_rows(), 1000, 2000)

        self.assertEqual([], fault_problems(report, dropped=0, exit_code=0))
        # 회복하지 못한 것은 시스템 결과이지 측정 무효가 아님
        unrecovered = analyze_fault(fault_rows(after="503"), 1000, 2000)
        self.assertEqual([], fault_problems(unrecovered, dropped=0, exit_code=0))

    def test_a_fault_run_is_invalid_when_the_load_or_samples_are_incomplete(self):
        report = analyze_fault(fault_rows(), 1000, 2000)
        self.assertEqual(["k6 종료 코드 99"], fault_problems(report, dropped=0, exit_code=99))
        self.assertEqual(["보내지 못한 요청 24건(정한 부하가 전달되지 않음)"], fault_problems(report, dropped=24, exit_code=0))

        without_wallet = analyze_fault([row for row in fault_rows() if row["name"] == "me"], 1000, 2000)
        self.assertEqual(["wallet: 요청 없음"], fault_problems(without_wallet, dropped=0, exit_code=0))

        nothing_during = analyze_fault([row for row in fault_rows() if not 1000 <= row["start"] < 2000], 1000, 2000)
        self.assertEqual(["me: 표본 없는 구간 ['during']", "wallet: 표본 없는 구간 ['during']"],
                         fault_problems(nothing_during, dropped=0, exit_code=0))

        failing_before = analyze_fault(fault_rows() + [request(10, "me", "500")], 1000, 2000)
        self.assertEqual(["me: 장애 전부터 실패 1건"], fault_problems(failing_before, dropped=0, exit_code=0))

    def test_a_request_in_flight_when_the_fault_starts_may_fail_without_invalidating_the_run(self):
        # 장애 직전에 보내 장애에 걸린 요청(응답은 장애 뒤)은 장애의 영향
        in_flight = analyze_fault(fault_rows() + [request(995, "me", "504", duration=10000)], 1000, 2000)

        self.assertEqual(1, in_flight["me"]["before"]["requests"] - in_flight["me"]["before"]["successes"])
        self.assertEqual([], fault_problems(in_flight, dropped=0, exit_code=0))



class RestoreTest(unittest.TestCase):
    """중단된 장애 시험의 복원. docker·psql 호출은 measure의 command·db를 바꿔 끼워 응답을 정합니다."""

    def abort(self, kind, events, initial, can_start=True, pending_stop=False):
        state = {"status": initial, "pending_stop": pending_stop}

        def fake_command(args, timeout=60):
            action = args[1]
            if action == "inspect":
                return state["status"]
            if action == "unpause":
                state["status"] = "running"
            elif action == "stop":
                state.update(status="exited", pending_stop=False)
            elif action == "start" and can_start:
                state["status"] = "running"
            return ""

        with tempfile.TemporaryDirectory() as directory, mock.patch.object(measure, "command", fake_command), \
                mock.patch.object(measure.time, "sleep"):
            try:
                measure.abort_fault(Path(directory), kind, "target-id", events, KeyboardInterrupt())
                error = None
            except RuntimeError as raised:
                error = raised
            record = json.loads((Path(directory) / "aborted.json").read_text(encoding="utf-8"))
        # 원래 중지 명령이 진행 중이면 뒤늦게 종료됩니다. 복원이 그 종료를 기다렸는지 결과로 확인합니다.
        if state["pending_stop"]:
            state["status"] = "exited"
        return state["status"], record, error

    def test_a_paused_target_is_unpaused_and_recorded(self):
        status, record, error = self.abort("pause", {"faultStart": 1}, "paused")

        self.assertEqual("running", status)
        self.assertTrue(record["restored"])
        self.assertIsNone(error)

    def test_a_stop_interrupted_midway_is_waited_for_before_starting_again(self):
        # 중지 도중이면 아직 실행 중으로 보이므로, 멈출 때까지 기다리지 않으면 시작하지 않고 끝남
        status, record, _ = self.abort("stop", {"faultStart": 1}, "running", pending_stop=True)

        self.assertEqual("running", status)
        self.assertTrue(record["restored"])

    def test_a_target_that_does_not_come_back_fails_loudly_and_is_recorded(self):
        status, record, error = self.abort("pause", {"injectionRequested": 1}, "exited", can_start=False)

        self.assertEqual("exited", status)
        self.assertFalse(record["restored"])
        self.assertIn("target-id", record["restoreError"])
        self.assertIsInstance(error, RuntimeError)

    def test_lock_sessions_that_connect_late_are_terminated_too(self):
        sessions = {"existing-session"}
        late_connection = {"arrived": False}

        def database(query):
            if query.startswith("SELECT pg_terminate_backend"):
                sessions.clear()
                return ""
            return str(len(sessions))

        def next_observation(_seconds):
            if not late_connection["arrived"]:
                sessions.add("late-session")
                late_connection["arrived"] = True

        with mock.patch.object(measure, "db", database), mock.patch.object(measure.time, "sleep", next_observation):
            measure.release_lock_sessions()

        self.assertTrue(late_connection["arrived"])
        self.assertEqual(set(), sessions)

    def test_a_lock_session_that_cannot_be_terminated_is_reported(self):
        with mock.patch.object(measure, "db", lambda query: "1"), mock.patch.object(measure.time, "sleep"):
            self.assertRaises(RuntimeError, measure.release_lock_sessions)

    def test_abort_before_injection_does_not_change_any_target(self):
        with tempfile.TemporaryDirectory() as temporary, \
                mock.patch.object(measure, "command", side_effect=AssertionError("target must not change")), \
                mock.patch.object(measure, "db", side_effect=AssertionError("lock must not change")):
            measure.abort_fault(Path(temporary), "lock-users", "target-id", {}, RuntimeError("not ready"))
            record = json.loads((Path(temporary) / "aborted.json").read_text(encoding="utf-8"))

        self.assertTrue(record["restored"])
        self.assertFalse(record["injectionAttempted"])

    def test_a_pause_that_lands_after_the_target_looked_running_is_undone(self):
        # 신호로 끊긴 pause 명령을 Docker가 마저 처리해, 실행 중으로 두 번 확인한 뒤에 정지가 걸린 경우
        state = {"status": "running", "inspections": 0}

        def docker(args, timeout=60):
            if args[1] == "inspect":
                state["inspections"] += 1
                status = state["status"]
                if state["inspections"] == 2:
                    state["status"] = "paused"
                return status
            if args[1] == "unpause":
                state["status"] = "running"
                return ""
            raise AssertionError(args)

        with mock.patch.object(measure, "command", docker), mock.patch.object(measure.time, "sleep"):
            measure.restore_container("target-id")

        self.assertEqual("running", state["status"])

    def test_a_transient_unpause_failure_is_reconciled_to_running(self):
        state = {"status": "paused", "failed_once": False}

        def docker(args, timeout=60):
            if args[1] == "inspect":
                return state["status"]
            if args[1] == "unpause":
                if not state["failed_once"]:
                    state["failed_once"] = True
                    raise subprocess.CalledProcessError(1, args)
                state["status"] = "running"
                return ""
            raise AssertionError(args)

        with mock.patch.object(measure, "command", docker), mock.patch.object(measure.time, "sleep"):
            measure.restore_container("target-id")

        self.assertEqual("running", state["status"])


class FaultRunTest(unittest.TestCase):
    """실제 fault 분석·파일 저장을 실행하고, Docker·k6 같은 외부 경계만 대체합니다."""

    @staticmethod
    def metrics(start, calls, timeouts=0):
        return (f"process_start_time_seconds {start}\n"
                f'http_server_requests_seconds_count{{uri="/internal/v1/auth/session"}} {calls}\n'
                f"hikaricp_connections_timeout_total {timeouts}\n")

    def run_fault(self, before, after, after_status="503", process_exit=None):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = root / "fault-test"
            directory.mkdir()
            rows = fault_rows(after=after_status)
            lines = ["metric_name,timestamp,metric_value,name,status,error_code"]
            lines += [f"http_req_duration,{row['end']:.0f},{row['durationMs']},{row['name']},{row['status']},"
                      for row in rows]
            (directory / "requests.csv").write_text("\n".join(lines) + "\n", encoding="utf-8")
            (directory / "k6-summary.json").write_text(json.dumps({
                "rate": 1, "duration": "40s", "httpRequests": 6, "droppedIterations": 0, "maxVus": 2,
            }), encoding="utf-8")
            args = SimpleNamespace(type="stop", rate=1, seconds=40, at=3, duration=2, label="test")
            process = mock.Mock()
            process.wait.return_value = process_exit or 0
            process.poll.return_value = process_exit
            self.injected = False

            def inject(kind, target, seconds, events):
                self.injected = True
                events.update(injectionRequested=1000, faultStart=1000, faultEnd=2000)

            replacements = {
                "ROOT": root, "running_services": lambda: set(), "require_stack": lambda *a, **kw: None,
                "require_sessions": lambda *a: None, "container": lambda *a: "test-owned-id",
                "new_result_directory": lambda *a: directory, "environment": lambda *a: {},
                "k6_command": lambda *a, **kw: ["not-executed"], "k6_environment": lambda: {},
                "written_requests": lambda *a: [row for row in rows if row["start"] < 1000], "inject": inject,
            }
            with ExitStack() as stack:
                for name, replacement in replacements.items():
                    stack.enter_context(mock.patch.object(measure, name, replacement))
                stack.enter_context(mock.patch.object(measure.time, "sleep"))
                stack.enter_context(mock.patch.object(measure.subprocess, "Popen", return_value=process))
                stack.enter_context(mock.patch.object(measure.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)))
                stack.enter_context(mock.patch.object(measure, "abort_fault"))
                stack.enter_context(mock.patch.object(measure, "app_metrics", side_effect=[before, after]))
                error = None
                try:
                    measure.fault(args)
                except Exception as raised:
                    error = raised
            result = json.loads((directory / "fault-analysis.json").read_text(encoding="utf-8")) \
                if (directory / "fault-analysis.json").exists() else None
            summary = (directory / "summary.md").read_text(encoding="utf-8") \
                if (directory / "summary.md").exists() else ""
            return result, summary, error

    def test_unrecovered_user_still_produces_analysis_when_metrics_are_unavailable(self):
        report, summary, error = self.run_fault(self.metrics(1000, 100), RuntimeError("metrics unavailable"))

        self.assertIsNone(error, str(error))
        self.assertTrue(report["validity"]["valid"])
        self.assertIn("RuntimeError", report["observationError"])
        self.assertIsNone(report["userObservations"]["i01Requests"])
        self.assertTrue(all(item["recoverySecondsAfterRelease"] is None for item in report["endpoints"].values()))
        self.assertIn("me", summary)
        self.assertIn("wallet", summary)
        self.assertIn("User 후행 지표 조회 실패 (metrics unavailable)", summary)
        self.assertIn("미회복", summary)

    def test_restarted_jvm_counters_are_unavailable_even_if_the_new_value_is_larger(self):
        report, _, error = self.run_fault(self.metrics(1000, 100), self.metrics(2000, 4000))

        self.assertIsNone(error, str(error))
        self.assertIsNone(report["userObservations"]["i01Requests"])
        self.assertIsNone(report["userObservations"]["hikariConnectionTimeouts"])

    def test_same_jvm_counters_keep_their_real_differences(self):
        report, _, error = self.run_fault(self.metrics(1000, 100), self.metrics(1000, 130, 1))

        self.assertIsNone(error, str(error))
        self.assertEqual(30, report["userObservations"]["i01Requests"])
        self.assertEqual(1, report["userObservations"]["hikariConnectionTimeouts"])

    def test_missing_jvm_start_time_does_not_establish_counter_continuity(self):
        report, _, error = self.run_fault('http_server_requests_seconds_count{uri="/internal/v1/auth/session"} 100\n',
                                          self.metrics(1000, 130))

        self.assertIsNone(error, str(error))
        self.assertIsNone(report["userObservations"]["i01Requests"])

    def test_a_counter_that_went_down_in_the_same_jvm_is_unavailable(self):
        report, _, error = self.run_fault(self.metrics(1000, 100), self.metrics(1000, 50))

        self.assertIsNone(error, str(error))
        self.assertIsNone(report["userObservations"]["i01Requests"])

    def test_samples_without_any_jvm_start_time_are_not_treated_as_the_same_jvm(self):
        i01 = 'http_server_requests_seconds_count{uri="/internal/v1/auth/session"} '
        report, _, error = self.run_fault(i01 + "100\n", i01 + "130\n")

        self.assertIsNone(error, str(error))
        self.assertIsNone(report["userObservations"]["i01Requests"])

    def test_missing_post_fault_counter_samples_are_not_reported_as_zero(self):
        report, _, error = self.run_fault(self.metrics(1000, 0), 'process_start_time_seconds 1000\n')

        self.assertIsNone(error, str(error))
        self.assertIsNone(report["userObservations"]["i01Requests"])
        self.assertIsNone(report["userObservations"]["hikariConnectionTimeouts"])

    def test_missing_initial_pool_sample_does_not_assume_a_zero_counter(self):
        before = ('process_start_time_seconds 1000\n'
                  'http_server_requests_seconds_count{uri="/internal/v1/auth/session"} 100\n')
        report, _, error = self.run_fault(before, self.metrics(1000, 130, 2))

        self.assertIsNone(error, str(error))
        self.assertEqual(30, report["userObservations"]["i01Requests"])
        self.assertIsNone(report["userObservations"]["hikariConnectionTimeouts"])

    def test_a_newly_registered_i01_timer_counts_from_zero_in_the_same_jvm(self):
        before = 'process_start_time_seconds 1000\nhikaricp_connections_timeout_total 0\n'
        report, _, error = self.run_fault(before, self.metrics(1000, 25))

        self.assertIsNone(error, str(error))
        self.assertEqual(25, report["userObservations"]["i01Requests"])
        self.assertEqual(0, report["userObservations"]["hikariConnectionTimeouts"])

    def test_an_exited_generator_never_triggers_the_fault(self):
        _, _, error = self.run_fault(self.metrics(1000, 100), self.metrics(1000, 130), process_exit=1)

        self.assertFalse(self.injected)
        self.assertIsInstance(error, RuntimeError)


class TerminationTest(unittest.TestCase):
    @unittest.skipUnless(hasattr(signal, "SIGHUP"), "POSIX 종료 신호 시험")
    def test_a_hangup_ignored_by_nohup_stays_ignored(self):
        # nohup으로 분리 실행한 측정은 터미널을 닫아도 계속 돌아야 함
        originals = {sig: signal.getsignal(sig) for sig in (signal.SIGHUP, signal.SIGTERM)}
        try:
            signal.signal(signal.SIGHUP, signal.SIG_IGN)
            measure.install_termination_handlers()

            self.assertIs(signal.SIG_IGN, signal.getsignal(signal.SIGHUP))
            self.assertIsNot(originals[signal.SIGTERM], signal.getsignal(signal.SIGTERM))
        finally:
            for sig, handler in originals.items():
                signal.signal(sig, handler)

    @unittest.skipUnless(os.name == "posix" and hasattr(signal, "SIGHUP"), "POSIX 종료 신호 시험")
    def test_termination_signals_restore_the_target_when_pause_command_is_interrupted(self):
        program = """from pathlib import Path
from types import SimpleNamespace
import json, sys, time, measure
root = Path(sys.argv[1])
measure.ROOT = root
measure.install_termination_handlers()
measure.running_services = lambda: set()
measure.require_stack = lambda *a, **kw: None
measure.require_sessions = lambda *a: None
measure.container = lambda *a: 'owned-test-id'
measure.new_result_directory = lambda *a: root
measure.environment = lambda *a: {}
measure.app_metrics = lambda *a: 'process_start_time_seconds 1000\\n'
measure.k6_command = lambda *a, **kw: ['not-executed']
measure.k6_environment = lambda: {}
measure.written_requests = lambda *a: [dict(name=n, status='200', errorCode='') for n in ('me', 'wallet')]
class Process:
    def poll(self): return None
    def wait(self, timeout=None): return 0
measure.subprocess.Popen = lambda *a, **kw: Process()
measure.subprocess.run = lambda *a, **kw: None
state = root / 'target-state'
state.write_text('running', encoding='utf-8')
def command(args, timeout=60):
    if args[1] == 'inspect': return state.read_text(encoding='utf-8')
    if args[1] == 'unpause':
        state.write_text('running', encoding='utf-8')
        return ''
    if args[1] == 'pause':
        state.write_text('paused', encoding='utf-8')
        print('paused', flush=True)
        time.sleep(15)
        return ''
    raise AssertionError(args)
measure.command = command
measure.fault(SimpleNamespace(type='pause', label='test', rate=1, seconds=40, at=1, duration=2))
"""
        for sig in (signal.SIGTERM, signal.SIGHUP):
            with self.subTest(signal=sig), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                process = subprocess.Popen([sys.executable, "-u", "-c", program, str(root)],
                                           cwd=Path(measure.__file__).parent, stdout=subprocess.PIPE,
                                           stderr=subprocess.PIPE, text=True)
                try:
                    self.assertEqual("paused", process.stdout.readline().strip())
                    process.send_signal(sig)
                    process.communicate(timeout=5)
                    self.assertEqual(128 + sig, process.returncode)
                    self.assertEqual("running", (root / "target-state").read_text(encoding="utf-8"))
                    record = json.loads((root / "aborted.json").read_text(encoding="utf-8"))
                    self.assertTrue(record["restored"])
                    self.assertTrue(record["injectionAttempted"])
                finally:
                    if process.poll() is None:
                        process.kill()
                    process.communicate(timeout=5)


def fault_rows(after="200"):
    return [request(start, name, "200" if start < 2000 else after)
            for name in ("me", "wallet") for start in (0, 1500, 2500)]


if __name__ == "__main__":
    unittest.main()
