# Pipeline log — 007 Ownership

- Status: running
- Phase: 1 — analysis
- Branch: 007-ownership · Base: main (= origin/main) @ 0af7859
- Requirement: docs/features/007-ownership.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: — · Checks: —
- Review run: on (pending)

## Project facts

Same as 004–006 (see `context/spec/005-ui-preferences/pipeline-log.md`). Re-checked: main = origin/main @ 0af7859 (includes 006 #24 and the docs change #25 on status visibility); pipeline tooling present on main; clean tree; the 006 review run was stopped (PIDs 4300 4477 4537); ports 8080/5173 free; JaCoCo floor 0.95; latest migration V6 → next V7. Agents: spring-backend, postgres-database, react-frontend, testing-expert, slice-reviewer. UI verification with the project's Playwright. Safe local run: `make up` (demo data via the repeatable migration; no realm change expected).

## Settings

- questions: blocking · reviewerEscalation: auto · notGreen: ask · gitUnsafe: ask · notTestableLocally: auto · publish: pr+checks · reviewRun: on · notify: true

## Phase 1 — requirements brief

**Requirement:** plot ownership — who owns each plot, in what exact fractional share and since when, full history, change of owner as one audited operation, corrections; plot status GREY/GREEN from ownership; member archive and plot delete refusals; member visibility of owners; cooperative setting `memberPlotStatusVisibility` (OWN_ONLY default / ALL_PLOTS) with new permission `SETTINGS_MANAGE`, `GET/PUT /settings` and a settings page; `GET /me` owned plots; owners on the plot card, plots list, member card and My account. Users: chairman (edits), treasurer (views), members (own plots).

**Prior work:** Agent A — nothing off main (only docs commits, all merged). Agent B — `OWNERSHIP_EDIT` permission exists (enum, contract, V2 seed, catalog, i18n); 006 left hooks: `PlotDtoMapper.canSeeDetails` seam, `PlotCommands.delete` 007 comment, `PlotStatusPolicy` GREY-only, "Owners" placeholder on the plot card. No ownership/settings tables, endpoints or UI; `SETTINGS_MANAGE` absent; tests hardcode 8 permissions. Verdict: **new, extends 006 (plots) and 002 (members)**.

**Scope:** sections 2–7 of the feature doc. Out: section 8 (Excel import, debt certificate, ledger, notifications), mobile.

**Verification:** backend unit tests (fractions, sum-to-one, overlap, date arithmetic incl. 1st of month and 1 January), Testcontainers integration (change of owner, correction audit, visibility matrix both setting values × own/not own × PLOTS_VIEW, archive refusal, delete refusal, plot_status, settings default/permissions/audit, isolation for new tables), web unit tests, Playwright (chairman → two co-owners at 1/2; member sees co-owners only on own plot; settings page), `make check`, `make e2e`, screenshots via Playwright on `make up`.

**Risks:** module cycles (plots ↔ members ↔ ownership; Spring Modulith forbids cycles); `status` becomes optional in `PlotSummary`/`PlotProperties` (client changes); new permission breaks tests counting 8 permissions and needs a re-seed of `seed_base_roles`; status filter could leak statuses under OWN_ONLY; demo data rewrite (owners, co-owners, history).

## Phase 1 — decisions

- AUTO: no blocking question (rule: questions=blocking; no prior work, tooling present, clean tree, requirement detailed with decisions in section 9).
- AUTO: a change of owner, a correction and an ownership date may not lie in the future; "current" = `valid_to is null` (rule: plot_status is a cache updated in the ownership transaction; future-dated changes would need a scheduled job the feature doc does not ask for).
- AUTO: change date D must be later than the start of every current ownership and later than the end of every ended one, so history never overlaps and sums stay valid (rule: section 3 sum-to-one on any date, no overlaps).
- AUTO: a change of owner may have an empty new-owner list — it ends the current ownership and the plot becomes GREY "No owner" (rule: section 3 "or the plot has no owner"); the dialog offers at least one owner row by default.
- AUTO: archiving a plot that has current owners is refused until ownership is ended (rule: same reasoning as section 3's member archive refusal; keeps "no owners on archived plots"). Ownership changes on archived plots, common land, or to archived members are refused.
- AUTO: a correction edits one record (member, share, dates, basis) with a required reason; it may include corrected shares of the plot's other records for the same period so that a 1/2+1/2 → 2/3+1/3 fix is one valid operation; recorded as one audited action with old and new values (rule: section 3 "the change must still satisfy all rules"; single-record corrections could never fix co-owner shares).
- AUTO: shares are stored reduced (2/4 → 1/2); denominator 1–1000; numerator ≥ 1 and ≤ denominator; the same member twice in one change is refused (rule: exact fractions; conservative bounds).
- AUTO: a member's own plots = plots they currently own; on own plots a member sees status, area, cadastral number, building, owners and history (not notes, which stay with PLOTS_VIEW per 006); former owners get the ordinary restricted view (rule: section 4, CLAUDE.md visibility).
- AUTO: under OWN_ONLY the status is omitted for every plot the member does not own, common land included; a member's status filter then only matches their own plots (rule: section 4.1 literal; the filter must not leak statuses).
- AUTO: ownership writes require PLOTS_VIEW + OWNERSHIP_EDIT (rule: 006 write rule — editors must see what they edit); the Change owner action also needs MEMBERS_VIEW in the UI for the member picker (existing `GET /members`); the Chairman has all of them.
- AUTO: `SETTINGS_MANAGE` granted to Chairman only by default, via a new migration re-seeding `seed_base_roles` (rule: section 4.1; V2 comment on adding permissions). Docs listing eight permissions (ADR 0009, architecture, READMEs) are updated to nine.
- AUTO: a missing settings row means OWN_ONLY (default for new and existing cooperatives) (rule: section 4.1).
- AUTO: coverage floor raised to the measured value rounded down (rule: general rules).

## Phase 2 — branch and specs

- Spec folder and branch `007-ownership` from main @ 0af7859 (= origin/main). Baseline `make check` started in a detached worktree.
- functional-spec.md written (Creation Mode, no markers; 8 requirement groups, assumptions from Phase 1).
- Baseline: 427 backend tests green; local coverage 0.9481 < 0.95 (CI on main green). Recorded in review-log.md.
- technical-considerations.md written (inputs: spring-backend, postgres-database, react-frontend; exploration from Phase 1 Agent B).
- AUTO: ownership lives in the plots module; new `settings` module; synchronous SPIs `MemberArchiveGuard` (members) and `OwnedPlotsSource` (identity) implemented in plots; new public `MemberDirectory` (rule: Spring Modulith forbids cycles; plots owns status and visibility).
- AUTO: overlap backstop with a `btree_gist` exclusion constraint; fallback partial unique index if the extension cannot be created (rule: the only defence against concurrent writes besides the plot lock).
- AUTO: the plots search box also matches current owners' names for PLOTS_VIEW; no separate owner parameter (rule: spec "typing an owner's last name in the search"; fewer controls).
- AUTO: "today" is the Europe/Kyiv date via a Clock bean (rule: AuditQueries precedent; UTC server would refuse today's changes at night).
- AUTO: a member sees the basis on their own plot's records; `GET /members/{id}/ownerships` for PLOTS_VIEW or MEMBERS_VIEW or self (rule: spec 2.5/2.6).
- AUTO: a plot with ownership history cannot be changed to common land (rule: common land never has owners; backend review C5).
- Spec example for plot 9 aligned with the demo plan (owner since 01.06.2025); owner search wording aligned with the design.
- tasks.md written: 8 slices (settings; owners read; change of owner; corrections; guards; member visibility; e2e/docs/coverage; Feature Testing & Regression by testing-expert). No staffing gaps (database work stays with spring-backend for two migrations + demo data, as in 006). No `not-user-reviewed` marker (Phase 1 decisions stand in for the plan review).
- AUTO: the migration is split by slice — V7 settings + permission, V8 ownership (rule: each slice commit runnable; no edits to a committed migration).
- Slice 1 committed c9a91b3 (backend 98 targeted incl. SettingsTests 13, isolation 7, roles 57; verify 447 tests, coverage 0.9584; web 134, shared 42, ui-tokens 56; typecheck/lint/format green; generated client stable). Review r1 started.
- Slice 1 review r1 MAJOR_ISSUES (M1 English audit label in uk UI) → fix 2d5914c → r2 PASS.
- Slice 2 committed (backend targeted 137 green, verify 528 tests, coverage 0.9611; shared 64, web 144, ui-tokens 56; typecheck/lint/format green). Note: machine load average 40–65 slowed client runs. Review r1 started.
