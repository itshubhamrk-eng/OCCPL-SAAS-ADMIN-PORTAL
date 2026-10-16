#!/usr/bin/env bash
# Tells CI and Release which parts of the repository a change touches, as lines for $GITHUB_OUTPUT:
#   backend=true|false   frontend=true|false   infra=true|false
#   core=true|false      portal=true|false     keycloak=true|false     (the three released images)
# Usage: changed-paths.sh <base-commit> <head-commit>
# If the base is empty or unknown, or the workflow/this script changed, everything counts as changed:
# a wrong "unchanged" would skip tests or ship a stale image, a wrong "changed" only costs time.
set -euo pipefail

base=${1:-}
head=${2:-HEAD}

emit() { echo "$1=$2"; }

all_changed() {
  for name in backend frontend infra core portal keycloak; do emit "$name" true; done
  exit 0
}

if [ -z "$base" ] || ! git rev-parse --verify -q "$base^{commit}" >/dev/null; then all_changed; fi
files=$(git diff --name-only "$base" "$head" 2>/dev/null) || all_changed

touched() { # regex of paths; true when any changed file matches
  if printf '%s\n' "$files" | grep -Eq "$1"; then echo true; else echo false; fi
}

# Changes to the pipeline itself must be proven by running everything.
if [ "$(touched '^(\.github/|infra/scripts/changed-paths\.sh$)')" = true ]; then all_changed; fi

emit backend "$(touched '^backend/')"
emit frontend "$(touched '^frontend/')"
emit infra "$(touched '^infra/')"
emit core "$(touched '^backend/core-platform/')"
emit portal "$(touched '^frontend/admin-portal/')"
# The production realm is generated from the development realm, so both belong to the Keycloak image.
emit keycloak "$(touched '^(infra/aws/compose/keycloak/|infra/docker/keycloak/)')"
