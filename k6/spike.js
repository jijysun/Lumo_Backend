import { sleep } from 'k6';
import { requestCode, mailpitClear, reportProcessing, snapshotCounters, RUN_ID } from './lib/lumo.js';

/*
 * S1 — 스파이크 테스트 (수락 계층)
 *
 * 답하는 질문: "요청이 급증해도 API 응답시간이 평탄하게 유지되는가"
 *
 * ⚠️ 이 테스트가 답하지 '않는' 것 — 메일이 실제로 몇 건 나갔는지.
 *    API 는 XADD 후 즉시 200 을 반환하므로, 여기서 좋은 응답시간이 나오는 것은
 *    비동기화의 당연한 귀결이지 워커 파이프라인의 성능이 아니다.
 *    배출 성능은 S2(stress.js)가, 유실은 S4(카오스)가 답한다.
 *
 * 실행:
 *   k6 run -e RUN_ID=s1-$(date +%s) k6/spike.js          # 100/200/400/600 계단
 *   k6 run -e RUN_ID=s1-400 -e VUS=400 k6/spike.js       # 단일 레벨
 */

const SINGLE_VUS = Number(__ENV.VUS || 0);
const HOLD = __ENV.HOLD || '30s';

// 계단식 급증. 램프를 5초로 짧게 둬 "서서히 늘리는" 부하와 구분한다.
const ladder = [
    { duration: '5s', target: 100 }, { duration: HOLD, target: 100 },
    { duration: '5s', target: 200 }, { duration: HOLD, target: 200 },
    { duration: '5s', target: 400 }, { duration: HOLD, target: 400 },
    { duration: '5s', target: 600 }, { duration: HOLD, target: 600 },
    { duration: '5s', target: 0 },
];

export const options = {
    scenarios: {
        spike: SINGLE_VUS > 0
            ? {
                executor: 'constant-vus',
                vus: SINGLE_VUS,
                duration: HOLD,
            }
            : {
                executor: 'ramping-vus',
                startVUs: 0,
                stages: ladder,
                gracefulRampDown: '5s',
            },
    },
    /*
     * 기본값 60초로는 배출 대기를 끝내지 못한다 — teardown 이 큐가 비워질 때까지 기다리기 때문이다.
     * DRAIN_TIMEOUT 보다 넉넉해야 "배출 미완료" 를 결과로 볼 수 있다(타임아웃으로 죽으면 아무것도 안 남는다).
     */
    setupTimeout: '60s',
    teardownTimeout: __ENV.TEARDOWN_TIMEOUT || '20m',
    thresholds: {
        // 수락 계층은 XADD 한 번이다. 여기가 느려지면 비동기화가 무의미해진다.
        'http_req_duration{name:request-code}': ['p(95)<500', 'p(99)<1000'],
        // 유니크 주소를 쓰므로 중복 거절은 0 이어야 한다. 0 이 아니면 RUN_ID 재사용을 의심할 것.
        'mail_rejected_duplicate': ['count==0'],
        'checks': ['rate>0.99'],
    },
};

export function setup() {
    mailpitClear();
    console.log(`[spike] RUN_ID=${RUN_ID} mode=${SINGLE_VUS > 0 ? SINGLE_VUS + 'VU' : 'ladder'}`);
    // 카운터는 앱 기동 이후 누적이므로 시작 시점을 찍어 둔다.
    return snapshotCounters();
}

export default function () {
    requestCode();
    // VU 1개 ≈ 초당 1요청. VU 수를 그대로 도착률로 읽을 수 있어 해석이 쉽다.
    sleep(1);
}

export function teardown(base) {
    reportProcessing(`spike ${SINGLE_VUS > 0 ? SINGLE_VUS + 'VU' : 'ladder'}`, base);
}
