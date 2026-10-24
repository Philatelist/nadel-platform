# Tasks: Reports and report templates (013)

Rules for every slice: contract first (`api/openapi.yaml`, then `make gen`), Flyway only through the new `V20__reports.sql`, every text through the i18n catalogs (uk default), no personal data in logs, no new dependency, no edits to generated code. Verify tasks run only the slice's own narrow tests (see CLAUDE.md verification levels); the full `make check` and `make e2e` run once in Phase 4. Every Verify task stops anything it started and deletes its own temporary files.

- [x] **Slice 1: Permissions, requisites and tenant ЄДРПОУ**
  - [x] Write `V20__reports.sql`: `report_template` and `report_template_version` (RLS, grants, guard trigger, constraints as in technical-considerations 2.4), requisite columns on `cooperative_settings`, re-created `seed_base_roles`, targeted `role_permission` inserts for existing cooperatives. **[Agent: postgres-database]**
  - [x] Add `REPORTS_VIEW` and `REPORT_TEMPLATES_MANAGE` to `Permission`, `BaseRoles`, `PermissionCatalog`, the OpenAPI `Permission` enum, and both i18n catalogs; update `BaseRolesMatrixTests` and `SeedBaseRolesTests`. **[Agent: spring-backend]**
  - [x] Extend settings with requisites (`Iban` value object with mod 97, `IBAN_INVALID`, audited through `SETTINGS_UPDATED`), `TenantSummary.edrpou`, read-only `edrpou` in `GET /settings`; update `settings/README.md`. **[Agent: spring-backend]**
  - [x] Add the `RequisitesPanel` to the settings page (IBAN field error, read-only ЄДРПОУ with hint), i18n uk and en. **[Agent: react-frontend]**
  - [x] Isolation, privilege and constraint tests for the two new tables; migration test of V20 over V19. **[Agent: postgres-database]**
  - [x] Verify: `cd backend && ./mvnw test -Dtest='app.nadel.settings.**,app.nadel.members.**,app.nadel.tenancy.**,app.nadel.identity.**,app.nadel.ModularityTests'` and `pnpm -r typecheck && pnpm -r lint && pnpm --filter web exec vitest run src/pages/SettingsPage.test.tsx` and `pnpm --filter shared test`; delete temporary files. **[Agent: spring-backend]**

- [x] **Slice 2: Shared workbook guard (`xlsx` module)**
  - [x] Create module `app.nadel.xlsx` with public `WorkbookGuard` and `UnsafeWorkbookException`; move `ZipGuard` and its tests; rewire `imports` to map the exception to `FILE_NOT_XLSX` with unchanged behaviour. Update ADR 0012 note. **[Agent: spring-backend]**
  - [x] Verify: `cd backend && ./mvnw test -Dtest='app.nadel.xlsx.**,app.nadel.imports.**,app.nadel.ModularityTests'`; delete temporary files. **[Agent: spring-backend]**

- [x] **Slice 3: Reports section and Charges by month**
  - [x] Contract: tag `reports` with `GET /reports`, `GET /reports/{code}`, `GET /reports/{code}.xlsx` (+ `lang`), the `ReportCode` enum of all eight reports, the typed filter union, error codes, `AuditAction` and `AuditEntityType` additions; run `make gen`. **[Agent: spring-backend]**
  - [x] `LedgerReadModel.monthTotals` (billing-month and service-month grouping, same predicate as the billing months screen). **[Agent: spring-backend]**
  - [x] Report framework in `reports`: `ReportCatalog`, `ReportDefinition`, `ReportRunner` (plain transaction, 20 000 cap, audit of preview and file, `REPORT_GENERATED`), `XlsxReportWriter`, `ReportLanguage`, uk and en bundles, `charges-by-month` definition with its second sheet, controllers, `reports/README.md`. **[Agent: spring-backend]**
  - [x] Web shell: route gating, menu item, `ReportsPage`, generic `ReportFilterForm`, `ReportPreviewTable`, download, error mapping, i18n uk and en, bundle-to-catalog parity test. **[Agent: react-frontend]**
  - [x] Tests: reconciliation of charges-by-month with billing months for every month on a worked fixture (reversal, later-month adjustment), 403/401/operator checks, `.xlsx` route precedence, audit entry, cap `REPORT_TOO_LARGE`, language of headers. **[Agent: spring-backend]**
  - [x] Verify: `cd backend && ./mvnw test -Dtest='app.nadel.reports.**,app.nadel.ledger.**,app.nadel.ModularityTests'` and `pnpm -r typecheck && pnpm -r lint && pnpm --filter web exec vitest run src/pages/ReportsPage.test.tsx src/pages/ReportPage.test.tsx` and `pnpm --filter shared test`; delete temporary files. **[Agent: spring-backend]**

