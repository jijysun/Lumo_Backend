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

/** 부하 대상 엔드포인트. 서버 측 응답시간은 이 uri 로 걸러서 본다. */
export const TARGET_URI = '/api/member/request-code';

/**
 * {@code http_server_requests_seconds_bucket} 을 긁어 le → 누적건수 맵으로 돌려준다.
 *
 * <p><b>왜 서버 측 값을 따로 보는가</b> — k6 의 http_req_duration 에는 네트워크 RTT 가 섞인다.
 * 로컬에서 EC2 로 쏘면 RTT 가 서버 처리시간보다 커서 측정값의 대부분이 네트워크가 된다.
 * 이 히스토그램은 Tomcat 이 요청을 받아 응답을 쓸 때까지만 재므로 부하 생성기 위치와 무관하다.
 */
export function scrapeBuckets(uri) {
    const res = http.get(`${BASE}/actuator/prometheus`, { tags: { name: 'scrape' } });
    if (res.status !== 200) return null;

    const buckets = {};
    const lines = res.body.split('\n');

    for (let i = 0; i < lines.length; i++) {
        const line = lines[i];
        if (line.indexOf('http_server_requests_seconds_bucket{') !== 0) continue;
        if (uri && line.indexOf('uri="' + uri + '"') < 0) continue;

        const m = /le="([^"]+)"\} ([0-9.eE+-]+)\s*$/.exec(line);
        if (!m) continue;

        // 같은 le 라도 method/status 조합마다 라인이 나뉜다. 합산해야 전체 분포가 된다.
        const le = m[1] === '+Inf' ? Infinity : parseFloat(m[1]);
        const count = parseFloat(m[2]);
        if (isNaN(count)) continue;
        buckets[le] = (buckets[le] || 0) + count;
    }
    return buckets;
}

/**
 * 두 버킷 스냅샷의 차이로 회차 분위수를 구한다 (Prometheus histogram_quantile 과 같은 선형 보간).
 *
 * <p>누적 히스토그램이므로 델타도 단조 증가를 유지한다 — 그래서 뺀 뒤에 그대로 계산할 수 있다.
 *
 * @returns 초 단위. 표본이 없으면 null
 */
export function quantileFromBuckets(base, now, q) {
    if (!now) return null;

    const les = Object.keys(now)
        .map(function (k) { return k === 'Infinity' ? Infinity : parseFloat(k); })
        .sort(function (a, b) { return a - b; });

    if (les.length === 0) return null;

    const cum = [];
    for (let i = 0; i < les.length; i++) {
        const key = les[i] === Infinity ? 'Infinity' : String(les[i]);
        const b = base && base[key] !== undefined ? base[key] : 0;
        cum.push((now[key] || 0) - b);
    }

    const total = cum[cum.length - 1];
    if (!total || total <= 0) return null;

    const target = total * q;
    for (let i = 0; i < les.length; i++) {
        if (cum[i] < target) continue;

        // 상한 밖(+Inf)으로 넘어갔다면 보간할 수 없다. maximum-expected-value 를 올려야 한다는 신호다.
        if (les[i] === Infinity) {
            return i > 0 ? les[i - 1] : null;
        }
        const lowerLe = i === 0 ? 0 : les[i - 1];
        const lowerCum = i === 0 ? 0 : cum[i - 1];
        const span = cum[i] - lowerCum;
        if (span <= 0) return les[i];

        return lowerLe + (les[i] - lowerLe) * ((target - lowerCum) / span);
    }
    return les[les.length - 1];
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
        // 서버 측 응답시간 분포. 회차값을 얻으려면 종료 시점에서 이 값을 빼야 한다.
        buckets: scrapeBuckets(TARGET_URI),
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

/*
 * ── 회차 결과 파일 저장 ────────────────────────────────────────────────────
 *
 * k6 는 VU 마다 별도 런타임을 쓰지만 setup / teardown / handleSummary 는 <b>같은 런타임</b>
 * 에서 돈다. 그래서 teardown 이 만든 텍스트를 모듈 변수에 담아 handleSummary 로 넘길 수 있다.
 * (VU 코드에서는 이 값이 보이지 않는다 — 보일 필요도 없다)
 *
 * handleSummary 안에서는 http 를 쓸 수 없으므로, Redis·Mailpit 을 조회하는 처리 계층 블록은
 * teardown 시점에 미리 만들어 두어야 한다.
 */
let processingReport = '';

function pad2(n) { return n < 10 ? '0' + n : String(n); }

/** `20260902_1743` 형태. CLAUDE_INIT 의 파일명 규칙에 쓴다. */
function stamp(d) {
    return d.getFullYear() + pad2(d.getMonth() + 1) + pad2(d.getDate())
        + '_' + pad2(d.getHours()) + pad2(d.getMinutes());
}

function isoLocal(d) {
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate())
        + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
}

