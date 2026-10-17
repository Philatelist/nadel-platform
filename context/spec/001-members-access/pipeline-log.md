# Pipeline log — 002 Members and access control

- Status: running
- Phase: 3 — implement, Slice 3
- Branch: 001-members-access · Base: main @ 39380d2
- Requirement: document `docs/features/002-members-access.md`
- Autonomy: minimal (default; no argument, no `.claude/feature-pipeline.yml`)

## Project facts

- Base branch: `main` (origin/HEAD not symbolic; `main` exists, equal to `origin/main` @ 39380d2, 0 ahead / 0 behind).
- Pipeline tooling on base: all present (feature-pipeline skill + review loop, slice-reviewer agent, awos wrappers and core for spec/tech/tasks/implement/verify).
- Working tree: `docs/features/002-members-access.md` staged (A); `docs/features/003..005` untracked. No conflict switching to a new branch cut from `main` (same commit).
- Build: `cd backend && ./mvnw -B -DskipTests compile` (backend), `pnpm -r typecheck` (clients).
- One test class: `cd backend && ./mvnw -B test -Dtest=<Class>` (integration tests: `-Dit.test=<Class> verify` if failsafe is used — to confirm in tech phase).
- Full suite: `make test` (`./mvnw -B verify` incl. Testcontainers, ModularityTests, JaCoCo floor `coverage.minimum`=0.40; then `pnpm -r typecheck`). CI-equivalent: `make check`.
- Docker: **not running at pre-flight** — Testcontainers tests cannot run until Docker is started.
- Specialist agents: spring-backend, postgres-database, react-frontend, devops-infra, expo-mobile (not needed), testing-expert (testing agent), slice-reviewer.
- UI: yes (`web/`, React + Vite). Browser tool: Claude in Chrome available.
- Safe local run mode: `make up` (local docker-compose Postgres + Keycloak, backend :8080, web :5173); no external services in phase 1.
- Git/commit rules: Conventional Commits per CLAUDE.md; pipeline commit format `{N}: ...`; feature branch only; never push/merge; **no AI attribution** (CLAUDE.md + pipeline rule override the harness reminder).
- Next spec number: 001 (`context/spec/` is empty).

## Effective autonomy settings

- questions: blocking
- stops.reviewerEscalation: auto
- stops.notGreen: ask
- stops.gitUnsafe: ask
- stops.notTestableLocally: auto

## Events

- Phase 0 done.
- Phase 1 start: analysis agents A (git history) and B (code/spec search) launched in parallel.
- Agent A (git history): no prior work on any branch, stash, worktree or dangling commit. Only related material: ADRs 0003/0007/0008/0009 and phase 0 identity/tenancy code on main. Verdict: new.
- Agent B (code/spec): not implemented; builds on phase 0 identity/tenancy. Verified: ELECTRICIAN listed as base role in ADR 0009:10, glossary:27, CLAUDE.md, product docs, realm, Roles.java — but missing from feature §2.2. Glossary maps Member to code `Person` (glossary:8); ADR 0009 table `person_role`. `spring-modulith-starter-jpa` absent (pom:65-68).

## Requirements brief

**Requirement:** `docs/features/002-members-access.md` — give a cooperative its people (member records) and DB-stored,
permission-based roles (ADR 0009); link Keycloak sign-in to a member by email; audit every admin change through a new
`audit` module over the Modulith outbox; web screens for members, roles, audit and My account. Users: chairman,
treasurer, custom-role admins (secretary), members.

**Prior work:** none on other branches (Agent A). Code: phase 0 only — JWT resource server (`identity/internal/SecurityConfiguration`),
`/me` from claims, `TenantContext`/`TenantAwareTransactionManager`, RLS pattern in `V1__foundation.sql`, repeatable
`R__demo_data.sql`, `IntegrationTest` base with PostGIS Testcontainer. Verdict: **new** (extends feature 001 foundation).

**Scope in:** everything in sections 2–6 of the feature doc. **Out:** section 7 (plots/ownership/import, self-service
edits, Keycloak account creation/invitations, multi-cooperative people, notification delivery), mobile.

**Draft acceptance criteria:** see feature doc §2–§6; the functional spec turns each into a testable criterion.

**Approach sketch:**
- Backend: new modules `members` (member, role, role_permission, member_role, Permission enum, current-member resolution)
  and `audit` (append-only audit_entry, `@ApplicationModuleListener`); `identity` resolves `PERM_*` authorities from
  `members` public API inside `TenantContext.runAs` + a transaction; Problem Details with `code` + field errors via
  `@RestControllerAdvice` and custom entry point / access-denied handler.
