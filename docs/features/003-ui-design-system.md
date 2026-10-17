# 003. UI design system

Cross-cutting feature, runs before the plots registry. Input for `/feature-pipeline`. Requires 002 (the screens it
restyles). No backend, API or database changes.

## 1. Goal

The web app gets the look of the approved product prototype: one set of design tokens, a small component kit and an
app shell, and the screens that already exist (002) are restyled with them. Every later feature builds its screens
from this kit, so new screens match the prototype without extra work.

The chosen direction is "calm registry": side menu, tables, green accent, serif page titles. It was picked over a
card-based and a large-type variant. Two rules from the large-type variant still apply: a status is never shown by color
alone (always a text label next to it), and member-facing screens put the amount due and the main action first.

The visual reference is `docs/design/screens/*.png` (screenshots of the prototype). Screens there that do not exist
yet (plots, ownership, payments, map, member balance, invoice, operator console) show the style that later features
follow; this feature does not build them.

## Context for the pipeline

Read together with `CLAUDE.md`, `context/product/product-definition.md`, `context/product/architecture.md` and
ADR 0005. Look at every screenshot in `docs/design/screens/` before planning.

Feature sequence: `002 Members and access control` → `003 UI design system` → `004 Role names` → `005 UI preferences` → `006 Plots registry` →
`007 Ownership` → `008 Excel import`.

## 2. Design tokens (`packages/ui-tokens`)

Replace the current palette and fonts. Tokens stay the single source: `web/src/index.css` (`@theme`) and the mobile
app read the same values. Names are semantic; a component never uses a raw hex value.

### 2.1 Colors, light theme

| Token | Value | Use |
|---|---|---|
| `background` | `#F3F5F0` | page ground |
| `surface` | `#FFFFFF` | panels, header, side menu, inputs |
| `line` | `#D9DFD4` | panel and table header borders |
| `lineSubtle` | `#EDF0EA` | table row dividers |
| `lineStrong` | `#B9C3B3` | input and secondary button borders |
| `ink` | `#1C2A22` | main text |
| `inkMuted` | `#46564C` | labels, table headers |
| `inkSubtle` | `#5B6A60` | hints, secondary text (still 4.5:1 on `background`) |
| `accent` | `#245A41` | primary buttons, links, active menu text |
| `accentHover` | `#143826` | hover and pressed state of `accent` |
| `accentSoft` | `#E3EEE6` | active menu item, selected pill |
| `onAccent` | `#FFFFFF` | text on `accent` |
| `danger` | `#A3301B` | debt amounts, destructive actions, errors |
| `dangerSoft` | `#FBE9E5` | error notices |
| `warningSoft` / `warningLine` | `#FFF6E5` / `#EBCB98` | notices that need attention |
| `successSoft` / `successInk` | `#DCEFE3` / `#14532D` | success messages, "active" badges |
| `infoSoft` / `infoInk` | `#E1E9F6` / `#1E3A6E` | neutral information badges |

Plot status colors keep their meaning (money-rules section 9) and are never used as the UI accent:
`GREEN #2E8B57`, `YELLOW #E0B23A`, `RED #C2412B`, `GREY #9AA39A`. Text on `GREEN` and `RED` is white, on `YELLOW` and
`GREY` it is `ink`.

### 2.2 Dark theme

Keep the existing dark theme (it follows the system setting) and derive it from the new palette: dark green-grey
ground, light ink, a lighter green accent. Every pair of text and background keeps a 4.5:1 contrast ratio (3:1 for text
of 24 px and more); add a unit test that checks the contrast of the token pairs used for text, in both themes.

### 2.3 Typography

| Role | Font | Size and weight |
|---|---|---|
| Page title (h1) | IBM Plex Serif | 30 px, 600 |
| Section title (h2) | IBM Plex Sans | 17 px, 600 |
| Body | IBM Plex Sans | 15 px on board screens, at least 16 px on member screens, 400 |
| Labels, table headers | IBM Plex Sans | 13–14 px, 600 |
| Hints | IBM Plex Sans | 13 px, 400 |
| Money and numbers | IBM Plex Mono | same size as the text around it, 500, right-aligned in tables |

All three families must include Cyrillic. Fonts are self-hosted from pinned `@fontsource` packages (`ibm-plex-sans`,
`ibm-plex-serif`, `ibm-plex-mono`), imported in the web entry point; remove the Google Fonts links from
`web/index.html` (no third-party requests on page load). Mobile loads the same families through `expo-font`.

### 2.4 Shape and spacing

