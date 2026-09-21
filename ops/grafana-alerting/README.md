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

## 사전 준비

1. **Grafana Cloud Access Policy 토큰**: Cloud Portal → Access Policies → 새 정책 생성,
   스코프는 `alerting:write`, `alerting.notifications:write`, `alerting-provisioning:write`,
   `folders:write`.
2. **Loki 데이터소스 UID**: Grafana Cloud 콘솔 → Connections → Data sources → Loki → Details.
   `logback-spring.xml`이 이미 쓰고 있는 그 데이터소스와 같은 UID여야 한다 - 새로 만들지 않는다.
3. **Slack Incoming Webhook URL**: 알림 받을 채널에 Incoming Webhook 앱 연결.

```
cp terraform.tfvars.example terraform.tfvars
# terraform.tfvars에 실제 값 채우기 (커밋 안 됨 - .gitignore 처리됨)
terraform init
terraform plan
terraform apply
```

## 상태(state) 관리

이 디렉터리는 로컬 state를 전제로 만들어졌다(`.gitignore`가 `*.tfstate`를 막는다). 팀이 실제로
CI에서 반복 적용할 계획이면 원격 백엔드(S3, Terraform Cloud 등)로 옮기는 걸 권장한다 - 지금은
누군가 로컬에서 `terraform apply`를 한 번 실행해 초기 상태를 만드는 걸 전제로 한다.

## 알려진 한계

- 이 코드를 작성한 환경엔 실제 Grafana Cloud 스택 접근 권한(토큰)이 없어서, `terraform validate`로
  문법/스키마만 확인했고 `terraform plan`/`apply`로 실제 스택에 적용해보지는 못했다. 실제 자격증명으로
  `terraform plan`을 한 번 돌려보고 apply하는 걸 권장한다.
- threshold 기본값(`variables.tf`)은 실제 트래픽 데이터 없이 잡은 시작값이다 - 적용 후 알림이
  너무 자주/드물게 온다면 조정한다.
- **critical 규칙과 warning 규칙이 같은 채널로 감** — severity 라벨은 메시지 안에서만 구분됨.
  별도 채널이나 @channel 태그가 필요하면 `grafana_notification_policy` 리소스를 추가하되, Grafana
  Cloud UI에서 이미 설정한 기본 정책과 충돌하지 않게 주의.