- DB: Flyway V2+ (members, roles, audit, `event_publication`), RLS + `tenant_isolation`, indexes on `(tenant_id, lower(email))`,
  `(tenant_id, keycloak_subject)`; REVOKE UPDATE/DELETE on audit from `nadel_app`; SQL function `seed_base_roles(tenant)`;
  demo data ~35 members + Secretary.
- Contract: openapi `/me`, `/members*`, `/permissions`, `/roles*`, `/audit`; Problem gains `code`, `errors[]`; paging schema.
- Web: Members list, Member card, Roles, Audit, My account, permission-driven nav, map removed from nav, i18n uk/en.
- Infra: realm users stop carrying cooperative realm roles; Playwright + CI job against compose stack (devops-infra).
- Dependency: `spring-modulith-starter-jpa` (anticipated by ADR 0007), `@playwright/test` (pinned), possibly `spring-boot-webmvc-test` for MockMvc.

**Verification:** per-slice `./mvnw -B test -Dtest=<Class>` (Testcontainers ⇒ Docker), `pnpm -r typecheck`/`lint`,
full `make check`; UI criteria via Claude in Chrome against `make up` (local compose only) + the Playwright E2E.

**Risks:** Docker not running; async listeners lose tenant context (events must carry tenantId + actor, listener uses
`TenantContext.runAs`); permission lookup before security context exists; V1 default privileges grant UPDATE/DELETE on
new tables; module cycle identity↔members; Playwright CI job is new infrastructure; `Me.roles` enum is a breaking change.

**Open questions (recommended answers):**
1. Docker not running — start it before Phase 2 (recommended) / proceed without (integration tests `[?]`) / stop. **Blocking.**
2. ELECTRICIAN base role — keep as a seeded base role with no permissions yet (meter permissions come later), matching
   ADR 0009, CLAUDE.md, glossary, realm and test users; treat §2.2 as an omission. (non-blocking → AUTO)
3. Naming Member vs Person — use `Member` in code/API/tables (`member`, `member_role`), update glossary and ADR 0009 text. (AUTO)
4. CLAUDE.md lines naming realm roles — propose the edits in the final report, do not commit them (CLAUDE.md rule). (AUTO)
5. Members list without `MEMBERS_VIEW` → 403 `PERMISSION_DENIED`; another member's card → 404; own card readable via
   `GET /members/{id}` without `notes`. (AUTO)
6. Base-role seeding: SQL function used by migration (existing tenants) and demo data; Java tenant-creation code does not
   exist yet, so new-tenant seeding is the function to call from it later (documented). (AUTO)
7. Phone normalization by hand: accept E.164 `+[1-9]\d{7,14}`, `0XXXXXXXXX` and `380XXXXXXXXX` → `+380XXXXXXXXX`; no libphonenumber. (AUTO)
8. Email unique case-insensitive among active members (partial unique index). (AUTO)
9. Editing base role permissions (TREASURER, ELECTRICIAN, MEMBER) needs `ROLES_MANAGE`; CHAIRMAN's set is fixed to all. (AUTO)
10. Paging `page`/`size` (default 20, max 100). (AUTO)
11. E2E CI job: compose infra + backend `./mvnw spring-boot:run` (dev profile) + Vite dev server, Playwright drives the
    Keycloak login form; owned by devops-infra. (AUTO)
