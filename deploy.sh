#!/bin/bash
# deploy.sh - 로컬에서 실행하여 서버에 배포

set -e

SERVER="168.107.31.154"
USER="ubuntu"
KEY="/Users/jason/IdeaProjects/can-agent/ssh-key-2026-06-25.key"
REMOTE_DIR="~/projects/can-agent"

echo "1. 빌드 중..."
./gradlew bootJar -x test -q

echo "2. JAR 서버에 복사 중..."
scp -i "$KEY" build/libs/can-agent-0.1.0.jar "$USER@$SERVER:$REMOTE_DIR/build/libs/"

echo "3. Docker 이미지 재빌드 및 재시작..."
ssh -i "$KEY" "$USER@$SERVER" "cd ~/projects && docker compose up -d --build can-agent"

echo "4. 테스트 중..."
sleep 15
STATUS=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 5 "http://$SERVER/can-agent")
if [ "$STATUS" = "200" ] || [ "$STATUS" = "302" ]; then
    echo "✅ 배포 성공! (HTTP $STATUS)"
    echo "   접속: http://$SERVER/can-agent"
else
    echo "❌ 배포 실패 (HTTP $STATUS)"
    echo "   로그: ssh -i $KEY $USER@$SERVER 'docker logs projects-can-agent-1 --tail 30'"
    exit 1
fi
