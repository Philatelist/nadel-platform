# Functional Specification: UI Design System

- **Topic:** One consistent look for the Nadel web app (colors, fonts, spacing, a shared set of building blocks and a new page frame), applied to the screens that already exist, plus the same colors and fonts in the mobile app.
- **Status:** Implemented — pending manual check
- **Author:** Alex Usov
- **Source:** `docs/features/003-ui-design-system.md`; visual reference `docs/design/screens/*.png`

---

## 1. Overview and Rationale (The "Why")

The web app works, but it does not look like the approved product prototype. Each screen styles its own lists,
badges and notices, so screens drift apart, and every new feature would repeat that work. The board and the
members of a cooperative are often older people reading on a phone or a small laptop: they need calm screens,
large readable text, clear statuses that never rely on color alone, and the amount due and the main action first.

This feature gives the app the approved "calm registry" look: a side menu, tables, a green accent and serif page
titles. It defines one set of colors, fonts and spacing that the web and mobile apps share, a small set of
building blocks (buttons, fields, panels, tables, badges, notices, amounts, loading/empty/error states), and a
page frame with a menu that folds away on narrow screens. The screens built in the members and access feature are
redrawn with them. Behaviour, addresses, permissions and data stay exactly as they are.

**Success looks like:** every existing screen matches its reference picture in layout, colors and type; the app
is fully usable on a 390 px wide phone screen and with the keyboard alone; the dark theme is readable; and the
next feature builds its screens from the same building blocks without new styling work.

---

## 2. Functional Requirements (The "What")

### 2.1 Colors

- The app uses one palette: a light green-grey page ground, white panels with thin grey-green borders, dark green
  ink for text, a deep green accent for primary buttons, links and the active menu item, a red for debts,
  destructive actions and errors, and soft amber, green and blue tints for notices and badges. Plot status colors
  (green, yellow, red, grey) keep their meaning and are never used as the app accent. The exact values are the
  ones in section 2.1 of the source document.
  - **Acceptance Criteria:**
    - [x] When a signed-in board member opens the Members screen, then the page ground is light green-grey, the content sits on a white panel with a thin border, and the "Add member" button is filled deep green with white text, as in `board-members.png`. _Evidence: e2e `design-system.spec.ts` light palette; see docs/screenshots/003-ui-design-system-members.png._
    - [x] When any existing screen is opened, then every color on it comes from the shared palette, and the same palette is used by the mobile app. _Evidence: ESLint hex rule in `web/eslint.config.js`, `web/src/theme.test.ts` (CSS = tokens, no undeclared variables), mobile unit check in `designSystem.acceptance.test.ts`._

### 2.2 Dark theme

- When the device is set to a dark appearance, the app shows a dark green-grey ground, light text and a lighter
  green accent. Every text stays readable: ordinary text has at least a 4.5 : 1 contrast with its background
  (3 : 1 for text of 24 px and larger), in both themes.
  - **Acceptance Criteria:**
    - [x] When the device switches to dark appearance and the user opens the Members screen, then the page shows the dark palette and all text, buttons and badges remain readable. _Evidence: e2e dark appearance test; see docs/screenshots/003-ui-design-system-members-dark.png._
    - [x] When the automated contrast check runs over every text and background pair of the palette, then every pair meets the ratio above in both the light and the dark theme. _Evidence: `packages/ui-tokens/src/contrast.test.ts` (both themes, green)._

### 2.3 Fonts and text sizes

- Page titles use IBM Plex Serif (30 px, semi-bold); section titles, body, labels and hints use IBM Plex Sans
  (body 15 px on board screens, at least 16 px on member screens); amounts and numbers use IBM Plex Mono and are
  right-aligned in tables. All three fonts show Ukrainian (Cyrillic) letters. The fonts are served by Nadel
  itself: opening the app makes no request to an outside font service.
  - **Acceptance Criteria:**
    - [x] When the user opens any screen in Ukrainian, then the page title is in a serif font and the rest of the text in a sans-serif font, with all Cyrillic letters drawn by those fonts. _Evidence: e2e fonts test (computed families, `document.fonts.check` with Cyrillic); see docs/screenshots/003-ui-design-system-members.png._
    - [x] When the app is loaded in a browser with its network log open, then no request goes to Google Fonts or any other outside font service. _Evidence: e2e fonts test (no non-localhost font requests) and Playwright resource listing during verification: none._
    - [?] When the mobile app starts, then its text uses the same IBM Plex fonts. _Evidence: fonts load via `useFonts` and `expo export --platform android` bundled the six Plex TTFs; not run on a device or simulator._

