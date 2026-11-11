# Pipeline log — 021 member PWA

- Status: running
- Phase: 3 (implement, slice 1)
- Branch: 021-member-pwa · Base: main @ 3e39ab2 (origin/main; local main is behind at b324146 and is not moved)
- Requirement: document docs/features/021-member-pwa.md (untracked in the working tree)
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: — · Checks: —
- Review run: on (not started yet; the 020 review run is still up and is stopped before Phase 6)

## Project facts
- Build: `make check` (contract, format, lint, tests); backend `cd backend && ./mvnw verify`; one test class `cd backend && ./mvnw test -Dtest='<Class>'`; module `-Dtest='app.nadel.<module>.**'`; clients `pnpm -r typecheck && pnpm -r lint && pnpm -r test`; e2e `make e2e`, one spec `pnpm --filter web exec playwright test <name>`, smoke `--grep @smoke`.
- Full suite: `make check` then `make e2e` (CLAUDE.md verification levels: full local suite before the first push of a new feature).
- Specialists: spring-backend, react-frontend, postgres-database, devops-infra, expo-mobile (not needed: Expo is removed), testing-expert (testing agent), slice-reviewer.
- UI: yes (web/). Browser tool: claude-in-chrome (deferred tools) available.
- Safe local run mode: `make up` — local docker-compose infra (Postgres, Keycloak realm nadel, Mailpit, fake Telegram API), dev profile; no real external services.
- Git rules: Conventional Commits; no AI attribution; branch per feature `NNN-name`; agents never merge/approve/push main; protected files CLAUDE.md, .claude/, .github/, .mcp.json are proposed, not edited.
- Autonomy: questions blocking; reviewerEscalation auto; notGreen ask; gitUnsafe ask; notTestableLocally auto. publish pr+checks; reviewRun on; notify true.
- Publishing pre-flight: gh authenticated to github.com (Philatelist/nadel-platform), PR #71 used it today. CI checks: Backend, Clients, Demo data by day, End-to-end (Playwright), SAST (Semgrep), Security.
- Next spec number: 021.

## Pre-flight issues
- Working tree: `context/spec/020-go-live-polish/pipeline-log.md` has one uncommitted line (020 CI green + review-run restart) written by the 020 run; the file is identical on origin/main, so it carries over to the new branch. Phase 5 needs a clean tree.
- `docs/features/021-member-pwa.md` is untracked (the requirement itself).

## Phase 1 — requirements brief
- **Requirement:** docs/features/021-member-pwa.md. The member (and the board, electrician, treasurer) uses Nadel on a phone: the web app installs as a PWA, sends Web Push for five events, a reading can carry a meter photo, the phone screens work at 360 px with «Сплатити в банку» and copy buttons; the Expo shell is removed (ADR 0021); stand fixes (kcadm login retry, README notes, VAPID keys generated once).
- **Prior work:** Agent A — nothing on any branch, stash, worktree or unreachable commit. Agent B — nothing of PWA/push/photo exists; reuse: notifications channels/outbox/JobRunr and infra/fake-telegram pattern, PersonalDataCipher (ADR 0016), treasury attachment checks (FileSniffer, metadata strippers, 5 MiB, package-private in treasury.internal), web heicToJpeg/AttachmentPicker/AttachmentPreview (hard-wired to expense attachments), cooperative_settings (settings module), NbuQrPayload (documents), MemberAccessRevoked (members, package-private). **Verdict: new** (extends 010 meters, 016 invoices, 017 delivery, 018 receipts, 019 help, 020 access).
- **Gaps in the requirement found by the code search** (decided below): no domain events for invoice issued, reading submitted, announcement sent (only audit events); members have no announcement screen and there is no invoice detail screen; `/me/invoices` rows have no QR/IBAN; `notify_payments` is in delivery_settings; a payment's payer can be null; no web-push library; e2e runs on the Vite dev server (a SW needs a build); no Lighthouse in the repo; no script-test harness for stand scripts; stand-init rotates the admin secret each run (VAPID must be generate-once).
- **Scope:** sections 1–7 of the document; out of scope as its section 8.
- **Acceptance criteria:** section 9 of the document.
- **Verify:** backend module tests (`./mvnw test -Dtest='app.nadel.<module>.**'`), web vitest, Playwright specs at 360 px with a phone viewport, `pnpm audit` + OSV, a shell test of stand-init with a stub kcadm; browser check through claude-in-chrome on `make up`.
- **Risks:** feature size (seven sections); SW testing on a build rather than the dev server; RFC 8291/VAPID crypto correctness; iOS behaviour cannot be tested locally ([?]); Lighthouse not available locally.

