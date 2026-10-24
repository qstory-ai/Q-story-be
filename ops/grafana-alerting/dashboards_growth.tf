# ── 유입 → 전환 (GA4 + 우리 DB) ─────────────────────────────────────────────
# GA4 일일 집계(백엔드 analytics/GaImportService가 매일 새벽 운영 DB로 가져온다, db/schema/085)와 우리 DB의 베타
# 퍼널 이벤트를 한 화면에. 날짜는 한국 시간(GA 속성 시간대) 기준. 상단 `트래픽` 변수 기본은 실제 이용자(BETA) -
# GA 쪽은 호스트로 나눈다(localhost·사설 IP = DEV, *.vercel.app = QA).
#
# GA 숫자 읽는 법: 방문자(totalUsers)는 날짜·유입·플랫폼으로 쪼갠 행을 더한 값이라 같은 사람이 여러 분할에 걸치면
# 두 번 센다(세션·이벤트 수는 그대로 더해도 맞다). GA가 하루 넘게 늦게 처리하므로 최근 3일은 매일 다시 받는다.
# 랜딩(qstory.ai.kr, 속성 558917997)과 웹·앱(demo.qstory.ai.kr·태블릿 앱, 555537619)은 GA 속성이 달라 둘 다 받아 합친다
# (GA_PROPERTY_ID에 쉼표로). 화면(surface)은 호스트로 나눈다.

