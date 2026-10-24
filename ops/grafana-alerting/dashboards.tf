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
# 운영·영업 PM과 기획이 보는 화면. 이야기 흐름(시작 → 질문 → 대화 → 완주 → 리포트), 장면별 이탈, 질문 지점별 반응,
# 그레텔 답과 대기 시간, 재생 조작을 본다. 원문(아이 말·답 문장)은 없다 - 원문은 UT 회차(개발 폴더)에서만.
# 상단 `트래픽` 변수 기본은 실제 이용자(BETA). 차트 꾸밈(stat·막대·도넛)은 dashboards_responses.tf의 locals를 같이 쓴다.

locals {
  pg = local.postgres_enabled ? { type = "grafana-postgresql-datasource", uid = grafana_data_source.postgres[0].uid } : null

  # $traffic_type은 대시보드 변수(BETA/QA/DEV, DB에 대문자로 저장됨).
  in_range = "traffic_type = '$traffic_type' and $__timeFilter(occurred_at)"
  # 아이 말(CHILD_TURN) 바로 다음 그레텔 답(REPLY)까지 걸린 초 - 같은 세션 안에서 다음 단계를 본다.
  reply_waits = <<-SQL
    select occurred_at, extract(epoch from next_at - occurred_at) as wait_s from (
      select occurred_at, turn_kind,
             lead(turn_kind) over w as next_kind, lead(occurred_at) over w as next_at
      from grafana.dialogue_steps where turn_kind in ('CHILD_TURN', 'REPLY', 'ERROR') and ${local.in_range}
      window w as (partition by session_id order by occurred_at)) t
    where turn_kind = 'CHILD_TURN' and next_kind = 'REPLY'
  SQL

  dialogue_rows = {
    r1 = { title = "한눈에", y = 0 }
    r2 = { title = "이야기 흐름 - 어디까지 듣고, 어디서 멈추나", y = 6 }
    r3 = { title = "질문 지점별 반응", y = 24 }
    r4 = { title = "그레텔 답", y = 42 }
    r5 = { title = "재생 조작", y = 51 }
  }

  dialogue_queries = {
    k1_started = {
      title       = "이야기 시작"
      description = "이야기를 시작한 세션 수."
      type        = "stat"
      sql         = "select count(distinct session_id) as \"세션\" from grafana.story_events where event_name = 'STORY_STARTED' and ${local.in_range}"
      options     = local.this_vs_prev
      pos         = { x = 0, y = 1, w = 3, h = 5 }
    }
    k2_completion = {
      title       = "완주율"
      description = "이야기를 시작한 세션 중 끝까지 들은 비율."
      type        = "stat"
      sql         = "select round(100.0 * count(distinct session_id) filter (where event_name = 'STORY_COMPLETED') / nullif(count(distinct session_id) filter (where event_name = 'STORY_STARTED'), 0), 0) as \"완주율\", count(distinct session_id) filter (where event_name = 'STORY_COMPLETED') as \"완주\" from grafana.story_events where ${local.in_range}"
      options     = local.this_vs_prev
      overrides   = [{ matcher = { id = "byName", options = "완주율" }, properties = [{ id = "unit", value = "percent" }, { id = "noValue", value = "-" }] }]
      pos         = { x = 3, y = 1, w = 3, h = 5 }
    }
    k3_talk_rate = {
      title       = "대화 참여율"
      description = "이야기를 시작한 세션 중 아이가 그레텔에게 한 번이라도 말한 비율."
      type        = "stat"
      sql         = <<-SQL
        select round(100.0 * t.n / nullif(s.n, 0), 0) as "참여율", t.n as "말한 세션"
        from (select count(distinct session_id) as n from grafana.dialogue_steps where turn_kind = 'CHILD_TURN' and ${local.in_range}) t,
             (select count(distinct session_id) as n from grafana.story_events where event_name = 'STORY_STARTED' and ${local.in_range}) s
      SQL
      options     = local.this_vs_prev
      overrides   = [{ matcher = { id = "byName", options = "참여율" }, properties = [{ id = "unit", value = "percent" }, { id = "noValue", value = "-" }] }]
      pos         = { x = 6, y = 1, w = 3, h = 5 }
    }
    k4_spontaneous = {
      title       = "아이가 먼저 건 대화"
      description = "질문 초대 없이 아이가 스스로 그레텔을 불러 대화를 연 횟수와 그런 세션 수. 아이의 자발적 참여 지표."
      type        = "stat"
      sql         = "select count(*) as \"횟수\", count(distinct session_id) as \"세션\" from grafana.dialogue_steps where turn_kind = 'OPEN' and entry_mode = 'SPONTANEOUS' and ${local.in_range}"
      options     = local.this_vs_prev
      pos         = { x = 9, y = 1, w = 3, h = 5 }
    }
    k5_turns = {
      title       = "대화당 평균 왕복"
      description = "대화 한 번에 아이와 그레텔이 주고받은 평균 횟수."
      type        = "stat"
      sql         = "select round(avg(turn_number)::numeric, 1) as \"왕복\" from grafana.dialogue_steps where turn_kind = 'CLOSE' and ${local.in_range}"
      options     = local.this_vs_prev
      defaults    = { noValue = "-", decimals = 1 }
      pos         = { x = 12, y = 1, w = 3, h = 5 }
    }
    k6_wait = {
      title       = "답 기다린 시간"
      description = "아이가 말한 뒤 그레텔 답이 나오기까지 걸린 시간(중앙값과 느린 쪽 10%)."
      type        = "stat"
      sql         = "select round(percentile_cont(0.5) within group (order by wait_s)::numeric, 1) as \"중앙값\", round(percentile_cont(0.9) within group (order by wait_s)::numeric, 1) as \"느린 10%\" from (${local.reply_waits}) w"
      options     = local.this_vs_prev
      defaults    = { noValue = "-", unit = "s", decimals = 1 }
      pos         = { x = 15, y = 1, w = 3, h = 5 }
    }
    k7_voice = {
      title       = "음성으로 말한 비율"
      description = "아이 말 중 음성 인식으로 들어온 비율(나머지는 글자·예시 선택)."
      type        = "stat"
      sql         = "select round(100.0 * count(*) filter (where turn_kind = 'TRANSCRIBED') / nullif(count(*) filter (where turn_kind = 'CHILD_TURN'), 0), 0) as \"음성\" from grafana.dialogue_steps where ${local.in_range}"
      options     = local.this_vs_prev
      defaults    = { noValue = "-", unit = "percent" }
      pos         = { x = 18, y = 1, w = 3, h = 5 }
    }
    k8_errors = {
      title       = "대화 오류율"
      description = "아이 말 중 답을 못 만들고 오류가 난 비율. 2% 넘으면 개발에 알려 주세요."
      type        = "stat"
      sql         = "select round(100.0 * count(*) filter (where turn_kind = 'ERROR') / nullif(count(*) filter (where turn_kind = 'CHILD_TURN'), 0), 1) as \"오류율\", count(*) filter (where turn_kind = 'ERROR') as \"오류\" from grafana.dialogue_steps where ${local.in_range}"
      options     = merge(local.this_vs_prev, { colorMode = "value" })
      overrides = [{ matcher = { id = "byName", options = "오류율" }, properties = [
        { id = "unit", value = "percent" }, { id = "decimals", value = 1 }, { id = "noValue", value = "-" },
        { id = "thresholds", value = { mode = "absolute", steps = [{ color = "green", value = null }, { color = "orange", value = 2 }, { color = "red", value = 5 }] } },
      ] }]
      pos = { x = 21, y = 1, w = 3, h = 5 }
    }
    f1_funnel = {
      title       = "세션 퍼널"
      description = "단계별 세션 수. 대화 참여 = 아이가 한 번이라도 말함, 리포트 = 보호자·앱 리포트를 연 세션."
      type        = "barchart"
      sql         = <<-SQL
        select step as "단계", n as "세션" from (
          select '1 이야기 시작' as step, count(distinct session_id) as n from grafana.story_events where event_name = 'STORY_STARTED' and ${local.in_range}
          union all select '2 질문 초대 봄', count(distinct session_id) from grafana.story_events where event_name = 'QUESTION_INVITE_SHOWN' and ${local.in_range}
          union all select '3 대화 참여', count(distinct session_id) from grafana.dialogue_steps where turn_kind = 'CHILD_TURN' and ${local.in_range}
          union all select '4 완주', count(distinct session_id) from grafana.story_events where event_name = 'STORY_COMPLETED' and ${local.in_range}
          union all select '5 리포트 열기', count(distinct session_id) from grafana.story_events where event_name in ('PARENT_REPORT_OPENED', 'REPORT_VIEWED') and ${local.in_range}
        ) f order by 1
      SQL
      options     = merge(local.bars_horizontal, { orientation = "auto" })
      pos         = { x = 0, y = 7, w = 12, h = 9 }
    }
    f2_scenes = {
      title       = "장면별 도달 (이탈 곡선)"
      description = "각 장면까지 온 세션 수와 첫 장면 대비 비율. 크게 줄어드는 장면이 이탈 지점."
      type        = "barchart"
      sql         = <<-SQL
        select scene_id as "장면", n as "도달 세션", round(100.0 * n / nullif(first_value(n) over (order by scene_id), 0), 0) as "첫 장면 대비 %"
        from (select scene_id, count(distinct session_id) as n from grafana.story_events
              where event_name = 'SCENE_REACHED' and scene_id is not null and ${local.in_range} group by 1) s
        order by 1
      SQL
      options     = merge(local.bars_horizontal, { orientation = "auto" })
      pos         = { x = 12, y = 7, w = 12, h = 9 }
    }
    f3_daily = {
      title       = "하루 이야기 시작·완주"
      description = "한국 날짜 기준 하루 세션 수."
      type        = "timeseries"
      format      = "time_series"
      sql         = "select (date_trunc('day', occurred_at at time zone 'Asia/Seoul') at time zone 'Asia/Seoul') as time, case event_name when 'STORY_STARTED' then '시작' else '완주' end as metric, count(distinct session_id) as value from grafana.story_events where event_name in ('STORY_STARTED', 'STORY_COMPLETED') and ${local.in_range} group by 1, 2 order by 1"
      custom      = { drawStyle = "line", fillOpacity = 10, showPoints = "always" }
      pos         = { x = 0, y = 16, w = 24, h = 8 }
    }
    q1_anchors = {
      title       = "질문 지점별 반응"
      description = "초대 본 세션 = 질문 초대가 뜬 세션, 답한 세션 = 그 지점에서 아이가 말한 세션. 건너뜀은 횟수(한 세션에서 여러 번일 수 있음)."
      type        = "table"
      sql         = <<-SQL
        select coalesce(i.anchor_id, d.anchor_id) as "질문 지점", coalesce(i.shown, 0) as "초대 본 세션", coalesce(d.answered, 0) as "답한 세션",
               round(100.0 * coalesce(d.answered, 0) / nullif(i.shown, 0), 0) as "답한 비율 %", coalesce(i.skipped, 0) as "건너뜀",
               coalesce(d.helps, 0) as "도움 요청", coalesce(d.actions, 0) as "행동 실행", coalesce(d.from_example, 0) as "예시 보고 고름"
        from (select anchor_id, count(distinct session_id) filter (where event_name = 'QUESTION_INVITE_SHOWN') as shown,
                     count(*) filter (where event_name = 'QUESTION_SKIPPED') as skipped
              from grafana.story_events where anchor_id is not null and ${local.in_range} group by 1) i
        full join (select anchor_id, count(distinct session_id) filter (where turn_kind = 'CHILD_TURN') as answered,
                          count(*) filter (where turn_kind = 'HELP') as helps, count(*) filter (where turn_kind = 'CONFIRM') as actions,
                          count(*) filter (where turn_kind = 'CONFIRM' and via_suggestion) as from_example
                   from grafana.dialogue_steps where anchor_id is not null and ${local.in_range} group by 1) d on d.anchor_id = i.anchor_id
        order by 1
      SQL
      pos         = { x = 0, y = 25, w = 24, h = 7 }
    }
    q2_help = {
      title       = "도움 단계 (몇 단계까지 썼나)"
      description = "아이가 막혀서 도움을 받은 단계. 단계가 높을수록 질문이 어려웠다는 뜻."
      type        = "barchart"
      sql         = "select anchor_id || ' · ' || help_step || '단계' as \"지점·단계\", count(*) as \"요청\" from grafana.dialogue_steps where turn_kind = 'HELP' and ${local.in_range} group by anchor_id, help_step order by anchor_id, help_step"
      options     = local.bars_horizontal
      pos         = { x = 0, y = 32, w = 12, h = 10 }
    }
    q3_opens = {
      title       = "하루 대화 열림 (어떻게 시작했나)"
      description = "질문 초대로 열림 / 아이가 먼저 열림 / 도움 요청으로 열림."
      type        = "timeseries"
      format      = "time_series"
      sql         = "select (date_trunc('day', occurred_at at time zone 'Asia/Seoul') at time zone 'Asia/Seoul') as time, case entry_mode when 'INVITE' then '질문 초대' when 'SPONTANEOUS' then '아이가 먼저' when 'HELP' then '도움 요청' else coalesce(entry_mode, '-') end as metric, count(*) as value from grafana.dialogue_steps where turn_kind = 'OPEN' and ${local.in_range} group by 1, 2 order by 1"
      custom      = local.stacked_bars
      pos         = { x = 12, y = 32, w = 12, h = 10 }
    }
    a1_reply_kinds = {
      title       = "그레텔 답 종류"
      description = "답변 / 공감 / 이야기로 돌림(주제를 벗어났을 때) / 마무리 / 행동 시도 실패."
      type        = "piechart"
      sql         = "select case reply_kind when 'ANSWER' then '답변' when 'REDIRECT' then '이야기로 돌림' when 'CLOSE' then '마무리' when 'TRY_FAIL' then '행동 시도 실패' when 'EMPATHY' then '공감' else coalesce(reply_kind, '-') end as \"답 종류\", count(*) as \"답\" from grafana.dialogue_steps where turn_kind = 'REPLY' and ${local.in_range} group by 1 order by 2 desc"
      options     = local.pie
      pos         = { x = 0, y = 43, w = 8, h = 8 }
    }
    a2_close = {
      title       = "대화가 끝난 방식"
      description = "이야기로 돌아감(그레텔이 마무리) / 아이가 직접 끝냄 등."
      type        = "piechart"
      sql         = "select case reply_kind when 'CONTINUE' then '이야기로 돌아감' when 'CHILD_ENDED' then '아이가 끝냄' when 'CLOSED_BY_REPLY' then '그레텔 답으로 끝남' else coalesce(reply_kind, '-') end as \"끝난 방식\", count(*) as \"대화\" from grafana.dialogue_steps where turn_kind = 'CLOSE' and ${local.in_range} group by 1 order by 2 desc"
      options     = local.pie
      pos         = { x = 8, y = 43, w = 8, h = 8 }
    }
    a3_wait_daily = {
      title       = "하루 답 기다린 시간"
      description = "아이 말 → 그레텔 답까지 걸린 초(하루 중앙값과 느린 쪽 10%)."
      type        = "timeseries"
      format      = "time_series"
      sql         = <<-SQL
        select (date_trunc('day', occurred_at at time zone 'Asia/Seoul') at time zone 'Asia/Seoul') as time,
               percentile_cont(0.5) within group (order by wait_s) as "중앙값", percentile_cont(0.9) within group (order by wait_s) as "느린 10%"
        from (${local.reply_waits}) w group by 1 order by 1
      SQL
      defaults    = { unit = "s", decimals = 1 }
      custom      = { drawStyle = "line", fillOpacity = 0, showPoints = "always" }
      pos         = { x = 16, y = 43, w = 8, h = 8 }
    }
    p1_controls = {
      title       = "재생 조작 종류"
      description = "듣는 중에 누른 조작 횟수."
      type        = "barchart"
      sql         = "select case action when 'skip_scene' then '장면 넘기기' when 'pause' then '일시정지' when 'resume' then '다시 재생' when 'exit' then '나가기' when 'chapter_jump' then '장면 이동' when 'home_menu' then '홈 메뉴' when 'replay' then '다시 듣기' else coalesce(action, '-') end as \"조작\", count(*) as \"횟수\", count(distinct session_id) as \"세션\" from grafana.story_events where event_name = 'PLAYBACK_CONTROL' and ${local.in_range} group by 1 order by 2 desc"
      options     = local.bars_horizontal
      pos         = { x = 0, y = 52, w = 8, h = 9 }
    }
    p2_skips = {
      title       = "장면별 넘기기·나가기"
      description = "어느 장면에서 넘기거나 나갔나. 넘기기가 몰린 장면은 지루하거나 길다는 신호."
      type        = "barchart"
      sql         = "select scene_id as \"장면\", count(*) filter (where action = 'skip_scene') as \"넘기기\", count(*) filter (where action in ('exit', 'home_menu')) as \"나가기\", count(*) filter (where action = 'replay') as \"다시 듣기\" from grafana.story_events where event_name = 'PLAYBACK_CONTROL' and scene_id is not null and ${local.in_range} group by 1 order by 1"
      options     = merge(local.bars_horizontal, { orientation = "auto", stacking = "none" })
      pos         = { x = 8, y = 52, w = 16, h = 9 }
    }
  }
}

