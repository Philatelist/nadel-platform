# Web

React + Vite + TypeScript + Tailwind CSS. One app for members and admins; what is shown depends on permissions. Rules: root [CLAUDE.md](../CLAUDE.md).

## Run and check

```bash
make install    # from the repo root
make web        # http://localhost:5173, proxies /api to the backend on :8080
pnpm --filter web typecheck
pnpm --filter web lint
pnpm --filter web build
```

## End-to-end specs

Playwright (chromium only) in `e2e/`, configured by `playwright.config.ts`: `baseURL` `http://localhost:5173`, locale
`en-US`, one worker, one retry in CI, trace and screenshot kept on failure. The specs sign in through the Keycloak form
(`e2e/support/login.ts`; password from `E2E_PASSWORD`, default the dev value) and check that the treasurer opens Members
and a read-only card, and that an ordinary member has no Members item and gets "no access" on `/members`.

```bash
make e2e-install    # from the repo root, once: pnpm --filter web e2e:install (chromium)
make e2e            # starts infra, backend jar and web, runs pnpm --filter web test:e2e, stops backend and web
```

With the stack already running (`make up`), `pnpm --filter web test:e2e` runs the specs directly. Reports land in
`playwright-report/` and `test-results/` (ignored by git, Prettier and ESLint). `typecheck` also checks `e2e/`.

Sign in through Keycloak as `treasurer@demo.local` / `nadel` (see the user list in CLAUDE.md). Navigation and routes
follow the permissions in `/me` (`useCan`, `RequirePermission`); hiding is a convenience, the API enforces access.

## Layout

| Path                                   | What it is                                                                                                                                                           |
| -------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `src/main.tsx`                         | Providers: OIDC auth, TanStack Query, i18n; API client configuration                                                                                                 |
| `src/App.tsx`                          | Routes (react-router)                                                                                                                                                |
| `src/pages/`                           | Screens                                                                                                                                                              |
| `src/components/`                      | Shared UI components                                                                                                                                                 |
| `src/components/ui/`                   | Component kit (Button, Panel, PageHeader, DataTable, fields, Badge, Notice, states); build web screens from it, with token colors only (ESLint forbids hex literals) |
| `src/theme.ts`, `src/themeContext.tsx` | Theme choice (`system`/`light`/`dark`) in `localStorage`, applied as `data-theme` on `<html>`; `index.html` has an inline script that applies it before first paint  |
| `src/index.css`                        | Tailwind and design tokens (match `packages/ui-tokens`)                                                                                                              |
| `src/me.tsx`                           | Current user from `GET /me`: `MeProvider`, `useMe()`, `useCan(permission)`; "not linked" and "no cooperative" pages                                                  |
| `e2e/`                                 | Playwright specs and the sign-in helper                                                                                                                              |

## Configuration

`web/.env` (see [web/.env.example](.env.example)): `VITE_OIDC_AUTHORITY`, `VITE_OIDC_CLIENT_ID`.

UI text comes only from `@nadel/shared` i18n catalogs; API calls only through `@nadel/api-client`.

Note: the inline theme script in `index.html` needs a hash (or nonce) in `script-src` once a Content-Security-Policy is added.
