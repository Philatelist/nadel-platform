# Acceptance coverage: 018 Cash book, expenses and reconciliation

Every acceptance criterion of `functional-spec.md` (2.1 to 2.16, 62 criteria, and the 12 worked examples of 2.17) mapped
to the tests that check it. Criteria are numbered `#n` in the order of the spec.

- `ACC` = `backend/src/test/java/app/nadel/treasury/TreasuryAcceptanceTests` (Slice 9, integration: the public HTTP API,
  Testcontainers, one cooperative, tokens of a chairman, a treasurer, a member who owns the plot, custom roles, the
  operator, and a second cooperative with every permission). Its tests: `story` =
  `theWholeCashBookStoryOfTheWorkedExamplesOnOneCooperative`, `roles` =
  `theRolesScreenListsTheThreeRightsTheTreasurerHasTwoAndTheChairmanAllThree`, `rights` =
  `everyEndpointFamilyHonoursTheThreeRightsForACustomRoleAMemberTheOperatorAndNoToken`, `isolation` =
  `aChairmanOfAnotherCooperativeFindsNothingOfThisOneAndChangesNothing`, `closing` =
  `aMonthIsReconciledWhenOnlyItsClosingAgreesAndTheIncomingAndOutgoingDifferencesAreAWarning`. It sits in the package of
  the slice tests because their fixtures are package-private.
- `E2E` = the Playwright specs of Slice 9 in `web/e2e`: `cashbook-expense-handover.spec.ts` (HAND, `@smoke`),
  `cash-count.spec.ts` (COUNT), `bank-reconciliation.spec.ts` (BANK), `reconciliation-act.spec.ts` (ACT). They run on the
  demo cooperative against the real stack, take months and amounts from the server at run time and assert deltas.
- Other names are slice tests in `backend/src/test/java/app/nadel/treasury` (T), `.../treasury/internal` (TI),
  `.../documents` (D), `.../ledger` (L), `.../acceptance` and `.../reports`; web unit tests (V) are under `web/src`.
  `CBX` = `CashBookExamplesTests`, one test per row of 2.17.

