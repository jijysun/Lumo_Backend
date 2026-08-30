import { requestCode, mailpitClear, reportProcessing, snapshotCounters, RUN_ID } from './lib/lumo.js';

/*
 * S2 / S3 — 스트레스 테스트 (처리 계층)
 *
 * 답하는 질문:
 *   S2 "워커 풀이 지속 부하에서 초당 몇 건을 배출할 수 있는가 (상한은 어디인가)"
 *   S3 "그 상한이 워커 수에 따라 어떻게 변하는가"  ← G-4(워커 15개의 근거)
 *
 * <b>constant-arrival-rate 를 쓰는 이유</b> — VU 기반(closed model)은 서버가 느려지면
 * VU 가 응답을 기다리느라 요청률이 함께 떨어진다(coordinated omission). 그러면 처리량 상한이
 * 영원히 보이지 않는다. 도착률을 고정해야 "들어오는 양 > 나가는 양" 인 지점이 드러난다.
 *
 * 실행:
 *   k6 run -e RUN_ID=s2-50 -e RATE=50  -e DURATION=5m k6/stress.js
 *
 * S3 스윕(워커 수를 바꿔가며 반복):
 *   MAIL_WORKER_COUNT=3  → RATE 를 고정하고 배출률·백로그를 비교
 *   MAIL_WORKER_COUNT=5 / 10 / 15 / 20 ...
 */

const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '5m';

export const options = {
    scenarios: {
        stress: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            // 도착률을 지키지 못하면 k6 가 경고한다. 그 경고 자체가 "수락 계층이 밀렸다" 는 신호다.
            preAllocatedVUs: Number(__ENV.PRE_VUS || Math.max(50, RATE)),
            maxVUs: Number(__ENV.MAX_VUS || RATE * 10),
        },
    },
    /*
     * 기본값 60초로는 배출 대기를 끝내지 못한다 — teardown 이 큐가 비워질 때까지 기다리기 때문이다.
     * DRAIN_TIMEOUT 보다 넉넉해야 "배출 미완료" 를 결과로 볼 수 있다(타임아웃으로 죽으면 아무것도 안 남는다).
     */
    /*
     * origin.ddotg.dev 는 Cloudflare Origin CA 인증서를 <b>직접</b> 제시한다.
     * 이 CA 는 공개 신뢰 체인에 없다 — 설계상 Cloudflare 엣지만 신뢰하는 인증서다.
     * 실측(20260829): 검증 ON → HTTP 000(핸드셰이크 실패) / 검증 OFF → 502(경로 정상).
     *
     * k6 는 커스텀 CA 번들을 지원하지 않으므로 검증을 끄는 것이 유일한 방법이다.
     * 측정 대상이 본인 소유 오리진이고, Cloudflare 프록시를 <b>일부러 우회</b>하는 것이
     * 목적(DDoS 보호·캐싱이 개입하면 측정이 오염된다)이므로 수용 가능한 트레이드오프다.
     *
     * 검증을 되살리려면 -e STRICT_TLS=true (api.ddotg.dev 처럼 공개 인증서를 쓸 때).
     */
    insecureSkipTLSVerify: __ENV.STRICT_TLS !== 'true',
    setupTimeout: '60s',
    teardownTimeout: __ENV.TEARDOWN_TIMEOUT || '20m',
    thresholds: {
        'http_req_duration{name:request-code}': ['p(95)<500'],
        'mail_rejected_duplicate': ['count==0'],
    },
};

export function setup() {
    mailpitClear();
    console.log(`[stress] RUN_ID=${RUN_ID} rate=${RATE}/s duration=${DURATION}`);
    // 카운터는 앱 기동 이후 누적이므로 시작 시점을 찍어 둔다.
    return snapshotCounters();
}

export default function () {
    requestCode();
}

export function teardown(base) {
    /*
     * 부하가 끝난 뒤에도 워커는 계속 돈다. 여기서 배출이 끝날 때까지 기다린 시간이
     * 곧 "밀린 양" 이다 — 도착률이 배출률을 넘었다면 이 값이 급격히 커진다.
     */
    reportProcessing(`stress ${RATE}/s ${DURATION}`, base);
}
