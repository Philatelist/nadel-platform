# Technical Specification: The member's phone — installable app, push, meter photo

- **Functional Specification:** `context/spec/021-member-pwa/functional-spec.md`
- **Status:** Implemented — pending manual check
- **Author(s):** Alex Usov (drafted in pipeline mode; every choice marked **Assumption** was not confirmed by the owner)

---

## 1. High-Level Technical Approach

- **Web (PWA):** `vite-plugin-pwa` (MIT, pinned exactly) with the `injectManifest` strategy and a hand-written
  `web/src/sw.ts` that precaches only the build output (shell, JS, CSS, fonts, icons, help text) and has
  one runtime cache, for the public help screenshots only (owner decision on PR #74: cache-first when first shown,
  size-limited; the precache stays about 4 MiB instead of 31.5 MiB). Registration in `prompt` mode with an update bar and a dirty-form guard; an offline banner;
  install offers; sign-out clean-up. A separate Playwright config tests the production build.
- **Push:** a new subpackage `notifications.internal.push`. VAPID (RFC 8292, ES256) and payload encryption (RFC 8291,
  aes128gcm) are built with **JDK crypto only** (no new backend dependency). Subscriptions are stored per member and
  device, encrypted at rest (ADR 0016). Each push is a durable JobRunr job (ADR 0007) with retries and TTL. Four
  events feed it: two new public events (`documents.InvoicesIssued`, `meters.ReadingSubmitted`), one made public
  (`members.MemberAccessRevoked`), one internal (`AnnouncementSent`), plus the existing `PaymentRecorded` and
  `ReadingDecided`. A fake push service in `infra/` receives pushes in dev and e2e.
- **Meter photo:** stored in the database like expense receipts (ADR 0019), one per reading, append-only. File checks
  move from `treasury.internal` into a new shared module `app.nadel.files`. New multipart operations for submission
  and board entry; a download endpoint with the access rule; a cooperative setting for the photo rule.
- **Phone layouts and paying:** a phone Playwright project (360×740); fixes found by a shared side-scroll and
  touch-target check; `InvoiceRow` of `/me/invoices` gains the payment details (NBU link, IBAN, purpose).
- **Expo removal:** delete `mobile/`, its workspace entry, Make target, ignore entries and the security exceptions
  accepted only for it; ADR 0021 records the PWA decision. Texts for owner-only files go into the PR.
- **Stand:** kcadm login retry; VAPID keys generated once into `.env`; README notes; Caddy cache headers for
  `sw.js`/manifest; a plain bash script test with a stub `docker`.

Migration: `V38__member_pwa.sql`. ADR: `docs/adr/0021-phone-access-as-a-pwa.md`.

---

## 2. Proposed Solution & Implementation Plan (The "How")

### 2.1 Web: installable app (`web/`)

