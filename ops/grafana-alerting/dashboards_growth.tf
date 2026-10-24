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
  # 직전 같은 길이 기간(한국 날짜). prev_from은 dashboards_responses.tf.
  ga_prev_days = "metric_date >= (${local.prev_from} at time zone 'Asia/Seoul')::date and metric_date < ($__timeFrom()::timestamptz at time zone 'Asia/Seoul')::date and traffic_type = '$traffic_type'"
  # 우리 DB 계정 - BETA는 테스트가 아닌 계정, QA·DEV는 테스트 계정(grafana.looks_like_test, db/schema/093).
  account_kind = "(('$traffic_type' = 'BETA') = not is_test)"
  # 퍼널 표 - 수는 막대로, 비율은 %로.
  funnel_overrides = [
    { matcher = { id = "byRegexp", options = "^(수|세션)$" }, properties = [{ id = "custom.cellOptions", value = { type = "gauge", mode = "basic" } }] },
    { matcher = { id = "byName", options = "이전 단계 대비 %" }, properties = [{ id = "unit", value = "percent" }, { id = "noValue", value = "-" }] },
  ]

  growth_rows = {
    r1 = { title = "한눈에 (GA4) - 이번 기간과 직전 같은 길이 기간", y = 0 }
    r2 = { title = "가입·로그인 (우리 DB)", y = 6 }
    r3 = { title = "전환 단계", y = 15 }
    r4 = { title = "유입 경로", y = 25 }
    r5 = { title = "QR·캠페인", y = 51 }
    r6 = { title = "추이·화면", y = 61 }
    r7 = { title = "데이터 상태", y = 78 }
  }


  growth_queries = {
    k1_sessions = {
      title       = "방문 세션"
      description = "GA4 세션 수(랜딩 + 앱). 직전 기간 = 고른 기간과 같은 길이의 바로 앞 기간."
      type        = "stat"
      sql         = "select (select coalesce(sum(sessions), 0) from grafana.ga_daily_traffic where ${local.ga_days}) as \"이번 기간\", (select coalesce(sum(sessions), 0) from grafana.ga_daily_traffic where ${local.ga_prev_days}) as \"직전 기간\""
      options     = local.this_vs_prev
      pos         = { x = 0, y = 1, w = 4, h = 5 }
    }
    k2_new_users = {
      title       = "신규 방문자"
      description = "GA4 처음 온 사람 수."
      type        = "stat"
      sql         = "select (select coalesce(sum(new_users), 0) from grafana.ga_daily_traffic where ${local.ga_days}) as \"이번 기간\", (select coalesce(sum(new_users), 0) from grafana.ga_daily_traffic where ${local.ga_prev_days}) as \"직전 기간\""
      options     = local.this_vs_prev
      pos         = { x = 4, y = 1, w = 4, h = 5 }
    }
    k3_engagement = {
      title       = "참여율"
      description = "GA4 참여 세션(10초 넘게 머물렀거나 두 화면 이상 봄) ÷ 세션."
      type        = "stat"
      sql         = "select (select round(100.0 * sum(engaged_sessions) / nullif(sum(sessions), 0), 0) from grafana.ga_daily_traffic where ${local.ga_days}) as \"이번 기간\", (select round(100.0 * sum(engaged_sessions) / nullif(sum(sessions), 0), 0) from grafana.ga_daily_traffic where ${local.ga_prev_days}) as \"직전 기간\""
      options     = local.this_vs_prev
      defaults    = { noValue = "-", unit = "percent" }
      pos         = { x = 8, y = 1, w = 4, h = 5 }
    }
    k4_cta_rate = {
      title       = "랜딩 CTA 클릭률"
      description = "랜딩 세션 중 '체험하기' 등 CTA를 누른 비율(클릭 수 ÷ 랜딩 세션)."
      type        = "stat"
      sql         = "select (select round(100.0 * (select sum(event_count) from grafana.ga_daily_events where event_name = 'cta_click' and surface = '랜딩' and ${local.ga_days}) / nullif(sum(sessions), 0), 0) from grafana.ga_daily_traffic where surface = '랜딩' and ${local.ga_days}) as \"이번 기간\", (select round(100.0 * (select sum(event_count) from grafana.ga_daily_events where event_name = 'cta_click' and surface = '랜딩' and ${local.ga_prev_days}) / nullif(sum(sessions), 0), 0) from grafana.ga_daily_traffic where surface = '랜딩' and ${local.ga_prev_days}) as \"직전 기간\""
      options     = local.this_vs_prev
      defaults    = { noValue = "-", unit = "percent" }
      pos         = { x = 12, y = 1, w = 4, h = 5 }
    }
    k5_story_starts = {
      title       = "이야기 시작"
      description = "GA4 story_start 이벤트 수."
      type        = "stat"
      sql         = "select (select coalesce(sum(event_count), 0) from grafana.ga_daily_events where event_name = 'story_start' and ${local.ga_days}) as \"이번 기간\", (select coalesce(sum(event_count), 0) from grafana.ga_daily_events where event_name = 'story_start' and ${local.ga_prev_days}) as \"직전 기간\""
      options     = local.this_vs_prev
      pos         = { x = 16, y = 1, w = 4, h = 5 }
    }
    k6_completes = {
      title       = "완주"
      description = "GA4 story_complete 이벤트 수."
      type        = "stat"
      sql         = "select (select coalesce(sum(event_count), 0) from grafana.ga_daily_events where event_name = 'story_complete' and ${local.ga_days}) as \"이번 기간\", (select coalesce(sum(event_count), 0) from grafana.ga_daily_events where event_name = 'story_complete' and ${local.ga_prev_days}) as \"직전 기간\""
      options     = local.this_vs_prev
      pos         = { x = 20, y = 1, w = 4, h = 5 }
    }
    l1_signups = {
      title       = "신규 가입"
      description = "앱 가입 계정 수(우리 DB). BETA는 테스트 계정을 뺀 실제 가입, QA·DEV는 테스트 계정만."
      type        = "stat"
      sql         = "select count(*) filter (where $__timeFilter(created_at)) as \"이번 기간\", count(*) filter (where created_at >= ${local.prev_from} and created_at < $__timeFrom()::timestamptz) as \"직전 기간\" from grafana.signups where ${local.account_kind}"
      options     = local.this_vs_prev
      pos         = { x = 0, y = 7, w = 4, h = 4 }
    }
    l2_logins = {
      title       = "로그인한 사람"
      description = "기간 안에 로그인한 서로 다른 계정 수(로그인 유지로 다시 들어온 사람 포함, 가입 직후 첫 로그인은 뺌). 10/24부터 기록."
      type        = "stat"
      sql         = "select count(distinct user_key) filter (where $__timeFilter(created_at)) as \"이번 기간\", count(distinct user_key) filter (where created_at >= ${local.prev_from} and created_at < $__timeFrom()::timestamptz) as \"직전 기간\" from grafana.logins where not is_signup and ${local.account_kind}"
      options     = local.this_vs_prev
      pos         = { x = 0, y = 11, w = 4, h = 4 }
    }
    l3_daily_accounts = {
      title       = "하루 로그인한 사람 · 신규 가입"
      description = "한국 날짜 기준. 로그인한 사람 = 그날 로그인(또는 로그인 유지로 들어온) 서로 다른 계정."
      type        = "timeseries"
      format      = "time_series"
      sql         = <<-SQL
        select time, metric, value from (
          select (date_trunc('day', created_at at time zone 'Asia/Seoul') at time zone 'Asia/Seoul') as time, '로그인한 사람' as metric, count(distinct user_key) as value
          from grafana.logins where not is_signup and $__timeFilter(created_at) and ${local.account_kind} group by 1
          union all
          select (date_trunc('day', created_at at time zone 'Asia/Seoul') at time zone 'Asia/Seoul'), '신규 가입', count(*)
          from grafana.signups where $__timeFilter(created_at) and ${local.account_kind} group by 1
        ) x order by 1
      SQL
      custom      = { drawStyle = "bars", fillOpacity = 80, lineWidth = 1 }
      pos         = { x = 4, y = 7, w = 12, h = 8 }
    }
    l4_login_kinds = {
      title       = "역할·방식별 로그인"
      description = "사람 = 서로 다른 계정, 횟수 = 로그인 수(로그인 유지는 하루 한 번)."
      type        = "table"
      sql         = "select role as \"역할\", method as \"방식\", count(distinct user_key) as \"사람\", count(*) as \"횟수\" from grafana.logins where not is_signup and $__timeFilter(created_at) and ${local.account_kind} group by 1, 2 order by 3 desc"
      pos         = { x = 16, y = 7, w = 8, h = 8 }
    }
    f1_ga_funnel = {
      title       = "전환 단계 (GA4)"
      description = "방문 세션은 세션 수, 나머지는 이벤트 수. 이전 단계 대비 = 바로 위 단계 수 대비 비율."
      type        = "table"
      sql         = <<-SQL
        with s as (
          select '1 방문 세션' as step, (select sum(sessions) from grafana.ga_daily_traffic where ${local.ga_days}) as n
          union all select '2 랜딩 CTA 클릭', sum(event_count) from grafana.ga_daily_events where event_name = 'cta_click' and ${local.ga_days}
          union all select '3 이야기 시작', sum(event_count) from grafana.ga_daily_events where event_name = 'story_start' and ${local.ga_days}
          union all select '4 그레텔 대화 열기', sum(event_count) from grafana.ga_daily_events where event_name = 'gretel_open' and ${local.ga_days}
          union all select '5 완주', sum(event_count) from grafana.ga_daily_events where event_name = 'story_complete' and ${local.ga_days}
          union all select '6 리포트 열기', sum(event_count) from grafana.ga_daily_events where event_name = 'report_open' and ${local.ga_days}
          union all select '7 가입', sum(event_count) from grafana.ga_daily_events where event_name = 'sign_up' and ${local.ga_days})
        select step as "단계", coalesce(n, 0) as "수", round(100.0 * n / nullif(lag(n) over (order by step), 0), 0) as "이전 단계 대비 %" from s order by step
      SQL
      overrides   = local.funnel_overrides
      pos         = { x = 0, y = 16, w = 12, h = 9 }
    }
    f2_db_funnel = {
      title       = "전환 단계 (우리 DB, 세션)"
      description = "운영 DB 베타 이벤트 기준 단계별 세션 수. 랜딩과 데모가 다른 세션으로 잡히는 경우가 있어 뒤 단계가 앞 단계보다 클 수 있다."
      type        = "table"
      sql         = <<-SQL
        with s as (
          select step, count(distinct session_id) as n from (
            select session_id, case
                when event_name = 'LANDING_VIEW' then '1 랜딩 방문'
                when event_name = 'LANDING_CTA_CLICK' then '2 랜딩 CTA'
                when event_name = 'STORY_STARTED' then '3 데모 시작'
                when event_name = 'DIALOGUE_STEP' then '4 그레텔 대화'
                when event_name = 'STORY_COMPLETED' then '5 완주'
                when event_name in ('PARENT_REPORT_OPENED', 'REPORT_VIEWED') then '6 리포트 열기'
                when event_name = 'SIGNUP_COMPLETED' then '7 가입'
              end as step
            from grafana.funnel_events where traffic_type = '$traffic_type' and $__timeFilter(occurred_at)) f
          where step is not null group by step)
        select step as "단계", n as "세션", round(100.0 * n / nullif(lag(n) over (order by step), 0), 0) as "이전 단계 대비 %" from s order by step
      SQL
      overrides   = local.funnel_overrides
      pos         = { x = 12, y = 16, w = 12, h = 9 }
    }
    c1_channels = {
      title       = "유입 경로별 성과"
      description = "GA4. 세션당 이야기 시작 = 이야기 시작 ÷ 세션. '앱 안 이동'은 앱 안 링크가 유입으로 잘못 잡힌 것(10/24 고침, 그 전 기록에 남아 있음)."
      type        = "table"
      sql         = <<-SQL
        with t as (
          select channel, sum(sessions) as s, sum(engaged_sessions) as e, sum(new_users) as nu
          from grafana.ga_daily_traffic where ${local.ga_days} group by 1),
        ev as (
          select channel,
                 sum(event_count) filter (where event_name = 'cta_click') as cta,
                 sum(event_count) filter (where event_name = 'story_start') as st,
                 sum(event_count) filter (where event_name = 'story_complete') as co,
                 sum(event_count) filter (where event_name = 'sign_up') as su,
                 sum(event_count) filter (where event_name = 'launch_notify_submit') as ln
          from grafana.ga_daily_events where ${local.ga_days} group by 1)
        select channel as "경로", coalesce(s, 0) as "세션", round(100.0 * e / nullif(s, 0), 0) as "참여율 %", coalesce(nu, 0) as "신규",
               coalesce(cta, 0) as "CTA", coalesce(st, 0) as "이야기 시작", round(100.0 * st / nullif(s, 0), 0) as "세션당 이야기 시작 %",
               coalesce(co, 0) as "완주", coalesce(su, 0) as "가입", coalesce(ln, 0) as "출시 알림"
        from t full join ev using (channel) order by 2 desc
      SQL
      pos         = { x = 0, y = 26, w = 24, h = 8 }
    }
    c2_channel_daily = {
      title       = "하루 세션 (경로별)"
      description = "GA4 세션을 경로별로 쌓았다."
      type        = "timeseries"
      format      = "time_series"
      sql         = "select ${local.ga_time} as time, channel as metric, sum(sessions) as value from grafana.ga_daily_traffic where ${local.ga_days} group by 1, 2 order by 1"
      custom      = local.stacked_bars
      pos         = { x = 0, y = 34, w = 12, h = 8 }
    }
    c3_weekdays = {
      title       = "요일별 세션"
      description = "GA4 세션 합계와 그 요일 하루 평균 - SNS·전단 올릴 요일을 고를 때."
      type        = "barchart"
      sql         = "select extract(isodow from metric_date)::int || ' ' || (array['월', '화', '수', '목', '금', '토', '일'])[extract(isodow from metric_date)::int] as \"요일\", sum(sessions) as \"세션\", round(sum(sessions)::numeric / nullif(count(distinct metric_date), 0), 1) as \"하루 평균\" from grafana.ga_daily_traffic where ${local.ga_days} group by 1 order by 1"
      options     = merge(local.bars_horizontal, { orientation = "auto" })
      pos         = { x = 12, y = 34, w = 12, h = 8 }
    }
    t2_daily_events = {
      title       = "하루 핵심 이벤트"
      description = "GA4 하루 건수."
      type        = "timeseries"
      format      = "time_series"
      sql         = "select ${local.ga_time} as time, case event_name when 'cta_click' then 'CTA 클릭' when 'story_start' then '이야기 시작' when 'gretel_open' then '그레텔 대화' when 'story_complete' then '완주' when 'report_open' then '리포트 열기' when 'sign_up' then '가입' when 'launch_notify_submit' then '출시 알림' end as metric, sum(event_count) as value from grafana.ga_daily_events where event_name in ('cta_click', 'story_start', 'gretel_open', 'story_complete', 'report_open', 'sign_up', 'launch_notify_submit') and ${local.ga_days} group by 1, 2 order by 1"
      pos         = { x = 12, y = 62, w = 12, h = 8 }
    }
    t1_daily_traffic = {
      title       = "하루 방문자·세션"
      description = "GA4 방문 보고서. 방문자는 분할 합이라 실제보다 조금 많을 수 있다."
      type        = "timeseries"
      format      = "time_series"
      sql         = "select ${local.ga_time} as time, sum(total_users) as \"방문자\", sum(new_users) as \"신규\", sum(sessions) as \"세션\", sum(engaged_sessions) as \"참여 세션\" from grafana.ga_daily_traffic where ${local.ga_days} group by 1 order by 1"
      pos         = { x = 0, y = 62, w = 12, h = 8 }
    }
    c4_sources = {
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
      pos         = { x = 0, y = 42, w = 24, h = 9 }
    }
    q1_qr_funnel = {
      title       = "QR 퍼널 (GA4)"
      description = "QR 코드(utm_source=qr, utm_medium=offline, qr-main)로 시작한 세션. 방문·데모 방문은 세션 수, 나머지는 이벤트 수. 데모 방문 = 앱 화면(demo.qstory.ai.kr 등)에 닿은 세션. 랜딩(속성 558917997)과 웹·앱(555537619)은 GA 속성이 달라 세션이 이어지지 않는다 - 데모 쪽 단계는 데모 주소에 utm이 실려 넘어간 경우만 QR로 잡힌다."
      type        = "barchart"
      options     = merge(local.bars_horizontal, { orientation = "auto" })
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
      pos         = { x = 0, y = 52, w = 8, h = 9 }
    }
    q2_db_funnel_by_utm = {
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
      pos         = { x = 8, y = 52, w = 16, h = 9 }
    }
    t3_platforms = {
      title       = "플랫폼별 하루 세션 (web / ios / android)"
      description = "GA4 사용자 속성 qs_platform. 값이 없으면 (not set)."
      type        = "timeseries"
      format      = "time_series"
      sql         = "select ${local.ga_time} as time, qs_platform as metric, sum(sessions) as value from grafana.ga_daily_traffic where ${local.ga_days} group by 1, 2 order by 1"
      pos         = { x = 0, y = 70, w = 12, h = 8 }
    }
    t4_surfaces = {
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
      pos         = { x = 12, y = 70, w = 12, h = 8 }
    }
    z_import_runs = {
      title       = "GA 가져오기 기록"
      description = "백엔드 GA 가져오기(매일 04:40·07:40 KST, 부팅 2분 뒤 한 번) 최근 10회. FAILED면 error 열의 사유를 본다."
      type        = "table"
      format      = "table"
      sql         = "select case property_id when '558917997' then '랜딩' when '555537619' then '웹·앱' else property_id end as \"속성\", started_at as \"시작\", status as \"결과\", start_date || ' ~ ' || end_date as \"받은 기간\", event_rows as \"이벤트 행\", traffic_rows as \"방문 행\", error as \"오류\" from grafana.ga_import_runs order by started_at desc limit 10"
      pos         = { x = 0, y = 79, w = 24, h = 6 }
    }
  }
}

