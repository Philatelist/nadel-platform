# Review log — 016-invoices-and-documents

## Baseline failures

- Base commit: `a668a03` (`main`; the spec branch is cut from `5287657`, which adds only docs/features/017 and 018), command: `make check`
- Result: 0 failing (backend 3301 tests, 0 failures; contract, format, lint, client typecheck and tests green)
- e2e not part of the baseline (`make check` excludes it).

Reviews deferred by the owner (28.10.2026) until after the combined verify run; round 1 of every slice started together after verify run 1.

## Slice 1 — print a cash receipt (documents module, PDF engine, amount in words)

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `07a0782..c146796` | PASS | — | minor: payer's full name printed to a member who owns every plot of the group (TC allows; tasks.md wording says name/note only with MEMBERS_VIEW — doc alignment); receipt /ID from content.toString() (stable today); CashReceiptQueries loads all billing months. Reviewer read the commit via git show (worktree misnamed by the orchestrator) |

## Slice 2 — an invoice for one plot on request, with the NBU QR code

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `c146796..d5b8178` | PASS | — | 206 tests, 1 known error (isolation test, fixed in c6844a5). minor: `latest` window flag relies on partition-key filters (comment); asOf driver type comment; missing correction target falls back to TARIFF silently (debug log). Backend source of QR, calculator, movements, access, snapshots checked; web and PDF layout code not opened |

## Slices 3 and 4 — month invoice build, print set, debt certificates

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `d5b8178..cd12fba` | PASS | — | 307 tests, 2 known failures (fixed in c6844a5); measurement in the review worktree 3.4 s / 2.6 s / 114 ms. minor: facade printPdf not REPEATABLE READ when it starts the tx; two connections per build request (pool note for ADR 0017); no concurrent-annul test; annul requireNonNull(memberId) → 500 instead of 403 for an unlinked caller |

## Slice 5 — member invoices and receipts, demo data, the 015 screen fixes

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `cd12fba..9745ab5` | PASS | — | minor: comment on the exact-match demo IBAN replacement; DemoDocuments logs only the exception class; splitLatest relies on server sort order. Backend 29 tests green; web 2 known AccountPage failures (fixed in 39eb540) |

## Verify fixes

| Round | Commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| — | `c6844a5`, web fix | not reviewed separately | — | test-only changes plus one i18n key removal; proven by the re-run (backend 30 targeted tests green; clients 853/853) and covered by Slice 7 and the full suite |

## Fix — CI: invoice month build slow under RLS (InvoiceLedgerQueries.CHARGES)

| Round | Commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `5cf5ed9` | PASS | — | equivalence checked case by case; 32 targeted tests green. minor: diagnosis stated as fact (an EXPLAIN note would make it verifiable); no plan/timing pin beyond the 60 s ceiling; same expression join remains in LedgerReportQueries (013) |
