# RDS keeps automated backups with point-in-time recovery (db_backup_retention_days). AWS Backup
# adds a daily copy to the disaster-recovery region so a regional outage cannot take the backups too.

resource "aws_backup_vault" "primary" {
  count = var.cross_region_backup ? 1 : 0
  name  = local.name
}

resource "aws_backup_vault" "dr" {
  count    = var.cross_region_backup ? 1 : 0
  provider = aws.dr
  name     = "${local.name}-dr"
}

resource "aws_backup_plan" "db" {
  count = var.cross_region_backup ? 1 : 0
  name  = "${local.name}-db"
  rule {
    rule_name         = "daily-with-dr-copy"
    target_vault_name = aws_backup_vault.primary[0].name
    schedule          = "cron(30 20 * * ? *)" # 02:00 IST
    lifecycle {
      delete_after = var.db_backup_retention_days
    }
    copy_action {
      destination_vault_arn = aws_backup_vault.dr[0].arn
      lifecycle {
        delete_after = var.db_backup_retention_days
      }
    }
  }
}

resource "aws_iam_role" "backup" {
  count = var.cross_region_backup ? 1 : 0
  name  = "${local.name}-backup"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "backup.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy_attachment" "backup" {
  count      = var.cross_region_backup ? 1 : 0
  role       = aws_iam_role.backup[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSBackupServiceRolePolicyForBackup"
}

resource "aws_backup_selection" "db" {
  count        = var.cross_region_backup ? 1 : 0
  name         = "${local.name}-db"
  plan_id      = aws_backup_plan.db[0].id
  iam_role_arn = aws_iam_role.backup[0].arn
  resources    = [aws_db_instance.main.arn]
}

# Failed backup or copy jobs → the alarm topic.
resource "aws_cloudwatch_event_rule" "backup_failed" {
  count = var.cross_region_backup ? 1 : 0
  name  = "${local.name}-backup-failed"
  event_pattern = jsonencode({
    source        = ["aws.backup"]
    "detail-type" = ["Backup Job State Change", "Copy Job State Change"]
    detail        = { state = ["FAILED", "ABORTED", "EXPIRED"] }
  })
}

resource "aws_cloudwatch_event_target" "backup_failed" {
  count = var.cross_region_backup ? 1 : 0
  rule  = aws_cloudwatch_event_rule.backup_failed[0].name
  arn   = aws_sns_topic.alarms.arn
}

resource "aws_sns_topic_policy" "alarms" {
  arn = aws_sns_topic.alarms.arn
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "CloudWatchAlarms"
        Effect    = "Allow"
        Principal = { Service = "cloudwatch.amazonaws.com" }
        Action    = "sns:Publish"
        Resource  = aws_sns_topic.alarms.arn
      },
      {
        Sid       = "EventBridge"
        Effect    = "Allow"
        Principal = { Service = "events.amazonaws.com" }
        Action    = "sns:Publish"
        Resource  = aws_sns_topic.alarms.arn
      },
    ]
  })
}
