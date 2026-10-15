# Development/test server on one EC2 instance (ADR-0012 amendment, D-17): the same Docker Compose
# stack as the production VPS (infra/vps), reachable at admin./api./auth.dev.occplsoftech.com.
# Default VPC, no NAT gateway or load balancer: about $85 a month while running; stop the
# instance when it is not needed (compute stops billing; disk and public address still do).

terraform {
  required_version = ">= 1.16.0"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.66" }
  }
  backend "s3" {
    bucket       = "occplsoftech-terraform-state"
    key          = "dev/terraform.tfstate"
    region       = "ap-south-1"
    encrypt      = true
    use_lockfile = true
  }
}

variable "region" {
  type    = string
  default = "ap-south-1"
}

variable "name_prefix" {
  type    = string
  default = "occpl"
}

variable "domain" {
  type    = string
  default = "occplsoftech.com"
}

variable "host_suffix" {
  description = "Hosts become admin.<suffix>.<domain>, api.<suffix>.<domain>, auth.<suffix>.<domain>"
  type        = string
  default     = "dev"
}

variable "instance_type" {
  description = "t3.large (2 vCPU, 8 GB) runs the whole stack; t3.xlarge for load tests"
  type        = string
  default     = "t3.large"
}

variable "volume_gb" {
  type    = number
  default = 80
}

variable "ssh_public_key" {
  description = "Content of ~/.ssh/occpl_vps.pub (the public half only)"
  type        = string
}

variable "ssh_cidrs" {
  description = "Addresses allowed to connect over SSH, e.g. [\"203.0.113.10/32\"] (your public IP)"
  type        = list(string)
}

provider "aws" {
  region = var.region
  default_tags {
    tags = { Project = "occpl-platform", ManagedBy = "terraform", Stack = "dev" }
  }
}

data "aws_caller_identity" "current" {}

data "aws_route53_zone" "main" {
  name         = var.domain
  private_zone = false
}

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
  filter {
    name   = "default-for-az"
    values = ["true"]
  }
}

# Current Canonical Ubuntu 24.04 LTS image.
data "aws_ssm_parameter" "ubuntu" {
  name = "/aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id"
}

locals {
  name   = "${var.name_prefix}-dev"
  prefix = "${var.host_suffix}.${var.domain}"
  hosts  = ["admin", "api", "auth"]
}

resource "aws_key_pair" "deploy" {
  key_name   = "${local.name}-deploy"
  public_key = var.ssh_public_key
}

resource "aws_security_group" "server" {
  name        = local.name
  description = "SSH from the operator, web traffic from everywhere"
  vpc_id      = data.aws_vpc.default.id
}

resource "aws_vpc_security_group_ingress_rule" "ssh" {
  for_each          = toset(var.ssh_cidrs)
  security_group_id = aws_security_group.server.id
  description       = "SSH"
  ip_protocol       = "tcp"
  from_port         = 22
  to_port           = 22
  cidr_ipv4         = each.value
}

resource "aws_vpc_security_group_ingress_rule" "http" {
  security_group_id = aws_security_group.server.id
  description       = "HTTP (certificate challenge, redirect to HTTPS)"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_ingress_rule" "https" {
  security_group_id = aws_security_group.server.id
  description       = "HTTPS"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_ingress_rule" "http3" {
  security_group_id = aws_security_group.server.id
  description       = "HTTP/3"
  ip_protocol       = "udp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.server.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

# The server writes its database backups to S3 with its own role: no access keys on the machine.
resource "aws_iam_role" "server" {
  name = local.name
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.server.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore" # Session Manager as a second way in
}

resource "aws_iam_role_policy" "backups" {
  name = "write-backups"
  role = aws_iam_role.server.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["s3:ListBucket", "s3:GetBucketLocation"], Resource = "arn:aws:s3:::${var.name_prefix}-backups-${data.aws_caller_identity.current.account_id}" },
      { Effect = "Allow", Action = ["s3:PutObject", "s3:GetObject"], Resource = "arn:aws:s3:::${var.name_prefix}-backups-${data.aws_caller_identity.current.account_id}/dev/*" },
    ]
  })
}

resource "aws_iam_instance_profile" "server" {
  name = local.name
  role = aws_iam_role.server.name
}

resource "aws_instance" "server" {
  ami                    = nonsensitive(data.aws_ssm_parameter.ubuntu.value)
  instance_type          = var.instance_type
  subnet_id              = sort(data.aws_subnets.default.ids)[0]
  vpc_security_group_ids = [aws_security_group.server.id]
  key_name               = aws_key_pair.deploy.key_name
  iam_instance_profile   = aws_iam_instance_profile.server.name
  monitoring             = false

  # t3 default "unlimited" mode bills extra when the CPU stays busy; "standard" throttles instead.
  credit_specification {
    cpu_credits = "standard"
  }
  metadata_options {
    http_tokens                 = "required" # IMDSv2 only
    http_put_response_hop_limit = 1
  }
  root_block_device {
    volume_type = "gp3"
    volume_size = var.volume_gb
    encrypted   = true
  }
  tags = { Name = local.name }

  lifecycle {
    ignore_changes = [ami] # a newer image must not replace the server (its data lives on the disk)
  }
}

resource "aws_eip" "server" {
  domain   = "vpc"
  instance = aws_instance.server.id
  tags     = { Name = local.name }
}

resource "aws_route53_record" "hosts" {
  for_each = toset(local.hosts)
  zone_id  = data.aws_route53_zone.main.zone_id
  name     = "${each.key}.${local.prefix}"
  type     = "A"
  ttl      = 300
  records  = [aws_eip.server.public_ip]
}

output "public_ip" {
  value = aws_eip.server.public_ip
}

output "ssh" {
  value = "ssh -i ~/.ssh/occpl_vps ubuntu@${aws_eip.server.public_ip}"
}

output "hosts" {
  value = [for h in local.hosts : "https://${h}.${local.prefix}"]
}

output "domain_for_env" {
  description = "Answer to the 'Domain' question of bin/init-env.sh on the server"
  value       = local.prefix
}

output "backup_s3_uri" {
  description = "BACKUP_S3_URI for the server's .env"
  value       = "s3://${var.name_prefix}-backups-${data.aws_caller_identity.current.account_id}/dev"
}