locals {
  # GA 날짜 열(date) 기간 필터 - 대시보드 기간을 한국 날짜로 바꿔 자른다.
  ga_days = "metric_date between ($__timeFrom()::timestamptz at time zone 'Asia/Seoul')::date and ($__timeTo()::timestamptz at time zone 'Asia/Seoul')::date and traffic_type = '$traffic_type'"
  ga_time = "(metric_date::timestamp at time zone 'Asia/Seoul')"

  growth_queries = {
    a_totals = {
      title       = "기간 합계"
      description = "GA4. 세션·신규 사용자는 방문 보고서, 나머지는 이벤트 수(같은 사람이 두 번 하면 두 번)."
      type        = "stat"
      format      = "table"
      sql         = <<-SQL
        select
          (select coalesce(sum(sessions), 0) from grafana.ga_daily_traffic where ${local.ga_days}) as "세션",
          (select coalesce(sum(new_users), 0) from grafana.ga_daily_traffic where ${local.ga_days}) as "신규 사용자",
          (select coalesce(sum(sessions), 0) from grafana.ga_daily_traffic where channel = 'QR' and ${local.ga_days}) as "QR 세션",
          coalesce(sum(event_count) filter (where event_name = 'cta_click'), 0) as "CTA 클릭",
          coalesce(sum(event_count) filter (where event_name = 'story_start'), 0) as "이야기 시작",
          coalesce(sum(event_count) filter (where event_name = 'story_complete'), 0) as "완주",
          coalesce(sum(event_count) filter (where event_name = 'sign_up'), 0) as "가입",
          coalesce(sum(event_count) filter (where event_name = 'launch_notify_submit'), 0) as "출시 알림 신청"
        from grafana.ga_daily_events where ${local.ga_days}
      SQL
      pos         = { x = 0, y = 0, w = 24, h = 4 }
    }
    b_daily_traffic = {
      title       = "하루 방문자·세션"
      description = "GA4 방문 보고서. 방문자는 분할 합이라 실제보다 조금 많을 수 있다."
      type        = "timeseries"
      format      = "time_series"
      sql         = "select ${local.ga_time} as time, sum(total_users) as \"방문자\", sum(new_users) as \"신규\", sum(sessions) as \"세션\", sum(engaged_sessions) as \"참여 세션\" from grafana.ga_daily_traffic where ${local.ga_days} group by 1 order by 1"
      pos         = { x = 0, y = 4, w = 12, h = 8 }
    }
    c_daily_events = {
      title       = "하루 핵심 이벤트"
      description = "GA4 핵심 이벤트(sign_up·story_complete·launch_notify_submit·cta_click)와 story_start 하루 건수."
      type        = "timeseries"
      format      = "time_series"
      sql         = "select ${local.ga_time} as time, event_name as metric, sum(event_count) as value from grafana.ga_daily_events where event_name in ('cta_click', 'story_start', 'story_complete', 'sign_up', 'launch_notify_submit') and ${local.ga_days} group by 1, 2 order by 1"
      pos         = { x = 12, y = 4, w = 12, h = 8 }
    }
    d_channels = {
      title       = "유입 경로별 세션"
      description = "GA4 세션 소스·매체를 묶은 것. QR = utm_source=qr 또는 utm_medium=offline."
      type        = "barchart"
      format      = "table"
      sql         = "select channel as \"경로\", sum(sessions) as \"세션\", sum(engaged_sessions) as \"참여 세션\" from grafana.ga_daily_traffic where ${local.ga_days} group by 1 order by 2 desc"
      pos         = { x = 0, y = 12, w = 8, h = 8 }
    }
    e_sources = {
      title       = "유입 상세 (소스 / 매체 / 캠페인)"
      description = "GA4. 세션은 방문 보고서, 나머지는 그 유입에서 난 이벤트 수. 많은 순 50개."
      type        = "table"
      format      = "table"
      sql         = <<-SQL
        with t as (
          select session_source as s, session_medium as m, session_campaign as c,
                 sum(sessions) as sessions, sum(engaged_sessions) as engaged
          from grafana.ga_daily_traffic where ${local.ga_days} group by 1, 2, 3),
        e as (
          select session_source as s, session_medium as m, session_campaign as c,
                 sum(event_count) filter (where event_name = 'cta_click') as cta,
                 sum(event_count) filter (where event_name = 'story_start') as starts,
                 sum(event_count) filter (where event_name = 'story_complete') as completes,
                 sum(event_count) filter (where event_name = 'sign_up') as signups,
                 sum(event_count) filter (where event_name = 'launch_notify_submit') as notify
          from grafana.ga_daily_events where ${local.ga_days} group by 1, 2, 3)
        select s as "소스", m as "매체", c as "캠페인", coalesce(t.sessions, 0) as "세션", coalesce(t.engaged, 0) as "참여 세션",
               coalesce(e.cta, 0) as "CTA", coalesce(e.starts, 0) as "이야기 시작", coalesce(e.completes, 0) as "완주",
               coalesce(e.signups, 0) as "가입", coalesce(e.notify, 0) as "출시 알림"
        from t full join e using (s, m, c)
        order by coalesce(t.sessions, 0) desc limit 50
      SQL
      pos         = { x = 8, y = 12, w = 16, h = 8 }
    }
    f_qr_funnel = {
      title       = "QR 퍼널 (GA4)"
      description = "QR 코드(utm_source=qr, utm_medium=offline, qr-main)로 시작한 세션. 방문·데모 방문은 세션 수, 나머지는 이벤트 수. 데모 방문 = 앱 화면(demo.qstory.ai.kr 등)에 닿은 세션. 랜딩(속성 558917997)과 웹·앱(555537619)은 GA 속성이 달라 세션이 이어지지 않는다 - 데모 쪽 단계는 데모 주소에 utm이 실려 넘어간 경우만 QR로 잡힌다."
      type        = "barchart"
      format      = "table"
      sql         = <<-SQL
        select '1 QR 방문' as "단계", coalesce(sum(sessions), 0) as "수" from grafana.ga_daily_traffic where channel = 'QR' and ${local.ga_days}
        union all
        select '2 CTA 클릭', coalesce(sum(event_count), 0) from grafana.ga_daily_events where channel = 'QR' and event_name = 'cta_click' and ${local.ga_days}
        union all
        select '3 데모 방문', coalesce(sum(sessions), 0) from grafana.ga_daily_traffic where channel = 'QR' and surface = '앱' and ${local.ga_days}
        union all
        select '4 이야기 시작', coalesce(sum(event_count), 0) from grafana.ga_daily_events where channel = 'QR' and event_name = 'story_start' and ${local.ga_days}
        union all
        select '5 완주', coalesce(sum(event_count), 0) from grafana.ga_daily_events where channel = 'QR' and event_name = 'story_complete' and ${local.ga_days}
        union all
        select '6 가입', coalesce(sum(event_count), 0) from grafana.ga_daily_events where channel = 'QR' and event_name = 'sign_up' and ${local.ga_days}
        order by 1
      SQL
      pos         = { x = 0, y = 20, w = 12, h = 8 }
    }
    g_db_funnel = {
      title       = "우리 DB 퍼널 (베타 세션 수)"
      description = "운영 DB 베타 이벤트(story_events) 기준 단계별 세션 수. 그레텔 대화 = 대화 한 단계라도 있었던 세션, 리포트 열기 = 보호자 리포트(PARENT_REPORT_OPENED) 또는 앱 리포트(REPORT_VIEWED)."
      type        = "barchart"
      format      = "table"
      sql         = <<-SQL
        select step as "단계", count(distinct session_id) as "세션" from (
          select session_id, case
              when event_name = 'LANDING_VIEW' then '1 랜딩 방문'
              when event_name = 'STORY_STARTED' then '2 데모 시작'
              when event_name = 'DIALOGUE_STEP' then '3 그레텔 대화'
              when event_name = 'STORY_COMPLETED' then '4 완주'
              when event_name in ('PARENT_REPORT_OPENED', 'REPORT_VIEWED') then '5 리포트 열기'
            end as step
          from grafana.funnel_events
          where traffic_type = '$traffic_type' and $__timeFilter(occurred_at)) f
        where step is not null group by step order by step
      SQL
      pos         = { x = 12, y = 20, w = 12, h = 8 }
    }
    h_db_funnel_by_utm = {
      title       = "우리 DB 퍼널 - 유입(utm)별"
      description = "베타 세션의 utm_source / utm_medium / utm_campaign별 단계 세션 수. utm이 없으면 '(없음)'."
      type        = "table"
      format      = "table"
      sql         = <<-SQL
        select coalesce(utm_source, '(없음)') as "utm_source", coalesce(utm_medium, '(없음)') as "utm_medium",
               coalesce(utm_campaign, '(없음)') as "utm_campaign",
               count(distinct session_id) filter (where event_name = 'LANDING_VIEW') as "랜딩 방문",
               count(distinct session_id) filter (where event_name = 'LANDING_CTA_CLICK') as "CTA",
               count(distinct session_id) filter (where event_name = 'STORY_STARTED') as "데모 시작",
               count(distinct session_id) filter (where event_name = 'DIALOGUE_STEP') as "그레텔 대화",
               count(distinct session_id) filter (where event_name = 'STORY_COMPLETED') as "완주",
               count(distinct session_id) filter (where event_name in ('PARENT_REPORT_OPENED', 'REPORT_VIEWED')) as "리포트 열기"
        from grafana.funnel_events
        where traffic_type = '$traffic_type' and $__timeFilter(occurred_at)
        group by 1, 2, 3 order by 4 desc, 6 desc limit 50
      SQL
      pos         = { x = 0, y = 28, w = 24, h = 8 }
    }
    i_platforms = {
      title       = "플랫폼별 하루 세션 (web / ios / android)"
      description = "GA4 사용자 속성 qs_platform. 값이 없으면 (not set)."
      type        = "timeseries"
      format      = "time_series"
      sql         = "select ${local.ga_time} as time, qs_platform as metric, sum(sessions) as value from grafana.ga_daily_traffic where ${local.ga_days} group by 1, 2 order by 1"
      pos         = { x = 0, y = 36, w = 12, h = 8 }
    }
    j_surfaces = {
      title       = "랜딩 vs 앱"
      description = "이벤트가 난 호스트로 나눈다(랜딩 = qstory.ai.kr, 앱 = demo.qstory.ai.kr·태블릿 앱). 랜딩에서 앱으로 넘어간 세션은 양쪽에 다 잡힌다."
      type        = "table"
      format      = "table"
      sql         = <<-SQL
        with t as (
          select surface, sum(sessions) as sessions, sum(total_users) as users, sum(page_views) as views
          from grafana.ga_daily_traffic where ${local.ga_days} group by 1),
        e as (
          select surface,
                 sum(event_count) filter (where event_name = 'cta_click') as cta,
                 sum(event_count) filter (where event_name = 'story_start') as starts,
                 sum(event_count) filter (where event_name = 'story_complete') as completes,
                 sum(event_count) filter (where event_name = 'sign_up') as signups
          from grafana.ga_daily_events where ${local.ga_days} group by 1)
        select surface as "화면", coalesce(t.sessions, 0) as "세션", coalesce(t.users, 0) as "방문자", coalesce(t.views, 0) as "페이지뷰",
               coalesce(e.cta, 0) as "CTA", coalesce(e.starts, 0) as "이야기 시작", coalesce(e.completes, 0) as "완주", coalesce(e.signups, 0) as "가입"
        from t full join e using (surface) order by 2 desc
      SQL
      pos         = { x = 12, y = 36, w = 12, h = 8 }
    }
    k_import_runs = {
      title       = "GA 가져오기 기록"
      description = "백엔드 GA 가져오기(매일 04:40·07:40 KST, 부팅 2분 뒤 한 번) 최근 10회. FAILED면 error 열의 사유를 본다."
      type        = "table"
      format      = "table"
      sql         = "select case property_id when '558917997' then '랜딩' when '555537619' then '웹·앱' else property_id end as \"속성\", started_at as \"시작\", status as \"결과\", start_date || ' ~ ' || end_date as \"받은 기간\", event_rows as \"이벤트 행\", traffic_rows as \"방문 행\", error as \"오류\" from grafana.ga_import_runs order by started_at desc limit 10"
      pos         = { x = 0, y = 44, w = 24, h = 6 }
    }
  }
}

