# Pipeline log — 006 Plots registry

- Status: running
- Phase: 2 — branch and specs
- Branch: 006-plots-registry · Base: origin/main @ 48cd8e0
- Requirement: docs/features/006-plots-registry.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: — · Checks: —
- Review run: on (pending)

## Project facts

Same as 004/005 (see `context/spec/005-ui-preferences/pipeline-log.md`). Re-checked: origin/main @ 48cd8e0 includes 005 (#23 merged); pipeline tooling present; clean tree; the 005 review run was stopped (PIDs 29856 30061 30106); ports 8080/5173 free; JaCoCo floor 0.94; latest migration V5 → next V6. Agents: spring-backend, postgres-database, react-frontend, testing-expert, slice-reviewer. UI verification with the project's Playwright. Safe local run: `make up` (a change to demo data is picked up by the repeatable migration; no realm change).

## Settings

- questions: blocking · reviewerEscalation: auto · notGreen: ask · gitUnsafe: ask · notTestableLocally: auto · publish: pr+checks · reviewRun: on · notify: true

## Phase 1 — requirements brief

**Requirement:** plots registry — the cooperative's plots and common land as records (number, street, kind, area, cadastral number, building flag, notes, soft archive), list/card/form screens, permission-based visibility, audit; the map GeoJSON moves to `GET /plots/map`. No polygons, no owners (007).

**Prior work:** Agent A — nothing off main (one docs branch `origin/docs/chairman-answers` edits the old plots doc; its only plots change is already in 006 on main). Agent B — phase-0 read-only map stub in `app.nadel.plots` (entity, `plot`/`plot_status` tables with RLS, `GET /plots` GeoJSON without permission check, demo 13+2 plots with seeded colours/debts); permissions PLOTS_VIEW/PLOTS_EDIT exist and are seeded; no CRUD, no audit, no screens. Verdict: **extends the phase-0 plots module**.

**Scope:** sections 2–6 of the feature doc. Out: section 7.

**Verification:** backend Testcontainers tests (isolation, permissions allowed/denied, uniqueness, visibility, audit), web unit tests, Playwright, `make check`, `make e2e`, UI via Playwright on `make up`.

**Risks:** `/plots` path changes meaning (map moves to `/plots/map`; existing tests and MapPage); demo data rewrite (seeded debts/colours conflict with "GREY until 007"; `ON CONFLICT (tenant_id, number)` breaks with a partial index); landing route change breaks e2e expectations.

## Phase 1 — decisions

- AUTO: no blocking question (rule: questions=blocking; no prior work, tooling present, clean tree, requirement detailed).
- AUTO: until 007 every plot's status is GREY and no debt is shown; demo `plot_status` rows are reseeded as GREY without debt (rule: section 2 "every PLOT is GREY until 007; keep plot_status consistent").
- AUTO: the GREY label depends on kind — "Без власника" / "No owner" for a plot, "Спільні землі" / "Common land" for common land (rule: money-rules §9 defines GREY as common land or a plot without an owner; the current single label is wrong for plots).
- AUTO: a member without PLOTS_VIEW gets the restricted view (number, street, kind, status) from the list, the card and the map; the response shape already has room for "own plot" details, which stay empty until 007 (rule: section 3).
- AUTO: `GET /plots/map` requires sign-in only and applies the same visibility; debt and area are omitted for people without PLOTS_VIEW (rule: section 4; closes today's gap where any member receives every plot's debt).
- AUTO: plot number uniqueness is case-insensitive after trimming among non-archived plots; restore re-checks it; cadastral number unique per cooperative including archived plots (rule: section 2; case-insensitive avoids "12А"/"12а" duplicates).
- AUTO: COMMON land may have area 0; PLOT needs area > 0 (rule: section 2 "> 0 for PLOT").
- AUTO: create/edit/archive/restore/delete require PLOTS_EDIT; read with all fields requires PLOTS_VIEW (rule: ADR 0009 permissions).
- AUTO: after sign-in, people with PLOTS_VIEW land on the plots list; others keep landing on My account (rule: section 5.1 "default screen for users with PLOTS_VIEW").
- AUTO: area input accepts sotkas or m² via a unit choice in the form; the server stores m² (rule: section 2 "accepts either unit on input"; conversion happens on the client only for input, the server validates the stored m² value — not money, so no server-only rule applies).
- AUTO: coverage floor raised to the measured value rounded down (rule: general rules).
- AUTO: every signed-in person of the cooperative can open the plots list (restricted view without PLOTS_VIEW), so ordinary members now get a side menu with a "Ділянки" / "Plots" item; e2e expectations that a plain member has no menu are updated (rule: section 3 gives members the restricted list; a list nobody can reach would not satisfy it).
- Baseline: 278 tests green but coverage 0.9393 < floor 0.94 (flaky by one line on main; 005 measured 0.9402). Recorded in review-log.md; reported to the owner.

## Phase 2 — branch and specs

- Spec folder and branch `006-plots-registry` from origin/main @ 48cd8e0.
- functional-spec.md (Creation Mode, no markers), technical-considerations.md (spring-backend + react-frontend input), tasks.md (6 slices; no staffing gaps — postgres-database work stays with spring-backend for one migration + demo data; no `not-user-reviewed` marker).
- AUTO: natural sort computed in the database (generated `number_sort_key`), tested with a database test instead of the Java comparator unit test named in the feature doc (rule: one source of the algorithm; the list is sorted in SQL).
- AUTO: deleting an archived plot is refused with `PLOT_ARCHIVED`; restore first (rule: section 2 "any non-archived plot without references can be deleted").
- AUTO: restricted viewers cannot search by cadastral number and never see archived plots (rule: section 3 restricted view; prevents probing hidden fields).
- AUTO: plot number limited to 20 characters (rule: conservative bound; free-text numbers like "12а", "R-1").

## Phase 3 — implement
- Slice 1 committed aeba3dd (backend 45 targeted, web 102 unit; typecheck/lint/format green; generated client stable). Note: mobile/App.tsx touched only to compile after the status-key rename (same label). Review r1 started.
- Slice 1 review r1 PASS. Slice 2 committed 441f9b1 (backend PlotsReadTests 27 + map; web 108, shared 20 unit; e2e specs updated for landing/menu, typechecked only — run in Slice 5). Review r1 started.
- AUTO: area wording uses Ukrainian plural forms (1 сотка, 2–4 сотки, 5+ соток, fractions сотки); the spec example "6,5 соток" corrected to "6,5 сотки" (rule: correct Ukrainian; reviewer note in Slice 2).
- Slice 2 review r1 PASS.