| FS | Criterion (short) | Tests |
| --- | --- | --- |
| 2.1 #1 | No opening balance: prompt, no balance | T `MoneyAccountsTests.bothAccountsAreListedCashFirstWithoutABalance…`; TI `AccountBalancesTests.withoutAnOpeningBalanceThereIsNoBalance`; ACC story; V `CashbookPage.test` (prompt for an account without opening) |
| 2.1 #2 | Opening 1 000,00 shown; history «Вхідний залишок встановлено» | T `MoneyAccountsTests.theOpeningBalanceIsEnteredOnceAndAudited`; TI `TreasuryAuditActionsTests`; ACC story; V `auditValue.treasury.test`; uk catalog `OPENING_BALANCE_SET` (catalog parity `catalogs.test`) |
| 2.1 #3 | Saved opening cannot be changed | T `MoneyAccountsTests.theOpeningBalanceIsEnteredOnceAndAudited`, `TreasuryGuardsTests.theOpeningBalanceIsWrittenOnceByTheApplicationRole`; ACC story (409 `OPENING_BALANCE_ALREADY_SET`); V `CashbookPage.test` (form only for an account without opening) |
| 2.1 #4 | Treasurer sees the prompt, no button | V `CashbookPage.test` (form only with `CASHBOOK_APPROVE`); T `MoneyAccountsAccessTests.onlyApproversEnterTheOpeningBalance`; ACC story (treasurer 403), ACC rights |
| 2.1 #5 | Bank expense without opening refused | T `ExpensesTests.anExpenseNeedsTheOpeningBalance…`; ACC story (`OPENING_BALANCE_MISSING`); V `ExpenseForm.test`; catalog text `OPENING_BALANCE_MISSING` |
| 2.2 #1 | Cash payment 500,00 on 05.10: +500,00, cash 1 500,00 | TI `AccountBalancesTests.example1…`, CBX row 1; T `PaymentMovementsTests.aCashPaymentWritesOneMovement…`; ACC story |
| 2.2 #2 | Reversal on 12.10: line −500,00 dated that day | TI `AccountBalancesTests.example10TheReversalOfACashPayment…`; T `PaymentMovementsTests.aReversalWritesTheNegatedMovementTodayInTheOpenMonth…` |
| 2.2 #3 | Expense 250,00 on 200,00 refused with balance and day | TI `CashGuardTests.example4…`, CBX row 4; T `ExpensesTests.theCashNeverGoesNegative…`; V `ExpenseForm.test` (shows balance and day); ACC story |
| 2.2 #4 | Back-dated 400,00 refused (07.10 would be −200,00) | TI `CashGuardTests.example5…`, CBX row 5; T `ExpensesTests.theCashNeverGoesNegative…`; ACC story |
| 2.2 #5 | Closed month: date 30.09, month October | T `ExpensesTests.anExpenseDatedInAClosedMonth…`, `HandoversTests.aHandoverDatedInAClosedMonth…`, `OtherIncomesTests.anIncomeDatedInAClosedMonth…`, `PaymentMovementsTests` |
| 2.2 #6 | Payment reversal below zero is saved, cash card warns | TI `AccountBalancesTests.example10AReversalThatTakesTheCashBelowZero…`; T `MoneyAccountsTests.aPaymentReversalThatTakesTheCashBelowZero…`; V `CashbookPage.test` (negative warnings) |
| 2.2 #7 | Bank may go negative, bank card warns | T `ExpensesTests.aBankExpenseMayTakeTheBankBelowZero…`; V `CashbookPage.test` (negative bank text) |
| 2.2 #8 | Two 150,00 on 200,00 at once: exactly one | T `ExpenseConcurrencyTests.twoCashExpensesOf150On200SavedAtTheSameMomentSaveExactlyOne` |
| 2.3 #1 | Cash expense 300,00 with photo: listed, cash down, card shows photo | T `ExpensesTests.aCashExpenseWithAPhotoIsRecorded…`; ACC story; E2E HAND (smoke: expense with `setInputFiles`, card with the file, cash delta) |
| 2.3 #2 | Downloaded photo has no GPS or camera data | T `ExpensesTests.aCashExpenseWithAPhoto…WithoutGpsOrCameraData`; TI `JpegMetadataStripperTests`, `PngMetadataStripperTests`; ACC story |
| 2.3 #3 | 6 MB file: «Файл завеликий» | T `ExpensesTests.theFileLimitsAreCheckedByContentAndSize…`; TI `AttachmentUploadsTests.theRefusalsNameTheFile`; V `ExpenseForm.test`; catalog `expenses.attachments.tooLarge` |
| 2.3 #4 | Text file renamed `receipt.jpg`: type message | T `ExpensesTests.theFileLimitsAreChecked…` (by content); TI `FileSnifferTests`; V `ExpenseForm.test` (wrong type) |
| 2.3 #5 | Sixth file: «Не більше 5 файлів» | T `ExpensesTests.theFileLimitsAreChecked…`; TI `AttachmentUploadsTests`; V `ExpenseForm.test`; catalog `tooMany` |
| 2.3 #6 | No cash book right: no expense or attachment, «no access» | T `ExpensesAccessTests.aMemberARoleWithoutTheRightsAndTheOperatorAreRefused`; ACC rights (card, download, list: 403 for a member and the operator, 401 without token); E2E HAND (member: no menu entry, no balances, API 403); `App.tsx` `RequirePermission` |
| 2.3 #7 | Reverse and re-enter with a reason: reversed, cash restored, form prefilled | T `ExpensesTests.aReversedExpenseIsReEnteredWithItsOldFilesAndTheCashIsRestored`; V `ExpenseCardPage.test` (reason, reverses, opens the form prefilled); ACC story (blank reason 400, reversal restores cash, once) |
| 2.3 #8 | No «Сторнувати» without approve | V `ExpenseCardPage.test` (hides the reverse button without `CASHBOOK_APPROVE`); T `ExpensesAccessTests.reversingAndTheCategoriesNeedTheRightToApprove`; ACC rights (treasurer 403); E2E HAND (no button on the card) |
| 2.4 #1 | Handover 1 000,00 «КВ-123»: cash 200,00, bank 21 000,00, both journals | T `HandoversTests.example3…`; CBX row 3; ACC story (both journals carry «КВ-123»); E2E HAND (smoke: deltas and the slip number in both journals) |
| 2.4 #2 | Chairman reverses the handover: both restored | T `HandoversTests.theReversalRestoresBothAccountsIsDatedTodayAndHappensOnce`; CBX row 3 (reversed) |
| 2.5 #1 | Bank interest 35,00: bank up, category in the journal | T `OtherIncomesTests.bankInterestOf35RaisesTheBankBalanceAndTheJournalShowsItsCategory` |
| 2.5 #2 | Reversal of cash income refused if later cash goes negative | T `OtherIncomesTests.theReversalOfACashIncomeIsRefusedWhenALaterExpenseWouldLeaveTheCashNegative`; CBX row 3 |
| 2.6 #1 | Count equals the book: «Збігається» | T `CashCountsTests.aCountThatMatchesIsSavedAsMatches…` |
| 2.6 #2 | Difference without explanation: «Вкажіть пояснення» | T `CashCountsTests.aDifferenceNeedsAnExplanationOnTheSave…`; V `CashCountPage.test`; ACC story; E2E COUNT (message on the screen) |
| 2.6 #3 | Count 180,00 against 200,00: −20,00, card «Неврегульована різниця» | T `CashCountsTests.example6…`; ACC story; E2E COUNT (shortage of the spec's own, card text, API) |
| 2.6 #4 | Chairman books «Нестача»: cash 180,00, difference gone | T `CashCountsTests.example6…`, CBX row 6; V `CashCountPage.test` (books with a reason); ACC story; E2E COUNT (chairman books, cash delta, no second booking) |
| 2.6 #5 | Act PDF: balances, difference, signature lines | T `CashCountActTests.theActOfAShortage…`; ACC story (act text); E2E COUNT (act downloads) |
| 2.6 #6 | Treasurer: no «Оформити різницю» | V `CashCountPage.test` (hides the booking button); T `CashCountsAccessTests.theTreasurerCountsAndSeesButNeitherBooksNorReverses`; ACC story (403); E2E COUNT (no button, API 403) |
| 2.7 #1 | Statement 100/50/20/200: «Цифри виписки не сходяться» | TI `StatementRulesTests.example9…`, CBX row 9; T `BankReconciliationTests.example9…`; V `BankReconciliationPage.test` (inconsistent text); ACC story; E2E BANK (message on the screen) |
| 2.7 #2 | Statement 20 000/3 000/150/22 850: «out» and closing differ, «Звірено» disabled | T `BankReconciliationTests.example7…`; TI `BankComparisonTests`; V `BankReconciliationPage.test` (disabled while unbalanced); ACC story; E2E BANK |
| 2.7 #3 | Bank expense 150,00 «Банківські комісії»: differences 0,00, month can be reconciled | T `BankReconciliationTests.example7…`, CBX row 7; ACC story (to `RECONCILED`) |
| 2.7 #4 | Bank payment dated 20.10 entered later: «Потребує повторної звірки» | T `BankReconciliationTests.example8…`, `aCashLineAndALineOfAnotherMonthDoNotReopen…`; CBX row 8; V `BankReconciliationPage.test` (shows what reopened); ACC story; E2E BANK (payment dated in the month, status on the bank card and the page) |
| 2.7 #5 | Chairman books a bank difference with a reason: in the journal, closing difference 0,00 | T `BankReconciliationTests.theChairmanBooksABankDifference…`; V `BankReconciliationPage.test`; E2E BANK (chairman books, treasurer then marks «Reconciled») |
| 2.7 #6 | Closing difference 0,00 while the total in and out each differ: a warning, and «Звірено» can be pressed (owner decision on PR #66) | T `BankReconciliationTests.aMonthWhoseClosingAgreesIsReconciled…`, `confirmIsRefusedWithoutAStatementAndWhileTheClosingDifferenceIsNot0`; TI `BankComparisonTests.incomingOutgoingAndOpeningDifferencesDoNotBlock…`; ACC closing (a line the bank books in the next month, both months reconciled); the on-screen warning: the web tests (`BankReconciliationPage.test`) |
| 2.8 #1 | Reversal without a reason: «Вкажіть причину» | T `ExpensesTests.aReversedExpense…`, `HandoversTests.theReversalRestores…`, `OtherIncomesTests`; V `ExpenseCardPage.test`; ACC story |
| 2.8 #2 | Already reversed: no «Сторнувати» | V `ExpenseCardPage.test` (hides the reverse button on a reversed expense); T `ExpensesTests` (`ENTRY_ALREADY_REVERSED`); ACC story (409) |
| 2.9 #1 | Rename «Інше»: old expenses show the new name | T `CategoriesTests.aRenamedCategoryShowsItsNewNameOnOldExpensesToo`; V `ExpenseCategoriesPanel.test` |
| 2.9 #2 | Archive: not offered, old expenses keep it | T `CategoriesTests.anArchivedCategoryIsNotOfferedButStaysOnOldExpenses`; V `ExpenseForm.test` (offers non-archived) |
| 2.9 #3 | Same name: «Така стаття вже є» | T `CategoriesTests.aCategoryIsAddedAtTheEndAndNoTwoOfOneListShareAName`, `TreasuryGuardsTests.aCategoryNameIsUniquePerKindIgnoringCase`; V `ExpenseCategoriesPanel.test` (says the name is taken) |
| 2.10 #1 | Board downloads the act of plot 9 for 01.09–31.10 (a start before the plot's first ledger day starts on that day), ends equal the plot balances report | L `PlotStatementTests.bothEndsEqualThePlotBalancesReportOnThoseDays`, `theStartPlusTheLinesIsTheEnd…`; D `ReconciliationActTests.theActShowsTheRequisites…`, `ReconciliationActTests.aStartBeforeThePlotsFirstLedgerDayGivesTheActFromThatDay`; ACC story (treasurer downloads any plot's act); E2E ACT (board: act from the plot card) |
| 2.10 #2 | Member's account: act per own plot, downloaded act shows the plot | D `ReconciliationActAccessTests.aMemberGetsTheActOfTheirOwnPlotAlsoFromADayBeforeItsFirstCharge`; ACC story (own plot's number in the PDF); E2E ACT (account button per own plot, PDF downloaded) |
| 2.10 #3 | Member asks for another plot: «not found» | D `ReconciliationActAccessTests.anotherMembersPlotIsNotFound…`; ACC story, isolation; E2E ACT (404 `PLOT_NOT_FOUND` for every plot the member does not own) |
| 2.10 #4 | Period ending after today (or starting after its end): «Невірний період» | D `ReconciliationActTests.thePeriodMustNotEndAfterTodayNorStartAfterItsEndAndThePlotNeedsALedger`; V `ReconciliationActForm.test`; ACC story (400 `INVALID`); E2E ACT (message on the form) |
| 2.10 #5 | Requisites missing: same message as invoices | D `ReconciliationActTests.commonLandIsRefused…AndTheRequisitesAreRequired`, `ReconciliationActAccessTests` (`REQUISITES_MISSING`); V `ReconciliationActForm.test` (requisites warning) |
| 2.11 #1 | Member: no «Каса і банк» in the menu | V `Layout.test` (menu by permission, `CASHBOOK_VIEW` item); E2E HAND (member: no link, no balances, API 403); ACC rights |
| 2.11 #2 | View without enter: balances, no entry buttons | V `CashbookPage.test` (entry buttons only with `CASHBOOK_ENTER`); ACC rights (a `CASHBOOK_VIEW` role reads, cannot enter) |
| 2.11 #3 | Phone: camera as attachment | V `ExpenseForm.test` (camera input), `AttachmentPicker` (`capture="environment"`); **manual**: taking a photo with a real phone camera cannot be automated |
| 2.12 #1 | «Касова книга»: closing of each day = cash balance | T `CashBookReportTests.everyDaysClosingBalanceIsTheCashBalanceOfThatDay` |
| 2.12 #2 | «Доходи та витрати»: result = change of cash + bank; member lines = «Платежі» allocations | T `IncomeExpenseReportTests` (both periods, the payments report's allocations); TI `IncomeExpenseCalculatorTests.incomeMinusExpensesPlusDifferencesIsTheChangeOfCashAndBank`, `DemoTreasuryTests.incomeMinusExpensesPlusDifferencesIsTheChangeOfCashAndBank` (demo data); ACC story (result = change over the story's period, ends = balances) |
| 2.12 #3 | Custom role with only the view right: the four reports, none other | T `TreasuryReportsAccessTests.aRoleWithOnlyTheCashBookViewSeesExactlyTheFourAndRunsThem`, `aRoleWithOnlyTheReportsViewSeesNone…`; `reports` `ReportsAcceptanceTests` (cash book reports listed and gated); ACC rights (view-only role runs `cash-book`) |
| 2.12 #4 | «Витрати» filtered by «Матеріали», links to attachments | T `ExpensesReportTests.theAccountAndCategoryFiltersChooseTheExpenses`, `everyExpenseIsListedWithItsFilesAnd…` |
| 2.13 #1 | Roles screen: three rights; treasurer two, chairman three | ACC roles (`GET /roles`, `GET /permissions`); `RolesTests`, `SeedBaseRolesTests`, `LedgerMigrationTests` |
| 2.13 #2 | Treasurer cannot book a count difference | T `CashCountsAccessTests.theTreasurerCountsAndSeesButNeitherBooks…`; ACC story and rights (403); E2E COUNT (403) |
| 2.13 #3 | Member opens «Каса і банк» by address: «no access» | ACC rights (403 on every family); E2E HAND (no balances for the member); `App.tsx` `RequirePermission permission="CASHBOOK_VIEW"` |
| 2.13 #4 | User of another cooperative: «not found» | T `ExpensesAccessTests.anotherCooperativeNeverSeesTheseExpensesOrCategories`, `CashCountsAccessTests`, `BankReconciliationsAccessTests`, `MoneyAccountsAccessTests`, `HandoversAndOtherIncomeAccessTests`, `TreasuryTenantIsolationTests` (every table); D `ReconciliationActAccessTests.anotherCooperativeFindsNothing…`; ACC isolation (expense, attachment, reverse, count, act PDF, book difference, movements, movement reverse, reconciliation act) |
| 2.14 #1 | «Витрату внесено» with amount, account, category | T `ExpensesTests.aCashExpenseWithAPhoto…` (audit entry); TI `TreasuryAuditActionsTests`; V `auditValue.treasury.test`; uk/en catalog labels |
| 2.14 #2 | Counterparty and description «приховано» without the personal-data right | T `ExpensesTests.theCounterpartyAndDescriptionAreNeverStoredAuditedOrPublishedInTheClear` (reader with only `AUDIT_VIEW` sees them hidden), `OtherIncomesTests.theDescriptionIsNeverStored…`; V `auditValue.treasury.test` |
| 2.15 #1 | Earlier payments and reversals listed once | T `TreasuryBackfillTests` (exactly one movement per payment row, reversals, idempotent), `PaymentMovementsTests`; TI `DemoTreasuryTests.everyDemoPaymentRowHasItsJournalLine` |
| 2.16 #1 | Demo: balances, last count with shortage, previous month «Звірено», current month with a difference | TI `DemoTreasuryTests.theRunnerEntersTheDemoCashBookOnce`, `theDemoBlockRunsAgainOverTheCashBook…`; E2E BANK (demo bank card: previous month «Reconciled», current month listed and reconciled through the screen), E2E COUNT (demo cash card, last count) |

