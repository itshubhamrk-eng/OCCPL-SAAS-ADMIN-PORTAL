output "ecr_registry" {
  value = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${var.region}.amazonaws.com"
}

output "ecr_repository_arns" {
  value = { for k, r in aws_ecr_repository.images : k => r.arn }
}

output "artifacts_bucket" {
  value = aws_s3_bucket.artifacts.bucket
}

output "github_oidc_provider_arn" {
  value = aws_iam_openid_connect_provider.github.arn
}

output "github_build_role_arn" {
  description = "Set as repository variable AWS_BUILD_ROLE_ARN"
  value       = aws_iam_role.github_build.arn
}

output "ses_smtp_secret_arn" {
  value = aws_secretsmanager_secret.ses_smtp.arn
}

output "hosted_zone_id" {
  value = data.aws_route53_zone.main.zone_id
}
