# Review log — 001-members-access

## Baseline failures

- Base commit: `39380d2` (`main`), command: `cd backend && ./mvnw -B verify`
- Result: 0 failing (7 tests: ModularityTests 2, TenantIsolationTests 5; BUILD SUCCESS, coverage checks met). Run with JDK 25 (`JAVA_HOME=/opt/homebrew/opt/openjdk@25/...`). First attempt with the default JDK 17 could not compile (`release version 25 not supported`).

## Slice 1 — Sign-in is linked to a member; My account shows own card, roles and permissions

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `caf2a12..f76322d` | MAJOR_ISSUES | M1 member-lookup failure during sign-in likely answered 401 instead of 500 (default bearer failure handler rethrows `AuthenticationServiceException`); no test | minor: operator gets 404 not 403 on `GET /members/{id}`; operator token keeps a `tenant_id` claim and `/plots` has no `PERM_*` check (pre-existing, ADR 0008); `log.debug(..., ex)` in the catch-all may log row values; `/me` email from claim |
| 2 | + `5ae986c` | PASS | M1 resolved (failure handler set after configurer; bad tokens still 401 via `MemberLinkingTests.unknownTokenIsUnauthenticated`) | minor: lookup/JWKS outage now answered 500 with no server-side log line (add a PII-free log in the `AuthenticationServiceException` branch); JWKS-outage path untested |

## Slice 2 — Members list and read-only member card, permission-gated navigation

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `f76322d..7ad1272` | PASS | — | minor: phone match triggers on any digit in `q` (e.g. `ivan2@example`); demo-data comment claims Secretary permissions reset each run (they are only inserted); MEMBER grant `id > …04` would re-grant to UI-created members on re-run; search box not re-synced when URL changes externally; page past the end shows "No members yet" without pagination; "Back to members" link shown to plain members |

## Slice 3 — Add, edit, archive and restore members, with every change recorded in the audit log

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `5ae986c..ca5ea40` | MAJOR_ISSUES | M1 phone field errors land on the wrong row when blank rows are filtered before submit (`MemberForm.tsx`) | minor: concurrent archive/restore of the same member can write two audit entries (read before lock); record `toString()` of `AuditEvent`/`Actor` would print personal data if ever logged; failed audit listener retried only at restart; form shows nothing when field errors match no rendered field; "Saved" reappears on reload (location.state); duplicate react-router imports; pipeline commit subjects are not Conventional Commits (owner decision) |
| 2 | + `81e5be8` | PASS | M1 resolved | minor: `remapPhoneErrors` has no unit test (no web test runner); exporting a helper from a component file can weaken Fast Refresh |

## Slice 4 — Audit log screen

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `81e5be8..eb74fb7` | PASS | — | minor: `deliveryChannel` change values shown as raw codes (TELEGRAM/PAPER) instead of localized labels; empty URL params (`?entityId=`) sent as '' and break the filtered/empty-state logic; page past the end shows the empty message; `Intl.DateTimeFormat` built per row; append-only fixtures accumulate in the test DB |

## Slice 5 — Roles — permissions list, base and custom roles, assigning roles to members

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `eb74fb7..f03fd4c` | MAJOR_ISSUES | M1 `RolesTests` leaves the shared demo Member role description changed (no restore in `@AfterEach`) | minor: custom role named like a localized base role ("Скарбник") is not refused, so two same-looking names appear; Chairman description not editable from the UI (Save disabled) though the backend allows it; role deleted concurrently with assignment → 500 instead of 404 (unmapped FK race); `RoleInput.name` maxLength checked before trim |