/**
 * CLAUDE_INIT 규칙의 파일명을 만든다 — `${년월일}_${시간}_${부하}_${지속}`.
 *
 * @param load     `500rps` (도착률 기반) 또는 `200vu` (VU 기반)
 * @param duration `1m` 처럼 지속시간 표기
 */
export function buildRunName(load, duration, startedAt) {
    return stamp(startedAt) + '_' + load + '_' + duration;
}

/** k6 요약 객체를 사람이 읽는 텍스트로 만든다. 외부 jslib 에 의존하지 않는다(오프라인 안전). */
function renderSummary(data) {
    const lines = [];
    const ms = function (v) { return (Math.round(v * 100) / 100) + 'ms'; };

    const checks = data.metrics && data.metrics.checks;
    if (checks && checks.values) {
        const p = checks.values.passes || 0;
        const f = checks.values.fails || 0;
        lines.push('CHECKS  성공 ' + p + ' / 실패 ' + f
            + '  (' + ((p / Math.max(1, p + f)) * 100).toFixed(2) + '%)');
        lines.push('');
    }

    const names = Object.keys(data.metrics).sort();
    lines.push('METRICS');
    for (let i = 0; i < names.length; i++) {
        const n = names[i];
        const m = data.metrics[n];
        const v = m.values || {};
        let body;

        if (m.type === 'counter') {
            body = 'count=' + v.count + '  rate=' + (v.rate || 0).toFixed(4) + '/s';
        } else if (m.type === 'rate') {
            body = (v.rate * 100).toFixed(2) + '%  (passes=' + v.passes + ' fails=' + v.fails + ')';
        } else if (m.type === 'gauge') {
            body = 'value=' + v.value + '  min=' + v.min + '  max=' + v.max;
        } else { // trend
            const u = m.contains === 'time' ? ms : function (x) { return String(Math.round(x * 100) / 100); };
            body = 'avg=' + u(v.avg) + '  min=' + u(v.min) + '  med=' + u(v.med)
                + '  max=' + u(v.max) + '  p90=' + u(v['p(90)']) + '  p95=' + u(v['p(95)']);
        }

        let line = '  ' + n;
        while (line.length < 34) line += ' ';
        lines.push(line + ': ' + body);

        // 임계값 통과 여부는 회차 유효성 판정에 직결되므로 함께 남긴다.
        if (m.thresholds) {
            const tn = Object.keys(m.thresholds);
            for (let j = 0; j < tn.length; j++) {
                lines.push('      threshold ' + tn[j] + ' → '
                    + (m.thresholds[tn[j]].ok ? 'PASS' : 'FAIL'));
            }
        }
    }
    return lines.join('\n');
}

/**
 * handleSummary 가 돌려줄 파일 맵을 만든다.
 *
 * <p>결과는 CLAUDE_INIT 규칙에 따라 `dev_notes/${프로젝트}/result/` 아래에 남긴다.
 * k6 는 <b>디렉터리를 만들어 주지 않으므로</b> 경로가 없으면 쓰기가 실패한다.
 *
 * <p>⚠️ `stdout` 키를 돌려주면 k6 기본 요약이 <b>대체</b>된다. 그래서 기본 요약을 잃지 않도록
 * 직접 렌더링한 요약을 같이 넣는다.
 *
 * @param meta `{ load: '500rps', duration: '1m', scenario: 'S2 스트레스' }`
 */
