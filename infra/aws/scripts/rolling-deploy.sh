#!/usr/bin/env bash
# Rolling deploy of a release to one environment (ADR-0011), one instance at a time:
#   take it out of the load balancer → deploy through SSM → wait until healthy → put it back.
# If an instance fails, the deploy stops and every instance already updated goes back to the
# previous release, so the environment never serves two releases for long. Used by
# .github/workflows/deploy.yml and by operators (RUNBOOK.md). A rollback is the same command
# with the previous release.
#
# Usage: rolling-deploy.sh <release>
# Environment: OCCPL_ENV, ASG_NAME, TARGET_GROUP_ARNS (space-separated), ARTIFACTS_BUCKET,
#              AWS_REGION; AWS credentials of the environment's deploy role.
set -euo pipefail

release=${1:?usage: rolling-deploy.sh <release>}
: "${OCCPL_ENV:?}" "${ASG_NAME:?}" "${TARGET_GROUP_ARNS:?}" "${ARTIFACTS_BUCKET:?}" "${AWS_REGION:?}"
here=$(cd "$(dirname "$0")" && pwd)
bundle_src="$here/../compose"
param="/occpl/$OCCPL_ENV/release"
prefix="s3://$ARTIFACTS_BUCKET/$OCCPL_ENV/bundle"
log() { echo "$(date -u +%H:%M:%S) $*"; }

# 1. Release bundle (compose file, instance scripts, CloudWatch config), kept per release.
aws s3 sync --delete --exclude 'keycloak/*' "$bundle_src/" "$prefix/$release/"
aws s3 sync --delete "$prefix/$release/" "$prefix/current/"

previous=$(aws ssm get-parameter --name "$param" --query Parameter.Value --output text)
aws ssm put-parameter --name "$param" --value "$release" --overwrite >/dev/null
log "$OCCPL_ENV: $previous → $release"

instances=$(aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$ASG_NAME" \
  --query "AutoScalingGroups[0].Instances[?LifecycleState=='InService'].InstanceId" --output text)
[ -n "$instances" ] || { echo "no instances in service in $ASG_NAME" >&2; exit 1; }

targets() { # deregister|register instance
  for tg in $TARGET_GROUP_ARNS; do
    aws elbv2 "$1-targets" --target-group-arn "$tg" --targets "Id=$2" >/dev/null
  done
  for tg in $TARGET_GROUP_ARNS; do
    if [ "$1" = deregister ]; then
      aws elbv2 wait target-deregistered --target-group-arn "$tg" --targets "Id=$2"
    else
      aws elbv2 wait target-in-service --target-group-arn "$tg" --targets "Id=$2"
    fi
  done
}

run_deploy() { # instance release → 0 when the instance reports all services ready
  local id
  id=$(aws ssm send-command --instance-ids "$1" --document-name AWS-RunShellScript \
    --comment "occpl deploy $2" --timeout-seconds 900 \
    --parameters "commands=[\"set -e\",\"export PATH=\$PATH:/snap/bin\",\"aws s3 sync --delete s3://$ARTIFACTS_BUCKET/$OCCPL_ENV/bundle/current/ /opt/occpl/\",\"chmod +x /opt/occpl/bin/*.sh\",\"/opt/occpl/bin/deploy.sh $2\"]" \
    --query Command.CommandId --output text)
  local status=Pending
  for _ in $(seq 1 120); do
    sleep 10
    status=$(aws ssm get-command-invocation --command-id "$id" --instance-id "$1" --query Status --output text 2>/dev/null || echo Pending)
    case "$status" in Pending|InProgress|Delayed) continue ;; *) break ;; esac
  done
  aws ssm get-command-invocation --command-id "$id" --instance-id "$1" \
    --query StandardOutputContent --output text | tail -20
  [ "$status" = Success ]
}

done=()
for instance in $instances; do
  log "$instance: leaving the load balancer"
  targets deregister "$instance"
  log "$instance: deploying $release"
  if run_deploy "$instance" "$release"; then
    targets register "$instance"
    done+=("$instance")
    log "$instance: back in service on $release"
  else
    log "$instance: deploy FAILED"
    aws ssm put-parameter --name "$param" --value "$previous" --overwrite >/dev/null
    if [ "$previous" != "none" ]; then
      # Every instance goes back, so the environment never runs two releases. Database migrations
      # of the failed release stay applied; they are backward compatible (expand/contract, RUNBOOK.md).
      aws s3 sync --delete "$prefix/$previous/" "$prefix/current/"
      for back in "$instance" "${done[@]}"; do
        log "$back: rolling back to $previous"
        [ "$back" = "$instance" ] || targets deregister "$back"
        if run_deploy "$back" "$previous"; then
          targets register "$back"
          log "$back: back in service on $previous"
        else
          log "$back: ROLLBACK FAILED — left out of the load balancer"
        fi
      done
    fi
    exit 1
  fi
done
log "$OCCPL_ENV now runs $release on: $instances"