- Spacing on a 4 px scale (`4, 8, 12, 16, 20, 24, 28, 32, 48`).
- Radius: controls 10 px, panels 12 px, badges and pills 999 px.
- Panels: `surface`, 1 px `line` border, 20 px padding, 14 px gap between children. No shadows, no gradients.
- Interactive targets are at least 44 px high.

## 3. Components (`web/src/components/ui/`)

Small, typed, no new UI library. Each one has a unit test (render, accessible name, disabled state where relevant).

| Component | Notes |
|---|---|
| `Button` | variants `primary` (accent fill) and `secondary` (surface, `lineStrong` border), `danger`; min height 44 px; visible focus ring |
| `TextField`, `SearchField`, `Select` | always a `<label>`; field error text under the input in `danger`; uses the existing `Field` logic |
| `Panel` | the white panel described above; optional title |
| `PageHeader` | h1 + optional status badge + actions on the right; wraps on narrow screens |
| `Badge` | tones `success`, `info`, `warning`, `neutral` |
| `DataTable` | header row, row dividers, numeric columns right-aligned in mono, horizontal scroll inside the panel on narrow screens, empty state row |
| `Money` | displays a server amount string (`"1250.50"`) as `1 250,50 ₴` in mono; negative values and debts styled by a prop; it formats only, never calculates |
| `StatusDot` | plot status color + text label for screen readers (color is never the only signal) |
| `Notice` | inline message in `warningSoft`, `successSoft` or `dangerSoft` with `role="status"` or `role="alert"` |
| `EmptyState`, `LoadingState`, `ErrorState` | used by `QueryState` so every screen gets the same three states |

`formatMoney` lives in `@nadel/shared` and is reused by mobile.

## 4. App shell (`Layout`)

- Header: product name (link to the home page), cooperative name, current user's name and role, locale switch, sign
  out.
- Side menu on wide screens: one button-like link per section the user may open (filtered by permissions, as today),
  active item in `accentSoft` with `accent` text.
- At widths under 768 px the side menu collapses behind a "Menu" button in the header (a real `<button>`,
  `aria-expanded`); content takes the full width. Tables scroll inside their panel; the page never scrolls sideways.
- Content column with a max width of about 1060 px, 24–28 px padding.

## 5. Screens restyled in this feature

Only presentation changes; behaviour, routes, permissions and API calls stay as they are.

| Screen | Reference |
|---|---|
| Members list with search and pagination | `board-members.png` |
| Member page | `board-member.png` |
| Member form (create and edit) | `board-member-form.png` |
| Roles and permissions | `board-roles.png` |
| Audit log | `board-audit.png` |
| My account | `member-account.png` |
| Not found, no access, sign-in and full-page messages | same tokens and components |

`MapPage` stays hidden and is not restyled (phase 6).

## 6. Mobile

Only tokens and fonts: `mobile/` reads colors, spacing and radius from `@nadel/ui-tokens` and loads the IBM Plex
fonts. No screens are built (member screens come in phase 5; reference `mobile-home.png`).

## 7. Out of scope

New screens or features; plot status in lists (006 and later); a component catalog tool such as Storybook; any change
to the API, backend or database.

## 8. Tests and quality

- Unit tests for every component and for `formatMoney` (`"0.00"`, `"1250.50"`, `"-350.00"`, `"1000000.00"`).
- Token contrast test (section 2.2).
- Existing E2E tests stay green; update selectors only where markup changed, prefer roles and labels.
- One E2E test at 390 px width: the menu button opens the menu, a members table scrolls inside its panel, no
  horizontal page scroll.
- `pnpm -r typecheck`, `pnpm -r lint` and `make check` green. No i18n strings outside the catalogs; new strings in
  `uk.ts` and `en.ts`.

## 9. Acceptance criteria

- [ ] `packages/ui-tokens` holds the palette, plot status colors, typography, spacing and radius from section 2; the
      web `@theme` and mobile use them; no raw hex values in components.
- [ ] IBM Plex Sans, Serif and Mono are self-hosted with Cyrillic; no request to Google Fonts.
- [ ] The component kit from section 3 exists with tests.
- [ ] The app shell matches section 4, including the collapsed menu under 768 px.
- [ ] Every screen in section 5 matches its reference screenshot in layout, colors and type (data may differ).
- [ ] Dark theme works and meets the contrast test.
- [ ] Keyboard: every action reachable with Tab, visible focus, menu toggle announces its state.
- [ ] The PR description proposes a `CLAUDE.md` rule for the owner to commit: new web screens are built from `web/src/components/ui` and follow
      `docs/design/screens`; no inline colors.
