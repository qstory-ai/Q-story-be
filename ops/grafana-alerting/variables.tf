variable "grafana_url" {
  description = "Grafana Cloud 스택 URL (예: https://<stack>.grafana.net). Grafana Cloud 콘솔 > Administration > Stack 상세에서 확인."
  type        = string
}

variable "grafana_auth" {
  description = "Grafana Cloud Access Policy 토큰. alerting:write, alerting.notifications:write, alerting-provisioning:write, folders:write 스코프가 필요하다. Cloud Portal > Access Policies에서 발급."
  type        = string
  sensitive   = true
}

variable "loki_datasource_uid" {
  description = <<-EOT
    알림 규칙이 쿼리할 Loki 데이터소스 UID. 새로 만들지 않는다 - logback-spring.xml의 loki4j
    appender가 이미 LOKI_URL로 같은 데이터소스에 로그를 쓰고 있으므로, 그 기존 데이터소스를
    그대로 가리켜야 한다. Grafana Cloud 콘솔 > Connections > Data sources > Loki > Details에서
    확인.
  EOT
  type        = string
}

variable "slack_webhook_url" {
  description = "알림을 받을 Slack Incoming Webhook URL."
  type        = string
  sensitive   = true
}

variable "app_env" {
  description = "Loki 로그 label env 값. logback-spring.xml의 spring.profiles.active(Railway 배포 기준)와 맞춘다."
  type        = string
  default     = "production"
}

variable "error_log_threshold" {
  description = "5분 창 안에서 이 건수를 넘는 ERROR 로그가 쌓이면 알림 - 실사용 트래픽을 보고 튜닝할 시작값."
  type        = number
  default     = 20
}

variable "provider_failure_threshold" {
  description = "5분 창 안에서 이 건수를 넘는 외부 프로바이더(OpenRouter/Gemini TTS) 실패가 쌓이면 알림."
  type        = number
  default     = 5
}

variable "uncaught_5xx_threshold" {
  description = "5분 창 안에서 이 건수를 넘는 처리되지 않은 서버 에러(GlobalExceptionHandler의 request.failed)가 쌓이면 알림."
  type        = number
  default     = 5
}

variable "db_pool_exhaustion_threshold" {
  description = "5분 창 안에서 이 건수를 넘는 HikariCP 커넥션 타임아웃 경고가 쌓이면 알림."
  type        = number
  default     = 3
}
