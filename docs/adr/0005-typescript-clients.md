# 0005. TypeScript on all clients: React (web) and Expo (mobile)

**Status:** accepted. Superseded in part by [ADR 0021](0021-phone-access-as-a-pwa.md): the mobile part (Expo) is replaced by the installable web app; the web part and the shared packages stand.

## Context
We need web, iOS and Android with one developer. Options: native apps, Flutter, React Native.

## Decision
Web: React + Vite. Mobile: Expo (React Native). Shared packages: `api-client`, `shared` (money, statuses, i18n), `ui-tokens`.

## Consequences
- One language and shared types across all clients; shared formatting and translations.
- Expo EAS builds the apps in the cloud; OTA updates ship without a store release.
- UI components differ between web and mobile (DOM vs native); only tokens and logic are shared.