| Item | Decision |
|---|---|
| Plugin | `vite-plugin-pwa` 2.0.0, exact pin, devDependency; brings Workbox. Fallback if its peer range rejects Vite 8: a small local Vite plugin around `workbox-build` (same contract). |
| Strategy | `injectManifest`, `srcDir: 'src'`, `filename: 'sw.ts'`; separate `web/tsconfig.sw.json` (`lib: ["WebWorker"]`) included in `typecheck`; ESLint SW globals. |
| `sw.ts` contents | `precacheAndRoute(self.__WB_MANIFEST)`, `cleanupOutdatedCaches()`, one `NavigationRoute` to `/index.html` with a denylist (`/api/`, `/realms/`, files with an extension), `push`, `notificationclick`, `message` (`SKIP_WAITING`). Nothing else. |
| Precache | `**/*.{js,css,html,svg,woff2,png,webmanifest}` plus the static help shells; the help screenshots `help/img/**` are excluded (owner decision on PR #74) and cached at runtime by a `CacheFirst` route with `ExpirationPlugin` (60 entries, 30 days, `purgeOnQuotaError`), cache `nadel-help-shots-v1`, kept at sign-out; the MapLibre chunk is excluded if over the size limit (**Assumption**: measured at the first build). |
| No personal data on the device | Three layers: no runtime routes in `sw.ts`; navigation denylist; a vitest contract test reading `sw.ts` that forbids `runtimeCaching`, extra `registerRoute(` and `caches.open(`. The server already sends `no-store` on PDFs and photos. |
| Registration | `web/src/pwa/registerPwa.ts`, called from `main.tsx` only in production builds; `registerType: 'prompt'`; state through `useSyncExternalStore`. No SW in `vite dev` (`devOptions` off) so the dev e2e stays as today. |
| Update bar | `UpdateBar.tsx` in `Layout.tsx`: «Доступна нова версія» + «Оновити». `pwa/dirtyForms.ts` with `useDirtyGuard(isDirty)`; while any form is dirty the button is disabled with a hint. The SW only activates on `SKIP_WAITING`; the single reload happens after the user presses «Оновити»; never while an OIDC `code`/`state` is in the URL. Forms that register: reading submit dialog, readings round, new payment, expense form, settings sections, announcement composer. |
| Manifest | `name`/`short_name` «Наділ», `lang uk`, `id`/`start_url`/`scope` `/`, `display standalone`, `background_color`/`theme_color` `#F3F5F0` (tokens); icons 192, 512 (`any`), 512 `maskable`; `apple-touch-icon` 180 linked from `index.html`. |
| Icons | Generated once by `web/scripts/make-icons.mjs` with Playwright (already a dependency) from the favicon glyph on the accent color; PNGs committed in `web/public/`. Not a build step. |
| Offline | `pwa/useOnline.ts` + TanStack Query `onlineManager`; `OfflineBanner.tsx` shows the spec text; `QueryBoundary` shows it instead of the generic error when offline; on `online` the active queries refetch. |
| Install offer | `pwa/installPrompt.ts` keeps `beforeinstallprompt` from module load; `InstallCard.tsx` on `AccountPage`: `prompt` (Chrome/Edge) or `ios` (Safari on iPhone/iPad, not CriOS/FxiOS/EdgiOS) or hidden (standalone, or dismissed: `localStorage['nadel.install.v1']`, per device, kept at sign-out). |
| Sign-out | `useSignOut()` used by `Layout.tsx` and `me.tsx`: delete this device on the server (device id kept in `localStorage`), `pushSubscription.unsubscribe()`, delete every cache that is not the Workbox precache, `sessionStorage.clear()`, then `signoutRedirect()`. All steps `allSettled` with a ~3 s cap; failures never block sign-out. |

### 2.2 Web: push UI

- `PushDeviceSwitch.tsx` inside `NotificationsPanel`; rendered only when `GET /me/push` says `enabled`.
- State union: `unsupported`, `ios-needs-install`, `denied`, `off`, `on`, `busy`. Turn on: `Notification.requestPermission()`
  in the click handler → `serviceWorker.ready` → `pushManager.subscribe({userVisibleOnly, applicationServerKey})` →
  `POST /me/push/devices`; on a server failure `unsubscribe()`. Turn off: `DELETE`, then `unsubscribe()`. If the
  subscription's key differs from the server key, unsubscribe and show `off`. If the server no longer lists the device,
  show `off` and drop the local subscription.
- `sw.ts` `push`: payload `{v, code, params, url, lang}`; the text comes from a **push slice of the i18n catalogs**
  (uk and en, imported from `@nadel/shared` into the SW bundle), chosen by `lang`; unknown code → «Наділ».
  `notificationclick` opens or focuses the same-origin path in `url` (validated: starts with `/`, not `//`).
- **Decision (resolves a conflict between the drafts):** the server sends codes and the month parameter, never text
  (CLAUDE.md language rule); the SW renders them from the catalog. The device's language is stored with the
  subscription.

### 2.3 Web: meter photo, phone layouts, paying

- Generalise `components/cashbook/Attachment*` into `components/files/` (moved, imports updated): `PreviewFile` gets
  `load(): Promise<Blob>` and a `key`; `AttachmentPicker` gets `maxFiles`, `accept`, `capture`. The cashbook passes
  `downloadExpenseAttachment`, readings pass `getReadingPhoto`. Blob queries keyed `['file-blob', …]`, short `gcTime`.
- `lib/heicToJpeg.ts` → `lib/normalizeImage.ts`: decode with `createImageBitmap(..., {imageOrientation: 'from-image'})`,
  scale to ≤ 2000 px on the long side, JPEG 0.85; JPEG/PNG already small are kept as they are; HEIC always converts;
  > 50 Mpx refused. The 5 MiB check runs after normalising.
- `SubmitReadingDialog`: «Додати фото лічильника» (`accept="image/*"`, `capture="environment"`), thumbnail,
  «Замінити»/«Прибрати» before saving; always uses the new multipart operation. The client does not enforce the photo
  rule; it shows the server's `READING_PHOTO_REQUIRED` message (**Assumption**: members cannot read `/settings`, so no
  hint before the attempt).
