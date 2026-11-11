# Review log — 021 member PWA

## Baseline failures
- Base: origin/main @ 3e39ab2, `make check` (contract, format, lint, backend `./mvnw verify` 4405 tests, clients typecheck/test): **none** (exit 0). e2e not part of the baseline.

## Slice 1 — Expo shell removed, ADR 0021 written

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `7cb7b53..8ffadbe` | PASS | — | minor: the diff deletes `mobile/CLAUDE.md` and `mobile/.claude/settings.json` (inside the requested `mobile/` deletion; flagged to the owner in owner-proposals.md); owner manual Expo lines left for Slice 10; `docs/roadmap.md` still says mobile apps; `nadel-mobile` realm client left on purpose |

## Slice 2 — Stand fixes and their script test

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `8ffadbe..9652767` | MAJOR_ISSUES | M1 navigations served by the Caddy catch-all lack `Cache-Control: no-cache` | minor: wrong admin password retried for 2 min; DER rebuild hardcodes 0x31 (correct for P-256) |
| 2 | + `51e4a15` | PASS | M1 resolved | — |
