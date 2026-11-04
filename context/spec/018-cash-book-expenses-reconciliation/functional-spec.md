# Functional Specification: Cash book, expenses and reconciliation

- **Topic:** The cooperative keeps track of its own money: the cash the treasurer holds and the bank account, every
  receipt and spending of that money, the monthly check of the cash and of the bank statement, a report of income and
  expenses for the general meeting, and a reconciliation act that a member gets for their plot.
- **Status:** Implemented — pending manual check
- **Author:** Alex Usov
- **Source:** `docs/features/018-cash-book-expenses-reconciliation.md` (roadmap phase 3)

---

## 1. Overview and Rationale (The "Why")

Until now the system knows what members owe and what they pay. It does not know where that money is and where it goes:
the treasurer keeps a paper cash book, carries cash to the bank, pays the electricity supplier and the repairman, and at
the end of the year the board puts together the income and expense report for the general meeting by hand from a pile
of receipts. Nobody can say at a glance how much cash the treasurer should have today, and a missing bank fee is found
only at the yearly audit.

This feature adds:

- **two money accounts**, «Каса» (cash, held by the treasurer) and «Банківський рахунок» (the cooperative's bank
  account), with their balances on any day;
- a **journal of every receipt and spending**: member payments appear in it by themselves; the treasurer adds handovers
  of cash to the bank, expenses with a photo or PDF of the receipt, and other income;
- **checks**: the treasurer counts the cash and compares the bank statement with the books every month, and the
  chairman settles any difference with a reason;
- **reports**: the cash book, bank movements, expenses, and the income and expense report for the general meeting;
- a **reconciliation act** («Акт звірки») for a plot and a period, which the board builds and a member downloads for
  their own plots.

Members' debts do not change in this feature: it only shows where the money they paid went.

**Success looks like:** on 31 October the treasurer opens «Каса і банк» and sees cash 180,00 ₴ and bank 22 850,00 ₴;
the cash count matches the book; the October bank statement matches after entering a forgotten bank fee, and the month
shows «Звірено». In spring the board downloads «Доходи та витрати» for the year and takes it to the general meeting
without retyping anything.

---

## 2. Functional Requirements (The "What")

Amounts are shown as `1 234,56 ₴`, dates as `28.10.2026`, months as «жовтень 2026». Screen texts follow the user's
language; generated PDF documents (the cash count act, the reconciliation act) are always in Ukrainian, like the
documents of 016. Dates are Kyiv dates and never later than today.

### 2.1 Money accounts and opening balances

A cooperative has exactly two money accounts: «Каса» and «Банківський рахунок» (the IBAN from the cooperative's
requisites is shown under its name). A second bank account and a second cash box are not possible.

Each account gets an **opening balance** («Вхідний залишок»): an amount and a date, entered once by the chairman. It is
the balance at the start of that date. After it is saved it cannot be changed; a mistake is corrected by a cash count
(cash) or a bank difference (bank).

Before an account has an opening balance, its screen shows «Введіть вхідний залишок» instead of a balance, and
expenses, handovers, other income, counts and reconciliations for that account are refused with «Спочатку введіть
вхідний залишок рахунку». Member payments are still recorded while an account has no opening balance; payments dated
before the opening date stay in the journal marked «до вхідного залишку» and are not counted in the balance.

**The balance of an account on a day** is its opening balance plus every receipt and minus every spending dated from the
opening date up to and including that day. A reversed entry and its reversal both stay in the journal.

- **Acceptance Criteria:**
  - [x] Given the cash account has no opening balance, when the treasurer opens «Каса і банк», then the cash card shows
        «Введіть вхідний залишок» and no balance.
  - [x] When the chairman enters a cash opening balance of 1 000,00 ₴ on 01.10.2026, then the cash card shows
        1 000,00 ₴ and the change history shows «Вхідний залишок встановлено».
  - [x] Given an opening balance is saved, when the chairman opens the account, then there is no way to change it.
  - [x] Given the treasurer has no right to approve, when they open an account without an opening balance, then they
        see the prompt but no button to enter it.
  - [x] Given the bank account has no opening balance, when the treasurer tries to record a bank expense, then they see
        «Спочатку введіть вхідний залишок рахунку».

