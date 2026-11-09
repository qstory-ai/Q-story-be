#!/bin/bash
# 가비아 서버 자동 배포. qstory-autodeploy.timer 가 2분마다 부른다.
#
# 서버 SSH는 사무실 IP에서만 열려 있어 GitHub Actions가 서버로 밀어 넣을 수 없다.
# 그래서 서버가 직접 GitHub를 들여다본다(pull 방식).
#   1. origin/$GIT_REF 끝 커밋이 지금 띄운 커밋(.autodeploy/deployed)과 같으면 끝.
#   2. 다르면 그 커밋의 ci 워크플로 test 결과를 GitHub 공개 API로 본다.
#      성공이면 배포. 실패면 배포하지 않고 알린다. 진행 중이면 다음 회차에 다시 본다.
#      20분이 지나도 결과가 없으면 그냥 배포한다.
#   3. 배포 = 이미지 빌드 → app 재시작 → /health/ready 확인(최대 5분).
#      빌드가 실패하면 기존 컨테이너가 그대로 돈다.
#      새 컨테이너가 준비되지 않으면 직전 이미지(qstory-be:prev)로 되돌린다.
#   4. 결과를 디스코드 #배포 채널로 보낸다(notify.env). 성공은 알림음 없이, 실패는 @개발 멘션.
#
# notify.env (선택, 깃에 올리지 않음)
#   DISCORD_WEBHOOK_DEPLOY  비어 있으면 알리지 않는다
#   DISCORD_ROLE_DEV_ID     실패 때 멘션할 역할
#   DEPLOY_LABEL            메시지 머리말. 예: 백엔드(가비아)
#   NOTIFY_SUCCESS          0이면 실패만 알린다(개발 서버)
#
# 설치 (서버 /opt/qstory 기준)
#   install -m 755 deploy.sh autodeploy.sh /opt/qstory/
#   sudo install -m 644 qstory-autodeploy.service qstory-autodeploy.timer /etc/systemd/system/
#   sudo systemctl daemon-reload && sudo systemctl enable --now qstory-autodeploy.timer
# 운영
#   systemctl list-timers qstory-autodeploy.timer    다음 실행 시각
#   journalctl -u qstory-autodeploy -n 50             최근 실행 기록
#   cat /opt/qstory/.autodeploy/last.log              마지막 배포 로그
#   sudo systemctl stop qstory-autodeploy.timer       자동 배포 멈춤 (다시: start)
#   rm /opt/qstory/.autodeploy/held-<sha>             보류된 커밋 다시 시도
#   docker image tag qstory-be:prev qstory-be:local && docker compose up -d app   직전 이미지로 되돌리기
# Railway와 달리 app 컨테이너를 바꿔 끼우는 30초~1분 동안 502가 난다.
set -uo pipefail
cd /opt/qstory
exec 9>/opt/qstory/.deploy.lock
flock -n 9 || exit 0

. ./.env
[ -f notify.env ] && . ./notify.env
# 윈도우에서 만든 env 파일이면 값 끝에 CR이 붙어 curl이 URL을 거부한다. 걸러낸다.
DISCORD_WEBHOOK_DEPLOY=${DISCORD_WEBHOOK_DEPLOY:-}; DISCORD_WEBHOOK_DEPLOY=${DISCORD_WEBHOOK_DEPLOY%$'\r'}
DISCORD_ROLE_DEV_ID=${DISCORD_ROLE_DEV_ID:-}; DISCORD_ROLE_DEV_ID=${DISCORD_ROLE_DEV_ID%$'\r'}
DEPLOY_LABEL=${DEPLOY_LABEL:-}; DEPLOY_LABEL=${DEPLOY_LABEL%$'\r'}
NOTIFY_SUCCESS=${NOTIFY_SUCCESS:-1}; NOTIFY_SUCCESS=${NOTIFY_SUCCESS%$'\r'}
REPO=qstory-ai/Q-story-be
LABEL=${DEPLOY_LABEL:-백엔드(가비아)}
STATE=.autodeploy
LOG=$STATE/last.log
CI_WAIT_MAX=1200
READY_WAIT_MAX=300
mkdir -p "$STATE"

