# Pipeline log — 019-in-app-help

- Status: finished
- Phase: done
- Branch: 019-in-app-help · Base: main @ aacd2a4
- Requirement: document `docs/features/019-in-app-help.md`
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: https://github.com/Philatelist/nadel-platform/pull/68 · Checks: green (run 41898629237)
- Review run: not started: the ports it needs (5173, 8080, 8180, 5432) are held by a local stack the pipeline did not start; that stack already serves this branch's web code from the working tree

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
- Slice 2 — `2b6b5e1` — review round 1: PASS (minor notes in review-log).
- Slice 3 — committed below — tests not run (deferred); typecheck, lint, format, build green per builder.
- Slice 3 — `a5dd1c3` — review round 1: MAJOR_ISSUES (M1 typo test); fix sent to the builder.
- Slice 4 infra task done (Caddy `NADEL_NOINDEX` matcher, `try_files {path}/index.html`, compose, README; `caddy validate` → Valid configuration). `infra/stand/.env.example` was NOT updated: the permission settings deny the agent access to it — left for the owner (line to add in the final report).
- Slice 3 review round 2: PASS.
- Slice 4 — committed below — tests not run (deferred); typecheck, lint, build green per builder; built shell `dist/help/payments-enter/index.html` carries title, description, canonical, og:*, robots index.
- NOTE: `infra/stand/` is not tracked — it is excluded locally in `.git/info/exclude`. The stand changes (Caddyfile `NADEL_NOINDEX` matcher, `try_files`, compose, README) exist only in the owner's working copy and are not part of the branch. `web/.env.example` and `infra/stand/.env.example` are denied to agents by the permission settings; `VITE_SITE_URL` is documented in `web/README.md` instead. Both left for the owner.
- Slice 4 — `dc7c798` — review round 1: PASS (minor notes in review-log).
- Slice 5 — committed below — tests not run (deferred); typecheck, lint green; `vite build` now fails on help issues by default (60 issues while content and screenshots are incomplete; `HELP_CHECK=warn` downgrades). Route/menu coverage is checked by the vitest real-content test only (the Vite plugin cannot load `App.tsx` in Node).
- Slice 5 — `9aed130` — review round 1: PASS. Slices 8 and 9 content writers started in parallel (own migration-map-s8/s9 files).
- Slice 6 — committed below — not run against an app (deferred); `node --check` ok; `build-pdf.mjs` built all PDFs from partial content (PDFs restored, not committed). The old SHOTS array is removed (no owner-manual shots exist); content writers use `git show main:docs/guide/scripts/capture-screens.mjs` as the shot source.
- Slice 6 — `7d356a9` — review round 1: PASS.
- Slice 7 — committed below — 11 pages uk+en, no BAD_FRONT_MATTER/UNKNOWN_UI_KEY/MISSING_SECTION per writer (HELP_CHECK=warn build).
- Slice 7 — `a753b52` — review round 1: PASS.
- Slice 10 content part — committed below (19 pages uk+en: how-* topics, account, member topics; no check issues except missing shot files per writer). The HTML board guide removal and doc updates of Slice 10 follow after Slices 8–9 (writers still read the old HTML). AUTO: Slice 10 split into two commits for that reason.
- Slice 9 — committed below — 17 pages uk+en, only MISSING_SHOT per writer. Writer lists old sections 14/15/17 parts it did not place (invoices, member Telegram, reconciliation act) — to be verified in the Slice 10 completion step.
- Slice 8 content finished by the writer (17 pages uk+en, only MISSING_SHOT); commit held at the review gate (Slices 10 and 9 reviews outstanding).
- Slice 10 content review round 1: MAJOR_ISSUES (M1); fix sent to the writer.
- Slice 10 content review round 2: PASS.
- Slice 8 — committed below — 17 pages uk+en, only MISSING_SHOT per writer.
- Slice 9 — `2de1f4b` — review round 1: PASS.
- Slice 8 — `90d32b6` — review round 1: MAJOR_ISSUES (M1); fix sent to the writer, together with the save-click shot safety check.
- NOTE: commit `f031138` (Slice 8 review fixes) also swept in deletions that the Slice 10 close-out agent had already staged with `git rm` (old `board-guide.html` uk/en, 150 files under `docs/guide/img/`, the split migration maps). Not rewritten (no reset); those deletions are reviewed with the Slice 10 close-out.
- Slice 8 review round 2: PASS. Slice 10 close-out done by the writer (gaps filled in 6 pages, one migration map, HTML guide and docs/guide/img removed, owner manual/README/architecture updated); a backend test read the deleted HTML guide — spring-backend repoints it to the help pages (no test run, deferred).
- Slice 10 close-out — committed below (with the backend test repointed to the help pages; not run, deferred).
- Slice 10 close-out review round 1: PASS. All slice reviews PASS → Slice 11 (Feature Testing & Regression) starts; baseline suite running on main in a worktree.
- Verify: first test run — help unit tests 8 failures + Layout.test 18 failures, all test-setup bugs (jsdom locale en-US, nested router, missing `useOptionalMe` mock); fixed by react-frontend, committed below. helpContent fails only on MISSING_SHOT (screenshots pending). Shared tests 130/130.
- Verify fix: help virtual module failed under `vite dev` (directory passed to addWatchFile) — fixed, committed below.
- Baseline (main @ aacd2a4): backend verify green, clients tests green — 0 failures.
- Slice 11 task 1: `web/e2e/help.spec.ts` (15 specs, one `@smoke`) written by testing-expert; 14 pass against the local dev stack.
- Verify finding: FS 2.6 "chairman sees every page" unmet (demo chairman owns no plot; `member` pages hidden). AUTO: `member` is satisfied by owning a plot or holding `MEMBERS_VIEW`; fix sent to react-frontend; spec assumption amended.
- Verify finding: public help language follows the stored choice, else the browser language (app-wide rule). AUTO: spec assumption amended to match the existing app behaviour instead of forcing Ukrainian.
- Local env: the running dev DB (not started by the pipeline) has the demo role «Електрик» with extra permissions (REPORTS_VIEW, AUDIT_VIEW) left by earlier runs; the electrician e2e fails there on its precondition. Not reset (not the pipeline's environment).
- Slice 11 task 1 committed below (`web/e2e/help.spec.ts`, chairman now expects every page).
- Verify: screenshots captured from the running demo app — 70 per language in `web/public/help/img/`; capture fix (`/api/v1/me` in the demo guard) and `announcement-card` shot reduced to the list screen (the demo has no announcements and creating one would send it). `helpContent.test.ts` passes.
- Verify: `make guide` built board-guide uk (~141 pages, 70 images) and en (~142 pages), owner manual uk/en and leaflet; no warnings. Layout not visually inspected (no PDF renderer available).
- Phase 4 verify: 40 acceptance criteria `[x]` with evidence, 5 `[?]` (electrician e2e on a dirty local DB; stand flag not in the repository; demo guard against a non-demo DB; PDF layout and title-page version not visually inspected). Status `Implemented — pending manual check`.
- Full suite: `make check` green (backend 4184 tests, coverage met; clients 166+8+2+1 files, 1044+130+56+9 tests); baseline had 0 failures → no regressions.
- e2e: `help.spec.ts` 14/15 passed against the local dev stack (electrician: local DB precondition). Smoke set: 13/22 passed; the 9 failures are HTTP 500 from the locally running backend on writes such as `POST /plots` (that backend was not started by the pipeline, runs `main` backend code — this branch changes no backend code); not attributable to the feature; CI's smoke job on a clean stack is the gate.
- Product docs: suggested follow-ups only (no `/awos:architecture` run; `context/product/architecture.md` already mentions the help).
- Phase 5: pushed `019-in-app-help`, opened PR #68 (`feat(help): in-app help per screen, per role and public`). CI run 41898629237: backend, clients, demo data by day, e2e (smoke), Semgrep, security — all green on the first round; no CI fixes needed.
- Phase 6: review run not started — ports in use by a stack the pipeline did not start (not stopped).

## Owner review of PR #68 (NOTES.local.md, items 1, 2 and Finish)

- Item 1 (member's menu and way home) and item 2 (non-modal «?» panel) implemented: `0dde5a4` (code, help content, owner manual, spec change logs of 006/007/019), fix review PASS; e2e updated `2b04da1` (help.spec 22/22 locally); screenshots and PDFs retaken `0c9caee`.
- A Prettier run reformatted ~120 help pages although `*.md` is in `.prettierignore`; the formatting-only changes were reverted before commit.
- Full suite once: `make check` green (backend 4184, coverage met; clients 1059+130+56+9).
- Still red in the full e2e set locally, outside the smoke set and outside this change: `acceptance.spec.ts:233,349` (expects 25 permissions, there are 31), `acceptance.spec.ts:151` (leftover local member without a role), `acceptance.spec.ts:507` (not investigated), `language-purity.spec.ts:353` (Latin "PDF" in «Завантажити PDF» on the account).

## Owner review of PR #68, second round (2026-11-05)

- «Мій кабінет» is in the menu only for a user who owns a plot (and has no plots list); the home screen is the first menu item (`menuItems` in `nav.ts`, `landingPath` derives from it; empty menu → `/account`); `/plots` without the right redirects home. The electrician without a plot: «Покази», «Лічильники», home «Покази».
- Stale e2e specs, each run on a fresh database (`docker compose … down -v`, `make up`) on `main` @ aacd2a4 and on this branch:
  - `acceptance.spec.ts` roles screen ×2 (25 permissions, there are 31): red on main and branch → list extended to 31.
  - `acceptance.spec.ts` members list (paging): green on main and branch on a fresh database (the earlier red was a leftover local member).
  - `acceptance.spec.ts` member creation to archive: red on main and branch (Telegram needs no username since feature 017) → the Telegram case removed.
  - `language-purity.spec.ts`: red on main and branch ("PDF", "SMTP", "STARTTLS", "SSL", "@BotFather", "/newbot", the bot name, the saved token tail in uk; the certificate number «Д-2026-1» in en) → allowlist PDF/SMTP/STARTTLS/SSL, patterns for Telegram handles/commands and secret tails, and the certificate number marked `data-code` on the register.
- Locally on the branch: those specs green, `help.spec.ts` + `meters.spec.ts` + electrician acceptance 28/28, smoke 22/22; clients typecheck, lint, 1061 web unit tests green.
