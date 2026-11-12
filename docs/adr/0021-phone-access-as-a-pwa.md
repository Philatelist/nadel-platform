# 0021. Phone access as a PWA: the web app installable with a service worker (vite-plugin-pwa, injectManifest, one runtime cache for the public help screenshots only), Web Push on JDK-only crypto, subscriptions encrypted at rest, meter photos stored like ADR 0019, the Expo app removed

**Status:** accepted (2026-11); introduced in phase 5 by spec 021 (`context/spec/021-member-pwa/`). Supersedes the mobile
part of ADR 0005; the web part and the shared packages of ADR 0005 stand.

## Context
ADR 0005 planned iOS and Android apps in Expo next to the web app. In practice `mobile/` stayed a shell: no screens, no
sign-in, no push. Its cost was real: a second UI code base, store accounts and review, EAS builds, a hoisted
`node_modules` layout, and a list of accepted security advisories that exist only for Expo build tooling. The users are
members of dacha cooperatives, mostly on their own phones, and the cooperative's budget pays for one developer. What a
member needs on the phone is small: the balance and the invoice, a meter reading with a photo, and a notice when a
new invoice or a decision on a reading arrives. The web app already does the first two; it lacks installation, push and
a phone layout.

## Decision

### The web app is the phone app
- The web app is made installable (manifest, icons, `display: standalone`) and gets a service worker. There is no
  native app, no store listing and no `mobile/` workspace; Expo is removed from the repository, the lockfile and the
  accepted-advisory lists.
- The service worker is built by `vite-plugin-pwa` in `injectManifest` mode: our own `sw.ts` is the only source of
  behaviour (push, notification click, update), the plugin only injects the precache list of the build's static files
  (app shell and help text; about 4 MiB).
  **One runtime cache, for public files only**: the help screenshots (about 27 MiB in all) are not precached; each one
  is cached the first time it is shown (cache-first, same origin, `/help/img/<lang>/<id>.png` only, 200 image answers
  only, at most 60 entries and 30 days). Offline, a screenshot not yet seen shows its alt text. No API response, PDF,
  photo or other personal data is ever put in a cache by the worker, so a shared or lost phone holds no cooperative data
  after sign-out. Updates use the `prompt` flow (the user applies them,
  never during an OIDC redirect); Caddy serves `sw.js`, the manifest and `index.html` with `no-cache`.

### Web Push without a library
- The backend sends Web Push itself, with the JDK `HttpClient` and JDK crypto only: message encryption `aes128gcm`
  (RFC 8291, ECDH P-256, HKDF-SHA-256, AES-128-GCM through `javax.crypto`) and VAPID (RFC 8292, an ES256 JWT signed with
  the JDK `Signature`). The incoming subscription key is checked to be on the curve. No new dependency.
- The payload is codes only: a notification kind, the month (where it applies) and the device language that the member
  chose. It carries no name, amount, plot, address or free text; the service worker turns the codes into text from the
  bundled i18n catalog. So even the push service, which sees the encrypted body, and a log, never see personal data.
- A subscription belongs to a member and a device. Its endpoint (a capability: whoever knows it can push to the device)
  and keys are stored encrypted with the cooperative's data key (ADR 0016), never returned by the API and never logged.
  A 404 or 410 from the push service deletes the subscription. Before every send the endpoint host is checked against an
  allowlist of known push services (https only, no redirects, private addresses refused); development origins are added
  only by configuration. VAPID keys are generated once per stand and come from the environment.
- Sending runs on domain events and the outbox (ADR 0007) and JobRunr jobs, one job per device and message with a
  deterministic id (a redelivered event queues nothing new), retried with back-off while a time-to-live lasts, so a late
  push is dropped. It is not a channel of the delivery register of ADR 0018: push is an extra signal next to the
  member's delivery channel, not a delivery of a document, so it has no delivery row, lane or paper fallback.
  (Implementation note, slice 7: this replaces the first wording "through the delivery register … as another
  channel", which the technical specification had already narrowed.)

### The fake push service rule
- Dev and end-to-end tests never reach a real push service. `infra/fake-push` (Node, standard library only, like
  `fake-telegram`) accepts `POST /push/<id>`, keeps headers and the raw encrypted body and never decrypts. Tests create
  synthetic subscriptions (their own P-256 key pair and auth secret, an endpoint pointing at the fake service), and
  decrypt what arrives with independent code. A subscription made by a real browser points at a real push service and is
  used only in a manual check on real phones.

### Meter photos
- A member attaches a photo to a reading. It is shrunk in the browser (long side 2,000 px, HEIC decoded there only, as
  in ADR 0019) and stored by a new `files` module in the database like the receipts of ADR 0019: out-of-line storage,
  size limit, JDK-only metadata stripping (EXIF and GPS removed), served with `Cache-Control: no-store`, RLS by tenant.
  The reading's decision stays with the board.

## Consequences
- One code base, one deploy, no stores, no review queue, an update reaches every phone on the next start.
- No native tooling in CI and no Expo advisory exceptions; the audit and OSV scan run without them. `.npmrc` with the
  hoisted layout is gone.
- Keycloak keeps the public client `nadel-web` with PKCE S256 for the phone too. The unused client `nadel-mobile` stays
  in the realm file until its removal is reviewed.
- Push needs a secure context (HTTPS), so the stand must serve https.
- Rotating the VAPID keys drops every subscription (the web app subscribes again on the next start); the owner manual
  says so.

## Accepted limits
- **iPhone:** Web Push works only in the app installed to the home screen, on iOS 16.4 or later. In a Safari tab there
  is no push. The app explains the steps; a member without push still gets the e-mail or chat message (ADR 0018).
- **No background sync:** a reading entered offline is not queued and sent later. The form needs a connection.
- **No offline data:** the app shows an offline notice and no cached balances, because of the no-runtime-caching rule.
- iOS and real push services cannot be tested locally; they are checked by hand on real phones.

## What would bring native apps back
- A requirement the browser cannot meet: background reading sync, a widget, reliable push on iPhone outside the home
  screen, deep device integration (NFC meter readers, barcode scanning at scale), or a store presence that the
  cooperatives ask for.
- Evidence that members do not install or use the web app (measured, not guessed), or that push delivery on iOS fails
  in practice.
- A budget for a second client, its store accounts and its release process. The API contract is already shared, so a
  native client would be new UI only.

## Alternatives
- **Expo apps (the plan of ADR 0005):** better push and offline, but a second UI, stores, builds and a maintenance
  cost out of proportion for the three screens a member needs. Rejected.
- **A store wrapper (Capacitor, a Trusted Web Activity):** the same web app inside a shell. It brings back store
  accounts, review and signing without the native benefits we would use. Rejected for now; it is the cheapest first step
  if the store presence is ever required.
- **A web-push library (`nl.martijndwars:web-push` with BouncyCastle):** less code to write, but two dependencies with
  their own advisories to track and the JDK already provides every primitive (ECDH, HKDF through HMAC, AES-GCM,
  ES256). The crypto is about two hundred lines checked against the RFC 8291 test vector and a round trip with
  independent Node code. Rejected.
- **A third-party push gateway (FCM, OneSignal):** sends device tokens and message metadata to a third party and adds an
  account. Rejected.
