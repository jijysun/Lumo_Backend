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

## 전체 순서 — 문서를 읽는 순서와 실행하는 순서는 다르다

이 문서는 주제별로 묶여 있어 위에서 아래로 읽으면 순서가 헷갈린다. **실행 순서는 아래가 정본이다.**

| # | 무엇을 | 어디서 | 몇 회차 | 참조 절 |
|---|---|---|---:|---|
| ~~**0**~~ | ~~대조군 이미지 굽기·푸시 (A·B)~~ | 🖥 로컬 | — | ✅ **20260903 완료** |
| ~~**1**~~ | ~~**파일럿** (C) — 하네스 검증~~ | 🖥 로컬 | 1 | ✅ **20260902 15:30 완료** |
| ~~**2**~~ | ~~**배출 상한 캘리브레이션** (C)~~ | 🖥 로컬 | 2 | ✅ **20260903 14:04 완료** |
| ~~**3**~~ | ~~**파라미터 재결정**~~ | 📝 | — | ✅ **20260903 확정** |
| **4** | **본 측정** — 블록 1~4 | 🖥+☁ | **10** | 아래 「블록이란」 |
| **5** | R11 — Cloudflare 경유 (C 전용) | 🖥 로컬 | 1 | [R11](#r11--cloudflare-경유-1회차-개선군-전용) |
| **6** | 데이터 반출 (`le` diff · 해시 · 로그) | ☁ EC2 | — | [계측 백포트](#계측-백포트--대조군에도-서버측-p95-를-뽑는-방법) |

<details>
<summary><b>✅ 0~3 완료 요약 (20260902~0903)</b> — 펼치기</summary>

| # | 완료 | 결과 |
|---|---|---|
| **0** | 20260903 | `4f38b75`(A) · `8f48f0e`(B) arm64 214MB 빌드 → Docker Hub 푸시 |
| **1** | 20260902 15:30 | `RATE=50 · 1m`. 하네스 5개 항목 전부 통과(경로 · Mailpit · 계측 주입 · 배출 판정 · 서버측 p95). 유실 0. **`워커 처리량 상한: 측정 불가`** — 부하가 상한보다 낮아 적체 미발생 |
| **2** | 20260902 17:58<br>20260903 14:04 | `RATE=500` → 배출 상한 **461건/초** 확보 · 백로그 9,712 · 유실 0<br>`RATE=1000` → 링버퍼 상한 도달 · **약 32,900건 트리밍 유실**. 상한을 찾았으므로 사다리 종료(2000 불필요) |
| **3** | 20260903 | 계획서의 «51건/초»(로컬 Docker Desktop) 폐기. **EC2 실측 340/s 기준**으로 S1~S4 재산정 |

**2 에서 얻은 가장 중요한 것 — 배출 상한은 상수가 아니다.**
건당 소요가 저부하 5.11ms → 1000rps 부하 시 28.6ms 로 **5.6배** 늘어난다.
워커가 Tomcat 과 CPU 를 다투기 때문이며, 그래서 «배출 상한 N건/초» 는 **부하 조건과 함께** 적어야 한다.

</details>

**1·2·5 는 C(개선군) 한 버전에서만 돈다.** 이미 배포돼 있으므로 **이미지 전환이 없다.**
회차마다 이미지를 바꾸는 것은 **4번(본 측정)뿐**이다.

### 블록이란 — A·B·C 는 «앱 버전», 블록은 «시나리오 묶음»

**A · B · C 는 배포되는 앱 버전(3점 대조군)이다.** 부하 종류가 아니다.

| 점 | ref | 무엇 |
|---|---|---|
| **A** | `4f38b75` | 원시 — 큐 없음 |
| **B** | `8f48f0e` | Redis List + BRPOP |
| **C** | `develop` | Redis Streams (개선군) |

**블록 = 같은 시나리오를 세 버전에 연속으로 돌리는 묶음.** ref 별로 몰지 않고 시나리오별로 묶는
이유는, 같은 시간대·같은 인스턴스 상태에서 3점이 비교되어 **회차 간 드리프트가 최소화**되기 때문이다.
전환이 1분이라 가능한 배치다.

그래서 본 측정 10회차의 실제 순서는 이렇게 된다.

| 회차 | 블록 | 배포 버전 | 시나리오 |
|---:|---:|---|---|
| 1 | 1 | **A 로 전환** | S1 스파이크 |
| 2 | 1 | **B 로 전환** | S1 스파이크 |
| 3 | 1 | **C 로 전환** | S1 스파이크 |
| 4 | 2 | **A 로 전환** | S2 스트레스 |
| 5 | 2 | **B 로 전환** | S2 스트레스 |
| 6 | 2 | **C 로 전환** | S2 스트레스 |
| 7 | 3 | **A 로 전환** | S4 카오스 |
| 8 | 3 | **B 로 전환** | S4 카오스 |
| 9 | 3 | **C 로 전환** | S4 카오스 |
| 10 | 4 | C 유지 | S3 워커 스윕 (**C 전용**) |

> S3 가 C 전용인 이유 — A 는 워커가 없고, B 는 워커 15가 하드코딩이라 재빌드해야 바뀐다.
> **그 사실 자체가 개선 항목이다.**

### 한 회차의 고정 절차 (10회 반복)

```
① [☁ EC2] 회차 사이 초기화   ← 앱 정지 → Redis FLUSHALL → Mailpit 비우기
② [☁ EC2] 회차 전환          ← A: 이미지가 바뀐다  → pull → .env 교체 → deploy.sh
                               B: 같은 이미지     → docker compose up -d <현재 색>
③ [☁ EC2] 관측 스택 정리     ← stop grafana loki promtail  ⚠️ 반드시 ② 다음
④ [☁ EC2] 전환 직후 확인     ← 이미지 SHA · 계측 주입 · 버킷 · 해시
⑤ [🖥 로컬] k6 실행           ← RUN_ID 에 숫자 붙었는지 눈으로 확인
⑥ [🖥 로컬] 결과 저장         ← result/ 에 로그, grafana_dashboards_capture/ 에 캡쳐
```

①~④ 는 [회차 전환](#회차-전환-ec2-1분) 절에, ⑤ 는 각 시나리오 절에 있다.

**②는 케이스가 둘이다.** 이미지가 바뀌는 회차(본 측정 1~9)는 A, 같은 이미지로 다시 도는
회차(파일럿 · 캘리브레이션 3회 · 회차 10)는 B 다. **B 에서는 `deploy.sh` 를 돌리지 않는다.**

**③의 순서가 중요하다** — `deploy.sh` 가 `grafana loki promtail` 을 무조건 다시 올리므로
②보다 먼저 내리면 무효가 된다. 케이스 B 는 `deploy.sh` 를 안 쓰므로 **한 번만 내려두면 유지**된다.

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

> ⚠️ **`deploy.sh` 를 돌리는 회차에서는 이 명령을 `deploy.sh` 다음에 해야 한다.**
> `deploy.sh` 가 시작할 때 그 셋을 무조건 다시 올리기 때문이다.
> 자세한 순서는 아래 [관측 스택 정리](#-관측-스택-정리는-deploysh-다음-에) 참조.

k6 요약의 `dropped_iterations` 가 0 이 아니면 **로컬 머신이 도착률을 못 채운 것**이므로
그 회차는 무효다.

## 실행

### ⛔ 셸을 먼저 고를 것 — PowerShell 에서는 `RUN_ID` 가 조용히 잘린다

이 문서의 명령은 전부 `$(date +%s)` 를 쓴다. **Git Bash 를 권장한다** — 그대로 동작한다.

**Git Bash 여는 법** — 탐색기에서 프로젝트 폴더(`Lumo_Backend`)를 열고 빈 곳에 **우클릭 →
`Open Git Bash here`**. 또는 시작 메뉴에서 `Git Bash` 실행 후 아래처럼 이동한다.
Windows 경로의 `C:\` 는 `/c/` 로, 역슬래시는 슬래시로 바뀐다.

```bash
cd /c/Users/jijys/Desktop/main/Github/Lumo_Backend
pwd            # 프로젝트 루트인지 확인 — k6/stress.js 가 상대경로라 여기서 실행해야 한다
k6 version     # PATH 에 잡히는지 확인 (Windows PATH 를 그대로 물려받는다)
```

`$K6` 같은 셸 변수와 `\` 줄바꿈, `&` 백그라운드 실행(S4 카오스)이 전부 Git Bash 기준이다.
**PowerShell 에서는 이 셋 다 다르게 동작한다.**

### 결과 저장 경로 — 머신마다 다르면 한 번만 export 한다

회차 로그는 `handleSummary` 가 **자동으로** 파일에 남긴다(CLAUDE_INIT 테스트 절). 경로는 세 단계로 찾는다.

| 우선순위 | 방법 | 쓰임 |
|---:|---|---|
| ① | `-e RESULT_DIR=...` | 회차 단위 임시 지정 |
| ② | `LUMO_RESULT_DIR` 환경변수 | **머신 단위 고정 (권장)** |
| ③ | `../dev_notes/Lumo_Backend/result` | 레포와 `dev_notes` 가 형제 폴더일 때 |

**대개는 아무 설정도 필요 없다.** ③ 은 상대경로라 그 자체로 머신 독립적이다 —
레포와 `dev_notes` 가 형제 폴더이기만 하면 데스크탑·노트북 어디서든 맞는다.
**폴더 배치가 다른 머신에서만** 아래를 쓴다.

k6 는 시스템 환경변수를 그대로 읽으므로(실측 확인) 한 번만 등록하면 `-e` 없이 잡힌다.

```bash
# 방법 A (권장) — Windows 사용자 환경변수. PowerShell·Git Bash 양쪽에서 잡힌다.
setx LUMO_RESULT_DIR "C:/Users/jijys/Desktop/main/Github/dev_notes/Lumo_Backend/result"
```

> ⚠️ `setx` 는 **현재 창에 반영되지 않는다.** 실행 후 터미널을 새로 열 것.

> ⛔ **`~/.bashrc` 에 넣는 방법은 Git Bash 에서 동작하지 않는다.**
> Git Bash 는 <b>로그인 셸</b>이라 `/etc/profile` 다음에 `~/.bash_profile` → `~/.bash_login`
> → `~/.profile` 중 **먼저 존재하는 하나만** 읽는다. `~/.bashrc` 는 아무도 부르지 않는다.
> 굳이 `.bashrc` 를 쓰려면 이걸 읽어줄 파일을 먼저 만들어야 한다.
>
> ```bash
> echo '[ -f ~/.bashrc ] && . ~/.bashrc' >> ~/.bash_profile
> ```

등록 확인: `echo "$LUMO_RESULT_DIR"`

> ⚠️ **k6 는 디렉터리를 만들어 주지 않는다.** 경로가 없으면 그 회차 로그가 통째로 사라진다.
> 회차 종료 시 콘솔 맨 아래 `── 저장 ──` 블록에 **실제 경로가 찍히므로 눈으로 확인**할 것.

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

### EC2 측정 순서 — 본 측정 블록 구성

**0. ~~파일럿 (C · 개선군)~~** — ✅ **20260902 15:30 완료**

`RATE=50 · 1m` · 3,000 요청. **하네스 5개 항목 전부 통과** —
경로 도달 · Mailpit API · `SPRING_APPLICATION_JSON` 반영 · 배출 완료 판정 · 서버측 p95 출력.
유실 0(Mailpit 3,000 = 수락 3,000) · PEL 0 · DLQ 0 · 서버측 p95 **3.0ms**.

`워커 처리량 상한: 측정 불가` 가 떴다 — 부하가 상한보다 낮아 적체가 안 생긴 것이며,
이것이 곧 **캘리브레이션 회차가 필요했던 이유**다.

<details>
<summary>당시 명령 (재현용) — 펼치기</summary>
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

</details>

**본 측정 — 블록 1~4 (총 10회차)**

회차 순서·절차는 맨 위 **「전체 순서」** 에 표로 있다. 여기서는 각 블록이 답하는 것만 적는다.

| 블록 | 시나리오 | 답하는 것 |
|---|---|---|
| 1 | S1 스파이크 | **문제 발견** — 개선 증명이 아니다 (아래 참조) |
| 2 | S2 스트레스 | 배출률 상한 |
| 3 | S4 카오스 | 유실 |
| 4 | S3 워커 스윕 (**C 전용**) | Little's Law |

### 회차별 권장 파라미터 — 확정 (20260903)

세 번의 실측으로 확정했다. 근거는 이 표다.

| 회차 | 배출률 | 백로그 | CPU max | Load/코어 | 스레드 peak | 유실 |
|---|---:|---:|---:|---:|---:|---:|
| 50rps · 1m (파일럿) | 50/s (미포화) | 0 | 22.3% | 23.5% | 61 | 0 |
| **500rps · 1m** | **461/s** (배출 구간) | **9,712** | **99.0%** | **334%** | **257** | 0 |
| 1000rps · 1m | 343/s (부하 중) | 50.5K | 97.3% | **429%** | 257 | **≈32,900** |

**부하 중 배출률은 약 340/s 로 수렴한다** (500rps 에서 338, 1000rps 에서 343).
그래서 백로그는 도착률이 아니라 **`도착률 − 340`** 에 비례한다 — 340 을 뺀 나머지만 쌓이므로
도착률을 10% 낮추면 백로그는 30% 넘게 줄어든다. **상한 근처에서는 작은 입력 변화가 크게 증폭된다.**

#### 확정 파라미터

| 시나리오 | 스크립트 | 파라미터 | 실제 도착률 | C 예상 백로그 | `DRAIN_TIMEOUT` | 대상 |
|---|---|---|---:|---:|---:|---|
| **S1** 스파이크 | `spike.js` | `VUS=500 · HOLD=60s` | ≈ 475 rps | ≈ 8,100 (81%) | 300 | A→B→C |
| **S2** 스트레스 | `stress.js` | `RATE=500 · DURATION=1m` | 500 rps | ≈ 9,600 (96%) | 300 | A→B→C |
| **S3** 워커 스윕 | `stress.js` | `RATE=500 · 1m` · `N=3/5/10/15/20` | 500 rps | 링버퍼 고정 | **600** | **C 전용** |
| **S4** 카오스 | `stress.js` | **`RATE=400 · DURATION=2m`** | 400 rps | ≈ 6,600 (66%) | 300 | A→B→C |
| **R11** CF 경유 | `stress.js` | `RATE=30 · 2m` | 30 rps | 0 | 300 | C 전용 |

**공통 옵션 — 모든 회차에 붙인다.**

```bash
K6="k6 run -e BASE_URL=https://origin.ddotg.dev -e MAILPIT_URL=http://origin.ddotg.dev:8025"
V="-e PRE_VUS=300 -e MAX_VUS=1500"
```

`PRE_VUS=300` — 500rps 회차에서 VU 가 200→430 으로 늘다가 `dropped_iterations` 246 이 났다.
미리 잡아두면 스케일업 지연으로 인한 드롭이 사라진다.
`MAX_VUS=1500` — 같은 회차의 `max=1.92s` 스톨을 덮는 여유다 (`500 × 1.9 ≈ 950`).

#### VU 와 RATE 는 다른 것을 정한다

| | `VUS` (S1 · `spike.js`) | `RATE` (S2·S3·S4 · `stress.js`) |
|---|---|---|
| 정하는 것 | **한 번에 몰려온 사람 수** | **초당 도착하는 사람 수** |
| 실행자 | `constant-vus` (closed model) | `constant-arrival-rate` (open model) |
| 도착률은 | **결과값** — 서버가 느려지면 함께 떨어진다 | **설정값** — 서버 상태와 무관하게 발사 |
| 못 지켰을 때 | 신호 없음. 조용히 느려질 뿐 | **`dropped_iterations`** 로 드러남 |

`spike.js` 는 `requestCode()` 뒤에 `sleep(1)` 이 있어 **VU 1개 ≈ 초당 1요청**이 된다.
그래서 `VUS=500` 은 약 475 rps 가 된다 (`500 ÷ (0.05 + 1)`).
**이 `sleep` 이 없으면 같은 500 VU 가 10,000 rps 가 되므로, VU 숫자만으로 부하를 판단하지 말 것.**

> **상한 측정에 open model 이 필수인 이유** — closed model 은 서버가 느려지면 요청도 함께 줄어
> «들어오는 양 > 나가는 양» 이 영원히 안 만들어진다. 게다가 서버가 멈춘 구간에는 요청을 아예
> 안 보내 **가장 느렸던 표본이 측정에서 빠진다**(coordinated omission).

#### 왜 S4 만 400 인가

S4 는 **유실 0 을 증명하는 회차**라 `MAX_LEN` 링버퍼에 닿으면 안 된다.

```
RATE=500 · 2m → (500 − 340) × 110초 ≈ 17,600  →  MAX_LEN 10,000 초과
                → A·B 는 상한이 없어 멀쩡한데 C 만 약 7,600 건 트리밍 유실
```

**A 는 큐가 없고 B 는 List 라 보관 상한이 없다.** 같은 부하에서 개선군만 유실이 나는 그림이
만들어지면 결론이 정반대가 된다. 그래서 S4 만 백로그를 66% 로 낮춘다.

`DURATION` 을 1분으로 줄여 500 을 유지하는 것도 불가능하다 — `docker start` 후 JVM 부팅 +
Spring 컨텍스트 + Flyway 까지 **20~30초**가 걸려서, 1분 회차는 절반이 502 구간이 되어
측정할 것이 남지 않는다.

#### S2 에서는 유실을 판정하지 않는다

C 의 백로그가 상한의 96% 라 회차마다 트리밍이 날 수도, 안 날 수도 있다.
**S2 의 판정 항목은 「배출률 상한」·「수락 p95」·「CPU·스레드」 뿐이다.** 유실은 S4 에서만 판정한다.

대신 **`XLEN` 이 10,000 에 닿았는지는 반드시 기록**한다 — 그 자체가
「C 의 무손실 한계는 어디인가」를 정량화하는 데이터다.

#### 중단 기준 — 하나라도 걸리면 즉시 중단

| 지표 | 임계 | 의미 |
|---|---|---|
| `jvm_threads_live_threads` | **3,000** | A 붕괴. 인스턴스가 죽기 전에 끊는다 |
| `mail_queue_depth` (XLEN) | **10,000 도달** | C 링버퍼 유실 시작. **S4 면 그 회차 무효** |
| `dropped_iterations` | **0 초과** | 부하 생성기가 도착률을 못 채움. 회차 폐기 |

컨테이너 메모리 상한을 **3점 모두 동일하게** 걸어 호스트가 아니라 컨테이너가 죽게 한다.

```bash
sudo docker update --memory 6g --memory-swap 6g Lumo_Green
```

#### 규칙 3가지

1. **C 의 S2 는 `MAIL_WORKER_COUNT=15`.** B 가 15 하드코딩이므로 이때만 «큐 구조» 만의 비교가 된다.
2. **파라미터는 3점 간 완전히 동일.** A 에서 붕괴해 값을 내렸다면 B·C 도 그 값으로 다시 돌린다.
3. **S3 은 C 전용.** A 는 워커가 없고 B 는 재빌드해야 바뀐다 — 그 사실 자체가 개선 항목이다.

#### 지속시간 하한과 `DRAIN_TIMEOUT`

**⚠️ 30초는 쓰지 말 것.** Prometheus 스크랩 간격이 5초라 30초 구간은 **데이터 포인트가 6개뿐**이다.
`rate(...[1m])` 계열이 창을 못 채워 값이 흔들린다. JVM 워밍업(30초로는 JIT 가 C2 까지 가지 못한다)과
처리 계층의 평형 도달까지 겹친다. **최소 60초.**

```
적체 = (RATE − 배출률) × DURATION
배출 소요 = 적체 ÷ 배출률
```

**S3 만 600 인 이유** — `N=3` 이면 상한이 ~90/s 라, 링버퍼에 고정된 10,000 건을 빼내는 데
`10,000 ÷ 90 ≈ 111초` 가 걸린다.

**S3 는 타임아웃이 나도 정상이다.** 목적이 「전량 배출」 이 아니라 「적체가 쌓인 상태의 배출률」 이고,
`waitForDrain()` 은 타임아웃 시에도 **그 구간의 배출량 ÷ 소요시간**을 돌려준다.

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

### S1 — 스파이크 `VUS=500 · HOLD=60s`

**판정 대상은 「서버측 수락 p95」 하나뿐이다.** 배출·유실은 보지 않는다.

```bash
$K6 $V -e RUN_ID=s1-c-$(date +%s) -e VUS=500 -e HOLD=60s -e DRAIN_TIMEOUT=300 k6/spike.js
```

`VUS=500` 은 **「사용자 500명이 동시에 몰려왔다」** 를 재현하는 것이지 500rps 를 보낸다는 뜻이 아니다.
`sleep(1)` 때문에 결과적으로 약 475 rps 가 되지만, 그 값은 **설정이 아니라 측정 결과**이므로
처리량 숫자를 결론에 쓰지 말 것.

급증의 실체는 **램프 구간이 5초**라는 점이다. 래더 모드는 `100 → 200 → 400 → 600` VU 를
각 5초 만에 올리고 `HOLD` 동안 유지한다.

```bash
# 래더 (계단) — C 에서만, 여유가 있을 때
$K6 $V -e RUN_ID=s1-ladder-c-$(date +%s) -e HOLD=60s k6/spike.js
```

> ⛔ **A(원시)에는 래더를 쓰지 말 것.** 단일 레벨 `VUS=500` 으로만 돌리고,
> `라이브 스레드` 패널을 보면서 진행한다. 3,000 접근 시 즉시 중단.

### S2 — 스트레스 `RATE=500 · 1m`

```bash
$K6 $V -e RUN_ID=s2-c-$(date +%s) -e RATE=500 -e DURATION=1m -e DRAIN_TIMEOUT=300 k6/stress.js
```

**배출률 상한을 재려면 도착률이 처리 능력을 넘겨 백로그가 실제로 쌓여야 한다.**
`워커 처리량 상한: 측정 불가` 가 나오면 적체가 안 생긴 것이므로 `RATE` 를 올려야 한다.

상한은 **부하가 끝난 뒤 배출 구간**에서 잰다. HTTP 부하가 0 이라 도착 트래픽과 CPU 를
다투지 않는, 가장 깨끗한 측정 구간이다.

### S3 — 워커 스윕 (G-4) `RATE=500 고정 · C 전용`

`mail.worker.count` 만 바꿔가며 S2 를 반복해 **상한이 워커 수에 따라 어떻게 변하는지** 본다.

**스윕 값: `N = 3 / 5 / 10 / 15 / 20`**

```bash
# [☁ EC2] 워커 수를 바꿔 재생성
cd ~/lumo && MAIL_WORKER_COUNT=<N> sudo -E docker compose up -d --force-recreate Green
sudo docker exec Lumo_Green printenv MAIL_WORKER_COUNT      # <N> 이 찍혀야 한다
```

```bash
# [🖥 로컬] 해당 회차 측정
$K6 $V -e RUN_ID=s3-c-w<N>-$(date +%s) -e RATE=500 -e DURATION=1m -e DRAIN_TIMEOUT=600 k6/stress.js
```

> ⚠️ **`printenv` 확인을 건너뛰지 말 것 (D-6).** 예전에는 `MAIL_WORKER_COUNT` 가
> compose 의 `environment:` 에 선언돼 있지 않아 **셸에서 주입해도 컨테이너에 도달하지 않았다.**
> 에러도 경고도 없이 전 회차가 기본값 15 로 돌아, 결과가 비슷하면 **「포화」 로 오해**하기 딱 좋다.

> 🔴 **낮은 `N` 에서는 대량 유실이 난다. 그리고 그게 문제가 되지 않는다.**
> `N=3` 이면 상한이 ~90/s 라 백로그가 21,600 까지 가야 하는데 링버퍼가 10,000 에서 자른다.
> **하지만 S3 의 판정 대상은 「배출 상한」 하나뿐이고, 그건 배출 구간에서 재므로 유실과 무관하다.**
> 오히려 링버퍼 덕분에 백로그가 항상 10,000 으로 고정되어 **배출 시간이
> `10,000 ÷ 상한` 으로 깨끗하게 나온다.**

**Little's Law 로 검증한다.**

```
필요 워커 수 = 목표 처리량(건/초) × 건당 소요시간(초)
건당 소요시간 = mail_send_duration_seconds_sum / mail_send_duration_seconds_count
```

`(N, 배출 상한)` 5개 점이 직선이면 모델이 맞고, 꺾이는 지점이 포화점이다.
실측에 이미 힌트가 있다 — 건당 소요가 저부하 5.11ms 에서 1000rps 부하 시 28.6ms 로 늘었다.

### S4 — 카오스 (유실 0 증명) `RATE=400 · 2m`

부하 도중 앱을 **강제 종료**하고, 재기동 후 회수되는지 본다.

> ⛔ **터미널이 두 개 필요하다.** k6 는 🖥 로컬에서, `kill` 은 ☁ EC2 에서 돈다.
> 한 블록에 이어 붙여 쓸 수 없다.

```bash
# 터미널 A — 🖥 로컬. 먼저 시작한다
$K6 $V -e RUN_ID=s4-c-$(date +%s) -e RATE=400 -e DURATION=2m -e DRAIN_TIMEOUT=300 k6/stress.js
```

```bash
# 터미널 B — ☁ EC2 SSH. 위 실행 후 30초쯤 지나서
sudo docker kill Lumo_Green && sleep 10 && sudo docker start Lumo_Green
```

`sleep 10` 은 **10초**다(셸 `sleep` 은 초 단위). 타이밍은 손으로 세도 되고 ±몇 초 어긋나도 무방하다 —
「부하가 충분히 쌓였고 아직 절반이 남은 시점」이면 된다.

> ⛔ **`stop` 이 아니라 반드시 `kill`.** `docker stop` 은 `stop_grace_period`(60초)가 개입해
> **graceful shutdown 이 되어버린다.** 워커가 정상 종료하고 PEL 이 비워져
> **장애 주입이 아니라 정상 배포를 측정**하게 된다.

**판정은 `mail_accepted` vs `Mailpit 수신` 만 쓴다.**

| ref | 킬 시점 유실 |
|---|---|
| **A 원시** | **인플라이트 전량** — 큐가 없어 되찾을 곳이 없다 |
| **B List** | `BRPOP` 으로 꺼낸 인플라이트 15건 소실. 리스트에 남은 건 살아남는다 |
| **C Streams** | **0** — PEL 에 남아 `XCLAIM` 으로 회수된다 |

> ⚠️ **재기동으로 Micrometer 카운터가 0 이 된다.** `reportProcessing()` 의 `발송 성공(회차)` 가
> 음수로 나오고, Grafana 의 **「실질 백로그(파생)」 패널도 못 쓴다**(두 카운터가 같이 리셋된다).
> S4 에서는 그 둘을 무시하고 위 판정 기준만 본다.

> ⚠️ 컨테이너가 죽어 있는 동안 요청은 **502** 를 받는다. 이 건들은 `mail_accepted` 에 안 잡히므로
> (k6 는 200 만 센다) 유실 판정에는 영향이 없다.
## ~~배출 상한 캘리브레이션~~ — ✅ **완료 (20260902~0903)**

파일럿이 `워커 처리량 상한: 측정 불가` 를 냈다. `RATE=50` 이 배출률보다 한참 낮아 적체가 아예
생기지 않았기 때문이다. 도착률을 계단으로 올려 **적체가 처음 생기는 지점**을 찾았다.

### 결과

| 회차 | 완료 | 배출률 | 백로그 | 유실 | 판정 |
|---|---|---:|---:|---:|---|
| `RATE=500 · 1m` | 20260902 17:58 | **461/s** (배출 구간) | 9,712 | **0** | 적체 발생 · **상한 확보** |
| `RATE=1000 · 1m` | 20260903 14:04 | 343/s (부하 중) | 50.5K | **≈32,900** | 링버퍼 상한 도달 · **사다리 종료** |

**`RATE=2000` 은 돌리지 않았다.** 이미 CPU 포화(Load 429%) · 링버퍼 상한 도달 · 대량 유실이
확인되어 같은 결론을 더 극단적으로 반복할 뿐이었다.

### 배운 것 — 배출 상한은 상수가 아니다

| 부하 | 건당 소요 | 배출률 |
|---|---:|---:|
| 50rps | **5.11ms** | 50/s (미포화) |
| 500rps | — | **461/s** (배출 구간, HTTP 부하 0) |
| 1000rps | **28.6ms** | **343/s** (부하 중) |

부하가 오르면 워커가 Tomcat 과 CPU 를 다투어 건당 소요가 **5.6배** 늘고 그만큼 상한이 내려간다.
1000rps 회차 실측: 시스템 CPU max **97.3%**, 프로세스 CPU max 65.8% —
**약 31% 가 JVM 밖**(Mailpit 의 초당 300+ SMTP 커넥션 · nginx TLS · Redis)에서 쓰였다.

→ **「배출 상한 N건/초」 는 반드시 부하 조건과 함께 적어야 한다.**

<details>
<summary><b>당시 절차 (재현용)</b> — 펼치기</summary>
#### 원리

```
적체 = (도착률 − 배출률) × 지속시간
```

`stress.js` 는 `constant-arrival-rate`(open model)라 **서버가 느려져도 도착률을 유지**한다.
VU 기반(closed model)은 서버가 느려지면 VU 가 응답을 기다리느라 요청률이 함께 떨어져
(coordinated omission) 상한이 영원히 보이지 않는다. 그래서 «들어오는 양 > 나가는 양» 을
강제로 만들 수 있는 것은 도착률 고정 방식뿐이다.

상한이 미지수이므로 도착률을 계단으로 올려 **`★ 실질 백로그 (파생)` 패널이 처음 0 을
벗어나는 지점**을 찾는다. 그 지점이 배출률 상한이다.

#### 실행

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

#### 고부하에서 새로 생기는 한계 3가지

| 항목 | 내용 |
|---|---|
| **Mailpit 저장량** | `RATE=2000 × 60s = 120,000통`. 회차마다 `DELETE /api/v1/messages` + 컨테이너 재시작으로 반드시 비운다 |
| **부하 생성기 대역폭** | 파일럿 실측 응답 ≈1.4KB/건 → 2,000/s 면 **≈22Mbps 수신**. 회선이 못 받치면 그 회차는 무효 |
| **`dropped_iterations`** | 0 이 아니면 k6 가 도착률을 못 지킨 것이다. 그 회차는 폐기 |

#### 분기 — 적체가 끝내 안 생기면

`dropped_iterations` 나 수락 p95 가 **먼저** 무너지면 병목은 워커가 아니라 **수락 계층·네트워크**다.
그때는 부하를 더 올리지 말고 **`MAIL_WORKER_COUNT` 를 1~3 으로 낮춰 상한을 끌어내린다.**

규칙(«C 의 S2 는 워커 15»)은 그대로 유지하고 **S3 스윕에서만** 적용할 것 —
S2 는 B 의 하드코딩 15 와 맞춰야 «큐 구조»만의 비교가 되기 때문이다.

---

</details>

## ⛔ `MAX_LEN` 링버퍼 — C 의 무손실 한계

`MemberService` 의 `XADD` 는 `maxlen(MailStream.MAX_LEN).approximateTrimming(true)` 로
스트림을 **약 10,000 엔트리 링버퍼**로 유지한다. 트리밍하지 않으면 메모리가 단조 증가하기 때문이다.

**`XTRIM` 은 PEL 을 보지 않는다.** 아직 아무도 읽지 않은 엔트리도 그냥 잘려나간다.
따라서 **백로그가 10,000 을 넘는 순간부터 조용히 유실**이 시작된다.

```
백로그(t) = (도착률 − 배출률) × t        부하 중 배출률 ≈ 340건/초 (실측)
백로그 > 10,000 이 되는 시각  t = 10,000 ÷ (도착률 − 340)
```

| 회차 | 도착 총량 | 백로그 | **유실** | 판정 |
|---|---:|---:|---:|---|
| `RATE=500` · 1m | 30,000 | **9,712** (실측) | **0** | 경계 바로 아래 — 가까스로 안 넘김 |
| `RATE=1000` · 1m | 60,000 | **50.5K** (실측) | **≈ 32,900** (실측) | 약 19초에 상한 도달 |
| `RATE=2000` · 1m | 120,000 | — | ≈ 82,000 (예측) | 약 6.5초. **미실행** |

1000rps 회차의 직접 증거 3개가 일치한다 — `XLEN` 이 **10.0K 에 고정**,
「실질 백로그」가 **40.9K 에서 0 으로 복귀하지 않음**, **PEL 은 0**(더 나갈 것이 없음).
DLQ 0 · 회수 0 이라 **어떤 카운터에도 「실패」로 잡히지 않는 조용한 소실**이었다.
> **이 유실은 코드 결함이 아니라 설계된 상한이다.** 그러나 «C 는 유실 0» 이라는 결론에는
> **단서가 붙어야 한다** — «백로그가 `MAX_LEN` 을 넘지 않는 동안» 이다.
> `RATE=500` 회차가 9,712 로 **경계 바로 아래**였다는 것이 이 한계를 드러낸 실측 증거다.
>
> 고부하 캘리브레이션 회차에서 `Mailpit 수신 << mail_accepted` 가 나오면 **정상이다.**
> S4(유실 0 증명)와 혼동하지 말 것 — S4 는 `RATE=50` 저부하라 링버퍼에 닿지 않는다.
>
> `MAX_LEN` 을 올리는 것은 **애플리케이션 코드 변경**이라 C 대조군 이미지가 무효가 된다.
> 측정 후 개선 항목으로 남긴다.

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

### ~~대조군 이미지 굽기~~ — ✅ **20260903 완료**

| ref | 태그 | 결과 |
|---|---|---|
| **A** `4f38b75` | `jijysun/lumo:4f38b75743fa…` | arm64 · 214MB · 푸시 완료 |
| **B** `8f48f0e` | `jijysun/lumo:8f48f0e062a9…` | arm64 · 214MB · 푸시 완료 |

회차마다 CI 로 굽지 않는다. **굽기와 돌리기를 분리**해야 배포 자산이 측정 내내 상수로 유지되고,
회차 전환이 10분에서 1분으로 줄어든다.

<details>
<summary>당시 절차 (인스턴스 재생성 시 재현용) — 펼치기</summary>
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

</details>

### 회차 사이 초기화 (EC2) — 전환보다 먼저

```bash
cd ~/lumo
sudo docker compose stop Blue Green                      # ① 앱을 먼저 멈춘다
sudo docker exec Lumo_Redis redis-cli FLUSHALL           # ② List ↔ Stream 잔재 제거
curl -s -X DELETE http://localhost:8025/api/v1/messages  # ③ Mailpit 비우기
sudo docker restart Lumo_Mailpit                         # ④ 인메모리 저장분 회수
```

앱을 **먼저** 멈추는 이유 — 살아 있는 상태로 `FLUSHALL` 하면 워커 15개가 동시에 `NOGROUP` 을
만나 그룹 재생성 경합 로그를 쏟는다. 결과는 정상이지만 진단이 어려워진다.

> ⚠️ **관측 스택 정리(`stop grafana loki promtail`)는 여기서 하지 말 것.**
> `deploy.sh` 가 그 셋을 무조건 다시 올린다(스크립트 28~32행) — 지금 내려도 되살아난다.
> **아래 「관측 스택 정리」 순서를 따를 것.**

### 회차 전환 (EC2, 1분)

**케이스 A — 이미지가 바뀌는 회차** (본 측정 10회차 중 A↔B↔C 전환)

```bash
cd ~/lumo
sudo docker pull jijysun/lumo:<SHA>
sudo sed -i "s|^LUMO_TAG=.*|LUMO_TAG=<SHA>|" .env
sudo bash ./scripts/deploy.sh
```

**케이스 B — 같은 이미지로 다시 도는 회차** (파일럿 · 캘리브레이션 3회차 · S3 스윕)

이미지가 그대로이므로 **`deploy.sh` 를 돌릴 필요가 없다.** 초기화에서 내려간 컨테이너를
**nginx 가 가리키는 색으로** 되살리기만 하면 된다.

```bash
cat /etc/nginx/conf.d/service-url.inc     # → set $service_url http://127.0.0.1:8082;  즉 Green
sudo docker compose up -d Green           # 그 포트의 색을 올린다 (8081 이면 Blue)
```

> `start` 가 아니라 **`up -d`** 를 쓴다. `start` 는 기존 컨테이너를 그대로 되살리므로
> `.env` 변경(`MAIL_WORKER_COUNT` 등)이 **반영되지 않는다.** 그리고 `docker compose start` 에는
> `-d` 플래그가 없다 — `start -d` 는 `unknown shorthand flag` 로 실패한다.

### `deploy.sh` 의 Blue/Green 토글 규칙 — 둘 다 내려가 있으면 Blue 로 간다

```bash
EXIST_BLUE=$(sudo docker ps -q -f name=Lumo_Blue)   # ← 실행 중인 것만 나온다
if [ -z "$EXIST_BLUE" ]; then TARGET=Blue(8081) ; else TARGET=Green(8082) ; fi
```

초기화로 **둘 다 멈춘 상태에서도 `deploy.sh` 는 정상 동작한다.** `EXIST_BLUE` 가 비어
항상 **Blue(8081)** 를 타깃으로 잡고, 헬스체크 통과 후 nginx 를 8081 로 바꾼 뒤 Green 을
`stop` + `rm` 한다. **컨테이너를 손으로 미리 올릴 필요가 없다** — 올려도 무해하지만 불필요하다.

다음 회차에는 Blue 가 살아 있으므로 타깃이 Green 이 되어 정상적으로 번갈아 간다.

### ⛔ 관측 스택 정리는 `deploy.sh` **다음** 에

`deploy.sh` 는 시작할 때 `up -d` + `restart` 로 `prometheus grafana loki promtail` 을 **전부
되살린다**(D-2 대응이다 — EC2 재생성 시 관측 스택이 안 뜬 채 "성공한 배포" 가 되던 문제).

따라서 순서는 **전환 → 정리** 다. 거꾸로 하면 정리가 무효가 된다.

```bash
sudo docker compose stop grafana loki promtail   # Prometheus 만 남긴다
```

케이스 B(같은 이미지)에서는 `deploy.sh` 를 안 돌리므로 **한 번만 내려두면 계속 유지**된다.

> ⛔ **측정 기간 중 워크플로 dispatch 금지.** 대조군 ref 로 dispatch 하면 대조군의 낡은
> `docker-compose.yml` 이 scp 되어 계측 백포트(`SPRING_APPLICATION_JSON`)가 사라진다.
> 회차 전환은 위 명령으로만 한다.

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

> **부하 중에도 0 이 정상일 수 있다.** 도착률이 배출 상한보다 낮으면 Little's Law 상
> 인플라이트가 1 건 미만이라 스크랩마다 0 이 찍힌다 — 파일럿은 `L = 50/s × 5.11ms = 0.26` 이었다.
>
> «진짜 0» 인지 «쿼리 고장» 인지는 같은 패널 **오른쪽 축의 점선 두 개(검산용)** 로 판별한다.
> 둘이 나란히 계단으로 오르면 쿼리는 정상이고 부하가 낮은 것이다.
> 한쪽만 오르거나 둘 다 없으면 라벨(`uri`·`status`)이 틀린 것이다.
>
> 적체를 **보려고** 하는 것이라면 부하가 부족한 것이므로 위 캘리브레이션 절로 돌아간다.

> 📌 **로컬 스모크 실측(20260824) 절은 `dev_notes/Lumo_Backend/analysis/` 로 이관했다 (20260902).**
> 원문은 `git show cf305a8:k6/README.md` 에서 볼 수 있다.
