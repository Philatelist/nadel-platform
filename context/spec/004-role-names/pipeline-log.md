# Pipeline log — 004 Role names

- Status: running
- Phase: 2 — branch and specs
- Branch: 004-role-names · Base: main @ 23451fb
- Requirement: docs/features/004-role-names.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: — · Checks: —
- Review run: on (pending)

## Project facts

- Base branch `main` (origin/HEAD unset); local main == origin/main @ 23451fb (includes 003 as 62041c4 and the 004 doc).
- Previous review run (003) stopped before this run (PIDs 40621 40843 40902). Ports 8080/5173 free.
- Pipeline tooling present on main. Working tree clean.
- Build: `cd backend && ./mvnw -B verify` (backend), `pnpm --filter web build` (web).
- Single test: backend `cd backend && ./mvnw -B test -Dtest=<Class>` (Testcontainers, Docker running); web `pnpm --filter web test -- <file>`; e2e `pnpm --filter web test:e2e <spec>` with the stack up.
- Full suite: `make check` (gen, format-check, lint, backend verify with JaCoCo floor `coverage.minimum` 0.90 in backend/pom.xml, typecheck, `pnpm -r test`); plus `make e2e`.
- Agents: spring-backend, postgres-database, react-frontend, expo-mobile, devops-infra, testing-expert (testing), slice-reviewer.
- UI: web. Browser: project Playwright (headless) for verification; the Chrome extension needs a browser choice (question) and is not used.
- Safe local run mode: `make up` (local docker-compose, dev profile). Demo users from CLAUDE.md, password `nadel`.
- Git rules: Conventional Commits for PR title; no AI attribution; feature branch only; only the spec branch is pushed.
- CI checks: Backend, Clients (contract, format, typecheck, unit tests, lint, build), Security, SAST, End-to-end.
- gh: logged in; origin github.com/Philatelist/nadel-platform. Next spec number: 004.

## Settings

- questions: blocking · reviewerEscalation: auto · notGreen: ask · gitUnsafe: ask · notTestableLocally: auto · publish: pr+checks · reviewRun: on · notify: true

## Phase 1 — requirements brief

**Requirement:** `docs/features/004-role-names.md`. A member with `ROLES_MANAGE` (Chairman by default) can rename every role, base roles included, on the Roles page, and restore a base role's default name. Uk default for TREASURER becomes "Казначей". Renames and restores are audited.

**Prior work:** Agent A — nothing off main (only the docs commits 227b326/65d053a on main; 003 already merged). Agent B — not implemented. Base renames blocked in `RoleCommands.update` (:76-77); name validation/uniqueness exist (`cleanName`, `ROLE_NAME_TAKEN`, index `role_tenant_name_uq` on lower(name)); seed stores English names; `useRoleName` (web/src/roleName.ts) picks catalog name by baseCode; RolesPage disables the name for base roles and Save for Chairman; audit diffs `name` via `RoleSnapshot`; e2e selects roles by English name and asserts the base-role name is disabled; mobile shows no roles; coverage floor 0.90, last measured ≈ 0.939. Verdict: **extends 002**.

**Scope:** sections 2–7 of the feature doc. Out: mobile (no role display exists), any other role behaviour.

**Acceptance criteria:** section 8 of the feature doc plus section 3 rows.

**Approach:** V4 migration (`name_customized`); contract change (`useDefaultName`, `nameCustomized`) + `make gen`; backend `RoleCommands`/mappers/`RoleRef` plumbing; audit display-name token; web `useRoleName`, RolesPage hint/restore/duplicate warning, AuditPage rendering, i18n; tests; coverage floor raise.

**Verification:** `./mvnw verify` (Testcontainers), `pnpm -r test`, `make check`, `make e2e`; UI via Playwright on `make up`.

**Risks:** audit display names (backend has no catalog); e2e role assertions; coverage floor; Chairman Save was disabled in the editor.

## Phase 1 — decisions

- AUTO: no blocking question (rule: questions=blocking; no prior work, tooling present, clean tree, requirement is clear).
- AUTO: audit display names — for a base role whose name is not customised, the audit `name` value (old or new) and the entry label are written as the token `base:<BASE_CODE>`; the web audit page renders such a token as the catalog name in the viewer's language; customised names are written as stored (rule: CLAUDE.md allows Ukrainian only in i18n catalogs, so the server cannot hold "Казначей"; this keeps the worked example "Казначей" → "Бухгалтер" true in Ukrainian and shows "Treasurer" → "Бухгалтер" in English).
- AUTO: "Restore default name" sets the stored name back to the seed name for that base code and `name_customized = false` (rule: uniqueness is checked against stored names, so the stored value must return to the seed value).
- AUTO: a rename to the same name after trimming changes nothing and writes no audit entry; a case-only change of a stored name counts as a rename (rule: section 3 row 3; existing behaviour treated case changes as renames).
- AUTO: the Chairman role's Save button becomes available for renaming; its permissions stay locked (rule: section 2, everything else about base roles unchanged).
- AUTO: mobile is out of scope — it displays no role names today (rule: section 6 "any mobile equivalent").
- AUTO: coverage floor raised to the measured line coverage rounded down to two decimals after the feature (rule: section 8).

## Phase 2 — branch and specs

- Spec folder `context/spec/004-role-names`, branch `004-role-names` from `main` @ 23451fb.
- Baseline `make check` on 23451fb: green (backend 239, ui-tokens 56, shared 5, web 45; line coverage 93.8 %).
- functional-spec.md written (Creation Mode, no questions, no markers).
- technical-considerations.md from spring-backend and react-frontend design input. Assumptions: client-side rendering of `base:<CODE>` audit token (also for member-role audit values); restore resets the stored seed name and may hit ROLE_NAME_TAKEN; `useDefaultName` on a custom role → 400 field `useDefaultName`/`INVALID`; no CHECK constraint on the flag; mobile unchanged; web submits the stored name when the catalog name is untouched.
- tasks.md: 4 slices, no staffing gaps (postgres-database not needed for a one-column migration; spring-backend owns it). No `not-user-reviewed` marker.

## Phase 3 — implement

- Slice 1 committed 54971bf (backend 71 targeted tests, web 49 unit; typecheck/lint/format/gen clean). Review r1 started.
