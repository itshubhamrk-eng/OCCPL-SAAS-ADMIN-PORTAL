#!/usr/bin/env bash
# Operator command: create the schemas and database accounts of an environment on RDS
# (compose/bin/bootstrap-db.sh), run on one app instance through SSM. Needed once before the first
# deploy and again after restoring into a new DB instance (RUNBOOK.md). The script is uploaded to
# a separate prefix, so the bundle of the running release is not touched.
#
# Usage: bootstrap-database.sh
# Environment: OCCPL_ENV, ASG_NAME, ARTIFACTS_BUCKET, AWS_REGION; operator (admin) credentials.
set -euo pipefail
: "${OCCPL_ENV:?}" "${ASG_NAME:?}" "${ARTIFACTS_BUCKET:?}" "${AWS_REGION:?}"
here=$(cd "$(dirname "$0")" && pwd)
prefix="s3://$ARTIFACTS_BUCKET/$OCCPL_ENV/bootstrap"

aws s3 cp "$here/../compose/bin/bootstrap-db.sh" "$prefix/bootstrap-db.sh"
instance=$(aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$ASG_NAME" \
  --query "AutoScalingGroups[0].Instances[?LifecycleState=='InService'].InstanceId | [0]" --output text)
if [ -z "$instance" ] || [ "$instance" = None ]; then echo "no instance in service in $ASG_NAME" >&2; exit 1; fi

id=$(aws ssm send-command --instance-ids "$instance" --document-name AWS-RunShellScript \
  --comment "occpl bootstrap database" --timeout-seconds 600 \
  --parameters "commands=[\"set -e\",\"export PATH=\$PATH:/snap/bin\",\"aws s3 cp $prefix/bootstrap-db.sh /root/bootstrap-db.sh\",\"bash /root/bootstrap-db.sh\",\"rm -f /root/bootstrap-db.sh\"]" \
  --query Command.CommandId --output text)
status=Pending
for _ in $(seq 1 60); do
  sleep 10
  status=$(aws ssm get-command-invocation --command-id "$id" --instance-id "$instance" --query Status --output text 2>/dev/null || echo Pending)
  case "$status" in Pending|InProgress|Delayed) continue ;; *) break ;; esac
done
aws ssm get-command-invocation --command-id "$id" --instance-id "$instance" \
  --query '[StandardOutputContent,StandardErrorContent]' --output text
echo "bootstrap on $instance: $status"
[ "$status" = Success ]