## Worked examples (2.17)

| # | Event | Tests |
| --- | --- | --- |
| 1 | Cash payment 500,00: cash 1 500,00 | CBX row 1; TI `AccountBalancesTests.example1…`; ACC story |
| 2 | Cash expense 300,00: cash 1 200,00 | CBX row 2; TI `CashGuardTests.example2…`; ACC story; E2E HAND |
| 3 | Handover 1 000,00: cash 200,00, bank 21 000,00 | CBX row 3; T `HandoversTests.example3…`; ACC story; E2E HAND |
| 4 | Expense 250,00 refused (balance 200,00) | CBX row 4; TI `CashGuardTests.example4…`; ACC story |
| 5 | Back-dated 400,00 refused (07.10 would be −200,00) | CBX row 5; TI `CashGuardTests.example5…`; ACC story |
| 6 | Count 180,00 against 200,00; shortage booked, cash 180,00 | CBX row 6; T `CashCountsTests.example6…`; ACC story; E2E COUNT |
| 7 | Bank October with the 150,00 fee: «Звірено» | CBX row 7; T `BankReconciliationTests.example7…`; ACC story; E2E BANK (with a bank difference) |
| 8 | Back-dated bank payment reopens the month | CBX row 8; T `BankReconciliationTests.example8…`; ACC story; E2E BANK |
| 9 | Statement 100/50/20/200 refused | CBX row 9; TI `StatementRulesTests.example9…`; ACC story |
| 10 | Cash payment reversal: line on the day, cash down, still allowed with a warning | TI `AccountBalancesTests.example10…` (both); T `MoneyAccountsTests.aPaymentReversalThatTakes…` |
| 11 | Act of plot 9: ends equal the plot balances report | L `PlotStatementTests.bothEndsEqualThePlotBalancesReportOnThoseDays`; D `ReconciliationActTests`; ACC story |
| 12 | Income and expense: member lines = payments allocations; result = change of cash + bank | T `IncomeExpenseReportTests`; TI `IncomeExpenseCalculatorTests`, `DemoTreasuryTests`; ACC story |

