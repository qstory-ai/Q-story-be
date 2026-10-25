# ── 비용 (FinOps) ────────────────────────────────────────────────────────────
# 운영 DB의 grafana 뷰(db/schema/098)를 읽는다. OpenRouter는 백엔드가 1시간마다 찍는 사용액(provider_cost_snapshot),
# Gemini TTS는 하루 호출 수와 음성 길이 × 단가표(finops_unit_price, 추정이면 source에 표시)로 본다.
# 기간 필터는 한국 날짜, OpenRouter "오늘 사용액"은 UTC 날짜 기준(API가 UTC 자정에 0으로 돌린다).

locals {
  finops_queries = {
    a_balance = {
      title       = "OpenRouter 잔액"
      description = "충전액 - 누적 사용액(마지막 기록 기준). 대화·리포트 분석 LLM 비용."
      type        = "stat"
      sql         = "select balance_usd as \"잔액\" from grafana.openrouter_latest"
      defaults    = { unit = "currencyUSD", decimals = 2, noValue = "-", thresholds = { mode = "absolute", steps = [{ color = "red", value = null }, { color = "orange", value = 3 }, { color = "green", value = 10 }] } }
      options     = merge(local.this_vs_prev, { colorMode = "value" })
      pos         = { x = 0, y = 0, w = 4, h = 5 }
    }
    b_or_month = {
      title       = "OpenRouter 이번 달"
      description = "이번 달(UTC) 누적 사용액과 지금 속도로 간 월말 예상."
      type        = "stat"
      sql         = <<-SQL
        select usage_monthly_usd as "이번 달",
               round(usage_monthly_usd / extract(day from (now() at time zone 'UTC'))
                 * extract(day from (date_trunc('month', now() at time zone 'UTC') + interval '1 month - 1 day')), 2) as "월말 예상"
        from grafana.openrouter_latest
      SQL
      defaults    = { unit = "currencyUSD", decimals = 2, noValue = "-" }
      options     = local.this_vs_prev
      pos         = { x = 4, y = 0, w = 6, h = 5 }
    }
    c_tts_month = {
      title       = "구글(그레텔 음성) 이번 달"
      description = "구글 클라우드 실제 청구액(BigQuery 내보내기, 크레딧 뺀 순비용). 청구가 아직 안 들어온 날은 음성 길이 × 단가 추정으로 채운다."
      type        = "stat"
      sql         = <<-SQL
        select round(coalesce(sum(cost_usd), 0), 2) as "비용",
               count(*) filter (where basis = '추정') as "추정으로 채운 날"
        from grafana.google_cost_daily where usage_date >= date_trunc('month', now() at time zone 'Asia/Seoul')::date
      SQL
      overrides   = [{ matcher = { id = "byName", options = "비용" }, properties = [{ id = "unit", value = "currencyUSD" }, { id = "decimals", value = 2 }] }]
      options     = local.this_vs_prev
      pos         = { x = 10, y = 0, w = 6, h = 5 }
    }
    d_tts_today = {
      title       = "오늘 그레텔 음성 호출"
      description = "Gemini TTS 하루 호출 수(미국 태평양 날짜, 한국 16~17시 초기화) / 하루 한도 10,000."
      type        = "gauge"
      sql         = "select coalesce((select calls from grafana.tts_calls_daily where usage_day = (now() at time zone 'America/Los_Angeles')::date), 0) as \"호출\""
      defaults    = { min = 0, max = 10000, noValue = "0", thresholds = { mode = "absolute", steps = [{ color = "green", value = null }, { color = "orange", value = 8000 }, { color = "red", value = 9500 }] } }
      pos         = { x = 16, y = 0, w = 4, h = 5 }
    }
    e_cost_per_play = {
      title       = "체험 1회당 AI 비용"
      description = "기간 안 (OpenRouter 사용액 + 구글 비용(실제, 없는 날은 추정)) ÷ 실제 이용자(BETA) 이야기 시작 수."
      type        = "stat"
      sql         = <<-SQL
        select round(((select coalesce(sum(spend_usd), 0) from grafana.openrouter_cost_daily
                        where usage_date between ($__timeFrom()::timestamptz at time zone 'UTC')::date and ($__timeTo()::timestamptz at time zone 'UTC')::date)
                     + (select coalesce(sum(cost_usd), 0) from grafana.google_cost_daily
                        where usage_date between ($__timeFrom()::timestamptz at time zone 'Asia/Seoul')::date and ($__timeTo()::timestamptz at time zone 'Asia/Seoul')::date))
                    / nullif((select count(distinct session_id) from grafana.story_events
                              where event_name = 'STORY_STARTED' and traffic_type = 'BETA' and $__timeFilter(occurred_at)), 0), 3) as "1회당"
      SQL
      defaults    = { unit = "currencyUSD", decimals = 3, noValue = "-" }
      options     = local.this_vs_prev
      pos         = { x = 20, y = 0, w = 4, h = 5 }
    }
    f_daily_cost = {
      title       = "하루 비용 (서비스별)"
      description = "OpenRouter는 UTC 날짜별 사용액, 구글은 실제 청구액(미국 태평양 날짜)이고 청구가 아직 없는 날은 음성 추정 비용. 기록은 10/25부터."
      type        = "timeseries"
      format      = "time_series"
      sql         = <<-SQL
        select (usage_date::timestamp at time zone 'UTC') as time, 'OpenRouter (대화·분석)' as metric, spend_usd as value
        from grafana.openrouter_cost_daily where $__timeFilter(usage_date::timestamp at time zone 'UTC')
        union all
        select (usage_date::timestamp at time zone 'Asia/Seoul'), '구글 (' || basis || ')', cost_usd
        from grafana.google_cost_daily where $__timeFilter(usage_date::timestamp at time zone 'Asia/Seoul')
        order by 1
      SQL
      defaults    = { unit = "currencyUSD", decimals = 2 }
      custom      = local.stacked_bars
      pos         = { x = 0, y = 5, w = 14, h = 9 }
    }
    g_tts_daily = {
      title       = "그레텔 음성 하루 호출·길이"
      description = "호출 수(한도 기준 날짜)와 실제로 들려준 음성 길이(분, 한국 날짜)."
      type        = "timeseries"
      format      = "time_series"
      sql         = <<-SQL
        select (usage_day::timestamp at time zone 'America/Los_Angeles') as time, '호출 수' as metric, calls as value
        from grafana.tts_calls_daily where $__timeFilter(usage_day::timestamp at time zone 'America/Los_Angeles')
        union all
        select (usage_date::timestamp at time zone 'Asia/Seoul'), '음성 길이(분)', round(audio_seconds / 60.0, 1)
        from grafana.tts_audio_daily where $__timeFilter(usage_date::timestamp at time zone 'Asia/Seoul')
        order by 1
      SQL
      custom      = { drawStyle = "line", fillOpacity = 10, showPoints = "always" }
      pos         = { x = 14, y = 5, w = 10, h = 9 }
    }
    h_prices = {
      title       = "단가표"
      description = "추정 단가는 source에 '추정'으로 표시. 가격표를 확인하면 운영 DB finops_unit_price만 고치면 된다."
      type        = "table"
      sql         = "select provider as \"서비스\", unit as \"단위\", usd_per_unit as \"달러/단위\", source as \"근거\", updated_at as \"고친 때\" from grafana.finops_unit_price order by 1, 2"
      pos         = { x = 0, y = 14, w = 12, h = 7 }
    }
    i_gcp_sku = {
      title       = "구글 청구 내역 (기간 안, 서비스·항목별)"
      description = "구글 클라우드 실제 청구액(크레딧 뺀 순비용). 결제 계정의 BigQuery 내보내기를 6시간마다 가져온다 - 하루 정도 늦다."
      type        = "table"
      sql         = <<-SQL
        select service as "서비스", sku as "항목", round(sum(cost_usd), 4) as "비용"
        from grafana.gcp_cost_daily
        where usage_date between ($__timeFrom()::timestamptz at time zone 'America/Los_Angeles')::date and ($__timeTo()::timestamptz at time zone 'America/Los_Angeles')::date
        group by 1, 2 having round(sum(cost_usd), 4) <> 0 order by 3 desc
      SQL
      overrides   = [{ matcher = { id = "byName", options = "비용" }, properties = [{ id = "unit", value = "currencyUSD" }, { id = "decimals", value = 4 }] }]
      pos         = { x = 12, y = 14, w = 12, h = 7 }
    }
  }
}