- [x] **Slice 4: Plot balances and Debtors**
  - [x] Facades: `PlotCatalog.findActive`, `PlotOwners`, `PlotStatuses` and `PlotStatus`, `LedgerReadModel.debtsAsOf`, `MemberDirectory.contacts`, settings thresholds. **[Agent: spring-backend]**
  - [x] `plot-balances` and `debtors` definitions (OR thresholds, past-date status note, extra `MEMBERS_VIEW` with 403, card hidden when not allowed); header notes; i18n. **[Agent: spring-backend]**
  - [x] Web: Debtors card hidden and direct URL shows no access; unknown and note rendering. **[Agent: react-frontend]**
  - [x] Tests on fixed fixtures with worked numbers; Debtors without `MEMBERS_VIEW` is 403; two owners; no names in plot balances. **[Agent: spring-backend]**
  - [x] Verify: `cd backend && ./mvnw test -Dtest='app.nadel.reports.**,app.nadel.plots.**,app.nadel.members.**,app.nadel.ledger.**,app.nadel.ModularityTests'` and `pnpm --filter web exec vitest run src/pages/ReportsPage.test.tsx src/pages/ReportPage.test.tsx`; delete temporary files. **[Agent: spring-backend]**

- [x] **Slice 5: Corrections and Land tax**
  - [x] `LedgerReadModel.corrections` and `chargedInYear`; `ExpectedAmounts.yearByVersion` in tariffs. **[Agent: spring-backend]**
  - [x] `corrections` and `land-tax` definitions (one row per rate version, mismatch mark and recalculation hint, empty-state message); i18n. **[Agent: spring-backend]**
  - [x] Tests: 35,00 per sotka and 600 m² gives 6 sotkas and 210,00; two rate versions; recalculation correction with cause and reason; kind filter. **[Agent: spring-backend]**
  - [x] Verify: `cd backend && ./mvnw test -Dtest='app.nadel.reports.**,app.nadel.tariffs.**,app.nadel.ledger.**,app.nadel.ModularityTests'`; delete temporary files. **[Agent: spring-backend]**

- [x] **Slice 6: Electricity by meter tree**
  - [x] `MeterReports.tree` (same data as `GET /meter-tree`), `LedgerReadModel.electricityCharged` (consumption, losses fixed and proportional runs, common consumer runs, adjustments and reversals with original service period). **[Agent: spring-backend]**
  - [x] `electricity-tree` definition with the "Consumption" and "Charged" sheets, unknown values empty with reason, totals and losses header; i18n. **[Agent: spring-backend]**
  - [x] Tests on a worked fixture: proportional losses run, common consumer run, meter replacement, unknown value; charged amounts equal the ledger's electricity charges in the range. **[Agent: spring-backend]**
  - [x] Verify: `cd backend && ./mvnw test -Dtest='app.nadel.reports.**,app.nadel.meters.**,app.nadel.ledger.**,app.nadel.ModularityTests'`; delete temporary files. **[Agent: spring-backend]**

- [x] **Slice 7: Water and Missing readings**
  - [x] `MeterReports.waterUse` and `missingReadings`, `LedgerReadModel.waterCharged` and `openIntervalItems`. **[Agent: spring-backend]**
  - [x] `water` and `missing-readings` definitions (connected days only, unconnected plots and out-of-range meters excluded, waiting-for-approval mark, queue link); i18n and web link. **[Agent: spring-backend]**
  - [x] Tests: part-month connection, source filter, meter with only a pending reading, water meter of an unconnected plot. **[Agent: spring-backend]**
  - [x] Verify: `cd backend && ./mvnw test -Dtest='app.nadel.reports.**,app.nadel.meters.**,app.nadel.ledger.**,app.nadel.ModularityTests'`; delete temporary files. **[Agent: spring-backend]**

- [x] **Slice 8: Report templates: storage, tax slot, upload, versions, archive**
  - [x] Contract for `GET|POST /report-templates`, `/versions`, `/archive`, `/versions/{id}/file`; error codes; `make gen`. **[Agent: spring-backend]**
  - [x] `TemplateStore` (JPA, `content` never loaded in lists), upload validation with `WorkbookGuard`, 2 MB cap, magic bytes, macro and external-link refusal and stripping, dry parse for one repeating set per sheet, version numbering, archive, fixed tax slot entry, audit events for upload and archive. **[Agent: spring-backend]**
  - [x] Web `ReportTemplatesPage`: tax slot card, list with versions, upload with client pre-validation, archive with confirm, version download; i18n. **[Agent: react-frontend]**
  - [x] Tests: refusals (macros, oversize, not xlsx, zip bomb), versions and archive, tax slot status, allowed and denied per endpoint, tenant isolation. **[Agent: spring-backend]**
  - [x] Verify: `cd backend && ./mvnw test -Dtest='app.nadel.reports.**,app.nadel.xlsx.**,app.nadel.ModularityTests'` and `pnpm --filter web exec vitest run src/pages/ReportTemplatesPage.test.tsx src/reportTemplateFile.test.ts`; delete temporary files. **[Agent: spring-backend]**

