# 알림 말고 한눈에 보는 화면 두 개. 알림 규칙과 같은 로그 태그·같은 폴더를 쓴다.
#
#   백엔드 상태   - Loki 로그로 요청량·5xx 비율·응답 시간·외부 AI/음성 실패·오늘 TTS 사용량·브라우저 에러
#   대화 품질     - 운영 DB의 grafana 스키마 뷰로 그레텔 대화(Q-31)와 이야기 진행 지표. Postgres 연결이 있을 때만.

locals {
  loki   = { type = "loki", uid = var.loki_datasource_uid }
  logsel = "{app=\"qstory-backend\", env=\"${var.app_env}\"}"

  # 시계열 패널 하나. targets는 [{expr, legend}].
  loki_timeseries = { for key, panel in {
    requests = {
      title   = "분당 요청 수"
      unit    = "short"
      targets = [{ expr = "sum(count_over_time(${local.logsel} |= \"http.request\" [1m]))", legend = "요청" }]
      pos     = { x = 0, y = 0 }
    }
    error_rate = {
      title = "5xx 비율"
      unit  = "percentunit"
      targets = [{
        expr   = "sum(count_over_time(${local.logsel} |= \"http.request\" |~ \"status=5[0-9][0-9]\" [5m])) / sum(count_over_time(${local.logsel} |= \"http.request\" [5m]))"
        legend = "5xx"
      }]
      pos = { x = 12, y = 0 }
    }
    latency = {
      title = "경로별 응답 시간 p95"
      unit  = "ms"
      targets = [{
        expr   = "max by (route) (quantile_over_time(0.95, ${local.logsel} |= \"http.request\" | regexp \"route=(?P<route>\\\\S+) status=[0-9]+ duration_ms=(?P<duration_ms>[0-9]+)\" | unwrap duration_ms [5m]))"
        legend = "{{route}}"
      }]
      pos = { x = 0, y = 8 }
    }
    providers = {
      title = "외부 AI·음성 실패 (5분)"
      unit  = "short"
      targets = [
        { expr = "sum(count_over_time(${local.logsel} |= \"openrouter-http.failed\" [5m]))", legend = "OpenRouter(AI)" },
        { expr = "sum(count_over_time(${local.logsel} |~ \"gemini-http.failed|gemini-tts.empty-audio\" [5m]))", legend = "Gemini TTS" },
        { expr = "sum(count_over_time(${local.logsel} |~ \"rtzr-http.failed|rtzr-transcription.failed\" [5m]))", legend = "STT(Rtzr)" },
      ]
      pos = { x = 12, y = 8 }
    }
    client_errors = {
      title = "브라우저 에러 (종류별, 5분)"
      unit  = "short"
      targets = [{
        expr   = "sum by (kind) (count_over_time(${local.logsel} |= \"client.error\" | regexp \"kind=(?P<kind>[A-Z_]+)\" [5m]))"
        legend = "{{kind}}"
      }]
      pos = { x = 0, y = 16 }
    }
    heap = {
      title = "서버 메모리 사용량"
      unit  = "decmbytes"
      targets = [{
        expr   = "max(max_over_time(${local.logsel} |= \"app.heartbeat\" | regexp \"heap_used_mb=(?P<heap>[0-9]+)\" | unwrap heap [10m]))"
        legend = "사용 중"
      }]
      pos = { x = 12, y = 16 }
    }
  } : key => panel }

  backend_panels = concat(
    [for index, key in keys(local.loki_timeseries) : {
      id         = index + 1
      type       = "timeseries"
      title      = local.loki_timeseries[key].title
      datasource = local.loki
      gridPos    = { x = local.loki_timeseries[key].pos.x, y = local.loki_timeseries[key].pos.y, w = 12, h = 8 }
      fieldConfig = {
        defaults  = { unit = local.loki_timeseries[key].unit, custom = { fillOpacity = 10, lineWidth = 2 } }
        overrides = []
      }
      options = { legend = { displayMode = "list", placement = "bottom" } }
      targets = [for t_index, target in local.loki_timeseries[key].targets : {
        refId        = substr("ABCDEFGH", t_index, 1)
        datasource   = local.loki
        expr         = target.expr
        legendFormat = target.legend
        queryType    = "range"
      }]
    }],
    [
      {
        id          = 20
        type        = "stat"
        title       = "오늘 Gemini TTS 사용량 (한도 ${var.tts_daily_limit}회)"
        description = "gemini-tts.ok 로그 수(한국 시간 자정부터가 아니라 최근 24시간). 한도에 가까우면 등급 상향 또는 키 분리."
        datasource  = local.loki
        gridPos     = { x = 0, y = 24, w = 8, h = 6 }
        fieldConfig = {
          defaults = {
            unit = "short"
            thresholds = {
              mode = "absolute"
              steps = [
                { color = "green", value = null },
                { color = "orange", value = floor(var.tts_daily_limit * 0.7) },
                { color = "red", value = var.tts_daily_limit },
              ]
            }
          }
          overrides = []
        }
        options = { reduceOptions = { calcs = ["lastNotNull"], fields = "", values = false }, colorMode = "background" }
        targets = [{
          refId      = "A"
          datasource = local.loki
          expr       = "sum(count_over_time(${local.logsel} |= \"gemini-tts.ok\" [24h]))"
          queryType  = "instant"
        }]
      },
      {
        id          = 21
        type        = "logs"
        title       = "최근 에러 로그"
        description = "서버 에러·처리 안 된 5xx·브라우저 에러."
        datasource  = local.loki
        gridPos     = { x = 8, y = 24, w = 16, h = 12 }
        options     = { showTime = true, wrapLogMessage = true, sortOrder = "Descending" }
        targets = [{
          refId      = "A"
          datasource = local.loki
          expr       = "${local.logsel} |~ \"ERROR|request.failed|client.error|gemini-http.failed\""
          queryType  = "range"
        }]
      },
    ],
  )
}

