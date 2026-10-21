# Review log — 009-charge-types-and-tariffs

## Baseline failures

- Base commit: `eca55b9` (`main` = `origin/main`), command: `make check` in a fresh detached worktree.
- Result: 0 failing — backend 1,096 tests, 0 failures; coverage check met (line coverage 3,871 / 3,958 = 0.9780 vs floor 0.97); client tests ui-tokens 56, shared 67, api-client 7, web 241.
- `make e2e` not part of the baseline; it was 81/81 green on the 008 branch.

## Slice 1 — Permissions and charge types

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `a6cdf22..1b100ae` | PASS | — | minor: audit label/old name of an unrenamed seeded type is the stored English default; concurrent creates may share a position (harmless, ordered by position, id); `ChargeTypeAuditTests` counts all CHARGE_TYPE entries after a sleep (could flake on a slow machine); test cleanup `delete from charge_type` will hit the RESTRICT FK once Slice 4 adds demo tariffs; Ukrainian literals in Java tests fall under the default-names exception (name it in the CLAUDE.md proposal); Treasurer description not mentioning tariffs (kept unchanged by design) |

## Slice 2 — Tariffs and versions

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `1b100ae..70bad53` | MAJOR_ISSUES | M1 TariffsPage ignored the tariffs query's loading/error state and showed "No tariffs yet" | minor: audit `chargeType` value/label of an unrenamed seeded type is the stored English default; create form offers only active charge types (server refuses only archived); `Instant.now()` instead of the injected `Clock` for `createdAt`; list vs card plotCount when versions switch between meter-based and other bases; rate shown with the locale comma ("50,0000") while the spec quotes "50.0000" |
| 2 | + `ec4e615` | PASS | M1 resolved (nested QueryBoundary; pending and error tests) | minor: two separate error states if both lists fail |

## Slice 3 — Which plots a tariff applies to

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `ec4e615..6ed5b5b` | PASS | — | minor: ScopeEditor shows nothing for list-level field errors (`plotIds`/`exclusions` INVALID or TOO_LONG — the UI never sends such requests); unknown field codes fall back to the INVALID text; row-indexed server errors shift when a row is removed after a failed save; a fully-qualified `Basis` in TariffDtoMapper; a memoised count in a `long[]` in TariffAssembler |

## Slice 4 — Expected amount on the plot card and in "My account"

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `6ed5b5b..96d1698` | PASS | — | minor: the demo exclusion of plot 28 returns on a rerun of the repeatable demo script if the chairman removed it (demo data only); the account title key is `tariffs.expected.myTitle` instead of the tech spec's `account.myCharges`; `DemoTariffData` gives a bare NPE if its resource is missing; fully-qualified `java.util.Objects` in `ExpectedCharge`; a dynamic `fireEvent` import and a no-op `userEvent.tab()` in a panel test; slice commit subjects are not Conventional Commits (the squash title is); the panel shows the empty state on archived plots |

## Slice 5 — End-to-end flow, docs and coverage

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `96d1698..f22d77f` | PASS | — | minor: the member "own plots only" check filters headings by name (counting all would be stricter); e2e checks English only; the charge type id is looked up after the UI shows it (a failure in between leaves a uniquely named row); two long README lines |

## Slice 6 — Feature Testing & Regression

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `f22d77f..a43e143` | PASS | — | minor: one e2e title says "§2.1 empty list text" for a §2.6 #1 check; the treasurer's card-level absence of controls is left to unit tests; some Ukrainian UI texts (§2.2 #1, §2.3 #5, §2.1 #3) are covered by unit tests, not e2e; the "refused change writes no audit entry" check reads right after the 403 (weak negative); per-test members are not removed (as in other acceptance classes); a fully-qualified `java.util.Arrays`; `rows.last()`/`nth(-2)` assume no concurrent charge types on the shared dev DB |

## Fix — §2.2 #1 "за сотку" in the tariffs list (verify)

| Round | Commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `a43e143..4f7be6e` | MAJOR_ISSUES | M1 no automated test asserts the Ukrainian "За сотку · Щороку" row and "діє з" in the list; the cited evidence does not cover it | minor: the criterion quotes lowercase words while the UI capitalises segments; the §2.6 #1 screenshot cuts off the Вода card (the e2e covers it) |
