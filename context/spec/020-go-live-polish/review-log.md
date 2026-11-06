# Review log — 020-go-live-polish

## Baseline failures

- Base commit: `b324146` (`main`), command: `make check`
- Result: could not run: skipped by the user's instruction for this run (no test runs until the end); baseline = green CI on `main` @ `b324146`

## Slice 1 — Clearer payment, allocation, expense and overlap screens

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `2fc2c51..4ef6f02` | PASS | — | static (no tests, no node_modules); minor: eslint-disable on payer effect deps, missing blank line in PaymentForm.test, fingerprint computed before reject checks |

## Slice 2 — Delete an unused tariff

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `4ef6f02..cd9183b` | PASS | — | static; minor: TariffUsages.lastChargedDay map get, double query invalidation in DeleteTariff, LedgerTariffUsage ignores tenantId (RLS) |

## Slice 3 — Archive a used tariff; new tariff form hints

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `cd9183b..ddf8c81` | PASS | — | static + offline compile OK; minor: long javadoc line in ChargeCalculator, redundant query invalidation in ArchiveTariffDialog, comment in TariffVersionChangeService |

## Slice 4 — Invite a member and set the password (backend, realm, contract)

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `ddf8c81..a2e5224` | PASS | — | static + offline compile OK; minor: accept holds locks across Keycloak calls (comment), legacy NONE window (comment), no rate limiting on public endpoints (hardening follow-up) |

## Slice 5 — Access block on the member card and the set-password page

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `a2e5224..ef3b71e` | MAJOR_ISSUES | M1 public set-password calls may send a stale bearer token | minor: resend/disable wired early (slice 6 adds tests); set-password topic front matter to confirm in slice 9 |
| 2 | + `579d6ad` | PASS | M1 resolved | minor: startsWith on relative URL only |

## Slice 6 — Resend, disable, the archive and e-mail rules, Telegram, last sign-in

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `579d6ad..71e116a` | MAJOR_ISSUES | M1 complete() can undo a concurrent disable/archive/e-mail change | minor: pre-check race leaves INVITED without link (README), listener 4xx leaves INVITED without link, check BotChat logging on 403 |
| 2 | + `1a30d0a` | PASS | M1 resolved | offline compile OK; minor: completeOrRevoke catches only ApiProblemException |

## Slice 7 — First chairman command, ADR and owner documentation

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `71e116a..8e795f1` (+ slice-7 hunks of 71e116a) | PASS | — | offline compile OK; minor: ignored cooperative name on re-run, outbox wait may last 30 s, NONE-web tests lose method security, confirm nadel_app INSERT grant on tenant in the final run |

## Slice 8 — End-to-end hygiene items

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `8e795f1..58c7a5c` | PASS | — | minor: restoreElectrician PUT results unchecked (warn), redundant toHaveCount, duplicate role id constant |

## Fix — test stand on one server (`chore(infra)`, owner request)

| Round | Commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `1a30d0a..01e564a` | PASS | — | minor (owner to decide): stand-init order Keycloak-then-.env without recovery hint; append without trailing newline; secrets in argv; first-chairman.sh missing-value handling and `${}` in names; link in Docker json log if interrupted; personal data in shell history; JVM memory; master realm login publicly proxied |

## Slice 9 — round 1 (2b2764b) — PASS

Minors (non-blocking, not fixed by rule):
- `infra/keycloak/nadel-realm.json:10` `editUsernameAllowed: true` is realm-wide; with the user-profile `username` edit permission `["admin","user"]` members can now rename themselves in the account console (collisions are still rejected; the backend does not read `preferred_username`). Narrowing `username` `permissions.edit` to `["admin"]` would keep the Admin API path only. Applies to the stand too.
- `MemberAccessTests.java:185` `Thread.sleep(500)` before a "no audit event" check; Awaitility `during()` would be sturdier.
- `IntegrationTest.java:50` `max_connections=400`: watch container memory as cached contexts grow.
- `ownerManual.test.ts` second test uses a loose regex.
- Stand must override the dev admin-client secret (stand-init.sh rotates it).

## Verify fix 1 — round 1 (7cfa226) — PASS

Minors (non-blocking, not fixed by rule):
- `FirstChairmanJarCommandTests.java:76-79` the "Cooperative created" and token-count assertions print the raw child output on failure (unmasked token of a throwaway test user); assert on `masked(printed)`.
- `FirstChairmanJarCommandTests.java:72-73` wrap the child in try/finally with `destroyForcibly` for an interrupted wait.
- Three identical `acceptLanguage()` helpers in ImportsController, ReportsController, ReportTemplatesController.

## CI fixes round 1 (ab28ff0, deaa33e) — PASS

Minors (non-blocking): gitleaks regexes are unanchored (fine for 14-char fixed strings; could be path-restricted); `FirstChairmanCommandExitTests.theApplicationRunnerDoesNothingUntilItRuns` adds little beyond coverage; its `warnings()` helper returns all events (rename to `messages()`).
