# 각 항목의 logql은 실제 로그에 찍히는 태그를 그대로 물고 있다(추측 문자열이 아님) - 출처는
# 아래 소스 파일들이고, PR 리뷰에서 로그 태그가 바뀌면 이 파일도 같이 고쳐야 한다는 걸 표시해둔다.
#
#   openrouter-http.failed  <- provider/openrouter/util/OpenRouterClient.java (TTS/채팅완성/이미지 공용)
#   gemini-http.failed      <- provider/gemini/util/GeminiTtsClient.java (HTTP 상태 실패)
#   gemini-tts.empty-audio  <- provider/gemini/util/GeminiTtsClient.java (200인데 오디오가 비어 있던,
#                               실제로 겪었던 GEMINI_TTS_EMPTY 장애의 그 케이스)
#   request.failed          <- common/error/GlobalExceptionHandler.java (5xx로 응답한 모든 미처리 예외)
#   Connection is not available <- HikariCP 기본 타임아웃 경고 문구(커스텀 로그 아님, 라이브러리 기본값)
#
# STT(Rtzr) 실패는 아직 커스텀 로그 태그가 없어 여기 포함하지 못했다 - 별도 이슈로 남겨둔다.
locals {
  log_alerts = {
    error-log-rate-spike = {
      summary   = "qstory-backend ERROR 로그가 5분간 ${var.error_log_threshold}건을 넘었어요."
      logql     = "sum(count_over_time({app=\"qstory-backend\", env=\"${var.app_env}\"} |= \"ERROR\" [5m]))"
      threshold = var.error_log_threshold
    }
    openrouter-provider-failure = {
      summary   = "OpenRouter(TTS/채팅완성/이미지) 호출 실패가 5분간 ${var.provider_failure_threshold}건을 넘었어요."
      logql     = "sum(count_over_time({app=\"qstory-backend\", env=\"${var.app_env}\"} |= \"openrouter-http.failed\" [5m]))"
      threshold = var.provider_failure_threshold
    }
    gemini-tts-failure = {
      summary   = "Gemini TTS 실패(HTTP 실패 또는 빈 오디오)가 5분간 ${var.provider_failure_threshold}건을 넘었어요."
      logql     = "sum(count_over_time({app=\"qstory-backend\", env=\"${var.app_env}\"} |~ \"gemini-http.failed|gemini-tts.empty-audio\" [5m]))"
      threshold = var.provider_failure_threshold
    }
    uncaught-5xx = {
      summary   = "처리되지 않은 서버 에러(request.failed)가 5분간 ${var.uncaught_5xx_threshold}건을 넘었어요."
      logql     = "sum(count_over_time({app=\"qstory-backend\", env=\"${var.app_env}\"} |= \"request.failed\" [5m]))"
      threshold = var.uncaught_5xx_threshold
    }
    db-pool-exhaustion = {
      summary   = "DB 커넥션 풀 타임아웃 경고가 5분간 ${var.db_pool_exhaustion_threshold}건을 넘었어요."
      logql     = "sum(count_over_time({app=\"qstory-backend\", env=\"${var.app_env}\"} |= \"Connection is not available\" [5m]))"
      threshold = var.db_pool_exhaustion_threshold
    }
  }
}

resource "grafana_rule_group" "backend_failures" {
  name             = "qstory-backend-failures"
  folder_uid       = grafana_folder.alerting.uid
  interval_seconds = 60

  dynamic "rule" {
    for_each = local.log_alerts
    content {
      name      = rule.key
      condition = "C"
      for       = "5m"

      # A: Loki에서 5분 윈도 카운트를 뽑는다.
      data {
        ref_id = "A"
        relative_time_range {
          from = 600
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
          from = 600
          to   = 0
        }
        datasource_uid = "__expr__"
        model = jsonencode({
          type       = "threshold"
          expression = "A"
          conditions = [{
            evaluator = { type = "gt", params = [rule.value.threshold] }
          }]
          refId = "C"
        })
      }

      annotations = {
        summary = rule.value.summary
      }

      labels = {
        severity = "warning"
        team     = "qstory-backend"
      }

      notification_settings {
        contact_point = grafana_contact_point.slack.name
      }
    }
  }
}
