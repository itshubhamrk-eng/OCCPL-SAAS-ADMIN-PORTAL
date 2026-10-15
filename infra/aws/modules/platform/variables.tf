variable "env" {
  description = "staging or production"
  type        = string
  validation {
    condition     = contains(["staging", "production"], var.env)
    error_message = "env must be staging or production."
  }
}

variable "domain" {
  type    = string
  default = "occplsoftech.com"
}

variable "host_suffix" {
  description = "Appended to admin./api./auth. (\"\" for production; e.g. \"staging.\" if a second environment is ever added)"
  type        = string
}

variable "hosted_zone_id" {
  type = string
}

variable "vpc_cidr" {
  type = string
}

variable "nat_gateway_count" {
  description = "1 (cheaper, single-AZ egress) or 2 (one per AZ)"
  type        = number
  default     = 2
}

variable "instance_type" {
  type    = string
  default = "m7i.large"
}

variable "instance_count" {
  description = "App instances (spread over the two AZs)"
  type        = number
  default     = 2
}

variable "root_volume_gb" {
  type    = number
  default = 40
}

variable "db_instance_class" {
  type    = string
  default = "db.m7g.large"
}

variable "db_allocated_storage_gb" {
  type    = number
  default = 100
}

variable "db_max_allocated_storage_gb" {
  type    = number
  default = 500
}

variable "db_multi_az" {
  type    = bool
  default = true
}

variable "db_backup_retention_days" {
  type    = number
  default = 35
}

variable "db_deletion_protection" {
  type    = bool
  default = true
}

variable "cache_node_type" {
  type    = string
  default = "cache.t4g.small"
}

variable "cache_nodes" {
  description = "1 = no replica; 2+ = primary with replicas and automatic failover"
  type        = number
  default     = 2
}

variable "admin_cidrs" {
  description = "Networks allowed to open the Keycloak admin console (auth./admin)"
  type        = list(string)
  default     = []
}

variable "waf_rate_limit_per_5min" {
  description = "Requests per client IP per 5 minutes before WAF blocks (coarse; Core has finer limits)"
  type        = number
  default     = 3000
}

variable "alarm_email" {
  description = "Receives CloudWatch alarm notifications (confirm the SNS subscription email)"
  type        = string
}

variable "log_retention_days" {
  type    = number
  default = 90
}

variable "cross_region_backup" {
  description = "Copy RDS backups to the DR region (aws.dr provider) daily"
  type        = bool
  default     = true
}

variable "ecr_registry" {
  type = string
}

variable "artifacts_bucket" {
  type = string
}

variable "ses_smtp_secret_arn" {
  type = string
}

variable "github_oidc_provider_arn" {
  type = string
}

variable "github_repository" {
  type    = string
  default = "itshubhamrk-eng/OCCPL-SAAS-ADMIN-PORTAL"
}

variable "bootstrap_super_admin_email" {
  description = "Verified email that becomes the first SUPER_ADMIN on first login; clear after use"
  type        = string
  default     = ""
}

variable "provisioning_alert_recipients" {
  type    = string
  default = ""
}
