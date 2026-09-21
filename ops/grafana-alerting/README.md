# Grafana Cloud 알림 규칙 (Terraform)

[Q-story-be#3](https://github.com/qstory-ai/Q-story-be/issues/3) 대응. `logback-spring.xml`이
로그를 Grafana Cloud Loki로 실어 나르기만 하고 정작 알림 규칙/채널이 없던 걸, 여기 Terraform으로
코드화한다.

## 뭘 감시하나

`alert_rules.tf`의 `local.log_alerts`에 일곱 개 규칙이 있다. 각각 5분 창에서 특정 로그 패턴이
threshold(변수, `variables.tf`)를 넘으면 Slack으로 알린다.

| 규칙 | severity | 로그 태그 | 어디서 나는지 |
|---|---|---|---|
| `error-log-rate-spike` | warning | `\|= "ERROR"` | 전체 ERROR 레벨 로그 |
| `openrouter-provider-failure` | warning | `openrouter-http.failed` | `OpenRouterClient.java` (TTS/채팅완성/이미지 공용) |
| `gemini-tts-failure` | **critical** | `gemini-http.failed`, `gemini-tts.empty-audio` | `GeminiTtsClient.java` (실장애 이력) |
| `rtzr-stt-failure` | warning | `rtzr-http.failed`, `rtzr-transcription.failed` | `RtzrSttClient.java` (인증/제출/폴링 실패 + status="failed") |
| `uncaught-5xx` | **critical** | `request.failed` | `GlobalExceptionHandler.java` (미처리 예외가 5xx로 응답할 때) |
| `db-pool-exhaustion` | **critical** | `Connection is not available` | HikariCP 기본 타임아웃 경고 |
| `companion-retention-failure` | warning | `companion-chat-retention.failed` | `CompanionChatRetentionScheduler.java` (일일 정리 실패) |

로그 태그는 실제 소스에서 그대로 가져온 것들이다(추측 아님) - 태그 문자열이 바뀌면 이 파일도
같이 고쳐야 한다.

**severity 라벨**은 실제 파급도 기준:
- `critical` — 이용자에게 5xx가 나가거나 시스템 전체가 흔들리는 실패. 즉시 대응.
- `warning` — 개별 요청 실패지만 상위가 폴백/재시도로 흡수. 반복되면 원인 확인.

지금은 단일 Slack contact point로 모두 라우팅되지만, severity별 채널·@channel 처리는 이후
`grafana_notification_policy`를 추가하면 된다. Slack 메시지에는 firing/resolved 요약, description,
Grafana Explore 링크, 런북 링크가 함께 실린다(`contact_points.tf` 템플릿 참고).

## 자동화

`.github/workflows/grafana-alerting.yml`이 파이프라인이다. 앱 배포(Railway)와는 완전히 분리 -
`ops/grafana-alerting/**`이 바뀔 때만 도는 별도 파이프라인이라 서로 재배포를 유발하지 않는다.

```
PR       →  terraform plan을 Actions 로그에 노출(변경 미리보기, apply 안 함)
main push →  terraform apply -auto-approve (실제 Grafana Cloud에 반영)
```

state는 Terraform Cloud(무료 티어) 워크스페이스에 보관하되 실행은 GH Actions 러너에서 한다
(워크스페이스 Execution Mode = **Local**). "state는 관리되고, 로그·시크릿은 GitHub 안"이라는 절충.

### 1회성 세팅 (Grafana/TFC 콘솔에서)

1. **Grafana Cloud Access Policy 토큰**: Cloud Portal → Access Policies → 새 정책 생성,
   스코프는 `alerting:write`, `alerting.notifications:write`, `alerting-provisioning:write`,
   `folders:write`.
2. **Loki 데이터소스 UID**: Grafana Cloud 콘솔 → Connections → Data sources → Loki → Details.
   `logback-spring.xml`이 이미 쓰고 있는 그 데이터소스와 같은 UID여야 한다 - 새로 만들지 않는다.
3. **Slack Incoming Webhook URL**: 알림 받을 채널에 Incoming Webhook 앱 연결.
4. **Terraform Cloud 워크스페이스**: [app.terraform.io](https://app.terraform.io) 가입 → org 생성
   → 워크스페이스 생성(예: `qstory-alerting`) → 설정에서 **Execution Mode = Local**로 변경
   (그래야 TFC가 실행 안 하고 state만 잡는다).
5. **TFC User/Team Token**: TFC → User Settings → Tokens → 새 토큰 생성. GH Actions가 state에 접근할 때 씀.

### GitHub 저장소 설정 (Settings → Secrets and variables → Actions)

**Variables** (평문):
- `TF_CLOUD_ORGANIZATION` — TFC 조직 이름
- `TF_WORKSPACE` — TFC 워크스페이스 이름 (예: `qstory-alerting`)
- `TF_VAR_APP_ENV` — Loki 로그 label의 `env` 값. `logback-spring.xml`의 `spring.profiles.active`와 맞춘다 (기본 `production`)

**Secrets** (암호화):
- `TF_API_TOKEN` — 4번에서 만든 TFC 토큰
- `TF_VAR_GRAFANA_URL` — 예: `https://<stack>.grafana.net`
- `TF_VAR_GRAFANA_AUTH` — 1번의 Access Policy 토큰
- `TF_VAR_LOKI_DATASOURCE_UID` — 2번의 UID
- `TF_VAR_SLACK_WEBHOOK_URL` — 3번의 Slack Webhook URL

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
export TF_VAR_slack_webhook_url=...
# ~/.terraformrc:
#   credentials "app.terraform.io" { token = "<TFC 토큰>" }
terraform -chdir=ops/grafana-alerting init
terraform -chdir=ops/grafana-alerting plan
```

`terraform.tfvars.example`은 로컬 참조용으로만 남겨두었다(TFC state를 쓰기 시작한 뒤에는 tfvars
파일보다 env-var 방식이 GH Actions와 동일해 관리가 편함).

## 알려진 한계

- 이 코드를 작성한 환경엔 실제 Grafana Cloud 스택 접근 권한(토큰)이 없어서, 로컬에서 `terraform
  plan`/`apply`로 실제 스택에 적용해보지는 못했다. 위 세팅을 마치고 첫 GH Actions apply가 성공하면
  이 항목은 지워도 됨.
- threshold 기본값(`variables.tf`)은 실제 트래픽 데이터 없이 잡은 시작값이다 - 적용 후 알림이
  너무 자주/드물게 온다면 조정한다.
- **critical 규칙과 warning 규칙이 같은 채널로 감** — severity 라벨은 메시지 안에서만 구분됨.
  별도 채널이나 @channel 태그가 필요하면 `grafana_notification_policy` 리소스를 추가하되, Grafana
  Cloud UI에서 이미 설정한 기본 정책과 충돌하지 않게 주의.
- 앱 배포 파이프라인(Railway)과 완전히 별개 — 알림 규칙이 바뀌어도 Spring Boot 앱은 재배포되지 않고,
  반대도 마찬가지. `.github/workflows/grafana-alerting.yml`의 `paths:` 필터로 이 격리를 보장한다.
