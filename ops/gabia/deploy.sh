#!/bin/bash
# 손으로 배포할 때: /opt/qstory/deploy.sh
# .env 의 GIT_REF 브랜치 끝 커밋을 받아 이미지를 다시 만들고 띄운다.
# 자동 배포(autodeploy.sh)와 같은 잠금을 써서 둘이 겹쳐 돌지 않는다.
set -euo pipefail
cd /opt/qstory
exec 9>/opt/qstory/.deploy.lock
flock 9
. ./.env
if [ ! -d src/.git ]; then git clone https://github.com/qstory-ai/Q-story-be.git src; fi
git -C src fetch -q --depth 1 origin "$GIT_REF"
git -C src checkout -q -B deploy FETCH_HEAD
echo "deploying $(git -C src rev-parse --short HEAD) ($GIT_REF)"
docker compose build app
docker compose up -d
mkdir -p .autodeploy
git -C src rev-parse HEAD > .autodeploy/deployed
docker image prune -f >/dev/null
