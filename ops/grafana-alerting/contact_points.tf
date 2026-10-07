# Discord Incoming Webhook 한 채널로 보낸다. 같은 채널에 contact point를 두 개 둔다 - critical은 @here로
# 사람을 부르고, warning은 조용히 쌓인다. 어느 쪽으로 갈지는 notification_policy.tf가 severity로 정한다.
resource "grafana_contact_point" "discord" {
  name = "qstory-backend-discord"

  discord {
    url = var.discord_webhook_url

    title = "[{{ .CommonLabels.severity | toUpper }}] {{ .CommonLabels.alertname }} · {{ .CommonLabels.env }}"

    # firing/resolved 요약, 각 인스턴스의 description, 그리고 Grafana에서 바로 조사할 수 있는 링크를
    # 한 번에 담는다. summary/description은 규칙의 annotations에서 온다(alert_rules.tf 참고).
    # Discord는 Slack의 `<url|label>` 대신 마크다운 `[label](url)` 문법을 쓴다.
    message = <<-EOT
      {{ if eq .Status "firing" }}🔥 발화 중 · {{ .Alerts.Firing | len }}건{{ else }}✅ 해제 · {{ .Alerts.Resolved | len }}건{{ end }}

      {{ range .Alerts -}}
      • {{ .Annotations.summary }}
        {{ .Annotations.description }}
        조사: [Grafana Explore]({{ .GeneratorURL }}){{ if .Annotations.runbook_url }} · [런북]({{ .Annotations.runbook_url }}){{ end }}
      {{ end }}
    EOT

    # 알림이 해제되면 다시 알려준다 - 팀이 "복구됐는지 확인하려고 Grafana를 다시 여는" 왕복을 줄인다.
    disable_resolve_message = false
  }
}

locals {
  discord_title = "[{{ .CommonLabels.severity | toUpper }}] {{ .CommonLabels.alertname }} · {{ .CommonLabels.env }}"
}

# critical 전용 - 위와 같은 웹후크·같은 형식이지만 맨 앞에 @here를 붙여 채널 사람들에게 알림이 울린다.
resource "grafana_contact_point" "discord_critical" {
  name = "qstory-backend-discord-critical"

  discord {
    url   = var.discord_webhook_url
    title = local.discord_title

    message = <<-EOT
      @here {{ if eq .Status "firing" }}🚨 지금 확인이 필요해요 · {{ .Alerts.Firing | len }}건{{ else }}✅ 해제 · {{ .Alerts.Resolved | len }}건{{ end }}

      {{ range .Alerts -}}
      • {{ .Annotations.summary }}
        {{ .Annotations.description }}
        조사: [Grafana Explore]({{ .GeneratorURL }}){{ if .Annotations.runbook_url }} · [런북]({{ .Annotations.runbook_url }}){{ end }}
      {{ end }}
    EOT

    disable_resolve_message = false
  }
}