### 2.4 Spacing and shape

- Spacing follows one 4 px step scale. Controls have 10 px rounded corners, panels 12 px, badges and pills are
  fully rounded. Panels have no shadows or gradients. Every button, link-button and field is at least 44 px high.
  - **Acceptance Criteria:**
    - [x] When the user looks at any button or input on an existing screen, then it is at least 44 px high and has rounded corners as above. _Evidence: e2e target-size tests: main controls on Members and Member form, header buttons incl. language switch, ≥ 44 px and 10 px corners._

### 2.5 Building blocks

- The app has one shared set of building blocks, used by every existing screen:
  - **Buttons** in three kinds: primary (filled green), secondary (white with a border) and danger (red), each with
    a clearly visible focus outline and a dimmed look when unavailable.
  - **Text fields, search fields and drop-down lists**, each always with a visible label; an error message appears
    in red right under the field.
  - **Panels** (white boxes, optional title), **page headers** (title, optional badge, actions on the right; the
    actions move under the title on narrow screens), **badges** (success, information, warning, neutral).
  - **Tables** with a header row, thin row dividers, numbers right-aligned in the number font, an "empty" row when
    there is nothing to show, and their own sideways scrolling on narrow screens.
  - **Amounts** shown as `1 250,50 ₴`; negative amounts and debts can be shown in red. Amounts always come from the
    server as they are; the app only formats them.
  - **Plot status marks**: a colored dot always paired with a text label, so the status is never shown by color
    alone.
  - **Notices** for attention, success and errors; and the same **loading, empty and error states** on every screen.
  - **Acceptance Criteria:**
    - [x] When the amount `1250.50` is displayed, then the user sees `1 250,50 ₴`; when `0.00`, `-350.00` or `1000000.00` is displayed, then the user sees `0,00 ₴`, `−350,00 ₴` and `1 000 000,00 ₴`. _Evidence: `Money.test.tsx`, `packages/shared/src/money.test.ts`._
    - [x] When a list has no entries, then the user sees the shared empty state; when it is loading, then they see the shared loading state; when loading fails, then they see the shared error state with a "Try again" button. _Evidence: `states.test.tsx`, `QueryBoundary`, e2e acceptance "Try again" flow._
    - [x] When a form field has an error, then the error text appears in red directly under that field and a screen reader announces it with the field. _Evidence: `Field.test.tsx`, `TextField.test.tsx` (aria-describedby, role=alert)._
    - [x] When a plot status mark is shown, then a text label is present next to or with the colored dot. _Evidence: `StatusDot.test.tsx`._

### 2.6 Page frame and menu

- Every signed-in screen has a header with the product name (a link to the home page), the cooperative's name,
  the signed-in person's name and roles, the language switch and the sign-out button.
- On screens 768 px wide and wider, a side menu lists one item per section the person may open (as today, only
  the sections their permissions allow); the current section is highlighted in soft green with green text.
- Below 768 px the side menu is hidden behind a "Menu" button in the header; the button tells assistive
  technology whether the menu is open, and the content uses the full width. Tables scroll sideways inside their
  panel; the page itself never scrolls sideways.
- A person who may open no sections (an ordinary member) sees no side menu and no "Menu" button.
- The content column is at most about 1060 px wide.
  - **Acceptance Criteria:**
    - [x] When the chairman signs in on a wide screen, then the header shows the product name "Наділ" as a link to the home page, the cooperative's name, the chairman's name and role, the language switch and "Sign out", and a side menu shows Members, Roles and Audit log with the current item highlighted. _Evidence: e2e "chairman: header shows…"; see docs/screenshots/003-ui-design-system-members.png._
    - [x] When an ordinary member signs in, then they see the header and their account page, and no side menu and no "Menu" button. _Evidence: e2e plain member test, `responsive.spec.ts`, `Layout.test.tsx`; see docs/screenshots/003-ui-design-system-account-member.png._
    - [x] Given a 390 px wide screen, when the chairman presses "Menu", then the menu opens and the button reports it as expanded; when they press it again, then the menu closes and the button reports it as collapsed. _Evidence: e2e "pressing Menu twice"; see docs/screenshots/003-ui-design-system-menu-open-390.png._
    - [x] Given a 390 px wide screen, when the chairman opens the Members screen, then the members table scrolls sideways inside its panel and the page has no sideways scroll. _Evidence: `responsive.spec.ts` (page scrollWidth ≤ clientWidth, table wrapper scrolls); see docs/screenshots/003-ui-design-system-members-390.png._