- `ReadingsRoundPage`: optional photo per row; a row with a photo is saved through the new entry operation.
- Approvals and history: `PendingReadingCard` and the reading history show a lazy thumbnail when `hasPhoto`; tap
  opens `ImageViewer`. No delete action.
- Settings: toggle «Фото показу обов'язкове для членів» (`readingPhotoRequired`) in the readings settings section.
- `InvoicePaymentActions.tsx` on each member invoice row: «Сплатити в банку» is an `<a target="_blank" rel="noopener
  noreferrer">` rendered only when `payment.link` starts with `https://qr.bank.gov.ua/`; `CopyButton` for IBAN and
  purpose (`navigator.clipboard.writeText`, fallback to a selectable field) with a `role="status"` «Скопійовано».
- 360 px: shared e2e helpers `expectNoSideScroll(page)` and `expectTouchTargets(page)` (visible controls < 44 px fail;
  inline links inside prose are exempt — **Assumption**). Known suspects: header controls, the invoice row actions,
  the reading dialog height with the keyboard, the help panel width.

### 2.4 Backend: push (`notifications.internal.push`)

- **Config** `nadel.webpush.*`: `public-key`, `private-key`, `subject` from `NADEL_WEBPUSH_*`; `allowed-hosts`
  (default `fcm.googleapis.com`, `*.push.services.mozilla.com`, `*.notify.windows.com`, `web.push.apple.com`);
  `extra-allowed-origins` (empty by default; the `dev` profile and the tests add the fake push service; only these may
  be plain http); timeouts 5 s / 10 s; `retry-delays` 1m, 5m, 30m, 2h, 6h. Push is on only when the three values are
  present, parse, the point is on the curve and the pair signs/verifies; otherwise one WARN without values and push
  off. **Startup never fails.**
- **Crypto (JDK only):** `VapidSigner` (JWT ES256 via `SHA256withECDSAinP1363Format`, `aud` = endpoint origin, `exp`
  ≤ 24 h, cached per audience); `WebPushEncryptor` (ephemeral P-256, ECDH, HKDF with HmacSHA256, AES-128-GCM, RFC 8188
  header, rs 4096). Test vector: RFC 8291 §5 byte for byte.
- **Sending:** `WebPushClient` (JDK `HttpClient`, `Redirect.NEVER`): endpoint revalidated against the allowlist before
  every send, and loopback/link-local/private addresses refused unless the origin is in `extra-allowed-origins`.
  Outcomes: 2xx delivered; 404/410 delete the subscription; 429/5xx/I/O retry (honouring `Retry-After`); 400/401/403/413
  rejected, deleted after 5 consecutive (**Assumption**). Exception messages and URIs are never logged.
- **Jobs:** `SendPush(tenantId, subscriptionId, code, ref, attempt)` per device and message, deterministic job id
  (redelivered events create nothing new), handler binds the tenant, reads in a short transaction, sends outside it,
  records the outcome; retries reschedule with the next delay until the TTL (3 days; 1 h for the approvals push).
  Not the delivery register (push is not a delivery channel) and not the in-memory `ReadingMessages` pattern (it would
  lose pushes while the service is down).
- **Recipients:**

  | Source event | Recipients | Code |
  |---|---|---|
  | `documents.InvoicesIssued(tenantId, month, buildId, plotIds)` **new public**, plots with a new or changed version | current owners of the plots, one push per member | `INVOICES_ISSUED` (param `month`) |
  | `ledger.PaymentRecorded` (existing), only if `delivery_settings.notify_payments` | each payment's payer member; none when no payer | `PAYMENT_RECORDED` |
  | `meters.ReadingDecided` (existing) | the submitter | `READING_APPROVED` / `READING_REJECTED` |
  | `AnnouncementSent(tenantId, announcementId)` **new internal** | members with a delivery row of that announcement | `ANNOUNCEMENT` |
  | `meters.ReadingSubmitted(tenantId, readingId, plotId, submitterMemberId)` **new public**, member source only | members holding `READINGS_APPROVE` (new `members.RoleHolders.activeWithPermission`), not the submitter, through `PushThrottle` | `READINGS_TO_APPROVE` |
  | `members.MemberAccessRevoked` **moved to the module root** (archive and disable access) | — | deletes all the member's subscriptions |

