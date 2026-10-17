# Review log — 004-role-names

## Baseline failures

- Base commit: `23451fb` (`main`), command: `make check` (gen, format-check, lint, backend `./mvnw verify`, typecheck, `pnpm -r test`)
- Result: 0 failing (backend 239 tests; ui-tokens 56, shared 5, web 45 unit tests). Backend line coverage 1,036 / 1,105 (93.8 %), floor 0.90.
- `make e2e` not part of the baseline (ports; not in `make check`); it was 29/29 green on the merged 003 branch.

## Slice 1 — Standard names shown from the new flag

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `a35ddff..54971bf` | PASS | — | minor: contract ahead of server until Slice 2; `nameCustomized` not asserted on `/me` and member list; cleanup check misses the flag; list test relies on ordering |

## Slice 2 — Rename and restore any role, audited

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `54971bf..138490e` | MAJOR_ISSUES | M1 web guard "submit stored name when the standard name is untouched" (RolesPage submit) has no unit test | minor: restore also sends unsaved description/permission edits; restore ROLE_NAME_TAKEN shown under the name field; no-audit checks use a fixed sleep; uk BASE_ROLE_IMMUTABLE wording; audit value mapping applied to every entity type |
| 2 | + `f40a963` | PASS | M1 resolved (`nameToSubmit` helper + 6 tests) | minor: helper takes the displayed name as an argument; round 1 minors open |

## Slice 3 — Permissions unchanged by renames, e2e and docs

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `f40a963..02a9b37` | PASS | — | minor: e2e second context not closed on failure; e2e finally assumes no open dialog; `restoreSeededBaseRoles` does not restore descriptions |
