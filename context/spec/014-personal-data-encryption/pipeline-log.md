# Pipeline log — 014 personal data encryption

- Status: running
- Phase: 3 (implement, slice 2)
- Branch: 014-personal-data-encryption (planned) · Base: main @ 67e3916
- Requirement: document docs/features/014-personal-data-encryption.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: — · Checks: —
- Review run: on (pending)

## Project facts

- Base branch: `main` (origin/HEAD not a symbolic ref; `main` exists, equal to `origin/main` @ 67e3916, no local-only commits).
- Pipeline tooling present on `main` (skill, slice-review-loop, slice-reviewer agent, AWOS wrappers and core files).
- Working tree: clean.
- Build: `cd backend && ./mvnw -q compile`; clients `pnpm -r typecheck`.
- One test: `cd backend && ./mvnw test -Dtest='<Class>'`; module: `-Dtest='app.nadel.<module>.**'`; web `pnpm --filter web exec playwright test <spec>`.
- Full suite: `make check` (contract, format, lint, backend verify with Testcontainers + JaCoCo floor, client typecheck), then `make e2e` (needs JDK 25).
- Specialist agents: spring-backend, postgres-database, react-frontend, devops-infra, expo-mobile; testing agent: testing-expert; reviewer: slice-reviewer.
- UI: yes (web). Browser tool: claude-in-chrome (deferred tools available).
- Safe local run mode: `make up` (local docker-compose Postgres + Keycloak, backend :8080, web :5173), demo users from CLAUDE.md.
- Git rules: Conventional Commits; no AI attribution (no Co-Authored-By, no "Generated with"); never commit on main; agents push only the spec branch in Phase 5, no force; never merge/approve.
- Protected files (propose only): CLAUDE.md, .claude/, .mcp.json; also .github/ per pipeline rules.
- Publish pre-flight: `gh` logged in (github.com, Philatelist); origin = github.com/Philatelist/nadel-platform; CI workflow `.github/workflows/ci.yml`.
- Next spec number: 014.

## Settings

- autonomy minimal: questions=blocking, reviewerEscalation=auto, notGreen=ask, gitUnsafe=ask, notTestableLocally=auto
- publish=pr+checks, reviewRun=on, notify=true

## Log

- Phase 0 done. Phase 1 started: analysis agents A (git history) and B (code search) launched.
- Note: a stale worktree from an earlier 012 run exists (`.../60c46cb4-.../scratchpad/run-012`, detached 9c58e55). Not created by this run; left untouched.

## Requirements brief

**Requirement:** docs/features/014-personal-data-encryption.md. Encrypt members' contacts (phones, e-mail, postal address,
Telegram, Viber, notes) at rest with per-cooperative data keys wrapped by an environment master key; blind indexes for
exact e-mail/phone/last-4 search; encrypted audit values and import plans; plus three small items (water connection
guard on plot archive/common land, 010 e2e clean-up, two flaky e2e tests). Users: board (search, audit), owner/operator
(keys, deployment).

**Prior work:** Agent A — nothing on any branch, stash or worktree (only the requirement doc, already on main).
Agent B — no crypto code; `MemberDirectory` javadoc and the meters README already anticipate 014. Verdict: **new**
(extends 002 members/audit, 008 import, 010 meters, 013 reports).

**Scope:** as in the requirement §§2–7; out of scope §8.

**Findings that shape the design (not in the requirement):**
1. `R__demo_data.sql` inserts plaintext contacts on every dev start → names-only SQL plus Java encryption of demo contacts.
2. Cooperatives are created only by operator SQL (owner manual recipe inserts a member with e-mail) → keys are created
   lazily on first use; the guide recipe changes (contacts added via the UI, not SQL).
3. The app (`nadel_app`, RLS) cannot list cooperatives → start-up key-check and re-wrap run with owner credentials
   (Flyway callback / owner datasource), one transaction per cooperative.
4. No `prod` profile exists → add one (fail-fast on missing/malformed key).
5. ~14 test files insert plaintext contacts; `IntegrationTest.newMember` used by 51 → fixture churn.
6. Key must reach `make up`/`backend`/`e2e` and the CI backend + e2e jobs → generate per run; CI workflow edits are
   protected (`.github/`) → if a CI key is needed, the app must have a dev-profile fallback, or the change is proposed.
7. Flaky e2e: run 40571131162 (full suite on main, 2026-10-27) — `ownership.spec.ts:211` (flaky),
   `ownership.spec.ts:253` and `ownership-acceptance.spec.ts:258` (failed twice), all on "Plot 9" heading after search.
   The 010 clean-up gap: `meters.spec.ts` beforeAll sweep does not retire the meter, so leftover E2E plots stay active.

