#!/usr/bin/env bash
# Starts a release on this instance and waits until every service is ready.
#   deploy.sh [<release>]   (default: the release recorded in SSM <prefix>/release)
# Called by the rolling deploy (scripts/rolling-deploy.sh via SSM) after the instance was taken
# out of the load balancer, and at boot by new instances. Exit code != 0 = not ready.
set -euo pipefail
export PATH="$PATH:/snap/bin" # aws-cli snap; SSM and cloud-init shells may lack it
exec > >(tee -a /var/log/occpl-deploy.log) 2>&1
# shellcheck disable=SC1091
source /etc/occpl/instance.env
cd /opt/occpl

release=${1:-$(aws ssm get-parameter --name "$SSM_PREFIX/release" --query Parameter.Value --output text)}
if [ -z "$release" ] || [ "$release" = "none" ]; then
  echo "no release recorded yet; nothing to start"
  exit 0
fi
config=$(aws ssm get-parameter --name "$SSM_PREFIX/config" --query Parameter.Value --output text)
registry=$(jq -r .registry <<< "$config")
echo "$(date -u +%FT%TZ) deploying release $release from $registry"

bin/render-env.sh
aws ecr get-login-password | docker login --username AWS --password-stdin "$registry" >/dev/null

export REGISTRY=$registry RELEASE=$release OCCPL_ENV AWS_REGION
docker compose pull --quiet
docker compose up -d --remove-orphans

# CloudWatch agent (metrics, prometheus scrape, deploy log); idempotent.
sed "s/OCCPL_ENV/$OCCPL_ENV/g" cloudwatch/agent.json > /opt/occpl/cloudwatch/agent.rendered.json
sed -i "s/OCCPL_ENV/$OCCPL_ENV/g" cloudwatch/prometheus.yml
/opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a fetch-config -m ec2 -s \
  -c file:/opt/occpl/cloudwatch/agent.rendered.json >/dev/null || echo "warning: CloudWatch agent not configured"

wait_ready() { # name url timeout_seconds
  local deadline=$((SECONDS + $3))
  until curl -fs -o /dev/null --max-time 5 "$2"; do
    if [ $SECONDS -ge $deadline ]; then
      echo "$1 not ready after $3 s"; docker compose ps; docker compose logs --tail 80 "$1"; return 1
    fi
    sleep 5
  done
  echo "$1 ready"
}
wait_ready keycloak http://127.0.0.1:9000/health/ready 300
wait_ready core-platform http://127.0.0.1:8081/actuator/health/readiness 300
wait_ready admin-portal http://127.0.0.1:8088/healthz 60
docker image prune -af --filter "until=168h" >/dev/null || true
echo "$(date -u +%FT%TZ) release $release running"
