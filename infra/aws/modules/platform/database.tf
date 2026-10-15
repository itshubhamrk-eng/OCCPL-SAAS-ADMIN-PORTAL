# Amazon RDS for MySQL 8.4 LTS (D-14). Holds occpl_core and keycloak (separate schemas and accounts,
# created by compose/bin/bootstrap-db.sh). The master password is generated and rotated by RDS in
# Secrets Manager; applications use their own least-privilege accounts.

resource "aws_db_subnet_group" "main" {
  name       = local.name
  subnet_ids = aws_subnet.data[*].id
}

resource "aws_db_parameter_group" "mysql84" {
  name   = "${local.name}-mysql84"
  family = "mysql8.4"

  parameter {
    name  = "character_set_server"
    value = "utf8mb4"
  }
  parameter {
    name  = "collation_server"
    value = "utf8mb4_0900_ai_ci"
  }
  parameter {
    name  = "time_zone"
    value = "UTC"
  }
  parameter {
    name  = "require_secure_transport"
    value = "1"
  }
  parameter {
    # Lets the migrator account create the audit-log triggers with binary logging on (no SUPER on RDS).
    name  = "log_bin_trust_function_creators"
    value = "1"
  }
  parameter {
    name  = "slow_query_log"
    value = "1"
  }
  parameter {
    name  = "long_query_time"
    value = "0.5"
  }
  parameter {
    # Online schema changes for large tables (expand/contract migrations).
    name  = "innodb_online_alter_log_max_size"
    value = "1073741824"
  }
}

resource "aws_db_instance" "main" {
  identifier                            = local.name
  engine                                = "mysql"
  engine_version                        = "8.4"
  auto_minor_version_upgrade            = true
  instance_class                        = var.db_instance_class
  allocated_storage                     = var.db_allocated_storage_gb
  max_allocated_storage                 = var.db_max_allocated_storage_gb
  storage_type                          = "gp3"
  storage_encrypted                     = true
  multi_az                              = var.db_multi_az
  db_subnet_group_name                  = aws_db_subnet_group.main.name
  vpc_security_group_ids                = [aws_security_group.db.id]
  parameter_group_name                  = aws_db_parameter_group.mysql84.name
  username                              = "occpl_admin"
  manage_master_user_password           = true
  backup_retention_period               = var.db_backup_retention_days
  backup_window                         = "19:30-20:30" # 01:00-02:00 IST
  maintenance_window                    = "sun:21:00-sun:22:00"
  copy_tags_to_snapshot                 = true
  deletion_protection                   = var.db_deletion_protection
  skip_final_snapshot                   = false
  final_snapshot_identifier             = "${local.name}-final"
  performance_insights_enabled          = true
  performance_insights_retention_period = 7
  enabled_cloudwatch_logs_exports       = ["error", "slowquery"]
  publicly_accessible                   = false
  apply_immediately                     = false
}
