#!/bin/bash
# deploy.sh - 수동 배포 (CI/CD 워크플로와 동일 방식: 서버가 origin/main 을 git pull 후 소스에서 Docker 빌드).
#
# ⚠️ 실계좌 서버다. 미국 실서버 준비(원화→USD 환전 · prod toss.broker.enabled=true ·
#    DB 마이그레이션 · 유니버스 적재 · 승인) 완료 전에는 배포하지 말 것.
# 참고: 멀티스테이지 Dockerfile 전환으로 로컬 bootJar→scp 방식은 폐지됨. 서버가 git checkout을 빌드한다.

set -e

SERVER="168.107.31.154"
USER="ubuntu"
KEY="$(cd "$(dirname "$0")" && pwd)/ssh-key-2026-06-25.key"  # repo-root 상대경로 (머신 이식성)

echo "0. 로컬 커밋을 origin/main 에 반영 (서버는 origin/main 을 pull)"
echo "   → 아직 push 안 했다면: git push origin main"
read -p "   origin/main 최신 상태입니까? 계속하려면 Enter (중단 Ctrl+C) " _

echo "1. 서버에서 git pull + Docker 소스 빌드·재시작..."
ssh -i "$KEY" "$USER@$SERVER" 'set -e; cd ~/projects/can-agent && git pull origin main && cd ~/projects && docker compose up -d --build can-agent'

echo "2. 헬스체크..."
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
