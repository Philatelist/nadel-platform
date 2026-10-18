# Functional Specification: UI Preferences

- **Topic:** A light/dark theme choice in the header, the cooperative's name as the header title, and base role descriptions shown in the user's language.
- **Status:** Completed
- **Author:** Alex Usov
- **Source:** `docs/features/005-ui-preferences.md` (owner's feedback after reviewing 003 and 004)

---

## 1. Overview and Rationale (The "Why")

After seeing the redesigned app the owner asked for three things:

- **A theme choice.** Today the app is dark only when the device is in dark mode. Some people want the dark theme in
  the evening on a light-mode device, or the light theme in bright sun on a dark-mode phone.
- **The cooperative first.** People think of "our cooperative's site", not of the product. The cooperative's name
  should be the main title of every screen, as in the design reference; the product name stays visible but secondary.
- **Role descriptions in the user's language.** The three base roles show English descriptions ("Full access", "Own
  data only") even in the Ukrainian interface.

**Success looks like:** a person picks "Dark" once and the app opens dark on every later visit in that browser without
a white flash; every screen's title and the browser tab show the cooperative's name; the Roles page reads entirely in
Ukrainian.

---

## 2. Functional Requirements (The "What")

### 2.1 Theme choice

- The header has a theme choice with three options: "Системна" / "System" (the default, follows the device), "Світла" /
  "Light" and "Темна" / "Dark". It looks and works like the language switch: three buttons, the current one
  highlighted, all reachable with the keyboard with a visible focus outline, and screen readers announce which one is
  selected.
- On phone-width screens (under 768 px) the choice sits inside the menu that opens with the "Menu" button. A person
  without a menu (an ordinary member) finds it in the header next to the language switch.
- The choice is remembered in this browser. If the browser does not allow the app to remember it (for example a
  private window that blocks it), the app uses "System" and shows no error.
- When the app opens, it is already in the chosen theme: a person who chose "Dark" never sees a white flash first. The
  sign-in step and full-page messages use the same theme.
  - **Acceptance Criteria:**
    - [x] Given a device in light mode, when the person chooses "Dark", then the whole app turns dark immediately and the "Dark" button shows as selected. _Evidence: e2e `ui-preferences.spec.ts` §2.1 #1; `theme.unit.test.tsx`; see docs/screenshots/005-ui-preferences-header-dark.png._
    - [x] Given the person chose "Dark", when they reload the page or come back later in the same browser, then the app opens dark from the first moment, with no white flash. _Evidence: e2e `ui-preferences.spec.ts` §2.1 #2 (`data-theme` set before `<body>` exists, MutationObserver); `theme.test.ts` (inline script colours)._
    - [x] When the person chooses "System", then the app follows the device's light or dark setting again. _Evidence: e2e `ui-preferences.spec.ts` §2.1 #3 (follows emulated device scheme)._
    - [x] Given the browser does not allow the app to remember settings, when the person opens the app and changes the theme, then the app works without any error message and starts in "System" on the next visit. _Evidence: e2e `ui-preferences.spec.ts` §2.1 #4 (app storage keys blocked; no page errors; System next visit) and `theme.unit.test.tsx`. Note: blocking all browser storage also breaks the sign-in library (pre-existing, outside this feature)._
    - [x] Given a 390 px wide screen, when the chairman opens the menu, then the theme choice is inside it; when an ordinary member opens the app at that width, then the theme choice is in the header. _Evidence: e2e `ui-preferences.spec.ts` §2.1 #5 (chairman: in the menu; member: in the header); `Layout.test.tsx`; see docs/screenshots/005-ui-preferences-phone-menu-theme.png._
    - [x] When the person moves through the header with the Tab key, then each theme button receives focus with a visible outline and a screen reader announces whether it is selected. _Evidence: e2e `ui-preferences-acceptance.spec.ts` (Tab through System/Light/Dark with visible outline, pressed state exposed) and e2e `ui-preferences.spec.ts` §2.1 #6._

### 2.2 The cooperative's name as the title

- The header's main title is the cooperative's name in the title font, linking to the home page. The product name
  "Наділ" / "Nadel" stays as a small secondary label next to it.
- The browser tab shows "<page title> — <cooperative name>"; screens without their own title show just the cooperative
  name.
- Long cooperative names are shortened with "…" and the full name appears when the person points at it; on a phone the
  name stays visible next to the "Menu" button.
- When there is no cooperative to show (for example on the sign-in step or a full-page message for someone outside any
  cooperative), the product name is the title.
  - **Acceptance Criteria:**
    - [x] When the demo chairman signs in, then the header title reads "Demo Garden Cooperative", it links to the home page, and "Наділ" appears as a smaller secondary label. _Evidence: e2e `ui-preferences.spec.ts` §2.2 #1 (title link, "Наділ" label, desktop order); `Layout.test.tsx`; see docs/screenshots/005-ui-preferences-header-light.png._
    - [x] When the chairman opens the Members screen, then the browser tab reads "Члени кооперативу — Demo Garden Cooperative". _Evidence: e2e `ui-preferences.spec.ts` §2.2 #2; `useDocumentTitle.test.tsx`; tab title observed "Члени кооперативу — Demo Garden Cooperative"._
    - [x] Given a cooperative with a name longer than the header space, when the header is shown, then the name ends with "…" and pointing at it shows the full name. _Evidence: e2e `ui-preferences-acceptance.spec.ts` long-name specs (ellipsis, `title` attribute, desktop and 390 px next to Menu); see docs/screenshots/005-ui-preferences-phone-header.png. The browser tooltip itself is the browser's own rendering of the title._
    - [x] When a person who belongs to no cooperative signs in, then the page title shows the product name. _Evidence: e2e `ui-preferences-acceptance.spec.ts` operator spec (title "No cooperative — Nadel"); `Layout.test.tsx` and `useDocumentTitle.test.tsx` fallbacks._

### 2.3 Base role descriptions in the user's language

- Same rule as names in 004: a base role whose description was never changed shows its standard description in the
  person's language: Chairman "Повний доступ" / "Full access"; Treasurer "Перегляд членів, ділянок і журналу змін" /
  "Read access to members, plots and audit"; Member "Лише власні дані" / "Own data only". Once the chairman changes it,
  the new text is shown in every language.
- In the role form, a changed base-role description can be returned to the standard one with "Restore standard
  description" / "Повернути стандартний опис". Saving a role without touching its standard description does not count
  as changing it.
- Changing or restoring a description is recorded in the audit log, with the old and new text as the reader would see
  them.
- Only people allowed to manage roles can change or restore descriptions (unchanged from 002).
  - **Acceptance Criteria:**
    - [x] When a person using Ukrainian opens the Roles page, then the base roles show "Повний доступ", "Перегляд членів, ділянок і журналу змін" and "Лише власні дані"; in English they show "Full access", "Read access to members, plots and audit" and "Own data only". _Evidence: e2e `ui-preferences.spec.ts` §2.3 #1 (uk) and e2e `ui-preferences-acceptance.spec.ts` (en); `roleName.test.ts`; see docs/screenshots/005-ui-preferences-roles-descriptions-dark.png._
    - [x] Given the chairman changes the Treasurer description to "Веде касу", when anyone views the Roles page in either language, then it shows "Веде касу", and a "Restore standard description" button is offered in the Treasurer form. _Evidence: e2e `ui-preferences.spec.ts` §2.3 #2; `RolesTests`; `RoleDescriptionsAcceptanceTests`._
    - [x] When the chairman presses "Restore standard description", then the description returns to the standard text in each language and the button disappears. _Evidence: e2e `ui-preferences.spec.ts` §2.3 #3; `RolesTests`; `RoleDescriptionsAcceptanceTests`; `roleRestore.test.ts`._
    - [x] Given the chairman only changes the Treasurer's permissions in Ukrainian, when they save, then the description still follows the language (no restore button appears). _Evidence: e2e `ui-preferences.spec.ts` §2.3 #4; `roleDescriptionSubmit.test.ts`; `RolesTests` permission-only save._
    - [x] When the chairman, reading the audit log in Ukrainian, opens the entry for changing the Treasurer description, then it shows the change from "Перегляд членів, ділянок і журналу змін" to "Веде касу". _Evidence: e2e `ui-preferences.spec.ts` §2.3 #5 audit row "Перегляд членів, ділянок і журналу змін → Веде касу"; `roleName.test.ts` (`auditRoleValue` by field)._
    - [x] When the treasurer, who may not manage roles, tries to change a description, then the change is refused and the description stays the same. _Evidence: `RoleDescriptionsAcceptanceTests` (403 on change and restore, description unchanged); `RolesTests`; the treasurer has no Roles page in the UI._

### 2.4 Colours stay readable

- Both themes keep the readability rule from 003 (text contrast at least 4.5 : 1, 3 : 1 for large text).
  - **Acceptance Criteria:**
    - [x] When the automated contrast check runs, then every text and background pair passes in both themes. _Evidence: `packages/ui-tokens/src/contrast.test.ts` (both themes, green in `make check`); `theme.test.ts` (both dark blocks equal the tokens)._

---

## 3. Scope and Boundaries

### In-Scope

- Theme choice per browser with no flash on load.
- Cooperative name as header title and in the browser tab; product name as a secondary label.
- Base role descriptions in the user's language, restore, audit.

### Out-of-Scope

- Remembering the theme on the server or across devices.
- The mobile app's theme (it follows the device until mobile screens are built).
- Renaming the product.

---

## Assumptions & Decisions

- No question was asked before the run (pipeline autonomy `minimal`; nothing was blocking).
- An ordinary member has no menu, so on phones their theme choice stays in the header.
- Name and description each have their own restore button; the name restore from 004 is unchanged.
- Role summaries returned inside other screens (header roles, member pages) carry no description, so only the Roles
  page and the role form are affected.
- Pages without their own title show just the cooperative name in the browser tab.
- The audit log shows a standard description in the reader's language, like standard names in 004.

---

## Change Log

- (none yet)
