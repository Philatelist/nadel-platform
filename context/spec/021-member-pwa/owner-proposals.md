# Owner proposals for feature 021

Agents never edit `CLAUDE.md`, `.claude/`, `.github/` or `.env.example` files. Apply these by hand (they go in the PR body).

## CLAUDE.md

- Product line (line 5): replace "a web account and mobile apps" with "a web account that installs on phones
  as an app (PWA)".
- Layout table: delete the row

  ```
  | `mobile/` | Expo (React Native) for iOS and Android |
  ```

- Layout table, `packages/ui-tokens`: "Design tokens (colors, spacing, fonts) for the web app".
- Code style, TypeScript line: "no default exports except where a framework requires them (for example Vite and
  ESLint config files)" (the Expo entry-point example is removed).
- Commands: `make infra` mentions the fake push service (:8092) next to the fake Telegram API; `make e2e` likewise (added
  by the slices that create it).

## .claude/

- Delete `.claude/agents/expo-mobile.md`, and any routing or list that names the `expo-mobile` agent.

## .github/

- `.github/dependabot.yml`: checked, no `/mobile` entry exists (the entries are `/backend`, two for `/`, `/infra`), so no
  removal is needed.
- `.github/workflows/ci.yml`: read, no Expo step or `mobile` path exists; no change is needed for the Expo removal (the
  Clients job uses the workspace). Later slices may add `make stand-test` and `make e2e-pwa` steps.

## infra/stand/.env.example

- Added by the push slice: the three `NADEL_WEBPUSH_*` lines with comments (not part of slice 1).