resource "grafana_dashboard" "finops" {
  count  = local.postgres_enabled ? 1 : 0
  folder = grafana_folder.ops.uid
  config_json = jsonencode({
    uid           = "qstory-finops"
    title         = "Q-Story 비용"
    description   = "AI 서비스 비용 - OpenRouter 잔액·이번 달·월말 예상, 그레텔 음성 호출과 추정 비용, 체험 1회당 비용."
    tags          = ["qstory", "finops", "cost"]
    timezone      = "Asia/Seoul"
    schemaVersion = 39
    time          = { from = "now-30d", to = "now" }
    panels = [for index, key in keys(local.finops_queries) : {
      id          = index + 1
      type        = local.finops_queries[key].type
      title       = local.finops_queries[key].title
      description = local.finops_queries[key].description
      datasource  = local.pg
      gridPos     = local.finops_queries[key].pos
      options     = try(local.finops_queries[key].options, {})
      fieldConfig = {
        defaults  = merge({ noValue = "0", custom = try(local.finops_queries[key].custom, {}) }, try(local.finops_queries[key].defaults, {}))
        overrides = try(local.finops_queries[key].overrides, [])
      }
      targets = [{
        refId      = "A"
        datasource = local.pg
        rawQuery   = true
        editorMode = "code"
        format     = try(local.finops_queries[key].format, "table")
        rawSql     = local.finops_queries[key].sql
      }]
    }]
  })
}
