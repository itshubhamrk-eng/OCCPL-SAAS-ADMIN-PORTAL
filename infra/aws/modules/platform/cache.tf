# ElastiCache (Valkey engine, Redis-protocol compatible) for Core's rate limiter.
resource "aws_elasticache_subnet_group" "main" {
  name       = local.name
  subnet_ids = aws_subnet.data[*].id
}

resource "random_password" "cache_auth" {
  length  = 48
  special = false
}

resource "aws_elasticache_replication_group" "main" {
  replication_group_id       = local.name
  description                = "Core rate limiting"
  engine                     = "valkey"
  engine_version             = "8.0"
  node_type                  = var.cache_node_type
  num_cache_clusters         = var.cache_nodes
  automatic_failover_enabled = var.cache_nodes > 1
  multi_az_enabled           = var.cache_nodes > 1
  port                       = 6379
  subnet_group_name          = aws_elasticache_subnet_group.main.name
  security_group_ids         = [aws_security_group.cache.id]
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  auth_token                 = random_password.cache_auth.result
  snapshot_retention_limit   = 0 # rate-limit buckets are disposable
  apply_immediately          = false
}
