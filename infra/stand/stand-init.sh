#!/usr/bin/env bash
# Run once on the server after the first `docker compose up -d keycloak`, BEFORE Caddy is started.
# The imported demo realm has public passwords (`nadel`, see CLAUDE.md) and localhost redirect URIs; this script
# sets a new password for the demo board users, points the web client at the stand's domain and disables the
# users the board does not need. It also rotates the secret of the backend's Keycloak service account
# (`nadel-backend-admin`) into .env and sets the realm password policy. It also generates the Web Push (VAPID) key
# pair once (only when either key in .env is empty; never rotated, a rotation drops every push subscription).
# Safe to run again. Tunables (seconds/tries): KC_HEALTH_TRIES (60), KC_HEALTH_INTERVAL (5), KC_LOGIN_TIMEOUT (120),
# KC_LOGIN_INTERVAL (5).
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

health_tries=${KC_HEALTH_TRIES:-60}
health_interval=${KC_HEALTH_INTERVAL:-5}
login_timeout=${KC_LOGIN_TIMEOUT:-120}
login_interval=${KC_LOGIN_INTERVAL:-5}

echo "Waiting for Keycloak to become healthy..."
status=
for _ in $(seq 1 "$health_tries"); do
  status=$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q keycloak)" 2>/dev/null || true)
  [ "$status" = healthy ] && break
  sleep "$health_interval"
done
if [ "$status" != healthy ]; then
  echo "Keycloak is not healthy after $((health_tries * health_interval)) seconds; see: docker compose logs keycloak" >&2
  exit 1
fi

# Healthy does not mean the admin endpoint already answers (a fresh realm import may still be running): retry the login.
# kcadm output goes to /dev/null so neither the password nor server messages reach the terminal.
if [ "$login_interval" -gt 0 ]; then
  login_attempts=$((login_timeout / login_interval))
else
  login_attempts=$login_timeout
fi
[ "$login_attempts" -ge 1 ] || login_attempts=1
logged_in=false
for attempt in $(seq 1 "$login_attempts"); do
  if kc config credentials --server http://localhost:8080/auth --realm master --user admin \
    --password "$KC_ADMIN_PASSWORD" >/dev/null 2>&1; then
    logged_in=true
    break
  fi
  [ "$attempt" -lt "$login_attempts" ] && sleep "$login_interval"
done
if [ "$logged_in" != true ]; then
  echo "Could not log in to the Keycloak admin API after $login_attempts attempts; check KC_ADMIN_PASSWORD in .env and: docker compose logs keycloak" >&2
  exit 1
fi

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

# Upsert KEY=value in .env (mode 600, atomic replace)
env_set() {
  local key=$1 value=$2 tmp
  touch .env && chmod 600 .env
  tmp=$(mktemp ./.env.XXXXXX)
  grep -v "^${key}=" .env > "$tmp" || true
  echo "${key}=${value}" >> "$tmp"
  chmod 600 "$tmp"
  mv "$tmp" .env
}

env_set NADEL_KEYCLOAK_ADMIN_CLIENT_SECRET "$admin_secret"

# Web Push (VAPID) P-256 key pair, generated once: a new pair would invalidate every stored push subscription.
# Public key = uncompressed point (65 bytes), private key = scalar (32 bytes), both base64url without padding
# (87 and 43 characters). Parsed from `openssl ec -text` rather than DER offsets. Never printed.
if [ -z "${NADEL_WEBPUSH_PUBLIC_KEY:-}" ] || [ -z "${NADEL_WEBPUSH_PRIVATE_KEY:-}" ]; then
  b64url() { sed 's/../\\x&/g' | { read -r esc; printf "$esc"; } | base64 | tr '+/' '-_' | tr -d '=\n'; }
  key_text=$(openssl ecparam -name prime256v1 -genkey -noout | openssl ec -text -noout 2>/dev/null)
  priv_hex=$(printf '%s\n' "$key_text" | awk '/^priv:/{f=1;next} /^pub:/{f=0} f' | tr -d ' :\n' | tail -c 64)
  pub_hex=$(printf '%s\n' "$key_text" | awk '/^pub:/{f=1;next} /^ASN1 OID|^NIST CURVE/{f=0} f' | tr -d ' :\n')
  if [ "${#priv_hex}" -ne 64 ] || [ "${#pub_hex}" -ne 130 ] || [ "${pub_hex:0:2}" != 04 ]; then
    echo "Could not generate the Web Push key pair with openssl" >&2
    exit 1
  fi
  env_set NADEL_WEBPUSH_PUBLIC_KEY "$(printf '%s' "$pub_hex" | b64url)"
  env_set NADEL_WEBPUSH_PRIVATE_KEY "$(printf '%s' "$priv_hex" | b64url)"
  unset key_text priv_hex pub_hex
  echo "Generated the Web Push (VAPID) key pair into .env (kept on later runs)."
fi

echo "Keycloak prepared for https://$NADEL_DOMAIN: demo board users have the new password, other users disabled."
echo "The backend secret changed: run 'docker compose up -d backend' so the backend reads it."