- **Throttle:** `push_throttle` claimed by one conditional upsert (rolling hour; atomic across instances).
- **REST** (tag `notifications`, contract-first, no permission annotation; the operator gets 403):
  `GET /me/push` → `PushConfig {enabled, publicKey?}`; `POST /me/push/devices` (`PushSubscriptionInput {endpoint,
  keys {p256dh, auth}, label?, language}`) → 201 `PushDevice {id, label, createdAt, lastSentAt}`, upsert by blind index
  (the same endpoint from another member moves to that member), at most 10 devices per member (**Assumption**);
  `GET /me/push/devices`; `DELETE /me/push/devices/{id}` (another member's or cooperative's → 404). 409 `PUSH_DISABLED`
  when push is off (new code). Endpoint and keys never in a response; not audited; logs carry tenant, member, code,
  outcome only.

### 2.5 Backend: meter photo, settings, invoices

- **New module `app.nadel.files`** (no tables, no HTTP, no dependencies; like `xlsx`): `FileKind`, `FileSniffer`,
  `ImageMetadata.strip`, `MalformedImageException`, `UploadLimits.MAX_FILE_BYTES` (5 MiB); strippers moved from
  `treasury.internal` with their tests. Treasury keeps its own problem mapping and calls `files`.
- **Operations** (contract-first; additive, the old JSON operations stay for cached old apps):

  | Operation | Contract |
  |---|---|
  | `POST /plots/{id}/reading-submissions` `submitPlotReadingWithPhoto` | multipart: `reading` (JSON `ReadingSubmission`), optional `photo`; same access and answers as `submitPlotReading` (now `deprecated`) |
  | `POST /reading-entries` `enterReadingWithPhoto` | multipart: `reading` (`ReadingEntry`), required `photo`; `READINGS_ENTER`; 201 `ReadingSaved` |
  | `GET /readings/{id}/photo` `getReadingPhoto` | owner of the plot, `READINGS_ENTER` or `READINGS_APPROVE`; everything else 404; `no-store`, `nosniff`, `inline` |

- Reading and photo are saved in one transaction. 400 `READING_PHOTO_REQUIRED` (field `photo`, new code) for a member
  submission without a photo while the rule is on; it also applies to the Telegram bot (**Assumption**: the bot answers
  with a new message asking to submit in the app, otherwise the rule could be bypassed). Board entries never need one.
  413 `FILE_TOO_LARGE`, 400 `FILE_TYPE_NOT_ALLOWED` as for receipts. The server does not re-encode or check pixels.
- A new photo with the same value and date counts as a change (**Assumption**).
- `hasPhoto` on `PendingReading`, `MeterReading`, `ReadingSaved`. Audit field `photo` (`true`/`false`, shown «так»/«ні»).
- **Settings:** `readingPhotoRequired` on `CooperativeSettings` (GET/PUT partial, `SETTINGS_MANAGE`, audited by
  `SETTINGS_UPDATED`); `CooperativeSettings` Java interface; meters reads it (new dependency meters → settings).
- **Invoices:** `InvoiceRow.payment?: InvoicePayment {link, iban, purpose}` filled by `listMyInvoices` from the stored
  snapshot (same as the PDF and `getInvoice`); absent when there is no QR. The values must be in the page before the
  tap (iOS blocks `window.open`/clipboard after an `await`).

### 2.6 Data model — `V38__member_pwa.sql`

| Table / change | Key columns | Rules |
|---|---|---|
| `cooperative_settings` | `reading_photo_required boolean not null default false` | — |
| `reading_photo` | `id`, `tenant_id`, `reading_id`, `content_type` (jpeg/png), `size_bytes` (1–5 MiB, = length), `sha256`, `content bytea` (`STORAGE EXTERNAL`), `created_by`, `created_at` | `UNIQUE (tenant_id, reading_id)`; FK to `meter_reading` `ON DELETE CASCADE`; trigger refuses UPDATE and a DELETE while the reading exists; RLS `tenant_isolation`, index on `tenant_id`; `nadel_app` without UPDATE/DELETE/TRUNCATE |
| `push_subscription` | `id`, `tenant_id`, `member_id`, `endpoint_enc`, `p256dh_enc`, `auth_enc` (PersonalDataCipher), `endpoint_index` (blind index, new `IndexKind.PUSH_ENDPOINT`), `server_key_id` (8 bytes of SHA-256 of the VAPID public key), `label`, `language`, `consecutive_failures`, `created_at`, `last_sent_at` | `UNIQUE (tenant_id, endpoint_index)`; indexes `(tenant_id)`, `(tenant_id, member_id)`; RLS; no TRUNCATE |
| `push_throttle` | PK `(tenant_id, member_id, topic)`, `last_sent_at` | RLS; no TRUNCATE |