## Phase 1 — decisions (no blocking question: AUTO under `questions: blocking`)
- AUTO (prior work none): build fresh on origin/main 3e39ab2.
- AUTO (working tree): the carried 020 pipeline-log line (true record of the 020 run) and the untracked requirement document are committed with the Phase 2 commit, so Phase 5 starts from a clean tree.
- AUTO: Web Push encryption (RFC 8291 aes128gcm) and VAPID (RFC 8292, ES256 JWT) implemented with JDK crypto (EC P-256, ECDH, HKDF via HmacSHA256, AES-GCM), no new backend dependency; recorded in ADR 0021. Web: `vite-plugin-pwa` (MIT, maintained) with Workbox, `injectManifest` or `generateSW` per tech doc.
- AUTO: push for a payment goes to the payer member when the payment has one, otherwise to nobody (the document says "the payer"); gated by delivery_settings.notify_payments.
- AUTO: new public domain events `documents.InvoicesIssued` (tenant, month, invoice ids/plots), `meters.ReadingSubmitted`, a public `members.MemberAccessRevoked`-equivalent event or SPI, and an internal announcement-sent event; all through the outbox (ADR 0007).
- AUTO: «Нове оголошення від правління» opens the member's account page (no member announcement screen exists; adding one is out of scope); recorded as an assumption.
- AUTO: «Сплатити в банку» and the copy buttons go on each invoice row of the account (`/me/invoices`, extended with the NBU link, IBAN and purpose), as there is no invoice detail screen; "the invoice view" = that row.
- AUTO: reading photos stored in the database like ADR 0019 receipts (not S3); the shared file checks move out of treasury.internal into a shared public place.
- AUTO: Lighthouse installability is replaced locally by a Playwright check of the manifest fields, icons and an active service worker on a production build (`vite preview`); a real Lighthouse run and iOS behaviour are marked [?] for a manual check.
- AUTO: the stand-init script test is a shell test with a stub `docker`/kcadm that fails the first logins with 503, run from the Makefile/CI without new tooling.
- Phase 2 started: spec folder context/spec/021-member-pwa, branch 021-member-pwa from origin/main 3e39ab2; 020 review run stopped (PR #71 merged).
- Baseline: make check on origin/main 3e39ab2 green (4405 backend tests, 0 failures); worktree removed.
- Functional spec written (no [NEEDS CLARIFICATION] left). Technical considerations written from the spring-backend, react-frontend and devops-infra drafts (Agent B's code search as the exploration). Assumptions in the tech doc (all AUTO): vite-plugin-pwa 2.0.0 injectManifest, no runtime caching, no SW in dev; push payload = codes + month + device language, texts from an i18n slice in the SW (resolves the web draft's server-text idea in favour of the CLAUDE.md language rule); JDK-only VAPID/RFC 8291; one JobRunr job per device and message; subscriptions per member, max 10 devices; 409 PUSH_DISABLED; endpoint host allowlist; new module app.nadel.files; new multipart operations, old JSON submit kept; READING_PHOTO_REQUIRED 400 and also for the bot; a new photo with the same value counts as a change; InvoiceRow.payment; V38 tables reading_photo, push_subscription, push_throttle; separate Playwright PWA config on vite preview :5173 (`make e2e-pwa`); `.npmrc` removed only if the web passes without it; stand script test in bash with a stub docker. No /awos:hire needed (vite-plugin-pwa and Web Push are covered by react-frontend and spring-backend).
- Tasks written: 11 slices (Expo removal + ADR; stand; pay from phone; photo submit + approvals; photo rule + board entry; installable app; push + invoices push; other pushes; 360 px layouts; help; Feature Testing & Regression). No staffing gaps. AUTO: <!-- not-user-reviewed --> removed (Phase 1 stands in for the plan review). AUTO: migration split V38 photo / V39 push. Phase 2 commit.
