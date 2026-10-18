# Tasks: 006 Plots registry

Spec: [functional-spec.md](functional-spec.md) · Tech: [technical-considerations.md](technical-considerations.md)

Rules for every task: follow `CLAUDE.md` (English in code; UI text only in `packages/shared/src/i18n`, both `uk.ts`
and `en.ts`; contract first; existing migrations never edited; RLS/isolation for tenant data; permissions not role
names; no personal data in logs; no edits to generated code). Do not commit; report every file created or changed.

- [x] **Slice 1: Plot records in the database, demo plots, map endpoint moved**

  > The demo cooperative has ~30 grey plots and a road; the map data moves to its own address with permission-based visibility; GREY is labelled by kind.
  - [x] Backend per tech spec 2.1 and the map row of 2.2/2.3: `V6__plots_registry.sql`, demo data rewrite, `Plot`/`PlotStatusEntry` fields, `/plots/map` (`getPlotMap`, optional `areaM2`/`debt`) with visibility and operator guard, `PlotQueries.map`, `PlotStatusPolicy`; `make gen`. Tests: `NaturalSortKeyTests` (ordering, "12а"/"12А" collide), updated `plots/internal/TenantIsolationTests` (counts, GREY, isolation for new columns and `plot_status`), `MeEndpointTests` path/count, map visibility tests (member: no area/debt; treasurer: both; operator: 403). **[Agent: spring-backend]**
  - [x] Web: `MapPage` uses `useGetPlotMap` and tolerates missing area/debt; `plotStatusLabel` helper with i18n `status.GREY_PLOT` / `status.GREY_COMMON` (uk "Без власника" / "Спільні землі"); unit test for the helper. **[Agent: react-frontend]**
  - [x] Verify: `cd backend && ./mvnw -B test -Dtest='NaturalSortKeyTests,TenantIsolationTests,MeEndpointTests,PlotMapTests,ModularityTests'` green; `pnpm gen:api` leaves no diff; `pnpm -r test && pnpm -r typecheck && pnpm -r lint && pnpm format:check` green. **[Agent: spring-backend]**

- [x] **Slice 2: Plots list and plot card (read only)**

  > Everyone in the cooperative can open the plots list and a plot card; people allowed to view plots see every field and land on the list after sign-in.
  - [x] Backend: `GET /plots` (`listPlots`) and `GET /plots/{id}` (`getPlot`), `PlotSummary`, `PlotDetails`, `PlotPage`, `PlotRepository.search` (escaped search, kind/status/archived filters, natural order, paging), `PlotDtoMapper` visibility seam, `make gen`. Tests `PlotsReadTests`: paging, natural order, each filter, search, visibility (member restricted: absent keys, no cadastral search, archived 404; treasurer/chairman full), other tenant's id 404, operator 403. **[Agent: spring-backend]**
  - [x] Web: nav item "Plots" for every signed-in member (first in the menu); `HomeRedirect` (`PLOTS_VIEW` → `/plots`, else `/account`); routes `/plots`, `/plots/:id`; `PlotsPage` (search, kind/status filters, show archived for full viewers, columns by viewer, paging, shared states, tab title) and `PlotPage` (details by viewer, status mark, "Owners" placeholder); `packages/shared` area display helpers; i18n; unit tests (restricted columns, nav, HomeRedirect, area helpers). Update existing e2e expectations for the landing route and the new menu item (treasurer/chairman land on /plots; a plain member now has a menu). **[Agent: react-frontend]**
  - [x] Verify: `cd backend && ./mvnw -B test -Dtest='PlotsReadTests,ModularityTests'` green; `pnpm -r test && pnpm -r typecheck && pnpm -r lint && pnpm format:check && pnpm --filter web build` green. **[Agent: spring-backend]**

