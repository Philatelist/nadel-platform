# Technical Specification: UI Design System

- **Functional Specification:** [functional-spec.md](functional-spec.md) (source: `docs/features/003-ui-design-system.md`)
- **Status:** Draft
- **Author(s):** Alex Usov

---

## 1. High-Level Technical Approach

Client-only change. No API, backend or database change.

1. `@nadel/ui-tokens` is rewritten with the semantic palette (light and dark), plot status colors, typography,
   a 4 px spacing scale, radius and layout constants. It stays the single source of values.
2. The web app keeps hand-written CSS custom properties in `web/src/index.css` (`@theme` for light, the existing
   `prefers-color-scheme: dark` block for dark), guarded by a unit test that fails on any drift from the tokens.
3. IBM Plex Sans/Serif/Mono are self-hosted through pinned `@fontsource` packages; Google Fonts links are removed.
4. A small component kit in `web/src/components/ui/` replaces the ad-hoc styling; the `Layout` shell gets a side
   menu that collapses behind a "Menu" button under 768 px; the 002 screens are restyled with the kit.
5. TS unit tests are introduced (vitest), wired into `make test` and the CI `clients` job.
6. Mobile reads the new tokens and loads IBM Plex through `expo-font` with bundled font packages.

---

## 2. Proposed Solution & Implementation Plan (The "How")

### 2.1 Tokens (`packages/ui-tokens/src/index.ts`)

All exports are `as const` objects; `ThemeName` and `PlotStatusKey` types stay.

| Export | Shape |
|---|---|
| `palette` | `{ light, dark }`, identical keys: `background surface line lineSubtle lineStrong ink inkMuted inkSubtle accent accentHover accentSoft onAccent danger dangerSoft warningSoft warningLine warningInk successSoft successInk infoSoft infoInk` |
| `plotStatusColors` | `{ light, dark }` with `GREEN YELLOW RED GREY`; light values from the feature doc (`#2E8B57 #E0B23A #C2412B #9AA39A`) |
| `plotStatusOnColor` | text color per status per theme (white on GREEN/RED, `ink` on YELLOW/GREY) |
| `typography` | `family { serif, sans, mono }` (CSS family names), `size { h1:30, h2:17, body:15, bodyMember:16, label:14, hint:13 }`, `weight { regular:400, medium:500, semibold:600 }` |
| `spacing` | 4 px scale `{1:4, 2:8, 3:12, 4:16, 5:20, 6:24, 7:28, 8:32, 12:48}` |
| `radius` | `{ control:10, panel:12, pill:999 }` |
| `layout` | `{ contentMaxWidth:1060, minTarget:44, menuBreakpoint:768 }` |

- Light values are exactly the feature doc's table 2.1. `warningInk` is added (the doc has no warning text color;
  needed so warning text is covered by the contrast test). **Assumption.**
- Dark values are derived from the palette (dark green-grey ground `#0F1512`, surface `#17211B`, ink `#E6EDE8`,
  accent `#7FCB9C`, ...) and tuned until the contrast test passes. Dark plot status colors keep their meaning.
- Removed/renamed: `muted` → `inkMuted`/`inkSubtle`; `fonts` → `typography.family`; `radius.sm/md/lg` →
  `control/panel/pill`. Only `mobile/App.tsx` consumes them today.

### 2.2 Web theme and fonts

- `web/src/index.css`: `@theme` declares `--color-<kebab-token>` (e.g. `--color-line-subtle`,
  `--color-status-green`), `--font-serif/sans/mono`, `--radius-control/panel`, and the base text sizes; the dark
  block overrides the same color variables. No `var()` inside `@theme` values. Global `:focus-visible` ring stays.
- Sync test `web/src/theme.test.ts`: reads `index.css`, parses both blocks and asserts every token equals the TS
  value (case-insensitive), in both directions.
- Fonts: `@fontsource/ibm-plex-sans` (400/500/600), `@fontsource/ibm-plex-serif` (600), `@fontsource/ibm-plex-mono`
  (400/500), only `latin` and `cyrillic` subset CSS files, imported at the top of `web/src/main.tsx`. Exact versions
  pinned in `web/package.json` dependencies.
- `web/index.html`: Google Fonts `preconnect`/stylesheet links removed; `theme-color` metas set to the new
  `background` values.
- ESLint: a `no-restricted-syntax` rule forbids hex color literals in `web/src/components` and `web/src/pages`.

