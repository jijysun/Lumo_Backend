import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

/*
 * 부하 테스트 공용 모듈.
 *
 * 측정 대상은 두 계층으로 나뉜다 — 어느 쪽을 재고 있는지 항상 명시할 것.
 *   수락 계층: HTTP → XADD → 200.  k6 가 직접 관측한다 (mail_accept_latency)
 *   처리 계층: 워커 → SMTP → XACK. k6 는 볼 수 없다. actuator/prometheus 를 긁어야 한다
 */

export const BASE = __ENV.BASE_URL || 'http://localhost:8080';
export const MAILPIT = __ENV.MAILPIT_URL || 'http://localhost:8025';

/**
 * 실행 회차 식별자.
 *
 * ⚠️ 반드시 회차마다 달라야 한다. MemberService.requestVerificationCode 는
 * {@code setIfAbsent(email, code, 3분)} 으로 중복 요청을 막으므로, 같은 주소를 재사용하면
 * 두 번째부터 400(MEMBER_4003) 으로 튕긴다. 그러면 측정 대상이 메일 파이프라인이 아니라
 * "SETNX 실패 후 예외 반사 경로" 가 되어 결과가 통째로 무의미해진다.
 */
export const RUN_ID = __ENV.RUN_ID || String(Date.now());

export const accepted = new Counter('mail_accepted');
export const rejectedDuplicate = new Counter('mail_rejected_duplicate');
export const rejectedOther = new Counter('mail_rejected_other');
export const acceptLatency = new Trend('mail_accept_latency', true);

/** 인증 코드 요청 1건. 주소는 VU·iteration 조합으로 항상 유일하다. */
export function requestCode() {
    const email = `lt-${RUN_ID}-${__VU}-${__ITER}@loadtest.invalid`;

    const res = http.post(
        `${BASE}/api/member/request-code?email=${encodeURIComponent(email)}`,
        null,
        { tags: { name: 'request-code' } },
    );

    acceptLatency.add(res.timings.duration);

    if (res.status === 200) {
        accepted.add(1);
    } else if (res.status === 400 && res.body && res.body.indexOf('MEMBER_4003') >= 0) {
        // 주소 중복. 0 이 아니면 RUN_ID 재사용을 의심할 것 — 측정이 오염된 상태다.
        rejectedDuplicate.add(1);
    } else {
        rejectedOther.add(1);
    }

    check(res, { 'accepted (200)': (r) => r.status === 200 });
    return res;
}

/**
 * actuator/prometheus 를 긁어 지정 메트릭의 합을 구한다.
 *
 * <p>태그 순서에 의존하지 않도록 이름 접두 + 태그 부분문자열로 거른다.
 * (Micrometer 는 태그를 알파벳순으로 내보내므로 result 가 항상 마지막에 온다)
 *
 * @param name      프로메테우스 메트릭 이름 (예: mail_send_result_total)
 * @param tagFilter 선택. 라인에 포함돼야 하는 문자열 (예: 'result="success"')
 * @returns 합계. 해당 메트릭이 하나도 없으면 null
 */
export function scrapeSum(name, tagFilter) {
    const res = http.get(`${BASE}/actuator/prometheus`, { tags: { name: 'scrape' } });
    if (res.status !== 200) {
        return null;
    }

    let sum = 0;
    let found = false;

    const lines = res.body.split('\n');
    for (let i = 0; i < lines.length; i++) {
        const line = lines[i];
        if (line.length === 0 || line.charAt(0) === '#') continue;
        if (line.indexOf(name + '{') !== 0 && line.indexOf(name + ' ') !== 0) continue;
        if (tagFilter && line.indexOf(tagFilter) < 0) continue;

        const value = parseFloat(line.slice(line.lastIndexOf(' ') + 1));
        if (!isNaN(value)) {
            sum += value;
            found = true;
        }
    }
    return found ? sum : null;
}

/** Mailpit 이 실제로 수신한 총 건수. 유실 판정의 기준값이다. */
export function mailpitTotal() {
    const res = http.get(`${MAILPIT}/api/v1/messages?limit=1`, { tags: { name: 'mailpit' } });
    if (res.status !== 200) return null;
    return res.json('total');
}

/** 회차 시작 전 Mailpit 을 비운다. 비우지 않으면 이전 회차 수신분이 유실률 계산을 망친다. */
export function mailpitClear() {
    const res = http.del(`${MAILPIT}/api/v1/messages`, null, { tags: { name: 'mailpit' } });
    return res.status === 200;
}

/**
 * 앱의 누적 카운터를 한 번에 찍는다.
 *
 * <p>⚠️ Micrometer 카운터는 <b>앱 기동 이후 누적</b>이다. 회차별 값을 얻으려면
 * 시작 시점 스냅샷을 빼야 한다. (Mailpit 은 회차마다 비우므로 이미 회차값이다 —
 * 이 둘을 그대로 비교하면 유실 판정이 성립하지 않는다)
 */