### 2.2 The journal of an account (movements)

Every change of an account is a line in its journal with: the date, the amount (receipt or spending), the kind, the
billing month it belongs to, a link to what caused it (the payment, the expense, the handover, …), who entered it and
when. Lines are never edited or deleted; a wrong line is reversed (2.8).

| Kind | Account | Direction | What causes it |
|---|---|---|---|
| Member payment | cash for a cash payment, bank for a bank payment | receipt | A payment of 015 appears in the journal by itself when it is saved; its reversal of 015 adds a reversing line dated on the day of the reversal |
| Handover to the bank | cash and bank | spending from cash, receipt to bank | A handover (2.4): one record with two lines of the same date and amount |
| Expense | cash or bank | spending | An expense (2.3) |
| Other income | cash or bank | receipt | Income not from members: bank interest, a grant, a sale of scrap (2.5) |
| Count difference | cash | either | A booked difference of a cash count (2.6) |
| Bank difference | bank | either | A booked difference of a bank reconciliation (2.7), for example a bank fee nobody entered |
| Reversal | as reversed | opposite | Reverses one expense, handover, other income or difference, with a reason (2.8) |

**Closed billing months.** A line dated in a closed billing month belongs to the earliest open billing month and keeps
its date, as payments do. Reports by day use the date; reports by billing month use the month it belongs to.

**Cash never goes negative.** An expense from cash, a handover, the reversal of a cash income and a booked cash shortage
are refused if they would make the cash balance negative on their date or on any later day. The message shows the
balance: «Недостатньо готівки в касі: залишок 200,00 ₴ на 07.10.2026». A wrong book balance is fixed by a cash count, not
by letting it go negative. Two entries saved at the same moment can never together push the cash below zero.

The one exception is the reversal of a cash member payment (015): the money is already gone, so the reversal is
allowed. If the cash then shows a negative balance, the cash card shows «Залишок каси від'ємний — проведіть перерахунок
каси».

