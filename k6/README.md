# 부하 테스트 (k6)

메일 인증 파이프라인의 부하 테스트. 시나리오는 코드로만 관리한다 — 재현 가능해야 결과를 방어할 수 있다.

> ⚠️ **구 스크립트는 삭제됐다 (M-25, 20260826).**
>
> `src/test/k6/emailTest.js` · `LoginTest.js` 는 이 디렉터리의 스위트로 대체됐다. 정본이 둘로 보이면
> 아래 "사전 조건" 을 지키지 않은 구 스크립트를 실수로 돌릴 수 있고, 그 경우 **실존 도메인으로 대량
> 발송되어 발신 평판이 훼손된다**(G-17-a 에서 결함 4건으로 지목된 파일이다).
>
> 원본이 필요하면 **`a9c34b9:src/test/k6/emailTest.js`** 에서 볼 수 있다.
> 포트폴리오 p24 의 `1000명 / 30초 / 77rps` 수치가 나온 시나리오가 그것이다.

## 왜 시나리오가 4개인가

이번 개선(Redis Streams + 병렬 워커)이 시스템을 **두 계층으로 쪼갰다.**

```
[수락 계층]  HTTP → XADD → 200 반환          O(1), 수백 µs
     ‖  ← 여기서 응답이 끝난다
[처리 계층]  워커 N개 → SMTP → XACK          SMTP RTT 에 종속, 처리량 상한 존재
```

**스파이크 테스트만으로는 개선을 증명할 수 없다.** 600 VU 를 쏴도 API 는 XADD 하고 즉시 200 을
뱉으므로 응답시간이 좋게 나온다. 그건 "큐에 넣었다" 는 뜻이지 "메일이 나갔다" 가 아니다.

| | 시나리오 | 답하는 질문 | 측정 계층 |
|---|---|---|---|
| **S1** | 스파이크 | 급증에도 수락 응답시간이 평탄한가 (**개선 증명이 아니다** — 아래 참조) | 수락 |
| **S2** | 스트레스 | 배출률 상한은 몇 건/초인가 | 처리 |
| **S3** | 워커 스윕 | 왜 하필 워커 N개인가 (G-4) | 처리 |
| **S4** | 카오스 | 유실이 정말 0인가 (Streams 의 존재 이유) | 큐 |

S4 는 S1·S2 로 절대 잡히지 않는다. **정상 상황에서는 개선 전 List+BRPOP 도 유실이 없기 때문에,
장애를 주입해야만 차이가 드러난다.**

## 사전 조건 (지키지 않으면 측정이 무의미해진다)

### 1. 주소는 반드시 유니크해야 한다

`MemberService.requestVerificationCode` 는 `setIfAbsent(email, code, 3분)` 으로 중복 요청을 막는다.
같은 주소를 재사용하면 두 번째부터 **400 `MEMBER_4003`** 으로 튕기고, 그러면 측정 대상이
메일 파이프라인이 아니라 "SETNX 실패 후 예외 반사 경로" 가 된다.

스크립트는 `lt-{RUN_ID}-{VU}-{ITER}@loadtest.invalid` 로 주소를 만든다.
**회차마다 `RUN_ID` 를 반드시 다르게 줄 것.** `mail_rejected_duplicate` 임계값이 `count==0` 이라
어기면 테스트가 실패로 끝난다.

### 2. 반드시 Mailpit 으로 보낼 것

실제 SMTP(Gmail 등)로 수천 건의 존재하지 않는 주소를 쏘면 바운스가 쌓여 **발신 도메인 평판이
훼손되고 계정이 정지**된다. `.env` 의 `MAIL_SENDER_HOST` 가 Mailpit 을 가리키는지 매 회차 확인할 것.

### 3. 부하 생성기와 SUT 를 분리할 것

**k6 를 앱과 같은 EC2 에서 돌리지 말 것.** m7g.xlarge 는 4 vCPU 인데 이미
Blue + Green + Prometheus + Grafana + Loki + Promtail + Mailpit 이 올라간다.
여기에 k6 를 얹으면 측정 도구가 측정 대상의 CPU 를 빼앗아 결과가 무의미해진다.

```bash
k6 run -e BASE_URL=https://origin.ddotg.dev -e MAILPIT_URL=http://origin.ddotg.dev:8025 ...
```

> **`8080` 이 아니다.** 앱 컨테이너는 `8081`(Blue) / `8082`(Green) 로 published 되고 보안그룹에서
> 닫혀 있다 — nginx 가 `127.0.0.1` 로 프록시하기 때문이다. 따라서 부하는 nginx 를 경유해야 한다.
> `origin` 은 **DNS only(회색 구름)** 여야 한다. Cloudflare 프록시를 타면 DDoS 보호·캐싱이 개입해
> 측정이 오염된다. 보안그룹에 **443 과 8025 를 집 IP 로** 열어둘 것.

> ⚠️ **TLS 검증은 꺼진 상태로 돈다.** `origin` 은 Cloudflare 프록시를 우회하므로 nginx 의
> **Cloudflare Origin CA 인증서를 직접** 제시하는데, 이 CA 는 공개 신뢰 체인에 없다(설계상
> Cloudflare 엣지만 신뢰). 실측: 검증 ON → `HTTP 000`(핸드셰이크 실패), OFF → 정상.
> k6 는 커스텀 CA 번들을 지원하지 않아 `insecureSkipTLSVerify` 가 유일한 방법이며
> 두 스크립트의 `options` 에 기본 활성화돼 있다. 되살리려면 `-e STRICT_TLS=true`.

로컬에서 EC2 로 쏘는 구성의 주의점:

