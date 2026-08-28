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
| **S1** | 스파이크 | 급증에도 수락 응답시간이 평탄한가 | 수락 |
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

**k6 를 앱과 같은 EC2 에서 돌리지 말 것.** t4g.xlarge 는 4 vCPU 인데 이미
Blue + Green + Prometheus + Grafana + Loki + Promtail + Mailpit 이 올라간다.
여기에 k6 를 얹으면 측정 도구가 측정 대상의 CPU 를 빼앗아 결과가 무의미해진다.

```bash
k6 run -e BASE_URL=http://<EC2>:8080 -e MAILPIT_URL=http://<EC2>:8025 ...
```

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

```bash
# 인프라
docker compose -f docker-compose-local.yml up -d mysql-local redis-local mailpit
```

### S1 — 스파이크 (100/200/400/600 VU 계단)

```bash
k6 run -e RUN_ID=s1-$(date +%s) k6/spike.js
```

단일 레벨만 볼 때:

```bash
k6 run -e RUN_ID=s1-400 -e VUS=400 -e HOLD=60s k6/spike.js
```

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

```bash
for W in 3 5 10 15 20 30; do
  MAIL_WORKER_COUNT=$W  # 앱 재기동 필요
  k6 run -e RUN_ID=s3-w$W-$(date +%s) -e RATE=300 -e DURATION=1m k6/stress.js
done
```

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
docker kill Lumo_Blue        # 또는 bootRun 프로세스 강제 종료
sleep 10
docker start Lumo_Blue
wait
```

판정:

| 지표 | 개선 전 (List + BRPOP) | 개선 후 (Streams + XACK) |
|---|---|---|
| Mailpit 수신 | k6 200 응답 수보다 **적음** (유실) | k6 200 응답 수 **이상** |
| 회복 경로 | 없음 | `MailRecoveryScheduler` 가 60초 내 회수 |

대조군 이미지는 Streams 도입 직전 커밋에서 빌드한다.

```bash
git checkout fa987ec -- .    # 또는 해당 커밋으로 별도 빌드
```

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