resource "grafana_dashboard" "growth" {
  count  = local.postgres_enabled ? 1 : 0
  folder = grafana_folder.ops.uid
  config_json = jsonencode({
    uid           = "qstory-growth"
    title         = "Q-Story 유입 → 전환"
    description   = "GA4(랜딩·데모 웹·태블릿 앱) 유입과 핵심 이벤트, QR 퍼널, 우리 DB 베타 퍼널."
    tags          = ["qstory", "growth", "ga4"]
    timezone      = "Asia/Seoul"
    schemaVersion = 39
    time          = { from = "now-30d", to = "now" }
    templating = {
      list = [{
        name    = "traffic_type"
        label   = "트래픽"
        type    = "custom"
        query   = "BETA,QA,DEV"
        current = { text = "BETA", value = "BETA" }
        options = [
          { text = "BETA", value = "BETA", selected = true },
          { text = "QA", value = "QA", selected = false },
          { text = "DEV", value = "DEV", selected = false },
        ]
      }]
    }
    panels = [for index, key in keys(local.growth_queries) : {
      id          = index + 1
      type        = local.growth_queries[key].type
      title       = local.growth_queries[key].title
      description = local.growth_queries[key].description
      datasource  = local.pg
      gridPos     = local.growth_queries[key].pos
      fieldConfig = { defaults = { noValue = "0" }, overrides = [] }
      targets = [{
        refId      = "A"
        datasource = local.pg
        rawQuery   = true
        editorMode = "code"
        format     = local.growth_queries[key].format
        rawSql     = local.growth_queries[key].sql
      }]
    }]
  })
}
