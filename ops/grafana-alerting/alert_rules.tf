# 각 항목의 logql은 실제 로그에 찍히는 태그를 그대로 물고 있다(추측 문자열이 아님) - 출처는
# 아래 소스 파일들이고, PR 리뷰에서 로그 태그가 바뀌면 이 파일도 같이 고쳐야 한다는 걸 표시해둔다.
#
#   openrouter-http.failed         <- provider/openrouter/util/OpenRouterClient.java (TTS/채팅완성/이미지 공용)
#   gemini-http.failed             <- provider/gemini/util/GeminiTtsClient.java (HTTP 상태 실패)
#   gemini-tts.empty-audio         <- provider/gemini/util/GeminiTtsClient.java (200인데 오디오가 비어 있던,
#                                     실제로 겪었던 GEMINI_TTS_EMPTY 장애의 그 케이스)
#   rtzr-http.failed               <- provider/rtzr/util/RtzrSttClient.java (인증/제출/폴링 HTTP 실패 공용)
#   rtzr-transcription.failed      <- provider/rtzr/util/RtzrSttClient.java (200인데 status="failed"로 온 케이스)
#   request.failed                 <- common/error/GlobalExceptionHandler.java (5xx로 응답한 모든 미처리 예외)
#   companion-chat-retention.failed <- companionchat/service/CompanionChatRetentionScheduler.java (일일 정리 실패)
#   Connection is not available    <- HikariCP 기본 타임아웃 경고 문구(커스텀 로그 아님, 라이브러리 기본값)
#   gemini-http.failed ... status=429|402 <- GeminiTtsClient.java (하루 한도 초과 / 크레딧 소진)
#   app.heartbeat                  <- health/service/HeartbeatLogger.java (5분마다, 요청이 없어도 찍힘)
#   APPLICATION FAILED TO START / Application run failed <- Spring Boot 기본 부팅 실패 문구
#   http.request ... duration_ms=  <- common/web/RequestIdFilter.java (요청마다 한 줄)
#   client.error                   <- clienterror/ClientErrorController.java (브라우저에서 보낸 에러)
#
# 각 규칙의 런북(대응 순서)은 README.md '런북' 절의 같은 이름 항목에 있다.
#
# severity 라벨은 실제 파급도 기준:
#   critical - 이용자에게 5xx가 나가거나 시스템 전체가 흔들리는 것: gemini-tts.empty-audio(실장애 이력),
#              uncaught-5xx, db-pool-exhaustion
#   warning  - 개별 요청 실패지만 상위 파이프라인이 폴백/재시도로 흡수 가능: openrouter/rtzr 프로바이더 실패,
#              ERROR 급증(전조), retention 스케줄러(하루 지연 허용).
# 라우팅은 아직 단일 Discord contact point이지만, severity 라벨을 붙여두면 이후 notification policy로 채널을
# 나누기 쉽다(critical만 @everyone 붙이기 등).
locals {
  log_alerts = {
    error-log-rate-spike = {
      severity    = "warning"
      summary     = "qstory-backend ERROR 로그가 5분간 ${var.error_log_threshold}건을 넘었어요."
      description = <<-EOT
        전체 ERROR 레벨 로그 급증. 다른 규칙(openrouter/gemini/rtzr/uncaught-5xx)이 함께 울리지 않는데
        이 규칙만 울리면 새로 생긴 실패 경로일 가능성. Grafana Explore에서 원본 로그를 열어 태그를 확인.
      EOT
      logql       = "sum(count_over_time({app=\"qstory-backend\", env=\"${var.app_env}\"} |= \"ERROR\" [5m]))"
      threshold   = var.error_log_threshold
    }
    openrouter-provider-failure = {
      severity    = "warning"
      summary     = "OpenRouter(TTS/채팅완성/이미지) 호출 실패가 5분간 ${var.provider_failure_threshold}건을 넘었어요."
      description = <<-EOT
        LLM/이미지 프로바이더 실패. 상위(QuestionRoutingService/LiveBranchExecutionWorker)가 재시도로 흡수
        가능한 범위지만 지속되면 사용자에게 실패 응답이 노출됨. context= 값으로 어느 호출인지 좁힐 수 있음.
      EOT
      logql       = "sum(count_over_time({app=\"qstory-backend\", env=\"${var.app_env}\"} |= \"openrouter-http.failed\" [5m]))"
      threshold   = var.provider_failure_threshold
    }
    gemini-tts-failure = {
      severity    = "critical"
      summary     = "Gemini TTS 실패(HTTP 실패 또는 빈 오디오)가 5분간 ${var.provider_failure_threshold}건을 넘었어요."
      description = <<-EOT
        TTS 실패는 낭독 자체가 재생되지 않아 아이 세션이 멈춤. gemini-tts.empty-audio는 200 응답인데
        오디오가 비어 있었던 실제 프로덕션 장애의 그 케이스 - 조용히 넘어가면 안 됨.
      EOT
      logql       = "sum(count_over_time({app=\"qstory-backend\", env=\"${var.app_env}\"} |~ \"gemini-http.failed|gemini-tts.empty-audio\" [5m]))"
      threshold   = var.provider_failure_threshold
    }
    rtzr-stt-failure = {
      severity    = "warning"
      summary     = "Rtzr STT(음성 인식) 실패가 5분간 ${var.rtzr_failure_threshold}건을 넘었어요."
      description = <<-EOT
        아이 발화 인식 실패. 개별 실패는 폴백 안내로 처리되지만, 반복되면 인증/제출/폴링 어디에서 막히는지
        context= 필드로 확인. rtzr-transcription.failed는 STT가 200으로 응답하고도 status="failed"를 낸
        케이스라 별도로 표시됨.
      EOT
      logql       = "sum(count_over_time({app=\"qstory-backend\", env=\"${var.app_env}\"} |~ \"rtzr-http.failed|rtzr-transcription.failed\" [5m]))"
      threshold   = var.rtzr_failure_threshold
    }
    uncaught-5xx = {
      severity    = "critical"
      summary     = "처리되지 않은 서버 에러(request.failed)가 5분간 ${var.uncaught_5xx_threshold}건을 넘었어요."
      description = <<-EOT
        GlobalExceptionHandler에서 명시적 처리 없이 5xx로 나간 요청. 이용자에게 그대로 실패가 노출되며,
        새로 유입된 경로일 가능성이 높음.
      EOT
      logql       = "sum(count_over_time({app=\"qstory-backend\", env=\"${var.app_env}\"} |= \"request.failed\" [5m]))"
      threshold   = var.uncaught_5xx_threshold
    }
    db-pool-exhaustion = {
      severity    = "critical"
      summary     = "DB 커넥션 풀 타임아웃 경고가 5분간 ${var.db_pool_exhaustion_threshold}건을 넘었어요."
      description = <<-EOT
        HikariCP 커넥션 풀 타임아웃. 시스템 전체 지연이 커지고 이후 요청도 줄줄이 실패 - 즉시 확인 필요.
      EOT
      logql       = "sum(count_over_time({app=\"qstory-backend\", env=\"${var.app_env}\"} |= \"Connection is not available\" [5m]))"
      threshold   = var.db_pool_exhaustion_threshold
    }
    companion-retention-failure = {
      severity    = "warning"
      summary     = "companion-chat 일일 정리(retention) 실패가 5분간 ${var.retention_failure_threshold}건을 넘었어요."
      description = <<-EOT
        CompanionChatRetentionScheduler(cron 매일 UTC 18:45)가 실패. 하루 정도 밀려도 서비스에는 영향이
        없으므로 warning이지만, 며칠 이어지면 90일 원칙 위반이므로 확인해 정리.
      EOT
      logql       = "sum(count_over_time({app=\"qstory-backend\", env=\"${var.app_env}\"} |= \"companion-chat-retention.failed\" [5m]))"
      threshold   = var.retention_failure_threshold
    }
    gemini-tts-quota = {
      severity    = "critical"
      summary     = "Gemini TTS 한도 초과(429) 또는 크레딧 소진(402)이 났어요."
      description = <<-EOT
        한도가 풀릴 때까지(하루 한도는 다음 날) 모든 실시간 음성 - 그레텔 답, 고정 음성이 없는 대사 - 이
        실패하고 기기 음성으로 대체됨. 429면 한도 상향 또는 다른 키, 402면 결제 크레딧 충전.
      EOT
      logql       = "sum(count_over_time({app=\"qstory-backend\", env=\"${var.app_env}\"} |= \"gemini-http.failed\" |~ \"status=(429|402)\" [5m]))"
      threshold   = 0
    }
    backend-boot-failure = {
      severity    = "critical"
      summary     = "백엔드가 부팅에 실패했어요(APPLICATION FAILED TO START)."
      description = <<-EOT
        새 배포가 뜨지 못함. Railway는 헬스체크를 통과하지 못한 배포를 트래픽에 붙이지 않지만, 재시작 루프나
        마이그레이션 실패일 수 있으니 바로 확인. 원인은 로그의 'Description:' 줄에 있음.
      EOT
      logql       = "sum(count_over_time({app=\"qstory-backend\", env=\"${var.app_env}\"} |~ \"APPLICATION FAILED TO START|Application run failed\" [5m]))"
      threshold   = 0
      pending     = "0s"
    }
    backend-silent = {
      severity      = "critical"
      summary       = "백엔드 로그가 15분째 한 줄도 없어요(서버가 멈췄을 수 있어요)."
      description   = <<-EOT
        app.heartbeat는 요청이 없어도 5분마다 찍힌다. 15분 동안 하나도 없으면 프로세스가 죽었거나 Loki 전송이
        끊긴 것. Railway 서비스 상태와 /health/ready를 먼저 확인.
      EOT
      logql         = "sum(count_over_time({app=\"qstory-backend\", env=\"${var.app_env}\"} |= \"app.heartbeat\" [15m]))"
      threshold     = 1
      comparison    = "lt"
      no_data_state = "Alerting"
      range_seconds = 1200
    }
    slow-requests = {
      severity    = "warning"
      summary     = "API 응답 p95가 5분간 ${var.slow_request_p95_ms}ms를 넘었어요."
      description = <<-EOT
        http.request 로그의 duration_ms 기준. 실시간 분기 생성처럼 원래 오래 걸리는 경로는 뺐다. 외부 AI·음성
        지연인지(같이 울리는 provider 규칙 확인), DB 지연인지(db-pool-exhaustion) 먼저 가른다.
      EOT
      logql       = "max(quantile_over_time(0.95, {app=\"qstory-backend\", env=\"${var.app_env}\"} |= \"http.request\" != \"live-branch\" | regexp \"duration_ms=(?P<duration_ms>[0-9]+)\" | unwrap duration_ms [5m]))"
      threshold   = var.slow_request_p95_ms
    }
    client-error-spike = {
      severity    = "warning"
      summary     = "브라우저 에러가 5분간 ${var.client_error_threshold}건을 넘었어요."
      description = <<-EOT
        프런트에서 보낸 client.error. kind=PLAYBACK이면 음성 재생, RUNTIME_FAILURE면 이야기 진행 실패,
        WINDOW_ERROR/UNHANDLED_REJECTION이면 코드 예외. route·release로 어느 화면·배포인지 좁힌다.
      EOT
      logql       = "sum(count_over_time({app=\"qstory-backend\", env=\"${var.app_env}\"} |= \"client.error\" [5m]))"
      threshold   = var.client_error_threshold
    }
  }

  # 규칙마다 다른 값만 위에 적고, 나머지는 기본값으로 채운다(대부분 "5분에 N건 넘으면").
  alerts = {
    for name, rule in local.log_alerts : name => merge({
      comparison    = "gt"
      no_data_state = "OK"
      pending       = "5m"
      range_seconds = 600
    }, rule)
  }
}

