# Grafana Cloud 알림 규칙 (Terraform)

[Q-story-be#3](https://github.com/qstory-ai/Q-story-be/issues/3) 대응. `logback-spring.xml`이
로그를 Grafana Cloud Loki로 실어 나르기만 하고 정작 알림 규칙/채널이 없던 걸, 여기 Terraform으로
코드화한다.

## 뭘 감시하나

`alert_rules.tf`의 `local.log_alerts`에 다섯 개 규칙이 있다. 각각 5분 창에서 특정 로그 패턴이
threshold(변수, `variables.tf`)를 넘으면 Slack으로 알린다.

| 규칙 | 로그 태그 | 어디서 나는지 |
|---|---|---|
| `error-log-rate-spike` | `\|= "ERROR"` | 전체 ERROR 레벨 로그 |
| `openrouter-provider-failure` | `openrouter-http.failed` | `OpenRouterClient.java` (TTS/채팅완성/이미지 공용) |
| `gemini-tts-failure` | `gemini-http.failed`, `gemini-tts.empty-audio` | `GeminiTtsClient.java` |
| `uncaught-5xx` | `request.failed` | `GlobalExceptionHandler.java` (미처리 예외가 5xx로 응답할 때) |
| `db-pool-exhaustion` | `Connection is not available` | HikariCP 기본 타임아웃 경고 |

로그 태그는 실제 소스에서 그대로 가져온 것들이다(추측 아님) - 태그 문자열이 바뀌면 이 파일도
같이 고쳐야 한다.

**커버 안 되는 것**: STT(Rtzr) 실패는 아직 커스텀 로그 태그가 없어서(`RtzrSttClient.java`에
`log.warn`/`log.error` 호출 자체가 없음) 여기 포함하지 못했다. 필요하면 로깅을 먼저 추가하는
후속 이슈로 남긴다.

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

- 이 PR을 작성한 환경엔 실제 Grafana Cloud 스택 접근 권한(토큰)이 없어서, `terraform validate`로
  문법/스키마만 확인했고 `terraform plan`/`apply`로 실제 스택에 적용해보지는 못했다. 리뷰어가
  실제 자격증명으로 `terraform plan`을 한 번 더 돌려보고 머지하는 걸 권장한다.
- threshold 기본값(`variables.tf`)은 실제 트래픽 데이터 없이 잡은 시작값이다 - 적용 후 알림이
  너무 자주/드물게 온다면 조정한다.
