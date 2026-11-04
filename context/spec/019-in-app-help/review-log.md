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