## Summary

- Criteria in the spec: **62** (2.1: 5, 2.2: 8, 2.3: 8, 2.4: 2, 2.5: 2, 2.6: 6, 2.7: 6, 2.8: 2, 2.9: 3, 2.10: 5,
  2.11: 3, 2.12: 4, 2.13: 4, 2.14: 2, 2.15: 1, 2.16: 1) and 12 worked examples.
- Covered by automated tests: **62 of 62** (all 12 examples too). Of them 2.11 #3 is covered only down to the markup
  (a camera input with `capture="environment"` and the form test); it is also **manual** (take a photo with a phone).
- Documentation only: none of the 62. The documentation items of the feature document §12 (money rules, glossary, ADR
  0019, both guides in two languages, screenshots list) are checked by review, `pnpm exec prettier --check docs/guide` and
  `make guide`, not by tests.
- Manual: 1 (2.11 #3, real phone camera).
- Remarks: the e2e specs work on the demo cooperative; its opening balances are written once, so they assert deltas. The
  month of `bank-reconciliation.spec.ts` is the current month read at run time; a later bank line dated in it (the
  smoke handover, a payment) sets it back to «Needs a new check», which the spec expects. The form's default start of
  the reconciliation act (1 January) is earlier than the first charge of a young ledger such as the demo; the e2e sets a
  valid start (see the GAP note under Slice 9 in `tasks.md`).