**Verification:** backend module tests `./mvnw test -Dtest='app.nadel.<module>.**'`; full `make check`; e2e specs for
members, audit, ownership, meters plus `@smoke`. UI criteria checkable with claude-in-chrome via `make up`.

**Risks:** large test-fixture churn; JaCoCo floor 0.974; Java migration under owner with the key from env; CI key
wiring vs protected `.github/`.

## Phase 1 answers

- No blocking question (no prior work, base has tooling, clean tree, requirement precise). Nothing asked.
- AUTO: (questions=blocking) every open question takes its recommended answer:
  - AUTO: encryption in members' repository/mapper layer (as the requirement says), ADR 0016.
  - AUTO: keys created lazily on first use per cooperative (no tenant-creation code exists); guide recipe updated.
  - AUTO: start-up key-check, re-wrap and demo-contact encryption run with the owner connection, one transaction per cooperative.
  - AUTO: add a `prod` Spring profile; dev/test fall back to a generated or configured key, never a committed one.
  - AUTO: the two flaky tests are those of run 40571131162 (ownership specs); fix the root cause.
  - AUTO: a CI change in `.github/` is not made by the pipeline; the app must run in CI without it (dev profile key
    fallback), and any wanted CI edit is proposed in the PR.
- Phase 2: branch `014-personal-data-encryption` cut from main @ 67e3916; baseline `make check` started in a worktree.
- Functional spec written (no open markers). Technical considerations written from the Explore report and the spring-backend, postgres-database, devops-infra and react-frontend design inputs.
  - AUTO: dev key from `infra/.env` (read or create) instead of an in-memory key; CI unchanged.
  - AUTO: demo members inserted as PAPER, contacts applied by a Flyway callback.
  - AUTO: plan_digest moves inside the sealed plan; decrypt failure is 500 PERSONAL_DATA_UNREADABLE.
  - AUTO: plot 9 e2e failures = search debounce writing the URL after navigation; fixed in the app plus waits in the specs.
- Tasks written: 8 slices. Staffing gap: guides (Slice 7 HTML/PDF guides) → general-purpose (no docs specialist); no Open Questions section kept.
- Removed `<!-- not-user-reviewed -->` from tasks.md (Phase 1 answers stand in for plan review).
- Baseline: `make check` on main 67e3916 green (2840 backend tests, 0 failures).
- Slice 1 committed 2ad5d2a — verify green (privacy/tenancy/modularity/ImportUploadLimit tests, pnpm typecheck; no infra/.env created); review: PASS round 1.
  - Note for slice 3: FieldRef names must match [a-z_.]+ → audit refs use snake_case field names.
- Slice 2 verify green: members/identity/imports/reports/privacy/modularity 1096 tests, 0 failures (1 skipped, pre-existing); fixture packages settings/ledger/tariffs/meters/plots/audit/acceptance 1780/0 (builder run); pnpm typecheck, lint, MembersPage vitest green.
- Slice 2 committed 9140a23 — review: PASS round 1.
- Slice 3 verify green: audit/members/modularity 318 tests 0 failures; pnpm typecheck, lint, AuditPage vitest 30/30. Builder found and fixed FieldChange deserialisation of pending outbox rows without 'sealed'.
- Slice 3 committed 25852a7 — review: PASS round 1.
- Slice 4 verify green: imports/members/modularity 585 tests 0 failures (1 skipped). Decision (builder): a copied/unopenable plan → 500 PERSONAL_DATA_UNREADABLE, run left untouched.
- Slice 4 committed a46cd13 — review: PASS round 1.
- Slice 5 verify green: meters/plots/ledger/modularity 1309 tests 0 failures; pnpm typecheck, lint, shared 130, PlotForm 10.
  - AUTO: explicit guard order ledger 100 → meter 200 → water 300 (keeps the previous effective order; ledger only refuses DELETE).
  - AUTO: uk wording «відключіть» (matches the meters UI) instead of «завершіть».
- Slice 5 committed 7130cc6 — review: PASS round 1.
- Slice 6 implemented: shared `useDebouncedSearchParam` (timer cleared on unmount, no write after the path changed) on Plots/Members/Meters/ReadingsRound pages; ownership specs wait for `?q=9`; `cleanupMeterPlot` in e2e support used by meters.spec finally and beforeAll sweep. Root cause confirmed from CI artefact (final page `/plots?q=9` after the click). Verify: typecheck, lint, format, vitest 120 files / 763 tests green.
  - AUTO (notTestableLocally=auto): the Playwright repeat run (`ownership ownership-acceptance meters --repeat-each=3 --retries=0`) and `@smoke` could not run — ports 8080/5173 are held by the owner's own `make up` stack (PIDs 79572 vite, 79613 java), which the pipeline does not stop. Deferred to Phase 4 / CI; the criterion "ownership checks pass without retries" stays [?] until then.
