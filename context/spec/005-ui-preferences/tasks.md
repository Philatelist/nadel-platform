# Tasks: 005 UI preferences

Spec: [functional-spec.md](functional-spec.md) · Tech: [technical-considerations.md](technical-considerations.md)

Rules for every task: follow `CLAUDE.md` (English in code; UI text only in `packages/shared/src/i18n` in both `uk.ts`
and `en.ts`; contract first; existing migrations never edited; no edits to generated code; named exports). Do not
commit; report every file created or changed.

- [x] **Slice 1: Theme switch**

  > A person picks System / Light / Dark in the header (in the menu on phones), the choice is remembered per browser and applied before the first paint.
  - [x] Implement tech spec 2.1: `web/src/theme.ts` + provider, inline script in `web/index.html`, `index.css` dark blocks and `color-scheme`, `theme.test.ts` updates (both dark blocks = tokens, script/meta colours = tokens), `ThemeSwitch.tsx`, placement in `Layout.tsx` (header; inside `#app-menu` under 768 px for people with a menu; header for people without), i18n keys. Unit tests: storage restore, invalid value → system, throwing storage → system without error, `applyTheme`, ThemeSwitch `aria-pressed` and click, Layout placement. Document the inline-script CSP note in `web/README.md`. **[Agent: react-frontend]**
  - [x] Verify: `pnpm -r test && pnpm -r typecheck && pnpm -r lint && pnpm format:check && pnpm --filter web build` green (contrast test included). **[Agent: react-frontend]**

- [ ] **Slice 2: Cooperative name in the header and the tab title**

  > The header title is the cooperative's name, the product name is a secondary label, and the browser tab reads "<page> — <cooperative>".
  - [ ] Implement tech spec 2.2: header title link with truncation and `title`, product name label, fallback without a tenant; `useOptionalMe`; `web/src/useDocumentTitle.ts` and calls in the pages, `SignIn` and `FullPageMessage`. Unit tests: Layout title and fallback, `useDocumentTitle`. Update `web/e2e/design-system.spec.ts` header assertions (cooperative link instead of the "Наділ" link; "Наділ" still visible as text). **[Agent: react-frontend]**
  - [ ] Verify: `pnpm -r test && pnpm -r typecheck && pnpm -r lint && pnpm format:check` green. **[Agent: react-frontend]**

- [ ] **Slice 3: Base role descriptions in the user's language**

  > Base role descriptions show in Ukrainian or English unless changed; a changed one can be restored; changes are audited.
  - [ ] Backend per tech spec 2.3: OpenAPI (`RoleInput.useDefaultDescription`, `RoleDetails.descriptionCustomized`), `pnpm gen:api`, `V5__role_description_customized.sql`, `BaseRoles` descriptions + `defaultDescription`, `Role` flag, `RoleCommands.update`, `RoleSnapshot.auditDescription`, `RoleDtoMapper`, members README; tests: `RolesTests` description cases, `RoleLogicTests`, `BaseRolesMatrixTests`, `SeedBaseRolesTests`, migration rule check; every shared-DB cleanup resets `description_customized`. **[Agent: spring-backend]**
  - [ ] Web per tech spec 2.3: `roleDescription`, `descriptionToSubmit` (+ tests), RolesPage list/form/restore button, AuditPage field-aware token mapping (+ tests), i18n `roleDescriptions.*` and `rolesPage.restoreDescription`. **[Agent: react-frontend]**
  - [ ] Verify: `cd backend && ./mvnw -B test -Dtest='RolesTests,RoleLogicTests,SeedBaseRolesTests,BaseRolesMatrixTests,RoleNamesAcceptanceTests,MembersAccessAcceptanceTests,ModularityTests'` green; `pnpm -r test && pnpm -r typecheck && pnpm -r lint && pnpm format:check` green; `pnpm gen:api` leaves no diff. **[Agent: spring-backend]**

- [ ] **Slice 4: End-to-end flows, docs and coverage floor**

  > Proves the user flows in a browser and closes the feature's quality bar.
  - [ ] E2E: dark ↔ light switch, reload keeps the choice, `data-theme` present before the app renders (no flash), phone placement (menu for the chairman, header for a member), header title "Demo Garden Cooperative" and tab title, Ukrainian base role descriptions on the Roles page, description change → restore flow with cleanup in `finally`. Fix other specs broken by the header change. **[Agent: testing-expert]**
  - [ ] Verify: `make check` green; raise `coverage.minimum` in `backend/pom.xml` to the measured line coverage rounded down to two decimals (never lower) and re-run `cd backend && ./mvnw -B verify`; `make e2e` green. **[Agent: testing-expert]**

- [ ] **Slice 5: Feature Testing & Regression**

  > Verifies the whole feature end-to-end against functional-spec.md, run after all implementation slices are complete.
  - [ ] Read functional-spec.md acceptance criteria in full. Generate acceptance-level tests that verify the entire feature as a whole — not individual slices. Cover applicable layers (unit for pure logic, integration for service interactions, e2e for user flows) based on the project's testing stack. Write tests with RED validation (must fail before implementation is confirmed done). Annotate each test with `@spec: 005-ui-preferences` and `@regression` if suitable for long-term regression. **[Agent: testing-expert]**
  - [ ] Run all generated tests. All must pass. Fix any failures before proceeding. **[Agent: testing-expert]**