No `SECURITY DEFINER` function, no new JobRunr tables.

### 2.7 Infrastructure

- **`infra/fake-push/server.mjs`** (Node 22, stdlib, like fake-telegram), compose service `fake-push` on 8092
  (`${NADEL_FAKE_PUSH_PORT:-8092}`) with a healthcheck: `POST /push/:id` stores headers and the raw encrypted body (201);
  control `GET /control/health`, `GET /control/received?id=`, `POST /control/reset`, `POST|DELETE /control/gone/:id`
  (410), `POST /control/fail/:id/:status`. It never decrypts; tests decrypt with their own keys.
- **Rule: dev and e2e use only synthetic subscriptions** (the test generates a P-256 key pair and auth secret,
  registers `http://localhost:8092/push/<uuid>` through the API and decrypts what arrives). A real browser's
  subscription points at a real push service, which tests never reach. `web/e2e/support/fakePush.ts` implements RFC
  8291 decryption with Node `crypto`.
- Dev VAPID test pair and `extra-allowed-origins: http://localhost:8092` in `application-dev.yml` (dev values only,
  labelled; gitleaks allowlist only if it flags them). `make infra`/`make e2e` help texts list the new service.
- **Expo removal:** `git rm -r mobile`; `pnpm-workspace.yaml` (package entry, the three Expo `ignoreGhsas`, the
  `node-forge` override); `osv-scanner.toml` (the six Expo blocks, header); `Makefile` (`mobile` target, `.PHONY`);
  `.gitignore`, `.prettierignore`, `.semgrepignore` Expo lines; `.npmrc` removed if the web passes typecheck, lint,
  test and build without the hoisted layout (otherwise kept with a new comment); `README.md`, ADR 0005 (superseded in
  part), `docs/adr/README.md`, `context/product/architecture.md`, `context/product/hired-agents.md`. Proof:
  `pnpm audit --audit-level=high` clean with no ignores; no `expo` in `pnpm-lock.yaml`; OSV runs in CI (not installed
  locally).
- **Stand:** `stand-init.sh` login retry (`KC_LOGIN_INTERVAL` 5, `KC_LOGIN_TIMEOUT` 120, output to `/dev/null`, clear
  message), health wait parametrised (`KC_HEALTH_TRIES`, `KC_HEALTH_INTERVAL`); `env_set` helper around the existing
  upsert; VAPID keys generated with `openssl ecparam` + DER offsets + `od`/`base64` only when either key is empty
  (public 87 chars, private 43 chars), never printed. `compose.yml` backend env `NADEL_WEBPUSH_*` with `:-` defaults.
  `Caddyfile`: `/sw.js`, `/manifest.webmanifest`, `/index.html` with `Cache-Control: no-cache` and no fallback to
  `index.html`; `/assets/*` immutable. README notes (no `down -v`; the data reset command; mailbox settings again;
  HTTPS required; key rotation).
- **Script test:** `infra/stand/tests/stand-init.test.sh` (bash, no bats) with a stub `docker` first on PATH: login
  fails twice with 503 then works (exit 0, 3 attempts); never works (exit 1, message); keys created (lengths); second
  run keeps them; `.env` mode 600. `make stand-test`, added to `make check`.

### 2.8 Texts for owner-only files (in the PR body, never committed by the agent)

- `CLAUDE.md`: the product line (web account, installable on phones), delete the `mobile/` row, ui-tokens "for the
  web app", the default-export exception example without Expo, `make infra`/`make e2e` mention the fake push service
  on 8092.
- `.github/workflows/ci.yml`: a step `make stand-test`; a step for the PWA e2e config (`make e2e-pwa`); nothing for
  Expo (the Clients job uses the workspace). `.github/dependabot.yml`: drop a `/mobile` entry if present.
- `infra/stand/.env.example`: the three `NADEL_WEBPUSH_*` lines with comments.
- `.claude/agents/expo-mobile.md`: delete (and any routing that names it).

---

## 3. Impact and Risk Analysis

