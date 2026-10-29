/**
 * Documents built from what other modules have booked (feature 016): the printed cash receipt of a payment
 * ({@code GET /payments/{id}/receipt.pdf}) and the invoice of a plot for a billing month with the NBU payment QR code
 * ({@code POST /plots/{id}/invoices}, {@code GET /invoices...}); debt certificates follow. Every document is a PDF in
 * Ukrainian, drawn with Apache PDFBox and the embedded IBM Plex Sans (ADR 0017). A receipt is made on request and never
 * stored; an invoice version is stored as a snapshot (table {@code invoice}, append-only, the names sealed) and its PDF
 * is rebuilt from it on every download. The module adds no money rule (an invoice's figures are the ledger's
 * booking-month rule) and reads only through the public APIs of the modules that own the data, never their tables.
 * <p>
 * The public API is {@code DocumentsFacade} with {@code PrintScope}: ids, links and PDF bytes for feature 017 (the
 * delivery of invoices), for the cooperative the caller bound, without permission checks of its own. Everything else is
 * internal:
 * the PDF layer ({@code internal.pdf}: {@code PdfDocument}, {@code TextBlock}, {@code Table}, {@code PdfFonts}), the
 * texts and formats ({@code DocumentTexts}, {@code DocumentFormat}, {@code AmountInWords}), the NBU payment QR code
 * ({@code internal.nbu}: {@code NbuQrPayload}, {@code NbuQrImage}, {@code HryvniaSign}; ZXing core), the receipt
 * ({@code CashReceipts}, {@code ReceiptContent}, {@code ReceiptLayout}), the invoice ({@code InvoiceBuilder},
 * {@code InvoiceCalculator}, {@code InvoiceFigures}, {@code InvoiceDocument}, {@code InvoiceSnapshots},
 * {@code InvoiceLayout}, {@code Invoices}, {@code RequisitesCheck}, {@code InvoiceRecord}, {@code InvoiceAuditPublisher},
 * the month build's {@code ListedMonths} and the print set {@code PrintBundle}; {@code DocumentsFacadeService})
 * and the endpoints of tag {@code documents} ({@code DocumentsController}). Depends on {@code ledger}
 * ({@code LedgerReadModel#cashReceipt}, {@code #invoiceLedgers}, {@code #firstBillingMonth}; {@code PaymentNotes}: the payer note, only for a viewer
 * with {@code MEMBERS_VIEW}), {@code plots} ({@code PlotCatalog}, {@code PlotOwners}), {@code members}
 * ({@code MemberDirectory}: the payer, the owners and their delivery channels; {@code RoleHolders}: the chairmen and treasurers), {@code audit}
 * ({@code ActorNames}: who accepted the money; {@code AuditEvent}), {@code tariffs} ({@code ChargeTypeCatalog}),
 * {@code settings} ({@code CooperativeSettings}: the requisites), {@code tenancy} ({@code TenantDirectory}: the name and
 * ЄДРПОУ), {@code identity} ({@code CurrentUser}, {@code OwnedPlotsSource}, {@code Permission}), {@code privacy}
 * ({@code PersonalDataCipher}: the sealed names and the content hash), {@code money} and {@code problem}; no module
 * depends on it.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Documents")
package app.nadel.documents;
