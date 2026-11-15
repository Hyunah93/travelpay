# User·Gateway 인증 성능 측정

인증 구조의 개선 전·후를 같은 조건으로 비교합니다. 현재 코드는 User 보호 API의 중복 I01 호출 제거가 반영된 상태입니다. 개선 전 기준선은 당시 코드·jar로 실행한 결과를 사용하며, `--label baseline`만 붙인다고 이전 구조가 되지는 않습니다. 운영 AWS와 기존 개발 DB·Redis에는 요청하지 않습니다.

| 시나리오 | 요청 | 목적 | 업무 구성 |
|---|---|---|---|
| 성능 A | `GET /api/v1/users/me` | User 보호 API의 중복 인증(I01) 개선 효과 | 끔 |
| 성능 B | `GET /api/v1/wallets/me` | I01이 남는 업무 API 경로가 나빠지지 않았는지, 장애 격리 장치의 정상 부하 영향 | 켬 |
| 장애 C | 위 두 API에 일정한 도착률로 요청하는 중 User 일시 정지·정지, 회원 테이블 잠금 | 장애 중 상태·응답 시간과 회복 과정 | 켬 |

```text
성능 A(현재): k6 → auth-gateway:8080 → User /users/me
                                  → Redis 세션·DB 회원 확인 → 본인 정보 조회
성능 B: k6 → auth-gateway:8080 → User I01 → Redis 세션·DB 회원 확인
                              → Wallet /wallets/me → Wallet DB 조회
```

Gateway·User·Wallet은 실제 Eureka에서 주소를 찾습니다. 부하 요청·세션 확인·DB·Redis 접근과 Prometheus 수집은 모두 Docker 내부 통신입니다. Wallet·Ledger·Kafka는 업무 구성(`--profile business`)으로만 뜨며, 코드는 바꾸지 않고 운영 설정으로 실행합니다. Ledger는 시험 준비의 지갑 개설에만 쓰이고, 측정 요청은 Wallet DB만 읽습니다.

## 실행

저장소 루트에서 실행합니다. Docker Compose, JDK 21, Python 3.11 이상이 필요합니다. 기존 로컬 모니터링의 `.env` 설정은 그대로 사용합니다. 셸에 `COMPOSE_PROJECT_NAME`이 있어도 이 시험 구성을 가리키도록 compose 명령에는 `-p travelpay-auth-load-test`를 붙입니다(측정기도 같은 프로젝트를 명시합니다).

```bash
./gradlew :eureka-server:bootJar :gateway:bootJar :user-service:bootJar
docker compose -p travelpay-auth-load-test -f user-service/load-test/compose.yaml up -d --wait --wait-timeout 240
python3 user-service/load-test/measure.py monitoring
python3 user-service/load-test/measure.py prepare --users 100
```

앱 코드를 바꿔 다시 빌드했다면 JVM도 반드시 새로 만듭니다. `up -d`만으로는 바인드한 jar의 내용 변경이 실행 중 앱에 반영되지 않습니다. DB·Redis는 유지합니다.

```bash
docker compose -p travelpay-auth-load-test -f user-service/load-test/compose.yaml up -d --no-deps --force-recreate --wait --wait-timeout 240 auth-eureka
docker compose -p travelpay-auth-load-test -f user-service/load-test/compose.yaml up -d --no-deps --force-recreate --wait --wait-timeout 240 auth-user auth-gateway
python3 user-service/load-test/measure.py prepare --users 100
```

측정기는 예열 전에 호스트·컨테이너 jar의 SHA256과 컨테이너 생성 시각을 확인합니다. 재빌드 뒤 재생성을 빠뜨리면 측정을 거부합니다. 측정 중에는 빌드하지 않습니다.

작은 부하로 먼저 정상 동작을 확인한 뒤 반복 측정합니다.

