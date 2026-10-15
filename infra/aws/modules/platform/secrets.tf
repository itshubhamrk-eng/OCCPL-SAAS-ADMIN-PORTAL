# Generated application secrets, one JSON secret per environment. Instances read it at deploy time
# (compose/bin/render-env.sh). Values are also in the Terraform state: keep the state bucket private.
# Razorpay keys are entered by an operator into the separate razorpay secret (see RUNBOOK.md).

resource "random_password" "app" {
  for_each = toset([
    "db_app", "db_migrator", "db_backup", "db_keycloak",
    "keycloak_admin", "keycloak_admin_client", "provisioning_client",
  ])
  length  = 40
  special = false
}

resource "aws_secretsmanager_secret" "app" {
  name        = "occpl/${var.env}/app"
  description = "Generated secrets of the ${var.env} platform"
}

resource "aws_secretsmanager_secret_version" "app" {
  secret_id = aws_secretsmanager_secret.app.id
  secret_string = jsonencode({
    db_app_password              = random_password.app["db_app"].result
    db_migrator_password         = random_password.app["db_migrator"].result
    db_backup_password           = random_password.app["db_backup"].result
    db_keycloak_password         = random_password.app["db_keycloak"].result
    keycloak_admin_password      = random_password.app["keycloak_admin"].result
    keycloak_admin_client_secret = random_password.app["keycloak_admin_client"].result
    provisioning_client_secret   = random_password.app["provisioning_client"].result
    redis_auth_token             = random_password.cache_auth.result
  })
}

resource "aws_secretsmanager_secret" "razorpay" {
  name        = "occpl/${var.env}/razorpay"
  description = "Razorpay key id/secret and webhook secret; filled in by an operator (empty = billing disabled)"
}

resource "aws_secretsmanager_secret_version" "razorpay" {
  secret_id     = aws_secretsmanager_secret.razorpay.id
  secret_string = jsonencode({ key_id = "", key_secret = "", webhook_secret = "" })
  lifecycle {
    ignore_changes = [secret_string] # operator-managed after creation
  }
}

# Non-secret runtime configuration for the instances.
resource "aws_ssm_parameter" "config" {
  name = "${local.ssm_prefix}/config"
  type = "String"
  value = jsonencode({
    env                           = var.env
    region                        = data.aws_region.current.region
    registry                      = var.ecr_registry
    admin_host                    = local.hosts.admin
    api_host                      = local.hosts.api
    auth_host                     = local.hosts.auth
    db_host                       = aws_db_instance.main.address
    db_port                       = aws_db_instance.main.port
    db_master_secret_arn          = aws_db_instance.main.master_user_secret[0].secret_arn
    redis_host                    = aws_elasticache_replication_group.main.primary_endpoint_address
    app_secret_arn                = aws_secretsmanager_secret.app.arn
    razorpay_secret_arn           = aws_secretsmanager_secret.razorpay.arn
    ses_smtp_secret_arn           = var.ses_smtp_secret_arn
    mail_from                     = "OCCPL Innovations <no-reply@${var.domain}>"
    log_group_prefix              = "/occpl/${var.env}"
    bootstrap_super_admin_email   = var.bootstrap_super_admin_email
    provisioning_alert_recipients = var.provisioning_alert_recipients
  })
}

# The release (git SHA tag of all three images) the environment runs; written by the deploy
# pipeline. New instances (Auto Scaling replacement) start this release on boot.
resource "aws_ssm_parameter" "release" {
  name  = "${local.ssm_prefix}/release"
  type  = "String"
  value = "none"
  lifecycle {
    ignore_changes = [value]
  }
}
