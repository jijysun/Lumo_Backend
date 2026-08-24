# 부하 테스트 (k6)

메일 인증 파이프라인의 부하 테스트. 시나리오는 코드로만 관리한다 — 재현 가능해야 결과를 방어할 수 있다.

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

같은 머신에서 돌리면 k6 가 쓰는 CPU 만큼 앱이 못 쓴다. 로컬 스모크는 괜찮지만
공식 회차는 별도 인스턴스에서 `-e BASE_URL=http://<서버>:8080` 으로 쏠 것.

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

| 워커 수 | 건당 소요 | Little's Law 예측 | 실측 배출률 | 오차 |
|---:|---:|---:|---:|---:|
| 3 | 174.7 ms | 17.2 건/초 | 18.4 건/초 | 7.0% |
| 15 | 293.5 ms | 51.1 건/초 | 50.2 건/초 | 1.8% |

**워커를 5배 늘렸는데 처리량은 2.7배에 그쳤다.** 건당 소요가 175ms → 294ms 로 늘어난 것이 원인이며,
15 워커가 동시에 SMTP 커넥션을 여닫으면서 서버 쪽 경합이 생긴 결과다.
즉 **15 라는 숫자는 이미 수확체감 구간에 들어와 있다** — G-4 가 지적한 "근거 없는 15" 의 실체다.

같은 회차에서 수락 계층은 300 건/초에 p95 34.6ms 로 여유가 있었고,
유실은 0 이었다 (수락 4500 / Mailpit 수신 4500 / XPENDING 0 / DLQ 0).

⚠️ 위 수치는 **로컬 Docker 기준**이다. EC2(t4g.xlarge, arm64)에서는 CPU·네트워크가 달라 절대값이
바뀐다. 포트폴리오에 싣는 값은 서버 회차로 다시 측정할 것.
