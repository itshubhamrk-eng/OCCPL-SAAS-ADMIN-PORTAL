# Account-wide resources shared by staging and production (ADR-0011):
# container registry, release artifacts bucket, GitHub Actions OIDC trust and build role,
# SES domain identity for occplsoftech.com with its DNS records and SMTP credentials.

terraform {
  required_version = ">= 1.16.0"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.66" }
  }
  backend "s3" {
    bucket       = "occplsoftech-terraform-state"
    key          = "shared/terraform.tfstate"
    region       = "ap-south-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = { Project = "occpl-platform", ManagedBy = "terraform", Stack = "shared" }
  }
}

data "aws_caller_identity" "current" {}

data "aws_route53_zone" "main" {
  name         = var.domain
  private_zone = false
}

# ---------------------------------------------------------------- container registry

resource "aws_ecr_repository" "images" {
  for_each             = toset(var.images)
  name                 = "occpl/${each.key}"
  image_tag_mutability = "IMMUTABLE" # a release tag (git SHA) always means the same image
  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "KMS"
  }
}

resource "aws_ecr_lifecycle_policy" "images" {
  for_each   = aws_ecr_repository.images
  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 100 release images (rollback targets)"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 100 }
      action       = { type = "expire" }
    }]
  })
}

# ---------------------------------------------------------------- release artifacts (compose bundle)

resource "aws_s3_bucket" "artifacts" {
  bucket = "${var.name_prefix}-artifacts-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket_versioning" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "artifacts" {
  bucket                  = aws_s3_bucket.artifacts.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id
  rule {
    id     = "expire-old-bundles"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 180
    }
  }
}

# ---------------------------------------------------------------- GitHub Actions (OIDC, no stored keys)

resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

# Builds and publishes images from the main branch only.
resource "aws_iam_role" "github_build" {
  name = "${var.name_prefix}-github-build"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          "token.actions.githubusercontent.com:sub" = "repo:${var.github_repository}:ref:refs/heads/main"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "github_build" {
  name = "publish-images"
  role = aws_iam_role.github_build.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
      {
        Effect = "Allow"
        Action = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:CompleteLayerUpload",
          "ecr:DescribeImages", "ecr:DescribeImageScanFindings", "ecr:GetDownloadUrlForLayer",
        "ecr:InitiateLayerUpload", "ecr:PutImage", "ecr:UploadLayerPart"]
        Resource = [for r in aws_ecr_repository.images : r.arn]
      },
    ]
  })
}

# ---------------------------------------------------------------- email (SES, ap-south-1)

resource "aws_sesv2_email_identity" "domain" {
  email_identity = var.domain
  dkim_signing_attributes {
    next_signing_key_length = "RSA_2048_BIT"
  }
}

resource "aws_route53_record" "ses_dkim" {
  count   = 3
  zone_id = data.aws_route53_zone.main.zone_id
  name    = "${aws_sesv2_email_identity.domain.dkim_signing_attributes[0].tokens[count.index]}._domainkey.${var.domain}"
  type    = "CNAME"
  ttl     = 1800
  records = ["${aws_sesv2_email_identity.domain.dkim_signing_attributes[0].tokens[count.index]}.dkim.amazonses.com"]
}

# Custom MAIL FROM domain so SPF aligns with occplsoftech.com (DMARC).
resource "aws_sesv2_email_identity_mail_from_attributes" "domain" {
  email_identity         = aws_sesv2_email_identity.domain.email_identity
  mail_from_domain       = "mail.${var.domain}"
  behavior_on_mx_failure = "USE_DEFAULT_VALUE"
}

resource "aws_route53_record" "mail_from_mx" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = "mail.${var.domain}"
  type    = "MX"
  ttl     = 1800
  records = ["10 feedback-smtp.${var.region}.amazonses.com"]
}

resource "aws_route53_record" "mail_from_spf" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = "mail.${var.domain}"
  type    = "TXT"
  ttl     = 1800
  records = ["v=spf1 include:amazonses.com -all"]
}

resource "aws_route53_record" "dmarc" {
  count   = var.manage_dmarc_record ? 1 : 0
  zone_id = data.aws_route53_zone.main.zone_id
  name    = "_dmarc.${var.domain}"
  type    = "TXT"
  ttl     = 1800
  records = ["v=DMARC1; p=quarantine; rua=mailto:${var.dmarc_report_email}; adkim=s; aspf=r"]
}

# SMTP credentials for Core and Keycloak (SES SMTP interface). Sending only, from the domain.
resource "aws_iam_user" "ses_smtp" {
  name = "${var.name_prefix}-ses-smtp"
}

resource "aws_iam_user_policy" "ses_smtp" {
  name = "send-from-domain"
  user = aws_iam_user.ses_smtp.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = ["ses:SendRawEmail"]
      Resource  = "*"
      Condition = { StringLike = { "ses:FromAddress" = "*@${var.domain}" } }
    }]
  })
}

resource "aws_iam_access_key" "ses_smtp" {
  user = aws_iam_user.ses_smtp.name
}

resource "aws_secretsmanager_secret" "ses_smtp" {
  name        = "occpl/shared/ses-smtp"
  description = "SES SMTP credentials (host, port, username, password) used by Core and Keycloak"
}

resource "aws_secretsmanager_secret_version" "ses_smtp" {
  secret_id = aws_secretsmanager_secret.ses_smtp.id
  secret_string = jsonencode({
    host     = "email-smtp.${var.region}.amazonaws.com"
    port     = "587"
    username = aws_iam_access_key.ses_smtp.id
    password = aws_iam_access_key.ses_smtp.ses_smtp_password_v4
  })
}
