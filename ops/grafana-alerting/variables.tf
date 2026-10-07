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

variable "discord_webhook_url" {
  description = "알림을 받을 Discord Incoming Webhook URL. Discord 서버 → 채널 설정 → 연동(Integrations) → 웹후크에서 발급."
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

variable "rtzr_failure_threshold" {
  description = "5분 창 안에서 이 건수를 넘는 Rtzr STT 실패(rtzr-http.failed 또는 rtzr-transcription.failed)가 쌓이면 알림. STT는 아이 발화당 1회씩만 호출되므로 트래픽 대비 실패율이 provider보다 훨씬 낮음."
  type        = number
  default     = 5
}

variable "retention_failure_threshold" {
  description = "companion-chat 일일 정리 실패 알림 임계. 스케줄러는 하루에 한 번 도는데 실패 한 번은 하루 밀리는 것뿐이라 warning으로 두되, 반복되면 며칠 안에 눈에 들어오도록 낮게 잡음."
  type        = number
  default     = 1
}

variable "slow_request_p95_ms" {
  description = "API 응답 시간 p95(실시간 분기 생성 제외)가 5분 창에서 이 값(ms)을 넘으면 알림. 그레텔 답은 AI 답 + 음성 생성이라 수 초가 정상이므로 넉넉하게 시작."
  type        = number
  default     = 8000
}

variable "client_error_threshold" {
  description = "5분 창 안에서 브라우저 에러(client.error)가 이 건수를 넘으면 알림. 서버는 분당 120건까지만 남긴다."
  type        = number
  default     = 10
}

variable "tts_daily_limit" {
  description = "Gemini TTS 하루 호출 한도. 대시보드의 '오늘 TTS 사용량' 기준선으로만 쓴다(무료 등급 100회/일). 등급을 올리면 같이 바꾼다."
  type        = number
  default     = 100
}

# ── 대화 품질 대시보드(Postgres) ─────────────────────────────────────────────
# 운영 DB의 grafana 스키마 뷰만 읽는 계정(grafana_reader)으로 붙는다. 비워 두면 데이터소스와 대시보드를
# 만들지 않는다. 계정 만드는 법은 README '대화 품질 대시보드'.

variable "postgres_host" {
  description = "운영 DB 호스트:포트(Supabase 세션 풀러, 예: aws-0-ap-northeast-2.pooler.supabase.com:5432). 비우면 Postgres 대시보드를 만들지 않는다."
  type        = string
  default     = ""
}

variable "postgres_user" {
  description = "읽기 전용 계정 이름. Supabase 풀러로 붙을 때는 grafana_reader.<프로젝트 ref> 형식."
  type        = string
  default     = ""
}

variable "postgres_password" {
  description = "읽기 전용 계정 비밀번호."
  type        = string
  default     = ""
  sensitive   = true
}

variable "postgres_database" {
  description = "DB 이름."
  type        = string
  default     = "postgres"
}

# ── 바깥에서 보는 생존 확인(Synthetic Monitoring) ────────────────────────────
# Grafana Cloud > Testing & synthetics > Synthetics > Config 에서 받는 값. 비워 두면 체크를 만들지 않는다.

variable "sm_url" {
  description = "Synthetic Monitoring API 주소(예: https://synthetic-monitoring-api-ap-northeast-0.grafana.net)."
  type        = string
  default     = ""
}

variable "sm_access_token" {
  description = "Synthetic Monitoring 액세스 토큰."
  type        = string
  default     = ""
  sensitive   = true
}

variable "backend_public_url" {
  description = "바깥에서 두드릴 백엔드 주소. /health/ready를 붙여 1분마다 확인한다."
  type        = string
  default     = "https://q-story-be-production.up.railway.app"
}

variable "prometheus_datasource_uid" {
  description = "Synthetic Monitoring 결과(probe_success)가 쌓이는 Prometheus 데이터소스 UID. Grafana Cloud 기본값은 grafanacloud-prom."
  type        = string
  default     = "grafanacloud-prom"
}