**The bank may go negative** (an overdraft is the bank's business): the screen warns «Залишок банку від'ємний», but does
not refuse.

- **Acceptance Criteria:**
  - [x] Given the cash opening balance is 1 000,00 ₴ on 01.10.2026, when a cash payment of 500,00 ₴ dated 05.10 is
        saved on the payments screen, then the cash journal shows a member payment line of +500,00 ₴ on 05.10 and the cash
        balance is 1 500,00 ₴.
  - [x] When the chairman reverses that cash payment on 12.10, then the cash journal shows a reversing line of −500,00 ₴
        dated 12.10.
  - [x] Given cash is 200,00 ₴ on 07.10, when the treasurer records a cash expense of 250,00 ₴ on 07.10, then it is
        refused with «Недостатньо готівки в касі: залишок 200,00 ₴ на 07.10.2026».
  - [x] Given cash was 1 200,00 ₴ on 06.10 and a handover of 1 000,00 ₴ was made on 07.10, when the treasurer enters an
        expense of 400,00 ₴ dated 06.10, then it is refused because 07.10 would become −200,00 ₴, and the message names
        the day and its balance.
  - [x] Given September is closed, when the treasurer records an expense dated 30.09, then the journal shows the date
        30.09 and the billing month October.
  - [x] Given reversing a cash payment makes the cash negative, when the chairman reverses it, then the reversal is saved
        and the cash card shows «Залишок каси від'ємний — проведіть перерахунок каси».
  - [x] Given the bank balance is 100,00 ₴, when the treasurer records a bank expense of 150,00 ₴, then it is saved and
        the bank card shows «Залишок банку від'ємний».
  - [x] Given two treasurers record cash expenses of 150,00 ₴ each at the same moment while cash is 200,00 ₴, when both
        save, then exactly one is saved and the other sees the insufficient cash message.

### 2.3 Expenses («Витрати»)

An expense has:

- **account:** cash or bank;
- **date:** not later than today;
- **amount:** above 0,00;
- **category:** from the cooperative's list of expense categories (2.9);
- **counterparty:** free text up to 200 characters, for example «АТ Одесаобленерго» or «ФОП Петренко»; optional;
- **description:** free text up to 500 characters; optional;
- **document:** the number and date of the supplier's invoice or receipt; optional;
- **attachments:** 0 to 5 files, each a photo (JPEG or PNG; a HEIC photo from an iPhone is turned into JPEG before it is
  sent) or a PDF, up to 5 MB each.

The counterparty may be a private person's name, so the counterparty and the description are personal data: they are
kept protected like members' contacts (014), shown to users with the right to view the cash book, and never shown in
the change history to users without the right to see members' personal data.

**Attachments.** The file type is checked by its content, not its name. Location and camera data are removed from photos
when they are uploaded, so a downloaded photo carries no GPS position. Attachments are downloaded from the expense card
by users with the right to view the cash book.

Error messages:
- a file over 5 MB: «Файл завеликий. Максимум 5 МБ.»;
- a sixth file: «Не більше 5 файлів.»;
- a file that is not a JPEG, PNG or PDF (by content): «Дозволені лише фото (JPEG, PNG) і PDF.»;
- amount 0,00 or less: «Сума має бути більшою за 0,00.»;
- a date after today: «Дата не може бути пізнішою за сьогодні.»;
- a HEIC photo the device or browser cannot turn into JPEG: «Не вдалося перетворити фото HEIC. Збережіть його як JPEG
  і додайте ще раз.»

An expense is never edited. The expense card offers «Сторнувати і внести заново»: it reverses the expense with a reason
and opens the form with the old values filled in (attachments included), ready to be corrected and saved as a new
expense.

The expense list shows date, account, category, counterparty, amount, number of attachments and whether it is
reversed, with filters by period, account and category.

- **Acceptance Criteria:**
  - [x] When the treasurer records a cash expense of 300,00 ₴ «Матеріали» on 06.10 with a photo of the receipt, then the
        expense appears in the list, the cash balance goes down by 300,00 ₴, and the card shows the photo.
  - [x] Given a photo with a GPS position was attached, when it is downloaded from the expense card, then the downloaded
        file contains no GPS position and no camera data.
  - [x] When the treasurer attaches a 6 MB file, then they see «Файл завеликий. Максимум 5 МБ.»
  - [x] When the treasurer attaches a text file renamed to `receipt.jpg`, then they see «Дозволені лише фото (JPEG, PNG) і
        PDF.»
  - [x] When the treasurer attaches a sixth file, then they see «Не більше 5 файлів.»
  - [x] Given a user without the right to view the cash book, when they try to open an expense or its attachment, then
        they see the «no access» page.
  - [x] Given an expense of 300,00 ₴ was recorded with the wrong amount, when the chairman clicks «Сторнувати і внести
        заново» and gives a reason, then the expense is shown as reversed, the cash balance is restored, and the form
        opens with the old values to correct.
  - [x] Given the treasurer has no right to approve, when they open an expense card, then they see no «Сторнувати»
        button.

### 2.4 Handover of cash to the bank («Здати в банк»)

A handover moves cash to the bank: a date, an amount above 0,00 and the bank's document number (the cash deposit slip).
It adds a spending line to the cash and a receipt line to the bank of the same date and amount. It is refused if it
would make the cash negative (2.2).

- **Acceptance Criteria:**
  - [x] Given cash is 1 200,00 ₴ and bank is 20 000,00 ₴, when the treasurer hands over 1 000,00 ₴ on 07.10 with the
        document number «КВ-123», then cash shows 200,00 ₴ and bank shows 21 000,00 ₴, and both journals show the
        handover with «КВ-123».
  - [x] When the chairman reverses that handover with a reason, then cash is back to 1 200,00 ₴ and bank to
        20 000,00 ₴.

### 2.5 Other income («Інший прихід»)

Income not from members: an account, a date, an amount above 0,00, a category from the cooperative's income categories
(2.9) and a description up to 500 characters (protected like an expense description). It adds a receipt line to the
account.

- **Acceptance Criteria:**
  - [x] When the treasurer records 35,00 ₴ «Відсотки банку» to the bank on 31.10, then the bank balance goes up by
        35,00 ₴ and the bank journal shows the income with its category.
  - [x] Given the cash would go negative on a later day, when the chairman reverses a cash other income, then it is
        refused with the insufficient cash message.

### 2.6 Cash count («Перерахунок каси»)

The treasurer counts the cash and enters the counted amount, the date and the time. The screen shows the book balance
at that moment (the balance on that date with every line entered so far), the difference (counted minus book), and the
journal lines since the previous count. The count keeps the book balance it showed; a line entered later for an
earlier date does not change a saved count.

- **Difference 0,00:** the count is saved as «Збігається».
- **Any other difference:** the count is saved with its difference and a required explanation («Пояснення»). The cash
  card and the cash book show «Неврегульована різниця −20,00 ₴» until the chairman settles it with «Оформити різницю»:
  a «Надлишок» (surplus, receipt) or «Нестача» (shortage, spending) line for the difference, with a reason. A difference
  is settled once. A shortage is refused if it would make the cash negative on a later day.
- **Act:** every count has «Акт перерахунку каси», a PDF with the cooperative's name, the date and time, the book
  balance, the counted balance, the difference (with its explanation and, once settled, how it was settled), and
  signature lines for the treasurer and the chairman.

- **Acceptance Criteria:**
  - [x] Given cash in the books is 200,00 ₴, when the treasurer enters a count of 200,00 ₴, then the count is saved as
        «Збігається».
  - [x] Given cash in the books is 200,00 ₴, when the treasurer enters a count of 180,00 ₴ without an explanation, then
        they see «Вкажіть пояснення різниці.»
  - [x] Given cash in the books is 200,00 ₴, when the treasurer enters a count of 180,00 ₴ with an explanation, then the
        count shows the difference −20,00 ₴ and the cash card shows «Неврегульована різниця −20,00 ₴».
  - [x] Given that unresolved difference, when the chairman clicks «Оформити різницю» and gives a reason, then a
        «Нестача» line of −20,00 ₴ is added, the cash shows 180,00 ₴ and the unresolved difference disappears.
  - [x] When the treasurer downloads the act of that count, then the PDF shows the book balance 200,00 ₴, the counted
        balance 180,00 ₴, the difference −20,00 ₴ and signature lines for the treasurer and the chairman.
  - [x] Given the treasurer has no right to approve, when they open a count with a difference, then they see no
        «Оформити різницю» button.

### 2.7 Bank reconciliation («Звірка з банком»), monthly

For a calendar month the treasurer enters four figures from the bank statement: the opening balance, the total in, the
total out and the closing balance. They must agree: opening + in − out = closing, otherwise «Цифри виписки не сходяться:
100,00 + 50,00 − 20,00 ≠ 200,00».

Next to them the screen shows the same four figures from the books (the bank balance at the start of the 1st, the sum of
bank receipts of the month, the sum of bank spendings of the month, the balance at the end of the last day) and the
difference of each. Below, it lists the bank journal lines of the month, so the treasurer can find what is missing.

A difference is closed by:
- **a missing entry:** the treasurer records it as a payment (015), an expense or an other income, and the comparison
  updates;
- **otherwise:** the chairman books «Банківська різниця» for the month with an amount and a reason.

When the closing difference is 0,00, the treasurer marks the month «Звірено». A difference in the total in or the total
out does not block it: the screen shows it as a warning with a short explanation (for example, a mistaken line reversed
in a later month, or a line the bank shows in a different month), so the treasurer can still check it. If a bank line
dated in a reconciled month is added later (for example a bank payment entered on 03.11 with the date 20.10), the month
goes back to «Потребує повторної звірки», and the screen says what was added and when: «Місяць потрібно звірити знову:
04.11.2026, 10:15 внесено платіж члена від 20.10.2026 на 500,00 ₴.» The month list on the bank card shows each month's
status: «Не звірено», «Звірено», «Потребує повторної звірки».

- **Acceptance Criteria:**
  - [x] When the treasurer enters a statement with opening 100,00, in 50,00, out 20,00 and closing 200,00, then they
        see «Цифри виписки не сходяться: 100,00 + 50,00 − 20,00 ≠ 200,00».
  - [x] Given the October books show in 3 000,00 ₴ and out 0,00 ₴, when the treasurer enters the statement 20 000,00 /
        3 000,00 / 150,00 / 22 850,00, then the screen shows the «out» difference of 150,00 ₴ and the «closing»
        difference, and «Звірено» cannot be pressed while the closing difference is not 0,00.
  - [x] Given that difference, when the treasurer records a bank expense of 150,00 ₴ «Банківські комісії» in October,
        then every difference is 0,00 and the treasurer can mark October «Звірено».
  - [x] Given October is «Звірено», when a bank payment dated 20.10 is entered on 03.11, then October shows «Потребує
        повторної звірки» with the reason.
  - [x] Given a difference no entry explains, when the chairman books a bank difference with a reason, then it appears
        in the bank journal and the closing difference becomes 0,00.
  - [x] Given the closing difference is 0,00 but the total in and the total out each differ (a line the statement shows
        in another month), when the treasurer opens the month, then they see a warning with a short explanation and can
        mark the month «Звірено».

### 2.8 Reversals

The chairman reverses an expense, a handover, an other income or a booked difference with a required reason. The
reversal is a new journal line of the opposite amount, dated on the day of the reversal; the reversed entry stays,
marked «Сторновано». An entry is reversed once. Member payments are reversed only on the payments screen (015).

- **Acceptance Criteria:**
  - [x] When the chairman reverses an expense without a reason, then they see «Вкажіть причину сторнування.»
  - [x] Given an expense is already reversed, when the chairman opens it, then there is no «Сторнувати» button.

### 2.9 Expense and income categories (Settings → «Статті витрат»)

Each cooperative has its own list of expense categories, seeded with: «Електроенергія постачальнику», «Вода», «Заробітна
плата та податки», «Ремонт мереж», «Матеріали», «Вивіз сміття», «Банківські комісії», «Податки та збори», «Канцелярія
та зв'язок», «Інше»; and its own list of income categories, seeded with «Відсотки банку», «Гранти та пожертви», «Продаж
матеріалів», «Інше». The chairman adds, renames, reorders and archives categories. An archived category is not offered
for new entries but stays on old ones. A category is never deleted. Two categories of one list cannot have the same
name.

- **Acceptance Criteria:**
  - [x] When the chairman renames «Інше» to «Інші витрати», then existing expenses show the new name.
  - [x] When the chairman archives «Вивіз сміття», then it is not offered on the expense form, and old expenses still
        show it.
  - [x] When the chairman adds a category named like an existing one, then they see «Така стаття вже є.»

### 2.10 Reconciliation act with a member («Акт звірки»)

For a plot and a period the act is a PDF in Ukrainian with:

- the cooperative's requisites;
- the plot and its current owners;
- the period;
- the balance at the start of the period (debt, prepayment or none);
- every charge, adjustment, reversal and payment of the period by date, with columns «Нараховано» and «Сплачено»;
- the turnover totals of the period;
- the balance at the end of the period;
- «За даними кооперативу заборгованість станом на 31.10.2026 становить 413,26 ₴» (or «переплата становить …», or
  «заборгованість відсутня»);
- two signature blocks: «Від кооперативу» with the treasurer's name (an empty line if the cooperative has no
  treasurer), and «Член кооперативу».

