terraform {
  # 1.7+ 이유: `cloud {}` 블록을 env-var(TF_CLOUD_ORGANIZATION / TF_WORKSPACE)로 채우는 방식이
  # 안정 지원되는 최소 버전. GH Actions 워크플로에서 organization/workspace를 하드코딩하지 않고
  # 넣을 수 있게 하기 위함(.github/workflows/grafana-alerting.yml 참고).
  required_version = ">= 1.7"

  required_providers {
    grafana = {
      source  = "grafana/grafana"
      version = "~> 3.0"
    }
  }

  # state 백엔드: Terraform Cloud(무료 티어) 워크스페이스. 실행은 GH Actions 러너에서 하고
  # TFC는 state만 잡는다(워크스페이스 Execution Mode = Local). 좌표는 env-var 주입 - README '자동화'
  # 절과 .github/workflows/grafana-alerting.yml 참고. 로컬에서 apply하려면 같은 env-var 두 개를
  # 셸에서 export해야 한다.
  cloud {}
}

provider "grafana" {
  url  = var.grafana_url
  auth = var.grafana_auth
}
