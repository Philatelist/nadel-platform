#!/usr/bin/env bash
# Run once on the server after the first `docker compose up -d keycloak`, BEFORE Caddy is started.
# The imported demo realm has public passwords (`nadel`, see CLAUDE.md) and localhost redirect URIs; this script
# sets a new password for the demo board users, points the web client at the stand's domain and disables the
# users the board does not need. It also rotates the secret of the backend's Keycloak service account
# (`nadel-backend-admin`) into .env and sets the realm password policy. Safe to run again.
set -euo pipefail
cd "$(dirname "$0")"
set -a
. ./.env
set +a

if [ "${#STAND_USER_PASSWORD}" -lt 8 ]; then
  echo "STAND_USER_PASSWORD must be at least 8 characters (the realm password policy is length(8))" >&2
  exit 1
fi

kc() { docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@"; }

echo "Waiting for Keycloak to become healthy..."
for _ in $(seq 1 60); do
  status=$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q keycloak)" 2>/dev/null || true)
  [ "$status" = healthy ] && break
  sleep 5
done
if [ "${status:-}" != healthy ]; then
  echo "Keycloak is not healthy after 5 minutes; see: docker compose logs keycloak" >&2
  exit 1
fi

kc config credentials --server http://localhost:8080/auth --realm master --user admin --password "$KC_ADMIN_PASSWORD"

# Realm password policy: minimum length 8, so a board user password that is too short is rejected
kc update realms/nadel -s 'passwordPolicy=length(8)'

client_id=$(kc get clients -r nadel -q clientId=nadel-web --fields id --format csv --noquotes | tr -d '\r')
kc update "clients/$client_id" -r nadel \
  -s "redirectUris=[\"https://$NADEL_DOMAIN/*\"]" \
  -s "webOrigins=[\"https://$NADEL_DOMAIN\"]"

for user in chairman@demo.local treasurer@demo.local electrician@demo.local member@demo.local; do
  kc set-password -r nadel --username "$user" --new-password "$STAND_USER_PASSWORD"
done

for user in member@other.local operator@nadel.local; do
  user_id=$(kc get users -r nadel -q username="$user" -q exact=true --fields id --format csv --noquotes | tr -d '\r')
  if [ -n "$user_id" ]; then
    kc update "users/$user_id" -r nadel -s enabled=false
  fi
done

# Rotate the backend service-account secret (the imported dev value is public) and store it in .env
admin_client_id=$(kc get clients -r nadel -q clientId=nadel-backend-admin --fields id --format csv --noquotes | tr -d '\r')
if [ -z "$admin_client_id" ]; then
  echo "Client nadel-backend-admin not found in realm nadel: the realm was imported before it existed" >&2
  exit 1
fi
admin_secret=$(openssl rand -hex 32)
kc update "clients/$admin_client_id" -r nadel -s "secret=$admin_secret"
touch .env && chmod 600 .env
if grep -q '^NADEL_KEYCLOAK_ADMIN_CLIENT_SECRET=' .env; then
  tmp=$(mktemp ./.env.XXXXXX)
  grep -v '^NADEL_KEYCLOAK_ADMIN_CLIENT_SECRET=' .env > "$tmp" || true
  echo "NADEL_KEYCLOAK_ADMIN_CLIENT_SECRET=$admin_secret" >> "$tmp"
  chmod 600 "$tmp"
  mv "$tmp" .env
else
  echo "NADEL_KEYCLOAK_ADMIN_CLIENT_SECRET=$admin_secret" >> .env
fi

echo "Keycloak prepared for https://$NADEL_DOMAIN: demo board users have the new password, other users disabled."
echo "The backend secret changed: run 'docker compose up -d backend' so the backend reads it."
