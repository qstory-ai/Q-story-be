# 조직 기본값 - 로그인하면 운영 개요(백엔드 상태)가 바로 뜬다. 시간대는 provider가 utc/browser만 받으므로 브라우저
# 시간(팀은 한국)으로 두고, 대시보드마다 timezone = "Asia/Seoul"을 따로 박아 둔다.
resource "grafana_organization_preferences" "default" {
  home_dashboard_uid = grafana_dashboard.backend_overview.uid
  timezone           = "browser"
  week_start         = "monday"
}