### 2.3 Unit test tooling

| Package | Runner | Tests |
|---|---|---|
| `web` | vitest + jsdom + `@testing-library/react`, `@testing-library/dom`, `@testing-library/jest-dom`, `@testing-library/user-event` | components, theme sync |
| `packages/shared` | vitest (node) | `formatMoney` (`"0.00"`, `"1250.50"`, `"-350.00"`, `"1000000.00"`), uk/en key parity |
| `packages/ui-tokens` | vitest (node) | WCAG contrast of text pairs in both themes, key parity light/dark, hex format |

- Web config lives in `web/vite.config.ts` (`test` block: `environment: 'jsdom'`, `setupFiles: ['./src/test/setup.ts']`,
  `include: ['src/**/*.test.{ts,tsx}']`); explicit imports, no globals. A helper `web/src/test/render.tsx` wraps
  components with router and i18n providers.
- Each package gets `"test": "vitest run"`; root `package.json` gets `"test": "pnpm -r test"`; the Makefile `test`
  target runs `pnpm -r test`; CI `clients` job gets a `pnpm -r test` step. All versions exact and checked against
  the peer range of vite 8.
- Contrast pairs (4.5:1): `ink/inkMuted/inkSubtle` on `background/surface/accentSoft`; `accent` on
  `surface/background/accentSoft`; `onAccent` on `accent` and `accentHover`; `danger` on `surface/background/dangerSoft`;
  `ink` and `warningInk` on `warningSoft`; `successInk` on `successSoft`; `infoInk` on `infoSoft`; status on-color on
  each status color (3:1, as the label is not the only signal and status chips use ≥ 13 px semibold — **Assumption**,
  raise to 4.5 if the values allow).

### 2.4 Component kit (`web/src/components/ui/`)

Named exports, one file plus a co-located `*.test.tsx` per component, an `index.ts` barrel.

| Component | Contract |
|---|---|
| `Button` | `variant?: 'primary' \| 'secondary' \| 'danger'` + button props, default `type="button"`, min height 44 px; `buttonClass(variant)` for links styled as buttons |
| `Field` | moved from `components/Field.tsx`: label, error with `role="alert"` linked via `aria-describedby` |
| `TextField`, `SearchField` (`type="search"`), `Select`, `TextArea` | `label`, `error?`, `hint?` + native props; wrap `Field` |
| `Panel` | `title?` (h2), children with 14 px gap, `surface` + `line` border, 20 px padding |
| `PageHeader` | `title` (serif h1), `badge?`, `description?`, `actions?`; wraps on narrow screens |
| `Badge` | `tone: 'success' \| 'info' \| 'warning' \| 'neutral'` |
| `DataTable<T>` | `columns {key, header, cell, numeric?}`, `rows`, `rowKey`, `caption` (visually hidden), `emptyMessage`; scroll wrapper `overflow-x-auto`; numeric cells right-aligned mono |
| `Money` | `amount: string`, `tone?: 'default' \| 'danger' \| 'auto'`; formats with `formatMoney` from `@nadel/shared`, never parses to a number |
| `StatusDot` | `status: PlotStatusKey`, `label`; dot `aria-hidden`, label always rendered |
| `Notice` | `tone: 'warning' \| 'success' \| 'danger'`; `role="alert"` for danger, `role="status"` otherwise |
| `EmptyState`, `LoadingState`, `ErrorState` | `message`; `ErrorState` has `onRetry` and a "Try again" button |

- `Banner` → `Notice`; `inputs.tsx` → `TextField/Select/TextArea`; `QueryState` (`QueryError`) → `ErrorState`;
  `QueryBoundary` stays and renders the three states. Old files are deleted once unused.

### 2.5 App shell (`web/src/components/Layout.tsx`)

- Header: product name link to `/`, `tenant.name`, the user's `fullName` (link to `/account`) and role names,
  `LocaleSwitch`, sign out, and a "Menu" button (`aria-expanded`, `aria-controls="app-menu"`, hidden from 768 px up)
  rendered only when the menu has items.
- Side menu: `<aside id="app-menu">` with `<nav aria-label={t.nav.main}>` and one `NavLink` per permission-filtered
  section (Members, Roles, Audit). **Assumption:** "My account" moves from the menu to the header (user name link),
  so an ordinary member has no menu sections and sees no side menu, as the functional spec requires.