resource "grafana_dashboard" "backend_overview" {
  folder = grafana_folder.alerting.uid
  config_json = jsonencode({
    uid           = "qstory-backend-overview"
    title         = "Q-Story 백엔드 상태"
    tags          = ["qstory", "backend"]
    timezone      = "Asia/Seoul"
    schemaVersion = 39
    time          = { from = "now-24h", to = "now" }
    refresh       = "1m"
    panels        = local.backend_panels
  })
}

# ── 대화 품질 (Postgres) ─────────────────────────────────────────────────────

locals {
  pg = local.postgres_enabled ? { type = "grafana-postgresql-datasource", uid = grafana_data_source.postgres[0].uid } : null

  # $traffic_type은 대시보드 변수(beta/qa/dev) - 기본은 실제 이용자(beta)만.
  dialogue_queries = {
    opens = {
      title  = "하루 대화 열림 (진입 방식별)"
      type   = "timeseries"
      format = "time_series"
      sql    = "select date_trunc('day', occurred_at) as time, entry_mode as metric, count(*) as value from grafana.dialogue_steps where turn_kind = 'OPEN' and traffic_type = '$traffic_type' and $__timeFilter(occurred_at) group by 1, 2 order by 1"
      pos    = { x = 0, y = 0, w = 12, h = 8 }
    }
    invite_outcomes = {
      title  = "질문 초대별 결과"
      type   = "table"
      format = "table"
      sql    = <<-SQL
        select anchor_id as "질문 지점",
               count(*) filter (where turn_kind = 'OPEN') as "초대",
               count(*) filter (where turn_kind = 'CHILD_TURN') as "아이 말",
               count(*) filter (where turn_kind = 'HELP') as "도움 요청",
               count(*) filter (where turn_kind = 'CONFIRM') as "행동 실행",
               count(*) filter (where turn_kind = 'CONFIRM' and via_suggestion) as "예시 후 선택",
               round(100.0 * count(*) filter (where turn_kind = 'CONFIRM') / nullif(count(*) filter (where turn_kind = 'OPEN'), 0), 1) as "행동 실행률 %"
        from grafana.dialogue_steps
        where anchor_id is not null and traffic_type = '$traffic_type' and $__timeFilter(occurred_at)
        group by anchor_id order by anchor_id
      SQL
      pos    = { x = 12, y = 0, w = 12, h = 8 }
    }
    help_steps = {
      title  = "도움 단계 분포 (몇 단계까지 썼나)"
      type   = "barchart"
      format = "table"
      sql    = "select anchor_id || ' · ' || help_step || '단계' as step, count(*) as \"요청\" from grafana.dialogue_steps where turn_kind = 'HELP' and traffic_type = '$traffic_type' and $__timeFilter(occurred_at) group by anchor_id, help_step order by anchor_id, help_step"
      pos    = { x = 0, y = 8, w = 12, h = 8 }
    }
    reply_kinds = {
      title  = "그레텔 답 종류"
      type   = "piechart"
      format = "table"
      sql    = "select coalesce(reply_kind, '-') as kind, count(*) as value from grafana.dialogue_steps where turn_kind = 'REPLY' and traffic_type = '$traffic_type' and $__timeFilter(occurred_at) group by 1"
      pos    = { x = 12, y = 8, w = 6, h = 8 }
    }
    dialogue_errors = {
      title  = "대화 오류율"
      type   = "stat"
      format = "table"
      sql    = "select round(100.0 * count(*) filter (where turn_kind = 'ERROR') / nullif(count(*) filter (where turn_kind = 'CHILD_TURN'), 0), 1) as \"오류율 %\" from grafana.dialogue_steps where traffic_type = '$traffic_type' and $__timeFilter(occurred_at)"
      pos    = { x = 18, y = 8, w = 6, h = 4 }
    }
    turns = {
      title  = "대화당 평균 왕복"
      type   = "stat"
      format = "table"
      sql    = "select round(avg(turn_number)::numeric, 1) as \"왕복\" from grafana.dialogue_steps where turn_kind = 'CLOSE' and traffic_type = '$traffic_type' and $__timeFilter(occurred_at)"
      pos    = { x = 18, y = 12, w = 6, h = 4 }
    }
    funnel = {
      title  = "하루 이야기 시작·완주"
      type   = "timeseries"
      format = "time_series"
      sql    = "select date_trunc('day', occurred_at) as time, case event_name when 'STORY_STARTED' then '시작' else '완주' end as metric, count(distinct session_id) as value from grafana.story_events where event_name in ('STORY_STARTED', 'STORY_COMPLETED') and traffic_type = '$traffic_type' and $__timeFilter(occurred_at) group by 1, 2 order by 1"
      pos    = { x = 0, y = 16, w = 24, h = 8 }
    }
  }
}