export function snapshotCounters() {
    return {
        success: scrapeSum('mail_send_result_total', 'result="success"') || 0,
        failTransient: scrapeSum('mail_send_result_total', 'failure="transient"') || 0,
        failPermanent: scrapeSum('mail_send_result_total', 'failure="permanent"') || 0,
        dlq: scrapeSum('mail_dlq_total') || 0,
    };
}

/**
 * 큐가 비워질 때까지 기다리고, 그 구간의 배출량·소요시간을 돌려준다.
 *
 * <p>⚠️ {@code mail_queue_pending}(XPENDING) 만으로는 완료를 판정할 수 없다.
 * XPENDING 은 "읽었지만 미확인" 만 세므로, 아직 XREADGROUP 되지 않은 항목은 0 으로 보인다.
 * 그래서 <b>성공 카운터의 증가가 멈췄는지</b>를 함께 본다.
 *
 * <p>이 구간의 배출률이 곧 <b>워커 처리량 상한</b>이다 — 큐에 백로그가 쌓인 상태에서
 * 도착률의 방해 없이 순수하게 빼내는 속도이기 때문이다.
 * (그러려면 부하 도착률이 처리 능력을 넘겨 백로그가 실제로 쌓여 있어야 한다)
 *
 * @param timeoutSec 최대 대기
 * @param stallSec   진전이 이만큼 없으면 배출이 끝난 것으로 본다
 */
export function waitForDrain(timeoutSec, stallSec) {
    const startedAt = Date.now();
    const startSuccess = scrapeSum('mail_send_result_total', 'result="success"') || 0;

    let lastSuccess = startSuccess;
    let lastProgressAt = Date.now();

    for (;;) {
        const elapsed = (Date.now() - startedAt) / 1000;
        const success = scrapeSum('mail_send_result_total', 'result="success"') || 0;
        const pending = scrapeSum('mail_queue_pending');

        if (success !== lastSuccess) {
            lastSuccess = success;
            lastProgressAt = Date.now();
        }

        const stalledFor = (Date.now() - lastProgressAt) / 1000;
        const drained = (pending === 0 || pending === null) && stalledFor >= stallSec;

        if (drained || elapsed >= timeoutSec) {
            // 정체 감지에 쓴 시간은 배출 시간이 아니다. 빼되 음수로는 내려가지 않게 한다.
            const drainSeconds = Math.max(0, elapsed - (drained ? stalledFor : 0));
            return {
                drainSeconds: drainSeconds,
                drained: success - startSuccess,
                success: success,
                pending: pending,
                timedOut: !drained,
            };
        }
        sleep(2);
    }
}

/**
 * 회차 종료 후 공통 리포트. 처리 계층의 결과는 전부 여기서 나온다.
 *
 * @param label 회차 이름
 * @param base  setup() 이 찍어 둔 시작 시점 스냅샷 (없으면 누적값이 그대로 나온다)
 */
export function reportProcessing(label, base) {
    const baseline = base || { success: 0, failTransient: 0, failPermanent: 0, dlq: 0 };

    const drain = waitForDrain(
        Number(__ENV.DRAIN_TIMEOUT || 600),
        Number(__ENV.DRAIN_STALL || 20),
    );

    const now = snapshotCounters();
    const processed = now.success - baseline.success;
    const failTransient = now.failTransient - baseline.failTransient;
    const failPermanent = now.failPermanent - baseline.failPermanent;
    const dlq = now.dlq - baseline.dlq;
    const received = mailpitTotal();

    // 백로그가 남아 있던 경우에만 배출률이 의미를 갖는다.
    const drainRate = drain.drainSeconds > 0
        ? (drain.drained / drain.drainSeconds).toFixed(1) + ' 건/초'
        : '측정 불가 (부하 종료 시점에 이미 배출 완료 — RATE 를 올릴 것)';

    console.log(`
────────────── 처리 계층 결과 (${label}) ──────────────
  발송 성공(회차)  ${processed}
  실패 일시/영구   ${failTransient} / ${failPermanent}
  DLQ 이관         ${dlq}
  미확인(XPENDING) ${drain.pending}
  Mailpit 수신     ${received}

  잔여 배출 소요   ${drain.drainSeconds.toFixed(1)}s ${drain.timedOut ? '(TIMEOUT — 배출 미완료)' : ''}
  잔여 배출량      ${drain.drained}
  워커 처리량 상한 ${drainRate}

  ※ 유실 판정: Mailpit 수신(${received}) >= 아래 요약의 mail_accepted 여야 한다
    (at-least-once 이므로 재시도로 인한 중복은 허용된다)
──────────────────────────────────────────────────`);

    return {
        processed: processed,
        failTransient: failTransient,
        failPermanent: failPermanent,
        dlq: dlq,
        mailpitReceived: received,
        drainSeconds: drain.drainSeconds,
        drained: drain.drained,
        timedOut: drain.timedOut,
    };
}