```bash
python3 user-service/load-test/measure.py run --scenario my-info --label smoke --vus 2 --seconds 10 --warmup 5 --repeats 1 --prewarm 0
python3 user-service/load-test/measure.py run --scenario my-info --label baseline-a --vus 10 30 60 --seconds 60 --warmup 30 --repeats 3
```

성능 B와 장애 C는 업무 구성을 켠 뒤 지갑까지 준비합니다. 성능 A는 업무 구성을 끈 상태에서만 측정되도록 측정기가 막습니다.

```bash
./gradlew :ledger-service:bootJar :wallet-service:bootJar
docker compose -p travelpay-auth-load-test -f user-service/load-test/compose.yaml --profile business up -d --wait --wait-timeout 300
python3 user-service/load-test/measure.py prepare --users 100 --wallets
python3 user-service/load-test/measure.py run --scenario wallet-me --label baseline-b --vus 10 30 60 --seconds 60 --warmup 30 --repeats 3
python3 user-service/load-test/measure.py fault --type pause --label baseline
python3 user-service/load-test/measure.py fault --type lock-users --label baseline
python3 user-service/load-test/measure.py fault --type lock-credentials --label baseline
python3 user-service/load-test/measure.py fault --type pause-db --label baseline
python3 user-service/load-test/measure.py fault --type stop --label baseline
```

순서는 성능 A → (업무 구성 켜기) 성능 B → 장애 `pause` → `lock-users` → `lock-credentials` → `pause-db` → `stop`입니다. `stop`은 User JVM을 다시 시작하므로 마지막에 두고, 그 뒤 성능을 다시 재려면 앱을 재생성하고 사전 예열부터 합니다. 성능 A로 돌아갈 때는 업무 구성만 내립니다(`docker compose -p travelpay-auth-load-test ... --profile business stop auth-wallet auth-ledger auth-kafka`).

- `prepare`: CSRF 조회 → 필수 약관 동의·가입 → 로그인 → 본인 프로필 확인. `--wallets`면 로그인 뒤 CSRF를 다시 받아 지갑을 개설하고 조회해 지갑 ID를 저장합니다. VU마다 다른 회원·세션을 사용합니다. 재실행하면 같은 시험 계정으로 다시 로그인합니다.
- `run`: 먼저 기록하지 않는 사전 예열(`--prewarm`, 기본 120초, 가장 높은 동시성)로 새 JVM의 JIT 비용을 덜어 내고, 회차별 예열 뒤 정상 RPS·p50·p95·p99·오류를 수집합니다. 개선 전후 모두 앱을 새로 띄운 뒤 같은 사전 예열을 써야 공정합니다. 로그인·가입 시간은 제외합니다. 대기 시간 없이 반복하므로 VU 수는 실제 서비스의 전체 사용자 수와 다릅니다.
- 성능 A는 HTTP 200과 회원 ID·이메일·ACTIVE·USER, 성능 B는 200과 본인 지갑 ID·ACTIVE까지 검사합니다. 인증 오류, 다른 회원의 응답, 서버 오류가 있으면 결과를 보존하고 중단합니다. 임의의 응답 시간 목표는 설정하지 않습니다.
- SQL(역할별)·Redis 명령 통계와 서버 HTTP 카운터를 전후로 비교해 SELECT·Redis 명령·I01 횟수를 검산합니다. 초기 계정 생성과 예열은 제외합니다. Redis의 측정용 INFO·AUTH와 healthcheck PING·연결 관리 명령도 제외합니다. 명령 수는 네트워크 왕복 횟수와 다릅니다.
- 컨테이너별 cgroup `cpu.stat`을 회차 전후로 읽어 **요청당 CPU 시간(ms)**과 **CPU 상한 때문에 멈춘 주기 비율·시간**을 남깁니다. CPU 상한에 닿은 구성에서는 지연 백분위수에 상한 대기가 섞이므로, 개선 효과는 요청당 CPU 시간과 함께 판단합니다.
- `fault`: 두 API에 API마다 일정한 도착률(기본 40/초)로 150초 동안 요청하고, 30초에 장애를 넣어 20초 뒤 풉니다. 응답이 멈춰도 요청이 줄지 않도록 도착률 고정 방식을 쓰며, VU가 모자라 못 보낸 요청은 손실 요청으로 기록합니다. 두 API의 정상 응답을 확인하고 k6가 실행 중일 때만 장애를 넣습니다. 종류별 의미와 결과 해석은 아래를 따릅니다.
- 측정 중 jar·컨테이너·설정을 바꾸거나 수동 API를 호출하지 않습니다. 다른 부하 시험도 동시에 실행하지 않습니다.

