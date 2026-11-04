# Review log — 018-cash-book-expenses-reconciliation

## Baseline failures

- Base commit: `c7419a6` (`main`), command: `make check`
- Result: 0 failing (backend 3952 tests green, coverage floor met; clients typecheck, lint and tests green: web 908, shared 130, ui-tokens 56, api-client 9)

## Slice 1 — Money accounts, opening balances and member payments in the journal

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `2ad657e..64b6fcf` | PASS | — | minor: movements `closingBalance` not capped at today for a future `to` (harmless, no future movements); offset pagination with a self left-join for `reversed` — revisit at scale |

## Slice 2 — Expenses with receipt attachments, categories, no negative cash

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `64b6fcf..bcae29e` | PASS | — | minor: IOException on upload read → 500; JFXX thumbnail / ICC kept whole (theoretical); list decrypts up to 200 counterparties per page; reversal reason stored as plain audit value (user content, as other reversals) |

## Slice 3 — Handovers to the bank and other income

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `bcae29e..cd1e699` | PASS | — | minor: TreasuryController Javadoc wrapping; a missing bank account would give 500 not 409 (accounts are always seeded); `useState` import order in CashbookPage |

## Slice 4 — Cash count, its act and the booked difference

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `cd1e699..d5ec2fc` | PASS | — | minor: over-long Javadoc lines in treasury package-info and TreasuryController; CashCounts:323 future check could note that the clock zone is Kyiv |

## Slice 5 — Monthly bank reconciliation

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `d5ec2fc..8513268` | PASS | — | minor: a line dated in month M shifts the opening of later reconciled months, which stay «Звірено» (FS reopens only the month of the line; same family as the past-month reversal limit — add to the treasury README note for the owner); `reopened` stays on a re-reconciled month — the UI must show it only for NEEDS_RECHECK |

## Slice 6 — Reconciliation act for the board and the member

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `8513268..6439480` | PASS | — | minor: a plot with no ledger rows always gets 400 from INVALID (the form shows «Невірний період»); plotStatement calls debtsAsOf twice (fine for one plot). Reviewer did not run the web checks (orchestrator ran them before the commit: web 958 green) |

## Slice 7 — Reports: cash book, bank movements, income and expense, expenses

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `6439480..a070e08` | PASS | — | minor: OPENING/DAY/CLOSING rows count against the 20 000-row cap (worth a README sentence); TemplateValues:103 over-long Javadoc; the «Витрати» total skips reversed expenses, so it can differ from income-expense when an expense and its reversal fall in different periods (per spec, documented) |

## Slice 8 — Demo data, documentation and guides

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `a070e08..74c731d` | PASS | — | minor: Ukrainian demo literals rely on UTF-8 sources; ADR 0019 title is long; CI demo-days list needs DemoTreasuryTests (owner, .github) |