| 항목 | 판정 |
|---|---|
| 대역폭 | **문제 없음.** 요청 500B 남짓, 600 req/s 라도 300KB/s |
| 포트 고갈 | **문제 없음.** k6 는 keep-alive 를 쓰므로 커넥션 수 ≈ VU 수 |
| **응답시간 측정** | **오염됨.** 아래 참고 |
| 배출률·유실 측정 | **무관.** k6 는 XADD 까지만 관여하고 나머지는 서버 내부 |

수락 계층 응답시간은 로컬 실측 p95 가 3.7ms 인데, 가정 인터넷 → `ap-northeast-2` RTT 가
10ms 면 **측정값의 대부분이 네트워크**가 된다. 그래서 판정 기준을 서버 측 지표로 옮겼다.

```
k6 http_req_duration           → 사용자 체감 (RTT 포함)      … 참고
http_server_requests_seconds   → 서버 처리시간 (RTT 제외)    … 판정
```

teardown 리포트의 `[수락 계층 — 서버가 직접 잰 값]` 블록이 후자다.
`application.yaml` 의 `percentiles-histogram: http.server.requests` 설정이 이를 가능하게 한다.

Prometheus 에서 직접 볼 때:

```promql
histogram_quantile(0.95, sum by (le) (
  rate(http_server_requests_seconds_bucket{uri="/api/member/request-code"}[1m])
))
```

### 4. 측정 회차 전 관측 스택 정리

Grafana 는 조회 UI 일 뿐이라 측정 중에는 내려도 된다. Loki/Promtail 은 부하 시 로그량이
급증해 CPU 를 먹는다. **Prometheus 만 남길 것.**

```bash
sudo docker compose stop grafana loki promtail
```

k6 요약의 `dropped_iterations` 가 0 이 아니면 **로컬 머신이 도착률을 못 채운 것**이므로
그 회차는 무효다.

## 실행

### ⛔ 셸을 먼저 고를 것 — PowerShell 에서는 `RUN_ID` 가 조용히 잘린다

이 문서의 명령은 전부 `$(date +%s)` 를 쓴다. **Git Bash 를 권장한다** — 그대로 동작한다.

PowerShell 은 `$(date +%s)` 를 `Get-Date -Date "+%s"` 로 해석해 **실패하고, 접두사만 남긴다.**
에러는 뜨지만 k6 는 그대로 실행되므로 놓치기 쉽다. 실제로 20260902 파일럿에서 `RUN_ID=pilot-c-`
로 돌았다.

```powershell
# PowerShell 대체 문법
-e RUN_ID=pilot-c-$([int](Get-Date -UFormat %s))
```

`RUN_ID` 를 **아예 생략해도 된다** — `lib/lumo.js` 가 `__ENV.RUN_ID || String(Date.now())` 로
자동 부여한다. 다만 회차 식별이 안 되므로 접두사를 주는 편이 낫다.

**기동 로그에서 반드시 눈으로 확인한다.**

```
INFO[0000] [stress] RUN_ID=pilot-c-1756789012 rate=50/s duration=1m
                              ^^^^^^^^^^ 숫자가 붙어 있어야 한다
```

`-` 로 끝나면 즉시 중단할 것. 다음 회차에서 **같은 주소가 재생성**되어
`mail_rejected_duplicate: count==0` 임계에 걸리고 **회차가 통째로 무효**가 된다.

---

로컬 스모크(시나리오 검증용):

```bash
docker compose -f docker-compose-local.yml up -d mysql-local redis-local mailpit
```

### EC2 측정 순서 — 파일럿 먼저, 그다음 시나리오별 블록

**0. 파일럿 (C · 개선군)** — 데이터는 버린다. 목적은 **하네스 검증**이다.

```bash
# Git Bash — 이 문서의 나머지 명령도 전부 이 형태다
k6 run -e BASE_URL=https://origin.ddotg.dev -e MAILPIT_URL=http://origin.ddotg.dev:8025 \
       -e RUN_ID=pilot-c-$(date +%s) -e RATE=50 -e DURATION=1m -e DRAIN_TIMEOUT=180 k6/stress.js
```

```powershell
# PowerShell — 이하 모든 명령에서 $(date +%s) 를 아래 형태로 바꿔 쓴다
k6 run -e BASE_URL=https://origin.ddotg.dev -e MAILPIT_URL=http://origin.ddotg.dev:8025 -e RUN_ID=pilot-c-$([int](Get-Date -UFormat %s)) -e RATE=50 -e DURATION=1m -e DRAIN_TIMEOUT=180 k6/stress.js
```

개선군으로 시작하는 이유 — 이미 배포돼 있고 코드를 가장 잘 알고 있어서, 실패했을 때
**하네스 문제인지 코드 문제인지 즉시 구분된다.** 원시로 시작하면 그 구분이 안 되고,
원시는 인스턴스를 죽일 수도 있어 검증되지 않은 하네스로 시도할 이유가 없다.

확인할 것: k6 가 `origin.ddotg.dev` 에 닿는가 · Mailpit API 응답 ·
`SPRING_APPLICATION_JSON` 반영 · 배출 완료 판정 · 서버측 p95 출력.

**1~4. 본 측정 — 시나리오별로 3점을 연속 실행**

ref 별로 몰지 않고 **시나리오별로 A→B→C 를 붙여서** 돈다. 같은 시간대·같은 인스턴스 상태에서
3점이 비교되어 회차 간 드리프트가 최소화된다. 전환이 1분이라 가능한 배치다.

| 블록 | 순서 | 시나리오 | 성격 |
|---|---|---|---|
| 1 | A → B → C | S1 스파이크 | **문제 발견** (개선 증명이 아니다 — 아래 참조) |
| 2 | A → B → C | S2 스트레스 | 배출률 상한 |
| 3 | A → B → C | S4 카오스 | 유실 |
| 4 | C 전용 | S3 워커 스윕 | Little's Law |

