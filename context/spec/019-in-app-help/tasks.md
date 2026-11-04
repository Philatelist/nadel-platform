# Tasks: Help inside the application — per screen, per role, public (019)

Rules for every slice: no backend, API or database change; every screen text through the i18n catalogs
(`packages/shared/src/i18n/{uk,en}.ts`, Ukrainian default, both catalogs); design system 003 components and tokens only;
named exports, PascalCase component files; exact dependency pins (only `markdown-it`, `@types/markdown-it`, `yaml`,
`minisearch` are new, TC 2.2); no edits to generated code, `.github/`, `CLAUDE.md` or `.claude/`; Conventional Commits,
no AI attribution in commits. References "TC x.y" are sections of `technical-considerations.md`; "FS x.y" of
`functional-spec.md`.

**Testing in this run (the user's instruction):** no tests are run until every implementation slice is done. Builders
write the unit tests listed in their task but do **not** run `vitest`, `playwright`, `make check` or the backend suite;
they may run `pnpm --filter web typecheck` only when they need it to finish their own code. There is no per-slice
Verify task. The whole feature is verified once, in the last slice and in the pipeline's Phase 4.

## Page map (route → page id → section → permissions)

Every route of `App.tsx` (except `*` and the index redirect) is listed by exactly one page. `member` = owns a plot.

| Section | Page id | Routes | Permissions |
|---|---|---|---|
| getting-started | `getting-started` (topic: sign in, menu, language, theme, the help) | — | — |
| getting-started | `imports` | `/imports` | IMPORT_RUN |
| plots-members | `plots` | `/plots` | PLOTS_VIEW, member |
| plots-members | `plot-card` | `/plots/:id` | PLOTS_VIEW, member |
| plots-members | `plot-form` | `/plots/new`, `/plots/:id/edit` | PLOTS_EDIT |
| plots-members | `members` | `/members` | MEMBERS_VIEW |
| plots-members | `member-form` | `/members/new` | MEMBERS_EDIT |
| plots-members | `member-card` | `/members/:id` | MEMBERS_VIEW |
| tariffs-charges | `tariffs` | `/tariffs` | TARIFFS_VIEW |
| tariffs-charges | `tariff-form` | `/tariffs/new` | TARIFFS_MANAGE |
| tariffs-charges | `tariff-card` | `/tariffs/:id` | TARIFFS_VIEW |
| tariffs-charges | `charge-types` | `/charge-types` | TARIFFS_VIEW |
| tariffs-charges | `charges` | `/charges` | CHARGES_VIEW |
| tariffs-charges | `charges-month` | `/charges/months/:ym` | CHARGES_VIEW |
| tariffs-charges | `not-charged` | `/charges/not-charged` | CHARGES_VIEW |
| tariffs-charges | `losses` | `/charges/losses` | CHARGES_VIEW |
| tariffs-charges | `common-consumers` | `/charges/common` | CHARGES_VIEW |
| tariffs-charges | `recalculations` | `/charges/recalculations` | CHARGES_VIEW |
| tariffs-charges | `recalculation-new` | `/charges/recalculations/new` | RECALCULATION_RUN |
| tariffs-charges | `recalculation-card` | `/charges/recalculations/:id` | CHARGES_VIEW |
| meters | `meters` | `/meters` | METERS_VIEW |
| meters | `meter-card` | `/meters/:id` | METERS_VIEW |
| meters | `meter-tree` | `/settings/meter-tree` | METERS_VIEW, METERS_MANAGE |
| meters | `readings-round` | `/readings` | READINGS_ENTER |
| meters | `readings-approvals` | `/readings/approvals` | READINGS_APPROVE |
| payments-cash | `payments` | `/payments` | PAYMENTS_VIEW |
| payments-cash | `payments-enter` | `/payments/new` | PAYMENTS_ENTER |
| payments-cash | `payment-card` | `/payments/:id` | PAYMENTS_VIEW |
| payments-cash | `cashbook` | `/cashbook` | CASHBOOK_VIEW |
| payments-cash | `expenses` | `/cashbook/expenses` | CASHBOOK_VIEW |
| payments-cash | `expense-form` | `/cashbook/expenses/new` | CASHBOOK_ENTER |
| payments-cash | `expense-card` | `/cashbook/expenses/:id` | CASHBOOK_VIEW |
| payments-cash | `cash-count` | `/cashbook/counts/new`, `/cashbook/counts/:id` | CASHBOOK_VIEW |
| payments-cash | `bank-reconciliation` | `/cashbook/bank/:month` | CASHBOOK_VIEW |
| documents-delivery | `certificates` | `/documents/certificates` | CHARGES_VIEW |
| documents-delivery | `deliveries` | `/deliveries` | INVOICES_ISSUE |
| documents-delivery | `announcements` | `/announcements` | ANNOUNCEMENTS_SEND |
| documents-delivery | `announcement-form` | `/announcements/new` | ANNOUNCEMENTS_SEND |
| documents-delivery | `announcement-card` | `/announcements/:id` | ANNOUNCEMENTS_SEND |
| reports | `reports` | `/reports` | REPORTS_VIEW, CASHBOOK_VIEW |
| reports | `report` | `/reports/:code` | REPORTS_VIEW, CASHBOOK_VIEW |
| reports | `report-templates` | `/reports/templates` | REPORT_TEMPLATES_MANAGE |
| settings | `settings` | `/settings` | SETTINGS_MANAGE, METERS_MANAGE |
| settings | `roles` | `/roles` | ROLES_MANAGE |
| settings | `audit` | `/audit` | AUDIT_VIEW |
| how-it-calculates | topics from Part II of the board guide (sections 18–31); bill topics (tariffs and expected amount, readings and consumption, losses and common consumers, recalculation, payments and allocation, numbers of the invoice) with empty permissions; the rest with the permissions of the screens they explain | — | per topic |
| member | `account` (Мій кабінет, every signed-in user) | `/account` | — |
| member | member topics (own plot and bill, invoices and documents, Telegram, reconciliation act) | — | member |

---

- [x] **Slice 1: Help section with one real page, signed-in**
  - [x] Compiler and plugin (TC 2.1, 2.2): add `markdown-it`, `@types/markdown-it`, `yaml`, `minisearch` to `web` (exact pins); `web/help/types.ts`, `parse.ts` (front matter, Markdown → `HelpNode[]`, anchors, `shot:`, `help:` links, `{{ui:…}}` from the catalogs, `[!important]` callouts, plain text for search), `compile.ts`, `vitePlugin.ts` (virtual modules `virtual:help/uk|en`, dev watch of `docs/help/**`, loads TS through Vite `runnerImport`; fallback `tsx` per TC 2.2), type declarations for the virtual modules, wiring in `web/vite.config.ts`; make `web/tsconfig`/eslint cover `web/help/`. i18n group `help.*` and `help.sections.*` in both catalogs (TC 2.4). Routes `help` and `help/:id` inside `Layout`; `web/src/help/useHelpBundle.ts`, `HelpContent.tsx` (React rendering of nodes, no `dangerouslySetInnerHTML`), `HelpIndexPage.tsx` (contents by section), `HelpPageView.tsx` (sticky heading list, unknown id → not found), `useHelpMeta`; a «Довідка» link in the menu for everyone. `docs/help/README.md` (format for authors). Pilot content: `docs/help/{uk,en}/getting-started.md` (topic) and `payments-enter.md` (screen, full template, migrated from board guide §13). Write (do not run) vitest tests: `web/help/parse.test.ts`, `web/src/help/HelpPageView.test.tsx`. **[Agent: react-frontend]**

- [x] **Slice 2: «?» button, side panel and what each user sees**
  - [x] Move `NAV_ITEMS` to `web/src/nav.ts` (exported, `Layout` uses it); export the route tree as `appRoutes` from `App.tsx`; `web/src/help/appRoutePaths.ts` (full patterns, skips `*`, index, `/help*`). `helpVisibility.ts` (TC 2.4: anonymous all; empty permissions → all signed in; any permission; `member` ↔ `ownedPlots.length > 0`), `pageForPath.ts` (`matchPath`, most specific wins). Apply visibility to the contents, related links (hidden → plain text in `HelpContent`) and `HelpPageView` (hidden → «Цей розділ вам недоступний» + link to contents, nothing of the page rendered). `HelpButton` + `HelpPanel` in the `Layout` header (hidden when no visible page matches; side panel, focus trap, Esc, close; full screen below `md`; «Відкрити повністю» → `/help/<id>`). Write (do not run) vitest tests: `helpVisibility.test.ts` (chairman, member, electrician, operator, anonymous), `pageForPath.test.ts`, `appRoutePaths.test.ts`, `HelpPanel.test.tsx`, hidden-page and hidden-link cases in `HelpPageView.test.tsx`. **[Agent: react-frontend]**

- [x] **Slice 3: Search**
  - [x] `web/help/searchIndex.ts` (MiniSearch per locale, fields title/headings/text with boosts, stored id and title, serialized into the bundle at build time); client `web/src/help/useHelpSearch.ts` (`MiniSearch.loadJSON`, `prefix: true`, `fuzzy` 1 edit for terms longer than 3 letters, max 20, visibility filter, no network); search box and results on `HelpIndexPage` («Нічого не знайдено»). Write (do not run) `web/help/searchIndex.test.ts` (word from text, button name via `{{ui:}}`, one typo, English, nothing found). **[Agent: react-frontend]**

- [x] **Slice 4: Public help and search-engine readiness**
  - [x] `App.tsx`: for `/help` and `/help/*` without a signed-in user render `PublicHelpApp` (own router, `PublicHelpLayout` with app name, `LocaleSwitch`, `ThemeSwitch`, «Увійти» → `signinRedirect` with `returnTo`; after sign-in, return to that path); no `MeProvider`, no API client call. `web/index.html` gets `robots noindex`; `useHelpMeta` sets title, description, canonical, robots `index, follow` on help pages and restores `noindex` when leaving. Vite plugin emits `dist/help/index.html` and `dist/help/<id>/index.html` shells on build (TC 2.5, `VITE_SITE_URL`, default `http://localhost:5173`, documented in `web/.env.example` or README). Write (do not run) `web/src/help/PublicHelpApp.test.tsx` (renders without auth, no fetch) and a plugin shell test. **[Agent: react-frontend]**
  - [x] Stand: `infra/stand/Caddyfile` — `X-Robots-Tag "noindex, nofollow"` only when `{$NADEL_NOINDEX:true}` is `true`; `try_files {path} {path}/index.html /index.html`; document `NADEL_NOINDEX` in `infra/stand/compose.yml`/env example and `infra/stand/README.md`. **[Agent: devops-infra]**

- [x] **Slice 5: The completeness check**
  - [x] `web/help/check.ts` (TC 2.2 issue codes: `ROUTE_WITHOUT_PAGE`, `MENU_WITHOUT_PAGE`, `UNKNOWN_ROUTE`, `MISSING_SHOT`, `MISSING_TRANSLATION`, `MISSING_SECTION`, `UNKNOWN_UI_KEY`, `BAD_FRONT_MATTER`, `DUPLICATE_ID`, `UNKNOWN_LINK`, `SCREEN_WITHOUT_SHOT`, `UNKNOWN_PERMISSION`; messages name the page, locale, route, shot or key); `compile.ts` returns the issues; `vite build` fails on any issue, `vite dev` shows them as warnings. Write (do not run) `web/help/check.test.ts` (one test per issue code on synthetic fixtures, each asserting the named item) and `web/src/help/helpContent.test.ts` (the real `docs/help` content against `appRoutePaths(appRoutes)`, `NAV_ITEMS`, the catalogs and `web/public/help/img` → zero issues). **[Agent: react-frontend]**

- [x] **Slice 6: Screenshots and the board guide PDF from the help**
  - [x] `docs/guide/scripts/capture-screens.mjs` (TC 2.7): load help pages through the compiler (`runnerImport`), take every page's `shots` in both languages into `web/public/help/img/<lang>/<name>.png`; keep the owner-manual shots in `docs/guide/img/<lang>/`; move the existing step helpers so front-matter shots use the same vocabulary; `--page <id>`; any failed shot → exit 1 with `<page id>: <shot>: <reason>`; demo guard (sign in as chairman, `GET /api/me`, `tenant.id` must equal the demo tenant id from `R__demo_data.sql`, else exit 1 before any shot). `docs/guide/scripts/build-pdf.mjs` (TC 2.8): board guide uk/en from the help pages in contents order with title page and version (`git describe --tags --abbrev=0`, else last commit date), table of contents, chapters with screenshots from `web/public/help/img`, page numbers; owner manual and leaflet unchanged. Makefile: `help-shots` (`ID=` required) and updated `guide-shots`/`guide` descriptions; `docs/guide/README.md` rewritten for the new source. **[Agent: devops-infra]**

- [x] **Slice 7: Content — «Початок роботи», «Ділянки і члени», «Налаштування»**
  - [x] Ukrainian pages per the page map for these sections (`getting-started` finished, `imports`, `plots`, `plot-card` with the member view and owners, `plot-form`, `members`, `member-form`, `member-card`, `settings`, `roles`, `audit`), migrated from the board guide sections 1–7 and 11 (`docs/guide/uk/board-guide.html`) plus the screens themselves (`web/src/pages`, `web/src/components`), six-part template, `{{ui:…}}` for every quoted button/field/tab, worked examples with demo numbers (`backend/src/main/resources/db/demo/R__demo_data.sql`), `shots` lists (port the matching entries of the current `SHOTS` array; the screen plus the main dialog/result), traps from FS 2.1 where they belong (Gmail app password → `settings`), short sentences, no developer words, no other cooperatives or the platform. Then the faithful English translation of each page in `docs/help/en/`, same structure and shot names. Append each old section → new page line to `context/spec/019-in-app-help/migration-map.md`. **[Agent: general-purpose]**

- [ ] **Slice 8: Content — «Тарифи і нарахування», «Лічильники»**
  - [ ] Same rules as Slice 7 for `tariffs`, `tariff-form` (traps 1 and 2), `tariff-card`, `charge-types` (trap 1: activate «Вода»), `charges` (trap 7), `charges-month`, `not-charged`, `losses`, `common-consumers`, `recalculations`, `recalculation-new`, `recalculation-card`, `meters`, `meter-card`, `meter-tree`, `readings-round` (trap 3, HEIC), `readings-approvals`; source: board guide sections 8–10 and the related parts of 15. English translation. Extend `migration-map.md`. **[Agent: general-purpose]**

- [ ] **Slice 9: Content — «Платежі і каса», «Документи і розсилка», «Звіти»**
  - [ ] Same rules as Slice 7 for `payments`, `payments-enter` (finish the pilot; trap 6), `payment-card` («Сторнувати»), `cashbook`, `expenses`, `expense-form` (trap 3), `expense-card`, `cash-count`, `bank-reconciliation`, `certificates`, `deliveries`, `announcements`, `announcement-form`, `announcement-card`, `reports` (trap 5), `report`, `report-templates`; source: board guide sections 13–17. English translation. Extend `migration-map.md`. **[Agent: general-purpose]**

- [ ] **Slice 10: Content — «Як система рахує», «Член кооперативу»; retire the HTML board guide**
  - [ ] Topic pages of «Як система рахує» from board guide Part II (sections 18–31; section 32 «Що з’явиться далі» is dropped and noted in the map) with all worked examples kept, permissions per the page map; member pages (`account` and the member topics) from section 12 and the member-facing parts of 13–16; English translation. Complete `migration-map.md` so that every `<h2>`/`<h3>` of the old guide maps to a page. Delete `docs/guide/{uk,en}/board-guide.html`; update the owner manual (`docs/guide/{uk,en}/owner-manual.html`: the help section, «?», public help, `NADEL_NOINDEX`, `make help-shots`), root `README.md` and any link to the old HTML. **[Agent: general-purpose]**

- [ ] **Slice 11: Feature Testing & Regression**

  > Verifies the whole feature end-to-end against functional-spec.md, run after all implementation slices are complete.
  - [ ] Read functional-spec.md acceptance criteria in full. Generate acceptance-level tests that verify the entire feature as a whole — not individual slices. Cover applicable layers (unit for pure logic, integration for service interactions, e2e for user flows) based on the project's testing stack. Write tests with RED validation (must fail before implementation is confirmed done). Annotate each test with `@spec: 019-in-app-help` and `@regression` if suitable for long-term regression. Include `web/e2e/help.spec.ts`: the member opens «?» on «Мій кабінет» (`@smoke`); the treasurer searches «сторнувати платіж» and opens the page; an anonymous visitor reads `/help/payments-enter` with no `/api/` request; the member opens a hidden page by link; help pages carry title, description and canonical; the electrician's contents. **[Agent: testing-expert]**
  - [ ] Run all generated tests. All must pass. Fix any failures before proceeding. **[Agent: testing-expert]**
