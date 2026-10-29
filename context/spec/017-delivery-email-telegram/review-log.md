# Review log — 017-delivery-email-telegram

## Baseline failures

- Base commit: `1fef5d0` (`main`), command: `make check`
- Result: 3 failing (backend 3650 tests, 0 failures; contract, format, lint, client typecheck green; web vitest 3 timeouts). The run shared the machine with four design agents, so the timeouts may be load-related.
- `web: RunPreviewDialog.test.tsx > RunPreviewDialog > books with the digest and shows the created count` (timed out in 5000ms)
- `web: RecalculationStep.test.tsx > RecalculationStep > previews again at once when the preview is outdated and says so` (timed out in 5000ms)
- `web: RecalculationStep.test.tsx > RecalculationStep > shows another problem as its message and keeps the preview` (timed out in 5000ms)
- e2e not part of the baseline (`make check` excludes it).
