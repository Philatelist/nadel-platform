# Pipeline log — 010 Meters and readings

- Status: running
- Phase: 3 — implement (Slice 1)
- Branch: 010-meters-and-readings · Base: main (= origin/main) @ 063af9f
- Requirement: docs/features/010-meters-and-readings.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: — · Checks: —
- Review run: on (not started yet)

## Project facts

Same as 004–009 (see `context/spec/009-charge-types-and-tariffs/pipeline-log.md`). Re-checked: origin/main @ 063af9f (009 #31, one-language fix #32, version correction #33, 010 doc #34); pipeline tooling present on origin/main; clean tree; no review run running. Agents: spring-backend, postgres-database, react-frontend, testing-expert, slice-reviewer. Safe local run: `make up`. Full suite: `make check`; e2e: `make e2e` (a realm or seed change may need `docker compose -f infra/docker-compose.yml down -v`). Latest migration on origin/main: V11 → next V12. One-language rules (CLAUDE.md): every system text from the catalogs; demo data in Ukrainian; data-user-content markers; language-purity e2e spec; local ESLint rule.

## Settings

- questions: blocking · reviewerEscalation: auto · notGreen: ask · gitUnsafe: ask · notTestableLocally: auto · publish: pr+checks · reviewRun: on · notify: true
- Before the last push of Phase 5: Status `finished`, Phase `5 — published`, committed with the branch; Phase 6 changes no tracked files.
- CI fix rule (SKILL.md 6b6e9f0): after a CI fix, run the failed job's full local suite before pushing.

## Phase 1 — requirements brief

**Requirement:** a register of meters (PLOT, SUBSTATION, QUARTER, COMMON_CONSUMER; ELECTRICITY kWh / WATER m³; serial, name, installation date and initial reading, removal with final reading, note), an electricity meter tree with dated parent history, replacement as one operation (incl. counter wrap-around), readings on any date (`taken_on` prefilled with today with a quiet hint; APPROVED / PENDING / SUPERSEDED; ordering validation; unusual-consumption warning; correction and deletion until a charge uses them via a `ReadingUsage` port), server-side interval and range consumption with linear interpolation marked as calculated, four permissions, a member's own meters and pending submissions with approval, an Excel `Лічильники`/`Meters` sheet, audit, Ukrainian demo data. Users: chairman (all), electrician custom role (METERS_VIEW, READINGS_ENTER), treasurer (METERS_VIEW), member (own meters, submission). Nothing is charged (011).

**Prior work:** Agent A — nothing off main (only the requirement doc 063af9f and older meter-rule docs #19/#20). Agent B — nothing built; 009 placeholders "за показаннями" / "застосовується до ділянок з лічильником" to switch (ExpectedAmountQueries, TariffAssembler plotCount, catalogs); patterns: TariffVersionUsage port, permissions re-seed (V10 full seed_base_roles + targeted inserts), PlotCatalog (no street), OwnedPlotsSource, veto SPI (ADR 0011, OwnershipArchiveGuard), ChargeCalculator style, imports sheets + vocabulary, DemoTariffData clear/restore; web: /plots has no permission (electrician would see Plots), Settings page has no sections, PlotPage/AccountPage panels, DataTable without phone cards. Verdict: **new; builds on 006, 007, 008, 009; prepares 011**.

**Scope:** sections 2–10 of the feature doc. Out: section 11.

**Verification:** unit tests for every row of §3 and the validation rules; Testcontainers (meters CRUD/replace/remove/delete, tree and parent history, readings entry/approve/reject/supersede/correct/delete, round list, range consumption, member visibility, permissions allowed/denied per endpoint, audit, isolation per table, import sheet uk/en, plot delete/archive/kind guards, tariffs meter presence); web unit tests; e2e (electrician round with default and changed date, chairman approves a member reading, member sees only own meter, language purity covers the new screens); `make check`, `make e2e`, screenshots.

**Risks:** size (four screens, tree, import sheet); interpolation and replacement maths; plot delete/archive guards touching 006/007 behaviour; permission count ripple (11 → 15); menu/landing rules for the electrician; demo data clear/restore in tests; phone-width round screen.

## Phase 1 — decisions

- AUTO: no blocking question (rule: questions=blocking; no prior work, tooling present, clean tree, requirement detailed with worked examples).
- AUTO: new module `app.nadel.meters` (depends on plots, identity, audit, problem); `MeterValue` (scale ≤ 3, never rounded, strings in JSON) lives in the meters module root, not in `money` (it is a quantity, not money) (rule: CLAUDE.md modules; money README scope).
- AUTO: tariffs learns meter presence through a public `meters` read API (tariffs → meters dependency); meters never depends on tariffs (rule: no cycles; a tariff "applies to plots with a meter" is a tariffs concern).
- AUTO: plots gets a veto SPI for delete, archive and PLOT→COMMON when a meter exists (ADR 0011 pattern), implemented by meters (rule: 2.1 "common land never has a PLOT meter"; a meter with readings is never deleted).
- AUTO: `PlotCatalog` gains street and natural street-and-number order for the round screen (rule: §8.2 order).
- AUTO: the round list includes every active meter: plot meters in street and number order, then non-plot meters by name; filters resource, street, "без показу з" (default 60 days off unless set) (rule: §4 "every active meter").
- AUTO: the meter's initial reading is the baseline for the first interval and is not a reading row; a meter can be deleted while it has no reading rows (rule: §2.1, §2.5).
- AUTO: unusual-consumption warning = new interval's daily average > 3 × the daily average over the approved intervals of the last 180 days before the new reading; no warning without at least one earlier interval (rule: §2.4; conservative).
- AUTO: a member's PENDING reading never supersedes; on approval it supersedes an APPROVED reading of the same date (rule: §2.4 one approved reading per date).
- AUTO: a menu item "Ділянки" is hidden for a user who has neither PLOTS_VIEW nor owned plots but has READINGS_ENTER or METERS_VIEW; such a user lands on Покази (or Лічильники) (rule: §8.2 "only this screen and the register").
- AUTO: the tree screen is `/settings/meter-tree` under Налаштування, gated by METERS_MANAGE; the Settings menu item shows with SETTINGS_MANAGE or METERS_MANAGE and the settings page lists the sections the user may use (rule: §8.4; settings page has no sections yet).
- AUTO: import sheet `Лічильники`/`Meters`: an active meter of the same resource and serial on the plot → unchanged; a different active meter → error (replacement is done in the UI); only plot meters (rule: §7; 008 idempotent re-import).
- AUTO: the Keycloak realm is unchanged; the electrician keeps demo member Ткаченко Сергій Іванович and the custom role Електрик gets METERS_VIEW and READINGS_ENTER in demo data with a new Ukrainian description (rule: §6, §10).

## Phase 2 — branch and specs

- Spec folder and branch `010-meters-and-readings` from main @ 063af9f (= origin/main). Baseline `make check` started in a detached worktree.
- functional-spec.md written (Creation Mode, no markers; 10 requirement groups).
- AUTO: meter-based tariffs show "немає лічильника" for a plot without an active meter of that resource and do not count it in the tariff's plots (rule: §4 "say whether the plot has an active meter").
- AUTO: messages for refusals chosen in the spec (e.g. "На ділянці вже є активний лічильник цього ресурсу", "Спершу зніміть лічильник ділянки") (rule: Problem codes need catalog texts).
- technical-considerations.md written (inputs: spring-backend, postgres-database, react-frontend).
- AUTO: `MeterValue` + exact `Quantity` + `ConsumptionCalculator` in the meters root; display values of interpolated readings rounded once HALF_UP to 3 decimals; ADR 0014 (rule: money-rules rounding is for money only; readings are not money).
- AUTO: `ReadingUsage` is range-based (`meterId, from, to`) (rule: 011 charges by date range; interpolated values change when a reading is inserted).
- AUTO: reading correction = a new row superseding the old (history kept); delete = hard delete, audited (rule: money-rules 6.2 history; spec 2.4).
- AUTO: batch entry is per row, each in its own transaction (rule: the round saves row by row).
- AUTO: `GET /readings?status=PENDING` added for the approvals screen (rule: §8.3 needs a list; missing in the feature doc API).
- AUTO: a non-plot meter's replacement takes over its children; meter_parent as explicit ranges with an exclusion constraint (V8 pattern) (rule: the tree must not break on replacement).
- AUTO: demo dates relative to CURRENT_DATE (rule: the 60-day "без показу" case must hold whenever the demo is loaded).
- tasks.md written: 8 slices (model + permissions + calculation; register; readings and round; replacement and member flow; tree; tariffs + import; demo/docs/e2e/coverage; Feature Testing & Regression by testing-expert). No staffing gaps. No `not-user-reviewed` marker.
