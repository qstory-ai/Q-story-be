# 대화 품질·이야기 진행 대시보드(dashboards.tf)가 읽는 운영 DB. grafana_reader 계정은 grafana 스키마의
# 뷰(db/schema/066-grafana-views.sql)만 읽을 수 있어서 원본 테이블과 아이 질문 문장은 보이지 않는다.
# postgres_host가 비어 있으면 만들지 않는다.

locals {
  postgres_enabled = var.postgres_host != "" && var.postgres_user != "" && var.postgres_password != ""
}

resource "grafana_data_source" "postgres" {
  count    = local.postgres_enabled ? 1 : 0
  type     = "grafana-postgresql-datasource"
  name     = "qstory-postgres-readonly"
  url      = var.postgres_host
  username = var.postgres_user

  json_data_encoded = jsonencode({
    database        = var.postgres_database
    sslmode         = "require"
    postgresVersion = 1500
    timescaledb     = false
    maxOpenConns    = 2
    maxIdleConns    = 1
  })

  secure_json_data_encoded = jsonencode({
    password = var.postgres_password
  })
}
