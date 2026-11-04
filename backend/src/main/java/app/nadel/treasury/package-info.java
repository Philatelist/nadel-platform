/**
 * The cooperative's own money (feature 018): the cash account and the bank account ({@code money_account}, V32), their
 * opening balances (written once), the append-only journal of every move of money ({@code account_movement}) and the
 * balance of an account on a day (never stored: the opening amount plus the movements dated from the opening date),
 * expenses with their receipt files ({@code expense}, {@code expense_attachment}) and the expense and income categories
 * ({@code expense_category}), handovers ({@code handover}), other income ({@code other_income}) and cash counts
 * ({@code cash_count}); {@code bank_reconciliation} gets its writer in a later slice. Every table is under RLS {@code tenant_isolation}.
 * <p>
 * Public API: the codes {@link app.nadel.treasury.MoneyAccountKind}, {@link app.nadel.treasury.MovementKind} and
 * {@link app.nadel.treasury.BankMonthStatus}, {@link app.nadel.treasury.CashCountActs} with
 * {@link app.nadel.treasury.CashCountAct} (the figures of a cash count for its act in {@code documents}) and
 * {@link app.nadel.treasury.TreasuryReadModel} with its records (the four cash book reports in {@code reports},
 * {@code CASHBOOK_VIEW}).
 * Everything else is internal: the endpoints of tag {@code treasury} ({@code TreasuryController}), the accounts service
 * ({@code MoneyAccounts}: the list with balances, the opening balance, the journal of a period), the sole writer of the
 * journal ({@code MovementWriter}), the reads ({@code TreasuryQueries}), the pure balance rule ({@code AccountBalances}),
 * the listener of {@link app.nadel.ledger.PaymentMoneyMoved} ({@code PaymentMovementListener}: a plain synchronous
 * {@code @EventListener} that writes the member payment movements in the payment's own transaction, ADR 0019), the
 * expenses ({@code Expenses}, {@code ExpenseQueries}, the no-negative-cash rule {@code CashGuard}, the attachment checks
 * {@code AttachmentUploads}, {@code FileSniffer}, {@code JpegMetadataStripper}, {@code PngMetadataStripper}), the
 * categories ({@code Categories}), the cash counts ({@code CashCounts}, {@code CashCountQueries}, the pure
 * {@code CashCountFigures}), the contract form of journal lines ({@code JournalLines}), the sealing of personal texts ({@code TreasuryCipher}) and the audit publisher
 * ({@code TreasuryAuditPublisher}).
 * <p>
 * Depends on {@code ledger} ({@code PaymentMoneyMoved}, {@code PaymentSource}, {@code LedgerReadModel#paymentRefs},
 * {@code BookingMonths}), {@code settings} ({@code CooperativeSettings}: the IBAN), {@code audit} ({@code AuditEvent},
 * {@code ActorNames}: who entered a line), {@code privacy} ({@code PersonalDataCipher}), {@code identity}
 * ({@code CurrentUser}, {@code AccessProfile}), {@code tenancy}
 * ({@code TenantContext}), {@code problem} and {@code money}; it never reads another module's tables. No module depends
 * on it except {@code documents} (the cash count act) and {@code reports} (the cash book reports); it never depends on
 * either.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Treasury (cash and bank)")
package app.nadel.treasury;