### 회차별 권장 파라미터

배출률은 **워커 수 / 건당 소요시간**이다.

> ⛔ **아래 표의 부하 값은 낡았다 (20260902).**
>
> 이 표는 로컬 Docker Desktop 실측(15워커 · 건당 294ms → **51건/초**)을 기준으로 잡았다.
> 그런데 **EC2 실측은 건당 5.11ms** 로 약 57배 빠르다 — 로컬이 느렸던 것은 Docker Desktop 의
> WSL2/vpnkit 네트워크 스택 때문이고, EC2 는 네이티브 docker bridge 다.
>
> Little's Law 상한: `15워커 ÷ 5.11ms ≈ 2,900건/초` (SMTP 구간) · Redis 왕복 3회 포함 **≈ 2,500건/초**.
> 파일럿 CPU 평균 2.86%@50/s 를 외삽해도 포화가 1,500~2,000/s 부근이라 두 추정이 일치한다.
>
> 따라서 아래 `RATE=100` · `300` 은 **상한의 4~12%** 라 **적체가 생기지 않는다** —
> S2(배출률 상한) · S3(Little's Law) 가 빈 결과를 낸다. 실제로 파일럿은
> `워커 처리량 상한: 측정 불가` 를 냈고 VU 를 3개만 썼다.
>
> **본 측정 전에 아래 「배출 상한 캘리브레이션」 절을 먼저 돌려 값을 다시 잡을 것.**

| 시나리오 | 부하 | 지속 | `DRAIN_TIMEOUT` | 총 요청 | 부하 후 배출 |
|---|---|---|---|---:|---|
| 파일럿 | `RATE=50` | `1m` | `180` | 3,000 | 적체 없음 |
| **S1** 스파이크 | `VUS=100` | `HOLD=60s` | `120` | ~6,000 | 약 1분 |
| **S2** 스트레스 | `RATE=100` | `2m` | `300` | 12,000 | 약 2분 |
| **S3** 워커 스윕 | `RATE=300` | `1m` | `180` | 18,000 | 타임아웃 정상 |
| **S4** 카오스 | `RATE=50` | `2m` | `300` | 6,000 | — |
| **R11** api 경유 | `RATE=30` | `2m` | `300` | 3,600 | 저부하 |

**⚠️ 30초는 쓰지 말 것.** Prometheus 스크랩 간격이 5초라 30초 구간은 **데이터 포인트가 6개뿐**이다.
`rate(...[1m])` 계열 쿼리가 창을 못 채워 값이 흔들린다. 여기에 JVM 워밍업(30초면 JIT 가 C2 까지
가지 못한다)과 처리 계층의 평형 도달까지 겹친다. **최소 60초**로 잡는다.

**⚠️ `DRAIN_TIMEOUT` 을 도착률에 맞춰 조정할 것.** 기본값은 600초다.

```
적체 = (RATE × DURATION) − (배출률 × DURATION)
배출 소요 = 적체 ÷ 배출률
```

README 앞부분의 예시 `RATE=300 DURATION=5m` 는 90,000 건을 수락하고 74,700 건이 적체되어
**배출에만 약 24분**이 걸린다. 기본 타임아웃을 훌쩍 넘겨 `TIMEOUT — 배출 미완료` 로 끝난다.

**S3 는 타임아웃이 나도 정상이다.** 목적이 "전량 배출" 이 아니라 "적체가 쌓인 상태의 배출률" 이고,
`waitForDrain()` 은 타임아웃 시에도 **그 구간의 배출량 ÷ 소요시간**을 돌려주기 때문이다.
짧게 끊는 편이 스윕 전체 시간을 크게 줄인다.

**파일럿에서 `워커 처리량 상한: 측정 불가` 가 뜨는 것은 정상이다.** `RATE=50` 은 배출률(51/초)보다
낮아 적체가 생기지 않는다. 파일럿의 목적은 하네스 검증이므로 그대로 두면 된다.
본 측정에서 이 메시지가 뜨면 `RATE` 를 올려야 한다는 신호다.

### R11 — Cloudflare 경유 1회차 (개선군 전용)

R1~R10 은 전부 `origin`(Cloudflare 우회)으로 쏜다. 여기에 **개선군에 대해 딱 한 번**
`BASE_URL` 만 바꿔 같은 시나리오를 돈다.

```bash
k6 run -e BASE_URL=https://api.ddotg.dev -e MAILPIT_URL=http://origin.ddotg.dev:8025        -e RUN_ID=r11-c-api-$(date +%s) -e RATE=30 -e DURATION=2m k6/stress.js
```

**개선군만 재는 이유** — Cloudflare 오버헤드는 **경로의 속성이지 앱 코드의 속성이 아니다.**
세 점 모두 같은 nginx·같은 Cloudflare 를 지나므로 같은 값이 세 번 나온다.

판정 두 가지:

| 볼 것 | 의미 |
|---|---|
| k6 측 p95 차이 (R11 − 개선군 origin 회차) | Cloudflare 왕복이 **사용자 체감**에 얹는 비용 |
| **서버측 p95 가 같은가** | Cloudflare 가 요청을 변형·지연시키지 않는가 |

두 번째가 어긋나면 그쪽이 더 중요한 발견이다 — 엣지가 버퍼링하거나 커넥션을 합치면
**서버 입장에서 도착 패턴 자체가 달라진다.**

⚠️ **반드시 저부하로.** 높은 rps 로 치면 Cloudflare 가 방어에 들어가 측정이 아니라 차단 테스트가 된다.
반대로 저부하에서도 challenge 가 뜬다면 그것도 결과다 — *"이 API 는 실서비스에서 Cloudflare 설정
조정이 필요하다"* 는 발견이 된다.
`MAILPIT_URL` 은 그대로 `origin` 을 쓴다. Mailpit 조회는 측정 대상이 아니다.

**회차 사이 초기화 (빠뜨리면 다음 회차가 오염된다)**

```bash
sudo docker compose stop Blue Green                    # 앱을 먼저 멈춘다
sudo docker exec Lumo_Redis redis-cli FLUSHALL         # List email_queue ↔ Stream mail:stream 잔재
curl -s -X DELETE http://localhost:8025/api/v1/messages
sudo docker restart Lumo_Mailpit                       # 인메모리 저장분 회수
sudo docker compose stop grafana loki promtail         # Prometheus 만 남긴다
```

앱을 먼저 멈추는 이유 — C 의 워커들이 동시에 NOGROUP 을 만나 그룹 재생성 경합 로그를 쏟는다.
결과는 정상이지만 진단이 어려워진다.

**회차 전환 직후 확인 (매번)**

```bash
sudo docker inspect Lumo_Blue --format '{{.Config.Image}}'      # 의도한 SHA
sudo docker exec Lumo_Blue printenv SPRING_APPLICATION_JSON     # 계측 주입
sudo docker exec Lumo_Blue printenv SPRING_MAIL_PORT            # 1025
curl -s localhost:8081/actuator/prometheus | grep -c http_server_requests_seconds_bucket
sha256sum ~/lumo/docker-compose.yml ~/lumo/scripts/deploy.sh    # 3점 내내 동일해야 한다
```

마지막 줄이 핵심이다. **해시를 기록하면 "인프라가 상수였다" 를 보고서에서 증명할 수 있다.**

### S1 — 스파이크 (100/200/400/600 VU 계단)

```bash
k6 run -e RUN_ID=s1-$(date +%s) k6/spike.js
```

단일 레벨만 볼 때:

```bash
k6 run -e RUN_ID=s1-400 -e VUS=400 -e HOLD=60s k6/spike.js
```

> `HOLD` 를 30초 이하로 낮추지 말 것 — 아래 「회차별 권장 파라미터」 참조.

### S2 — 스트레스 (고정 도착률)

```bash
k6 run -e RUN_ID=s2-$(date +%s) -e RATE=300 -e DURATION=5m k6/stress.js
```

`constant-arrival-rate` 를 쓰는 이유 — VU 기반(closed model)은 서버가 느려지면 VU 가 응답을
기다리느라 요청률이 함께 떨어진다(coordinated omission). 그러면 처리량 상한이 영원히 보이지 않는다.

**배출률 상한을 재려면 도착률이 처리 능력을 넘겨 백로그가 실제로 쌓여야 한다.**
`워커 처리량 상한: 측정 불가` 가 나오면 `RATE` 를 올릴 것.

### S3 — 워커 스윕 (G-4)

`mail.worker.count` 를 바꿔가며 S2 를 반복한다.

EC2 에서 워커 수를 바꿔 재기동한 뒤, 로컬에서 k6 를 쏜다.

```bash
# [EC2] 워커 수를 바꿔 Blue 재생성
cd ~/lumo && MAIL_WORKER_COUNT=30 sudo -E docker compose up -d --force-recreate Blue
docker exec Lumo_Blue printenv MAIL_WORKER_COUNT      # 30 이 찍혀야 한다
```

```bash
# [로컬] 해당 회차 측정
k6 run -e BASE_URL=https://origin.ddotg.dev -e MAILPIT_URL=http://origin.ddotg.dev:8025        -e RUN_ID=s3-w30-$(date +%s) -e RATE=300 -e DURATION=1m k6/stress.js
```

> ⚠️ **`printenv` 확인을 건너뛰지 말 것 (D-6).** 예전에는 `MAIL_WORKER_COUNT` 가
> compose 의 `environment:` 에 선언돼 있지 않아 **셸에서 주입해도 컨테이너에 도달하지 않았다.**
> 에러도 경고도 없이 전 회차가 기본값 15 로 돌아, 결과가 비슷하게 나오면 **"포화" 로 오해**하기 딱 좋다.
> 지금은 선언돼 있지만, 회차마다 실제 반영 여부를 눈으로 확인하는 편이 안전하다.

**Little's Law 로 검증한다.**

```
필요 워커 수 = 목표 처리량(건/초) × 건당 소요시간(초)
건당 소요시간 = mail_send_duration_seconds_sum / mail_send_duration_seconds_count
```

실측 곡선과 이 계산이 일치하면 워커 수에 근거가 생긴다. 어긋나면 그 지점이 포화점이다.

### S4 — 카오스 (유실 0 증명)

부하 도중 앱을 강제 종료하고, 재기동 후 회수되는지 본다.

```bash
k6 run -e RUN_ID=s4-$(date +%s) -e RATE=100 -e DURATION=2m k6/stress.js &
sleep 30
docker kill Lumo_Blue        # ⚠️ 반드시 kill. stop 은 stop_grace_period 60s 가 개입해
                             #    "정상 종료" 가 되어 장애 주입이 되지 않는다
sleep 10
docker start Lumo_Blue
wait
```

판정:

| 지표 | 개선 전 (List + BRPOP) | 개선 후 (Streams + XACK) |
|---|---|---|
| Mailpit 수신 | k6 200 응답 수보다 **적음** (유실) | k6 200 응답 수 **이상** |
| 회복 경로 | 없음 | `MailRecoveryScheduler` 가 60초 내 회수 |

## 배출 상한 캘리브레이션 — 본 측정 전에 한 번

파일럿이 `워커 처리량 상한: 측정 불가` 를 냈다. `RATE=50` 이 배출률보다 한참 낮아 적체가 아예
생기지 않았기 때문이다. **상한을 모르면 S2·S3 파라미터를 정할 수 없다.**

### 원리

```
적체 = (도착률 − 배출률) × 지속시간
```

`stress.js` 는 `constant-arrival-rate`(open model)라 **서버가 느려져도 도착률을 유지**한다.
VU 기반(closed model)은 서버가 느려지면 VU 가 응답을 기다리느라 요청률이 함께 떨어져
(coordinated omission) 상한이 영원히 보이지 않는다. 그래서 «들어오는 양 > 나가는 양» 을
강제로 만들 수 있는 것은 도착률 고정 방식뿐이다.

상한이 미지수이므로 도착률을 계단으로 올려 **`★ 실질 백로그 (파생)` 패널이 처음 0 을
벗어나는 지점**을 찾는다. 그 지점이 배출률 상한이다.

### 실행

```bash
K6="k6 run -e BASE_URL=https://origin.ddotg.dev -e MAILPIT_URL=http://origin.ddotg.dev:8025"
V="-e PRE_VUS=200 -e MAX_VUS=2000"

$K6 $V -e RUN_ID=cal-c-500-$(date +%s)  -e RATE=500  -e DURATION=1m -e DRAIN_TIMEOUT=180 k6/stress.js
$K6 $V -e RUN_ID=cal-c-1000-$(date +%s) -e RATE=1000 -e DURATION=1m -e DRAIN_TIMEOUT=300 k6/stress.js
$K6 $V -e RUN_ID=cal-c-2000-$(date +%s) -e RATE=2000 -e DURATION=1m -e DRAIN_TIMEOUT=600 k6/stress.js
```

> ⛔ **`PRE_VUS` · `MAX_VUS` 를 반드시 명시한다.**
> `stress.js` 의 기본값은 `preAllocatedVUs = max(50, RATE)` · `maxVUs = RATE × 10` 이다.
> `RATE=2000` 이면 **VU 2,000개를 선할당하고 상한이 20,000** 이 된다.
> 실제 필요량은 `RATE × 지연(≈10ms)` = **20개** 수준이므로, 그대로 두면
> 측정 대상이 아니라 **부하 생성기(노트북)가 먼저 죽는다.**

`DURATION` 은 60초 미만 금지 — Prometheus 5초 스크랩으로 `rate()[1m]` 창을 채워야 하고
JVM 이 C2 까지 워밍업할 시간도 필요하다.

### 고부하에서 새로 생기는 한계 3가지

| 항목 | 내용 |
|---|---|
| **Mailpit 저장량** | `RATE=2000 × 60s = 120,000통`. 회차마다 `DELETE /api/v1/messages` + 컨테이너 재시작으로 반드시 비운다 |
| **부하 생성기 대역폭** | 파일럿 실측 응답 ≈1.4KB/건 → 2,000/s 면 **≈22Mbps 수신**. 회선이 못 받치면 그 회차는 무효 |
| **`dropped_iterations`** | 0 이 아니면 k6 가 도착률을 못 지킨 것이다. 그 회차는 폐기 |

### 분기 — 적체가 끝내 안 생기면

`dropped_iterations` 나 수락 p95 가 **먼저** 무너지면 병목은 워커가 아니라 **수락 계층·네트워크**다.
그때는 부하를 더 올리지 말고 **`MAIL_WORKER_COUNT` 를 1~3 으로 낮춰 상한을 끌어내린다.**

규칙(«C 의 S2 는 워커 15»)은 그대로 유지하고 **S3 스윕에서만** 적용할 것 —
S2 는 B 의 하드코딩 15 와 맞춰야 «큐 구조»만의 비교가 되기 때문이다.

---

## 3점 대조군

비교는 **원시 → Redis List+BRPOP → Redis Streams** 세 점으로 한다.

| 점 | ref | 성격 | S4 기대 결과 |
|---|---|---|---|
| **A. 원시** | `4f38b75` | 큐 없음. `SimpleAsyncTaskExecutor`(무제한 스레드) | **전량 유실** |
| **B. List** | `8f48f0e` | Redis List `email_queue`, `BRPOP`(at-most-once), 워커 15 하드코딩 | 인플라이트 유실 |
| **C. Streams** | `develop` HEAD | Streams + XACK + PEL 회수 + DLQ | **유실 0** |

> ⛔ **`fa987ec` 를 쓰지 말 것.** 예전 문서가 "Streams 도입 직전 커밋" 으로 지목했으나,
> 그 커밋의 `MailQueueMetrics.java` 는 `MailStream` 을 import 하는데 그 클래스는
> **다음 커밋(`4a2f563`)에서 추가된다.** 컴파일 자체가 되지 않아 배포할 수 없다.
>
> ⚠️ **`3ff8e02` 가 아니라 `4f38b75`** 를 쓴다. `3ff8e02` 에는
> `management.metrics.tags.application` 이 없어 Grafana 의 `$application` 변수가 비고
> **모든 패널이 빈 화면**이 된다. `4f38b75` 는 그 태그만 추가한 다음 커밋이라 원시 구조는 동일하다.

### 대조군 이미지 굽기 (한 번만)

회차마다 CI 로 굽지 않는다. **굽기와 돌리기를 분리**해야 배포 자산이 측정 내내 상수로 유지되고,
회차 전환이 10분에서 1분으로 줄어든다.

```bash
git worktree add ../lumo-ctrl-raw  4f38b75
git worktree add ../lumo-ctrl-list 8f48f0e

docker buildx build --platform linux/arm64 --push \
  -t jijysun/lumo:$(git rev-parse 4f38b75) ../lumo-ctrl-raw
docker buildx build --platform linux/arm64 --push \
  -t jijysun/lumo:$(git rev-parse 8f48f0e) ../lumo-ctrl-list
```

`Dockerfile` 의 빌더 스테이지가 `--platform=$BUILDPLATFORM` 고정이라 **Gradle 컴파일은 로컬
네이티브로 돌고** QEMU 는 JRE 레이어에만 쓰인다. arm64 빌드지만 느리지 않다.
**이 빌드가 곧 컴파일 검증**이므로 별도 확인 단계가 필요 없다.

### 회차 전환 (EC2, 1분)

```bash
cd ~/lumo
sudo docker pull jijysun/lumo:<SHA>
sudo sed -i "s|^LUMO_TAG=.*|LUMO_TAG=<SHA>|" .env
sudo bash ./scripts/deploy.sh
```

> ⛔ **측정 기간 중 워크플로 dispatch 금지.** 대조군 ref 로 dispatch 하면 대조군의 낡은
> `docker-compose.yml` 이 scp 되어 계측 백포트(`SPRING_APPLICATION_JSON`)가 사라진다.
> 회차 전환은 위 3줄로만 한다.

**전환 직후 확인 목록** (하나라도 어긋나면 그 회차는 돌리지 말 것)

```bash
C=Lumo_Green; P=8082                                             # 활성 색상에 맞출 것
sudo docker inspect $C --format '{{.Config.Image}}'              # 의도한 SHA
sudo docker inspect $C --format '{{.State.ExitCode}}'            # 직전 컨테이너: 137 이면 SIGKILL (D-19)
sudo docker exec $C printenv SPRING_APPLICATION_JSON             # 계측 주입 (두 타이머 모두)
sudo docker exec $C printenv MAIL_SENDER_HOST                    # mailpit ← 실 SMTP 면 즉시 중단
curl -s localhost:$P/actuator/prometheus | grep -c mail_send_duration_seconds_bucket   # > 0
sha256sum ~/lumo/docker-compose.yml ~/lumo/scripts/deploy.sh     # 3점 내내 동일해야 한다
```

> `sha256sum` 은 **EC2 에서만** 대조할 것. Windows 로컬 체크아웃은 `core.autocrlf=true` 라
> CRLF 가 되어 절대 일치하지 않는다. 로컬에서 보려면 `tr -d '\r' < docker-compose.yml | sha256sum`.

그리고 k6 를 쏘기 직전, **기동 로그의 `RUN_ID` 에 숫자가 붙었는지 눈으로 본다**
(위 「셸을 먼저 고를 것」 참조). `-` 로 끝나면 중단 — 다음 회차가 통째로 무효가 된다.

### ⚠️ S1 은 개선을 증명하지 않는다 — 문제를 드러내는 시나리오다

세 점의 **수락 경로**를 코드로 확인한 결과다.

| ref | 요청 스레드가 하는 일 | Redis 왕복 |
|---|---|---|
| A 원시 | `get(email)` → 비동기 제출 (SMTP 는 다른 스레드) | **1회** |
| B List | `setIfAbsent()` → `leftPush()` | 2회 |
| C Stream | `setIfAbsent()` → `XADD` | 2회 |

**원시도 SMTP 를 요청 스레드에서 하지 않는다.** 따라서 저부하 S1 의 수락 p95 는
**원시가 오히려 빠르게 나온다**(Redis 왕복이 1회뿐이라서). "비동기화로 응답시간이 좋아졌다" 는
서사는 이 3점 대조에서 성립하지 않는다.

실제 차별점은 이쪽이다.

| 축 | 원시 | List | Streams |
|---|---|---|---|
| 수락 p95 (저부하) | 가장 빠름 | 중간 | 중간 |
| **동시성 상한** | **없음** | 15 | 15 (환경변수로 조정) |
| **고부하 거동** | **스레드 폭증 → 붕괴** | 백프레셔로 적체 | 백프레셔로 적체 |
| **유실 (S4)** | **전량** | 인플라이트 | **0** |
| 관측 | `mail.queue.*` 시계열 없음 | depth 만 | depth + pending + dlq + recovery |
| 운영 조정 | 불가 | 재빌드 필요 | 환경변수 |

→ 결론은 **"응답시간을 희생하지 않으면서(+Redis 1왕복) 동시성 상한·유실·관측·조정 가능성을 얻었다"**
이지 "빨라졌다" 가 아니다. 트레이드오프를 드러내는 쪽이 근거로 더 강하다.

> 원시(`4f38b75`)의 `mail.queue.depth` 는 "항상 0" 이 아니라 **아예 미등록**이다.
> `MailQueueMetrics` 의 `@PostConstruct` 가 주석 처리돼 있다. Grafana 에 "No data" 로 뜨는 것이
> 정상이며, **"원시 구조는 적체를 관측할 대상 자체가 없다"** 가 정확한 서술이다.

### ⛔ 원시(A) 회차는 인스턴스를 죽일 수 있다

`SimpleAsyncTaskExecutor` 는 동시성 제한 없이 **태스크마다 새 플랫폼 스레드**를 만든다.
게다가 대조군 `application.yaml` 에는 **SMTP 타임아웃이 없다**(무한 대기).

- **낮은 `RATE` 부터 계단으로 올리고** `jvm_threads_live_threads` 를 감시. 임계(3,000) 초과 시 중단
- 컨테이너 메모리 상한으로 **호스트가 아니라 컨테이너가 죽게** 한다 (3점 모두 동일 적용):
  ```bash
  sudo docker update --memory 6g --memory-swap 6g Lumo_Blue
  ```
- **붕괴 자체가 결과물이다.** 직전/직후의 로그 · 스레드 그래프 · `RestartCount` 를 캡처할 것

### 계측 백포트 — 대조군에도 서버측 p95 를 뽑는 방법

대조군에는 `management.metrics.distribution` 이 없어 `http_server_requests_seconds_bucket`
시계열이 **아예 생기지 않는다.** `mail.send.result` 같은 카운터와 `mail.send.duration` 의
**합계·건수·최대값**은 대조군에도 있으므로 **유실·배출률·평균 소요시간 측정은 그대로 가능**하다.
부족한 것은 **백분위 두 가지**다.

| 타이머 | 대조군 상태 | 백포트 후 |
|---|---|---|
| `http.server.requests` | `_bucket` 없음 → **수락 p95 불가** | ✅ 1ms\~2s 격자 |
| `mail.send.duration` | `TYPE summary` (`_count`·`_sum`·`_max` 만) → **배출 p95 불가** | ✅ 1ms\~10s 격자 |

`mail.send.duration` 은 세 버전 모두 `Timer.builder("mail.send.duration")` 로 **같은 이름에
등록돼 있다**(확인 완료). 히스토그램 스위치만 꺼져 있어 평균으로만 볼 수 있었는데,
**배출 지연의 꼬리는 평균에 안 나타난다** — 워커가 몇 건에서 오래 물렸는지가 p95 에서 드러난다.

상한을 `10s` 로 둔 근거는 `application.yaml` 의 JavaMail read timeout 이 10s 라는 것이다.
그 지점까지 격자 안에 들어와야 꼬리가 `+Inf` 한 칸으로 뭉개지지 않는다.
(원시 대조군에는 SMTP 타임아웃 설정이 아예 없어 더 길어질 수 있으나, 그때는 `+Inf` 로
몰리는 것 자체가 결과다)

이건 대조군 소스를 고치지 않고 `docker-compose.yml` 의 `SPRING_APPLICATION_JSON` 으로 주입한다.
Micrometer 자체는 대조군에도 들어 있고 스위치만 꺼져 있었을 뿐이다.

**⚠️ `maximum-expected-value` 는 필수다.** 실측으로 확인했다:

| 설정 | `le` 버킷 수 | 최대 `le` |
|---|---:|---:|
| `application.yaml` 만 (max 2s) | 51 | 2.0 |
| JSON 으로 max 4s 주입 | 57 | 4.0 |
| JSON 에 실제값 2s 주입 | 51 | 2.0 (yaml 과 `diff` 빈 출력) |

값이 다르면 **`le` 라벨 집합 자체가 달라져** `quantileFromBuckets()` 가 서로 다른 격자 위에서
보간한다. 3점 p95 비교가 무의미해진다.

**회차마다 격자가 같은지 확인할 것:**

```bash
P=8081   # 활성 색상에 맞출 것

# 두 타이머 모두 버킷이 생겼는지 (0 이면 측정 금지)
curl -s localhost:$P/actuator/prometheus | grep -c http_server_requests_seconds_bucket
curl -s localhost:$P/actuator/prometheus | grep -c mail_send_duration_seconds_bucket

# le 격자 채취 — 회차마다 남긴다
curl -s localhost:$P/actuator/prometheus | grep http_server_requests_seconds_bucket \
  | grep -o 'le="[^"]*"' | sort -u > le_http_<ref>.txt
curl -s localhost:$P/actuator/prometheus | grep mail_send_duration_seconds_bucket \
  | grep -o 'le="[^"]*"' | sort -u > le_mail_<ref>.txt

diff le_http_A.txt le_http_C.txt     # 반드시 빈 출력
diff le_mail_A.txt le_mail_C.txt     # 반드시 빈 출력
```

> ⚠️ `mail_send_duration_seconds_bucket` 이 **0 인데 회차를 돌리면**, 그 회차만 배출 p95 가
> 빠져 3점 비교표에 구멍이 난다. 해당 회차는 다시 돌려야 하므로 **전환 직후 반드시 확인**한다.

## 결과 읽는 법

teardown 이 출력하는 블록이 처리 계층의 결과다.

```
발송 성공(회차)   회차 동안 실제로 나간 건수 (누적 카운터의 델타)
실패 일시/영구    TRANSIENT / PERMANENT 분류 결과
DLQ 이관          재시도를 포기한 건수
미확인(XPENDING)  아직 XACK 되지 않은 건수. 0 이 아니면 처리 중이거나 막힌 것
Mailpit 수신      실제 도착 건수 ← 유실 판정의 기준
워커 처리량 상한  백로그를 빼내는 속도. 도착률의 방해가 없는 순수 처리 능력
```

3-way 비교로 유실을 판정한다.

```
mail_accepted (k6 가 200 받은 수)  <=  Mailpit 수신
```

at-least-once 이므로 **재시도로 인한 중복은 허용**된다. 반대로 Mailpit 수신이 더 적으면 유실이다.

### 적체는 Grafana 의 `★ 실질 백로그 (파생)` 로만 본다

teardown 의 `미확인(XPENDING)` 은 적체가 아니다 — 워커가 `count(1)` 로 1건씩 읽으므로
**워커 수(기본 15)가 상한**이다. 수천 건이 밀려 있어도 이 값은 15 를 못 넘는다.
`XCLAIM` 은 기존 PEL 항목의 소유자만 바꿀 뿐 새 항목을 만들지 않으므로 회수도 상한을 올리지 않는다.

대시보드의 `mail_queue_depth` 도 적체가 아니다 — **회차마다 재는 대상이 다르다.**

| ref | 재는 것 | 부하 후 |
|---|---|---|
| A 원시 | 쓰지 않는 `email_queue` 의 `LLEN`. 없는 키라 **항상 0** | 0 |
| B List | 진짜 대기열 | **0 으로 복귀** |
| C Stream | `XLEN` 누적. `XACK` 해도 줄지 않는다 | **`MAX_LEN`(10,000)까지 남음** |

C 를 그대로 B 옆에 놓으면 **적체가 0 인데도 개선군이 더 나빠 보인다.**
20260902 파일럿이 그 사례다 — PEL 0 · 잔여 배출량 0 · Mailpit 3,000 수신으로 적체가 없었는데
`mail_queue_depth` 는 3,000 에서 끝까지 평평했다.

진짜 백로그인 `XINFO GROUPS` 의 `lag`(Redis 7.0+)는 **현재 미계측**이다.
그래서 3점 비교는 두 카운터의 차(수락 − 배출)로 만든 파생 지표를 쓴다.

## 로컬 스모크 실측 (2026-08-24, Docker Desktop / Windows)

| 회차 | 워커 수 | 건당 소요 | Little's Law 예측 | 실측 배출률 | 오차 |
|---:|---:|---:|---:|---:|---:|
| 1 | 3 | 174.7 ms | 17.2 건/초 | 18.4 건/초 | 7.0% |
| 2 | 15 | 293.5 ms | 51.1 건/초 | 50.2 건/초 | 1.8% |
| 3 | 15 | 946.1 ms | 15.9 건/초 | 16.3 건/초 | 2.5% |

### 읽는 법 1 — 모델은 맞다

```
배출률 = 워커 수 / 건당 소요시간
```

세 회차 모두 오차 7% 이내다. **워커 수를 정하는 근거로 Little's Law 를 쓸 수 있다.**

```
필요 워커 수 = 목표 처리량(건/초) × 건당 소요시간(초)
```

### 읽는 법 2 — 15 는 이미 수확체감 구간이다

회차 1 → 2 에서 워커를 5배 늘렸는데 처리량은 2.7배에 그쳤다.
건당 소요가 174.7ms → 293.5ms 로 **1.68배 늘었기 때문**이다.
워커가 동시에 SMTP 커넥션을 여닫으면서 생긴 경합이며, G-4 가 지적한 "근거 없는 15" 의 실체다.

### 읽는 법 3 — 로컬 절대값은 포트폴리오에 쓸 수 없다

회차 2 와 3 은 **같은 워커 수(15)인데 건당 소요가 3.2배 차이**난다.
Docker Desktop(WSL2) 환경의 회차 간 편차이며, 절대값을 인용하면 방어할 수 없다.
**수치는 EC2 회차로 다시 측정할 것.** 로컬은 시나리오 검증용이다.

### 부수 발견 — 메일 1통당 SMTP 커넥션 1개

`JavaMailSenderImpl.send(MimeMessage...)` 는 varargs 이고,
`doSend(MimeMessage[], Object[])` 가 **배열 전체에 대해 `connectTransport()` 를 한 번** 호출한다.
현재 코드는 `send(msg)` 로 한 건씩 부르므로 **메일마다 커넥션을 새로 연다.**

건당 소요가 로컬 Mailpit 상대로도 175~946ms 나 나오는 주된 이유이자,
회차 간 편차가 큰 이유이기도 하다(커넥션 수립 비용은 환경에 민감하다).

원리상 워커가 `XREADGROUP COUNT n` 으로 여러 건을 모아 읽고
`send(msg1, ..., msgN)` 으로 보내면 커넥션 1개를 재사용한다.

**⛔ 그러나 지금 구현하지 말 것.** G-23(SES 전환)이 계획에 있고, 전환 시
`doSendEmail` 의 SMTP 조립·발송은 통째로 사라진다. SES 는 배치도 `SendBulkEmail` 이라는
별도 API 라 SMTP 배치 코드가 그대로 버려진다. 게다가 배치는 **XACK 단위를 건 단위에서
묶음 단위로 바꾸므로** A-5·A-6 에서 만든 유실 방지·DLQ 분기를 전부 손봐야 한다 —
수명이 짧은 코드에 그만한 리스크를 쓸 이유가 없다.

측정 결과 자체는 버리지 않는다. **"병목이 워커 수가 아니라 커넥션 수립에 있었다"** 는
규명은 그대로 남고, 오히려 SES 전환의 근거가 된다.

### 전송 수단이 바뀌면 무엇이 남는가

| 요소 | G-23(SES) 전환 시 |
|---|---|
| `doSendEmail` SMTP 조립 + `mailSender.send` | **사라짐** — SES SDK 호출로 대체 |
| `MailFailureClassifier` 의 SMTP 코드 판정 | **재작성** — 단 TRANSIENT/PERMANENT 구조는 유지 |
| Streams + XACK + PEL 회수 | **그대로** |
| DLQ + 재시도 상한 | **그대로** |
| 워커 풀 + Little's Law | **그대로** — 입력값이 SMTP RTT → SES API 지연으로 바뀔 뿐 |
| 워커 수의 근거 | **오히려 개선** — SES 계정 초당 전송률에서 역산 가능 (G-4 의 답) |

바뀌는 것은 **전송 어댑터 한 겹**이고, 신뢰성 계층(큐·재시도·DLQ·계측)은 그대로 살아남는다.

### 그 외

같은 회차에서 수락 계층은 300 건/초에 서버 측 p95 3.8ms / p99 5.5ms 로 여유가 있었고,
유실은 0 이었다 (수락 4500 / Mailpit 수신 4500 / XPENDING 0 / DLQ 0).