| Risk | Mitigation |
|---|---|
| Personal data cached on a shared phone | No runtime caching of anything but the public help screenshots (three layers + PWA e2e that inspects `caches` after a member session); `no-store` on PDFs and photos. |
| Stale app after a deploy | `prompt` update flow; Caddy `no-cache` for `sw.js`, manifest, `index.html`. |
| SSRF through the push endpoint | Host allowlist at subscribe and before each send; https only; no redirects; private addresses refused; dev origins only by config. |
| Invalid-curve key, crypto bugs | On-curve check; RFC 8291 vector; round-trip tests; e2e decrypts with independent Node code. |
| Endpoint leak (it is a capability) | Encrypted at rest; never logged or returned; log-capture test. |
| VAPID rotation drops every subscription | Keys generated once; `server_key_id`; the web re-subscribes on key mismatch; owner manual explains. |
| Older cached app calls the API | Only additive contract changes; old JSON submit kept. |
| Fan-out of pushes (2,000 owners) | Batch enqueue; JobRunr workers bound concurrency; TTL. |
| Photo storage growth | 2,000 px cap in the browser; out-of-line storage; retention out of scope (README). |
| Update reload during OIDC redirect | Apply deferred while `code`/`state` in the URL. |
| `.npmrc` removal breaks hoisting | Verified by typecheck/lint/test/build; fallback keeps it. |
| Removing the Expo exceptions makes CI fail if a package stays through web | `pnpm audit` locally; OSV in CI; any surviving advisory is kept with a new reason, not hidden. |
| iOS behaviour, real push services and Lighthouse cannot be tested locally | Marked `[?]` for a manual check on real phones. |
| Wider 44 px fixes than listed | Budgeted in the phone-layout slice; exemptions recorded. |

Modularity: new module `files`; `members.MemberAccessRevoked` and `RoleHolders.activeWithPermission` public;
`documents.InvoicesIssued` and `meters.ReadingSubmitted` public; meters → settings. `ModularityTests` must stay green.

---

## 4. Testing Strategy

- **Backend unit:** `WebPushEncryptorTests` (RFC vector, round trip), `VapidSignerTests`, `VapidKeysTests` (empty,
  malformed, mismatched → off, no exception), `PushOutcomeTests`, back-off/TTL with a fixed clock, `PushPayloadTests`
  (no amount, plot, name or address), endpoint validation, `ReadingPhotoUploadsTests`, moved `files` tests.
- **Backend integration (Testcontainers):** an in-JVM fake push service (like `FakeBotApi`) that decrypts:
  `PushDevicesTests` (own devices, 404 for others and other cooperatives, 403 operator, off → 409, no endpoint in
  responses), `PushEventsTests` (each event, recipients, throttle, redelivery, 410, outage then recovery, archive and
  disable, logs), `PushJobTests`, `ReadingPhotoTests` (types, size, rule incl. the bot, access table, headers,
  history keeps photos, table guard, audit, `hasPhoto`), settings, `MyInvoicesPaymentTests`, `InvoicesIssuedEventTests`,
  isolation tests for the three tables, `ModularityTests`. Raise `coverage.minimum` if coverage rises.
- **Web unit (vitest):** `sw.ts` contract test, `normalizeImage`, install-offer and push state machines, dirty guard,
  `registerPwa` state, `CopyButton`, `InvoicePaymentActions`, catalog parity.
- **E2E (Playwright):**
  - dev config: `meter-photo.spec.ts` (member with photo at phone size and chairman approves with thumbnail —
    `@smoke`), `push.spec.ts` (synthetic subscription → chairman issues invoices → fake push receives «Новий рахунок
    за …» after decryption), `pay-from-phone.spec.ts`, `*.phone.spec.ts` per screen at 360×740 (side scroll and 44 px).
  - PWA config (`web/playwright.pwa.config.ts`, production build served by `vite preview` on 5173 with the `/api`
    proxy, `make e2e-pwa`): manifest and icons, active SW, caches after a member session, offline banner and cached
    help, update bar with a dirty form (two builds), install offer with a synthetic `beforeinstallprompt`, sign-out
    clean-up with a stubbed `PushManager`.
- **Infra:** `make stand-test`; `pnpm audit`; OSV in CI.
- **Manual `[?]`:** Lighthouse/DevTools installability on a real build over HTTPS, the Android install dialog, the iOS
  card and push in the installed iOS app, a real push on a phone from the stand.
