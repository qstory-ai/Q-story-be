# 알림 말고 한눈에 보는 화면. 알림 규칙과 같은 로그 태그·같은 폴더를 쓴다. 유입 → 전환은 dashboards_growth.tf.
#
#   백엔드 상태   - 운영 개요. Loki 로그로 요청량·5xx 비율·응답 시간·외부 AI/음성 실패·TTS 사용량·브라우저 에러·
#                   heartbeat·요청 상한 차단, 운영 DB로 오늘 이용(Postgres 연결이 있을 때)
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
        title       = "최근 24시간 Gemini TTS 호출 (하루 한도 ${var.tts_daily_limit}회)"
        description = "GeminiTtsClient 로그로 센 실제 호출 수 - 성공(gemini-tts.ok)과 한도를 쓴 실패(빈 오디오·스트림 끊김·HTTP 실패, 429 제외). 한도는 태평양 시간 자정(한국 16~17시)에 풀리므로 최근 24시간으로 근사한다. 같은 키를 데모 실시간 음성과 함께 쓴다 - 대량 생성에 쓰지 말 것. 한도에 가까우면 등급 상향 또는 키 분리."
        datasource  = local.loki
        gridPos     = { x = 0, y = 24, w = 8, h = 6 }
        fieldConfig = {
          defaults = {
            unit    = "short"
            noValue = "0"
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
          expr       = "sum(count_over_time(${local.logsel} |~ \"gemini-tts[.](ok|empty-audio|stream-failed)|gemini-http[.]failed\" !~ \"status=429\" [24h]))"
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
      {
        id          = 22
        type        = "stat"
        title       = "최근 15분 heartbeat"
        description = "app.heartbeat는 요청이 없어도 5분마다 찍힌다(health/service/HeartbeatLogger). 0이면 서버가 멈췄거나 로그 전송이 끊긴 것 - backend-silent 알림과 같은 기준."
        datasource  = local.loki
        gridPos     = { x = 0, y = 30, w = 8, h = 6 }
        fieldConfig = {
          defaults = {
            unit    = "short"
            noValue = "0"
            thresholds = {
              mode  = "absolute"
              steps = [{ color = "red", value = null }, { color = "green", value = 1 }]
            }
          }
          overrides = []
        }
        options = { reduceOptions = { calcs = ["lastNotNull"], fields = "", values = false }, colorMode = "background" }
        targets = [{
          refId      = "A"
          datasource = local.loki
          expr       = "sum(count_over_time(${local.logsel} |= \"app.heartbeat\" [15m]))"
          queryType  = "instant"
        }]
      },
      {
        id          = 23
        type        = "timeseries"
        title       = "요청 상한(429) 차단 (상한별, 5분)"
        description = "rate-limit.blocked(ratelimit/RateLimitService). group=tts-anonymous-daily면 오늘 익명 실시간 음성이 다 쓰였다는 뜻. rate-limit-spike 알림과 같은 태그."
        datasource  = local.loki
        gridPos     = { x = 0, y = 36, w = 12, h = 8 }
        fieldConfig = {
          defaults  = { unit = "short", custom = { fillOpacity = 10, lineWidth = 2 } }
          overrides = []
        }
        options = { legend = { displayMode = "list", placement = "bottom" } }
        targets = [{
          refId        = "A"
          datasource   = local.loki
          expr         = "sum by (group) (count_over_time(${local.logsel} |= \"rate-limit.blocked\" | regexp \"group=(?P<group>[^ ]+)\" [5m]))"
          legendFormat = "{{group}}"
          queryType    = "range"
        }]
      },
      # 리전 이전 판단용(docs/dev-log/구조-점검.md 5장). 백엔드(암스테르담) ↔ Supabase(서울) 한 왕복과, 요청 하나를
      # 백엔드 안에서 처리하는 시간. 이용자가 느끼는 시간 = 이 처리 시간 + 바깥 왕복(프록시 elapsedMs - duration_ms).
      {
        id          = 25
        type        = "timeseries"
        title       = "DB 왕복 ms (백엔드 → Supabase)"
        description = "app.heartbeat가 5분마다 SELECT 1을 3번 보낸 결과(HeartbeatLogger). 가장 짧은 값 = 순수 네트워크 왕복에 가까움, 첫 값 = 풀에서 연결 꺼내기 포함. 쿼리 N개짜리 요청은 대략 N배가 든다. -1은 DB 응답 없음."
        datasource  = local.loki
        gridPos     = { x = 0, y = 44, w = 12, h = 8 }
        fieldConfig = {
          defaults  = { unit = "ms", min = 0, custom = { fillOpacity = 10, lineWidth = 2 } }
          overrides = []
        }
        options = { legend = { displayMode = "list", placement = "bottom", calcs = ["mean", "min", "max"] } }
        targets = [
          {
            refId        = "A"
            datasource   = local.loki
            expr         = "min(min_over_time(${local.logsel} |= \"app.heartbeat\" | regexp \"db_ping_min_ms=(?P<v>[0-9]+)\" | unwrap v [10m]))"
            legendFormat = "가장 짧은 왕복"
            queryType    = "range"
          },
          {
            refId        = "B"
            datasource   = local.loki
            expr         = "max(max_over_time(${local.logsel} |= \"app.heartbeat\" | regexp \"db_ping_ms=(?P<v>[0-9]+)\" | unwrap v [10m]))"
            legendFormat = "첫 왕복(연결 꺼내기 포함)"
            queryType    = "range"
          },
        ]
      },
      {
        id          = 26
        type        = "timeseries"
        title       = "백엔드 처리 시간 duration_ms (프록시 elapsedMs와 비교용)"
        description = "http.request의 duration_ms - 백엔드 안에서 걸린 시간(DB 왕복 포함, 바깥 네트워크 제외). 웹 프록시(Vercel 서울)의 elapsedMs는 Vercel 로그([qstory-proxy])에만 있어 여기 없다 - 같은 x-qstory-request-id로 맞춰 보면 elapsedMs - duration_ms가 Vercel ↔ Railway 왕복이다. /health는 요청 로그를 남기지 않는다."
        datasource  = local.loki
        gridPos     = { x = 12, y = 44, w = 12, h = 8 }
        fieldConfig = {
          defaults  = { unit = "ms", min = 0, custom = { fillOpacity = 10, lineWidth = 2 } }
          overrides = []
        }
        options = { legend = { displayMode = "list", placement = "bottom", calcs = ["mean", "max"] } }
        targets = [
          {
            refId        = "A"
            datasource   = local.loki
            expr         = "quantile_over_time(0.5, ${local.logsel} |= \"http.request\" | regexp \"duration_ms=(?P<d>[0-9]+)\" | unwrap d [10m]) by (app)"
            legendFormat = "전체 p50"
            queryType    = "range"
          },
          {
            refId        = "B"
            datasource   = local.loki
            expr         = "quantile_over_time(0.95, ${local.logsel} |= \"http.request\" | regexp \"duration_ms=(?P<d>[0-9]+)\" | unwrap d [10m]) by (app)"
            legendFormat = "전체 p95"
            queryType    = "range"
          },
          {
            refId        = "C"
            datasource   = local.loki
            expr         = "quantile_over_time(0.5, ${local.logsel} |= \"http.request\" |= \"route=/v1/stories \" | regexp \"duration_ms=(?P<d>[0-9]+)\" | unwrap d [10m]) by (app)"
            legendFormat = "GET /v1/stories p50 (공개 목록, DB 사용)"
            queryType    = "range"
          },
        ]
      },
    ],
    # 운영 DB 연결이 있을 때만 - 오늘(한국 시간) 실제 이용자(BETA) 활동.
    local.postgres_enabled ? [{
      id          = 24
      type        = "stat"
      title       = "오늘 이용 (한국 시간, BETA)"
      description = "운영 DB 베타 이벤트 기준 오늘 0시부터. 방문 세션 = 이벤트가 하나라도 있는 세션, 그레텔 대화 = 대화가 열린 횟수(turn_kind=OPEN)."
      datasource  = local.pg
      gridPos     = { x = 12, y = 36, w = 12, h = 8 }
      fieldConfig = { defaults = { unit = "short", noValue = "0" }, overrides = [] }
      targets = [{
        refId      = "A"
        datasource = local.pg
        rawQuery   = true
        editorMode = "code"
        format     = "table"
        rawSql     = <<-SQL
          select count(distinct session_id) as "방문 세션",
                 count(distinct session_id) filter (where event_name = 'STORY_STARTED') as "이야기 시작",
                 count(distinct session_id) filter (where event_name = 'STORY_COMPLETED') as "완주",
                 (select count(*) from grafana.dialogue_steps
                   where turn_kind = 'OPEN' and traffic_type = 'BETA'
                     and occurred_at >= date_trunc('day', now() at time zone 'Asia/Seoul') at time zone 'Asia/Seoul') as "그레텔 대화"
          from grafana.story_events
          where traffic_type = 'BETA' and occurred_at >= date_trunc('day', now() at time zone 'Asia/Seoul') at time zone 'Asia/Seoul'
        SQL
      }]
    }] : [],
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

  # $traffic_type은 대시보드 변수(BETA/QA/DEV, DB에 대문자로 저장됨) - 기본은 실제 이용자(BETA)만.
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
  folder = grafana_folder.ops.uid
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
        query   = "BETA,QA,DEV"
        current = { text = "BETA", value = "BETA" }
        options = [
          { text = "BETA", value = "BETA", selected = true },
          { text = "QA", value = "QA", selected = false },
          { text = "DEV", value = "DEV", selected = false },
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

# ── UT 회차 (Q-40) ───────────────────────────────────────────────────────────
# 현장 관찰·인터뷰를 앱 기록에 잇는 화면. 관찰자가 플레이어 홈 메뉴·리포트 맨 아래의 "회차 코드"(회차 id 앞 6자)를
# 적어 오면, 위 칸에 넣어 그 회차의 대화 한 줄 한 줄과 그 참여자의 방문·가입·리포트 흐름을 본다.
# 원문이 보이므로 팀 내부 분석용(db/schema/070-ut-data-collection.sql 참고).

locals {
  ut_queries = {
    sessions = {
      title = "최근 회차"
      type  = "table"
      sql   = "select session_code as \"회차 코드\", started_at as \"시작\", user_role as \"역할\", child_age_years as \"아이 나이\", kind as \"구분\", play_setting as \"진행 형태\", entry_source as \"진입\", device_platform as \"기기\", read_from_scene_id || ' → ' || coalesce(read_through_scene_id, '-') as \"읽은 범위\", coalesce(end_status, '진행 중') as \"종료\", child_turns as \"아이 말\", help_steps as \"도움\", actions as \"행동 실행\", actions_from_example as \"예시 선택\", failures as \"실패\", avg_reply_ms as \"답 평균 ms\", report_views as \"리포트 열람\" from grafana.ut_sessions where $__timeFilter(started_at) order by started_at desc limit 200"
      pos   = { x = 0, y = 0, w = 24, h = 9 }
    }
    turns = {
      title = "회차 대화 ($session_code)"
      type  = "table"
      sql   = "select seq as \"순서\", occurred_at as \"시각\", scene_id as \"장면\", anchor_id as \"질문 지점\", entry_mode as \"진입\", role as \"누가\", speaker as \"입력 주체\", text_length as \"글자 수\", input_mode as \"음성/글\", transcript_edited as \"고쳐 씀\", help_step as \"도움 단계\", reply_kind as \"답 종류\", latency_ms as \"답 ms\", event as \"이벤트\", family_id as \"행동\", via_suggestion as \"예시\", error_code as \"오류\" from grafana.ut_turns where session_code = upper('$session_code') order by seq"
      pos   = { x = 0, y = 9, w = 24, h = 12 }
    }
    journey = {
      title = "이 참여자의 흐름 (방문·가입·연결·플레이·리포트)"
      type  = "table"
      sql   = "select e.occurred_at as \"시각\", e.event_name as \"이벤트\", e.metadata::text as \"내용\" from grafana.ut_events e where e.user_id = (select user_id from grafana.ut_sessions where session_code = upper('$session_code') limit 1) order by e.occurred_at"
      pos   = { x = 0, y = 21, w = 24, h = 10 }
    }
    funnel = {
      title = "가입·연결·사용 퍼널 (기간 안, 실제 이용자)"
      type  = "barchart"
      sql   = "select step as \"단계\", count(distinct coalesce(user_id::text, '')) filter (where user_id is not null) as \"계정\", count(*) as \"이벤트\" from (select case event_name when 'APP_ENTRY' then '1 앱 진입' when 'SIGNUP_STARTED' then '2 가입 시작' when 'SIGNUP_COMPLETED' then '3 가입 완료' when 'CHILD_REGISTERED' then '4 아이 등록' when 'CLASS_JOIN' then '5 반 연결 ' || coalesce(metadata ->> 'step', '') when 'STORY_STARTED' then '6 이야기 시작' when 'STORY_COMPLETED' then '7 완주' when 'REPORT_VIEWED' then '8 리포트 열람' end as step, user_id from grafana.ut_events where traffic_type = 'BETA' and $__timeFilter(occurred_at)) f where step is not null group by step order by step"
      pos   = { x = 0, y = 31, w = 12, h = 9 }
    }
    controls = {
      title = "재생 조작 (장면별)"
      type  = "table"
      sql   = "select metadata ->> 'scene_id' as \"장면\", metadata ->> 'action' as \"조작\", count(*) as \"횟수\" from grafana.ut_events where event_name = 'PLAYBACK_CONTROL' and traffic_type = 'BETA' and $__timeFilter(occurred_at) group by 1, 2 order by 1, 3 desc"
      pos   = { x = 12, y = 31, w = 12, h = 9 }
    }
    after_report = {
      title = "리포트 이후 (다시 읽기·펼쳐 보기)"
      type  = "table"
      sql   = "select metadata ->> 'action' as \"리포트에서 한 것\", metadata ->> 'kind' as \"리포트 종류\", count(*) as \"횟수\" from grafana.ut_events where event_name = 'REPORT_ACTION' and traffic_type = 'BETA' and $__timeFilter(occurred_at) group by 1, 2 order by 3 desc"
      pos   = { x = 0, y = 40, w = 24, h = 7 }
    }
  }
}

resource "grafana_dashboard" "ut_sessions" {
  count  = local.postgres_enabled ? 1 : 0
  folder = grafana_folder.alerting.uid
  config_json = jsonencode({
    uid           = "qstory-ut-sessions"
    title         = "Q-Story UT 회차"
    tags          = ["qstory", "ut"]
    timezone      = "Asia/Seoul"
    schemaVersion = 39
    time          = { from = "now-14d", to = "now" }
    templating = {
      list = [{
        name    = "session_code"
        label   = "회차 코드"
        type    = "textbox"
        query   = ""
        current = { text = "", value = "" }
      }]
    }
    panels = [for index, key in keys(local.ut_queries) : {
      id         = index + 1
      type       = local.ut_queries[key].type
      title      = local.ut_queries[key].title
      datasource = local.pg
      gridPos    = local.ut_queries[key].pos
      targets = [{
        refId      = "A"
        datasource = local.pg
        rawQuery   = true
        editorMode = "code"
        format     = "table"
        rawSql     = local.ut_queries[key].sql
      }]
    }]
  })
}