The act is built when it is requested and is not kept; the same plot and period always give the same act. The balances
at both ends equal the plot balances report on those days. A period that starts before the plot's first charge
or payment starts on that first day (the act shows the actual period); a period that ends after today, or starts after
it ends, shows «Невірний період».

The board builds it from the plot card («Акт звірки», with a period). A member downloads it in their account for each
of their own plots; another plot's act is not available to them. The act can be sent like any document of 017.

- **Acceptance Criteria:**
  - [x] When the treasurer opens plot 9 and downloads «Акт звірки» for 01.09.2026–31.10.2026, then the PDF shows the
        balance on 31.08 and on 31.10 equal to the plot balances report on those days, and every charge and payment of
        the period by date.
  - [x] When a member opens their account, then each of their plots has «Акт звірки», and the downloaded act shows
        their plot.
  - [x] Given a member does not own plot 9, when they try to open plot 9's act, then they see «not found».
  - [x] When a user asks for a period that ends after today, then they see «Невірний період».
  - [x] Given the cooperative's requisites are not entered, when a user builds an act, then they see the same message
        as for invoices: the requisites must be entered first.

### 2.11 The «Каса і банк» screen

In the menu for users with the right to view the cash book. It shows:

- **the cash card:** today's balance, the last count (date and result) and an unresolved difference, if any, and the
  negative cash warning;