### 2.7 Restyled screens

- These screens are redrawn to match their reference pictures (data may differ): Members list with search and
  pages (`board-members.png`), Member page (`board-member.png`), Member form for adding and editing
  (`board-member-form.png`), Roles and permissions (`board-roles.png`), Audit log (`board-audit.png`), My account
  (`member-account.png`), and the Not found, No access, sign-in and full-page messages (same colors and blocks).
  Only the look changes: what each screen does, its address, who may open it and what data it shows stay the same.
  The hidden map screen is not restyled.
  - **Acceptance Criteria:**
    - [?] When the chairman opens each screen above, then its layout, colors and type match its reference picture (a serif page title, white panels, tables with header rows, green primary buttons). _Evidence: visual match is an owner judgement; screenshots docs/screenshots/003-ui-design-system-members.png, member.png, member-form.png, roles.png, audit.png, account-chairman.png, account-member.png, not-found.png._
    - [x] When the Members list is opened, then members are shown as rows of a table with column headers, and search and paging work as before. _Evidence: e2e acceptance and members specs (table rows, search, paging)._
    - [x] When any existing user flow (sign-in, member search, add and edit member, roles, audit log, account) is run, then it behaves exactly as before this feature. _Evidence: `make e2e` 29 passed (acceptance, members, responsive, design-system specs)._

### 2.8 Keyboard and screen reader use

- Every action can be reached with the Tab key, the focused element always has a visible outline, and the menu
  toggle announces whether it is open.
  - **Acceptance Criteria:**
    - [?] When the user moves through any restyled screen with Tab, then every link, button and field receives focus in reading order and shows a visible focus outline. _Evidence: automated on the Members screen (menu links, search, Add member, visible outline); other screens rely on the same global focus ring and were not tabbed through._

### 2.9 Mobile app

- The mobile app uses the same colors, spacing, corner rounding and IBM Plex fonts. No mobile screens are built.
  - **Acceptance Criteria:**
    - [?] When the mobile app's start screen opens, then it uses the shared palette and IBM Plex fonts. _Evidence: same as the mobile font criterion: code uses tokens and Plex fonts, bundle export verified; not rendered on a device._

### 2.10 Guidance for future screens

- The pull request for this feature proposes a rule for the project owner to add to the agents' instructions:
  new web screens are built from the shared building blocks and follow the reference pictures; no colors are
  written directly into screens.
  - **Acceptance Criteria:**
    - [?] When the owner opens the pull request, then its description contains this proposed rule. _Evidence: satisfied in the PR description at publish time (Phase 5)._

---

## 3. Scope and Boundaries

### In-Scope

- Shared palette (light and dark), fonts, spacing and shape for web and mobile.
- The shared building blocks listed in 2.5, with automated tests.
- The page frame with the side menu and the folding menu under 768 px.
- Restyling the existing screens listed in 2.7.
- Mobile: colors, spacing, corner rounding and fonts only.
- New on-screen texts in Ukrainian and English.

### Out-of-Scope

- New screens or features (plots, ownership, payments, map, member balance, invoice, operator console) — they
  only show the style later features follow.
- Plot status in lists (plots registry and later).
- A building-block catalog tool such as Storybook.
- Any change to what the server provides, stores or calculates.
- Elements that exist only in the prototype pictures: the "Готово" readiness badges, the "Увійти як" role
  switcher, the feature-status dots and their legend in the menu, and menu items for sections that do not exist
  yet.

---

## Assumptions & Decisions

- No question was asked before the run (pipeline autonomy `minimal`; nothing was blocking).
- Prototype-only elements listed in Out-of-Scope are not built: the source document restyles only existing screens.
- One page frame for everyone, as in section 4 of the source document. A person with no menu sections (an ordinary
  member) gets no side menu, so My account looks like `member-account.png` without the future-section tabs.
- The header shows the cooperative name, the person's name and their roles from what the app already knows about
  the signed-in person; nothing new is requested from the server.
- Negative amounts are shown with the typographic minus sign (`−350,00 ₴`), as the app already does.
- The building blocks and the contrast check are covered by automated tests that run with the project's checks.
- The mobile app bundles the fonts with the app, so it makes no request to an outside font service either.

---

## Change Log

- (none yet)
