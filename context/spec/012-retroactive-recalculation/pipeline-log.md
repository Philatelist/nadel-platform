# Pipeline log — 012 Retroactive recalculation

- Status: running
- Phase: 3 (implement), Slice 1
- Branch: 012-retroactive-recalculation · Base: main @ 2e51e81
- Requirement: document docs/features/012-retroactive-recalculation.md
- Autonomy: minimal (.claude/feature-pipeline.yml)
- Publish: pr+checks · PR: — · Checks: —
- Review run: on (not started yet)

## Project facts (Phase 0)

- Base: `main`, local = origin/main = 2e51e81; no local-only commits. Working tree clean (no tracked changes).
- Pipeline tooling present on main: feature-pipeline skill + slice-review-loop, slice-reviewer agent, AWOS wrappers and core files for spec/tech/tasks/implement/verify.
- Build/tests: backend one class `cd backend && ./mvnw -B test -Dtest=<Class>`, module `-Dtest='app.nadel.<module>.**'`; full suite `make check` (contract, format, lint, backend verify with coverage floor, clients); e2e `make e2e` (own specs + `--grep @smoke` locally; full e2e via the `full-e2e` PR label).
- Owner rule (memory, delegation rules): builders never run the full backend suite inside a slice; full suite once in Phase 4.
- Specialists: spring-backend, postgres-database, react-frontend, testing-expert (testing agent), devops-infra, expo-mobile; slice-reviewer.
- UI: yes (web/). Browser tool: claude-in-chrome available.
- Safe local run mode: `make up` (local docker Postgres + Keycloak, dev profile, demo data; no external services). Demo users in CLAUDE.md, password `nadel`.
- Git rules: Conventional Commits; no AI attribution; stage by path; never stash/reset/force; agents never merge/approve, push only the spec branch.
- Protected files: CLAUDE.md, .claude/, .github/, .mcp.json; generated code not edited by hand.
- Publishing pre-flight: `gh auth status` OK (Philatelist), origin github.com/Philatelist/nadel-platform; CI workflow `.github/workflows/ci.yml` (Backend, Clients, SAST, Security, End-to-end).
- Next spec number: 012.
- Settings: autonomy minimal (questions blocking; reviewerEscalation auto; notGreen ask; gitUnsafe ask; notTestableLocally auto); publish pr+checks; reviewRun on; notify true.

## Phase 1 — requirements brief