시험 계정의 고정 암호와 DB·Redis의 `load-test-only`는 이 독립 환경 전용입니다. 실제 비밀값을 넣지 않습니다. 세션 쿠키는 `data/sessions.json`에 저장하고 Git에서 제외합니다. 세션이 만료되거나 Redis를 재시작하면 `prepare`를 다시 실행합니다.

## 장애 측정과 복원

| 종류 | 재현하는 상황 | `faultStart` / `faultEnd` 기준 |
|---|---|---|
| `pause` | User JVM 일시 정지 | pause 명령 완료 / unpause 명령 완료 |
| `pause-db` | DB 서버 정지로 SQL 취소도 처리하지 못함. 같은 DB를 쓰는 Wallet도 영향받음 | pause 명령 요청 / unpause 명령 완료 |
| `lock-users` | 회원 테이블의 `ACCESS EXCLUSIVE` 잠금으로 인증 조회 차단 | 잠금 SQL 명령 요청 / COMMIT 명령 완료 |
| `lock-credentials` | 로그인 정보 테이블 잠금으로 인증 후 마이페이지 조회 차단 | 잠금 SQL 명령 요청 / COMMIT 명령 완료 |
| `stop` | User 정상 종료 후 재시작 | stop 명령 요청 / start 명령 완료. 실제 healthy 시각은 별도 기록 |

DB 일시 정지는 명령 완료 전부터 진행 중인 SQL에 영향을 줄 수 있어 요청 시각부터 셉니다. SQL 잠금도 실제 잠금 획득 시각을 별도 측정하지 않으므로 명령 처리·잠금 획득 대기가 포함됩니다. 모든 종류는 명령 실행 직전의 `injectionRequested`도 남깁니다. 시각은 호스트 Python과 Docker k6의 같은 Unix 시계를 전제로 하며, 시계가 어긋나면 첫 실패·회복 계산도 어긋납니다.

- 전·중·후 성공률과 p95·최대 지연은 **요청 시작 시각**으로 구분합니다. 첫 실패는 **장애 시작 뒤 처음 완료된 실패 응답**이므로, 직전에 보낸 요청도 포함합니다. 전송 오류는 k6 오류 코드로 따로 셉니다.
- 회복은 해제 뒤 시작한 요청이 5초 연속 모두 200인 첫 초입니다. 서버 기동·Eureka 갱신 시간도 포함하며 서킷의 CLOSED 전환 시각과 다릅니다. 안정 구간을 찾지 못하면 미회복으로 남깁니다.
- 유효성은 k6 정상 종료, 손실 요청 0건, API별 전·중·후 표본, 장애 전에 완료된 실패 0건으로 판정합니다. 미회복은 측정 무효와 다른 결과입니다. 후행 User 지표를 못 읽어도 `observationError`를 남기고 CSV 분석·요약을 보존합니다. JVM 시작 시각이 바뀌거나 없어 비교할 수 없는 전후 카운터는 `null`입니다.
- Ctrl+C·SIGTERM·POSIX SIGHUP은 같은 복원 경로를 거칩니다. `nohup`처럼 SIGHUP을 무시하도록 실행했다면 그 설정을 유지해 터미널을 닫아도 측정이 계속됩니다. 대상 컨테이너가 실행 중인지, `travelpay-fault-lock` 잠금 세션이 제거됐는지 확인하고 `aborted.json`을 남깁니다. 일시적인 컨테이너 복원 실패는 최대 3번까지만 시도하고, 실패하면 명시적으로 알립니다. SIGKILL·호스트 종료처럼 정리 코드를 실행할 수 없는 중단은 이 방식으로 복원을 보장하지 않습니다.

