# Pipeline log — 005 UI preferences

- Status: finished
- Phase: 6 — review run
- Branch: 005-ui-preferences · Base: main @ 5999343
- Requirement: docs/features/005-ui-preferences.md (the run was first started with the stale path `docs/features/005-plots-registry.md`; the user re-ran it with this document)
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: https://github.com/Philatelist/nadel-platform/pull/23 · Checks: green (run 39141013064)
- Review run: on (pending)

## Project facts

Same as 004 (see `context/spec/004-role-names/pipeline-log.md`), re-checked: base `main` == origin/main @ 5999343 (includes 003 #18, 004 #21, 005 doc #22); pipeline tooling present; clean tree; previous 004 review run stopped; ports 8080/5173 free; JaCoCo floor 0.93; latest migration V4. Agents: spring-backend, react-frontend, testing-expert, slice-reviewer. UI verification with the project's Playwright (Chrome extension needs a browser choice). Safe local run: `make up`.

## Settings

- questions: blocking · reviewerEscalation: auto · notGreen: ask · gitUnsafe: ask · notTestableLocally: auto · publish: pr+checks · reviewRun: on · notify: true

## Phase 1 — requirements brief

**Requirement:** theme switch (System / Light / Dark, per browser, no flash), cooperative name as the header title with the product name secondary and the tab title "<page> — <cooperative>", base role descriptions translated unless customised (mirrors 004 names), audited.

**Prior work:** Agent A — nothing off main. Agent B — not implemented; builds on 003 (dark tokens in `index.css` under `prefers-color-scheme`, `theme.test.ts` sync test, LocaleSwitch pattern, `nadel.locale` storage pattern) and 004 (`nameCustomized` end to end: V4, `Role`, `BaseRoles.defaultName`, `RoleCommands` restore, `RoleSnapshot.auditName`, `roleDisplayName`, `auditRoleValue`, `nameToSubmit`, RolesPage restore). Verdict: **extends 003 and 004**.

**Scope:** sections 2–5 of the feature doc. Out: section 6.

**Verification:** backend Testcontainers tests, `pnpm -r test`, `make check`, `make e2e`, UI via Playwright on `make up`.

**Risks:** inline script vs a future Content-Security-Policy; e2e asserting the header product link; the theme sync test parsing the dark block.

## Phase 1 — decisions

- AUTO: no blocking question (rule: questions=blocking; no prior work, tooling present, clean tree, requirement clear).
- AUTO: theme switch placement under 768 px — inside the menu panel for people who have a menu; people without a menu (a plain member) keep it in the header row next to the language switch (rule: section 2 says "inside the collapsed menu", but a plain member has no menu since 003; keeping it reachable is the conservative choice).
- AUTO: `descriptionCustomized` only on `RoleDetails`; `RoleRef` carries no description today, so it is unchanged (rule: section 4 "where a description is returned").
- AUTO: a separate "Restore standard description" button, shown when a base role's description is customised; the 004 name restore stays as it is (rule: section 4 allows either; separate buttons match the 004 pattern and keep each restore simple).
- AUTO: audit values for a non-customised base-role description use the same `base:<CODE>` token; the web maps it by field (`description` → catalog description, `name` → catalog name) (rule: Ukrainian only in i18n catalogs; mirrors 004).
- AUTO: like 004's name, the role form sends the stored description when the catalog text shown in the field is untouched (rule: avoids a permission-only save customising the description in Ukrainian).
- AUTO: the product name stays as a small muted label after the cooperative name in the header (rule: section 3 example).
- AUTO: the tab title uses each page's own title; pages without one show just the cooperative name; sign-in and full-page messages show the product name (rule: section 3).
- AUTO: the inline theme script also updates the `theme-color` meta for the chosen theme (rule: no flash; browser chrome matches).
- AUTO: coverage floor raised to the measured line coverage rounded down (rule: section 7).

## Phase 2 — branch and specs

- Spec folder and branch `005-ui-preferences` from `main` @ 5999343. Baseline `make check` green (backend 259; unit 56/5/65; coverage 0.9392).
- functional-spec.md (Creation Mode, no markers), technical-considerations.md (react-frontend + spring-backend input), tasks.md (5 slices, no staffing gaps, no `not-user-reviewed` marker).
- AUTO: V5 also flags base roles whose stored description already differs from the V2 seed text as customised (rule: descriptions were editable since 002; hiding an existing edit behind the catalog text would silently change what the chairman wrote).
- AUTO: name and description restores may be combined in one request (rule: independent fields; one audit entry).

## Phase 3 — implement
- Slice 1 committed a33b050 (web 76 unit tests, typecheck/lint/format/build green). Review r1 started.
- Slice 2 committed 39719cd (web 82 unit tests; typecheck/lint/format green; e2e header assertions updated, not run yet).
- Slice 1 review r1 MAJOR_ISSUES (M1 MapPage colours) → orchestrator showed MapPage is unreachable → r2 PASS (M1 withdrawn, phase-6 note).
- Slice 2 review r1 PASS.
- Slice 3 committed 413ee10 (backend 88 targeted, web 94 unit; typecheck/lint/format clean; generated client in sync). Review r1 started.
- Slice 3 review r1 MAJOR_ISSUES (M1 name restore customised the description in uk) → fix c99e535 → r2 PASS.
- Slice 4 (testing-expert): `web/e2e/ui-preferences.spec.ts` (10 tests). GAP: at 390 px the Menu button wraps to a third header row below the cooperative name (functional spec 2.2: name next to Menu). Sent to react-frontend as a minimal Layout fix (attempt 1 of 3). Also: blocking all of localStorage breaks the sign-in library (pre-existing), so the storage-blocked test blocks only the app's `nadel.*` keys; a failed run left dirty local data, so the testing agent reset the local Docker database (`down -v`, local dev only).
- Gap fix (Layout: Menu button on the title row below md) verified: 22 targeted e2e passed. Slice 4 verify: make check green (backend 275; unit 56/5/96), coverage 0.9402 → floor 0.93→0.94, make e2e 43 passed.
- Slice 4 committed 0abc642; review r1 MAJOR_ISSUES (M1 desktop header order, M2 e2e cleanup) → fix 46b47b5 (make e2e 43 passed) → r2 PASS. All slice reviews PASS — Slice 5 gate open.
- Slice 5 (testing-expert): RoleDescriptionsAcceptanceTests (3) + e2e ui-preferences-acceptance.spec.ts (5); no GAP; make check green (backend 278, floor 0.94 met), make e2e 48 passed.
- Slice 5 committed d75dd4a; review r1 MAJOR_ISSUES (order-dependent acceptance test) → fix 7ef6e94 (green in both run orders; `./mvnw verify` 278, floor met) → r2 PASS. All slice reviews PASS.

## Phase 4 — verify

- UI checked in Ukrainian with the project's Playwright on the local stack; screenshots `docs/screenshots/005-ui-preferences-*.png`; Dark persists across navigation; tab titles "<page> — Demo Garden Cooperative". (A first screenshot attempt looked like a bug — "Темна" matched "Системна" in a non-exact lookup in my script; not an app issue.)
- Acceptance criteria: 17 of 17 verified. Status: Completed.
- Full suite: `make check` green at d75dd4a (backend 278 after the fix via `./mvnw verify` at 7ef6e94, floor 0.94 met; unit 56/5/96); `make e2e` 48 passed at d75dd4a (the later fix only touched a backend test). Baseline 0 failures → no regressions.
- Product docs: none needed.

## Phase 5 — publish

- Pushed `005-ui-preferences`; PR https://github.com/Philatelist/nadel-platform/pull/23 — "feat(web): theme switch, cooperative name in the header, translated role descriptions".
- Checks: Backend, Clients, End-to-end, SAST, Security — all green (run 39141013064). `gh pr checks --watch` exited early twice while e2e was pending; waited with `gh run watch` instead. No CI fixes, no re-runs.

## Final report

- Commits: b5e85b4 specs · a33b050 S1 · 39719cd S2 · 413ee10 S3 · c99e535 S3 fix · 0abc642 S4 (incl. phone header gap fix) · 46b47b5 S4 fix · d75dd4a S5 · 7ef6e94 S5 fix · verification · (this log commit).
- Acceptance criteria: 17/17 verified.
- Reviews: S1 2 rounds (M1 withdrawn: MapPage unreachable) PASS; S2 1 round PASS; S3 2 rounds (M1 name restore customised the description in uk) PASS; S4 2 rounds (M1 desktop header order, M2 e2e cleanup) PASS; S5 2 rounds (M1 order-dependent acceptance test) PASS. One gap from Slice 4 e2e (Menu button wrapped below the name at 390 px) fixed in Layout.
- Full suite vs baseline: baseline 0 failures; branch 0 failures (backend 278, unit 56/5/96, e2e 48). Coverage 0.940, floor 0.93 → 0.94.
- Follow-ups: map re-reads colours on theme change (phase 6); full-storage block breaks sign-in (pre-existing); Keycloak page not themed; minor review notes in review-log.md.