notify() { # notify ok|hold|fail "본문"
  local kind=$1 text=$2 flags=4096 roles='[]' prefix=''
  [ -n "${DISCORD_WEBHOOK_DEPLOY:-}" ] || return 0
  # 개발 서버(NOTIFY_SUCCESS=0)는 성공과 CI 보류를 알리지 않는다. 운영 서버가 같은 커밋을 이미 알린다.
  if [ "$kind" != fail ] && [ "${NOTIFY_SUCCESS:-1}" = 0 ]; then return 0; fi
  if [ "$kind" != ok ]; then
    flags=0
    if printf '%s' "${DISCORD_ROLE_DEV_ID:-}" | grep -Eq '^[0-9]{5,25}$'; then
      prefix="<@&$DISCORD_ROLE_DEV_ID> "
      roles="[\"$DISCORD_ROLE_DEV_ID\"]"
    fi
  fi
  local body code
  body=$(jq -n --arg c "$prefix$text" --argjson r "$roles" --argjson f "$flags" \
    '{content: ($c | .[0:1990]), flags: $f, allowed_mentions: {parse: [], roles: $r}}')
  for _ in 1 2 3 4; do
    code=$(curl -sS -o "$STATE/discord.out" -w '%{http_code}' -m 15 -H 'content-type: application/json' \
      -d "$body" "$DISCORD_WEBHOOK_DEPLOY") || true  # 연결 실패면 curl이 000을 찍는다
    if [ "$code" = 429 ]; then
      sleep "$(jq -r '(.retry_after // 1) | ceil' "$STATE/discord.out" 2>/dev/null || echo 2)"; sleep 1
      continue
    fi
    break
  done
  echo "discord status=$code"
}

ready() { # 새 app 컨테이너가 DB까지 붙어 준비됐는지 최대 READY_WAIT_MAX초 기다린다
  local waited=0
  while [ "$waited" -lt "$READY_WAIT_MAX" ]; do
    if docker compose exec -T caddy wget -qO- -T 5 http://app:8080/health/ready 2>/dev/null | grep -q '"ok":true'; then
      return 0
    fi
    sleep 5; waited=$((waited + 5))
  done
  return 1
}

remote=$(git ls-remote "https://github.com/$REPO.git" "refs/heads/$GIT_REF" 2>/dev/null | cut -f1)
[ -n "$remote" ] || exit 0
deployed=$(cat "$STATE/deployed" 2>/dev/null || git -C src rev-parse HEAD 2>/dev/null || true)
[ "$remote" = "$deployed" ] && exit 0
[ -f "$STATE/held-$remote" ] && exit 0
short=${remote:0:7}

# CI 결과 확인. 공개 저장소라 토큰 없이 읽는다(IP당 시간당 60회, 2분 주기면 충분).
[ -f "$STATE/seen-$remote" ] || date +%s > "$STATE/seen-$remote"
ci=$(curl -fsS -m 15 -H 'Accept: application/vnd.github+json' \
  "https://api.github.com/repos/$REPO/commits/$remote/check-runs?check_name=test" 2>/dev/null \
  | jq -r '.check_runs | sort_by(.started_at) | last | if . == null then "none" else "\(.status):\(.conclusion // "")" end' 2>/dev/null) || ci=error
note=''
case "$ci" in
  completed:success) ;;
  completed:*)
    touch "$STATE/held-$remote"
    url="https://github.com/$REPO/commit/$remote/checks"
    echo "CI $ci, $short 배포 보류"
    notify hold "[배포] $LABEL **보류** · $short CI(test)가 통과하지 못해 배포하지 않았습니다 · <$url>"
    exit 0 ;;
  none|error)
    age=$(( $(date +%s) - $(cat "$STATE/seen-$remote") ))
    [ "$age" -ge "$CI_WAIT_MAX" ] || exit 0
    note=' (CI 결과 없이 배포)' ;;
  *) exit 0 ;;  # queued / in_progress
esac

exec > >(tee "$LOG") 2>&1
echo "$(date -Is) deploy $short ($GIT_REF) ci=$ci"
git -C src fetch -q --depth 1 origin "$remote" && git -C src checkout -q -B deploy FETCH_HEAD || {
  echo "git fetch 실패, 다음 회차에 다시 시도"; exit 0; }
title=$(git -C src log -1 --format=%s)
pr=$(printf '%s' "$title" | grep -oE '\(#[0-9]+\)$' | tr -d '()' || true)
ref="${pr:+$pr }$short"

if docker image inspect qstory-be:local >/dev/null 2>&1; then
  docker image tag qstory-be:local qstory-be:prev
fi
if ! docker compose build app; then
  touch "$STATE/held-$remote"
  notify fail "[배포] $LABEL **실패** · $ref $title · 이미지 빌드 실패, 기존 서버 그대로 · 서버 $LOG"
  exit 1
fi
docker compose up -d app
if ready; then
  echo "$remote" > "$STATE/deployed"
  rm -f "$STATE"/seen-* "$STATE"/held-*
  docker image prune -f >/dev/null
  notify ok "[배포] $LABEL 성공 · $ref $title$note · <https://$SITE_ADDRESS>"
  exit 0
fi

echo "새 컨테이너가 ${READY_WAIT_MAX}초 안에 준비되지 않아 직전 이미지로 되돌린다"
docker compose logs --no-color --tail 80 app || true
touch "$STATE/held-$remote"
docker image tag qstory-be:prev qstory-be:local
docker compose up -d app
if ready; then back='직전 버전으로 되돌림'; else back='되돌린 버전도 응답 없음, 바로 확인 필요'; fi
notify fail "[배포] $LABEL **실패** · $ref $title · 헬스체크 실패, $back · 서버 $LOG"
exit 1
