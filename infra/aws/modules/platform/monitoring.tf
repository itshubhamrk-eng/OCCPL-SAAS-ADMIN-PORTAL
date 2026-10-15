# Alerts of DEPLOYMENT.md §6 as CloudWatch alarms, notified by email through SNS.
# Application metrics come from Core's /actuator/prometheus, scraped by the CloudWatch agent
# (compose/cloudwatch/*) into the OCCPL/<env> namespace.

resource "aws_sns_topic" "alarms" {
  name = "${local.name}-alarms"
}

resource "aws_sns_topic_subscription" "alarm_email" {
  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

resource "aws_cloudwatch_log_group" "services" {
  for_each          = toset(["core-platform", "admin-portal", "keycloak", "deploy", "cloudwatch-agent"])
  name              = "/occpl/${var.env}/${each.key}"
  retention_in_days = var.log_retention_days
}

locals {
  app_namespace = "OCCPL/${var.env}"
  alarm_actions = [aws_sns_topic.alarms.arn]
  target_groups = {
    core     = aws_lb_target_group.core.arn_suffix
    portal   = aws_lb_target_group.portal.arn_suffix
    keycloak = aws_lb_target_group.keycloak.arn_suffix
  }
}

resource "aws_cloudwatch_metric_alarm" "api_5xx_rate" {
  alarm_name          = "${local.name}-api-5xx-rate"
  alarm_description   = "More than 1% of API requests failed with 5xx for 5 minutes"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 1
  evaluation_periods  = 5
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
  metric_query {
    id          = "rate"
    expression  = "100 * errors / MAX([requests, 1])"
    label       = "5xx %"
    return_data = true
  }
  metric_query {
    id = "errors"
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "HTTPCode_Target_5XX_Count"
      period      = 60
      stat        = "Sum"
      dimensions  = { LoadBalancer = aws_lb.main.arn_suffix, TargetGroup = local.target_groups.core }
    }
  }
  metric_query {
    id = "requests"
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "RequestCount"
      period      = 60
      stat        = "Sum"
      dimensions  = { LoadBalancer = aws_lb.main.arn_suffix, TargetGroup = local.target_groups.core }
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "api_latency_p95" {
  alarm_name          = "${local.name}-api-latency-p95"
  alarm_description   = "API p95 latency above 300 ms for 10 minutes (provisional SLO, D-6)"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "TargetResponseTime"
  extended_statistic  = "p95"
  dimensions          = { LoadBalancer = aws_lb.main.arn_suffix, TargetGroup = local.target_groups.core }
  period              = 60
  evaluation_periods  = 10
  threshold           = 0.3
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "unhealthy_targets" {
  for_each            = local.target_groups
  alarm_name          = "${local.name}-${each.key}-unhealthy"
  alarm_description   = "A ${each.key} target is failing its health check"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "UnHealthyHostCount"
  dimensions          = { LoadBalancer = aws_lb.main.arn_suffix, TargetGroup = each.value }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 5
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "no_healthy_targets" {
  for_each            = local.target_groups
  alarm_name          = "${local.name}-${each.key}-down"
  alarm_description   = "No healthy ${each.key} target: the service is unavailable"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HealthyHostCount"
  dimensions          = { LoadBalancer = aws_lb.main.arn_suffix, TargetGroup = each.value }
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "instances_missing" {
  alarm_name          = "${local.name}-instances-missing"
  alarm_description   = "Fewer instances in service than desired for 15 minutes"
  namespace           = "AWS/AutoScaling"
  metric_name         = "GroupInServiceInstances"
  dimensions          = { AutoScalingGroupName = aws_autoscaling_group.app.name }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 3
  threshold           = var.instance_count
  comparison_operator = "LessThanThreshold"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "disk" {
  alarm_name          = "${local.name}-disk-80"
  alarm_description   = "Root disk of an app instance above 80%"
  namespace           = "CWAgent"
  metric_name         = "disk_used_percent"
  dimensions          = { AutoScalingGroupName = aws_autoscaling_group.app.name, path = "/" }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 2
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "missing"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "db_cpu" {
  alarm_name          = "${local.name}-db-cpu"
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "db_storage" {
  alarm_name          = "${local.name}-db-free-storage"
  alarm_description   = "Less than 10 GiB free (storage autoscaling caps at the configured maximum)"
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 10 * 1024 * 1024 * 1024
  comparison_operator = "LessThanThreshold"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "db_memory" {
  alarm_name          = "${local.name}-db-freeable-memory"
  namespace           = "AWS/RDS"
  metric_name         = "FreeableMemory"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 3
  threshold           = 256 * 1024 * 1024
  comparison_operator = "LessThanThreshold"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "cache_cpu" {
  alarm_name          = "${local.name}-cache-cpu"
  namespace           = "AWS/ElastiCache"
  metric_name         = "EngineCPUUtilization"
  dimensions          = { ReplicationGroupId = aws_elasticache_replication_group.main.id }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "certificate_expiry" {
  alarm_name          = "${local.name}-certificate-expiry"
  alarm_description   = "TLS certificate expires within 14 days (ACM renewal failed?)"
  namespace           = "AWS/CertificateManager"
  metric_name         = "DaysToExpiry"
  dimensions          = { CertificateArn = aws_acm_certificate.main.arn }
  statistic           = "Minimum"
  period              = 86400
  evaluation_periods  = 1
  threshold           = 14
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

# ---- application metrics (CloudWatch agent → OCCPL/<env>)

resource "aws_cloudwatch_metric_alarm" "db_pool_pending" {
  alarm_name          = "${local.name}-db-pool-pending"
  alarm_description   = "Requests waiting for a database connection for 5 minutes (overload; 503 SERVICE_BUSY follows)"
  namespace           = local.app_namespace
  metric_name         = "hikaricp_connections_pending"
  dimensions          = { Environment = var.env, pool = "core-hikari" }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 5
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "provisioning_failed" {
  alarm_name          = "${local.name}-provisioning-failed"
  alarm_description   = "A provisioning job failed permanently (also emailed to PROVISIONING_ALERT_RECIPIENTS)"
  namespace           = local.app_namespace
  metric_name         = "occpl_provisioning_jobs_total"
  dimensions          = { Environment = var.env, outcome = "failed" }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "webhook_rejections" {
  alarm_name          = "${local.name}-webhook-rejections"
  alarm_description   = "More than 10 payment webhooks with invalid signatures in 5 minutes"
  namespace           = local.app_namespace
  metric_name         = "occpl_billing_webhooks_total"
  dimensions          = { Environment = var.env, outcome = "rejected" }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 10
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

# The rate limiter fails open when Redis is unreachable (requests still served, limits off).
resource "aws_cloudwatch_log_metric_filter" "rate_limiter_down" {
  name           = "${local.name}-rate-limiter-down"
  log_group_name = aws_cloudwatch_log_group.services["core-platform"].name
  pattern        = "\"Rate limiter cannot connect to Redis\""
  metric_transformation {
    name          = "rate_limiter_redis_unavailable"
    namespace     = local.app_namespace
    value         = "1"
    default_value = "0"
  }
}

resource "aws_cloudwatch_metric_alarm" "rate_limiter_down" {
  alarm_name          = "${local.name}-rate-limiter-down"
  alarm_description   = "Core cannot reach ElastiCache: rate limiting is off (fail-open)"
  namespace           = local.app_namespace
  metric_name         = "rate_limiter_redis_unavailable"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}
