/**
 * Documents built from what other modules have booked (feature 016): so far the printed cash receipt of a payment
 * ({@code GET /payments/{id}/receipt.pdf}); invoices with the NBU payment QR code and debt certificates follow. Every
 * document is a PDF in Ukrainian, drawn with Apache PDFBox and the embedded IBM Plex Sans (ADR 0017). A receipt is
 * made on request and never stored. The module adds no money rule and reads only through the public APIs of the
 * modules that own the data, never their tables.
 * <p>
 * The module has no public API yet (the facade for feature 017 comes with the invoices); everything is internal: the
 * PDF layer ({@code internal.pdf}: {@code PdfDocument}, {@code TextBlock}, {@code Table}, {@code PdfFonts}), the texts
 * and formats ({@code DocumentTexts}, {@code DocumentFormat}, {@code AmountInWords}), the receipt
 * ({@code CashReceipts}, {@code ReceiptContent}, {@code ReceiptLayout}) and the endpoints of tag {@code documents}
 * ({@code ReceiptController}). Depends on {@code ledger} ({@code LedgerReadModel#cashReceipt}; {@code PaymentNotes}:
 * the payer note, only for a viewer with {@code MEMBERS_VIEW}), {@code plots} ({@code PlotCatalog}), {@code members}
 * ({@code MemberDirectory}: the payer), {@code audit} ({@code ActorNames}: who accepted the money), {@code tariffs}
 * ({@code ChargeTypeCatalog}), {@code settings} ({@code CooperativeSettings}: the requisites), {@code tenancy}
 * ({@code TenantDirectory}: the name and ЄДРПОУ), {@code identity} ({@code CurrentUser}, {@code OwnedPlotsSource},
 * {@code Permission}), {@code money} and {@code problem}; no module depends on it.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Documents")
package app.nadel.documents;
