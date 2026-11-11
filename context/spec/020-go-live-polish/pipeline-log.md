# Pipeline log — 020 go-live polish

- Status: finished
- Phase: 6 done (review run up)
- Branch: 020-go-live-polish · Base: main @ b324146
- Requirement: docs/features/020-go-live-polish.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: https://github.com/Philatelist/nadel-platform/pull/71 · Checks: green (d26c7b8)
- Review run: running at http://localhost:5173 and :8080 (make up PID 32152, backend 32648, vite 32537; PIDs in the session scratchpad review-run.pid)

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
- Slice 4: a2e5224, tests not run, review r1 running
- Slice 5: ef3b71e, tests not run, review r1 running
- `infra/stand/` is excluded by the owner's local `.git/info/exclude` (never tracked). The slice 4 and slice 7 stand changes (compose.yml env, stand-init.sh secret rotation and password policy, README, deploy.sh, new first-chairman.sh) exist only on disk and are NOT committed. Not force-added (owner's local folder); reported for the owner.
- Slice 7 docs builder issued `git stash && git stash pop` by mistake; the classifier denied it, nothing ran (stash list empty, tree intact).
- Slice 6 backend notes: legacy-linked members (demo users) get no member_access row at sign-in → no lastSignInAt («ще не входив»); invited members get it. Open gaps (documented, not fixed): Excel import can change an e-mail without the login rules; disable/archive racing an invite between prepare and complete leaves INVITED with a revoked link; nothing stops a chairman disabling their own access (spec silent). MembersAccessAcceptanceTests onboarding expectation changed (new e-mail → INVITED, 403 until the new link is used).
- Slices 6/7 were built concurrently; shared files (MemberInvitations.java, members/identity READMEs, application.yml) went into the slice 6 commit with the slice 7 hunks included.
- Slice 6: 71e116a, tests not run, review r1 running
- Slice 7: 8e795f1, tests not run, review r1 running
- Slice 8: 58c7a5c, tests not run, review r1 running
- Owner removed infra/stand/ from .git/info/exclude and asked to include it. Checked 7 files (only 127.0.0.1, 203.0.113.10, example.com/org; secrets from env); owner checked and edited .env.example (NADEL_NOINDEX=true, NADEL_KEYCLOAK_ADMIN_CLIENT_SECRET=, STAND_USER_PASSWORD comment). Committed 01e564a chore(infra): test stand on one server (+ infra/README.md pointer). Fix-review r1 running.
- Stand fix-review r1: PASS (minors in review-log, not fixed by rule).
- Slice 9 (testing-expert): `make check` EXIT 0 (backend 4360 tests, 0 failures; JaCoCo 0.9771 vs floor 0.977 — thin margin); e2e after `down -v`: feature specs 102 passed, `@smoke` 23 passed, "creation to archive" ×10 passed. RED/GREEN for invite/accept, disable/archive race, tariff delete audit, archive month run, payer default, duplicate attachment. Fixes found by the run (tests/config only, no `src/main`): realm import (pre-hashed demo passwords vs `length(8)`), `clientScopeMappings` for the admin client (403 without them), `editUsernameAllowed` (login change 400), test Postgres `max_connections=400`, isolation test updated for the V35 tariff DELETE grant, RoleLogicTests index, InvitationTenantFunctionTests cast, PaymentForm payer tests made RED-sensitive, flaky MemberAccessKeycloakTests wait, extra tests for coverage, `member-card-access` shot. The stand imports the same realm file (deploy.sh rsyncs it), so the realm fixes apply there. Not done: a `set-password` screenshot (topic, no shot).
- Slice 9: 2b2764b, full suite green (see above), review r1 running. Phase 4 started: awos:verify (verification agent on an isolated stack, own DB).
- Slice 9 review r1: PASS (minors in review-log).
- Verify (agent, isolated stack, DB nadel_verify dropped after): 31 [x], 1 [?] (2.3 "Розподілено X з Y" one line at 360 px: one line, but the 422 px allocation table scrolls inside a 286 px container so the total is cut until scrolled — owner judges), 1 NOT MET: 2.1 first-chairman command exits 1 at start-up under the `command` profile (web-application-type none) because ImportsController/ReportsController/ReportTemplatesController inject HttpServletRequest in their constructors; the command tests never booted the real profile. Fix attempt 1 delegated to spring-backend (test reproducing the stand invocation first). Observation (not a criterion): set-password shows «Паролі збігаються» while both fields are empty — follow-up. Test Keycloak users (@e2e.local) left in the local realm; `down -v` clears them (bulk delete was denied, not worked around). Screenshots in docs/screenshots/020-go-live-polish-*.png, untracked (folder not git-ignored; owner setup).
- Verify fix 1: 7cfa226 (controllers read Accept-Language per call; FirstChairmanJarCommandTests RED→GREEN; 777 module tests green; real-jar smoke exit 0). Fix-review r1 running. Criterion 2.1 first-chairman now [x]; 32 [x], 1 [?] (2.3 360 px). Spec and tech Status → Completed. Product context: architecture.md already updated in slice 7; nothing else to suggest.
- Verify fix 1 review r1: PASS. Full suite: make check EXIT 0 in slice 9 (before the fix); after the fix only the touched modules (777 tests) — CI runs the full suite. Phase 4 done; worktrees: main only. Phase 5 started.
- CI round 1 on PR #71: Backend red (JaCoCo 0.97698 < 0.977, 1 line short; the child-JVM command test is not counted), Security red (gitleaks generic-api-key on a fake test token). Fixes: ab28ff0 gitleaks allowlist for the two fake test tokens (local gitleaks on 17 commits: no leaks); deaa33e tests for 27 CI-missed lines in feature classes (47 tests green, 3 mutations caught). Pushed; fix-review running.
- CI round 2: all 6 checks green on d26c7b8. CI fix review r1: PASS. Phase 6: down -v (realm re-import, clears verify test users), make up detached; smoke: backend health 200, web 200, realm 200, Mailpit 200, public inspect bogus token 404, members unauthenticated 401, /set-password 200. Run finished.
- Owner decisions on PR #71 (7 items): 82d8beb realm username/email admin-only; 96059d1 allocation cards at 360 px; 93b6272 set-password hint; bdff989 V37 charge(tenant_id, tariff_id) partial index; 47e2b92 import cannot move a login (tests + README, no code — the import never changes a matched member's e-mail); 7e57eed OWN_ACCESS / last chairman with access guard + in-memory rate limit 5/15 min per address and per invitation e-mail (429 TOO_MANY_REQUESTS), `server.forward-headers-strategy: native`; 22b26b5 .gitignore docs/screenshots/. Affected module tests green with RED evidence; fix review r1 PASS. Not committed: `.github/workflows/ci.yml` (e2e job env NADEL_INVITATIONS_RATE_LIMIT_MAX_REQUESTS=1000) — agents do not change .github; patch in the session scratchpad ci-yml-proposal.patch for the owner.
- Owner bug on PR #71 (disable → re-invite: old password worked, 'not linked'): fixed in 6dae469 (lock-out of the existing account at re-invite). RED 3/3 → GREEN; members+identity+ModularityTests 445 green; member-invite e2e 8/8. Review r1 PASS.
- Owner decisions on PR #71 (round 3): (1) c7c11ae an e-mail change of a member with access locks the account out (disabled, credentials removed, sessions ended; the new link goes to the new address automatically, as before). (2) 9c994e7 the limits count only sent invitations/resends (per member 5, per client address 30 — AUTO: a separate `max-sends-per-address` so a chairman can invite the cooperative at go-live; owner to confirm) and failed links (404 INVITATION_INVALID, per address 5; past it the address gets 429 on every public request until the window passes); a usable link opens any number of times. (3) `server.forward-headers-strategy: native` confirmed by the owner. (4) ci.yml diff given to the owner (now also NADEL_INVITATIONS_RATE_LIMIT_MAX_SENDS_PER_ADDRESS=1000); not committed. RED shown for the e-mail lock-out and the page-load test; members+identity+imports+ModularityTests green (MemberAccessKeycloakTests 9/9); clients typecheck/lint/1120 web tests green; PDFs rebuilt. Fix review r1 running.
- CI on 6cf5719: Backend red — flaky PaymentMessagesTests.theOutboxCarriesIdsOnly (a random payment UUID contained "512", the amount the test forbids). Fixed the assertion to the full amount "512.34"; test 8/8 green. All other checks green (e2e included).
- CI on 7766574: all 6 checks green. Review run restarted (down -v, make up); smoke green.
- Owner decisions on PR #71 (round 4): (1) 30 sends per address kept. (2) A usable link is never refused, from a blocked address too: the public endpoints look at the token first and only an INVITATION_INVALID is counted, or answered 429 once the address has 5 within the window (`InvitationRateLimiter.countFailedLink`). RED: the new HTTP test against the old pre-check, "Status expected:<200> but was:<429>". InvitationRateLimiterTests 7, InvitationRateLimitTests 5, MemberInvitationTests 17, ModularityTests green; typecheck, format, SetPassword web tests green; PDFs rebuilt. (3) The two leftovers (send check/record not atomic, one shared LRU map) stay in review-log.md, accepted by the owner.
