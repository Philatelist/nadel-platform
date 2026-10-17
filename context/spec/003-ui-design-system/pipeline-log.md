# Pipeline log — 003 UI design system

- Status: running
- Phase: 2 — branch and specs
- Branch: 003-ui-design-system · Base: main @ e85f746
- Requirement: docs/features/003-ui-design-system.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: — · Checks: —
- Review run: on (pending)

## Project facts

- Base branch: `main` (origin/HEAD not set; `main` used). Local main == origin/main (e85f746).
- Pipeline tooling present on main (feature-pipeline skill, slice-review-loop, slice-reviewer, AWOS spec/tech/tasks/implement/verify wrappers and core).
- Working tree: tracked user edits to docs (glossary, money-rules, features 001-007 renumbering) and untracked `docs/features/004-role-names.md`. Branch is cut from the same commit, so they carry over; they are the user's work and are never staged by the pipeline.
- Build: `pnpm --filter web build`; backend `cd backend && ./mvnw -B verify` (not touched by this feature).
- Single test: currently none for TS (no unit runner exists); this feature adds vitest: `pnpm --filter <pkg> test -- <file>`. E2E single spec: `pnpm --filter web test:e2e <spec>` with the stack running.
- Full suite: `make check` (gen, format-check, lint, backend verify, typecheck); plus `make e2e` (Playwright, needs infra, JDK 25).
- Specialist agents: react-frontend (web + shared TS packages), expo-mobile, devops-infra (Makefile, CI), testing-expert (testing agent), slice-reviewer.
- UI: yes (web). Browser tool: claude-in-chrome available.
- Safe local run mode: `make up` (local docker-compose Postgres + Keycloak, backend dev profile, web :5173). No external services. Demo users per CLAUDE.md, password `nadel`.
- Git rules: Conventional Commits, no AI attribution, feature branch only, agents never merge/approve/push main; only the spec branch is pushed (publish pr+checks).
- CI checks (ci.yml): backend, clients (contract, format, typecheck, lint, build), security (gitleaks, pnpm audit high, OSV-Scanner any), SAST (Semgrep), e2e (Playwright).
- gh: logged in as Philatelist; origin github.com/Philatelist/nadel-platform.
- Next spec number: 003.

## Settings

- questions: blocking · reviewerEscalation: auto · notGreen: ask · gitUnsafe: ask · notTestableLocally: auto
- publish: pr+checks · reviewRun: on · notify: true

## Phase 0 — finished

## Phase 1 — requirements brief

**Requirement:** `docs/features/003-ui-design-system.md`. Give the web app the approved "calm registry" look: semantic design tokens (new green palette, IBM Plex fonts, spacing, radius) in `packages/ui-tokens`, a small typed component kit in `web/src/components/ui/`, a new app shell (side menu collapsing under 768 px), restyle of the 002 screens, and token/font adoption in mobile. Users: board members (chairman, treasurer) and cooperative members using the web app.

