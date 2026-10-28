# 018. Cash book, expenses and reconciliation

Roadmap phase 3 (moved after phase 4 by the owner on 28.10.2026). Input for `/feature-pipeline`. Requires 011 (billing
months), 013 (reports), 014 (`privacy`), 015 (payments) and 016 (documents engine).

## 1. Goal

Until now the system knows what members owe and pay. This feature adds where the cooperative's money is and where it
goes:
- the **cash** the treasurer holds;
- the **bank account**;
- money handed from cash to the bank;
- the cooperative's **expenses**;
- **reconciliation**: the treasurer counts the cash and compares the bank statement with the books, and the board gets
  an **income and expense report** for the general meeting.

A member can get a **reconciliation act** for their plot.

**Decided with the owner (28.10.2026):**
- **Cash:** received cash is both handed in to the bank and spent on the cooperative's needs.
- **Expenses:** a simple expense journal for cash and bank, with categories and a photo or PDF of the receipt.
- **Who handles cash:** only the treasurer, so there is one cash box.
- **Reconciliation:** a count of the cash, a monthly comparison with the bank statement (entered by hand until the bank
  statement import exists), and a reconciliation act with a member.

## Context for the pipeline

Read together with:
- `CLAUDE.md`;
- `docs/domain/money-rules.md` sections 1, 3 (3.1), 7 and the 015 sections;
- the READMEs of `ledger` (payments, `BillingCalendar`), `reports`, the documents module of 016, `privacy` and
  `settings`;
- ADRs 0006, 0007, 0013, 0015, 0016 and 0017;
- the guides.

General rules as in earlier features:
- contract-first;
- Flyway only;
- RLS and isolation tests;
- append-only money tables;
- permissions, not role names;
- Problem Details with a `code`;
- i18n;
- audit through the outbox;
- only the server calculates money;
- every worked example is a unit test;
- the verification levels and the guides rules of `CLAUDE.md`.

**A new module `treasury`.** It owns money accounts, movements, expenses, counts and bank reconciliations. It reads
payments through a `ledger` facade and never writes to the ledger: members' debts do not change in this feature.

## 2. Domain

### 2.1 Money accounts

A cooperative has exactly two money accounts:
- **Каса** (cash), held by the treasurer;
- **Банківський рахунок** (bank), the IBAN of the requisites (013).

A second bank account is out of scope. The model keeps an `account_id`, so it can be added later.

Each account has an **opening balance** at a date, entered once by the chairman («Вхідний залишок»). Before it is entered,
the account screens ask for it and balances are not shown.

### 2.2 Movements (append-only)

Every change of an account is a **movement** with:
- account;
- date (Kyiv, not after today);
- signed amount;
- kind;
- billing month (`BillingCalendar.bookingMonth`, as for payments);
- a link to its source;
- created by and at.

| Kind | Account | Sign | Source |
|---|---|---|---|
| `MEMBER_PAYMENT` | cash for `CASH`, bank for `BANK` payments of 015 | + | the payment; created automatically in the payment's transaction, and its reversal with the payment's reversal |
| `HANDOVER_OUT` / `HANDOVER_IN` | cash − / bank + | −/+ | a handover of cash to the bank (one record, two movements, same date and amount), with the bank's document number |
| `EXPENSE` | cash or bank | − | an expense (2.3) |
| `OTHER_INCOME` | cash or bank | + | income not from members: bank interest, a grant, a sale of scrap. Category and description |
| `COUNT_DIFFERENCE` | cash | ± | a booked difference of a cash count (2.4) |
| `BANK_DIFFERENCE` | bank | ± | a booked difference of a bank reconciliation (2.5), for example a bank fee nobody entered |
| `REVERSAL` | as reversed | ∓ | reverses one movement record (an expense, a handover, an other income, a difference) with a reason. Payments are reversed only through 015 |

**Balance of an account on a day** = opening balance + Σ movements up to and including that day. It is never stored, only
calculated.

