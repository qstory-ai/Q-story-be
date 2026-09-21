# Discord Incoming Webhook 한 채널로 모아 보낸다. severity 라벨은 규칙에 붙어 있으므로 메시지 안에서만
# 구분하며, "critical만 @everyone 붙이기" 같은 라우팅이 필요해지면 grafana_notification_policy를 추가하면
# 된다(지금은 정책 리소스를 만들지 않아 UI에서 설정한 기본 정책과 충돌하지 않는다).
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
