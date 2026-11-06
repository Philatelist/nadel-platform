# 020 QA notes

## FS 2.4.2 Readings clean-up: already fixed by feature 014, no code change

The meter specs clean up their data, and a rerun sweeps what an interrupted run left:

- `web/e2e/meters.spec.ts`: `cleanup()` (line 87) calls `cleanupMeterPlot` for each fixture (line 93); every test
  that creates a plot calls it from its `finally` (lines 176, 221, 270, 303; the submit/approve test starts at line 180 and its `finally` calls it at line 221).
  `test.beforeAll` (line 101) sweeps the plots numbered `E2E-M-` left by earlier runs (lines 105-109).
- `web/e2e/support/api.ts`: `cleanupMeterPlot` (line 238) rejects pending readings, deletes or retires each meter,
  ends the ownership and archives the plot; it never throws.
- `web/e2e/meters-acceptance.spec.ts`: `cleanLeftovers` (line 154) is run before the specs (`beforeAll`, line 169), and each
  acceptance spec cleans up in its `finally` (for example lines 238, 269, 375, 474).

## FS 2.4.4 Flaky "a member from creation to archive"

`web/e2e/acceptance.spec.ts` now waits for the archive response and for the filtered list request before it asserts
"Nothing found". Not run in this slice. The final slice must prove it with:

`pnpm --filter web exec playwright test acceptance --grep "creation to archive" --repeat-each=10`