- [x] **Slice 9: Template filling, field catalog and Generate**
  - [x] ADR 0015 (engine decision: own streaming OOXML filler, POI fallback), spike on formula and merged-range shifting first. **[Agent: spring-backend]**
  - [x] `TemplateField` catalog and `GET /report-templates/catalog`; `TemplateValues` from the report definitions; `TemplateFiller` and `FormulaShifter`; warnings sheet, response headers, document properties; `POST /report-templates/{id}/generate`; audit. **[Agent: spring-backend]**
  - [x] Web: `GenerateDialog` with warnings, `FieldCatalog` with search and copy; i18n of catalog descriptions. **[Agent: react-frontend]**
  - [x] Tests with a sample template shipped in test resources: typed whole-cell values, text substitution, repeating rows, formulas shifted, merged cells kept, unknown placeholder warning, unknown value empty, archived template 409, catalog vs bundles consistency; coverage kept above the floor. **[Agent: spring-backend]**
  - [x] Verify: `cd backend && ./mvnw test -Dtest='app.nadel.reports.**,app.nadel.ModularityTests'` and `pnpm --filter web exec vitest run src/pages/ReportTemplatesPage.test.tsx`; delete temporary files. **[Agent: spring-backend]**

- [ ] **Slice 10: Demo template, cross-links and performance**
  - [ ] `db/demo/R__demo_report_templates.sql` with the sample "Land tax statement" (generated by a script kept in the repository), demo requisites and ЄДРПОУ. **[Agent: postgres-database]**
  - [ ] Links from the billing months screen and the plot account to the matching report with filters set; i18n. **[Agent: react-frontend]**
  - [ ] 300 plots x 12 months fixture: every report under 5 s; EXPLAIN review and only the justified index (adjustments join) if needed. **[Agent: postgres-database]**
  - [ ] Verify: `cd backend && ./mvnw test -Dtest='app.nadel.reports.**'` and `pnpm --filter web exec vitest run src/pages/ChargesMonthPage.test.tsx`; delete temporary files. **[Agent: spring-backend]**

- [ ] **Slice 11: Documentation, guides and e2e specs**
  - [ ] `money-rules.md` section 12, glossary entries, ADR 0009 permission list, `context/product/architecture.md` if it lists modules, `reports/README.md` final. **[Agent: general-purpose]**
  - [ ] Guides in uk and en: board guide chapter 21 to "works", owner manual screens, permissions matrix and template security; new screens in `docs/guide/scripts/capture-screens.mjs` (`reports`, `report-electricity`, `report-templates`); rebuild PDFs with `make guide-shots` and `make guide`. **[Agent: general-purpose]**
  - [ ] Playwright specs `reports.spec.ts` (`@smoke` electricity download), `report-templates.spec.ts` (generate demo land tax statement), `reports-acceptance.spec.ts` (custom role without `MEMBERS_VIEW`, Treasurer, member and electrician); add the new screens to `language-purity.spec.ts`. **[Agent: react-frontend]**
  - [ ] Verify: `pnpm --filter web exec playwright test reports report-templates language-purity` and `pnpm --filter web exec playwright test --grep @smoke` (infra and backend jar started by the e2e setup; stop them by recorded PID afterwards); delete screenshots and temporary files. **[Agent: react-frontend]**

- [ ] **Slice 12: Feature Testing & Regression**

  > Verifies the whole feature end-to-end against functional-spec.md, run after all implementation slices are complete.
  - [ ] Read functional-spec.md acceptance criteria in full. Generate acceptance-level tests that verify the entire feature as a whole — not individual slices. Cover applicable layers (unit for pure logic, integration for service interactions, e2e for user flows) based on the project's testing stack. Write tests with RED validation (must fail before implementation is confirmed done). Annotate each test with `@spec: context/spec/013-reports` and `@regression` if suitable for long-term regression. **[Agent: testing-expert]**
  - [ ] Run all generated tests. All must pass. Fix any failures before proceeding. **[Agent: testing-expert]**
