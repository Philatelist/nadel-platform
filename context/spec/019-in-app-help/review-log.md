# Review log — 019-in-app-help

## Baseline failures

- Base commit: `aacd2a4` (`main`), command: `make check`
- Command: `cd backend && ./mvnw verify` and `pnpm -r test`
- Result: 0 failing (backend verify exit 0; clients 155+8+1+2 files, 983+130+9+56 tests passed)

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

## Slice 9 — Content: payments and cash, documents and delivery, reports

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `a8308bb..2de1f4b` | PASS | — | all `{{ui:}}` keys resolve; shots read-only (`payment-reverse` opens the dialog, `announcement-form` previews). minor: "токен" wording in `deliveries`; en `payment-card` should say the receipt mark is Ukrainian «СТОРНОВАНО»; `payment-card` shots open the first payment row (works with today's demo; a `rowText` filter would be more robust) |

## Slice 8 — Content: tariffs and charges, meters and readings

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `7dbb8be..90d32b6` | MAJOR_ISSUES | M1 `meter-card` worked example ends with figures (65/66 kWh) not derivable from the demo meters | minor (sent along as a safety fix): `tariff-recalculation-step`/`reading-recalculation-step` click «Зберегти» — read-only only if the edited version/reading is in use. Other minor: hand-typed interface names in 3 places; "38 лічильників" unverified; `[!important]` boxes under «Хто бачить» in `tariff-form` |
| 2 | + `f031138` | PASS | M1 resolved; save-click shots confirmed read-only on demo data | minor: awkward YAML comment wording in `meter-card` |

## Slice 10 — close-out (migration map, HTML guide retired, docs, backend test repointed)

| Round | Commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `c8c1199` + deletions in `f031138` | PASS | — | every old heading mapped once; 8 "added in close-out" rows verified in uk+en; 43 new `{{ui:}}` keys resolve; test intent kept. minor: owner manual points to `infra/stand/` files that are not tracked in the repository (owner decision) |

## Fix — unit test setup (verify)

| Round | Commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `e05be47` | PASS | — | real test-setup bugs, no assertion weakened. minor: duplicate beforeEach; no Layout test of the «?» placement |

## Fix — help module under vite dev (verify)

| Round | Commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `7e87b1f` | PASS | — | root cause confirmed; dev reload via watcher intact; build check unchanged |

## Fix — the chairman sees every help page (FS 2.6, verify)

| Round | Commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `014b702` | PASS | — | electrician and member paths unchanged. minor: typographic apostrophe; assumption should name the treasurer (also has MEMBERS_VIEW) |
