# Pipeline log — 020 go-live polish

- Status: running
- Phase: 3 (implement, slice 1)
- Branch: 020-go-live-polish (planned) · Base: main @ b324146
- Requirement: docs/features/020-go-live-polish.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: — · Checks: —
- Review run: on (pending)

## User instruction for this run

"skip all tests till the end of the feature implementation. Firstly we implement all tasks, and only one verify at the end."
- AUTO: no background baseline suite (it is a test run); baseline = green CI on main @ b324146.
- AUTO: slice `Verify:` tasks are not executed during Phase 3; builders write tests but do not run them (compile/typecheck allowed). Slice reviewers review statically and do not run tests.
- AUTO: one verification pass at the end (Phase 4: make check, feature e2e + smoke).

## Project facts

- Build: `cd backend && ./mvnw -q compile`; clients `pnpm -r typecheck`
- One test: `cd backend && ./mvnw test -Dtest='...'`; clients `pnpm -r test`; e2e `pnpm --filter web exec playwright test <spec>`
- Full suite: `make check`, then `make e2e` (feature specs + `--grep @smoke`)
- Specialists: spring-backend, postgres-database, react-frontend, devops-infra, expo-mobile, testing-expert (testing agent), slice-reviewer
- UI: yes (web); browser tool: claude-in-chrome available
- Safe local run mode: `make up` (local docker-compose infra: Postgres, Keycloak dev realm, Mailpit, fake Telegram API)
- Git rules: Conventional Commits in CLAUDE.md, pipeline commits `{N}: Slice {S} — …`; no AI attribution; feature branch only; agents push only spec branch in Phase 5
- gh: logged in (Philatelist), origin github.com/Philatelist/nadel-platform; CI: .github/workflows/ci.yml
- Pre-flight issues: none (tree clean, main == origin/main, tooling present)

## Phase 1 — requirements brief

- Requirement: docs/features/020-go-live-polish.md. Goal: let «Надія» go live — board invites members into Keycloak from the member card, tariffs can be deleted/archived, clearer tariff/payment screens, review items and e2e hygiene. Users: chairman (invite), treasurer (tariffs, payments), members (sign in).
- Prior work: Agent A — nothing on other branches. Agent B — nothing implemented; 4.2 (readings e2e clean-up) already fixed by 014 (`web/e2e/meters.spec.ts` finally → cleanupMeterPlot) → close with evidence. Verdict: new, extends 004 (members/roles), 010 (tariffs), 015 (payments), 017 (mailbox), 018 (expenses), 019 (help).
- Findings that shape the design:
  - ADR number: 0019 exists → invitations ADR is 0020.
  - Keycloak `execute-actions-email` sends through the realm SMTP and never returns the link; delivery through the cooperative's mailbox needs a design choice (asked).
  - First chairman's cooperative has no mailbox yet (asked).
  - `notifications` depends on `members` → invitation letters via domain event + notifications listener; the link never enters the outbox/audit.
  - No last-sign-in source → record in backend on token resolution.
  - `acceptance.spec.ts:702-729` archives the demo electrician → conflicts with "archive disables the Keycloak user"; redesign with a throwaway member.
  - Tariff "used" = any charge/charge part incl. reversed (FKs RESTRICT) → new port.
  - Next Flyway V35; permissions 31 → 32 in tests/e2e/manual.
- Proposed scope: sections 1–5 of the requirement; out of scope as section 7.
- Verification: once at the end — make check, e2e feature specs + @smoke, Testcontainers Keycloak (new test dep `com.github.dasniko:testcontainers-keycloak`), browser check of the UI.
- Risks: Keycloak Admin API in tests (container startup time), set-password flow security, permission-count ripple.

## Phase 1 — answers

- Invite link delivery: Backend token (user). Single-use 72 h token stored as a hash; letter from the cooperative mailbox; web /set-password page; backend sets the password via Admin API in memory, never logged; marks e-mail verified, clears required action.
- First chairman: Print to terminal (user). Same invite code path; without a mailbox the command prints the link once to stdout (never logs/audit).
- AUTO (recommended answers, non-blocking): ADR 0020 (0019 taken); 4.2 closed with evidence from 014; last sign-in recorded by backend on token resolution; electrician archive e2e redesigned with a throwaway member; "used tariff" = any charge or charge part incl. reversed; testcontainers-keycloak as test dependency (PR note); invitation letter via domain event + notifications listener with the token never in outbox/audit (token handed over through a non-persisted in-memory channel or regenerated — decided in tech).

## Phase 2

- Spec folder context/spec/020-go-live-polish, branch 020-go-live-polish from main @ b324146.
- AUTO: baseline suite skipped per user instruction (no test runs until the end); baseline = CI on main @ b324146.
- Functional spec written (Creation Mode, no markers left).
- Tech: specialists spring-backend, react-frontend, devops-infra drafted in parallel (exploration from Phase 1 Agent B reused). Assumptions (TC §5): client id `nadel-backend-admin`; Keycloak tests via GenericContainer (no new dependency); stored access state; first-chairman command not limited to prod/staging profiles; archived = archived_at set; no un-archive; separate V35 (tariffs) and V36 (invitations).
- Tasks: 9 slices; no per-slice Verify (user instruction; same pattern as 019); Feature Testing & Regression → testing-expert. No staffing gaps. `<!-- not-user-reviewed -->` marker not written (pipeline removes it; Phase 1 answers stand in for review).
- Slice 1: 4ef6f02, tests not run (user instruction), review round 1 running
- Slice 4 realm/infra: `infra/stand/.env.example` could not be read by the builder (permission denied). Not worked around; left as a proposal for the owner (add `NADEL_KEYCLOAK_ADMIN_CLIENT_SECRET=` written by stand-init.sh; note `STAND_USER_PASSWORD` ≥ 8 chars).
- AUTO: stand compose uses `${NADEL_KEYCLOAK_ADMIN_CLIENT_SECRET:-}` instead of `:?` (compose interpolates all services before stand-init.sh writes the secret). Backend must treat a blank secret as missing (fail fast outside dev; never fall back to the dev secret when the env var is set but blank).
- Slice 2 note: no index on charge.tariff_id (used by tariff list deletable check); acceptable at cooperative scale — follow-up candidate.
- Slice 2: cd9183b, tests not run (user instruction), review round 1 running
- Slice 3 note: mid-month handover from an archived tariff to a new tariff of the same type on the same plots stays a conflict for that month (handover on the 1st works) — documented in tariffs README.
- Slice 3: ddf8c81, tests not run (user instruction), review round 1 running
- Slice 5 help: the builder ran one test (`src/help/helpContent.test.ts`, passed) against the run instruction — recorded as a deviation. `set-password.md` is a `topic` (route handled outside appRoutes; a `screen` needs a shot). Final slice: add shot `member-card-access` to member-card front matter (uk+en) when screenshots are taken.
