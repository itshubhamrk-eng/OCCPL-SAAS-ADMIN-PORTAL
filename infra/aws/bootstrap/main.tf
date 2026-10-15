# One-time bootstrap: the S3 bucket that holds Terraform state for the other roots (S3-native
# state locking, no DynamoDB table). Applied once with local state by an account administrator:
#   terraform -chdir=infra/aws/bootstrap init && terraform -chdir=infra/aws/bootstrap apply
# Terraform state contains generated secrets: access to this bucket is administrators only.

terraform {
  required_version = ">= 1.16.0"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.66" }
  }
}

variable "region" {
  type    = string
  default = "ap-south-1"
}

variable "state_bucket" {
  description = "Globally unique bucket name, e.g. occplsoftech-terraform-state"
  type        = string
  default     = "occplsoftech-terraform-state"
}

provider "aws" {
  region = var.region
  default_tags {
    tags = { Project = "occpl-platform", ManagedBy = "terraform", Stack = "bootstrap" }
  }
}

resource "aws_s3_bucket" "state" {
  bucket = var.state_bucket
  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_policy" "state_tls_only" {
  bucket = aws_s3_bucket.state.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.state.arn, "${aws_s3_bucket.state.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
}

output "state_bucket" {
  value = aws_s3_bucket.state.bucket
}
