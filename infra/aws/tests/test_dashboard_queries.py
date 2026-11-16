"""선택 실행: 운영 Prometheus의 promtool로 실제 대시보드 PromQL을 검산합니다. 외부망·기존 앱은 사용하지 않습니다."""

import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).parents[1]
DASHBOARD = ROOT.parent / "monitoring/grafana/dashboards/travelpay-local.json"
PROMETHEUS_IMAGE = re.search(r'^\s+prometheus\s+= "([^"]+@sha256:[0-9a-f]{64})"$',
                             (ROOT / "monitoring.tf").read_text(), re.M).group(1)


@unittest.skipUnless(os.environ.get("TRAVELPAY_TEST_DOCKER_MONITORING") == "1", "PromQL 실행 검사는 명시적으로 실행합니다.")
class DashboardQueryTest(unittest.TestCase):
    def test_circuit_state_covers_protection_modes_without_adding_rolling_tasks(self):
        # 롤링 중 OPEN + HALF_OPEN을 더해 3으로 만들거나, 보호 해제 상태를 정상으로 표시하면 실패합니다.
        panel = next(panel for panel in json.loads(DASHBOARD.read_text())["panels"] if panel["title"] == "서킷 상태")
        query = panel["targets"][0]["expr"].replace("${environment:regex}", "load-test").replace("${service:regex}", "gateway")
        cases = [
            ("closed", ["closed"], 0),
            ("half", ["half_open"], 1),
            ("open", ["open"], 2),
            ("forced", ["forced_open"], 2),
            ("disabled", ["disabled"], 3),
            ("metrics", ["metrics_only"], 4),
            ("rolling", ["open", "half_open"], 2),
            ("rolling-disabled", ["open", "disabled"], 3),
        ]
        series, expected = [], []
        states = ["closed", "half_open", "open", "forced_open", "disabled", "metrics_only"]
        for name, tasks, wanted in cases:
            for index, active in enumerate(tasks):
                for state in states:
                    labels = (f'job="spring-jvm",environment="load-test",service="gateway",name="{name}",'
                              f'instance="task-{index}",state="{state}"')
                    series.append({"series": "resilience4j_circuitbreaker_state{" + labels + "}",
                                   "values": ("1" if state == active else "0") + "+0x4"})
            expected.append({"labels": f'{{environment="load-test",service="gateway",name="{name}"}}', "value": wanted})
        # 다른 메트릭 환경의 시계열은 선택한 환경에 섞이지 않아야 합니다.
        series.append({"series": 'resilience4j_circuitbreaker_state{job="spring-jvm",environment="local",service="gateway",name="excluded",state="open"}',
                       "values": "1+0x4"})
        fixture = {"evaluation_interval": "15s", "tests": [{"interval": "15s", "input_series": series,
                   "promql_expr_test": [{"expr": query, "eval_time": "1m", "exp_samples": expected}]}]}
        with tempfile.TemporaryDirectory() as work:
            path = Path(work) / "query.json"
            path.write_text(json.dumps(fixture))
            result = subprocess.run(["docker", "run", "--rm", "--network", "none", "--pull=never",
                                     "--entrypoint", "/bin/promtool", "-v", f"{path}:/query.json:ro", PROMETHEUS_IMAGE,
                                     "test", "rules", "/query.json"], capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        mappings = panel["fieldConfig"]["defaults"]["mappings"][0]["options"]
        self.assertEqual([mappings[str(value)]["text"] for value in range(5)],
                         ["닫힘", "반열림", "열림", "비활성", "계측만"])


    def test_circuit_rule_fires_only_for_sustained_rejections(self):
        # 운영 규칙(grafana-alert-operations.yml.tftpl)의 식과 같은 평가 주기(30s)·지속 조건(2m)으로 Prometheus 규칙을 만들어 봅니다.
        # 계속 거절하는 서킷은 알리고, 한 번 열렸다 바로 회복한 서킷은 알리지 않아야 합니다.
        template = (ROOT / "templates/monitoring/grafana-alert-operations.yml.tftpl").read_text()
        rule = template[template.index("uid: circuit-breaker-rejecting"):]
        expr = re.search(r"expr: >-\n\s+(.+)\n", rule).group(1)
        self.assertIn("for: 2m", rule)
        rules = {"groups": [{"name": "circuit", "interval": "30s",
                             "rules": [{"alert": "CircuitRejecting", "expr": expr + " > 0", "for": "2m"}]}]}
        labels = 'job="spring-jvm",environment="production",service="gateway",name="{}"'
        fixture = {"rule_files": ["/work/rules.json"], "evaluation_interval": "30s", "tests": [{
            "interval": "15s",
            "input_series": [
                {"series": "resilience4j_circuitbreaker_not_permitted_calls_total{" + labels.format("sustained") + "}", "values": "0+1x30"},
                {"series": "resilience4j_circuitbreaker_not_permitted_calls_total{" + labels.format("blip") + "}", "values": "0x4 5x26"},
            ],
            "alert_rule_test": [{"eval_time": "4m", "alertname": "CircuitRejecting",
                                 "exp_alerts": [{"exp_labels": {"service": "gateway", "name": "sustained"}}]}],
        }]}
        with tempfile.TemporaryDirectory() as work:
            (Path(work) / "rules.json").write_text(json.dumps(rules))
            (Path(work) / "test.json").write_text(json.dumps(fixture))
            Path(work).chmod(0o755)
            for name in ("rules.json", "test.json"):
                (Path(work) / name).chmod(0o644)
            result = subprocess.run(["docker", "run", "--rm", "--network", "none", "--pull=never",
                                     "--entrypoint", "/bin/promtool", "-v", f"{work}:/work:ro", PROMETHEUS_IMAGE,
                                     "test", "rules", "/work/test.json"], capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

if __name__ == "__main__":
    unittest.main()
