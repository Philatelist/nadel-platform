#!/usr/bin/env bash
# Tests for stand-init.sh with a stub `docker` first on PATH (no Keycloak, no Docker needed).
# Run: make stand-test   (works with bash 3.2 on macOS and bash 5 on Linux; needs only openssl, sed, awk, od, base64, tr)
set -uo pipefail

here=$(cd "$(dirname "$0")" && pwd)
script_src="$here/../stand-init.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

failures=0
pass() { echo "  ok   $1"; }
fail() { echo "  FAIL $1" >&2; failures=$((failures + 1)); }
check() { # check <description> <expected> <actual>
  if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 (expected '$2', got '$3')"; fi
}

# The stub: `config credentials` fails with a 503 message while the counter file says so; every other kcadm call
# succeeds; `get` calls print an id.
mkdir -p "$work/bin"
cat > "$work/bin/docker" <<'STUB'
#!/usr/bin/env bash
args="$*"
case "$args" in
  "inspect "*) echo healthy ;;
  "compose ps -q keycloak") echo stubcontainer ;;
  *"config credentials"*)
    n=$(cat "$STUB_DIR/attempts" 2>/dev/null || echo 0)
    n=$((n + 1))
    echo "$n" > "$STUB_DIR/attempts"
    if [ "$n" -le "$STUB_LOGIN_FAILURES" ]; then
      echo "Failed to send request - 503 Service Unavailable password=$STUB_SECRET_MARKER" >&2
      exit 1
    fi
    ;;
  *" get "*) echo stub-id ;;
esac
exit 0
STUB
chmod +x "$work/bin/docker"

# setup <case name> <login failures> : fresh copy of the script and an .env in its own directory
setup() {
  case_dir="$work/$1"
  mkdir -p "$case_dir/stand"
  cp "$script_src" "$case_dir/stand/stand-init.sh"
  printf 'NADEL_DOMAIN=stand.example.com\nSTAND_USER_PASSWORD=standpass123\nKC_ADMIN_PASSWORD=SECRET-ADMIN-PW\nNADEL_WEBPUSH_PUBLIC_KEY=\nNADEL_WEBPUSH_PRIVATE_KEY=\n' > "$case_dir/stand/.env"
  chmod 600 "$case_dir/stand/.env"
  export STUB_DIR="$case_dir"
  export STUB_LOGIN_FAILURES=$2
  export STUB_SECRET_MARKER=SECRET-ADMIN-PW
  rm -f "$case_dir/attempts"
}

run_init() { # run_init <case name>; sets rc and out
  out=$(PATH="$work/bin:$PATH" KC_HEALTH_TRIES=2 KC_HEALTH_INTERVAL=0 KC_LOGIN_TIMEOUT=3 KC_LOGIN_INTERVAL=0 \
    bash "$case_dir/stand/stand-init.sh" 2>&1)
  rc=$?
}

env_value() { grep "^$1=" "$case_dir/stand/.env" | head -n 1 | cut -d= -f2-; }
attempts() { cat "$case_dir/attempts" 2>/dev/null || echo 0; }

echo "login retry"
setup retry 2
run_init retry
check "503 twice then success: exit 0" 0 "$rc"
check "503 twice then success: 3 login attempts" 3 "$(attempts)"
case "$out" in *SECRET-ADMIN-PW*) fail "output must not contain the admin password" ;; *) pass "no admin password in output" ;; esac
case "$out" in *"503"*) fail "kcadm output must not reach the terminal" ;; *) pass "kcadm output suppressed" ;; esac

setup never 1000
run_init never
check "login never works: exit 1" 1 "$rc"
check "login never works: gives up after the timeout (3 attempts)" 3 "$(attempts)"
case "$out" in *"Could not log in to the Keycloak admin API"*) pass "clear failure message" ;; *) fail "clear failure message missing" ;; esac
case "$out" in *SECRET-ADMIN-PW*) fail "output must not contain the admin password" ;; *) pass "no admin password on failure" ;; esac

echo "VAPID keys"
setup keys 0
run_init keys
check "run succeeds" 0 "$rc"
pub=$(env_value NADEL_WEBPUSH_PUBLIC_KEY)
priv=$(env_value NADEL_WEBPUSH_PRIVATE_KEY)
check "public key length (base64url)" 87 "${#pub}"
check "private key length (base64url)" 43 "${#priv}"
case "$pub$priv" in *[+/=]*) fail "keys must be base64url without padding" ;; *) pass "keys are base64url without padding" ;; esac
case "$out" in *"$priv"*|*"$pub"*) fail "keys must not be printed" ;; *) pass "keys not printed" ;; esac

# Hex of a base64url string (no padding) using only tr, base64 and od
b64url_hex() {
  local s
  s=$(printf '%s' "$1" | tr '_-' '/+')
  case $(( ${#s} % 4 )) in 2) s="$s==" ;; 3) s="$s=" ;; esac
  printf '%s' "$s" | base64 -d 2>/dev/null || printf '%s' "$s" | base64 -D
}
pub_hex=$(b64url_hex "$pub" | od -An -v -tx1 | tr -d ' \n')
priv_hex=$(b64url_hex "$priv" | od -An -v -tx1 | tr -d ' \n')
check "public key is 65 bytes" 130 "${#pub_hex}"
check "public key is an uncompressed point (0x04)" 04 "${pub_hex:0:2}"
check "private key is 32 bytes" 64 "${#priv_hex}"

# The public key must belong to the private key: rebuild a SEC1 DER key from the scalar alone (P-256 OID) and let
# openssl derive the public point. Nothing about the private key is printed.
der="$work/derived.der"
{ printf '30310201010420%s' "$priv_hex" ; printf 'a00a06082a8648ce3d030107'; } | sed 's/../\\x&/g' | { read -r esc; printf "$esc" > "$der"; }
derived=$(openssl ec -inform DER -in "$der" -text -noout 2>/dev/null | awk '/^pub:/{f=1;next} /^ASN1 OID|^NIST CURVE/{f=0} f' | tr -d ' :\n')
rm -f "$der"
check "public key matches the private key" "$pub_hex" "$derived"

env_before=$(cat "$case_dir/stand/.env" | grep '^NADEL_WEBPUSH_')
rm -f "$case_dir/attempts"
run_init keys
check "second run succeeds" 0 "$rc"
check "second run keeps the VAPID keys" "$env_before" "$(grep '^NADEL_WEBPUSH_' "$case_dir/stand/.env")"
check "keys appear once each" 1 "$(grep -c '^NADEL_WEBPUSH_PRIVATE_KEY=' "$case_dir/stand/.env")"
check "Keycloak admin secret is still rotated by every run" 1 "$(grep -c '^NADEL_KEYCLOAK_ADMIN_CLIENT_SECRET=' "$case_dir/stand/.env")"

echo ".env permissions"
if mode=$(stat -c '%a' "$case_dir/stand/.env" 2>/dev/null); then :; else mode=$(stat -f '%Lp' "$case_dir/stand/.env"); fi
check ".env mode is 600" 600 "$mode"
leftovers=$(ls -A "$case_dir/stand" | grep -c '^\.env\.' || true)
check "no temp files left next to .env" 0 "$leftovers"

echo
if [ "$failures" -eq 0 ]; then echo "stand-init tests passed"; else echo "$failures stand-init test(s) FAILED" >&2; exit 1; fi