- **the bank card:** today's balance, the negative bank warning, and the months with their reconciliation status;
- the buttons «Витрата», «Здати в банк», «Інший прихід», «Перерахунок каси» for users with the right to enter cash book
  entries;
- each card opens its journal for a period.

On a phone the expense form offers taking a photo with the camera.

- **Acceptance Criteria:**
  - [x] Given a user without the right to view the cash book (for example a member), when they look at the menu, then
        there is no «Каса і банк».
  - [x] Given a user with the right to view but not to enter, when they open «Каса і банк», then they see the balances
        and no entry buttons.
  - [?] When the treasurer opens the expense form on a phone, then they can take a photo with the camera as an
        attachment. (Manual check: the camera button is in place and tested; taking a photo with a real phone was not automated.)

### 2.12 Reports (on «Звіти»)

Four new reports, previewed on screen and downloaded as xlsx like the reports of 013, for users with the right to view
the cash book:

- **«Касова книга»:** a period; per day the opening balance, each line (time, kind, receipt or document number,
  counterparty, receipt or spending), the closing balance; the totals of the period. The closing balance of every day
  equals the cash balance of that day on «Каса і банк».
- **«Рух коштів по банку»:** the same for the bank account, with the reconciliation status of each month.
- **«Доходи та витрати»** (for the general meeting): a period;
  - income: member payments received in the period by charge type (by how those payments are allocated at the end of
    the period), the part of those payments not allocated to any charge as «Аванси», reversals made in the period of
    payments received earlier as a negative line, and other income by category;
  - expenses by category;
  - booked count and bank differences as their own line;
  - the result: income − expenses + differences, which equals the change of cash plus bank over the period;
  - the cash and bank balances at the start and at the end of the period.

  It can also be filled into the board's own report template (013).
