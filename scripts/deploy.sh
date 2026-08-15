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
        RESPONSE=$(curl -s http://localhost:${TARGET_PORT}/actuator/health)
        UP_COUNT=$(echo $RESPONSE | grep 'UP' | wc -l)

        if [ $UP_COUNT -ge 1 ]; then
                echo ">>> Health check Success!"
                break
        fi

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

echo ">>> Cleaning up unused images..."
sudo docker image prune -af

echo ">>> Deploy Success :) !!!!!!!!!!!!!!!!!!!"
