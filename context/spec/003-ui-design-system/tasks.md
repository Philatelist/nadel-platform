# Tasks: 003 UI design system

Spec: [functional-spec.md](functional-spec.md) · Tech: [technical-considerations.md](technical-considerations.md)

Rules for every task: follow `CLAUDE.md` (English in code, UI text only via `packages/shared/src/i18n` in both `uk.ts`
and `en.ts`, named exports, exact dependency pins, no edits to generated code). Do not commit; report every file
created or changed.

- [x] **Slice 1: New tokens, self-hosted IBM Plex fonts and unit-test tooling**

  > After this slice the whole app (web and mobile) already uses the new palette and fonts, and `pnpm -r test` runs.
  - [x] Rewrite `packages/ui-tokens/src/index.ts` per tech spec 2.1 (palette light/dark incl. `warningInk`, plotStatusColors, plotStatusOnColor, typography, spacing, radius, layout). Add vitest to `packages/ui-tokens` and `packages/shared` (`"test": "vitest run"`), a WCAG contrast test and token-shape test in ui-tokens (tune dark values until green), `formatMoney` tests (`"0.00"`, `"1250.50"`, `"-350.00"`, `"1000000.00"`) and a uk/en key-parity test in shared. **[Agent: react-frontend]**
  - [x] Web: rewrite `web/src/index.css` `@theme` and dark block to the new tokens (tech spec 2.2), keep existing utility names working or update every usage in `web/src` to the new token utilities; add `@fontsource/ibm-plex-sans|serif|mono` (exact pins, latin+cyrillic, used weights) imported in `web/src/main.tsx`; remove Google Fonts from `web/index.html` and update `theme-color` metas; add vitest + jsdom + Testing Library (exact pins compatible with vite 8) with the `test` block in `web/vite.config.ts`, `web/src/test/setup.ts`, `web/src/test/render.tsx`, `"test": "vitest run"`, and the theme sync test `web/src/theme.test.ts`. Root `package.json` gets `"test": "pnpm -r test"`. **[Agent: react-frontend]**
  - [x] Mobile: add `expo-font` 58.0.8 and `@expo-google-fonts/ibm-plex-sans|serif|mono` 0.4.1 (exact), `mobile/src/fonts.ts` with per-weight family names, update `mobile/App.tsx` to the renamed tokens and IBM Plex fonts (`useFonts`, system fonts on error, button `minHeight` 44), and set `mobile/app.json` `adaptiveIcon.backgroundColor` to the light accent. **[Agent: expo-mobile]**
  - [x] Wire unit tests into the Makefile `test` target (`pnpm -r test`) and add a `pnpm -r test` step to the CI `clients` job in `.github/workflows/ci.yml` (after typecheck). Update README/`make help` text if it lists what `make test` runs. **[Agent: devops-infra]**
  - [x] Verify: `pnpm install && pnpm -r test && pnpm -r typecheck && pnpm -r lint && pnpm format:check && pnpm --filter web build && pnpm audit --prod --audit-level high` all green; `grep -r "fonts.googleapis" web/` finds nothing. Delete any temporary files produced. **[Agent: react-frontend]**

- [x] **Slice 2: Component kit**

  > The kit exists with tests; every screen's loading, empty and error states already come from it.
  - [x] Create `web/src/components/ui/` per tech spec 2.4: `Button` (+ `buttonClass`), `Field` (moved from `components/Field.tsx`, old imports updated), `TextField`, `SearchField`, `Select`, `TextArea`, `Panel`, `PageHeader`, `Badge`, `DataTable`, `Money` (uses `formatMoney`), `StatusDot`, `Notice`, `EmptyState`, `LoadingState`, `ErrorState`, and an `index.ts` barrel. One co-located `*.test.tsx` per component (render, accessible name, disabled state where relevant, field error linkage via `aria-describedby`, `Money` output for the four sample amounts). Make `QueryBoundary` render `LoadingState` / `ErrorState` / `EmptyState`. New strings only via i18n. **[Agent: react-frontend]**
  - [x] Verify: `pnpm --filter web test && pnpm -r typecheck && pnpm -r lint && pnpm format:check && pnpm --filter web build` green. Delete any temporary files produced. **[Agent: react-frontend]**