`stop` 시험은 JRE에 jar를 마운트한 이 Compose의 종료·기동을 측정합니다. 운영 이미지의 Eureka 선행 등록 해제·drain 진입점과 ECS 롤링 배포를 재현하는 시험은 아닙니다.

로그인·비밀번호 변경·가입·관리자 조회처럼 비용이 큰 정상 처리는 별도로 확인합니다. 아래 명령은 각 작업 20회를 쉬는 상태와 `/users/me` 60 VU 부하 상태에서 비교합니다. 수동 HTTP 클라이언트가 호스트의 28080을 거치므로 이 결과에는 포트 전달 시간이 포함됩니다. Docker 내부 경로의 성능 A·B 처리량과 구분해 시간 제한의 여유를 확인하는 데 씁니다.

```bash
python3 user-service/load-test/measure.py slow-ops --label normal-operations --samples 20 --vus 60 --load-seconds 300
```

## 포트·자원

| 대상 | Docker 내부 | 호스트 공개 | CPU 상한 | 메모리 상한 / 최대 Heap |
|---|---|---|---|---|
| Gateway | 8080 / 관리 18080 | 127.0.0.1:28080 → 8080 | 1 | 768MiB / 384MiB |
| User | 8080 / 관리 18081 | 없음 | 2 | 1024MiB / 512MiB |
| Eureka | 8761 / 관리 18061 | 없음 | 0.5 | 512MiB / 256MiB |
| PostgreSQL | 5432 | 없음 | 1 | 512MiB |
| 세션 Redis | 6379 | 없음 | 0.5 | 256MiB / maxmemory 128MiB |
| Wallet(업무 구성) | 8080 / 관리 18082 | 없음 | 2 | 1536MiB / 768MiB |
| Ledger(업무 구성) | 8080 / 관리 18085 | 없음 | 0.5 | 1024MiB / 512MiB |
| Kafka(업무 구성) | 9092 | 없음 | 1 | 1024MiB / 512MiB |
| k6 | 내부 Gateway 호출 | 없음 | 2 | 2048MiB |

Wallet·Ledger의 메모리·Heap은 운영 Task 크기와 같습니다. DB 계정·스키마는 팀 공용 초기화 SQL(`infra/postgres/init/01-service-schemas.sql`)을 그대로 씁니다. Wallet의 대사 스케줄러는 wallet 부하 시험처럼 끄고, 충전을 시험하지 않으므로 Mock Bank는 띄우지 않습니다. k6 메모리는 장애 시험에서 응답을 기다리는 VU가 늘어나는 것에 대비한 값이며 앱 측 결과에는 영향이 없습니다.

User는 운영과 같은 DB 풀 최대 5·최소 1, 연결 획득 2초·검증 1초를 사용합니다. Redis는 `noeviction`, 디스크 저장 없음입니다. 운영 파일만 읽고 HTTP 시험망에서 필요한 Secure 쿠키만 해제합니다. HTTP 히스토그램·로그·추적 설정도 비교 양쪽에서 유지합니다. Eureka의 현재 갱신 5초·목록 조회 5초와 LoadBalancer 캐시 비활성화는 유지하며, 인증 흐름 같은 앱 동작은 시험 설정으로 바꾸지 않습니다.

CPU 상한은 PC별 사용 가능 자원의 차이를 줄이기 위한 시험 조건입니다. ECS의 CPU 예약과 같은 의미가 아니며, 이 결과로 AWS 최대 처리량을 단정할 수 없습니다. Docker Desktop의 CPU·메모리 할당, 다른 프로세스·컨테이너 부하도 결과에 영향을 줍니다.

## Grafana·로그

