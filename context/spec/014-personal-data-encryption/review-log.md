# Review log — 014-personal-data-encryption

## Baseline failures

- Base commit: `67e3916` (`main`), command: `make check`
- Result: 0 failing (backend 2840 tests, 0 failures; contract, format, lint, client typecheck and tests green)
- e2e not part of the baseline (`make check` excludes it); the full e2e run 40571131162 on `main` had 3 ownership failures (see pipeline-log).

## Slice 1 — privacy module, keys and start-up checks

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `e1fa7ea..2ad5d2a` | PASS | — | minor: TenantKeyCache duplicate load on a concurrent miss (harmless); decrypt-failure log carries table/column/row id (internal ids only); KeyCheckCallback rewrap ordering fine |

## Slice 2 — member contacts stored encrypted, exact search, sign-in and import matching

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `2ad5d2a..9140a23` | PASS | — | minor: IntegrationTest static bean reference (sequential runs only); replacePhoneIndex DELETE for new members; V23 guard vs migration blank-value definitions differ (whitespace-only legacy values would abort V23 clearly); test copy of PhoneNumber rules may drift |

## Slice 3 — audit values encrypted and hidden without MEMBERS_VIEW

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `9140a23..25852a7` | PASS | — | minor: personal-field set duplicated in members and audit migration 24 (documented); FieldChange.snakeCase javadoc promises an IllegalArgumentException it does not throw (FieldRef validates the result) |

## Slice 4 — pending Excel import plan encrypted

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `25852a7..a46cd13` | PASS | — | minor: StoredPlan javadoc line too long; check leftover imports in ImportRun; ImportCommitter.commit javadoc lacks the 500 PERSONAL_DATA_UNREADABLE outcome |

## Slice 5 — water connection guard on plot archive and common land

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `a46cd13..7130cc6` | PASS | — | minor: plots README added lines exceed the wrap width; archive returns empty errors (consistent with PLOT_HAS_METER) |

## Slice 6 — reliable e2e: search debounce, ownership specs, meters clean-up

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `7130cc6..5ea5800` | PASS | — | Playwright not run (ports held by the owner's stack); minor: unused `active` field and unexplained `.reverse()` in cleanupMeterPlot; no test for the path guard (unmount is tested); copy-pasted "resets paging" comment on Meters/ReadingsRound pages |

## Slice 7 — docs and guides

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `5ea5800..643fef6` | PASS | — | minor: en board guide audit paragraph quotes «приховано» where the English UI shows "hidden"; tasks.md claims a status update of the 014 feature doc that has no status field; members/audit screenshots stale until `make guide-shots` runs on this branch |

## Slice 8 — feature testing and regression

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `643fef6..bdf737e` | PASS | — | minor: upgrade test does not assert the queued publication's notes are sealed and omits the chairman e-mail from the scan; no positive control on log capture; `delete from import_run` unscoped; members-search e2e second positive assertion may pass on the previous results (Java test covers it strictly) |
