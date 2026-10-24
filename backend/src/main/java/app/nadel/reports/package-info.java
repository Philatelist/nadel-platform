/**
 * Built-in reports over what the other modules have booked (charges, debts, meters, water, land tax, corrections):
 * the list of reports a caller may run ({@code GET /reports}), the on-screen preview ({@code GET /reports/{code}})
 * and the Excel file ({@code GET /reports/{code}.xlsx}). Later also the report templates. The module adds no money
 * rule: it reads only through the public read facades of the modules that own the data and never their tables.
 * <p>
 * The module has no public API; everything is internal: the endpoints, the framework ({@code ReportCatalog},
 * {@code ReportDefinition}, {@code ReportFilters}, {@code ReportRunner}, {@code XlsxReportWriter},
 * {@code ReportLanguage}, {@code ReportTexts}) and one definition per report. Depends on {@code ledger}
 * ({@code LedgerReadModel}), {@code plots} ({@code PlotCatalog}), {@code tariffs} ({@code ChargeTypeCatalog}),
 * {@code tenancy} ({@code TenantDirectory}), {@code identity} ({@code CurrentUser}, {@code Permission}),
 * {@code audit}, {@code money} and {@code problem}; no module depends on it.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Reports")
package app.nadel.reports;
