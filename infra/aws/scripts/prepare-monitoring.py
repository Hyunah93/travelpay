#!/usr/bin/env python3
"""Data EC2의 모니터링 폴더와 Grafana 관리자 암호·Slack Webhook 파일을 준비합니다. 비밀값은 출력하지 않습니다.

Redis·Kafka 준비(prepare-data.py)와 유닛을 나눠, 이 준비가 실패해도 Redis·Kafka의 시작·반영은 막지 않습니다.
"""

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time


class PreparationError(Exception):
    pass


def failure_message(error):
    reason = str(error) if isinstance(error, PreparationError) else type(error).__name__
    return f"Monitoring preparation failed: {reason}"


# 모니터링 이미지의 실행 사용자(uid, gid)와 폴더 권한입니다. 컨테이너가 자기 데이터 폴더에만 쓸 수 있게 합니다.
MONITORING_DIRECTORIES = {
    "prometheus": (65534, 65534, 0o750),  # prom/prometheus: nobody
    "loki": (10001, 10001, 0o750),        # grafana/loki: 10001
    "grafana": (472, 0, 0o750),           # grafana/grafana: 472
    "alloy": (0, 0, 0o700),               # grafana/alloy: root(Docker 소켓으로 컨테이너 로그를 읽음)
}
GRAFANA_UID = 472
# IAM 변경은 전파에 시간이 걸리고(이 매개변수의 거부를 같은 apply에서 푼 직후 실행됨), 부팅 직후에는 SSM에 아직 닿지 않을 수
# 있습니다. 권한 거부와 연결 실패만 정한 횟수까지 다시 시도하고, 매개변수 없음 같은 오류는 바로 실패합니다.
READ_ATTEMPTS = 6
READ_RETRY_SECONDS = 10


# 운영자가 브라우저에 입력하는 암호라 앞뒤 공백 없이 출력 가능한 ASCII 10~128자(공백 포함)만 받습니다. 줄바꿈 같은 제어 문자가
# 비밀 파일에 들어가지 않게 하며, 비밀값 준비 도구(prepare-parameters.py)와 같은 규칙입니다.
GRAFANA_PASSWORD_PATTERN = r"[!-~][ -~]{8,126}[!-~]"


def grafana_password(password):
    if not re.fullmatch(GRAFANA_PASSWORD_PATTERN, password):
        raise PreparationError("Grafana 관리자 암호는 앞뒤 공백 없이 영문·숫자·기호·공백 10~128자여야 합니다.")
    return password


# 현재 구성은 연락처의 $SLACK_WEBHOOK_URL 환경 변수 치환을 사용하므로, 주소는 Compose의 env_file로 넘깁니다.
# 줄바꿈이 들어가면 env_file에 다른 변수가 생기고 $가 들어가면 Grafana가 다시 치환하므로, Slack 발급 형식만 받습니다.
# 비밀값 준비 도구(prepare-parameters.py)와 같은 규칙입니다.
SLACK_WEBHOOK_PATTERN = r"https://hooks\.slack\.com/services/[A-Za-z0-9]+/[A-Za-z0-9]+/[A-Za-z0-9]+"
# Slack 연락처 설정입니다. 설정 묶음은 이 파일을 데이터 폴더에만 두고, Webhook 주소를 받은 뒤 여기서 Grafana 폴더에 넣습니다.
# 주소 없이 이 파일만 먼저 들어가면 실행 중인 Grafana가 다음에 다시 시작될 때 연락처를 만들지 못해 시작에 실패합니다.
NOTIFICATIONS_TARGET = Path("/opt/travelpay/monitoring/grafana/provisioning/alerting/notifications.yml")


def slack_webhook_environment(url):
    if not re.fullmatch(SLACK_WEBHOOK_PATTERN, url):
        raise PreparationError("Slack Webhook 주소가 https://hooks.slack.com/services/ 발급 형식이 아닙니다.")
    return f"SLACK_WEBHOOK_URL={url}\n"


def install_notifications(source, target):
    """Grafana 사용자가 읽도록 0644로 바꿔치기합니다. 폴더는 설정 묶음 적용(apply-data-bundle.py)이 0755로 만듭니다."""
    if not target.parent.is_dir():
        raise PreparationError("Grafana 알림 설정 폴더가 없습니다. 설정 묶음 적용을 먼저 확인하세요.")
    temporary = target.with_name(target.name + ".new")
    temporary.write_bytes(source.read_bytes())
    temporary.chmod(0o644)
    os.replace(temporary, target)


def read_parameter(config, name, run=subprocess.run, sleep=time.sleep):
    command = ["aws", "ssm", "get-parameter", "--region", config["region"], "--name",
               config["parameter_prefix"] + "/" + name, "--with-decryption", "--output", "json"]
    for attempt in range(1, READ_ATTEMPTS + 1):
        try:
            result = run(command, capture_output=True, text=True, timeout=30)
        except subprocess.TimeoutExpired:
            code = None
        else:
            if result.returncode == 0:
                return json.loads(result.stdout)["Parameter"]["Value"]
            # 오류 원문에는 요청 정보가 들어갈 수 있어 AWS 오류 코드만 남깁니다. 연결 실패에는 코드가 없습니다.
            found = re.search(r"\(([A-Za-z0-9]+)\)", result.stderr or "")
            code = found.group(1) if found else None
        if code not in (None, "AccessDeniedException") or attempt == READ_ATTEMPTS:
            raise PreparationError(f"Parameter Store에서 {name}을 읽지 못했습니다: {code or '연결 실패'}")
        sleep(READ_RETRY_SECONDS)


def write_secret(directory, name, contents, uid, gid):
    """tmpfs(/run)에만 두고, 해당 컨테이너 사용자만 읽을 수 있게 합니다."""
    directory.mkdir(parents=True, exist_ok=True)
    directory.chmod(0o500)
    os.chown(directory, uid, gid)
    target = directory / name
    target.write_text(contents)
    os.chown(target, uid, gid)
    target.chmod(0o400)


def prepare_monitoring(root, chown=os.chown):
    for name, (uid, gid, mode) in MONITORING_DIRECTORIES.items():
        directory = root / name
        directory.mkdir(exist_ok=True)
        chown(directory, uid, gid)
        directory.chmod(mode)


def main():
    root = Path(__file__).resolve().parent
    config = json.loads((root / "runtime.json").read_text())
    os.umask(0o077)
    monitoring_root = Path(config["volumes"]["monitoring"]["mount"])
    if not monitoring_root.is_mount():
        raise PreparationError("모니터링 EBS가 마운트되지 않았습니다.")
    prepare_monitoring(monitoring_root)
    write_secret(Path("/run/travelpay/grafana"), "admin-password",
                 grafana_password(read_parameter(config, "GRAFANA_ADMIN_PASSWORD")), GRAFANA_UID, 0)
    # Compose(root)가 읽어 Grafana 환경 변수로 넘기는 파일이라 root만 읽게 둡니다. 관리자 암호 폴더와 소유자가 달라 나눕니다.
    write_secret(Path("/run/travelpay/grafana-env"), "slack.env",
                 slack_webhook_environment(read_parameter(config, "SLACK_WEBHOOK_URL")), 0, 0)
    install_notifications(root / "grafana-alert-notifications.yml", NOTIFICATIONS_TARGET)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # AWS 오류와 비밀값이 포함될 수 있는 응답·명령 인자는 로그에 내보내지 않습니다.
        print(failure_message(error), file=sys.stderr)
        sys.exit(1)
