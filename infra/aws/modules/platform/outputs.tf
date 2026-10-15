output "hosts" {
  value = local.hosts
}

output "asg_name" {
  value = aws_autoscaling_group.app.name
}

output "target_group_arns" {
  value = {
    core     = aws_lb_target_group.core.arn
    portal   = aws_lb_target_group.portal.arn
    keycloak = aws_lb_target_group.keycloak.arn
  }
}

output "deploy_role_arn" {
  description = "Set as GitHub environment variable AWS_DEPLOY_ROLE_ARN"
  value       = aws_iam_role.deploy.arn
}

output "db_endpoint" {
  value = aws_db_instance.main.address
}

output "db_master_secret_arn" {
  value = aws_db_instance.main.master_user_secret[0].secret_arn
}

output "razorpay_secret_name" {
  value = aws_secretsmanager_secret.razorpay.name
}

output "alarm_topic_arn" {
  value = aws_sns_topic.alarms.arn
}
