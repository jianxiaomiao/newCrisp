#!/usr/bin/env bash
set -euo pipefail

root=$1
fixture=$2
maven=$3
test -f "$root/nexus-server/pom.xml"
test -f "$root/nexus-console/package-lock.json"

rsync -a --exclude target --exclude .idea --exclude '.env*' --exclude 'application-local.*' \
  "$root/nexus-server/" "$fixture/server/"
rsync -a --exclude node_modules --exclude dist --exclude .idea --exclude '.env*' \
  "$root/nexus-console/" "$fixture/console/"

(cd "$fixture/server" && "$maven" -B -Dmaven.test.skip=true package)
(cd "$fixture/console" && npm ci --no-audit --no-fund && VITE_NEXUS_CHAT_ENABLED=true npm run build)
