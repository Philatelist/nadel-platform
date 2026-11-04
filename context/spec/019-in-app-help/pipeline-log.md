# Pipeline log — 019-in-app-help

- Status: running
- Phase: 2 — specs
- Branch: 019-in-app-help · Base: main @ aacd2a4
- Requirement: document `docs/features/019-in-app-help.md`
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: — · Checks: —
- Review run: on (pending)

## Project facts

- Build: `make check` (contract, format, lint, tests); backend `cd backend && ./mvnw verify`; clients `pnpm -r typecheck && pnpm -r lint && pnpm -r test`.
- One test: backend `./mvnw test -Dtest=<Class>`; web `pnpm --filter web test -- <file>`; e2e `pnpm --filter web exec playwright test <spec>`.
- Full suite: `make check` then `make e2e` (smoke set `--grep @smoke`).
- Agents: spring-backend, react-frontend, postgres-database, devops-infra, expo-mobile, testing-expert (testing), slice-reviewer, general-purpose.
- UI: yes (web). Browser tool: claude-in-chrome available.
- Safe local run mode: `make up` (local docker-compose with Mailpit and a fake Telegram API; no real external services).
- Git rules: Conventional Commits, feature branch only, no AI attribution, CLAUDE.md / .claude/ changes are owner-only (proposed, not committed).
- Publish pre-flight: `gh` logged in to github.com (origin Philatelist/nadel-platform); CI jobs: backend, demo-days, clients, security, sast, e2e.

## Phase 1

- Prior work: none. `origin/docs/feature-019-help` holds only the requirements doc, already merged as #67 (aacd2a4). No `docs/help/`, no `/help` route. Verdict: **new**.
- Code analysis agents: not run (the user rejected the Explore agent launch); the git history check was done inline.
- No blocking questions → nothing asked.
- **User instruction (argument):** skip all tests until the whole feature is implemented; implement every task first, then one verification at the end. Applied as:
  - no baseline suite in Phase 2 (the baseline runs in Phase 4, next to the final suite);
  - slice `Verify:` steps are not run per slice; builders do not run tests per task; slice reviewers review statically (no test runs);
  - Phase 4 runs verify, `make check` and the e2e set once.

## Phases
- Phase 2 start: branch `019-in-app-help` cut from main @ aacd2a4.
- Baseline suite: deferred to Phase 4 (user instruction: no tests until the end).
- Functional spec, technical considerations and tasks written. The codebase exploration for `/awos:tech` was done inline
  (no Explore/specialist agents, after the user rejected the Explore launch in Phase 1).
- Tasks: 11 slices, no per-slice Verify tasks (user instruction); the `<!-- not-user-reviewed -->` marker was not kept
  (pipeline mode). Staffing gap: content writing (Slices 7–10) has no specialist → `general-purpose` (AUTO: accepted).
- AUTO: assumptions of the functional spec §4 and the technical considerations (runnerImport, one copy of help shots in
  `web/public/help/img`, uk-only pre-rendered SEO shells, `NADEL_NOINDEX`, `account` page visible to every signed-in user).
- AUTO: slice reviews run statically (the reviewer reads the diff and may typecheck, but runs no tests) — user instruction.
- Phase 3 start.
- Slice 1 — `a8e2bcc` — tests not run (deferred); typecheck, lint, prettier, `vite build` green per builder; review round 1 running (static).
- AUTO: content slices run in parallel with the code slices (content writers touch only `docs/help/**` and `migration-map.md`), to shorten the run; each slice is still committed separately by path.
- Slice 1 review round 1: PASS (minor notes in review-log).
- Slice 2 — committed below — tests not run (deferred); typecheck, lint, format green per builder.