- [x] **Slice 3: App shell and members screens**

  > The new header and side menu (collapsing under 768 px) and the restyled Members list, Member page and Member form.
  - [x] Rewrite `web/src/components/Layout.tsx` per tech spec 2.5: header (product link, cooperative name, user name linking to `/account`, role names, locale switch, sign out, Menu button with `aria-expanded`/`aria-controls` only when there are menu items), `<aside id="app-menu">` with a named `<nav>` of permission-filtered sections (Members, Roles, Audit), active item `accentSoft`/`accent`, collapse under 768 px, close on nav click and Escape, content column max ~1060 px with `min-w-0`. i18n keys `nav.menu`, `nav.main`. **[Agent: react-frontend]**
  - [x] Restyle Members list (`PageHeader`, `Panel`, `SearchField`, `DataTable` with name link, phone, email, channel, roles, status badge; restyled `Pagination`), Member page (`MemberDetails`, `MemberRolesForm`, `ConfirmDialog`) and Member form (two-column grid from `md`, `TextField`/`Select`/`TextArea`, `Notice` instead of `Banner`) against `docs/design/screens/board-members.png`, `board-member.png`, `board-member-form.png`. Behaviour, routes, permissions and API calls unchanged. **[Agent: react-frontend]**
  - [x] Update e2e selectors in `web/e2e` broken by the new markup (tech spec 2.8: `header nav`, `getByRole('navigation')` ambiguity, member `listitem` rows → table rows, `a > span:first-child`, `support/login.ts` wait), preferring roles and labels. **[Agent: testing-expert]**
  - [x] Verify: `pnpm --filter web test && pnpm -r typecheck && pnpm -r lint && pnpm format:check` green, then `make e2e` green (needs Docker and JDK 25; ports 8080/5173 free). Delete any temporary files produced. **[Agent: testing-expert]**

- [ ] **Slice 4: Remaining screens, narrow-screen spec and clean-up**

  > Roles, Audit, My account and the full-page messages restyled; old styling helpers removed; the 390 px flow covered.
  - [ ] Restyle Roles (per-role `Panel`s, `Badge`, `Button`s; structure and behaviour kept), Audit (`DataTable` + pagination), My account (two `Panel`s, roles as `Badge` list items, body ≥ 16 px), Not found, No access, Sign-in and `FullPageMessage` (centered `Panel`, serif h1) against `board-roles.png`, `board-audit.png`, `member-account.png`. Replace remaining `Banner`, `inputs.tsx` and `QueryState` usages and delete those files. Add the ESLint `no-restricted-syntax` rule against hex color literals in `web/src/components` and `web/src/pages`. Update docs that describe web styling (`web/README*`, `docs/design/README.md`) if they mention old fonts or palette. **[Agent: react-frontend]**
  - [ ] Add `web/e2e/responsive.spec.ts` at 390 × 844 (tech spec 2.8): menu toggle with `aria-expanded`, members table scrolls inside its panel, no horizontal page scroll, a plain member sees no Menu button; plus a desktop check of the active menu item (`aria-current="page"`). Fix any other e2e selectors broken by this slice. **[Agent: testing-expert]**
  - [ ] Verify: `make check` green, then `make e2e` green. Delete any temporary files produced. **[Agent: testing-expert]**

- [ ] **Slice 5: Feature Testing & Regression**

  > Verifies the whole feature end-to-end against functional-spec.md, run after all implementation slices are complete.
  - [ ] Read functional-spec.md acceptance criteria in full. Generate acceptance-level tests that verify the entire feature as a whole — not individual slices. Cover applicable layers (unit for pure logic, integration for service interactions, e2e for user flows) based on the project's testing stack. Write tests with RED validation (must fail before implementation is confirmed done). Annotate each test with `@spec: 003-ui-design-system` and `@regression` if suitable for long-term regression. **[Agent: testing-expert]**
  - [ ] Run all generated tests. All must pass. Fix any failures before proceeding. **[Agent: testing-expert]**
