data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  name = "occpl-${var.env}"
  azs  = slice(data.aws_availability_zones.available.names, 0, 2)
  hosts = {
    admin = "admin.${var.host_suffix}${var.domain}"
    api   = "api.${var.host_suffix}${var.domain}"
    auth  = "auth.${var.host_suffix}${var.domain}"
  }
  # Ports published by the Compose services on each instance (see compose/docker-compose.yml).
  ports = {
    core          = 8080
    core_mgmt     = 8081
    portal        = 8088
    keycloak      = 8180
    keycloak_mgmt = 9000
    jgroups       = 7800
  }
  ssm_prefix = "/occpl/${var.env}"
}
