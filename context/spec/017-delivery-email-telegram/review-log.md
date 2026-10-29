# Review log — 017-delivery-email-telegram

## Baseline failures

- Base commit: `1fef5d0` (`main`), command: `make check`
- Result: 3 failing (backend 3650 tests, 0 failures; contract, format, lint, client typecheck green; web vitest 3 timeouts). The run shared the machine with four design agents, so the timeouts may be load-related.
- `web: RunPreviewDialog.test.tsx > RunPreviewDialog > books with the digest and shows the created count` (timed out in 5000ms)
- `web: RecalculationStep.test.tsx > RecalculationStep > previews again at once when the preview is outdated and says so` (timed out in 5000ms)
- `web: RecalculationStep.test.tsx > RecalculationStep > shows another problem as its message and keeps the preview` (timed out in 5000ms)
- e2e not part of the baseline (`make check` excludes it).

## Slice 1 — channel settings, the cooperative's mailbox and bot, with test buttons

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `b91832e..515ec7b` | PASS | — | backend Verify 124 tests green in the review worktree. minor: `TestLetters.java:36` Javadoc "never throws" but only MessagingException/UnsupportedEncodingException caught; `DeliverySettingsService.java:240,245` byte[] change detection by reference (works, needs a comment); SMTP host is free text → admin can point the test letter at internal hosts (SSRF note for the README / later restriction) |

## Slice 2 — link a member to Telegram, choose the channel

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `515ec7b..b811a37` | PASS | — | 38 new tests green in the review worktree. minor: `BotWorker.handle` takes the update before answering, so a failed answer is lost (recorded "at most once" deviation); `MemberNotifications.askBotName` logs `e.getMessage()` (safe while TelegramException messages stay fixed); `TelegramLinks.redeem` concurrent double link → unique-index error logged, not answered (rare); `BotInputLimiter` map cleaned only above 10,000 entries; `V29_1` naming departs from `V{N}` (recorded) |

## Slice 3 — send the month's invoices, the delivery screen, paper copies with a link QR

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `b811a37..346a693` | MAJOR_ISSUES | M1 `DeliveryDispatcher.run`: an unexpected exception while preparing/sending a row is logged and skipped; the row stays QUEUED with attempts and next_attempt_at unchanged, `nextDue` returns now → a new job every second, never FAILED, 3-attempt rule bypassed | 38 slice tests green. minor: `DeliveryQueue.schedule` job id per second drops a wake in the same second as a finished job (sweep catches it within a minute); `DeliveryRows.markMonthPrinted` marks all TO_PRINT rows (document it in OpenAPI); Telegram 400 → NO_CONTACT (recorded deviation) |
