resource "aws_s3_bucket" "alb_logs" {
  bucket        = "${local.name}-alb-logs-${data.aws_caller_identity.current.account_id}"
  force_destroy = var.env != "production"
}

resource "aws_s3_bucket_server_side_encryption_configuration" "alb_logs" {
  bucket = aws_s3_bucket.alb_logs.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256" # required by ALB access logging
    }
  }
}

resource "aws_s3_bucket_public_access_block" "alb_logs" {
  bucket                  = aws_s3_bucket.alb_logs.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "alb_logs" {
  bucket = aws_s3_bucket.alb_logs.id
  rule {
    id     = "expire"
    status = "Enabled"
    filter {}
    expiration {
      days = var.log_retention_days
    }
  }
}

resource "aws_s3_bucket_policy" "alb_logs" {
  bucket = aws_s3_bucket.alb_logs.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "logdelivery.elasticloadbalancing.amazonaws.com" }
      Action    = "s3:PutObject"
      Resource  = "${aws_s3_bucket.alb_logs.arn}/AWSLogs/${data.aws_caller_identity.current.account_id}/*"
    }]
  })
}

resource "aws_lb" "main" {
  name                       = local.name
  load_balancer_type         = "application"
  internal                   = false
  subnets                    = aws_subnet.public[*].id
  security_groups            = [aws_security_group.alb.id]
  drop_invalid_header_fields = true
  enable_deletion_protection = var.env == "production"
  idle_timeout               = 60
  access_logs {
    bucket  = aws_s3_bucket.alb_logs.bucket
    enabled = true
  }
  depends_on = [aws_s3_bucket_policy.alb_logs]
}

resource "aws_lb_target_group" "core" {
  name                 = "${local.name}-core"
  port                 = local.ports.core
  protocol             = "HTTP"
  vpc_id               = aws_vpc.main.id
  deregistration_delay = 30
  health_check {
    path                = "/actuator/health/readiness"
    port                = tostring(local.ports.core_mgmt)
    matcher             = "200"
    interval            = 15
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_target_group" "portal" {
  name                 = "${local.name}-portal"
  port                 = local.ports.portal
  protocol             = "HTTP"
  vpc_id               = aws_vpc.main.id
  deregistration_delay = 15
  health_check {
    path    = "/healthz"
    matcher = "200"
  }
}

resource "aws_lb_target_group" "keycloak" {
  name                 = "${local.name}-keycloak"
  port                 = local.ports.keycloak
  protocol             = "HTTP"
  vpc_id               = aws_vpc.main.id
  deregistration_delay = 30
  health_check {
    path     = "/health/ready"
    port     = tostring(local.ports.keycloak_mgmt)
    matcher  = "200"
    interval = 15
  }
  # Login flows keep their state in the cluster cache; stickiness only reduces cross-node lookups.
  stickiness {
    type            = "lb_cookie"
    cookie_duration = 3600
    enabled         = true
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate_validation.main.certificate_arn
  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Not found"
      status_code  = "404"
    }
  }
}

# Keycloak admin console and admin REST API only from the operations networks. Core reaches the
# admin API from inside the VPC (KEYCLOAK_BASE_URL points at the instances, not the ALB).
resource "aws_lb_listener_rule" "keycloak_admin_allowed" {
  count        = length(var.admin_cidrs) > 0 ? 1 : 0
  listener_arn = aws_lb_listener.https.arn
  priority     = 5
  condition {
    host_header {
      values = [local.hosts.auth]
    }
  }
  condition {
    path_pattern {
      values = ["/admin", "/admin/*"]
    }
  }
  condition {
    source_ip {
      values = var.admin_cidrs
    }
  }
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.keycloak.arn
  }
}

resource "aws_lb_listener_rule" "keycloak_admin_denied" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 6
  condition {
    host_header {
      values = [local.hosts.auth]
    }
  }
  condition {
    path_pattern {
      values = ["/admin", "/admin/*"]
    }
  }
  action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Forbidden"
      status_code  = "403"
    }
  }
}

resource "aws_lb_listener_rule" "hosts" {
  for_each = {
    admin = { priority = 10, tg = aws_lb_target_group.portal.arn }
    api   = { priority = 20, tg = aws_lb_target_group.core.arn }
    auth  = { priority = 30, tg = aws_lb_target_group.keycloak.arn }
  }
  listener_arn = aws_lb_listener.https.arn
  priority     = each.value.priority
  condition {
    host_header {
      values = [local.hosts[each.key]]
    }
  }
  action {
    type             = "forward"
    target_group_arn = each.value.tg
  }
}
