# ── 사용자 응답 (출시 알림·가입·1분 후기·협업 문의·마이페이지 요청·탈퇴) ─────────────────────────────────
# 운영 DB의 grafana.user_responses 뷰(db/schema/091·092)를 읽는다. 최근 목록에는 운영이 바로 연락할 수 있게 이름·이메일·전화와
# 연락 동의를 같이 보여 준다(자유 글 요약 속 연락처는 가린 채). 이 폴더를 볼 수 있는 사람은 모두 연락처를 보게 된다.
# 디스코드 "소식" 채널에는 같은 내용이 30분마다 묶여 온다.

locals {
  response_queries = {
    a_totals = {
      title       = "기간 합계 (종류별)"
      description = "선택한 기간에 들어온 응답 수. 가입은 보호자·선생님·원장만 센다."
      type        = "bargauge"
      format      = "table"
      sql         = "select kind as \"종류\", count(*) as \"건수\" from grafana.user_responses where $__timeFilter(occurred_at) group by kind order by 2 desc"
      pos         = { x = 0, y = 0, w = 10, h = 8 }
    }
    b_daily = {
      title       = "날짜별 건수"
      description = "한국 날짜 기준 하루 응답 수."
      type        = "timeseries"
      format      = "time_series"
      sql         = <<-SQL
        select (date_trunc('day', occurred_at at time zone 'Asia/Seoul') at time zone 'Asia/Seoul') as time, kind as metric, count(*) as value
        from grafana.user_responses where $__timeFilter(occurred_at) group by 1, 2 order by 1
      SQL
      pos         = { x = 10, y = 0, w = 14, h = 8 }
    }
    c_recent = {
      title       = "최근 응답"
      description = "최근 200건. 내용은 종류별 요약(나이대·유입 경로·역할·점수·글 앞부분). 연락은 연락 동의 칸을 확인하고 한다."
      type        = "table"
      format      = "table"
      sql         = <<-SQL
        select to_char(occurred_at at time zone 'Asia/Seoul', 'YYYY-MM-DD HH24:MI') as "시각", kind as "종류", summary as "내용",
               name as "이름", email as "이메일", phone as "전화", contact_consent as "연락 동의"
        from grafana.user_responses where $__timeFilter(occurred_at) order by occurred_at desc limit 200
      SQL
      pos         = { x = 0, y = 8, w = 24, h = 14 }
    }
  }
}

resource "grafana_dashboard" "user_responses" {
  count  = local.postgres_enabled ? 1 : 0
  folder = grafana_folder.ops.uid
  config_json = jsonencode({
    uid           = "qstory-user-responses"
    title         = "Q-Story 사용자 응답"
    description   = "출시 알림 신청·가입·1분 후기·협업 문의·마이페이지 요청·탈퇴를 날짜별로 세고 최근 목록(연락처 포함)을 본다."
    tags          = ["qstory", "growth", "responses"]
    timezone      = "Asia/Seoul"
    schemaVersion = 39
    time          = { from = "now-30d", to = "now" }
    panels = [for index, key in keys(local.response_queries) : {
      id          = index + 1
      type        = local.response_queries[key].type
      title       = local.response_queries[key].title
      description = local.response_queries[key].description
      datasource  = local.pg
      gridPos     = local.response_queries[key].pos
      fieldConfig = { defaults = { noValue = "0" }, overrides = [] }
      targets = [{
        refId      = "A"
        datasource = local.pg
        rawQuery   = true
        editorMode = "code"
        format     = local.response_queries[key].format
        rawSql     = local.response_queries[key].sql
      }]
    }]
  })
}