- Under 768 px the aside is hidden unless open, and stacks under the header; a nav click or Escape closes it
  (Escape returns focus to the button). The content column is `max-w-[1060px]` with 24–28 px padding; `main` has
  `min-w-0` so tables scroll inside their panel.
- New i18n keys in `en.ts` and `uk.ts`: `nav.menu`, `nav.main`, table captions/headers where missing.

### 2.6 Screens

| Screen | Change |
|---|---|
| Members | `PageHeader` + "Add member" button link; `Panel` with `SearchField` and `DataTable` (name link, phone, email, channel, roles, status badge); `Pagination` restyled |
| Member | `PageHeader` with status badge and actions; `Panel`s with the `<dl>` details; `MemberRolesForm` in a `Panel`; `ConfirmDialog` restyled |
| Member form | `Panel` with fields in a two-column grid from `md`, primary/secondary buttons, `Notice` for errors |
| Roles | per-role `Panel` in the existing list with `Badge` and buttons. **Assumption:** the prototype's permission matrix is a different interaction; behaviour stays, so the current per-role structure is kept and restyled |
| Audit | `DataTable` with pagination |
| Account | two `Panel`s (my data, roles and permissions), roles as `Badge`s in a list, body ≥ 16 px |
| Not found, No access, Sign-in, full-page messages | centered `Panel` with serif h1 and a primary action |

`MapPage` is untouched.

### 2.7 Mobile

- Dependencies (exact): `expo-font` 58.0.8, `@expo-google-fonts/ibm-plex-sans`, `-serif`, `-mono` 0.4.1.
- `mobile/App.tsx`: `useFonts` with the used weights, render after load (system fonts on error); per-weight family
  names in a mobile-only `mobile/src/fonts.ts`; renamed tokens (`inkMuted`, `radius.panel/control`, new spacing keys);
  `minHeight` 44 on the button. No config plugin.
- `mobile/app.json` `adaptiveIcon.backgroundColor` → light `accent`.

### 2.8 E2E

- Update selectors that depend on changed markup: `header nav` → `aside nav` / `navigation` by name; member rows
  `listitem` → table rows (header row excluded); `a > span:first-child` → the row's link; `support/login.ts`
  waits for the header (sign-out) instead of the navigation landmark; avoid ambiguity with `Pagination`'s `<nav>`.
- New `web/e2e/responsive.spec.ts` at 390 × 844: menu toggle and `aria-expanded`, members table scrolls inside its
  panel, no horizontal page scroll, member without sections has no Menu button.

---

## 3. Impact and Risk Analysis

- **System Dependencies:** `/me` already returns `fullName`, `roles`, `permissions`, `tenant`; no contract change.
  The generated API client is untouched.
- **Risks & Mitigations:**
  - *Supply chain* — new packages (fontsource, vitest, jsdom, testing-library, expo fonts) must pass `pnpm audit`
    (high) and OSV-Scanner (any). Mitigation: pin exact current versions; run `pnpm audit --prod --audit-level high`
    locally; prefer versions without advisories; never add ignores.
  - *E2E breakage* from markup changes. Mitigation: role/label selectors; `make e2e` in slices 3–4.
  - *Dark values failing contrast*. Mitigation: the contrast test drives tuning.
  - *Token/CSS drift*. Mitigation: theme sync test.
  - *vitest ↔ vite 8 compatibility*. Mitigation: pick the vitest line whose peer range includes vite 8.
  - *CI change* (`ci.yml` gains a test step) — listed in the PR under changes that need attention.

---

## 4. Testing Strategy

- Unit (vitest): every kit component (render, accessible name, disabled where relevant, field error linkage),
  `formatMoney`, contrast, token shape, theme sync, i18n key parity.
- E2E (Playwright, `make e2e`): existing specs green with updated selectors; new 390 px spec.
- Static: `pnpm -r typecheck`, `pnpm -r lint`, `pnpm format:check`, `pnpm --filter web build`, `make check`.
- Visual: screens checked against `docs/design/screens/*.png` in the browser on `make up`; screenshots saved to
  `docs/screenshots/003-ui-design-system-*.png`.

## Assumptions (recorded, not confirmed)

- `warningInk` token added; status on-color contrast checked at 3:1.
- "My account" moves into the header; the menu holds permission-gated sections only.
- Roles screen keeps its current per-role structure (no permission matrix).
- Hand-written CSS + sync test instead of a CSS generator.
- Mobile fonts via `@expo-google-fonts/*` at runtime, no `expo-font` config plugin.
