# Review log — 019-in-app-help

## Baseline failures

- Base commit: `aacd2a4` (`main`), command: `make check`
- Result: pending — deferred to Phase 4 (user instruction: no tests until the end)

## Slice 1 — Help section with one real page, signed-in

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `14451ea..a8e2bcc` | PASS | — | static review (no tests, per user). minor: issues warned twice (uk+en load) — rework in Slice 5; `DUPLICATE_ID` unreachable (kept as safeguard); redundant template literal in `Layout.tsx`; `useHelpMeta` skipped on not-found |

## Slice 2 — «?» button, side panel and what each user sees

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `a8e2bcc..2b6b5e1` | PASS | — | static review. minor: focus-restore cleanup runs twice under StrictMode; inline ref callback; no click-outside close. Note: a chairman without a plot does not see `member`-only pages (spec definition; demo chairman owns plot 1) |

## Slice 3 — Search

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `2b6b5e1..a5dd1c3` | MAJOR_ISSUES | M1 typo test query is 2 edits away (fails with 1-edit fuzzy) | minor: misleading `processTerm` comment; JSON round-trip test does not round-trip; duplicate regex; `useMemo` on viewer identity |
| 2 | + `59e3d86` | PASS | M1 resolved | remaining minor notes of round 1 stand |

## Slice 4 — Public help and search-engine readiness

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `59e3d86..dc7c798` | PASS | — | static review. minor: `returnPathOf` allows `/\host` (not exploitable today, value is the app's own path); public help flashes for a signed-in user while OIDC loads; `useHelpMeta` restore when previous is null; `existsIndex` reads the whole file |

## Slice 5 — The completeness check

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `dc7c798..9aed130` | PASS | — | static review; route/menu coverage only in vitest judged sufficient (CI runs `pnpm -r test`). minor: no test for `DUPLICATE_ID`; `UNKNOWN_LINK` accepts a link to a page that exists only in the other language (flagged by `MISSING_TRANSLATION`); build needs `pnpm gen:api` first |

## Slice 6 — Screenshots and the board guide PDF from the help

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `9aed130..7d356a9` | PASS | — | static review. minor: old `board-guide.html` still present (Slice 10 removes); refusal message prints the cooperative name; TOC without page numbers; a page with no shots is skipped silently. Read-only depends on front-matter `click` steps never pressing a save button — check in content slices |

## Slice 7 — Content: getting started, plots and members, settings

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `7d356a9..a753b52` | PASS | — | static review with read-only key/heading scripts. minor: "a copy of the database" wording in `member-form`; `getting-started` uk/en `{{ui:}}` sets differ (language wording); `plot-card` "no buttons" vs downloads elsewhere |

## Slice 10 — Content: how the system calculates, member pages (content part)

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `a753b52..a8308bb` | MAJOR_ISSUES | M1 electricity-by-reading rule and examples on a board-only page (`how-charges-months`), invisible to members | digits of sections 18–31 checked, none lost. minor: `member-bill` uses `audit.outcome.RESOLVED` key for «Нараховано»; "database" wording on member-visible pages; `how-meters-tree` permissions vs `meter-tree`; treasurer wording on `how-payments-allocate` |
| 2 | + `7dbb8be` | PASS | M1 resolved | round 1 minor notes stand |
