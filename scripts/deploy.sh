#!/usr/bin/env bash
set -uo pipefail

# 헬스체크가 응답 JSON 의 최상위 status 만 보도록 jq 로 파싱한다 (C-5).
# 없으면 조용히 잘못된 판정을 하느니 즉시 멈추는 편이 낫다.
if ! command -v jq >/dev/null 2>&1; then
        echo ">>> jq 가 필요합니다. 설치 후 다시 실행하세요: sudo apt-get update && sudo apt-get install -y jq"
        exit 1
fi

echo ">>> Updating Monitoring Tools ..."
sudo docker compose restart promtail loki prometheus grafana

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
        STATUS=$(curl -s --max-time 5 "http://localhost:${TARGET_PORT}/actuator/health" \
                | jq -r '.status // empty' 2>/dev/null)

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