**Prior work found:**
- Agent A (git): nothing off main. Only 497fafe (#16) on main with the feature doc and 15 reference PNGs. No other spec folders.
- Agent B (code): tokens exist but are an old blue palette (`packages/ui-tokens/src/index.ts`), duplicated by hand in `web/src/index.css` `@theme`; Google Fonts in `web/index.html`; `formatMoney` already exists in `packages/shared/src/money.ts:14` (format matches the spec); Field/inputs/Banner/QueryBoundary are early versions to evolve; dark theme via `prefers-color-scheme`; no unit test runner anywhere; mobile `App.tsx` reads `palette.muted` (rename impact).
- Verdict: **new**, extends 002 (restyle only).

**Scope (in):** sections 2–6 and 8 of the feature doc. **Out:** section 7 (new screens, plot status in lists, Storybook, API/backend/DB).

**Acceptance criteria:** as in section 9 of the feature doc.

**Approach sketch:** react-frontend: tokens, `@theme`, fontsource, components/ui, Layout, screens, i18n, formatMoney tests. expo-mobile: tokens + expo-font. devops-infra: wire unit tests into Makefile and CI. testing-expert: e2e selector updates, 390 px spec, acceptance tests.

**Verification:** vitest unit tests (new), `pnpm -r typecheck`, `pnpm -r lint`, `make check`, `make e2e`; UI criteria in Chrome against `make up`, screenshots in `docs/screenshots/`.

**Risks:** e2e selectors tied to `<ul>` lists and `header nav`; supply-chain checks on new packages (fontsource, vitest, testing-library, jsdom, expo fonts); CI must gain a unit-test step.

**Open questions:** none blocking. Non-blocking ones were answered automatically (below).

## Phase 1 — decisions

- AUTO: no blocking question (rule: questions=blocking; no prior work, tooling present, tree carries over, requirement is clear). Nothing asked.
- AUTO: prototype-only elements in the screenshots are not built: the "Готово" readiness badges, the "Увійти як" role switcher, the feature-status dots and legend in the menu, and menu items for future sections (rule: section 1 of the feature doc, only existing screens are restyled).
- AUTO: one shell for everyone, as in section 4. A user whose permissions give no menu items (a plain member, per 002) gets no side menu and no Menu button, so My account reads like `member-account.png` without the future-section pills (rule: section 4 text wins over the prototype's member tab bar; 002 navigation criteria stay).
- AUTO: TS unit tests use vitest (+ jsdom and @testing-library/react in web), pinned exact versions; a `test` script per package, `pnpm -r test` added to `make test` and as a step in the CI `clients` job (rule: section 8 requires unit tests and the repository has no runner; the CI change is listed under "Changes that need attention" in the PR).
- AUTO: the web `@theme` stays hand-written CSS, guarded by a unit test that checks it matches `@nadel/ui-tokens` (rule: smallest change that keeps tokens the single source; no build step added).
- AUTO: the existing `formatMoney` is kept and covered by tests; the `Money` component uses it (rule: reuse; spec example output already matches).
- AUTO: mobile loads IBM Plex through `expo-font` with the `@expo-google-fonts/ibm-plex-*` packages (bundled font files, no runtime network request) (rule: section 6 names expo-font).
- AUTO: the header shows only the cooperative and user data the existing `/me` response already provides; no API change (rule: section 7 forbids API changes).

## Phase 2 — branch and specs

- Spec folder `context/spec/003-ui-design-system`, branch `003-ui-design-system` cut from `main` @ e85f746. The user's staged renames in `docs/features/` stay in the index; every pipeline commit uses `git commit -- <paths>` so they are never included.
- Baseline (`make check` on e85f746 in a detached worktree): first run errored (Docker daemon not running); Docker Desktop started, re-run green (backend 239 tests, 0 failures). Recorded in `review-log.md`.
- functional-spec.md written (Creation Mode, no questions, no NEEDS CLARIFICATION markers).
- technical-considerations.md written from react-frontend and expo-mobile design input. Assumptions: `warningInk` token; status on-color contrast 3:1; "My account" moves into the header; Roles keeps its per-role structure; hand-written CSS + sync test; mobile fonts at runtime without the expo-font config plugin.
- AUTO: CI `clients` job gains a `pnpm -r test` step (rule: section 8 needs unit tests in `make check`; adding a check is not weakening one). Listed for the PR.
- tasks.md written: 5 slices; no staffing gaps. The `<!-- not-user-reviewed -->` marker is not present (pipeline: Phase 1 stands in for plan review; the plan is listed in the final report).

## Phase 3 — implement

- Slice 1 task 4 (devops-infra): Makefile `test` runs `pnpm -r test`; CI `clients` job gets a `pnpm -r test` step. The agent also renamed the CI job; reverted by the orchestrator (job names may be required checks). Follow-up for the owner: CLAUDE.md `make test` line → "backend ./mvnw verify (Testcontainers, coverage floor) + client typecheck + client unit tests (vitest)".
- Slice 1 committed 0b35db5 (tests: ui-tokens 56, shared 5, web 3 passing; typecheck, lint, format, build, audit green). Review r1 started.
- Slice 1 review r1: MAJOR_ISSUES (M1 stale --color-bg in MapPage); fix committed 8e8bf1a with a guard test.
