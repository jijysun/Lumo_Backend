#!/usr/bin/env bash
#
# (D-3) -e 추가. 이전에는 -uo pipefail 뿐이라 중간 실패가 조용히 무시됐다.
#   가장 위험했던 지점은 nginx 였다 — `nginx -s reload` 가 실패해도 바로 다음 줄에서
#   이전 컨테이너를 내려버려, nginx 는 죽은 포트를 가리킨 채 전면 502 가 된다.
#   -e 가 있으면 reload 실패 시점에 멈추므로 이전 컨테이너가 살아남는다.
#
# ⚠️ -e 와 pipefail 을 같이 쓰면 <b>헬스체크의 curl 실패가 스크립트를 죽인다.</b>
#   기동 대기 중의 연결 실패는 정상이고 재시도해야 하는 상황이므로,
#   그 한 줄만 아래에서 `|| STATUS=""` 로 명시적으로 예외 처리한다.
set -euo pipefail

# 헬스체크가 응답 JSON 의 최상위 status 만 보도록 jq 로 파싱한다 (C-5).
# 없으면 조용히 잘못된 판정을 하느니 즉시 멈추는 편이 낫다.
if ! command -v jq >/dev/null 2>&1; then
        echo ">>> jq 가 필요합니다. 설치 후 다시 실행하세요: sudo apt-get update && sudo apt-get install -y jq"
        exit 1
fi

# (D-2) 이전에는 restart 만 했다. restart 는 <b>컨테이너가 이미 존재해야</b> 동작하므로,
#   EC2 를 재생성한 직후에는 실패한다. 그런데 set -e 가 없어 스크립트가 그대로 진행됐고,
#   결과적으로 "관측 스택이 하나도 안 뜬 채 성공한 배포" 가 만들어졌다.
#   Prometheus 가 없으면 측정 데이터가 아예 수집되지 않는데 그 사실이 로그에 드러나지 않는다.
#
#   up -d    → 없으면 만든다 (재생성된 EC2 대응)
#   restart  → 마운트된 설정 파일을 다시 읽힌다.
#              bind mount 파일이 바뀌어도 컨테이너 "정의" 는 그대로라 up -d 만으로는 반영되지 않는다.
echo ">>> Ensuring monitoring stack exists ..."
sudo docker compose up -d prometheus grafana loki promtail

echo ">>> Reloading monitoring configs ..."
sudo docker compose restart prometheus grafana loki promtail

EXIST_BLUE=$(sudo docker ps -q -f name=Lumo_Blue)

if [ -z "$EXIST_BLUE" ]; then
        TARGET_COLOR="Blue"
        TARGET_PORT=8081
        BEFORE_COLOR="Green"
        BEFORE_PORT=8082
else
        TARGET_COLOR="Green"
        TARGET_PORT=8082
        BEFORE_COLOR="Blue"
        BEFORE_PORT=8081
fi

echo ">>> ${BEFORE_COLOR} is running! start deploying new ${TARGET_COLOR}"

# ── 로컬 MySQL 프로필 자동 기동 ────────────────────────────────────────────
# mysql 서비스는 profiles: ["localdb"] 라 명시하지 않으면 뜨지 않는다.
# RDS 전환 시 compose 를 수정하지 않으려고 Blue/Green 의 depends_on 에도 넣지 않았는데,
# 그 대가로 EC2 를 새로 만들면 DB 없이 앱만 떠서 Flyway 가 죽는다 (20260828 실제 발생).
#
# .env 의 MYSQL_HOST 가 컨테이너 서비스명이면 로컬 DB 구성이므로 여기서 함께 띄운다.
# RDS 로 전환하면 MYSQL_HOST 가 엔드포인트로 바뀌어 이 블록을 자동으로 건너뛴다.
if grep -qE '^MYSQL_HOST=mysql[[:space:]]*$' .env 2>/dev/null; then
        echo ">>> Local MySQL profile detected - bringing it up first"
        # --wait 로 헬스체크 통과까지 기다린다. 첫 기동은 시스템 테이블 생성 때문에 수십 초가 걸리는데,
        # 기다리지 않으면 앱이 먼저 떠서 Flyway 가 연결 실패로 죽는다.
        sudo docker compose --profile localdb up -d --wait --wait-timeout 180 mysql
        echo ">>> MySQL is healthy"
fi

sudo docker compose up -d ${TARGET_COLOR}

echo "${TARGET_PORT} -> Try Health Check.."

for retry_cnt in {1..10}
do
        echo ">>> Health check try ${retry_cnt}.."

        # 최상위 status 만 본다.
        # application.yaml 이 show-details: always 라 응답에 컴포넌트별 status 가 전부 실린다.
        #   {"status":"DOWN","components":{"db":{"status":"DOWN"},"diskSpace":{"status":"UP"},...}}
        # 예전 방식(grep 'UP' | wc -l)은 최상위가 DOWN 이어도 diskSpace/ping 의 UP 에 걸려 통과했고,
        # 그 결과 DB 에 못 붙은 컨테이너로 nginx 트래픽이 전환될 수 있었다.
        # 응답이 없거나 JSON 이 아니면 jq 가 빈 문자열을 내도록 // empty 와 2>/dev/null 로 방어한다.
        # `|| STATUS=""` 가 없으면 set -e + pipefail 조합에서 curl 실패 시 스크립트가 즉시 죽는다.
        # 아직 기동 중이라 연결이 안 되는 것은 정상이며, 재시도로 넘어가야 한다.
        STATUS=$(curl -s --max-time 5 "http://localhost:${TARGET_PORT}/actuator/health" \
                | jq -r '.status // empty' 2>/dev/null) || STATUS=""

        if [ "${STATUS}" = "UP" ]; then
                echo ">>> Health check Success! (status=UP)"
                break
        fi

        echo ">>> not ready yet (status=${STATUS:-no-response})"

        if [ $retry_cnt -eq 10 ]; then
            echo ">>> Health check FAILED, Stop Deploying..."
            sudo docker compose stop ${TARGET_COLOR}
            exit 1
        fi

        sleep 7
done


echo ">>> Change Nginx port to ${TARGET_PORT}"
echo "set \$service_url http://127.0.0.1:${TARGET_PORT};" | sudo tee /etc/nginx/conf.d/service-url.inc
sudo nginx -s reload

echo ">>> Shutting down previous container (${BEFORE_COLOR})..."
sudo docker compose stop ${BEFORE_COLOR}

echo ">>> Removing previous container (${BEFORE_COLOR})..."
sudo docker compose rm -f ${BEFORE_COLOR}

echo ">>> Cleaning up dangling images..."
# -a 를 쓰지 않는다. -af 는 "컨테이너가 참조하지 않는 모든 이미지"를 지우므로
# 직전 버전과 대조군(:<커밋SHA>) 태그까지 전부 날아가 롤백·재현 배포가 불가능해진다.
# -f 는 태그 없는(dangling) 레이어만 정리한다.
sudo docker image prune -f

echo ">>> Deploy Success :) !!!!!!!!!!!!!!!!!!!"