resource "grafana_dashboard" "growth" {
  count  = local.postgres_enabled ? 1 : 0
  folder = grafana_folder.ops.uid
  config_json = jsonencode({
    uid           = "qstory-growth"
    title         = "Q-Story 유입 → 전환"
    description   = "GA4 유입·전환(직전 기간 비교), 가입·로그인, 경로별 성과, 요일, QR·캠페인, 화면·기기."
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
    panels = concat(
      [for index, key in keys(local.growth_rows) : {
        id        = 100 + index
        type      = "row"
        title     = local.growth_rows[key].title
        collapsed = false
        panels    = []
        gridPos   = { x = 0, y = local.growth_rows[key].y, w = 24, h = 1 }
      }],
      [for index, key in keys(local.growth_queries) : {
        id          = index + 1
        type        = local.growth_queries[key].type
        title       = local.growth_queries[key].title
        description = local.growth_queries[key].description
        datasource  = local.pg
        gridPos     = local.growth_queries[key].pos
        options     = try(local.growth_queries[key].options, {})
        fieldConfig = {
          defaults  = merge({ noValue = "0", custom = try(local.growth_queries[key].custom, {}) }, try(local.growth_queries[key].defaults, {}))
          overrides = try(local.growth_queries[key].overrides, [])
        }
        targets = [{
          refId      = "A"
          datasource = local.pg
          rawQuery   = true
          editorMode = "code"
          format     = try(local.growth_queries[key].format, "table")
          rawSql     = local.growth_queries[key].sql
        }]
      }]
    )
  })
}
