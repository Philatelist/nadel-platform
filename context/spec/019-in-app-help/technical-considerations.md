# Technical Specification: Help inside the application — per screen, per role, public

- **Functional Specification:** [functional-spec.md](functional-spec.md)
- **Status:** Draft
- **Author(s):** Alex Usov

---

## 1. High-Level Technical Approach

The help is static content compiled at build time. No backend, API contract or database change.

- **Source:** one Markdown file per page and language under `docs/help/<lang>/<id>.md`, with YAML front matter.
- **Compiler** (`web/help/`, TypeScript, runs in Node at build and test time): reads the pages, validates them, resolves
  `{{ui:key.path}}` placeholders from the i18n catalogs of `@nadel/shared`, compiles Markdown into a small JSON node tree,
  and builds a MiniSearch index per language.
- **Vite plugin** (`web/help/vitePlugin.ts`): exposes the compiled content as virtual modules `virtual:help/uk` and
  `virtual:help/en` (code-split, loaded only when the help is opened), and at `vite build` emits pre-rendered HTML shells
  `dist/help/index.html` and `dist/help/<id>/index.html` with title, description, canonical URL, Open Graph image and
  `robots: index, follow`. The application's own `index.html` gets `noindex`.
- **Web client** (`web/src/help/`): the «?» button and side panel in `Layout`, the routes `/help` and `/help/:id` inside the
  signed-in app, and the same routes outside the sign-in gate for anonymous visitors (public shell with «Увійти»).
- **Completeness check:** a pure function `checkHelp(...)` in the compiler plus vitest tests (one per failure case on
  synthetic fixtures, one on the real content) so `make check` fails when the help drifts.
- **Screenshots and PDF:** `docs/guide/scripts/capture-screens.mjs` reads the shot lists from the help front matter;
  `docs/guide/scripts/build-pdf.mjs` builds the board guide from the help pages. The old `board-guide.html` files are
  removed once their content has moved.
