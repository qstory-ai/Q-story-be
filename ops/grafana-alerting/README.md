# Grafana Cloud 알림·대시보드 (Terraform)

[Q-story-be#3](https://github.com/qstory-ai/Q-story-be/issues/3) 대응. `logback-spring.xml`이
로그를 Grafana Cloud Loki로 실어 나르기만 하고 정작 알림 규칙/채널이 없던 걸, 여기 Terraform으로
코드화한다. 알림 규칙·알림 정책·대시보드·바깥 생존 확인·Postgres 데이터소스가 모두 이 폴더에 있다.

| 파일 | 내용 |
|---|---|
| `alert_rules.tf` | 로그 기반 알림 규칙 12개 |
| `notification_policy.tf` | severity별로 어디로·얼마나 자주 보낼지 |
| `contact_points.tf` | Discord(warning용, critical용 @here) |
| `dashboards.tf` | "Q-Story 백엔드 상태"(Loki), "Q-Story 대화 품질"(Postgres) |
| `synthetic.tf` | 바깥에서 1분마다 `/health/ready` 확인 + 닿지 않으면 알림 |
| `datasource_postgres.tf` | 대화 품질 대시보드용 읽기 전용 DB 연결 |

## 뭘 감시하나

`alert_rules.tf`의 `local.log_alerts`에 로그 규칙이 있다. 대부분 5분 창에서 특정 로그 패턴이
threshold(변수, `variables.tf`)를 넘으면 Discord로 알린다. `backend-silent`만 반대로 "로그가 없으면" 울린다.

| 규칙 | severity | 로그 태그 | 어디서 나는지 |
|---|---|---|---|
| `error-log-rate-spike` | warning | `\|= "ERROR"` | 전체 ERROR 레벨 로그 |
| `openrouter-provider-failure` | warning | `openrouter-http.failed` | `OpenRouterClient.java` (TTS/채팅완성/이미지 공용) |
| `gemini-tts-failure` | **critical** | `gemini-http.failed`, `gemini-tts.empty-audio` | `GeminiTtsClient.java` (실장애 이력) |
| `rtzr-stt-failure` | warning | `rtzr-http.failed`, `rtzr-transcription.failed` | `RtzrSttClient.java` (인증/제출/폴링 실패 + status="failed") |
| `uncaught-5xx` | **critical** | `request.failed` | `GlobalExceptionHandler.java` (미처리 예외가 5xx로 응답할 때) |
| `server-5xx-ratio` | **critical** | `http.request`의 `status=5xx` / 전체 요청 | `RequestIdFilter.java` (5분 요청 20건 이상일 때 5% 초과) |
| `db-pool-exhaustion` | **critical** | `Connection is not available` | HikariCP 기본 타임아웃 경고 |
| `companion-retention-failure` | warning | `companion-chat-retention.failed` | `CompanionChatRetentionScheduler.java` (일일 정리 실패) |
| `gemini-tts-quota` | **critical** | `gemini-http.failed` + `status=429\|402` | `GeminiTtsClient.java` (하루 한도 초과·크레딧 소진, 1건이면 울림) |
| `backend-boot-failure` | **critical** | `APPLICATION FAILED TO START` | Spring Boot 부팅 실패(be#18 같은 배포 장애) |
| `backend-silent` | **critical** | `app.heartbeat`가 15분째 없음 | `HeartbeatLogger.java` (요청이 없어도 5분마다 찍힘) |
| `slow-requests` | warning | `http.request`의 `duration_ms` p95 | `RequestIdFilter.java` (실시간 분기 생성 제외) |
| `client-error-spike` | warning | `client.error` | `ClientErrorController.java` (브라우저에서 보낸 에러) |
| `fcm-push-failure` | warning | `fcm.send-failed` (단 `disabled=true` 제외) | `FcmClient.java`, `PushDispatcher.java` (앱 푸시 발송 실패) |
| `backend-unreachable` | **critical** | `probe_success` (Prometheus) | `synthetic.tf` - 바깥에서 본 `/health/ready` |

로그 태그는 실제 소스에서 그대로 가져온 것들이다(추측 아님) - 태그 문자열이 바뀌면 이 파일도
같이 고쳐야 한다.

**severity 라벨**은 실제 파급도 기준:
- `critical` — 이용자에게 5xx가 나가거나 시스템 전체가 흔들리는 실패. 즉시 대응.
- `warning` — 개별 요청 실패지만 상위가 폴백/재시도로 흡수. 반복되면 원인 확인.

보내는 곳은 `notification_policy.tf`가 severity로 나눈다.
- critical → `qstory-backend-discord-critical`: 메시지 맨 앞 `@here`, 10초 묶음, 해결 전까지 1시간마다 다시.
- warning → `qstory-backend-discord`: 2분 묶음, 같은 알림은 12시간에 한 번.

Discord 메시지에는 firing/resolved 요약, description, Grafana Explore 링크, 규칙별 런북 링크가 함께
실린다(`contact_points.tf` 템플릿 참고).

> 알림 정책은 스택 전체 트리를 이 파일 내용으로 덮어쓴다. Grafana UI에서 정책을 손으로 고치면 다음
> apply 때 되돌아간다.

## 대시보드

`Dashboards > qstory-backend` 폴더.

- **Q-Story 백엔드 상태**: 분당 요청 수, 5xx 비율, 경로별 응답 시간 p95, 외부 AI·음성 실패, 브라우저
  에러(종류별), 서버 메모리, 최근 24시간 Gemini TTS 사용량(한도 `tts_daily_limit` 대비 색), 최근 에러 로그.
- **Q-Story 대화 품질**: 그레텔 대화(Q-31)와 이야기 진행. 하루 대화 열림(진입 방식별), 질문 초대별 결과
  (초대·아이 말·도움 요청·행동 실행·예시 후 선택·실행률), 도움 단계 분포, 그레텔 답 종류, 대화 오류율,
  대화당 평균 왕복, 하루 이야기 시작·완주. 상단 `트래픽` 변수로 BETA/QA/DEV를 고른다(기본 BETA).
  Postgres 연결 값이 있을 때만 만든다(아래).

## 자동화

`.github/workflows/grafana-alerting.yml`이 파이프라인이다. 앱 배포(Railway)와는 완전히 분리 -
`ops/grafana-alerting/**`이 바뀔 때만 도는 별도 파이프라인이라 서로 재배포를 유발하지 않는다.

```
PR       →  terraform plan을 Actions 로그에 노출(변경 미리보기, apply 안 함)
main push →  terraform apply -auto-approve (실제 Grafana Cloud에 반영)
```

state는 Terraform Cloud(무료 티어) 워크스페이스에 보관하되 실행은 GH Actions 러너에서 한다
(워크스페이스 Execution Mode = **Local**). "state는 관리되고, 로그·시크릿은 GitHub 안"이라는 절충.

### 1회성 세팅 (Grafana/TFC/Discord 콘솔에서)

1. **Grafana Cloud 토큰**: 알림·폴더 외에 대시보드와 데이터소스도 만들므로 `dashboards:write`,
   `datasources:write`가 더 필요하다. 서비스 계정 토큰(Administration → Service accounts, Editor 이상)이면
   이 권한이 모두 들어 있다.
2. **Loki 데이터소스 UID**: Grafana Cloud 콘솔 → Connections → Data sources → Loki → Details.
   `logback-spring.xml`이 이미 쓰고 있는 그 데이터소스와 같은 UID여야 한다 - 새로 만들지 않는다.
3. **Discord Webhook URL**: 알림 받을 서버 → 채널 설정 → **연동(Integrations)** → **웹후크(Webhooks)** → 새 웹후크 → 이름·아바타 지정 → **웹후크 URL 복사**. `https://discord.com/api/webhooks/<id>/<token>` 형태.
4. **Terraform Cloud 워크스페이스**: [app.terraform.io](https://app.terraform.io) 가입 → org 생성
   → 워크스페이스 생성(예: `qstory-alerting`) → 설정에서 **Execution Mode = Local**로 변경
   (그래야 TFC가 실행 안 하고 state만 잡는다).
5. **TFC User/Team Token**: TFC → User Settings → Tokens → 새 토큰 생성. GH Actions가 state에 접근할 때 씀.

### GitHub 저장소 설정 (Settings → Secrets and variables → Actions)

**Variables** (평문):
- `TF_CLOUD_ORGANIZATION` — TFC 조직 이름
- `TF_WORKSPACE` — TFC 워크스페이스 이름 (예: `qstory-alerting`)
- `TF_VAR_APP_ENV` — Loki 로그 label의 `env` 값. Railway의 `SPRING_PROFILES_ACTIVE`(지금 `prod`)와 같아야 한다. 다르면 모든 규칙이 로그를 못 찾고, 실패 규칙은 조용하며 backend-silent만 울린다.

**Secrets** (암호화):
- `TF_API_TOKEN` — 5번에서 만든 TFC 토큰
- `TF_VAR_GRAFANA_URL` — 예: `https://<stack>.grafana.net`
- `TF_VAR_GRAFANA_AUTH` — 1번의 Access Policy 토큰
- `TF_VAR_LOKI_DATASOURCE_UID` — 2번의 UID
- `TF_VAR_DISCORD_WEBHOOK_URL` — 3번의 Discord Webhook URL
- `TF_VAR_POSTGRES_HOST`, `TF_VAR_POSTGRES_USER`, `TF_VAR_POSTGRES_PASSWORD` — 대화 품질 대시보드용(선택, 아래)
- `TF_VAR_SM_URL`, `TF_VAR_SM_ACCESS_TOKEN` — 바깥 생존 확인용(선택, 아래)

### 첫 apply

세팅 후 `ops/grafana-alerting/README.md` 또는 규칙 파일을 살짝 건드려 커밋·머지하면 워크플로가
처음으로 apply를 수행해 초기 state를 만든다. 이후에는 파일이 바뀔 때마다 자동 반영.

### 로컬에서 apply/plan을 돌리려면

GH Actions에 의존하고 싶지 않은 경우, 같은 env-var 두 개를 셸에서 export하고 TFC 자격증명을
`~/.terraformrc`에 세팅한 뒤 실행한다. TFC state를 그대로 사용하므로 GH Actions와 번갈아 써도
안전하다.

```
export TF_CLOUD_ORGANIZATION=<your-org>
export TF_WORKSPACE=qstory-alerting
export TF_VAR_grafana_url=...        # 아래 4개도 같은 방식으로
export TF_VAR_grafana_auth=...
export TF_VAR_loki_datasource_uid=...
export TF_VAR_discord_webhook_url=...
# ~/.terraformrc:
#   credentials "app.terraform.io" { token = "<TFC 토큰>" }
terraform -chdir=ops/grafana-alerting init
terraform -chdir=ops/grafana-alerting plan
```

`terraform.tfvars.example`은 로컬 참조용으로만 남겨두었다(TFC state를 쓰기 시작한 뒤에는 tfvars
파일보다 env-var 방식이 GH Actions와 동일해 관리가 편함).

## 대화 품질 대시보드 (Postgres 연결)

운영 DB에 Grafana 전용 읽기 계정을 만든다. 이 계정은 `grafana` 스키마의 뷰
(`src/main/resources/db/schema/066-grafana-views.sql`)만 읽고 원본 테이블은 못 읽는다. 그래서 아이 질문
문장 같은 metadata 원문은 Grafana에 보이지 않는다. 마이그레이션 066이 배포된 뒤 운영 DB에서 한 번:

```sql
create role grafana_reader login password '<긴 무작위 비밀번호>';
alter role grafana_reader set statement_timeout = '10s';
grant usage on schema grafana to grafana_reader;
grant select on all tables in schema grafana to grafana_reader;
```

Supabase 세션 풀러로 붙으므로 GitHub Secrets에는 `TF_VAR_POSTGRES_HOST`=`<풀러 호스트>:5432`,
`TF_VAR_POSTGRES_USER`=`grafana_reader.<프로젝트 ref>`, `TF_VAR_POSTGRES_PASSWORD`를 넣는다. 셋 중 하나라도
비면 데이터소스와 대화 품질 대시보드는 만들지 않는다. 뷰를 새로 추가하면 마이그레이션 끝의 grant가
다시 권한을 준다.

## 바깥에서 보는 생존 확인 (Synthetic Monitoring)

Grafana Cloud → Testing & synthetics → Synthetics → (처음이면 Initialize) → Config에서 API 주소와
액세스 토큰을 받아 `TF_VAR_SM_URL`, `TF_VAR_SM_ACCESS_TOKEN`에 넣는다. 그러면 서울·도쿄·싱가포르 공개
프로브가 1분마다 `/health/ready`를 확인하고(DB가 끊기면 503), 5분 동안 절반 넘게 실패하면
`backend-unreachable`(critical)이 울린다. 비워 두면 만들지 않는다.

## 런북

알림 메시지의 "런북" 링크가 아래 같은 이름의 항목으로 온다. 공통으로 먼저 Discord 메시지의 Grafana
Explore 링크로 원본 로그를 연다.

### error-log-rate-spike
1. 같이 울린 다른 규칙이 있으면 그쪽 런북부터.
2. 없으면 Explore에서 `|= "ERROR"`로 연 뒤 logger 이름별로 묶어 새로 생긴 실패 경로를 찾는다.
3. 최근 배포(Railway)와 시점이 겹치면 그 배포의 변경부터 의심.

### openrouter-provider-failure
1. 로그의 `context=`로 어느 호출(질문 분류·그레텔 답·분기 생성·이미지)인지 본다.
2. `status=401/403`이면 키 문제라 Railway의 `OPENROUTER_API_KEY`를 확인. `429`면 사용량 한도, `5xx`면
   OpenRouter 쪽 장애(status.openrouter.ai).
3. 지속되면 그레텔 대화와 질문 분기가 실패 응답으로 바뀐다. 운영 채널에 공지.

### gemini-tts-failure
1. `gemini-tts.empty-audio`면 200인데 오디오가 빈 경우. 모델 이름(`GEMINI_TTS_MODEL`) 변경·서비스 이상 확인.
2. `status=429/402`면 아래 gemini-tts-quota.
3. 그 밖의 status는 응답 본문(responseBody=)에 이유가 있다.

### gemini-tts-quota
1. `status=429`: 하루 또는 분당 한도. 대시보드 "오늘 Gemini TTS 사용량"으로 얼마나 썼는지 본다.
   한도 등급을 올리거나 다른 키로 바꾼다(Railway `GEMINI_API_KEY`). 하루 한도는 다음 날 풀린다.
2. `status=402`: 결제 크레딧 소진. Google AI Studio에서 충전.
3. 그동안 고정 음성이 없는 대사·그레텔 답은 기기 음성으로 대체된다.

### rtzr-stt-failure
1. `rtzr-http.failed`의 `context=`로 인증·제출·폴링 중 어디서 막혔는지 본다. 인증이면 Rtzr 키 만료.
2. `rtzr-transcription.failed`는 Rtzr가 인식을 실패로 돌려준 경우. 잡음·무음 녹음이 많은지 확인.

### uncaught-5xx
1. 로그의 `request_id`로 같은 요청의 다른 줄(`http.request`, 예외 스택)을 모은다.
2. 새 배포 직후면 되돌리기(Railway에서 이전 배포 Redeploy)를 먼저 고려.

### server-5xx-ratio
1. 백엔드 개요 대시보드의 "5xx 비율"·"경로별 응답 시간"에서 어느 경로인지 본다.
2. `uncaught-5xx`가 같이 울렸으면 코드 결함 쪽(그 런북), 아니면 같이 울린 provider 규칙(OpenRouter·Gemini·Rtzr)부터 확인.
3. 새 배포 직후면 되돌리기를 먼저 고려.

### db-pool-exhaustion
1. Supabase 대시보드에서 DB CPU·연결 수 확인. 오래 걸리는 쿼리가 커넥션을 잡고 있지 않은지 본다.
2. 급하면 Railway에서 재시작. `SUPABASE_DB_POOL_SIZE`는 Supabase 풀러 한도 안에서만 늘린다.

### companion-retention-failure
1. 다음 날 같은 시각(UTC 18:45)에 다시 돈다. 하루 실패는 지켜봐도 된다.
2. 이틀 이상 이어지면 로그의 `reason=`을 보고 고친다(90일 보관 원칙).

### backend-boot-failure
1. Railway 배포 로그에서 `Description:`과 `Action:` 줄을 본다. Flyway 마이그레이션 실패, 빈 설정값,
   엔티티·스키마 불일치가 흔한 원인.
2. Railway는 헬스체크를 통과하지 못한 배포를 붙이지 않으므로 이전 배포가 계속 서비스 중일 수 있다.
   `/health/ready`로 확인.
3. 바로 못 고치면 원인 커밋을 되돌려 main에 올린다.

### backend-silent
1. Railway 서비스가 Running인지, 최근 재시작·크래시가 있었는지.
2. 서비스는 살아 있는데 로그만 없으면 Loki 전송 문제. Railway의 `LOKI_URL`, `LOKI_API_KEY`(logs:write) 확인.
3. `/health/ready`가 200이면 서비스는 정상이고 관측만 끊긴 것.

### slow-requests
1. 대시보드 "경로별 응답 시간 p95"에서 어느 경로가 느린지 본다.
2. 그레텔 대화·질문 경로면 외부 AI·음성 지연(같이 울린 provider 규칙), 전체가 느리면 DB(db-pool-exhaustion).

### client-error-spike
1. Explore에서 `|= "client.error"`로 열어 `kind`·`route`·`release`로 묶는다.
2. 특정 `release`(프런트 배포)에서만 나면 그 배포를 Vercel에서 되돌린다.
3. `PLAYBACK`이 많으면 음성 파일 주소(Supabase Storage)·브라우저 자동재생 제한을 의심.

### fcm-push-failure
1. 인앱 알림은 정상으로 쌓이고 기기 푸시만 안 가는 상태다. 로그의 `reason=`으로 가른다.
2. `reason=auth`: 액세스 토큰을 못 받음. Railway `QSTORY_FCM_SERVICE_ACCOUNT_JSON`(키 JSON 원문 또는 base64)이 맞는지,
   Firebase 콘솔에서 그 서비스 계정 키가 폐기되지 않았는지 본다. `status=401/403`(`UNAUTHENTICATED`/`PERMISSION_DENIED`)도
   같은 쪽 - 서비스 계정에 Firebase Cloud Messaging 권한과 `QSTORY_FCM_PROJECT_ID`(비우면 JSON의 project_id)를 확인.
3. `UNAVAILABLE`·`INTERNAL`·`QUOTA_EXCEEDED`·`network`: FCM 쪽 장애나 한도. status.firebase.google.com 확인. 놓친 푸시는
   다시 보내지 않는다(인앱 알림으로 남아 있음).
4. `queue-full`: 발송 대기열(1000건) 포화 - 한꺼번에 알림이 쏟아진 경우. 지속되면 `AsyncConfig.pushExecutor` 크기를 본다.
5. 부팅 로그의 `fcm.disabled`는 키가 없거나 읽을 수 없다는 뜻이다(이때는 이 규칙이 울리지 않는다).

### backend-unreachable
1. `/health/ready`를 직접 열어 본다. 503이면 DB(Supabase 상태), 응답이 없으면 Railway 서비스.
2. backend-silent가 같이 울리면 프로세스 문제, 혼자 울리면 네트워크·도메인 문제일 가능성.

## 알려진 한계

- threshold 기본값(`variables.tf`)은 실제 트래픽 데이터 없이 잡은 시작값이다 - 적용 후 알림이
  너무 자주/드물게 온다면 조정한다.
- critical과 warning이 같은 Discord 채널로 간다(critical만 `@here`). 채널을 나누려면 critical용 웹후크를
  하나 더 만들어 `contact_points.tf`의 `discord_critical`에 넣는다.
- "오늘 TTS 사용량"은 최근 24시간 기준이라 Gemini의 하루 한도 초기화 시각과 정확히 맞지는 않는다.
- 앱 배포 파이프라인(Railway)과 완전히 별개 — 알림 규칙이 바뀌어도 Spring Boot 앱은 재배포되지 않고,
  반대도 마찬가지. `.github/workflows/grafana-alerting.yml`의 `paths:` 필터로 이 격리를 보장한다.
