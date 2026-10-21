variable "region" {
  type    = string
  default = "ap-south-1"
}

variable "name_prefix" {
  type    = string
  default = "occpl"
}

variable "domain" {
  description = "Public domain; its Route 53 hosted zone must exist in this account (D-12)"
  type        = string
  default     = "occplsoftech.com"
}

variable "images" {
  description = "Container images published by CI"
  type        = list(string)
  default     = ["core-platform", "admin-portal", "keycloak"]
}

variable "github_repository" {
  description = "owner/name of the GitHub repository allowed to publish and deploy"
  type        = string
  default     = "occplsoftech/OCCPL-SAAS-ADMIN-PORTAL"
}

variable "manage_dmarc_record" {
  description = "Create the _dmarc record (leave false while the domain already publishes one, e.g. from the mail host)"
  type        = bool
  default     = false
}

variable "dmarc_report_email" {
  type    = string
  default = "dmarc@occplsoftech.com"
}

variable "backup_retention_days" {
  description = "How long the off-host database backups of the VPS are kept"
  type        = number
  default     = 35
}
