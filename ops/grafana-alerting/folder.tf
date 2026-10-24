resource "grafana_folder" "alerting" {
  title = "qstory-backend"
}

# 운영·영업 PM이 보는 대시보드(사용자 응답·유입 → 전환·대화 품질). 조직 Viewer는 이 폴더만 본다.
resource "grafana_folder" "ops" {
  title = "Q-Story 운영·영업"
}

# 백엔드 상태·UT 회차(아이 발화 원문)·에러 로그는 개발만 - 조직 Viewer 권한을 뺀다.
# 권한은 이 목록이 전부다(authoritative). 조직 Admin(Terraform 서비스 계정 포함)은 폴더 권한과 관계없이 들어온다.
resource "grafana_folder_permission" "alerting" {
  folder_uid = grafana_folder.alerting.uid
  permissions {
    role       = "Editor"
    permission = "Edit"
  }
}

resource "grafana_folder_permission" "ops" {
  folder_uid = grafana_folder.ops.uid
  permissions {
    role       = "Editor"
    permission = "Edit"
  }
  permissions {
    role       = "Viewer"
    permission = "View"
  }
}
