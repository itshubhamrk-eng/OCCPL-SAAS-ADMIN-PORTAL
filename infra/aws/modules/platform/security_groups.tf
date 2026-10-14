resource "aws_security_group" "alb" {
  name        = "${local.name}-alb"
  description = "Public HTTPS entry"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${local.name}-alb" }
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  security_group_id = aws_security_group.alb.id
  description       = "Redirected to HTTPS"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

resource "aws_vpc_security_group_egress_rule" "alb_to_app" {
  for_each                     = { for k in ["core", "core_mgmt", "portal", "keycloak", "keycloak_mgmt"] : k => local.ports[k] }
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = each.value
  to_port                      = each.value
}

resource "aws_security_group" "app" {
  name        = "${local.name}-app"
  description = "App instances: traffic from the load balancer only, no SSH (SSM)"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${local.name}-app" }
}

resource "aws_vpc_security_group_ingress_rule" "app_from_alb" {
  for_each                     = { for k in ["core", "core_mgmt", "portal", "keycloak", "keycloak_mgmt"] : k => local.ports[k] }
  security_group_id            = aws_security_group.app.id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = each.value
  to_port                      = each.value
}

# Keycloak cluster traffic (Infinispan over JGroups, discovery through the database).
resource "aws_vpc_security_group_ingress_rule" "app_jgroups" {
  security_group_id            = aws_security_group.app.id
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = local.ports.jgroups
  to_port                      = local.ports.jgroups
}

resource "aws_vpc_security_group_ingress_rule" "app_jgroups_fd" {
  description                  = "JGroups failure detection (port + 50000)"
  security_group_id            = aws_security_group.app.id
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = local.ports.jgroups + 50000
  to_port                      = local.ports.jgroups + 50000
}

resource "aws_vpc_security_group_egress_rule" "app_all" {
  security_group_id = aws_security_group.app.id
  description       = "HTTPS to AWS APIs, OS updates, Razorpay and product services; DB/cache in VPC"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_security_group" "db" {
  name        = "${local.name}-db"
  description = "MySQL from app instances only"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${local.name}-db" }
}

resource "aws_vpc_security_group_ingress_rule" "db_from_app" {
  security_group_id            = aws_security_group.db.id
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = 3306
  to_port                      = 3306
}

resource "aws_security_group" "cache" {
  name        = "${local.name}-cache"
  description = "Valkey/Redis from app instances only"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${local.name}-cache" }
}

resource "aws_vpc_security_group_ingress_rule" "cache_from_app" {
  security_group_id            = aws_security_group.cache.id
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
}
