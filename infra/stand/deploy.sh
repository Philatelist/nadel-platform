#!/usr/bin/env bash
# Builds the backend jar and the web app on this machine and copies them with the stand files to the server.
# Usage (from the repository root, on main): infra/stand/deploy.sh <ssh-host> <domain>
#   infra/stand/deploy.sh root@203.0.113.10 nadel.example.com
# The first run only copies files; start the stack by hand as in infra/stand/README.md. Later runs also restart it.
set -euo pipefail

host=${1:?usage: deploy.sh <ssh-host> <domain>}
domain=${2:?usage: deploy.sh <ssh-host> <domain>}
root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"

echo "==> Building the backend jar (tests skipped: CI is the gate)"
(cd backend && ./mvnw -q -DskipTests package)
jar=$(ls -t backend/target/nadel-backend-*.jar | grep -v -- '-plain' | head -1)

echo "==> Building the web app for https://$domain"
VITE_OIDC_AUTHORITY="https://$domain/auth/realms/nadel" VITE_OIDC_CLIENT_ID=nadel-web pnpm --filter web build

echo "==> Copying to $host:/opt/nadel"
ssh "$host" 'mkdir -p /opt/nadel/app /opt/nadel/web /opt/nadel/keycloak'
rsync -az infra/stand/compose.yml infra/stand/Caddyfile infra/stand/init-db.sh infra/stand/stand-init.sh infra/stand/first-chairman.sh \
  infra/stand/.env.example "$host":/opt/nadel/
rsync -az infra/keycloak/nadel-realm.json "$host":/opt/nadel/keycloak/
rsync -az "$jar" "$host":/opt/nadel/app/nadel-backend.jar
rsync -az --delete web/dist/ "$host":/opt/nadel/web/

if ssh "$host" 'test -f /opt/nadel/.env && cd /opt/nadel && docker compose ps --services --status running | grep -q backend'; then
  echo "==> Restarting the backend"
  ssh "$host" 'cd /opt/nadel && docker compose up -d && docker compose restart backend'
else
  echo "==> Files copied. First install: follow infra/stand/README.md (create .env, start, run stand-init.sh)."
fi