- **«Витрати»:** the expense list with filters by period, category and account; on screen each expense links to its
  attachments.

A custom role (for example an audit committee) can be given these reports with the right to view the cash book alone.

- **Acceptance Criteria:**
  - [x] When the treasurer previews «Касова книга» for October, then each day's closing balance equals the cash balance
        on «Каса і банк» for that day.
  - [x] When the chairman downloads «Доходи та витрати» for October on the demo data, then the result equals the change
        of cash plus bank between 01.10 and 31.10, and the member payments by charge type equal the allocations of the
        «Платежі» report for the same payments.
  - [x] Given a custom role with only the right to view the cash book, when its user opens «Звіти», then they see the
        four cash book reports and none of the other reports.
  - [x] When the treasurer previews «Витрати» filtered by «Матеріали», then only expenses of that category are listed,
        each with links to its attachments.

### 2.13 Rights

- **«Перегляд каси і банку»** (new; chairman, treasurer): accounts, journals, expenses and their attachments, counts,
  reconciliations, and the reports of 2.12.
- **«Внесення в касову книгу»** (new; treasurer, chairman): expenses, handovers, other income, counts, bank statement
  figures, «Звірено».
- **«Затвердження касових операцій»** (new; chairman): opening balances, booking count and bank differences, reversals,
  expense and income categories.
- **The reconciliation act:** the right to view charges for the board; a member for their own plots.

Custom roles can be given any of the three new rights.

