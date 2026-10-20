# Pipeline log — 003 UI design system

- Status: finished
- Phase: 6 — review run
- Branch: 003-ui-design-system · Base: main @ e85f746
- Requirement: docs/features/003-ui-design-system.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: https://github.com/Philatelist/nadel-platform/pull/18 · Checks: green (run 39073218619)
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
- Slice 2 committed bfba724 (web 35 tests); review r1 PASS. Slice 1 review r2 PASS.
- Slice 3 committed 4657f49 (web 37 tests, make e2e 17 passed); review r1 PASS.
- Slice 4 committed 792b4f7 (make check green: backend 239, ui-tokens 56, shared 5, web 37; make e2e 20 passed x2); review r1 PASS. All slice reviews PASS — Slice 5 gate open.
- Slice 5 (testing-expert): 8 unit + 9 e2e acceptance tests; found GAP (locale switch buttons 24 px < 44 px); fixed by react-frontend in LocaleSwitch.tsx; make e2e 29 passed, web 45 tests.
- Slice 5 committed 05a3791; review r1 MAJOR_ISSUES (LocaleSwitch radius 6 px) → fix bb08049; review r2 PASS. All slice reviews PASS.

## Phase 4 — verify

- UI checked with the project's Playwright (headless Chromium) against the local stack (`make infra` + backend dev profile + web dev); the Chrome extension was not used because choosing between two connected browsers would have required a question. Screenshots in `docs/screenshots/003-ui-design-system-*.png` (taken before the radius fix bb08049; the only difference is 10 px vs 6 px corners on the UK/EN buttons). No external font requests observed.
- Acceptance criteria: 17 of 22 verified `[x]`; 5 `[?]`: mobile fonts on a device (2), visual match to the reference pictures (owner judgement), keyboard walk-through beyond the Members screen, PR description rule (done in Phase 5). Spec status: Implemented — pending manual check.
- Full suite `make check` on bb08049: green (backend 239/0 failures, ui-tokens 56, shared 5, web 45). Baseline had 0 failures → no regressions. `make e2e` on bb08049: 29 passed.
- Product docs: suggest `/awos:architecture` to record vitest unit tests for the TS packages and self-hosted IBM Plex fonts (not run).

## Phase 5 — publish

- AUTO: the working tree carries the user's own uncommitted edits under `docs/` (present before the run, never staged); the push contains only the branch's commits, so this is not treated as a git blocker (rule: Phase 5 safety check is about unpublished pipeline work).
- Pushed `003-ui-design-system`; PR https://github.com/Philatelist/nadel-platform/pull/18 — "feat(web): UI design system — tokens, IBM Plex fonts, component kit and app shell".
- Checks on 98b05cd: Backend, Clients, End-to-end, SAST, Security — all green (run 39073218619). No CI fixes, no re-runs.

## Final report

- Commits: f9e5866 specs · 0b35db5 S1 · 8e8bf1a S1 fix · bfba724 S2 · 4657f49 S3 · 792b4f7 S4 · 05a3791 S5 · bb08049 S5 fix · 98b05cd verification · (this log commit).
- Acceptance criteria: 17/22 verified; 5 `[?]` (visual match, mobile on device ×2, keyboard beyond Members, PR rule — the last is now satisfied by the PR description).
- Reviews: S1 2 rounds (M1 stale CSS var) PASS; S2 1 round PASS; S3 1 round PASS; S4 1 round PASS; S5 2 rounds (M1 control radius) PASS.
- Full suite vs baseline: baseline 0 failures; branch 0 failures (backend 239, ui-tokens 56, shared 5, web 45; e2e 29).
- Follow-ups for the owner: CLAUDE.md rule and `make test` wording (in the PR), mobile device check, optional `/awos:architecture`.
