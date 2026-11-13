import unittest

from measure import (
    analyze_fault, cpu_delta, fault_timeline, metric_total, parse_cpu_stat, parse_k6_requests, percentile,
    redis_delta, require_current_jar, require_stack, sql_delta, validate_summary,
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


if __name__ == "__main__":
    unittest.main()
