resource "grafana_contact_point" "slack" {
  name = "qstory-backend-slack"

  slack {
    url   = var.slack_webhook_url
    title = "{{ .CommonLabels.alertname }}"
    text  = "{{ .CommonAnnotations.summary }}"
  }
}