resource "grafana_dashboard" "dialogue_quality" {
  count  = local.postgres_enabled ? 1 : 0
  folder = grafana_folder.alerting.uid
  config_json = jsonencode({
    uid           = "qstory-dialogue-quality"
    title         = "Q-Story 대화 품질"
    tags          = ["qstory", "dialogue"]
    timezone      = "Asia/Seoul"
    schemaVersion = 39
    time          = { from = "now-14d", to = "now" }
    templating = {
      list = [{
        name    = "traffic_type"
        label   = "트래픽"
        type    = "custom"
        query   = "beta,qa,dev"
        current = { text = "beta", value = "beta" }
        options = [
          { text = "beta", value = "beta", selected = true },
          { text = "qa", value = "qa", selected = false },
          { text = "dev", value = "dev", selected = false },
        ]
      }]
    }
    panels = [for index, key in keys(local.dialogue_queries) : {
      id         = index + 1
      type       = local.dialogue_queries[key].type
      title      = local.dialogue_queries[key].title
      datasource = local.pg
      gridPos    = local.dialogue_queries[key].pos
      targets = [{
        refId      = "A"
        datasource = local.pg
        rawQuery   = true
        editorMode = "code"
        format     = local.dialogue_queries[key].format
        rawSql     = local.dialogue_queries[key].sql
      }]
    }]
  })
}
