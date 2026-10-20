# Pipeline log — 004 Role names

- Status: finished
- Phase: 6 — review run
- Branch: 004-role-names · Base: main @ 23451fb
- Requirement: docs/features/004-role-names.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: https://github.com/Philatelist/nadel-platform/pull/21 · Checks: green (run 39079616098)
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
- Slice 1 review r1 PASS. Slice 2 committed 138490e (backend RolesTests/RoleLogicTests/AuditReadTests 72, web 59 unit; typecheck/lint/format clean). Review r1 started.
- Slice 2 review r1 MAJOR_ISSUES (M1 untested submit-name guard) → fix f40a963 → r2 PASS.
- Slice 3 committed 02a9b37 (make check green: backend 257, web 65, shared 5, ui-tokens 56; coverage 0.9392 → floor 0.90→0.93; make e2e 30 passed). Review r1 started.
- Slice 3 review r1 PASS. All slice reviews PASS — Slice 4 gate open.
- Slice 4 (testing-expert): RoleNamesAcceptanceTests (2) + e2e role-names.spec.ts (3, Ukrainian UI); no GAP; make check green (backend 259, floor 0.93 met), make e2e 33 passed. Note: a 60-char name with a trailing space is refused by the contract's maxLength on the raw input (pre-existing 002 behaviour; web input caps at 60).
- Slice 4 committed aa853d6; review r1 PASS. All slice reviews PASS.

## Phase 4 — verify

- UI checked in Ukrainian with the project's Playwright against the local stack; screenshots `docs/screenshots/004-role-names-*.png`; the audit page shows no `base:` text; the Treasurer name was restored afterwards.
- Acceptance criteria: 15 of 15 verified. Status: Completed.
- Full suite on aa853d6 (final code): `make check` green (backend 259, ui-tokens 56, shared 5, web 65; JaCoCo floor 0.93 met), `make e2e` 33 passed. Baseline 0 failures → no regressions.
- Product docs: no `/awos:architecture` or `/awos:product` change needed (extends 002 within the existing architecture).

## Phase 5 — publish

- Pushed `004-role-names`; PR https://github.com/Philatelist/nadel-platform/pull/21 — "feat(members): rename any role, including base roles".
- Checks on 7745f32: Backend, Clients, End-to-end, SAST, Security — all green (run 39079616098). No CI fixes, no re-runs.

## Final report

- Commits: a35ddff specs · 54971bf S1 · 138490e S2 · f40a963 S2 fix · 02a9b37 S3 · aa853d6 S4 · 7745f32 verification · (this log commit).
- Acceptance criteria: 15/15 verified.
- Reviews: S1 1 round PASS; S2 2 rounds (M1 untested submit-name guard) PASS; S3 1 round PASS; S4 1 round PASS.
- Full suite vs baseline: baseline 0 failures; branch 0 failures (backend 259, web 65, shared 5, ui-tokens 56; e2e 33). Coverage 0.939, floor 0.90 → 0.93.
- Decided automatically: see Phase 1 decisions (audit token, restore semantics, trim/case rules, Chairman Save, mobile out of scope, coverage floor).
- Follow-ups: pre-existing 60-char + trailing space edge; raw permission codes in the audit log; minor review notes in review-log.md.
