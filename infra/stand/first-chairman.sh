#!/usr/bin/env bash
# Creates a cooperative and its first chairman, and prints the one-time "set your password" link to stdout.
# Run on the server in /opt/nadel (next to compose.yml). See infra/stand/README.md and ADR 0020.
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: ./first-chairman.sh --cooperative NAME --email ADDRESS --last-name NAME --first-name NAME [--resend]

Creates the cooperative (with base roles, settings and charge types) and its first chairman, then prints a
one-time set-password link ONCE. Pass the link to the chairman privately; it is valid for 72 hours and is never
stored in readable form.

  --cooperative NAME   name of the cooperative, for example "SK Zorya"
  --email ADDRESS      the chairman's e-mail (the sign-in name)
  --last-name NAME     the chairman's last name
  --first-name NAME    the chairman's first name
  --resend             the cooperative already exists: issue a new link (the old one stops working)
  -h, --help           this text

Safe to run again with the same arguments: nothing is created twice. Exit code is not zero on any failure.
USAGE
}

coop=''; email=''; last=''; first=''; resend=false
while [ $# -gt 0 ]; do
  case "$1" in
    --cooperative) coop="${2-}"; shift 2 ;;
    --email) email="${2-}"; shift 2 ;;
    --last-name) last="${2-}"; shift 2 ;;
    --first-name) first="${2-}"; shift 2 ;;
    --resend) resend=true; shift ;;
    -h | --help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

fail() { echo "Error: $1" >&2; exit 2; }
[ -n "$coop" ] || fail "--cooperative is required"
[ -n "$last" ] || fail "--last-name is required"
[ -n "$first" ] || fail "--first-name is required"
[ -n "$email" ] || fail "--email is required"
[[ "$email" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]] || fail "--email is not a valid address"
[ "${#coop}" -le 200 ] || fail "--cooperative is too long (200 characters at most)"

cd "$(dirname "$0")"
[ -f compose.yml ] || fail "compose.yml not found next to the script"

# One-shot container: no published ports, no HTTP server, no dependencies started. Postgres and Keycloak must be up.
exec docker compose run --rm --no-deps backend \
  java -jar /app/nadel-backend.jar \
  --spring.profiles.include=command \
  --nadel.command=first-chairman \
  "--nadel.first-chairman.cooperative=${coop}" \
  "--nadel.first-chairman.email=${email}" \
  "--nadel.first-chairman.last-name=${last}" \
  "--nadel.first-chairman.first-name=${first}" \
  "--nadel.first-chairman.resend=${resend}"
