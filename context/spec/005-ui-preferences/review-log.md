# Review log — 005-ui-preferences

## Baseline failures

- Base commit: `5999343` (`main`), command: `make check`
- Result: 0 failing (backend 259 tests; ui-tokens 56, shared 5, web 65 unit tests). Backend line coverage 1,065 / 1,134 (93.92 %), floor 0.93.
- `make e2e` not part of the baseline; it was 33/33 green on the merged 004 branch.

## Slice 1 — Theme switch

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `b5e85b4..a33b050` | MAJOR_ISSUES | M1 `MapPage` reads colours once and would not follow an in-app theme change | orchestrator check: `MapPage` is not routed or imported anywhere (`web/src/App.tsx` routes `/` → `/account`; the map is phase 6), so no user can reach it; sent back to the reviewer with this evidence instead of a fix. minor: storage key duplicated in script and module without a test; ThemeSwitch group label not asserted; no cross-tab `storage` listener; tech spec names `theme.tsx` |
| 2 | (no fix) | PASS | M1 withdrawn: `MapPage` is unreachable | note for phase 6: the map must re-read colours on theme change |

## Slice 2 — Cooperative name in the header and the tab title

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `a33b050..39719cd` | PASS | — | minor: cached-404 tab title on Member page; at phone width the name may shrink or the Menu button may wrap (Slice 4 e2e to check at 375–390 px); import order in MemberPage |

## Slice 3 — Base role descriptions in the user's language

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `39719cd..413ee10` | MAJOR_ISSUES | M1 `RolesPage.restoreName` sends the displayed (catalog) description, customising a standard description in Ukrainian | minor: `restoreDescription` sends the typed name and permissions; migration-rule test copies the SQL condition; fixed sleeps for no-audit checks; a custom description literally `base:…` renders as catalog text; duplicate `roleDescription` call |
| 2 | + `c99e535` | PASS | M1 resolved (`restoreNameDescription` → `descriptionToSubmit`, tests) | minor: thin wrapper; no test for an already-customised description; round 1 minors open |

## Slice 4 — End-to-end flows, phone header fix and coverage floor

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `c99e535..0abc642` | MAJOR_ISSUES | M1 `Layout.tsx:59` title keeps `order-1` from md up, so the desktop header shows the cluster before the title; M2 e2e cleanup uses a non-waiting `count()` and §2.3 #4 has no restore | minor: phone Tab order differs from visual order (Menu reached after Sign out); class order; audit row check may match older runs; context not closed if the first `try` fails |
| 2 | + `46b47b5` | PASS | M1, M2 resolved (desktop order restored + e2e position check; waiting restore helper in both `finally`) | minor: class order; audit row may match an older run |