- [x] **Slice 3: Create and edit plots, audited**

  > The chairman adds and corrects plots with per-field messages; each change is in the audit log.
  - [x] Backend: `POST /plots`, `PUT /plots/{id}`, `PlotInput`, `PlotData` (+ `CadastralNumber`), `PlotCommands.create/update` (pre-checks, index-name mapping, GREY status row on create, no-op without audit), `PlotSnapshot`, Problem codes `PLOT_NUMBER_TAKEN`, `CADASTRAL_NUMBER_TAKEN`, field codes `CADASTRAL_INVALID`, `AREA_INVALID`, `AuditAction`/`AuditEntityType` additions, `make gen`. Tests: `PlotDataTests`, `CadastralNumberTests`, `PlotSnapshotTests`, `PlotsWriteTests` (create/update, uniqueness incl. other tenant and case, cadastral uniqueness incl. archived, concurrency → 409, treasurer/member 403, audit entries). **[Agent: spring-backend]**
  - [x] Web: `PlotForm` + `PlotFormPage` (routes `/plots/new`, `/plots/:id/edit` with `PLOTS_VIEW` + `PLOTS_EDIT` (after the Slice 3 review fix); area + unit select using `toSquareMetres`/`m2ToSotky`; field-error mapping incl. the two conflict codes), "Add plot" and "Edit" actions, AuditPage support for PLOT entries (link, field and action labels), i18n, unit tests (form conversion and validation, error mapping). **[Agent: react-frontend]**
  - [x] Verify: `cd backend && ./mvnw -B test -Dtest='PlotsWriteTests,PlotDataTests,CadastralNumberTests,PlotSnapshotTests,AuditReadTests,ModularityTests'` green; `pnpm -r test && pnpm -r typecheck && pnpm -r lint && pnpm format:check` green. **[Agent: spring-backend]**

- [x] **Slice 4: Archive, restore and delete**

  > The chairman archives, restores and deletes plots from the card; restore re-checks the number; an archived plot must be restored before deleting.
  - [x] Backend: `POST /plots/{id}/archive`, `/restore`, `DELETE /plots/{id}`, `PLOT_ARCHIVED`, `PlotCommands.archive/restore/delete` (idempotent, restore number re-check, delete removes the status row, seam comment for 007), audit, `plots/README.md`, `package-info`, `make gen`. Tests in `PlotsWriteTests` (archive/restore/delete happy paths, restore conflict, archived delete → 409, 403 for treasurer/member, audit entries). **[Agent: spring-backend]**
  - [x] Web: card actions Archive/Restore (mutation) and Delete with `ConfirmDialog`, navigation after delete, notices for `PLOT_NUMBER_TAKEN` on restore and `PLOT_ARCHIVED`, i18n, unit tests where feasible. **[Agent: react-frontend]**
  - [x] Verify: `cd backend && ./mvnw -B test -Dtest='PlotsWriteTests,ModularityTests'` green; `pnpm -r test && pnpm -r typecheck && pnpm -r lint && pnpm format:check` green. **[Agent: spring-backend]**

- [x] **Slice 5: End-to-end flows, docs and coverage floor**

  > Proves the user flows in a browser and closes the quality bar.
  - [x] E2E `web/e2e/plots.spec.ts`: treasurer lands on the plots list, searches "12" (12 before 12а), opens a card with area/cadastral/building, no edit actions; member opens Plots from the menu, sees no area column and no area/notes on the card; chairman create (area in sotkas) → edit → duplicate number error → archive → show archived → restore → archived delete refused → delete with confirmation; audit row for a plot. Clean up created data in `finally`. Fix other specs broken by the menu/landing changes. Update `docs/domain/glossary.md` if plot terms change. **[Agent: testing-expert]**
  - [x] Verify: `make check` green (if coverage is below the floor, report it with the measured numbers instead of lowering anything); raise `coverage.minimum` in `backend/pom.xml` to the measured line coverage rounded down (never lower) and re-run `cd backend && ./mvnw -B verify`; `make e2e` green. **[Agent: testing-expert]**

- [ ] **Slice 6: Feature Testing & Regression**

  > Verifies the whole feature end-to-end against functional-spec.md, run after all implementation slices are complete.
  - [ ] Read functional-spec.md acceptance criteria in full. Generate acceptance-level tests that verify the entire feature as a whole — not individual slices. Cover applicable layers (unit for pure logic, integration for service interactions, e2e for user flows) based on the project's testing stack. Write tests with RED validation (must fail before implementation is confirmed done). Annotate each test with `@spec: 006-plots-registry` and `@regression` if suitable for long-term regression. **[Agent: testing-expert]**
  - [ ] Run all generated tests. All must pass. Fix any failures before proceeding. **[Agent: testing-expert]**