- **Requirement:** docs/features/012-retroactive-recalculation.md. The board (chairman) corrects the past safely: back-dated, corrected or deleted tariff versions; corrected, deleted or back-dated readings; a manual recalculation. The system computes expected charges with today's data (011 calculators), compares them with the effective booked amounts, previews the plan, and books it atomically: reversal + new charge in open months, an ADJUSTMENT in the earliest open month for closed months. Users: chairman (book), treasurer (view), members (see adjustments on their own account).
- **Prior work:** git history: nothing on any branch, stash or worktree (only the requirement doc, PR #42). Code: only 011 hooks: `ChargeKind` comment "ADJUSTMENT reserved for 012", closed-month 409 texts pointing to 012, usage ports `TariffVersionUsage`/`ReadingUsage`, a test asserting ADJUSTMENT is refused by `charge_kind_ck` (LedgerTenantIsolationTests:279, must change). **Verdict: new, extends 011.**
- **Scope in:** everything in sections 2–9 of the requirement. **Out:** section 10 (payments/prepayment allocation, notifications, opening balances, dated history of area/scope, reopening months); mobile.
- **Acceptance criteria:** section 11 of the requirement, refined in the functional spec.
- **Approach sketch:** V19 migration (ADJUSTMENT/RECALCULATION, `adjusts_charge_id`, `recalculation`, `recalculation_line`, RLS, `RECALCULATION_RUN` seeded for chairman); a pure `RecalculationPlanner` in `ledger` fed by the 011 planners (`TariffMonthPlanning`, `ReadingChargePlanning`, `LossesPlanning`, `CommonConsumerPlanning`) with a "charged" predicate that ignores existing keys; booking through the 011 writers; digest/`PREVIEW_OUTDATED`; cause-driven blocks on tariff version and reading endpoints via the usage ports; plot account adjustments; web dialogs step 2, "Перерахунок" tab, hints; docs, guides, demo recalculation.
- **Verification:** planner unit tests (every section 3 row), Testcontainers booking/permission/isolation tests, client tests, e2e (`@smoke` used-version correction + adjustment on account; reading correction; electrician refused), browser check of UI criteria via `make up`.
- **Risks:** size (largest feature so far); reuse of 011 planners without duplicating them; lock design (011 has per-month/queue/run locks, no single per-cooperative lock); interval re-structuring on reading changes; closed-month run adjustments per plot.
- **Open questions (none blocking; answered automatically):**
  - AUTO: (Phase 1, rule `questions: blocking`) Lock: a recalculation takes the existing 011 locks it touches in a fixed order plus a new per-cooperative recalculation lock; month runs, losses and common consumer runs also take that per-cooperative lock briefly, so runs and recalculations exclude each other. Final shape decided in the tech doc.
  - AUTO: (rule `questions: blocking`) `context/product/product-definition.md` says recalculation is automatic and members are notified; the requirement (confirmed preview, notifications in phase 8) wins; `/awos:product` suggested as a follow-up, product docs not edited by the pipeline.
  - AUTO: (rule `questions: blocking`) The 009 hint "Нарахування за минулі періоди буде перераховано" becomes accurate with the cause-driven flow; kept, and the dialog's step 2 shows the actual preview.
  - AUTO: (rule `questions: blocking`) A cause-driven change that needs no recalculation (nothing used, or no amount changes) saves directly as today; a used one without the block keeps the 011 409.
  - AUTO: (rule `questions: blocking`) Mobile untouched (members see adjustments via the account API; mobile shows no account yet).
- Phase 1 end: no blocking questions; nothing asked.

## Phase 2 — branch and specs

- Spec folder `context/spec/012-retroactive-recalculation`, branch `012-retroactive-recalculation` from main @ 2e51e81.
- Baseline (`make -k check` at 2e51e81): backend 2,144 green, coverage met; clients 56/7/130/577 green; **format-check red** on the two board-guide HTML files from PR #44 → fixed on this branch, commit c53c6ac `style(docs): format the board guide audit chapter`. Recorded in review-log.md.
- Functional spec written (Creation Mode, no markers).
- Technical considerations written from Explore + spring-backend, postgres-database, react-frontend designs. Assumptions (also in the doc):
  - AUTO: (rule `questions: blocking`, tech step 5) cause-driven block on `POST /recalculations` with cause types instead of the tariff/reading endpoints (requirement §4); same user behaviour; existing endpoints keep the 409.
  - AUTO: preview = apply the change in a rolled-back transaction (no what-if override).
  - AUTO: recalculation reversals use source `RECALCULATION`; hand reversals stay `MANUAL_REVERSAL` and freeze their chain.
  - AUTO: adjustment key `ADJUSTMENT:{recalcId}:{originalId}`; new charges base key or `base#r{recalcId}`.
  - AUTO: used tariff versions deleted softly (`deleted_at`) — `charge_part` FK is RESTRICT and append-only (latent 011 bug).
  - AUTO: interval identity = meter + source + point dates (a corrected reading gets a new id).
  - AUTO: one per-cooperative advisory lock `ledger.charging:{tenant}` taken first by every charging path.
  - AUTO: manual scope ≤ 24 months, active charge types, chargeable plots.
  - AUTO: electrician: 409 READING_IN_USE from the reading endpoint, 403 from /recalculations; web hint without RECALCULATION_RUN.
- tasks.md written: 9 slices (adjustments + list; pure planner; manual tariff recalculation; intervals; runs; tariff version causes; reading causes; demo/docs/guides/e2e; Feature Testing & Regression with testing-expert). No staffing gaps. The `<!-- not-user-reviewed -->` marker was removed (pipeline rule; the plan is listed in the final report for review).
- Phase 2 end.
- Slice 1: 167f42e — verify green (backend 447, web 592, clients green) — review PASS (r1)
- Slice 2: 0ef7ec0 — verify green (40 tests) — review PASS (r1)
- Slice 3: 4e38655 (+ fix c6926e6) — verify green (backend 68 + builder 164, web 616) — review PASS (r2)
- Slice 4: bd66a08 — verify green (backend 112) — review r1 running
