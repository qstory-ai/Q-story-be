# ── 사용자 응답 (출시 알림·가입·1분 후기·협업 문의·마이페이지 요청·탈퇴) ─────────────────────────────────
# 운영·영업 PM용. 운영 DB의 grafana 뷰(db/schema/091~093)를 읽는다. 상단 `데이터` 변수 기본은 "실제만" - 팀 QA·자동화
# 테스트 입력(grafana.looks_like_test, 093)을 뺀다. 지금까지 쌓인 응답의 절반 넘게가 테스트라 꼭 빼고 본다.
# 연락처(이름·이메일·전화)는 영업용 목록 두 곳(연락 동의한 신청자, 협업 문의)과 맨 아래 전체 목록에만 나온다.

locals {
  # `데이터` 변수 - 실제만(real)이면 테스트 표시된 행을 뺀다.
  real_only = "(not is_test or '$data' = 'all')"
  # 대시보드 기간과 같은 길이의 직전 기간 시작 시각.
  prev_from = "($__timeFrom()::timestamptz - ($__timeTo()::timestamptz - $__timeFrom()::timestamptz))"
  this_vs_prev = {
    reduceOptions = { values = false, calcs = ["lastNotNull"], fields = "" }
    textMode      = "value_and_name"
    colorMode     = "none"
    graphMode     = "none"
    justifyMode   = "auto"
  }
  bars_horizontal = { orientation = "horizontal", showValue = "always", xTickLabelRotation = 0, legend = { showLegend = true, displayMode = "list", placement = "bottom" } }
  stacked_bars    = { drawStyle = "bars", fillOpacity = 80, lineWidth = 1, stacking = { mode = "normal", group = "A" } }
  pie = {
    reduceOptions = { values = true, calcs = [], fields = "" }
    pieType       = "donut"
    legend        = { showLegend = true, displayMode = "table", placement = "right", values = ["value", "percent"] }
  }

  response_rows = {
    r1 = { title = "한눈에 - 이번 기간과 직전 같은 길이 기간", y = 0 }
    r2 = { title = "출시 알림 신청자", y = 14 }
    r3 = { title = "1분 체험 후기", y = 32 }
    r4 = { title = "가입·탈퇴", y = 76 }
    r5 = { title = "협업 문의", y = 85 }
    r6 = { title = "전체 최근 응답", y = 94 }
  }

  response_queries = {
    a1_launch = {
      title       = "출시 알림 신청"
      description = "랜딩 출시 알림 신청 수. 직전 기간 = 대시보드 기간과 같은 길이의 바로 앞 기간."
      type        = "stat"
      sql         = "select count(*) filter (where $__timeFilter(created_at)) as \"이번 기간\", count(*) filter (where created_at >= ${local.prev_from} and created_at < $__timeFrom()::timestamptz) as \"직전 기간\" from grafana.launch_requests where ${local.real_only}"
      options     = local.this_vs_prev
      pos         = { x = 0, y = 1, w = 4, h = 5 }
    }
    a2_contact_rate = {
      title       = "연락 동의율"
      description = "출시 알림 신청 중 연락받기에 동의한 비율과 인원(이번 기간)."
      type        = "stat"
      sql         = "select round(100.0 * count(*) filter (where wants_contact) / nullif(count(*), 0), 0) as \"동의율\", count(*) filter (where wants_contact) as \"동의 인원\" from grafana.launch_requests where $__timeFilter(created_at) and ${local.real_only}"
      options     = local.this_vs_prev
      overrides   = [{ matcher = { id = "byName", options = "동의율" }, properties = [{ id = "unit", value = "percent" }] }]
      pos         = { x = 4, y = 1, w = 4, h = 5 }
    }
    a3_signups = {
      title       = "신규 가입"
      description = "앱 가입(보호자·선생님·원장) 수."
      type        = "stat"
      sql         = "select count(*) filter (where $__timeFilter(created_at)) as \"이번 기간\", count(*) filter (where created_at >= ${local.prev_from} and created_at < $__timeFrom()::timestamptz) as \"직전 기간\" from grafana.signups where ${local.real_only}"
      options     = local.this_vs_prev
      pos         = { x = 8, y = 1, w = 4, h = 5 }
    }
    a4_surveys = {
      title       = "1분 후기"
      description = "1분 체험 후기 수와 평균 몰입도(5점 만점, 이번 기간)."
      type        = "stat"
      sql         = "select count(*) as \"후기\", round(avg(child_engagement)::numeric, 1) as \"평균 몰입\" from grafana.survey_answers where $__timeFilter(created_at) and ${local.real_only}"
      options     = local.this_vs_prev
      overrides   = [{ matcher = { id = "byName", options = "평균 몰입" }, properties = [{ id = "decimals", value = 1 }, { id = "noValue", value = "-" }] }]
      pos         = { x = 12, y = 1, w = 4, h = 5 }
    }
    a5_partnerships = {
      title       = "협업 문의"
      description = "랜딩 협업 문의 수."
      type        = "stat"
      sql         = "select count(*) filter (where $__timeFilter(created_at)) as \"이번 기간\", count(*) filter (where created_at >= ${local.prev_from} and created_at < $__timeFrom()::timestamptz) as \"직전 기간\" from grafana.partnership_list where ${local.real_only}"
      options     = local.this_vs_prev
      pos         = { x = 16, y = 1, w = 4, h = 5 }
    }
    a6_landing_conversion = {
      title       = "랜딩 방문 → 출시 알림"
      description = "랜딩을 연 실제 이용자 세션(BETA) 중 출시 알림을 신청한 비율. 신청 수 ÷ 랜딩 방문 세션."
      type        = "stat"
      sql         = <<-SQL
        select round(100.0 * l.n / nullif(v.n, 0), 1) as "전환율", v.n as "랜딩 세션"
        from (select count(*) as n from grafana.launch_requests where $__timeFilter(created_at) and ${local.real_only}) l,
             (select count(distinct session_id) as n from grafana.funnel_events
              where event_name = 'LANDING_VIEW' and traffic_type = 'BETA' and $__timeFilter(occurred_at)) v
      SQL
      options     = local.this_vs_prev
      overrides   = [{ matcher = { id = "byName", options = "전환율" }, properties = [{ id = "unit", value = "percent" }, { id = "decimals", value = 1 }, { id = "noValue", value = "-" }] }]
      pos         = { x = 20, y = 1, w = 4, h = 5 }
    }
    b1_weekly = {
      title       = "주별 응답 (종류별)"
      description = "한 주(월요일 시작, 한국 시간)에 들어온 응답 수를 종류별로 쌓았다."
      type        = "timeseries"
      format      = "time_series"
      sql         = <<-SQL
        select (date_trunc('week', occurred_at at time zone 'Asia/Seoul') at time zone 'Asia/Seoul') as time, kind as metric, count(*) as value
        from grafana.user_responses where $__timeFilter(occurred_at) and ${local.real_only} group by 1, 2 order by 1
      SQL
      custom      = local.stacked_bars
      pos         = { x = 0, y = 6, w = 14, h = 8 }
    }
    b2_cumulative = {
      title       = "누적 출시 알림·가입"
      description = "처음부터 센 누적 수(기간은 보이는 구간만 자른다)."
      type        = "timeseries"
      format      = "time_series"
      sql         = <<-SQL
        select time, metric, value from (
          select d as time, '출시 알림 신청' as metric, sum(n) over (order by d) as value
          from (select date_trunc('day', created_at at time zone 'Asia/Seoul') at time zone 'Asia/Seoul' as d, count(*) as n
                from grafana.launch_requests where ${local.real_only} group by 1) a
          union all
          select d, '가입', sum(n) over (order by d)
          from (select date_trunc('day', created_at at time zone 'Asia/Seoul') at time zone 'Asia/Seoul' as d, count(*) as n
                from grafana.signups where ${local.real_only} group by 1) b
        ) x where $__timeFilter(time) order by 1
      SQL
      custom      = { drawStyle = "line", lineInterpolation = "stepAfter", fillOpacity = 10, showPoints = "always" }
      pos         = { x = 14, y = 6, w = 10, h = 8 }
    }
    c1_sources = {
      title       = "알게 된 경로"
      description = "출시 알림 신청자가 적은 '알게 된 경로'를 묶었다(자유 입력이라 낱말로 나눔). 원문은 아래 목록에서."
      type        = "barchart"
      sql         = "select source_group as \"경로\", count(*) as \"신청\", count(*) filter (where wants_contact) as \"연락 동의\" from grafana.launch_requests where $__timeFilter(created_at) and ${local.real_only} group by 1 order by 2 desc"
      options     = local.bars_horizontal
      pos         = { x = 0, y = 15, w = 8, h = 8 }
    }
    c2_ages = {
      title       = "아이 나이대"
      description = "출시 알림 신청서의 아이 나이(자유 입력)를 나이대로 묶었다."
      type        = "barchart"
      sql         = "select age_group as \"나이대\", count(*) as \"신청\" from grafana.launch_requests where $__timeFilter(created_at) and ${local.real_only} group by 1 order by 1"
      options     = merge(local.bars_horizontal, { orientation = "auto" })
      pos         = { x = 8, y = 15, w = 8, h = 8 }
    }
    c3_gender = {
      title       = "아이 성별"
      description = "출시 알림 신청서 기준."
      type        = "piechart"
      sql         = "select gender as \"성별\", count(*) as \"신청\" from grafana.launch_requests where $__timeFilter(created_at) and ${local.real_only} group by 1 order by 2 desc"
      options     = local.pie
      pos         = { x = 16, y = 15, w = 8, h = 8 }
    }
    c4_contacts = {
      title       = "연락 동의한 신청자 (영업용)"
      description = "출시 알림 신청 중 연락받기에 동의한 사람. 최근 신청 순."
      type        = "table"
      sql         = <<-SQL
        select to_char(created_at at time zone 'Asia/Seoul', 'YYYY-MM-DD') as "신청일", parent_name as "이름", phone as "전화", email as "이메일",
               child_age as "아이 나이", gender as "성별", discovery_source as "알게 된 경로"
        from grafana.launch_requests where wants_contact and $__timeFilter(created_at) and ${local.real_only} order by created_at desc
      SQL
      pos         = { x = 0, y = 23, w = 24, h = 9 }
    }
    d1_engagement = {
      title       = "평균 몰입도"
      description = "1분 후기 '아이가 얼마나 몰입했나' 5점 만점 평균."
      type        = "stat"
      sql         = "select round(avg(child_engagement)::numeric, 1) as \"몰입 (5점)\", count(*) as \"응답\" from grafana.survey_answers where $__timeFilter(created_at) and ${local.real_only}"
      options     = local.this_vs_prev
      defaults    = { noValue = "-" }
      pos         = { x = 0, y = 33, w = 6, h = 4 }
    }
    d2_report = {
      title       = "부모 리포트 도움"
      description = "'부모 리포트가 도움이 됐나' 5점 만점 평균."
      type        = "stat"
      sql         = "select round(avg(report_helpfulness)::numeric, 1) as \"리포트 (5점)\" from grafana.survey_answers where $__timeFilter(created_at) and ${local.real_only}"
      options     = local.this_vs_prev
      defaults    = { noValue = "-", decimals = 1 }
      pos         = { x = 6, y = 33, w = 6, h = 4 }
    }
    d3_retry = {
      title       = "다시 체험 의향"
      description = "'꼭 다시 체험하고 싶어요' + '아마 다시 체험할 것 같아요' 비율."
      type        = "stat"
      sql         = "select round(100.0 * count(*) filter (where retry_interest in ('나오면 꼭 다시 체험하고 싶어요', '아마 다시 체험할 것 같아요')) / nullif(count(*), 0), 0) as \"다시 체험 의향\" from grafana.survey_answers where $__timeFilter(created_at) and ${local.real_only}"
      options     = local.this_vs_prev
      defaults    = { noValue = "-", unit = "percent" }
      pos         = { x = 12, y = 33, w = 6, h = 4 }
    }
    d4_response_rate = {
      title       = "완주 대비 후기 응답률"
      description = "이야기를 끝까지 들은 실제 이용자 세션(BETA) 중 1분 후기를 남긴 비율."
      type        = "stat"
      sql         = <<-SQL
        select round(100.0 * s.n / nullif(c.n, 0), 1) as "응답률", c.n as "완주 세션"
        from (select count(*) as n from grafana.survey_answers where $__timeFilter(created_at) and ${local.real_only}) s,
             (select count(distinct session_id) as n from grafana.funnel_events
              where event_name = 'STORY_COMPLETED' and traffic_type = 'BETA' and $__timeFilter(occurred_at)) c
      SQL
      options     = local.this_vs_prev
      overrides   = [{ matcher = { id = "byName", options = "응답률" }, properties = [{ id = "unit", value = "percent" }, { id = "decimals", value = 1 }, { id = "noValue", value = "-" }] }]
      pos         = { x = 18, y = 33, w = 6, h = 4 }
    }
    e1_reactions = {
      title       = "아이가 보인 반응 (여러 개 선택)"
      description = "후기 응답 중 그 반응을 고른 수."
      type        = "barchart"
      sql         = "select r as \"반응\", count(*) as \"응답\" from grafana.survey_answers, jsonb_array_elements_text(child_reactions) r where $__timeFilter(created_at) and ${local.real_only} group by 1 order by 2 desc"
      options     = local.bars_horizontal
      pos         = { x = 0, y = 37, w = 12, h = 8 }
    }
    e2_disruptions = {
      title       = "불편했던 점 (여러 개 선택)"
      description = "후기 응답 중 그 불편을 고른 수."
      type        = "barchart"
      sql         = "select r as \"불편\", count(*) as \"응답\" from grafana.survey_answers, jsonb_array_elements_text(disruptions) r where $__timeFilter(created_at) and ${local.real_only} group by 1 order by 2 desc"
      options     = local.bars_horizontal
      pos         = { x = 12, y = 37, w = 12, h = 8 }
    }
    e3_best = {
      title       = "가장 좋았던 점"
      description = "한 가지 선택(기타는 직접 입력)."
      type        = "barchart"
      sql         = "select best_aspect as \"좋았던 점\", count(*) as \"응답\" from grafana.survey_answers where $__timeFilter(created_at) and ${local.real_only} group by 1 order by 2 desc"
      options     = local.bars_horizontal
      pos         = { x = 0, y = 45, w = 12, h = 8 }
    }
    e4_priority = {
      title       = "가장 먼저 고쳐 줬으면 하는 점"
      description = "비워 둔 응답은 뺐다."
      type        = "barchart"
      sql         = "select top_priority as \"개선\", count(*) as \"응답\" from grafana.survey_answers where coalesce(top_priority, '') <> '' and $__timeFilter(created_at) and ${local.real_only} group by 1 order by 2 desc"
      options     = local.bars_horizontal
      pos         = { x = 12, y = 45, w = 12, h = 8 }
    }
    e5_understanding = {
      title       = "말이 이야기에 반영된 걸 알아챘나"
      description = "Q-Story 핵심 가치(아이 말 → 장면 변화)를 아이가 느꼈는지."
      type        = "barchart"
      sql         = "select input_understanding as \"답\", count(*) as \"응답\" from grafana.survey_answers where $__timeFilter(created_at) and ${local.real_only} group by 1 order by 2 desc"
      options     = local.bars_horizontal
      pos         = { x = 0, y = 53, w = 12, h = 8 }
    }
    e6_help = {
      title       = "부모 도움이 얼마나 필요했나"
      description = "아이 혼자 진행할 수 있었는지."
      type        = "barchart"
      sql         = "select help_needed as \"답\", count(*) as \"응답\" from grafana.survey_answers where $__timeFilter(created_at) and ${local.real_only} group by 1 order by 2 desc"
      options     = local.bars_horizontal
      pos         = { x = 12, y = 53, w = 12, h = 8 }
    }
    e7_next = {
      title       = "다시 체험 의향 · 다음 이야기 안내"
      description = "선택지별 응답 수."
      type        = "table"
      sql         = <<-SQL
        select '다시 체험' as "문항", retry_interest as "답", count(*) as "응답" from grafana.survey_answers where $__timeFilter(created_at) and ${local.real_only} group by 1, 2
        union all
        select '다음 이야기 안내', wants_next_stories, count(*) from grafana.survey_answers where $__timeFilter(created_at) and ${local.real_only} group by 1, 2
        order by 1, 3 desc
      SQL
      pos         = { x = 0, y = 61, w = 8, h = 8 }
    }
    e8_reviews = {
      title       = "한 줄 후기"
      description = "공개 동의 칸이 '익명으로 공개해도 괜찮아요'인 후기만 홍보에 쓸 수 있다."
      type        = "table"
      sql         = <<-SQL
        select to_char(created_at at time zone 'Asia/Seoul', 'YYYY-MM-DD') as "날짜", child_age_band as "나이", child_engagement as "몰입",
               report_helpfulness as "리포트", one_line_review as "한 줄 후기", review_usage_consent as "공개 동의"
        from grafana.survey_answers where coalesce(one_line_review, '') <> '' and $__timeFilter(created_at) and ${local.real_only}
        order by created_at desc
      SQL
      pos         = { x = 8, y = 61, w = 16, h = 8 }
    }
    e9_ages = {
      title       = "후기 남긴 아이 나이"
      description = "1분 후기 나이 선택지별 응답 수와 평균 몰입."
      type        = "barchart"
      sql         = "select child_age_band as \"나이\", count(*) as \"응답\", round(avg(child_engagement)::numeric, 1) as \"평균 몰입\" from grafana.survey_answers where $__timeFilter(created_at) and ${local.real_only} group by 1 order by 1"
      options     = merge(local.bars_horizontal, { orientation = "auto" })
      pos         = { x = 0, y = 69, w = 24, h = 7 }
    }
    g1_weekly = {
      title       = "주별 가입 (역할별)"
      description = "한 주(월요일 시작)에 가입한 계정 수."
      type        = "timeseries"
      format      = "time_series"
      sql         = <<-SQL
        select (date_trunc('week', created_at at time zone 'Asia/Seoul') at time zone 'Asia/Seoul') as time, role as metric, count(*) as value
        from grafana.signups where $__timeFilter(created_at) and ${local.real_only} group by 1, 2 order by 1
      SQL
      custom      = local.stacked_bars
      pos         = { x = 0, y = 77, w = 12, h = 8 }
    }
    g2_roles = {
      title       = "역할별 계정"
      description = "기간 안에 가입한 계정 중 지금 남아 있는 계정과 탈퇴한 계정."
      type        = "table"
      sql         = "select role as \"역할\", count(*) as \"가입\", count(*) filter (where not deleted) as \"이용 중\", count(*) filter (where deleted) as \"탈퇴\" from grafana.signups where $__timeFilter(created_at) and ${local.real_only} group by 1 order by 2 desc"
      pos         = { x = 12, y = 77, w = 6, h = 8 }
    }
    g3_deletions = {
      title       = "탈퇴 사유"
      description = "회원 탈퇴 때 고른 사유."
      type        = "piechart"
      sql         = "select reason as \"사유\", count(*) as \"탈퇴\" from grafana.account_deletions where $__timeFilter(created_at) group by 1 order by 2 desc"
      options     = local.pie
      pos         = { x = 18, y = 77, w = 6, h = 8 }
    }
    p1_kinds = {
      title       = "문의 유형"
      description = "교육기관 / 출판사·콘텐츠 / 기타."
      type        = "piechart"
      sql         = "select kind as \"유형\", count(*) as \"문의\" from grafana.partnership_list where $__timeFilter(created_at) and ${local.real_only} group by 1 order by 2 desc"
      options     = local.pie
      pos         = { x = 0, y = 86, w = 6, h = 8 }
    }
    p2_list = {
      title       = "협업 문의 목록 (영업용)"
      description = "최근 문의 순. 내용은 전문."
      type        = "table"
      sql         = <<-SQL
        select to_char(created_at at time zone 'Asia/Seoul', 'YYYY-MM-DD HH24:MI') as "시각", kind as "유형", affiliation as "소속", job_title as "직급",
               name as "이름", email as "이메일", phone as "전화", message as "내용"
        from grafana.partnership_list where $__timeFilter(created_at) and ${local.real_only} order by created_at desc
      SQL
      pos         = { x = 6, y = 86, w = 18, h = 8 }
    }
    z_recent = {
      title       = "최근 응답"
      description = "최근 200건. 내용은 종류별 요약(나이대·유입 경로·역할·점수·글 앞부분). 연락은 연락 동의 칸을 확인하고 한다."
      type        = "table"
      sql         = <<-SQL
        select to_char(occurred_at at time zone 'Asia/Seoul', 'YYYY-MM-DD HH24:MI') as "시각", kind as "종류", summary as "내용",
               name as "이름", email as "이메일", phone as "전화", contact_consent as "연락 동의"
        from grafana.user_responses where $__timeFilter(occurred_at) and ${local.real_only} order by occurred_at desc limit 200
      SQL
      pos         = { x = 0, y = 95, w = 24, h = 12 }
    }
  }
}

