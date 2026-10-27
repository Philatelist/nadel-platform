/**
 * Built-in reports over what the other modules have booked (charges, debts, meters, water, land tax, corrections):
 * the list of reports a caller may run ({@code GET /reports}), the on-screen preview ({@code GET /reports/{code}})
 * and the Excel file ({@code GET /reports/{code}.xlsx}); and the report templates of the cooperative (tables
 * {@code report_template} and {@code report_template_version}: upload with its safety checks, versions, archive,
 * download, the fixed tax report slot). The module adds no money rule: it reads only through the public read facades
 * of the modules that own the data and never their tables.
 * <p>
 * The module has no public API; everything is internal: the endpoints, the framework ({@code ReportCatalog},
 * {@code ReportDefinition}, {@code ReportFilters}, {@code ReportRunner}, {@code XlsxReportWriter},
 * {@code ReportLanguage}, {@code ReportTexts}), one definition per report and the template store
 * ({@code ReportTemplateService}, {@code TemplateUploadChecks}). Depends on {@code ledger} ({@code LedgerReadModel}),
 * {@code meters} ({@code MeterReports}: the electricity tree), {@code plots} ({@code PlotCatalog}, {@code PlotOwners},
 * {@code PlotStatuses}), {@code members} ({@code MemberDirectory}: the debtors' contacts, the names of who booked a
 * correction or uploaded a template), {@code settings} ({@code CooperativeSettings}: the red threshold, the losses
 * mode), {@code tariffs} ({@code ChargeTypeCatalog}, {@code ExpectedAmounts} for the land tax), {@code tenancy}
 * ({@code TenantDirectory}), {@code identity} ({@code CurrentUser}, {@code Permission}), {@code xlsx}
 * ({@code WorkbookGuard}: the uploaded templates), {@code audit}, {@code money} and {@code problem}; no module depends
 * on it.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Reports")
package app.nadel.reports;