resource "grafana_dashboard" "dialogue_quality" {
  count  = local.postgres_enabled ? 1 : 0
  folder = grafana_folder.ops.uid
  config_json = jsonencode({
    uid           = "qstory-dialogue-quality"
    title         = "Q-Story 대화 품질"
    description   = "이야기 흐름·장면별 이탈·질문 지점별 반응·그레텔 답과 대기 시간·재생 조작(원문 없음)."
    tags          = ["qstory", "dialogue"]
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
      [for index, key in keys(local.dialogue_rows) : {
        id        = 100 + index
        type      = "row"
        title     = local.dialogue_rows[key].title
        collapsed = false
        panels    = []
        gridPos   = { x = 0, y = local.dialogue_rows[key].y, w = 24, h = 1 }
      }],
      [for index, key in keys(local.dialogue_queries) : {
        id          = index + 1
        type        = local.dialogue_queries[key].type
        title       = local.dialogue_queries[key].title
        description = local.dialogue_queries[key].description
        datasource  = local.pg
        gridPos     = local.dialogue_queries[key].pos
        options     = try(local.dialogue_queries[key].options, {})
        fieldConfig = {
          defaults  = merge({ noValue = "0", custom = try(local.dialogue_queries[key].custom, {}) }, try(local.dialogue_queries[key].defaults, {}))
          overrides = try(local.dialogue_queries[key].overrides, [])
        }
        targets = [{
          refId      = "A"
          datasource = local.pg
          rawQuery   = true
          editorMode = "code"
          format     = try(local.dialogue_queries[key].format, "table")
          rawSql     = local.dialogue_queries[key].sql
        }]
      }]
    )
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