기존 Prometheus·Grafana·Alloy·Loki를 재사용합니다. `monitoring` 명령은 공통 부하 시험 overlay로 Prometheus를 `travelpay-auth-load-test_default` 네트워크에 연결하고 이 폴더의 수집 대상 파일을 마운트합니다. 설정 폴더가 바뀐 경우도 반영하도록 Prometheus·Alloy·Grafana를 재생성하며 잠시 재시작됩니다. 기존 `local` 대상과 모니터링 저장 볼륨은 유지합니다. 다른 시험 대상 파일을 쓰고 있었다면 공통 overlay의 선택 대상이 이 시험으로 바뀝니다. 동시 시험 연결을 자동 병합하지 않습니다.

- Grafana `http://localhost:3000/d/travelpay-local-perf`: 메트릭 환경 `load-test`, 서비스 `gateway`·`user-service`·`eureka-server`, API `/api/v1/users/me` 또는 `/internal/v1/auth/session`을 선택합니다. API는 호출 후 수집 주기와 화면 갱신을 거쳐 나타납니다.
- HTTP 응답 시간·RPS, Gateway의 User 세션 호출 시간, User의 DB 대기·점유 시간·Heap·CPU·GC를 함께 봅니다. 서비스·API별 p95를 빼서 구간 지연을 계산하면 안 됩니다.
- 현재 Gateway 서버 HTTP 지표의 URI는 `UNKNOWN`입니다. API별 조회는 User에서 선택하고, Gateway 경유 전체 지연은 k6 결과와 라우트 지표로 구분합니다.
- Alloy는 `io.travelpay.logs=load-test`를 명시한 컨테이너만 추가 수집합니다. `io.travelpay.service`가 서비스 이름이 되며 `environment=load-test`, `job=docker-local`로 Loki에 들어갑니다. 기존 로컬 Docker 로그 분류를 재사용하므로 운영에서도 원본으로 쓰는 대시보드 JSON은 수정하지 않습니다. 개발 로그 수집은 그대로 유지합니다.
- 대시보드 통합 로그는 서비스 선택을 따릅니다. 메트릭 환경 필터는 로그에 적용되지 않으므로 시험 로그만 보려면 Explore에서 `{job="docker-local",environment="load-test",service_name="user-service"}`를 사용합니다. 메트릭은 Prometheus, 로그는 Alloy→Loki가 수집합니다.

시험 종료 후 일반 모니터링 연결로 되돌리려면:

```bash
docker compose -p travelpay-monitoring -f compose.monitoring.yaml up -d prometheus
```

## 결과와 비교

`results/<UTC시각>-<label>/`에 k6 요약·SQL/메트릭·CPU 전후값·컨테이너 자원 표본·환경 정보와 `summary.md`를 남깁니다. 장애 측정은 `results/<UTC시각>-fault-<종류>-<label>/`에 요청별 기록(`requests.csv`), 장애 시각(`fault-analysis.json`의 events), 5초 단위 흐름과 `summary.md`를 남깁니다. 원본 실행 결과와 세션 데이터는 Git에서 제외합니다. 공유 문서에는 검증한 요약과 조건만 옮깁니다.

비교 시 코드 커밋·jar SHA256, 이미지 ID, 자원 상한, 사용자 수·DB 행 수, 같은 VU·예열·측정 시간·반복 수, 히스토그램·로그 설정을 맞춥니다. 반복별 p95의 중앙값은 전체 표본을 합친 p95와 다릅니다. Docker 내부 통신만으로 모든 실행 환경 차이가 사라지지는 않습니다.

```bash
python3 -m unittest discover -s user-service/load-test -p 'test_*.py'
docker compose -p travelpay-auth-load-test -f user-service/load-test/compose.yaml down
```

`down`은 이 시험 프로젝트만 종료합니다. 시험 DB는 메모리 파일시스템에 있어 PostgreSQL 컨테이너를 삭제하면 사라집니다. 다음 실행 때 `prepare`부터 다시 진행합니다. 기존 개발 인프라와 모니터링은 종료하지 않습니다.
