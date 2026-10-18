# Review log — 006-plots-registry

## Baseline failures

- Base commit: `48cd8e0` (`origin/main`), command: `make check`
- Result: 0 failing tests (backend 278 tests, 0 failures, 0 errors), but **the JaCoCo coverage check failed**: line coverage 1,084 / 1,154 = 0.9393 < floor 0.94 → `BUILD FAILURE`. The 005 branch measured 1,085 / 1,154 = 0.9402 on the same code, so one line is covered only in some runs (timing-dependent path) and the 0.94 floor is flaky on main. 006 must end clearly above the floor; the flakiness is reported to the owner.
- `make e2e` not part of the baseline; it was 48/48 green on the merged 005 branch.

## Slice 1 — Plot records in the database, demo plots, map endpoint moved

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `f36a3d5..aeba3dd` | PASS | — | minor: map query orders by plain text; demo upsert does not touch `updated_at`, and rolling dev back to the previous release would fail its old demo script (dev only); SQL function without `search_path`; mobile legend shows GREY as "Common land" only |

## Slice 2 — Plots list and plot card

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `aeba3dd..441f9b1` | PASS | — | minor: page past the end shows the empty state; search box does not follow external URL changes; malformed id shows the error state; area wording "6,5 сот." vs spec examples (picked up in Slice 3 with Ukrainian plural forms); dead no-menu branch in Layout; 005 spec text about members having no menu is now historical |

## Slice 3 — Create and edit plots, audited

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `441f9b1..9cc8a6d` | MAJOR_ISSUES | M1 an editor with PLOTS_EDIT but without PLOTS_VIEW silently erases the cadastral number and building flag (fields hidden from them, PUT replaces all) | minor: form sends area as a JSON number (contract choice); `maxLength` checked before trimming; audit shows area with "." in Ukrainian; `violates()` message fallback; string check for area > 0. Fix overlaps Slice 4 files → applied after the Slice 4 commit |
| 2 | + `27bfa1f` | PASS | M1 resolved (all writes need PLOTS_VIEW + PLOTS_EDIT on server and web; notes special case removed) | minor: tasks.md Slice 3 line still says PLOTS_EDIT; long description line in openapi |

## Slice 4 — Archive, restore and delete

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `9cc8a6d..e2e7631` | PASS | — | minor: no `@Version`/row lock, so concurrent archive/restore may write two audit entries and archive-after-delete may 500; PlotPage tests only check button visibility; `setQueryData` + invalidate double refetch; a test helper builds SQL from constants |

## Slice 5 — End-to-end flows and coverage floor

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `27bfa1f..397c4da` | PASS | — | minor: e2e captures the plot id after the heading check (a failure in between leaves a local leftover); cleanup errors can mask the original failure; archived delete asserts any 4xx instead of 409; treasurer card check uses a plot without notes |
