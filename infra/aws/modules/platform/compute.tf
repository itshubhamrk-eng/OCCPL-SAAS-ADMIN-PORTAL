data "aws_ssm_parameter" "ubuntu_ami" {
  name = "/aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id"
}

resource "aws_launch_template" "app" {
  name_prefix   = "${local.name}-app-"
  image_id      = data.aws_ssm_parameter.ubuntu_ami.value
  instance_type = var.instance_type
  iam_instance_profile {
    arn = aws_iam_instance_profile.instance.arn
  }
  vpc_security_group_ids = [aws_security_group.app.id]
  metadata_options {
    http_tokens                 = "required" # IMDSv2 only
    http_put_response_hop_limit = 2          # containers may use the instance role
    http_endpoint               = "enabled"
  }
  block_device_mappings {
    device_name = "/dev/sda1"
    ebs {
      volume_size           = var.root_volume_gb
      volume_type           = "gp3"
      encrypted             = true
      delete_on_termination = true
    }
  }
  monitoring {
    enabled = true
  }
  user_data = base64encode(templatefile("${path.module}/user_data.sh.tftpl", {
    env              = var.env
    region           = data.aws_region.current.region
    artifacts_bucket = var.artifacts_bucket
    ssm_prefix       = local.ssm_prefix
  }))
  tag_specifications {
    resource_type = "instance"
    tags          = { Name = "${local.name}-app", Environment = var.env, Role = "app" }
  }
  tag_specifications {
    resource_type = "volume"
    tags          = { Name = "${local.name}-app", Environment = var.env }
  }
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_autoscaling_group" "app" {
  name                      = "${local.name}-app"
  vpc_zone_identifier       = aws_subnet.app[*].id
  min_size                  = var.instance_count
  max_size                  = var.instance_count + 1 # room for one extra during instance refresh
  desired_capacity          = var.instance_count
  health_check_type         = "ELB"
  health_check_grace_period = 900 # first boot installs packages and pulls images
  target_group_arns         = [aws_lb_target_group.core.arn, aws_lb_target_group.portal.arn, aws_lb_target_group.keycloak.arn]
  default_instance_warmup   = 300
  enabled_metrics           = ["GroupDesiredCapacity", "GroupInServiceInstances", "GroupTotalInstances"]

  launch_template {
    id      = aws_launch_template.app.id
    version = aws_launch_template.app.latest_version
  }

  # A new launch template (e.g. monthly AMI refresh) replaces instances one at a time.
  instance_refresh {
    strategy = "Rolling"
    preferences {
      min_healthy_percentage = 50
      instance_warmup        = 600
    }
  }

  tag {
    key                 = "Environment"
    value               = var.env
    propagate_at_launch = true
  }
}