export function summaryFiles(data, meta) {
    const endedAt = new Date();
    const durMs = (data.state && data.state.testRunDurationMs) || 0;
    const startedAt = new Date(endedAt.getTime() - durMs);

    const name = buildRunName(meta.load, meta.duration, startedAt);

    /*
     * 저장 경로는 머신마다 다르다 (데스크탑 ↔ 노트북). 세 단계로 찾는다.
     *
     *   ① -e RESULT_DIR=...     회차 단위 임시 지정
     *   ② LUMO_RESULT_DIR       머신 단위 고정. k6 는 시스템 환경변수를 그대로 읽으므로
     *                           ~/.bashrc 에 export 해두면 -e 없이도 잡힌다 (실측 확인)
     *   ③ 상대경로 기본값        레포와 dev_notes 가 형제 폴더일 때만 맞는다
     *
     * ⚠️ k6 는 디렉터리를 만들어 주지 않는다. 경로가 없으면 이 회차의 로그가 통째로 사라진다 —
     *    아래 stdout 에 실제 경로를 찍으므로 회차 종료 시 눈으로 확인할 것.
     */
    const dir = __ENV.RESULT_DIR || __ENV.LUMO_RESULT_DIR || '../dev_notes/Lumo_Backend/result';
    const base = dir + '/' + name;

    const head = [
        '='.repeat(78),
        ' 회차: ' + name,
        ' 시나리오: ' + (meta.scenario || '-') + '   RUN_ID: ' + RUN_ID,
        ' 대상: ' + BASE + '   Mailpit: ' + MAILPIT,
        '',
        ' 시작: ' + isoLocal(startedAt),
        ' 종료: ' + isoLocal(endedAt),
        ' 소요: ' + (durMs / 1000).toFixed(1) + 's',
        '',
        ' ※ Grafana 시간 범위를 위 시작~종료로 맞출 것. 부하 구간 밖이 섞이면 평균이 희석된다.',
        ' ※ 대시보드 캡쳐는 grafana_dashboards_capture/' + name + ' - 1~4.png 로 저장한다.',
        '='.repeat(78),
        '',
    ].join('\n');

    const tail = [
        '',
        '',
        '='.repeat(78),
        ' 분석',
        '='.repeat(78),
        '(여기에 해석을 적는다 — 무엇이 병목이었고, 무엇이 이전 회차와 달라졌는가)',
        '',
    ].join('\n');

    const text = head + processingReport + '\n\n' + renderSummary(data) + tail;

    const out = {};
    out[base + '.txt'] = text;
    out[base + '.json'] = JSON.stringify(data, null, 2);
    out['stdout'] = processingReport + '\n\n' + renderSummary(data) + '\n\n'
        + '── 저장 ' + '─'.repeat(60) + '\n'
        + '  시작 ' + isoLocal(startedAt) + '  →  종료 ' + isoLocal(endedAt)
        + '   (' + (durMs / 1000).toFixed(1) + 's)\n'
        + '  ' + base + '.txt\n'
        + '  ' + base + '.json\n'
        + '  캡쳐 파일명: ' + name + ' - 1.png ~ - 4.png\n'
        + '─'.repeat(68) + '\n';
    return out;
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

    // 네트워크 RTT 가 빠진 순수 서버 처리시간. 개선 전후 비교는 이 값으로 한다.
    const nowBuckets = scrapeBuckets(TARGET_URI);
    const p95 = quantileFromBuckets(baseline.buckets, nowBuckets, 0.95);
    const p99 = quantileFromBuckets(baseline.buckets, nowBuckets, 0.99);
    const fmt = function (v) { return v === null ? 'n/a' : (v * 1000).toFixed(1) + 'ms'; };

    // 백로그가 남아 있던 경우에만 배출률이 의미를 갖는다.
    const drainRate = drain.drainSeconds > 0
        ? (drain.drained / drain.drainSeconds).toFixed(1) + ' 건/초'
        : '측정 불가 (부하 종료 시점에 이미 배출 완료 — RATE 를 올릴 것)';

    // handleSummary 로 넘기려면 문자열로 들고 있어야 한다 (거기서는 http 조회가 불가능하다).
    processingReport = `
────────────── 처리 계층 결과 (${label}) ──────────────
  발송 성공(회차)  ${processed}
  실패 일시/영구   ${failTransient} / ${failPermanent}
  DLQ 이관         ${dlq}
  미확인(XPENDING) ${drain.pending}
  Mailpit 수신     ${received}

  잔여 배출 소요   ${drain.drainSeconds.toFixed(1)}s ${drain.timedOut ? '(TIMEOUT — 배출 미완료)' : ''}
  잔여 배출량      ${drain.drained}
  워커 처리량 상한 ${drainRate}

  [수락 계층 — 서버가 직접 잰 값, 네트워크 RTT 제외]
  p95 / p99       ${fmt(p95)} / ${fmt(p99)}
  ※ k6 요약의 http_req_duration 은 RTT 를 포함한 '사용자 체감' 이다.
    개선 전후 비교는 위 서버 측 값으로 할 것 — 부하 생성기 위치에 영향받지 않는다.

  ※ 유실 판정: Mailpit 수신(${received}) >= 아래 요약의 mail_accepted 여야 한다
    (at-least-once 이므로 재시도로 인한 중복은 허용된다)
──────────────────────────────────────────────────`;

    console.log(processingReport);

    return {
        processed: processed,
        failTransient: failTransient,
        failPermanent: failPermanent,
        dlq: dlq,
        mailpitReceived: received,
        serverP95: p95,
        serverP99: p99,
        drainSeconds: drain.drainSeconds,
        drained: drain.drained,
        timedOut: drain.timedOut,
    };
}