- **Acceptance Criteria:**
  - [x] When the chairman opens the roles screen, then the three new rights are listed, the treasurer role has the first
        two, and the chairman has all three.
  - [x] Given the treasurer, when they try to book a count difference, then they are refused.
  - [x] Given a member, when they try to open «Каса і банк» by its address, then they see the «no access» page.
  - [x] Given a user of another cooperative, when they try to open an expense or a count of this cooperative, then they
        see «not found».

### 2.14 Change history

The change history records: opening balance set, expense recorded and reversed, handover recorded and reversed, other
income recorded and reversed, cash counted, count difference booked and reversed, bank statement entered, bank
difference booked and reversed,
bank month reconciled, bank month reopened (by the system, with the line that reopened it), and categories added,
renamed, reordered and archived. Counterparty and description values appear in it only to users with the right to see
members' personal data; others see «приховано».

- **Acceptance Criteria:**
  - [x] When the treasurer records an expense, then the change history shows «Витрату внесено» with the amount,
        account and category.
  - [x] Given a user without the right to see members' personal data, when they open that history entry, then the
        counterparty and the description show «приховано».

### 2.15 Existing payments

When the feature is installed, every payment already entered (and every reversal of a payment) gets its journal line,
so the cash and bank journals are complete from the first day. Each payment and each reversal has exactly one line.

- **Acceptance Criteria:**
  - [x] Given a cooperative had payments before the feature was installed, when the treasurer opens the cash journal,
        then every earlier cash payment and reversal is listed once.

### 2.16 Demo data

The demo cooperative shows: opening balances of cash and bank; expenses in four categories in the current month, one
with a photo of a receipt; two handovers; one count with a booked shortage of 20,00 ₴; the previous month reconciled and
the current month with a bank difference to find.

- **Acceptance Criteria:**
  - [x] When the demo chairman opens «Каса і банк», then both balances, the last count with its booked shortage, the
        previous month «Звірено» and the current month with a difference are shown.

### 2.17 Worked examples

Cash opening balance 01.10.2026: 1 000,00 ₴. Bank opening balance 01.10.2026: 20 000,00 ₴.

| # | Event | Expected |
|---|---|---|
| 1 | 05.10 cash payment 500,00 (015) | cash 1 500,00 |
| 2 | 06.10 cash expense 300,00 «Матеріали» | cash 1 200,00 |
| 3 | 07.10 handover 1 000,00 to the bank | cash 200,00; bank 21 000,00 |
| 4 | 07.10 cash expense 250,00 | refused: insufficient cash (balance 200,00) |
| 5 | 08.10 a back-dated cash expense dated 06.10 of 400,00 | refused: 06.10 itself stays positive (800,00), but 07.10 would be −200,00 after the handover |
| 6 | 10.10 count: counted 180,00, book 200,00 | difference −20,00, unresolved; the chairman books «Нестача» −20,00: cash 180,00 |
| 7 | Bank October: statement opening 20 000,00, in 3 000,00, out 150,00, closing 22 850,00; books in 3 000,00 (2 000,00 member bank payments + 1 000,00 handover), out 0,00 | out differs by 150,00; the treasurer enters an expense 150,00 «Банківські комісії»: differences 0,00, October «Звірено» |
| 8 | After «Звірено», a bank payment dated 20.10 is entered on 03.11 | October «Потребує повторної звірки» |
| 9 | Statement opening 100, in 50, out 20, closing 200 | refused: the statement figures do not agree |
| 10 | A cash payment of 015 is reversed | a reversing member payment line on the day of the reversal; cash goes down by its amount; if cash would go negative, the reversal is still allowed and the cash card warns |
| 11 | Reconciliation act, plot 9, 01.09–31.10 | start balance as on 31.08; lines by date; end balance equals the plot balances report on 31.10 |
| 12 | Income and expense, October | member payments by type equal the payments report's allocations of those payments; income − expenses + differences = change of (cash + bank) over October |

---

## 3. Scope and Boundaries

### In-Scope

- The two money accounts, opening balances, the journal with every kind of line, the no-negative-cash rule, closed
  billing months.
