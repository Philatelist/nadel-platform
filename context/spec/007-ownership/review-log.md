# Review log — 007-ownership

## Baseline failures

- Base commit: `0af7859` (`main` = `origin/main`), command: `make check` in a fresh detached worktree.
- Result: 0 failing tests (backend 427 tests, 0 failures, 0 errors; client checks green), but **the JaCoCo coverage check failed locally**: line coverage 1,352 / 1,426 = 0.9481 < floor 0.95 → `BUILD FAILURE`. CI on the same commit (run 39166627032) passed the coverage check, and the 006 branch measured 0.9586 locally. The floor is environment-sensitive; 007 must end clearly above it on CI and locally.
- `make e2e` not part of the baseline; it was 57/57 green on the 006 branch.

## Slice 1 — Cooperative settings page and the SETTINGS_MANAGE permission

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `4b3101f..c9a91b3` | MAJOR_ISSUES | M1 the audit record of a settings change shows the stored English label "Cooperative settings" in the Ukrainian UI | minor: audit entity-type filter lacks SETTINGS; concurrent identical saves can write two audit entries (no row lock); SettingsPage comment mentions a `key` that is not set |
| 2 | + `2d5914c` | PASS | M1 resolved (SETTINGS entries show the catalog title) | minor notes from round 1 unchanged |

## Slice 2 — Owners recorded and shown (read only)

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `2d5914c..2e95b90` | PASS | — | minor: search placeholder does not mention owner names; the 200-match cap on owner-name search is silent; import order in PlotQueries; unchecked member lookup in OwnershipQueries (safe by the composite FK) |

## Slice 3 — Change of owner

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `2e95b90..00c30ed` | PASS | — | minor: an empty change on a plot with no owners is accepted and audited; running share sum can overflow `int` for many large unrelated denominators (order-dependent, unrealistic); tests compute "today" separately from the server clock (could flake at Kyiv midnight); `lockActive` name also returns archived members; `basis` lacks `@Nullable`; client duplicate-member branch not unit-tested |

## Slice 4 — Corrections

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `00c30ed..36e99ac` | MAJOR_ISSUES | M1 moving an owner's start date into the previous (different) owner's period is reported as SHARES_NOT_ONE on `share` instead of a message under the date (spec 2.3 AC 4); only the same-member overlap is tested | minor: stray period in an OwnersPanel comment; "Correct" also requires MEMBERS_VIEW (dialog uses the member picker); no client check end < start; end-before-start is a 409 not 400. Fix overlaps Slice 5 files (OwnershipCommands) → applied after the Slice 5 commit |

## Slice 5 — Guards on members and plots

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `36e99ac..96ced1f` | PASS | — | minor: `problem.PLOT_HAS_OWNERSHIP_HISTORY` top-level text says "deleted" though the code also covers the kind change (the form shows the field text); no test for archive racing a role change; README claim "an archived current owner cannot result" did not cover corrections — a correction could clear `validTo` on an archived member's past record (Slice 4 code, routed into the Slice 4 fix round); `OwnershipOverlapMappingTests` belongs to Slices 3/4 behaviour |
| 2 | + `edd95b3` | PASS | M1 resolved (overlap with a different owner reported on the moved date as OWNERSHIP_OVERLAP); archived-member rule correct | minor: one test asserts only the code for the moved-away date branch; a co-owned record moved into an unowned period reports on `share`; archived no-op correction with no end date gets 409 (legacy data only); MEMBER_ARCHIVED wording under the end date |
