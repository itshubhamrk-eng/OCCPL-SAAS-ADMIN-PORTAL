#!/usr/bin/env bash
# Adds a tag to an image that is already in a registry, byte for byte: the same manifest is read
# under the old tag and written under the new one, so the digest does not change and a server that
# already runs it sees no new image. (`docker buildx imagetools create` would wrap it in a new index.)
# Usage: retag-image.sh <registry> <repository> <from-tag> <to-tag>
#   e.g. retag-image.sh ghcr.io owner/occpl-core-platform <old-sha> <new-sha>
# For ghcr.io set REGISTRY_USER and REGISTRY_TOKEN (a token that may push the package).
# Exit codes: 0 done, 2 the source tag does not exist, other: failure.
set -euo pipefail

registry=${1:?registry}
repo=${2:?repository}
from=${3:?from-tag}
to=${4:?to-tag}

scheme=https
case "$registry" in localhost:* | 127.0.0.1:*) scheme=http ;; esac

auth=()
if [ -n "${REGISTRY_TOKEN:-}" ]; then
  token=$(curl -fsS -u "${REGISTRY_USER:?REGISTRY_USER is required with REGISTRY_TOKEN}:$REGISTRY_TOKEN" \
    "$scheme://$registry/token?service=$registry&scope=repository:$repo:pull,push" |
    python3 -c 'import json, sys; print(json.load(sys.stdin)["token"])')
  auth=(-H "Authorization: Bearer $token")
fi

accept='application/vnd.oci.image.index.v1+json,application/vnd.oci.image.manifest.v1+json'
accept+=',application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.docker.distribution.manifest.v2+json'
body=$(mktemp)
headers=$(mktemp)
trap 'rm -f "$body" "$headers"' EXIT
url="$scheme://$registry/v2/$repo/manifests"

status=$(curl -sS "${auth[@]}" -H "Accept: $accept" -D "$headers" -o "$body" -w '%{http_code}' "$url/$from")
[ "$status" != 404 ] || { echo "$repo:$from not found" >&2; exit 2; }
[ "$status" = 200 ] || { echo "reading $repo:$from failed (HTTP $status)" >&2; exit 1; }
type=$(sed -n 's/^[Cc]ontent-[Tt]ype: *//p' "$headers" | tr -d '\r' | tail -1)

curl -fsS "${auth[@]}" -X PUT -H "Content-Type: $type" --data-binary "@$body" "$url/$to" >/dev/null
echo "$repo:$to now points at the same image as $repo:$from"