**Cash cannot go negative.** A handover or a cash expense that would make the cash balance on its date or any later day
negative is 409 `CASH_INSUFFICIENT`, with the balance shown. A wrong book balance is fixed by a cash count, not by
letting it go negative. The one exception is the reversal of a cash payment (015, example 10): the money is already
gone, so the reversal is allowed and the cash screen warns that a count is needed. The bank may go negative (an overdraft is the bank's business). The screen warns, but does not
refuse.

**Closed billing months.** A movement dated in a closed month is booked in the earliest open month and keeps its date,
as payments do. Reports by date use the date; reports by billing month use the booking month.

### 2.3 Expenses

An expense has:
- **account**: cash or bank;
- **date**;
- **amount > 0**;
- **category**: a per-cooperative list, editable by the chairman, seeded with:
  - «Електроенергія постачальнику»;
  - «Вода»;
  - «Заробітна плата та податки»;
  - «Ремонт мереж»;
  - «Матеріали»;
  - «Вивіз сміття»;
  - «Банківські комісії»;
  - «Податки та збори»;
  - «Канцелярія та зв'язок»;
  - «Інше»;

  categories can be renamed, reordered and archived, never deleted when used;
- **counterparty**: text, up to 200 characters; for example «АТ Одесаобленерго» or «ФОП Петренко». A private person's
  name here is personal data, so it is encrypted (014). The board sees it, the reports show it only with `CASHBOOK_VIEW`;
- **description**: up to 500 characters, encrypted for the same reason;
- **document**: the number and date of the supplier's invoice or receipt, optional;
- **attachments**: 0–5 files, each a photo (JPEG, PNG, HEIC converted to JPEG on upload) or a PDF:
  - up to 5 MB each, stored in the database (bytea) like the 013 templates;
  - the type is checked by magic bytes;
  - EXIF metadata, GPS included, is stripped on upload;
  - downloadable by `CASHBOOK_VIEW`.

An expense is changed only by reversing it and entering it again. The form offers «Сторнувати і внести заново» with the
old values filled in.

### 2.4 Cash count (касова перевірка)

- The treasurer counts the cash and enters the counted amount at a date and time. The system shows:
  - the book balance at that moment;
  - the difference;
  - the movements since the last count.
- **Difference 0.00:** the count is saved as «Збігається».
- **Any other difference:**
  - the count is saved with its difference and a required explanation;
  - the chairman can then book the difference as a `COUNT_DIFFERENCE` movement («Надлишок» or «Нестача») with a reason;
  - until then the screen and the cash book show «Неврегульована різниця».
- The count produces a **cash count act** (PDF through the 016 engine) with:
  - the date and time;
  - the book balance, the counted balance and the difference;
  - the signature lines of the treasurer and the chairman.

### 2.5 Bank reconciliation (monthly, by hand)

- For a calendar month the treasurer enters four figures from the bank statement:
  - opening balance;
  - total in;
  - total out;
  - closing balance.

  The four must be consistent: opening + in − out = closing, else 400 `STATEMENT_INCONSISTENT`.
- The system shows the same four figures from the books:
  - its opening balance on the 1st;
  - Σ positive bank movements;
  - Σ negative bank movements;
  - its closing balance;
  - the difference of each.

  It also lists the bank movements of the month, so the treasurer can find what is missing (a payment not entered, a fee
  not recorded).
- Closing a difference:
  - **a missing item:** the treasurer enters it as a payment (015), an expense or an other income;
  - **otherwise:** the chairman books a `BANK_DIFFERENCE` with a reason.
- When the differences are 0.00, the treasurer marks the month **«Звірено»**. A movement booked later with a date in a
  reconciled month reopens it, back to «Потребує повторної звірки», and the screen shows why.
- The bank statement import (later in the roadmap) will fill these figures and match lines automatically. This feature
  keeps the data shape it will need: per-month statement figures and a reconciliation status.

### 2.6 Reconciliation act with a member (акт звірки)

- For a plot and a period, a PDF through the 016 engine, in Ukrainian. It has:
  - the cooperative's requisites;
  - the plot and its current owners;
  - the period;
  - the balance at the start of the period (debt or prepayment);
  - every charge, adjustment, reversal and payment of the period by date, with columns «Нараховано» and «Сплачено»;
  - the turnover totals;
  - the balance at the end;
  - «За даними кооперативу заборгованість станом на {дата} становить …» (or prepayment, or none);
  - two signature blocks: «Від кооперативу» (the treasurer's name) and «Член кооперативу».
- It is built on demand from the ledger and is not stored. The ledger is append-only, so the same period always gives the
  same act.
- The board builds it from the plot card. A member downloads it for their own plots from the account, for any period
  from the first charge to today. 017 can send it like any document.

## 3. Reports (013 mechanism)

- **Касова книга** (`cash-book`):
  - filters: a period;
  - per day: the opening balance; each movement (time, kind, receipt number or document, counterparty, amount in or
    out); the closing balance;
  - the period totals.
- **Рух коштів по банку** (`bank-movements`): the same for the bank account, with the reconciliation status per month.
- **Доходи та витрати** (`income-expense`), for the general meeting:
  - filters: a period;
  - income: member payments by charge type (allocations; the unallocated part as «Аванси»), other income by category;
  - expenses by category;
  - the result;
  - the balances of cash and bank at the start and at the end.

  An xlsx and a template row set, so the board can use its own form.
- **Витрати** (`expenses`): the list with filters by category, account and period, and attachment links on screen.

## 4. Worked examples (unit tests of the balance calculator and the checks)

Cash opening balance 01.10.2026: 1 000.00. Bank opening balance 01.10.2026: 20 000.00.

| # | Event | Expected |
|---|---|---|
| 1 | 05.10 cash payment 500.00 (015) | cash 1 500.00 |
| 2 | 06.10 cash expense 300.00 «Матеріали» | cash 1 200.00 |
| 3 | 07.10 handover 1 000.00 to the bank | cash 200.00; bank 21 000.00 |
| 4 | 07.10 cash expense 250.00 | 409 `CASH_INSUFFICIENT` (balance 200.00) |
| 5 | 08.10 a back-dated cash expense dated 06.10 of 400.00 | 409 `CASH_INSUFFICIENT`: 06.10 itself stays positive (800.00), but 07.10 would be −200.00 after the handover |
| 6 | 10.10 count: counted 180.00, book 200.00 | difference −20.00, unresolved; the chairman books «Нестача» −20.00: cash 180.00 |
| 7 | Bank October: statement opening 20 000.00, in 3 000.00, out 150.00, closing 22 850.00; books: in 3 000.00 (2 000 member bank payments + 1 000 handover), out 0.00 | out differs by 150.00; the treasurer finds a bank fee, enters an expense 150.00 «Банківські комісії»: differences 0.00, the month is «Звірено» |
| 8 | After «Звірено», a bank payment dated 20.10 is entered on 03.11 | October back to «Потребує повторної звірки» |
| 9 | Statement with opening 100, in 50, out 20, closing 200 | 400 `STATEMENT_INCONSISTENT` |
| 10 | A cash payment of 015 is reversed | a `MEMBER_PAYMENT` reversal movement on the same day as the reversal; cash goes down by its amount; if that would make cash negative, the payment reversal is still allowed (money already gone is a count matter) and the cash screen shows a warning |
| 11 | Reconciliation act, plot 9, 01.09–31.10 | start balance from the ledger on 31.08; lines by date; end balance equals the plot balances report as of 31.10 |
| 12 | Income and expense, October | member payments by type equal the payments report's allocations; the result equals the change of cash + bank minus the change of unallocated prepayment |

Phase 1 checks example 12's identity and restates it exactly if needed.

## 5. API (`api/openapi.yaml`, tag `treasury`)

**Accounts and movements:**
- `GET /money-accounts`: with balances as of a date;
- `POST /money-accounts/{id}/opening-balance`;
- `GET /money-accounts/{id}/movements?from&to`.

**Expenses:**
- `POST /expenses` (multipart with attachments);
- `GET /expenses`;
- `GET /expenses/{id}`;
- `GET /expenses/{id}/attachments/{n}`;
- `POST /expenses/{id}/reverse`.

**Handovers and other income:**
- `POST /handovers`;
- `POST /handovers/{id}/reverse`;
- `POST /other-income`;
- `POST /other-income/{id}/reverse`.

**Expense categories:** `GET|POST|PUT /expense-categories`, with archive and reorder.

**Cash counts:**
- `POST /cash-counts`;
- `GET /cash-counts`;
- `GET /cash-counts/{id}/act.pdf`;
- `POST /cash-counts/{id}/book-difference`.

**Bank reconciliations:**
- `GET|PUT /bank-reconciliations/{month}`;
- `POST /bank-reconciliations/{month}/book-difference`;
- `POST /bank-reconciliations/{month}/confirm`.

**Reconciliation act:**
- `GET /plots/{id}/reconciliation-act.pdf?from&to`;
- members use the same endpoint for their own plots.

## 6. Data

- `money_account`;
- `account_movement` (append-only);
- `handover`;
- `expense` (encrypted counterparty and description), `expense_attachment` (bytea), `expense_category`;
- `other_income`;
- `cash_count`;
- `bank_reconciliation`.

All tables are tenant-scoped with RLS and isolation tests. Payment movements are created in 015's transaction through a
`ledger` event or SPI that `treasury` implements. The pipeline picks the way that keeps one transaction and no
dependency cycle, and writes it in the READMEs. Existing payments of the demo and of a real cooperative get their
movements in a one-time migration.

## 7. Permissions

- **New `CASHBOOK_VIEW`** (Chairman, Treasurer): accounts, movements, expenses, counts, reconciliations, the reports of
  section 3.
- **New `CASHBOOK_ENTER`** (Treasurer, Chairman): expenses, handovers, other income, counts, bank statement figures,
  «Звірено».
- **New `CASHBOOK_APPROVE`** (Chairman):
  - opening balances;
  - booking count and bank differences;
  - reversing movements;
  - expense categories.
- **The reconciliation act:** `CHARGES_VIEW` for the board; a member for their own plots.
- **The income and expense report** can also be given to a custom role (for example, an audit committee) through
  `CASHBOOK_VIEW`.

## 8. Web UI (style of 003)

1. **Каса і банк** (menu, `CASHBOOK_VIEW`):
   - two cards with today's balances;
   - for the cash card: the last count and an unresolved difference, if any;
   - for the bank card: the months and their reconciliation status;
   - buttons «Витрата», «Здати в банк», «Інший прихід», «Перерахунок каси».
2. **Витрати:** the list, the form (account, date, amount, category, counterparty, description, document, attachments
   with a camera button on a phone), the card with attachments and «Сторнувати».
3. **Перерахунок каси:** the counted amount, the result, the act PDF, and «Оформити різницю» for the chairman.
4. **Звірка з банком:** the month, the four statement figures, the comparison, the list of the month's movements,
   «Звірено».
5. **Plot card:** «Акт звірки» with a period. **Member account:** «Акт звірки» per plot.
6. **Звіти:** the four reports of section 3.
7. **Settings → Статті витрат:** categories.

## 9. Audit

- `OPENING_BALANCE_SET` (account);
- `EXPENSE_RECORDED`, `EXPENSE_REVERSED`;
- `HANDOVER_RECORDED`, `HANDOVER_REVERSED`;
- `OTHER_INCOME_RECORDED`;
- `CASH_COUNTED`, `COUNT_DIFFERENCE_BOOKED`;
- `BANK_STATEMENT_ENTERED`, `BANK_DIFFERENCE_BOOKED`, `BANK_MONTH_RECONCILED`, `BANK_MONTH_REOPENED`;
- `EXPENSE_CATEGORY_*`.

Counterparty and description values are encrypted in the audit as in 014.

## 10. Docs and guides

- `money-rules.md`: a new section «Каса і банк», with the balances, the no-negative-cash rule, closed months, counts,
  reconciliation and the identity of example 12.
- Glossary: money account, movement, handover, cash count, reconciliation, reconciliation act.
- The `treasury` README; ADR 0019 if Phase 1 needs one (attachments in the database, the link to `ledger`).
- **Guides, both languages:**
  - board guide chapters «Каса і банк» (daily work, the end of the month) and «Звіт для загальних зборів»;
  - the owner manual: permissions, attachments, the migration of existing payments;
  - new screens in `capture-screens.mjs`.
- **Demo data:**
  - opening balances;
  - October expenses in four categories, one with a photo;
  - two handovers;
  - one count with a booked shortage of 20.00;
  - September reconciled, October with a difference to find.

## 11. Out of scope

- the bank statement import and automatic matching (later in the roadmap);
- several bank accounts;
- several cash holders;
- budgets and plans;
- accounting entries for a professional accountant (a chart of accounts, 1C or M.E.Doc export);
- payroll calculation;
- paying suppliers from the system.

## 12. Acceptance criteria

- [ ] Every row of section 4 is a passing unit test, with booking tests for back-dated movements, closed months and
      concurrent cash expenses (the cash never goes negative under concurrency: the cash account row is locked).
- [ ] Movements are append-only (trigger and grant tests). Every 015 payment and reversal has exactly one movement, also
      after the migration of existing payments.
- [ ] The cash book's closing balance on any day equals the balance calculator. The income and expense report satisfies
      the identity of example 12 on the demo data.
- [ ] Attachments: type by magic bytes, 5 MB cap, EXIF stripped (test with a GPS-tagged photo), downloadable only with
      `CASHBOOK_VIEW`.
- [ ] Encrypted counterparty and description: no plaintext in the tables, the audit or the outbox.
- [ ] The reconciliation act matches the plot balances report at both ends of the period. A member gets only their own
      plots.
- [ ] Permissions as in section 7, with allowed and denied tests. Isolation tests. Audit as in section 9.
- [ ] Docs, both guides (two languages) and the demo data are present.
- [ ] `make check` is green. e2e:
      - the treasurer records a cash expense with a photo and a handover, and the cash balance follows (`@smoke`);
      - a cash count with a difference, booked by the chairman;
      - October bank reconciliation to «Звірено»;
      - the member downloads a reconciliation act.
