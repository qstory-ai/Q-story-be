# 조직 기본값 - 로그인하면 사용자 응답 대시보드가 뜬다(조직 Viewer인 운영·영업 PM도 열 수 있는 폴더라서. 백엔드 상태는
# 개발 폴더라 Viewer에게는 홈으로 줄 수 없다). 시간대는 provider가 utc/browser만 받으므로 브라우저
# 시간(팀은 한국)으로 두고, 대시보드마다 timezone = "Asia/Seoul"을 따로 박아 둔다.
resource "grafana_organization_preferences" "default" {
  home_dashboard_uid = local.postgres_enabled ? grafana_dashboard.user_responses[0].uid : grafana_dashboard.backend_overview.uid
  timezone           = "browser"
  week_start         = "monday"
}