- Expenses with attachments, handovers, other income, reversals, expense and income categories.
- Cash counts with their act, monthly bank reconciliation by hand.
- The reconciliation act for the board and for members.
- The four reports and the report template filling.
- Rights, change history, journal lines for existing payments, demo data.
- Documentation: money rules, glossary, both guides in Ukrainian and English, screenshots.

### Out-of-Scope

- The bank statement import and automatic matching (later in the roadmap); this feature keeps the monthly statement
  figures and the reconciliation status the import will fill.
- Several bank accounts; several cash holders.
- Budgets and plans.
- Accounting entries for a professional accountant (a chart of accounts, 1C or M.E.Doc export).
- Payroll calculation.
- Paying suppliers from the system.
- The mobile app (members download the reconciliation act in the web account).

---

## 4. Assumptions & Decisions

Decided with the owner (28.10.2026, in the requirement): cash is both handed to the bank and spent; a simple expense
journal for cash and bank with categories and a receipt photo or PDF; only the treasurer holds cash (one cash box);
reconciliation = cash count, monthly manual bank comparison, reconciliation act with a member.

Decided by the pipeline (no blocking questions; recommended answers taken, see `pipeline-log.md`):

1. **Income and expense identity (example 12) restated.** Member income is the payments received in the period by their
   allocation per charge type as of the period end, plus their unallocated part as «Аванси», plus a negative line for
   reversals in the period of earlier payments; other income by category; expenses by category; booked differences as
   their own line. Then income − expenses + differences = change of cash + bank. The requirement's wording "minus the
   change of unallocated prepayment" only holds when «Аванси» are left out of income and differences are ignored.
2. **A payment reversal's journal line is dated on the day of the reversal** (example 10). The member's debt view (the
   account, the reconciliation act) keeps the dating of 015.
3. **The reconciliation act follows the debt view**, so its ends equal the plot balances report (example 11).
4. **Opening balance:** the balance at the start of its date; entered once, never changed; entries other than member
   payments dated before it are refused; member payments before it stay in the journal but do not count.
5. **No negative cash** applies to cash expenses, handovers, reversals of cash income and booked shortages, on the date
   and every later day; only member payment reversals are exempt (warning).
6. **HEIC photos** are turned into JPEG on the user's device before upload; only JPEG, PNG and PDF are accepted. Where
   the browser cannot convert a HEIC photo, the user is asked for a JPEG (owner decision, 2026-11-04).
7. **Other income has its own category list**, seeded «Відсотки банку», «Гранти та пожертви», «Продаж матеріалів»,
   «Інше», managed like expense categories.
8. **The four cash book reports need only the right to view the cash book**, so an audit committee role works without
   the general reports right.
9. **Protected values in the change history** follow the rule of 014 (visible with the right to see members' personal
   data); screens and reports show them with the right to view the cash book.
10. **Bank reconciliation months are calendar months by date**; any bank line dated in a reconciled month, payments
    included, reopens it.
11. **A saved count keeps the book balance it showed.**
12. **Demo months** follow the demo ledger's moving months: «previous month» reconciled, «current month» with a
    difference.
13. **The «Від кооперативу» signature** shows the treasurer's name, or an empty line if there is no treasurer.
14. **Counterparty and document are optional** on an expense; the category is required.

---

## Change Log

_Dated amendments made after the spec was first written. Leave empty until the first amendment._

- 2026-11-04 — owner decisions on PR #66 — (1) «Звірено» needs only the closing difference to be 0,00; differences in the
  total in or total out are a warning with a short explanation and do not block (FS 2.7); (2) HEIC stays browser-only; on
  failure the user is asked for a JPEG (FS 2.3); (3) the reopen notice reads «Місяць потрібно звірити знову: {at}
  внесено {kind} від {date} на {amount}.» (FS 2.7).

- 2026-11-04 — Slice 9 acceptance testing (GAP on FS 2.10) — a reconciliation act period that starts before the plot's
  first charge or payment now starts on that first day instead of being refused, because the form's default start
  (1 January) made the first download fail for most plots.
