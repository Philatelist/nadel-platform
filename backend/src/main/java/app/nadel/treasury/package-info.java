/**
 * The cooperative's own money (feature 018): the cash account and the bank account ({@code money_account}, V32), their
 * opening balances (written once), the append-only journal of every move of money ({@code account_movement}) and the
 * balance of an account on a day (never stored: the opening amount plus the movements dated from the opening date),
 * expenses with their receipt files ({@code expense}, {@code expense_attachment}) and the expense and income categories
 * ({@code expense_category}). The other V32 tables ({@code other_income}, {@code handover}, {@code cash_count},
 * {@code bank_reconciliation}) get their writers in later slices. Every table is under RLS {@code tenant_isolation}.
 * <p>
 * Public API: the codes {@link app.nadel.treasury.MoneyAccountKind} and {@link app.nadel.treasury.MovementKind}.
 * Everything else is internal: the endpoints of tag {@code treasury} ({@code TreasuryController}), the accounts service
 * ({@code MoneyAccounts}: the list with balances, the opening balance, the journal of a period), the sole writer of the
 * journal ({@code MovementWriter}), the reads ({@code TreasuryQueries}), the pure balance rule ({@code AccountBalances}),
 * the listener of {@link app.nadel.ledger.PaymentMoneyMoved} ({@code PaymentMovementListener}: a plain synchronous
 * {@code @EventListener} that writes the member payment movements in the payment's own transaction, ADR 0019), the
 * expenses ({@code Expenses}, {@code ExpenseQueries}, the no-negative-cash rule {@code CashGuard}, the attachment checks
 * {@code AttachmentUploads}, {@code FileSniffer}, {@code JpegMetadataStripper}, {@code PngMetadataStripper}), the
 * categories ({@code Categories}), the sealing of personal texts ({@code TreasuryCipher}) and the audit publisher
 * ({@code TreasuryAuditPublisher}).
 * <p>
 * Depends on {@code ledger} ({@code PaymentMoneyMoved}, {@code PaymentSource}, {@code LedgerReadModel#paymentRefs},
 * {@code BookingMonths}), {@code settings} ({@code CooperativeSettings}: the IBAN), {@code audit} ({@code AuditEvent},
 * {@code ActorNames}: who entered a line), {@code privacy} ({@code PersonalDataCipher}), {@code identity}
 * ({@code CurrentUser}, {@code AccessProfile}), {@code tenancy}
 * ({@code TenantContext}), {@code problem} and {@code money}; it never reads another module's tables. No module depends
 * on it yet.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Treasury (cash and bank)")
package app.nadel.treasury;
