# staging environment (ADR-0011). Apply after infra/aws/shared:
#   terraform -chdir=infra/aws/envs/staging init && terraform -chdir=infra/aws/envs/staging apply

terraform {
  required_version = ">= 1.16.0"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 6.66" }
    random = { source = "hashicorp/random", version = "~> 3.8" }
  }
  backend "s3" {
    bucket       = "occplsoftech-terraform-state"
    key          = "staging/terraform.tfstate"
    region       = "ap-south-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = "ap-south-1"
  default_tags {
    tags = { Project = "occpl-platform", ManagedBy = "terraform", Environment = "staging" }
  }
}

# Disaster-recovery region for backup copies.
provider "aws" {
  alias  = "dr"
  region = "ap-south-2"
  default_tags {
    tags = { Project = "occpl-platform", ManagedBy = "terraform", Environment = "staging" }
  }
}

data "terraform_remote_state" "shared" {
  backend = "s3"
  config = {
    bucket = "occplsoftech-terraform-state"
    key    = "shared/terraform.tfstate"
    region = "ap-south-1"
  }
}

variable "alarm_email" {
  description = "Receives alarm notifications"
  type        = string
}

variable "admin_cidrs" {
  description = "Networks allowed to open the Keycloak admin console"
  type        = list(string)
  default     = []
}

variable "bootstrap_super_admin_email" {
  type    = string
  default = ""
}

variable "provisioning_alert_recipients" {
  type    = string
  default = ""
}

module "platform" {
  source = "../../modules/platform"
  providers = {
    aws    = aws
    aws.dr = aws.dr
  }

  env            = "staging"
  host_suffix    = "staging."
  hosted_zone_id = data.terraform_remote_state.shared.outputs.hosted_zone_id
  vpc_cidr       = "10.30.0.0/16"

  # Same topology, smaller and single-AZ where it only costs availability.
  instance_type            = "t3.large"
  instance_count           = 1
  nat_gateway_count        = 1
  db_instance_class        = "db.t4g.medium"
  db_multi_az              = false
  db_backup_retention_days = 7
  db_deletion_protection   = false
  cache_node_type          = "cache.t4g.micro"
  cache_nodes              = 1
  log_retention_days       = 30
  cross_region_backup      = false

  alarm_email                   = var.alarm_email
  admin_cidrs                   = var.admin_cidrs
  bootstrap_super_admin_email   = var.bootstrap_super_admin_email
  provisioning_alert_recipients = var.provisioning_alert_recipients

  ecr_registry             = data.terraform_remote_state.shared.outputs.ecr_registry
  artifacts_bucket         = data.terraform_remote_state.shared.outputs.artifacts_bucket
  ses_smtp_secret_arn      = data.terraform_remote_state.shared.outputs.ses_smtp_secret_arn
  github_oidc_provider_arn = data.terraform_remote_state.shared.outputs.github_oidc_provider_arn
}

output "hosts" {
  value = module.platform.hosts
}

output "asg_name" {
  value = module.platform.asg_name
}

output "target_group_arns" {
  value = module.platform.target_group_arns
}

output "deploy_role_arn" {
  value = module.platform.deploy_role_arn
}

output "razorpay_secret_name" {
  value = module.platform.razorpay_secret_name
}

output "github_environment_variables" {
  description = "Variables of the GitHub environment used by .github/workflows/deploy.yml"
  value = {
    AWS_DEPLOY_ROLE_ARN = module.platform.deploy_role_arn
    ASG_NAME            = module.platform.asg_name
    TARGET_GROUP_ARNS   = join(" ", values(module.platform.target_group_arns))
  }
}