- **Stand:** Caddy sends `X-Robots-Tag: noindex, nofollow` only when `NADEL_NOINDEX=true` (the stand's default).

---

## 2. Proposed Solution & Implementation Plan (The "How")

### 2.1 Content format (`docs/help/<lang>/<id>.md`)

Front matter:

| Field | Type | Meaning |
|---|---|---|
| `id` | slug | equals the file name; same set of ids in `uk` and `en` |
| `title` | string | page title (also `<title>` and `og:title`) |
| `description` | string | one sentence for search engines (`meta description`) |
| `kind` | `screen` \| `topic` | `screen` pages follow the six-part template and need ≥1 shot; `topic` pages (Part II «Як система рахує», «Початок роботи» topics) have free structure |
| `routes` | string[] | React Router patterns this page explains (`/payments/new`, `/payments/:id`); empty for topics |
| `permissions` | string[] | any of these `Permission` values, or `member` (owns a plot); empty = every signed-in user |
| `section` | enum | `getting-started`, `plots-members`, `tariffs-charges`, `meters`, `payments-cash`, `documents-delivery`, `reports`, `settings`, `how-it-calculates`, `member` (order of the contents) |
| `order` | int | order inside the section |
| `shots` | list | `{ name, user, path, viewport?: desktop\|phone, open?, steps?, element? }` — same step vocabulary as today's `SHOTS` array in `capture-screens.mjs` |

Language rules (in the language of the page):

- `screen` pages have exactly these `##` headings, in order — uk: «Для чого цей екран», «Хто бачить», «Елементи екрана»,
  «Приклад», «Знімки екрана», «Пов’язані сторінки»; en: "What the screen is for", "Who sees it", "Every element",
  "Worked example", "Screenshots", "Related pages". Extra `###` headings inside are free.
- Screenshot reference: `![alt](shot:<name>)`, `<name>` from the page's `shots`; resolves to `/help/img/<lang>/<name>.png`.
- Link to another page: `[text](help:<id>)` (optionally `help:<id>#anchor`).
- Interface names: `{{ui:payments.reverse}}` — a dot path into `Messages`; rendered as the catalog string of the page's
  language, styled as a UI label. An unknown path is a check error.
- «Важливо» box: a blockquote whose first line is `[!important]`; rendered as a callout titled from the catalog
  (`help.important`).

### 2.2 Compiler (`web/help/`)

| File | Responsibility |
|---|---|
| `web/help/types.ts` | `HelpPage`, `HelpShot`, `HelpNode` (JSON node tree: `p`, `h2`, `h3`, `ul`, `ol`, `li`, `table…`, `strong`, `em`, `code`, `text`, `img` {shot}, `link` {pageId, anchor}, `ui` {key, text}, `callout`), `HelpBundle` (pages + serialized search index) |
| `web/help/parse.ts` | read files, parse front matter (`yaml`), Markdown → tokens (`markdown-it`) → `HelpNode[]`; heading anchors (slugified); plain text extraction for search |
| `web/help/check.ts` | `checkHelp({ pages: Record<Locale, HelpPage[]>, routes: string[], navItems: string[], catalogs, shotExists })` → `HelpIssue[]` (`{ code, pageId?, locale?, detail }`) |
| `web/help/searchIndex.ts` | MiniSearch per locale: fields `title` (boost 3), `headings` (boost 2), `text`; store `id`, `title`; serialized with `JSON.stringify` |
| `web/help/compile.ts` | `compileHelp(rootDir)` → `{ uk: HelpBundle, en: HelpBundle, issues }` |
| `web/help/vitePlugin.ts` | virtual modules, watch `docs/help/**` in dev, emit SEO shells on build |

`checkHelp` codes (one test each): `ROUTE_WITHOUT_PAGE`, `MENU_WITHOUT_PAGE`, `UNKNOWN_ROUTE`, `MISSING_SHOT`,
`MISSING_TRANSLATION`, `MISSING_SECTION`, `UNKNOWN_UI_KEY`, plus structural ones (`BAD_FRONT_MATTER`, `DUPLICATE_ID`,
`UNKNOWN_LINK`, `SCREEN_WITHOUT_SHOT`, `UNKNOWN_PERMISSION`). Route coverage compares React Router patterns literally
after normalisation (leading `/`, no trailing `/`).

**Loading TypeScript from Node.** The plugin loads the compiler and the catalogs through Vite's `runnerImport` (Vite ≥ 6.1),
so workspace TypeScript (`@nadel/shared` exports `src/index.ts` with extension-less imports) needs no extra loader. The
capture and PDF scripts do the same (`import { runnerImport } from 'vite'` resolved from `web/node_modules`).
**Assumption:** if `runnerImport` proves unworkable, the builder may add `tsx` as a pinned dev dependency instead and
note it in the PR.

**New dependencies (web, exact pins, noted in the PR):** `markdown-it` + `@types/markdown-it` (Markdown parsing at build
time), `yaml` (front matter), `minisearch` (client-side fuzzy search, index serialisable at build time, ~7 kB gz).

### 2.3 Routes and route list for the check

- `web/src/App.tsx` exports its route tree as `appRoutes` (the `createBrowserRouter` argument), and a helper
  `web/src/help/appRoutePaths.ts` walks it into full path patterns, skipping `*` and the index redirect. `/help` and
  `/help/:id` are excluded from coverage.
- The menu list `NAV_ITEMS` moves from `Layout.tsx` to `web/src/nav.ts` (exported) so the check can read its `to` values.
- New routes inside `Layout` (signed-in): `help` → `HelpIndexPage`, `help/:id` → `HelpPageView`.
- Anonymous visitors: `App` checks `window.location.pathname` before the sign-in gate; for `/help` and `/help/*` without
  an authenticated user it renders `PublicHelpApp` (own small router, `PublicHelpLayout` with app name, `LocaleSwitch`,
  `ThemeSwitch` and «Увійти» → `auth.signinRedirect({ state: { returnTo } })`), and never mounts `MeProvider`, so no
  `/api` request happens.
- After sign-in from the public help the user lands back on the same help page (`returnTo`).

### 2.4 Client components (`web/src/help/`)

| Component / module | Responsibility |
|---|---|
| `useHelpBundle(locale)` | lazy `import('virtual:help/<locale>')`, cached (TanStack Query, `staleTime: Infinity`) |
| `helpVisibility.ts` | `visiblePageIds(pages, viewer)`; `viewer` = `{ kind: 'anonymous' }` or `{ permissions, ownsPlot }`. Anonymous sees all; empty `permissions` → visible; else any match, `member` ↔ `ownedPlots.length > 0` |
| `pageForPath(pages, pathname)` | `matchPath` over each page's `routes`; most specific (fewest params, longest) wins |
| `HelpContent` | renders `HelpNode[]` with React elements (no `dangerouslySetInnerHTML`); `link` to a hidden page → plain `<span>`; `img` → `/help/img/<locale>/<name>.png` with `loading="lazy"` |
| `HelpButton` + `HelpPanel` | «?» in the `Layout` header (hidden when no page matches); right side panel (`Modal`-like, focus trap, Esc closes), full-screen below `md`; «Відкрити повністю» → `/help/<id>` |
| `HelpIndexPage` | contents by section (catalog section names), search box with results (`MiniSearch.loadJSON`, `fuzzy: term.length > 3 ? 1 : 0`, `prefix: true`, max 20, filtered by visibility), «Нічого не знайдено» |
| `HelpPageView` | page with sticky heading list (`IntersectionObserver` highlight); hidden page → «Цей розділ вам недоступний» + link to contents, page content never rendered; unknown id → `NotFoundPage` |
| `useHelpMeta(page)` | sets `document.title`, `meta description`, canonical and robots on client navigation |

All visible strings come from a new `help` group in `packages/shared/src/i18n/{uk,en}.ts` (title «Довідка», `open` «?»
aria-label, `openFull` «Відкрити повністю», `close`, `search`, `noResults` «Нічого не знайдено», `hidden` «Цей розділ
вам недоступний», `toContents`, `important` «Важливо», `onThisPage`, `signIn` reuse `auth.signIn`, `sections.*`).
Design system 003 components and tokens only.

### 2.5 SEO

- `web/index.html` gets `<meta name="robots" content="noindex">`.
- On `vite build` the plugin writes, for `uk` (the default language): `dist/help/index.html` and
  `dist/help/<id>/index.html` — copies of the built `index.html` with `<title>`, `meta description`,
  `link rel=canonical` (`${VITE_SITE_URL}/help/<id>`), `og:title`, `og:description`, `og:image` (first shot, uk),
  `og:type=article`, `robots: index, follow`, `html lang`. `VITE_SITE_URL` defaults to `http://localhost:5173`.
- Caddy (stand) `try_files {path} {path}/index.html /index.html` serves those shells; the SPA then boots normally.
- **Assumption:** only the Ukrainian variant is pre-rendered (the public site's language); English is reached through the
  language switch, with no separate URL.

### 2.6 Stand flag

`infra/stand/Caddyfile`: the `X-Robots-Tag "noindex, nofollow"` header is applied through a matcher on
`{$NADEL_NOINDEX:true}`; `infra/stand/compose.yml` and `.env` example document `NADEL_NOINDEX`. Default `true` on the
stand, so nothing changes for it unless switched off.

### 2.7 Screenshots (`docs/guide/scripts/capture-screens.mjs`)

- Collect shots from the help front matter (both languages share the `uk` page's `shots` list; the `en` page must list
  the same names — check `MISSING_SHOT`), plus the owner-manual shots that stay in the script.
- Write help shots to `web/public/help/img/<lang>/<name>.png` (served by the app); owner-manual shots stay in
  `docs/guide/img/<lang>/`.
- **Assumption:** help shots are stored once, in `web/public/help/img/`, and the PDF build reads them from there instead
  of keeping a second copy under `docs/guide/img/` (deviation from the letter of the requirement, avoids duplicate
  binaries; the PDF is still built from the same images).
- Any failed shot → non-zero exit with `<page id>: <shot name>: <reason>` (no placeholder).
- Demo guard: before capturing, sign in as the chairman and read `GET /api/me`; if `tenant.id` is not the demo tenant id
  of `R__demo_data.sql`, exit with an error and take nothing.
- CLI: `--page <id>`; `make help-shots ID=<id>` runs it; `make guide-shots` runs all.

### 2.8 PDF (`docs/guide/scripts/build-pdf.mjs`)

- Board guide: compile help pages per language (via the compiler), render one HTML document with `guide.css`: title page
  with version (`git describe --tags --abbrev=0`, else the last commit date `git log -1 --format=%cs`), table of contents
  by section and page, each page as a chapter with its screenshots; page numbers via the existing footer template.
  Output `docs/guide/pdf/board-guide.{uk,en}.pdf` (same names as now).
- Owner manual and leaflet: unchanged HTML pipeline.
- Remove `docs/guide/{uk,en}/board-guide.html` after the content migration; `docs/guide/README.md` is rewritten for the
  new source.

### 2.9 Content

About 40 `screen` pages (every route of `App.tsx`, forms for new/edit sharing one page) and about 15 `topic` pages
(«Початок роботи»; «Як система рахує» from Part II sections 18–31), in `uk` and `en`. The content of the current board
guide (Parts I and II) is moved, not rewritten from scratch; the seven traps get `[!important]` boxes. Member pages:
«Мій кабінет» (account), plot card as a member, documents and invoices, Telegram link, reconciliation act.

Bill pages of «Як система рахує» with empty `permissions` (everyone signed in): tariffs and expected amount, readings and
consumption (electricity), losses and common consumers, recalculation, payments and allocation, numbers of the invoice.
Other Part II pages carry the permissions of the screens they explain.

`docs/help/README.md` documents the format for authors. The PR description gets: the page list with word counts and
shots, and the old-section → new-page checklist (`context/spec/019-in-app-help/migration-map.md`).

### 2.10 Documentation and rules

- `docs/help/README.md` (new), `docs/guide/README.md`, root `README.md`/`make help` (new `help-shots` target).
- Owner manual (`docs/guide/{uk,en}/owner-manual.html`): the help section, the «?» button, the stand flag, the shot
  commands.
- `CLAUDE.md` change (help page rule; guides rule points at `docs/help/`) is **proposed in the PR**, not committed
  (owner-only file).

---

## 3. Impact and Risk Analysis

- **System dependencies:** `App.tsx` and `Layout.tsx` (refactor: exported routes and nav list), i18n catalogs, Vite
  config, Caddy stand config, guide scripts, Makefile. No backend, API or DB change; `make gen` unaffected.
- **Risks and mitigations:**
  - *Bundle size* — content for two languages is large: virtual modules are per language and lazy; screenshots are
    static files with `loading="lazy"`.
  - *Public routes leaking data* — the public tree never mounts `MeProvider` or calls the API client; e2e checks the
    network of an anonymous visitor.
  - *Hidden page content in the DOM* — `HelpPageView` returns the «недоступний» view before rendering any node; a unit
    test asserts the text of a hidden page is absent.
  - *Check depends on screenshots* — the real-content check requires every shot to exist; shots are captured once the
    app runs with the feature (Phase 4). Until then the real-content test is expected red (tests are deferred to the
    end of this run by the user's instruction).
  - *Vite config loading TS through `runnerImport`* — fallback `tsx` (see 2.2).
  - *Content volume* — writing ~110 pages; split into content slices by section; English is a translation of the
    finished Ukrainian page.
  - *Typos in button quotes* — `{{ui:…}}` placeholders make renamed buttons fail the check.

---

## 4. Testing Strategy

By the user's instruction for this run, no tests are run until every slice is implemented; then everything is verified
once.

- **Unit (vitest, web):** `parse` (front matter, nodes, placeholders, callouts, links), `checkHelp` (one test per issue
  code on synthetic fixtures), real-content check (zero issues), `helpVisibility` (chairman, member, electrician,
  operator, anonymous), `pageForPath`, search (word from text, button name, one typo, English), `HelpPageView` hidden
  page, `HelpButton` panel.
- **e2e (Playwright, `web/e2e/help.spec.ts`):** member opens «?» on «Мій кабінет» (`@smoke`); treasurer searches
  «сторнувати платіж» and opens the page; anonymous visitor reads `/help/payments-enter` with no `/api/` request; member
  direct link to a hidden page; help pages carry title, description, canonical.
- **Scripts:** `make guide-shots`, `make guide` run once in verification with the demo app.
- **Final:** `make check`, the new e2e spec, and the smoke set.
