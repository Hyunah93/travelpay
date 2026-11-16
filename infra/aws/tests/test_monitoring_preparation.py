"""Data 호스트 모니터링 준비(prepare-monitoring.py)를 검사합니다. 실제 AWS·chown은 호출하지 않습니다."""

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

path = Path(__file__).parents[1] / "scripts" / "prepare-monitoring.py"
spec = importlib.util.spec_from_file_location("monitoring_preparation", path)
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class MonitoringPreparationTest(unittest.TestCase):
    def test_grafana_password_accepts_typed_passphrases_but_not_control_or_edge_spaces(self):
        # 운영자가 정하는 문장형 암호(공백·기호 포함)와 비밀값 준비 도구가 만드는 무작위 값을 모두 받습니다.
        for value in ["Travel pay $HOME 2026!", "correct horse", "0" * 64, "x" * 128]:
            with self.subTest(value=value):
                self.assertEqual(prepare.grafana_password(value), value)
        for value in ["short pw", " leading space", "trailing space ", "a" * 40 + "\nGF_SECURITY_ADMIN_USER=x",
                      "여행 결제 관리 암호", "x" * 129]:
            with self.subTest(value=value), self.assertRaises(prepare.PreparationError):
                prepare.grafana_password(value)

    def test_slack_webhook_becomes_a_single_environment_line_only_for_the_issued_format(self):
        # env_file에 다른 변수가 끼어들거나(줄바꿈) Grafana가 다시 치환하는($) 값은 파일로 만들지 않습니다.
        url = "https://hooks.slack.com/services/T0123ABCD/B0456EFGH/abcDEF0123456789ghiJKL"
        self.assertEqual(prepare.slack_webhook_environment(url), f"SLACK_WEBHOOK_URL={url}\n")
        for value in ["", url + "\nGF_SECURITY_ADMIN_USER=x", url + "$HOME", url + " ", "https://example.com/services/T0/B0/x"]:
            with self.subTest(value=value), self.assertRaises(prepare.PreparationError):
                prepare.slack_webhook_environment(value)

    def test_notifications_are_installed_readable_only_into_an_existing_alerting_folder(self):
        with tempfile.TemporaryDirectory() as work:
            source = Path(work) / "grafana-alert-notifications.yml"
            source.write_text("apiVersion: 1\n")
            target = Path(work) / "alerting" / "notifications.yml"
            # 폴더가 없으면 설정 묶음 적용이 끝나지 않은 것이므로 만들지 않고 멈춥니다(umask 077로 만들면 Grafana가 못 읽음).
            with self.assertRaises(prepare.PreparationError):
                prepare.install_notifications(source, target)
            target.parent.mkdir()
            prepare.install_notifications(source, target)
            self.assertEqual(target.read_text(), "apiVersion: 1\n")
            self.assertEqual(target.stat().st_mode & 0o777, 0o644)
            self.assertEqual(sorted(path.name for path in target.parent.iterdir()), ["notifications.yml"])

    def test_missing_webhook_keeps_the_active_contact_configuration_unchanged(self):
        # AWS 읽기·root 비밀 파일 쓰기·EBS 확인은 격리하고, 연락처 설치 순서는 실제 파일로 검사합니다.
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            (root / "runtime.json").write_text(json.dumps({"region": "ap-northeast-2", "parameter_prefix": "/travelpay/production",
                                                         "volumes": {"monitoring": {"mount": work}}}))
            (root / "grafana-alert-notifications.yml").write_text("apiVersion: 1\n# new contact\n")
            target = root / "alerting" / "notifications.yml"
            target.parent.mkdir()
            target.write_text("apiVersion: 1\n# previous configuration\n")
            previous = target.read_bytes()
            mask = os.umask(0o077)
            try:
                with patch.object(prepare, "__file__", str(root / "prepare-monitoring.py")), \
                        patch.object(Path, "is_mount", return_value=True), patch.object(prepare, "prepare_monitoring"), \
                        patch.object(prepare, "write_secret"), patch.object(prepare, "NOTIFICATIONS_TARGET", target), \
                        patch.object(prepare, "read_parameter", side_effect=["test-password-123", prepare.PreparationError("ParameterNotFound")]):
                    with self.assertRaises(prepare.PreparationError):
                        prepare.main()
            finally:
                os.umask(mask)
            self.assertEqual(target.read_bytes(), previous)

    def test_each_monitoring_folder_gets_its_image_user_and_mode(self):
        # 표의 uid가 실제 이미지 사용자와 같은지는 TRAVELPAY_TEST_DOCKER_MONITORING 검사가 이미지에서 확인합니다.
        with tempfile.TemporaryDirectory() as work:
            owners = {}
            prepare.prepare_monitoring(Path(work), chown=lambda path, uid, gid: owners.__setitem__(path.name, (uid, gid)))
            self.assertEqual(sorted(owners), sorted(prepare.MONITORING_DIRECTORIES))
            for name, (uid, gid, mode) in prepare.MONITORING_DIRECTORIES.items():
                with self.subTest(folder=name):
                    self.assertEqual(owners[name], (uid, gid))
                    self.assertEqual((Path(work) / name).stat().st_mode & 0o777, mode)


class ParameterReadTest(unittest.TestCase):
    CONFIG = {"region": "ap-northeast-2", "parameter_prefix": "/travelpay/production"}
    DENIED = ("An error occurred (AccessDeniedException) when calling the GetParameter operation: "
              "User is not authorized to perform: ssm:GetParameter with an explicit deny")

    def read(self, results):
        """get-parameter가 results를 차례로 돌려주는 실행기로 읽고, (값 또는 예외, 실행 횟수)를 돌려줍니다."""
        calls = []

        def run(command, **_):
            calls.append(command)
            outcome = results.pop(0)
            if isinstance(outcome, Exception):
                raise outcome
            return outcome

        try:
            value = prepare.read_parameter(self.CONFIG, "GRAFANA_ADMIN_PASSWORD", run=run, sleep=lambda _: None)
        except prepare.PreparationError as error:
            value = error
        return value, len(calls)

    def completed(self, code, stdout="", stderr=""):
        return subprocess.CompletedProcess([], code, stdout, stderr)

    def test_retries_denial_while_the_new_permission_propagates(self):
        ok = self.completed(0, json.dumps({"Parameter": {"Value": "a" * 64}}))
        self.assertEqual(self.read([self.completed(254, stderr=self.DENIED)] * 2 + [ok]), ("a" * 64, 3))

    def test_retries_connection_failures_and_timeouts(self):
        ok = self.completed(0, json.dumps({"Parameter": {"Value": "a" * 64}}))
        failures = [self.completed(255, stderr="Could not connect to the endpoint URL"), subprocess.TimeoutExpired("aws", 30)]
        self.assertEqual(self.read(failures + [ok]), ("a" * 64, 3))

    def test_missing_parameter_fails_without_retry(self):
        missing = "An error occurred (ParameterNotFound) when calling the GetParameter operation: "
        error, calls = self.read([self.completed(254, stderr=missing)])
        self.assertEqual((str(error).rsplit(": ", 1)[-1], calls), ("ParameterNotFound", 1))

    def test_gives_up_after_the_bounded_attempts_without_exposing_the_response(self):
        error, calls = self.read([self.completed(254, stderr=self.DENIED)] * prepare.READ_ATTEMPTS)
        self.assertEqual(calls, prepare.READ_ATTEMPTS)
        self.assertTrue(str(error).endswith("AccessDeniedException"))
        self.assertNotIn("explicit deny", str(error))


if __name__ == "__main__":
    unittest.main()