resource "grafana_dashboard" "user_responses" {
  count  = local.postgres_enabled ? 1 : 0
  folder = grafana_folder.ops.uid
  config_json = jsonencode({
    uid           = "qstory-user-responses"
    title         = "Q-Story 사용자 응답"
    description   = "출시 알림·가입·1분 후기·협업 문의를 문항별로 보고, 영업용 연락 목록을 본다. 기본은 테스트 입력 제외."
    tags          = ["qstory", "growth", "responses"]
    timezone      = "Asia/Seoul"
    schemaVersion = 39
    time          = { from = "now-90d", to = "now" }
    templating = {
      list = [{
        name    = "data"
        label   = "데이터"
        type    = "custom"
        query   = "실제만 : real,전체(테스트 포함) : all"
        current = { text = "실제만", value = "real" }
        options = [
          { text = "실제만", value = "real", selected = true },
          { text = "전체(테스트 포함)", value = "all", selected = false },
        ]
      }]
    }
    panels = concat(
      [for index, key in keys(local.response_rows) : {
        id        = 100 + index
        type      = "row"
        title     = local.response_rows[key].title
        collapsed = false
        panels    = []
        gridPos   = { x = 0, y = local.response_rows[key].y, w = 24, h = 1 }
      }],
      [for index, key in keys(local.response_queries) : {
        id          = index + 1
        type        = local.response_queries[key].type
        title       = local.response_queries[key].title
        description = local.response_queries[key].description
        datasource  = local.pg
        gridPos     = local.response_queries[key].pos
        options     = try(local.response_queries[key].options, {})
        fieldConfig = {
          defaults  = merge({ noValue = "0", custom = try(local.response_queries[key].custom, {}) }, try(local.response_queries[key].defaults, {}))
          overrides = try(local.response_queries[key].overrides, [])
        }
        targets = [{
          refId      = "A"
          datasource = local.pg
          rawQuery   = true
          editorMode = "code"
          format     = try(local.response_queries[key].format, "table")
          rawSql     = local.response_queries[key].sql
        }]
      }]
    )
  })
}
