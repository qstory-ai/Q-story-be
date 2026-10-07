# 알림을 어디로·얼마나 자주 보낼지. 규칙에는 contact point를 직접 적지 않고(alert_rules.tf) 여기서
# severity 라벨로 나눈다.
#
#   critical - @here가 붙는 채널로 바로(10초 묶음), 해결 전까지 1시간마다 다시 알림
#   warning  - 조용한 채널로 2분 묶어서, 같은 알림은 12시간에 한 번
#
# 주의: grafana_notification_policy는 스택의 알림 정책 트리 전체를 이 파일 내용으로 덮어쓴다. Grafana UI에서
# 정책을 손으로 고치면 다음 apply 때 되돌아간다 - 정책 변경은 이 파일에서 한다.
resource "grafana_notification_policy" "root" {
  contact_point   = grafana_contact_point.discord.name
  group_by        = ["alertname", "env"]
  group_wait      = "1m"
  group_interval  = "10m"
  repeat_interval = "12h"

  policy {
    matcher {
      label = "severity"
      match = "="
      value = "critical"
    }
    contact_point   = grafana_contact_point.discord_critical.name
    group_by        = ["alertname", "env"]
    group_wait      = "10s"
    group_interval  = "5m"
    repeat_interval = "1h"
  }

  policy {
    matcher {
      label = "severity"
      match = "="
      value = "warning"
    }
    contact_point   = grafana_contact_point.discord.name
    group_by        = ["alertname", "env"]
    group_wait      = "2m"
    group_interval  = "30m"
    repeat_interval = "12h"
  }
}
