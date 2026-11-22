# 바깥에서 1분마다 /health/ready를 두드려 "서비스가 응답하는지"를 직접 본다. 로그 기반 규칙은 로그가
# 와야 판단할 수 있지만, 이건 로그·Loki와 상관없이 실제 이용자 쪽에서 본 상태다.
# /health/ready는 DB까지 확인한다(HealthController) - 프로세스는 떠 있는데 DB가 끊긴 경우도 잡는다.
#
# sm_url·sm_access_token이 비어 있으면 아무것도 만들지 않는다(README '바깥에서 보는 생존 확인').

locals {
  sm_enabled = var.sm_url != "" && var.sm_access_token != ""
  # 이용자와 가까운 공개 프로브부터. 스택에 없는 이름은 건너뛴다.
  # Free 플랜은 API 실행이 월 10만 회까지다. 실행 수 = 분당 실행 횟수 x 프로브 수.
  # 프로브 3곳을 1분마다 돌리면 월 129,600회라 넘는다. 2곳이면 월 86,400회다.
  # 프로브를 늘리려면 frequency 를 120000 으로 같이 올린다.
  sm_preferred_probes = ["Seoul", "Tokyo"]
}

data "grafana_synthetic_monitoring_probes" "all" {
  count = local.sm_enabled ? 1 : 0
}

locals {
  sm_probe_map = local.sm_enabled ? data.grafana_synthetic_monitoring_probes.all[0].probes : {}
  sm_probe_ids = [for name in local.sm_preferred_probes : local.sm_probe_map[name] if contains(keys(local.sm_probe_map), name)]
}

resource "grafana_synthetic_monitoring_check" "backend_ready" {
  count     = local.sm_enabled ? 1 : 0
  job       = "qstory-backend-ready"
  target    = "${var.backend_public_url}/health/ready"
  enabled   = true
  frequency = 60000
  timeout   = 10000
  probes    = length(local.sm_probe_ids) > 0 ? local.sm_probe_ids : slice(values(local.sm_probe_map), 0, min(2, length(local.sm_probe_map)))

  labels = {
    service = "qstory-backend"
  }

  settings {
    http {
      method                          = "GET"
      valid_status_codes              = [200]
      fail_if_body_not_matches_regexp = ["\"db\":\"up\""]
    }
  }
}

# 프로브 절반 넘게 5분 동안 실패하면 critical. 프로브가 2곳이라 한 곳만 실패하면(평균 0.5) 울리지 않는다. Grafana Cloud 기본 Prometheus(grafanacloud-prom)에 쌓이는
# probe_success를 본다.
resource "grafana_rule_group" "backend_uptime" {
  count            = local.sm_enabled ? 1 : 0
  name             = "qstory-backend-uptime"
  folder_uid       = grafana_folder.alerting.uid
  interval_seconds = 60

  rule {
    name      = "backend-unreachable"
    condition = "C"
    for       = "3m"

    no_data_state  = "Alerting"
    exec_err_state = "Error"

    data {
      ref_id = "A"
      relative_time_range {
        from = 600
        to   = 0
      }
      datasource_uid = var.prometheus_datasource_uid
      model = jsonencode({
        expr          = "avg(avg_over_time(probe_success{job=\"qstory-backend-ready\"}[5m]))"
        intervalMs    = 1000
        maxDataPoints = 43200
        refId         = "A"
      })
    }

    # B: A(시계열)를 마지막 값 하나로 줄인다. 시계열에 바로 threshold를 걸면 "only reduced data can be
    # alerted on" 평가 오류가 난다(alert_rules.tf의 B와 같은 이유). 값이 하나도 없으면 no data -> Alerting.
    data {
      ref_id = "B"
      relative_time_range {
        from = 600
        to   = 0
      }
      datasource_uid = "__expr__"
      model = jsonencode({
        type       = "reduce"
        expression = "A"
        reducer    = "last"
        settings   = { mode = "dropNN" }
        refId      = "B"
      })
    }

    data {
      ref_id = "C"
      relative_time_range {
        from = 600
        to   = 0
      }
      datasource_uid = "__expr__"
      model = jsonencode({
        type       = "threshold"
        expression = "B"
        conditions = [{ evaluator = { type = "lt", params = [0.5] } }]
        refId      = "C"
      })
    }

    annotations = {
      summary     = "바깥에서 백엔드(/health/ready)에 5분째 절반 넘게 닿지 않아요."
      description = "프로세스가 죽었거나, DB가 끊겼거나(503), 서버·네트워크 문제. 가비아 서버의 docker compose ps부터 확인."
      runbook_url = "https://github.com/qstory-ai/Q-story-be/blob/main/ops/grafana-alerting/README.md#backend-unreachable"
    }

    labels = {
      severity = "critical"
      team     = "qstory-backend"
      env      = var.app_env
    }
  }
}