resource "grafana_rule_group" "backend_failures" {
  name             = "qstory-backend-failures"
  folder_uid       = grafana_folder.alerting.uid
  interval_seconds = 60

  dynamic "rule" {
    for_each = local.alerts
    content {
      name      = rule.key
      condition = "C"
      for       = rule.value.pending

      # 실패 카운트 규칙은 로그가 아예 없으면(Loki 지연 등) 울리지 않는다(OK). 서버가 멈춘 건 app.heartbeat가
      # 끊기는 걸 보는 backend-silent 규칙만 데이터 없음을 알림으로 본다(Alerting).
      no_data_state  = rule.value.no_data_state
      exec_err_state = "Error"

      # A: Loki에서 5분 윈도 카운트를 뽑는다.
      data {
        ref_id = "A"
        relative_time_range {
          from = rule.value.range_seconds
          to   = 0
        }
        datasource_uid = var.loki_datasource_uid
        model = jsonencode({
          expr          = rule.value.logql
          queryType     = "range"
          intervalMs    = 1000
          maxDataPoints = 43200
          refId         = "A"
        })
      }

      # C: A가 threshold를 넘는지 보는 순수 표현식(expr) 쿼리 - 데이터소스 없이 __expr__로 계산.
      data {
        ref_id = "C"
        relative_time_range {
          from = rule.value.range_seconds
          to   = 0
        }
        datasource_uid = "__expr__"
        model = jsonencode({
          type       = "threshold"
          expression = "A"
          conditions = [{
            evaluator = { type = rule.value.comparison, params = [rule.value.threshold] }
          }]
          refId = "C"
        })
      }

      annotations = {
        summary     = rule.value.summary
        description = rule.value.description
        # README.md '런북' 절에서 규칙 이름과 같은 제목으로 바로 이동한다.
        runbook_url = "https://github.com/qstory-ai/Q-story-be/blob/main/ops/grafana-alerting/README.md#${rule.key}"
      }

      labels = {
        severity = rule.value.severity
        team     = "qstory-backend"
        env      = var.app_env
      }

      # 어디로 보낼지는 규칙이 아니라 알림 정책(notification_policy.tf)이 severity 라벨로 정한다.
    }
  }
}
