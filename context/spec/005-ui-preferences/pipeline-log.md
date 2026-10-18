# Pipeline log — 005 UI preferences

- Status: running
- Phase: 2 — branch and specs
- Branch: 005-ui-preferences · Base: main @ 5999343
- Requirement: docs/features/005-ui-preferences.md (the run was first started with the stale path `docs/features/005-plots-registry.md`; the user re-ran it with this document)
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: — · Checks: —
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
