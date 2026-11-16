"""선택 실행: TRAVELPAY_TEST_DOCKER_MONITORING=1. 운영 Grafana 이미지가 Slack 연락처·알림 정책을 읽고 알림을 Slack으로 보내려 하는지 검사합니다.

Terraform 모의 테스트는 파일 내용만 봅니다. 연락처의 $SLACK_WEBHOOK_URL이 환경 변수로 바뀌는지, 규칙 주석의 $가 치환되지 않는지,
받을 곳을 정하지 않은 규칙이 실제로 Slack 연락처로 가는지는 Grafana를 실행해야 드러납니다. 네트워크 없이 실행하므로 Slack에는 보내지 않습니다.
"""

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import unittest
import uuid

ROOT = Path(__file__).parents[1]
GRAFANA_IMAGE = re.search(r'^\s+grafana\s+= "([^"]+@sha256:[0-9a-f]{64})"$', (ROOT / "monitoring.tf").read_text(), re.M).group(1)
# 형식만 맞춘 가짜 주소입니다. 컨테이너에 네트워크가 없어 실제로 나가지 않습니다.
WEBHOOK = "https://hooks.slack.com/services/TTEST0000/BTEST0000/notarealtoken"
PASSWORD = "check-only-" + uuid.uuid4().hex


def docker(*arguments, check=True):
    return subprocess.run(["docker", *arguments], capture_output=True, text=True, timeout=180, check=check).stdout


@unittest.skipUnless(os.environ.get("TRAVELPAY_TEST_DOCKER_MONITORING") == "1", "모니터링 실행 검사는 명시적으로 실행합니다.")
class GrafanaAlertingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container = "travelpay-grafana-alerting-" + uuid.uuid4().hex[:8]
        work = Path(os.environ.get("TMPDIR", "/tmp")) / cls.container
        cls.addClassCleanup(shutil.rmtree, work)
        alerting, datasources = work / "alerting", work / "datasources"
        alerting.mkdir(parents=True)
        datasources.mkdir()
        # 운영 묶음과 같은 파일입니다. 송금 규칙은 monitoring.tf처럼 데이터소스만 운영 값으로 바꿉니다.
        shutil.copy(ROOT / "templates" / "monitoring" / "grafana-datasources.yml", datasources / "datasources.yml")
        shutil.copy(ROOT / "templates" / "monitoring" / "grafana-alert-notifications.yml", alerting / "notifications.yml")
        transfer = (ROOT.parent / "monitoring" / "grafana" / "provisioning" / "alerting" / "transfer-alerts.yml").read_text()
        (alerting / "transfer-alerts.yml").write_text(transfer.replace("datasourceUid: prometheus-local", "datasourceUid: prometheus"))
        # 실제 Terraform templatefile로 운영 규칙도 렌더링합니다. 이 임시 디렉터리에는 Provider·Backend·리소스가 없습니다.
        values = {"services": ["gateway", "eureka-server", "user-service", "wallet-service", "payment-service",
                               "exchange-service", "ledger-service", "mock-bank"],
                  "recovery_schedulers": ["com.travelpay.payment.presentation.payment.PendingSettlementScheduler",
                                          "com.travelpay.payment.transfer.application.TransferRecoveryScheduler"]}
        template = ROOT / "templates" / "monitoring" / "grafana-alert-operations.yml.tftpl"
        expression = "jsonencode(templatefile(" + json.dumps(str(template.resolve())) + "," + json.dumps(values) + "))\n"
        rendered = subprocess.run(["terraform", f"-chdir={work}", "console", "-no-color"], input=expression,
                                  capture_output=True, text=True, check=True, timeout=30)
        (alerting / "operations.yml").write_text(json.loads(json.loads(rendered.stdout)))
        for path in [work, alerting, datasources, *alerting.iterdir(), *datasources.iterdir()]:
            path.chmod(0o755 if path.is_dir() else 0o644)
        cls.addClassCleanup(docker, "rm", "-f", "-v", cls.container, check=False)
        docker("run", "-d", "--name", cls.container, "--network", "none", "--pull=never",
               "-e", "SLACK_WEBHOOK_URL=" + WEBHOOK, "-e", "GF_SECURITY_ADMIN_PASSWORD=" + PASSWORD,
               "-v", f"{alerting}:/etc/grafana/provisioning/alerting:ro",
               "-v", f"{datasources}:/etc/grafana/provisioning/datasources:ro", GRAFANA_IMAGE)
        cls.wait(lambda: cls.api("/api/health") is not None)

    @classmethod
    def api(cls, path):
        result = subprocess.run(["docker", "exec", cls.container, "wget", "-q", "-O", "-",
                                 f"http://admin:{PASSWORD}@localhost:3000{path}"], capture_output=True, text=True, timeout=30)
        return result.stdout if result.returncode == 0 else None

    @staticmethod
    def wait(condition, timeout=180):
        deadline = time.monotonic() + timeout
        while not condition():
            if time.monotonic() > deadline:
                raise AssertionError("제한 시간 안에 조건을 만족하지 못했습니다.")
            time.sleep(3)

    def test_contact_point_receives_the_webhook_from_the_environment(self):
        exported = self.api("/api/v1/provisioning/contact-points/export?decrypt=true&format=json")
        receivers = [receiver for point in json.loads(exported)["contactPoints"] for receiver in point["receivers"]]
        self.assertEqual([(r["uid"], r["type"], r["settings"]["url"]) for r in receivers], [("slack-monitoring", "slack", WEBHOOK)])

    def test_rules_keep_template_variables_and_follow_the_default_policy(self):
        self.assertEqual(json.loads(self.api("/api/v1/provisioning/policies"))["receiver"], "slack-monitoring")
        rules = json.loads(self.api("/api/v1/provisioning/alert-rules"))
        expected_templates = {"transfer-manual-review": "{{ $values.A.Value }}", "transfer-processing-stuck": "{{ $values.A.Value }}",
                              "service-metrics-missing": "{{ $labels.service }}",
                              "payment-recovery-scheduler-stalled": "{{ $labels.code_namespace }}",
                              "circuit-breaker-rejecting": "{{ $labels.name }}"}
        self.assertEqual({rule["uid"] for rule in rules}, set(expected_templates))
        for rule in rules:
            with self.subTest(rule=rule["uid"]):
                self.assertIsNone(rule.get("notification_settings"))
                self.assertIn(expected_templates[rule["uid"]], rule["annotations"]["summary"])

    def test_firing_alerts_are_sent_to_the_slack_contact_point(self):
        # 네트워크가 없어 Prometheus를 조회하지 못하므로 규칙이 오류 알림(DatasourceError)을 냅니다. 새 Grafana는 시작 직후
        # 기본 설정으로 시작했다가 저장된 정책을 다음 동기화(약 1분)에 반영하므로, 묶음의 받을 곳이 Slack이 될 때까지 기다립니다.
        def routed_to_slack():
            groups = json.loads(self.api("/api/alertmanager/grafana/api/v2/alerts/groups") or "[]")
            return bool(groups) and {group["receiver"]["name"] for group in groups} == {"slack-monitoring"}

        self.wait(routed_to_slack)

        def tried_the_webhook():
            # 보내려 한 주소가 환경 변수의 Webhook인지 봅니다. 연결 실패는 네트워크를 막은 결과입니다.
            logs = subprocess.run(["docker", "logs", self.container], capture_output=True, text=True, timeout=30)
            return any("Failed to send Slack message" in line and WEBHOOK in line
                       for line in (logs.stdout + logs.stderr).splitlines())

        self.wait(tried_the_webhook)


if __name__ == "__main__":
    unittest.main()
