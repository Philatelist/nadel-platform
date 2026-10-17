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

Sign in through Keycloak as `treasurer@demo.local` / `nadel` (see the user list in CLAUDE.md).

## Layout

| Path | What it is |
|---|---|
| `src/main.tsx` | Providers: OIDC auth, TanStack Query, i18n; API client configuration |
| `src/App.tsx` | Routes (react-router) |
| `src/pages/` | Screens |
| `src/components/` | Shared UI components |
| `src/index.css` | Tailwind and design tokens (match `packages/ui-tokens`) |

## Configuration

`web/.env` (see [web/.env.example](.env.example)): `VITE_OIDC_AUTHORITY`, `VITE_OIDC_CLIENT_ID`.

UI text comes only from `@nadel/shared` i18n catalogs; API calls only through `@nadel/api-client`.