12. Index route → My account once map leaves navigation. (AUTO)
13. Commit the staged `docs/features/002-members-access.md` with the Phase 2 commit (it is this spec's source); leave 003–005 untracked. (AUTO)

## User answers (Phase 1 batch)

- Docker: "I'll start Docker" — verified running (server 24.0.5) before Phase 2.
- Scope: "Drop ELECTRICIAN" — base roles are CHAIRMAN, TREASURER, MEMBER only (as §2.2). ADR 0009, glossary, product definition and architecture docs are updated to match; `Roles.ELECTRICIAN` and the realm role are removed; CLAUDE.md edits are proposed in the final report only. All other brief decisions (2–13, with 2 replaced by this answer) apply as AUTO.
- AUTO: electrician@demo.local maps to a demo member holding a demo custom role "Electrician" with no permissions yet (meter permissions come in a later phase) plus MEMBER. Rule: the user's option allowed "custom role or MEMBER"; a custom role keeps the persona and shows custom roles in the demo. 
- AUTO: brief decisions 3–13 taken at their recommended answers (rule: `questions: blocking`; user accepted the brief's scope apart from ELECTRICIAN).

- Phase 1 done.
- Phase 2 start: spec folder `context/spec/001-members-access`, branch `001-members-access` cut from main @ 39380d2. Baseline `./mvnw -B verify` running in detached worktree.
- Functional spec written (`functional-spec.md`), 0 NEEDS CLARIFICATION markers; Assumptions & Decisions section lists 12 pipeline assumptions.
- Phase 2 step 4: /awos:tech started.
- Baseline: could not run (no JDK 25 on the machine; default java 17). Homebrew offers openjdk@25 25.0.4.1 and cask temurin@25. Recorded in review-log.md.
- Tech step 2: Agent B findings reused as the codebase exploration; specialists spring-backend, postgres-database, react-frontend, devops-infra plus one Explore (Modulith/Boot/CI plumbing) ran in parallel.
- technical-considerations.md written; 13 assumptions listed in its section 5 (pipeline mode).
- Phase 2 step 5: /awos:tasks started.
- tasks.md written: 7 slices (1 linking + My account, 2 members read, 3 member writes + audit outbox, 4 audit screen, 5 roles, 6 E2E/CI/docs/coverage, 7 Feature Testing & Regression → testing-expert).
- AUTO: docs task in Slice 6 assigned to general-purpose (no documentation specialist); no `## Open Questions` section kept (pipeline rule: accept general-purpose assignments). Noted for the final report.
- `<!-- not-user-reviewed -->` marker removed from tasks.md (pipeline rule: Phase 1 answers stand in for plan review).
- User message mid-run: "I started Docker, go ahead" (Docker already verified).
- Phase 2 commit: caf2a12 `001: functional spec, technical considerations and tasks` (includes docs/features/002-members-access.md and review-log.md).
- STOP (stop point 4, environment): backend requires `--release 25`; installed JDKs are 8, 17, 19, 20, 21, 23 (Homebrew openjdk 23.0.2). The `notTestableLocally: auto` fallback (compile/build check) is impossible too, and installing a JDK changes the user's machine, so the user is asked. Tried: `java -version`, `/usr/libexec/java_home -V`, Homebrew/SDKMAN/JetBrains locations. Homebrew offers `openjdk@25` 25.0.4.1 and cask `temurin@25`.
- User answer: "Install Temurin 25". The cask needs sudo (no terminal for the password), so installed the Homebrew formula `openjdk@25` 25.0.4.1 instead (no sudo, same JDK version, not linked into the shell profile). All backend commands in this run use `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`.
- Resumed: status running. Baseline re-run in a fresh detached worktree with JDK 25.
- Baseline (JDK 25): 0 failing, 7 tests, BUILD SUCCESS. review-log.md updated; baseline worktree removed.
- Slice 1: contract, infra and web tasks ticked (web: one fix round for a hard-coded aria-label; client checks re-run by the orchestrator, EXIT 0). DB task waits for its Java tests in the backend task.
- Slice 1 committed: f76322d — Verify: 60 backend tests green (orchestrator run), client typecheck/lint/format/build green, no generated-client drift — review round 1 running (worktree review-001-s1-r1).
- Phase 3: Slice 2 started.
- Slice 1 review round 1: MAJOR_ISSUES (M1 401-vs-500 on lookup failure). Fix deferred until Slice 2 is committed (Slice 2 backend agent active in backend/; avoid concurrent Maven builds and mixed changes), then sent to spring-backend as `001: Slice 1 — review fixes (round 1)`.
- Slice 2 Verify (orchestrator): 41 tests green, no generated-client drift, client checks green.
- Slice 2 committed: 7ad1272 — review round 1 running (worktree review-001-s2-r1). Slice 1 M1 fix sent to spring-backend (test-first). Slice 3 held until the M1 fix is committed (its contract change would break the backend build the fix is testing in the same tree).
- Slice 2 review round 1: PASS (6 minor notes in review-log.md).
- Slice 1 review fix committed: 5ae986c (bearer filter failure handler no longer rethrows; MemberLookupFailureTests RED→GREEN; verify 87 tests green). Round 2 review running (worktree review-001-s1-r2).
- Phase 3: Slice 3 started (gate: Slice 3 commit waits for Slice 1 PASS).
- Slice 1 review round 2: PASS (M1 resolved). All slice reviews so far PASS; no review outstanding.
- Slice 3: contract (1 fix round: wrong permission name MEMBERS_MANAGE, Viber rule) and web (1 fix round: orval `useQuery: true` generated query hooks for POST/PUT; removed, mutations regenerated) ticked. DB task waits for its Java tests in the backend task.
- Slice 3 Verify (orchestrator): 81 tests green, no generated-client drift, client checks green.
