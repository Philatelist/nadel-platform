# Review log — 015-payments-and-allocation

## Baseline failures

- Base commit: `75db154` (`main`), command: `make check`
- Result: 0 failing (backend 2969 tests, 0 failures; contract, format, lint, client typecheck and tests green)
- e2e not part of the baseline (`make check` excludes it).

## Slice 1 — ledger tables, permissions and the pure allocation rules

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `6a3d4a3..23557e8` | PASS | — | minor: release-cap check in `allocation_guard` relies on the per-plot lock of later slices (service must lock before releasing); allocation to a reversed payment caught by the deferred backstop when the reversal is in the same transaction |

## Slice 2 — enter a payment for one plot, list and card

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `23557e8..faf6564` | PASS | — | minor: Javadoc over 120 columns in `PlotDebtCache`/`PlotStatusEntry`; `Money.parse` and `YearMonth.parse` rely on the contract pattern (a bypass would be 500, not 400) |

## Slice 3 — one payment split over several plots of one payer

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `faf6564..ec792e9` | PASS | — | minor: split proposal reads unlocked non-part plots (protected by digest and sum check); `PaymentQueries` javadoc over 120 columns; payer without plots gets `NOT_FOUND` on `split.payerMemberId` |

## Slice 4 — reverse a payment, reallocate it, allocate prepayment by hand

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `ec792e9..2bf814c` | PASS | — | minor: `LedgerAuditPublisher` Javadoc line over wrap width; reallocate preview resolves the plot without a lock (save re-reads under the lock) |

## Slice 5 — automatic release on charge corrections and automatic allocation of prepayment

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `2bf814c..08e3d1d` | PASS | — | deadlock analysis: no cycle (only `MonthClosing.close` takes month FOR UPDATE and holds no other ledger lock; a FOR SHARE on an already share-locked row does not queue behind it). Optional hardening: take plot ledger locks in `book` before the plan's month locks. minor: settings README line over wrap width; RecalculationService javadoc could name the `MonthClosing` argument |

## Slice 6 — plot account, member account, billing months received and the permission matrix

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `08e3d1d..f6b5f41` | PASS | — | minor: a member receives the free-text reversal reason (not forbidden by the spec; could be gated by `PAYMENTS_VIEW`); account merge sorts by day only (same-day order by kind) |

## Slice 7 — reports and templates after payments

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `f6b5f41..592d62a` | PASS | — | minor: payments report street filter binds one large `IN (:plotIds)` (other queries chunk by 500); `PREPAYMENT_AS_OF` with all plots runs once per chunk; `TemplateField` javadoc wrapping |
