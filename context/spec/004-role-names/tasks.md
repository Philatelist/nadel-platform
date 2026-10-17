# Tasks: 004 Role names

Spec: [functional-spec.md](functional-spec.md) · Tech: [technical-considerations.md](technical-considerations.md)

Rules for every task: follow `CLAUDE.md` (English in code; UI text only in `packages/shared/src/i18n` in both `uk.ts`
and `en.ts`; contract first; existing migrations are never edited; exact dependency pins; no edits to generated code).
Do not commit; report every file created or changed.

- [ ] **Slice 1: Standard names shown from the new flag ("Казначей")**

  > After this slice every screen shows "Казначей" for the never-renamed Treasurer role in Ukrainian, driven by the new `nameCustomized` flag end to end.
  - [ ] Contract and persistence: `api/openapi.yaml` changes from tech spec 2.2 (RoleInput.useDefaultName, RoleRef/RoleDetails.nameCustomized, descriptions), `make gen`; `V4__role_name_customized.sql`; `Role` flag + `isNameCustomized()` rule + `custom()`; `BaseRoles` seed names + `defaultName`; plumb the flag through `identity/RoleRef`, `RoleGrant`, `RoleGrantRow`, `MemberRoleRow`, `MemberRepository` JPQL, `RoleGrantReader`, `MemberDtoMapper`, `RoleDtoMapper`, `MeController`; demo data custom roles flagged; fix test constructors. Tests: `listIncludesNameCustomizedFlags`, `SeedBaseRolesTests` (seeded not customised; second run keeps a customised name and flag), `BaseRolesMatrixTests` (Java names = SQL seed). Rename logic is NOT part of this task. **[Agent: spring-backend]**
  - [ ] Web: `roleDisplayName`/`useRoleName` per tech spec 2.4 with `web/src/roleName.test.ts`; uk `roles.TREASURER` = "Казначей"; add `nameCustomized` to role fixtures (e.g. `Layout.test.tsx`). **[Agent: react-frontend]**
  - [ ] Verify: `cd backend && ./mvnw -B test -Dtest='SeedBaseRolesTests,BaseRolesMatrixTests,MeEndpointTests,RolesTests,MemberAuthenticationConverterTests,PermissionUnionTests'` green; `pnpm -r test && pnpm -r typecheck && pnpm -r lint && pnpm format:check` green; generated client has no diff after `pnpm gen:api`. **[Agent: spring-backend]**

- [ ] **Slice 2: Rename and restore any role, audited**

  > The chairman renames base roles on the Roles page, sees the base-role hint and the restore button, gets the on-screen-name warning, and the audit log shows old and new names in the reader's language.
  - [ ] Backend: `RoleCommands.update` per tech spec 2.3 (useDefaultName validation, target name, uniqueness for every role excluding self, Chairman lock kept, flag-only persistence, no-op without audit), `RoleSnapshot.auditName` used for the `name` field and `ROLE_UPDATED` label, `MemberRoleCommands` roles audit values via `auditName`; update the Javadoc. Tests in `RolesTests` (replace `baseRolesCannotBeRenamed`; all cases in tech spec 4 and the five worked examples of the feature doc section 3; cleanup resets base names and flags) and `RoleLogicTests.auditNameUsesBaseTokenOnlyForNonCustomizedBaseRoles`; fix the two member-roles audit tests. **[Agent: spring-backend]**
  - [ ] Web: RolesPage editor per tech spec 2.4 (editable name, hint, restore button, Chairman Save, submit stored name when the catalog name is untouched, clash warning via `web/src/roleNameClash.ts` + `roleNameClash.test.ts`), AuditPage token rendering via `auditRoleValue` (+ tests in `roleName.test.ts`), i18n keys `rolesPage.baseHint`, `rolesPage.restoreName`, `rolesPage.nameClash`, updated `problem.BASE_ROLE_IMMUTABLE` (en + uk). **[Agent: react-frontend]**
  - [ ] Verify: `cd backend && ./mvnw -B test -Dtest='RolesTests,RoleLogicTests,AuditReadTests'` green; `pnpm -r test && pnpm -r typecheck && pnpm -r lint && pnpm format:check` green. **[Agent: spring-backend]**

- [ ] **Slice 3: Permissions unchanged by renames, e2e and docs**

  > Proves renames never change who can do what, updates the end-to-end flows and the documentation, and raises the coverage floor.
  - [ ] `MembersAccessAcceptanceTests.renamingEveryBaseRoleDoesNotChangeWhoCanDoWhat`: rename all three base roles, rerun the existing permission checks unchanged (extract shared helpers if needed), restore names and flags in cleanup. **[Agent: spring-backend]**
  - [ ] Docs: note in `docs/features/002-members-access.md` §2.2 that renaming base roles is allowed since 004; `members`/`identity` module READMEs (flag, audit token, the accepted `base:` naming risk); `docs/domain/glossary.md` if it lists base role names. Do not edit CLAUDE.md. **[Agent: spring-backend]**
  - [ ] E2E (`web/e2e/acceptance.spec.ts`): base-role name fields are enabled and Chairman Save is enabled (permissions stay locked); new rename → hint → restore flow for the Treasurer role with restore in `finally`; audit log shows "Treasurer → Bookkeeper" (no raw token). **[Agent: testing-expert]**
  - [ ] Verify: `make check` green, then raise `coverage.minimum` in `backend/pom.xml` to the measured line coverage rounded down to two decimals and re-run `cd backend && ./mvnw -B verify`; then `make e2e` green. **[Agent: testing-expert]**

- [ ] **Slice 4: Feature Testing & Regression**

  > Verifies the whole feature end-to-end against functional-spec.md, run after all implementation slices are complete.
  - [ ] Read functional-spec.md acceptance criteria in full. Generate acceptance-level tests that verify the entire feature as a whole — not individual slices. Cover applicable layers (unit for pure logic, integration for service interactions, e2e for user flows) based on the project's testing stack. Write tests with RED validation (must fail before implementation is confirmed done). Annotate each test with `@spec: 004-role-names` and `@regression` if suitable for long-term regression. **[Agent: testing-expert]**
  - [ ] Run all generated tests. All must pass. Fix any failures before proceeding. **[Agent: testing-expert]**
