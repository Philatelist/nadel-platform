# 021. The member's phone: installable web app, push, meter photo

Input for `/feature-pipeline`. Requires 001–020 (all merged). This is phase 5 of the roadmap ("member account, mobile
apps, meter reading submission with photo, push").

**Decided with the owner (11.11.2026): a progressive web app (PWA), not native apps.** The same React web app is
installed on the phone from the browser. There is no App Store or Google Play, no store review and no developer
account, and updates reach everyone at once. The Expo shell in `mobile/` is removed (section 6). Native apps can come
back later only if a PWA limit blocks something real.

## Context for the pipeline

Read together with:
- `CLAUDE.md`;
- `web/` (routes in `App.tsx`, the account page, the member readings panel, the help panel of 019);
- the READMEs of `meters` (member readings, approvals), `notifications` (channels, outbox jobs, the fake Telegram API
  as a pattern), `documents` (invoices, the NBU QR link), `treasury` (receipt files, ADR 0019: storage, HEIC, thumbnails
  and the viewer);
- ADRs 0005 (clients), 0007 (outbox and jobs), 0016 (personal data), 0019;
- `infra/stand/` and `docs/features/backlog/deployment.md`.

General rules as in earlier features:
- contract-first;
- Flyway only;
- RLS;
- permissions, not role names;
- Problem Details;
- i18n;
- audit;
- the verification levels and the help rule of `CLAUDE.md`.

## 1. Installing the app on a phone

- **Web app manifest:**
  - the product name the app header shows now, a short name, icons (192, 512, maskable), the theme and background
    colors of the design tokens;
  - `display: standalone`, `start_url` `/` (the app already sends each user to their home screen: «Мій кабінет» for a
    member).
- **Service worker** (a maintained, MIT or Apache licensed library, such as `vite-plugin-pwa`; record the choice in the
  ADR):
  - it caches the app shell and the static files only (JS, CSS, fonts, icons, the help pages and their shots);
  - it **never caches an API response, an invoice PDF or a photo.** These are personal and money data, and a phone can
    be shared;
  - without a connection the app shows «Немає з'єднання з інтернетом. Дані з'являться, коли зв'язок відновиться.»
    and the cached help still opens;
  - **a new version:** a bar «Доступна нова версія» with «Оновити». The app never reloads by itself while a form is open.
- **The install offer:**
  - on Android and desktop Chrome or Edge, «Встановити застосунок» on the account page (the browser's install
    prompt);
  - on an iPhone in Safari, a short card with the steps «Поділитися → На початковий екран»;
  - both can be hidden with «Не показувати» (remembered on that device); they are not shown when already installed.
- **The board** can install it the same way. Nothing on the board's screens changes, apart from section 4.
- **Signing out** clears the service worker's runtime data and the push subscription of that device (section 2).
- The public help stays as in 019, and the stand flag still makes everything `noindex`.

## 2. Push notifications

**Web Push** with VAPID keys. The keys come from the environment (`NADEL_WEBPUSH_PUBLIC_KEY`,
`NADEL_WEBPUSH_PRIVATE_KEY`, `NADEL_WEBPUSH_SUBJECT`); they belong to the platform, not to a cooperative. With no keys,
push is off and its controls are hidden. The server never fails to start because of it.

**The member turns it on per device.** In the account's «Сповіщення» block, «Сповіщення на цьому пристрої» asks the
browser's permission and stores the subscription. A user can have several devices, and each one can be turned off. On
an iPhone, push works only in the installed app (iOS 16.4 or later); in Safari outside the installed app, the switch
explains this instead.

**Push is an extra signal, not a delivery channel.** Invoices are still delivered as 017 set (e-mail, Telegram, paper).
A push only says that something happened and opens the right screen.

| Event | Who | Text (no amounts, no names) | Opens |
|---|---|---|---|
| An invoice for a month is issued | the plot's owners with push on | «Новий рахунок за {місяць}» | the account's invoices |
| A payment is recorded (when the cooperative's `notify_payments` is on) | the payer | «Платіж зараховано» | the account's charges |
| The member's reading is approved or rejected | the submitter | «Показ лічильника прийнято» / «…відхилено» | the member's readings |
| An announcement is sent (017) | its recipients | «Нове оголошення від правління» | the announcement |
| A member submits a reading | users with `READINGS_APPROVE` | «Нові покази на перевірку» (at most one per hour per user) | `/readings/approvals` |

**The lock screen rule:** a push carries no amount, no plot number, no name and no address. The payload is encrypted
(RFC 8291) anyway, but it shows on a locked phone.

**Sending:**
- through the outbox and a JobRunr job, as in 017;
- a subscription that answers 404 or 410 is deleted;
- other failures are retried with back-off and never block the event.

**Logs and audit:**
- the log has the cooperative id, the user id, the event code and the outcome only. Never the endpoint URL, which is a
  per-device secret: it is stored and shown nowhere outside the push sender;
- turning push on or off is not audited.

**The data:**
- a table of subscriptions per user and device (endpoint, keys, user agent label, created, last success), with RLS;
- the endpoint and keys are encrypted at rest as in ADR 0016;
- archiving a member or disabling their access (020) deletes their subscriptions.

**Tests and the demo:** a fake push service in `infra/` (like `fake-telegram`) receives the pushes in dev and e2e.
Tests decrypt the payload with the test subscription's keys.

## 3. A photo of the meter with a reading

- When submitting a reading, the member can attach one photo: «Додати фото лічильника». On a phone, this opens the
  camera (`capture`) or the gallery.
- The electrician and the board can attach one too, on the readings screen.
- **Files and checks as receipt files (018, ADR 0019):**
  - JPEG, PNG, HEIC (decoded in the browser, as now);
  - the same size limit;
  - the photo is reduced in the browser to at most 2 000 px on the long side before upload;
  - the same thumbnail and viewer.
- **New cooperative setting** «Фото показу обов'язкове для членів», off by default. When it is on, a member's
  submission without a photo is refused with `READING_PHOTO_REQUIRED`. The board and the electrician never need one.
- **«Перевірка показів»** shows the thumbnail next to each pending reading, with the viewer on click, so the board
  compares the digits before approving.
- **Who can open a reading photo:** the plot's owners, and users with `READINGS_ENTER` or `READINGS_APPROVE`. Others
  get 404.
- **The photo stays with the reading:**
  - a superseded or rejected reading keeps its photo;
  - deleting a photo is not offered;
  - a wrong photo is replaced by submitting the reading again.
- **Audit:** the reading's existing audit records `photo: yes/no`, never the file.

## 4. Phone layouts

The screens used on a phone must work at 360 px wide without horizontal scrolling, with touch targets of at least
44 px:
- «Мій кабінет» and all its blocks;
- the member's readings and the photo step;
- the invoice view;
- «Перевірка показів» and the readings entry (the electrician walks the plots with a phone);
- «Новий платіж» (the treasurer takes cash at the gate);
- the help panel.

**Paying from the phone:**
- next to an invoice, «Сплатити в банку» opens the invoice's NBU QR link (016). On a phone, this offers the bank apps;
  on a computer, it is the QR code;
- «Скопіювати IBAN» and «Скопіювати призначення» copy the payment details with a short confirmation.

## 5. Help and guides

**New help pages** (uk and en, template of 019, phone shots):
- «Встановити на телефон» (Android and iPhone, with the iOS version note);
- «Сповіщення на телефоні» (turning on and off, what is sent, why there are no amounts).

**Updated pages:**
- the account;
- the member's readings and readings approvals (the photo);
- the settings (the photo rule);
- an invoice («Сплатити в банку»);
- «Початок роботи» for a member.

**The owner manual:**
- the VAPID keys (how to generate them, where they go in the server environment and in `infra/stand`, that changing
  them drops every subscription);
- HTTPS is required for the service worker and push.

## 6. Removing the Expo shell (ADR 0021)

- **ADR 0021 "Phone access as a PWA":**
  - supersedes the mobile part of ADR 0005;
  - records the reasons (one code base, no stores, the cooperative's budget) and the PWA limits accepted: iOS push
    only from the installed app, no background readings sync;
  - says what would bring native apps back.
- **Delete `mobile/`:**
  - remove it from `pnpm-workspace.yaml` and the Makefile;
  - remove the vulnerabilities accepted only for Expo (`deepmerge`, `glob`, `minimatch`, and any other whose reason
    says Expo) from `pnpm-workspace.yaml` and `osv-scanner.toml`;
  - prove with a clean `pnpm audit` and an OSV scan.
- **Text the owner applies (the agent writes the exact text in the PR):**
  - `CLAUDE.md`: the `mobile/` row, the "web account and mobile apps" wording, the ui-tokens line;
  - `.github/workflows/ci.yml`: the mobile steps of the Clients job.
- **`@nadel/shared` and `@nadel/ui-tokens` stay** (used by the web).

## 7. Stand fixes (from the 11.11 redeploy)

- **`infra/stand/stand-init.sh`:** Keycloak can report healthy before its admin login works (503). Retry the `kcadm`
  login every 5 s for up to 2 minutes, then fail with a clear message.
- **`infra/stand/README.md`:**
  - do not use `docker compose down -v`: it also deletes Caddy's certificate volume, and Let's Encrypt allows only 5
    certificates per week;
  - to reset the data, use `docker compose down && docker volume rm nadel-stand_pgdata`;
  - after a data reset, the cooperative's mailbox settings must be entered again.
- **The stand's VAPID variables.** The owner adds them to `infra/stand/.env.example` from the PR's text. `deploy.sh` or
  `stand-init.sh` generates a key pair once when the variables are empty, and keeps it across runs.

## 8. Out of scope

- native apps and store publishing;
- online card payment and bank APIs (phase 8);
- OCR of the meter photo (phase 8);
- offline data or offline submission of readings;
- SMS and Viber;
- push to the board for events other than the one in section 2.

## 9. Acceptance criteria

- [ ] **Install and service worker:**
  - the manifest and service worker pass the Lighthouse installability check;
  - a test proves that no `/api` response, PDF or photo is in any cache after a member session;
  - the offline message and the cached help work;
  - the «Оновити» bar appears for a new build and does not reload an open form.
- [ ] **Push:**
  - on and off per device;
  - every event of section 2 reaches the fake push service with the listed text and no amount, plot or name;
  - 404/410 deletes the subscription;
  - push without keys is off and the server starts;
  - the approvals push is limited to one per hour;
  - archiving or disabling access deletes subscriptions;
  - tests for allowed and denied access to the subscription endpoints, and RLS between cooperatives.
- [ ] **Meter photo:**
  - a member, the electrician and the board attach a photo;
  - the photo rule refuses a member's reading without a photo with `READING_PHOTO_REQUIRED`;
  - access to the photo is as in section 3 (tests for allowed, denied and another cooperative);
  - approvals show the thumbnail and the viewer.
- [ ] **Phone layouts:**
  - the screens of section 4 at 360 px with no horizontal scroll (a Playwright check per screen);
  - «Сплатити в банку» opens the NBU link;
  - the copy buttons work.
- [ ] **Expo removed:**
  - `mobile/` is removed;
  - `pnpm audit` and OSV pass without the Expo exceptions;
  - ADR 0021 is written;
  - the owner's `CLAUDE.md` and CI text is in the PR.
- [ ] **Stand:**
  - `stand-init.sh` survives a Keycloak that answers 503 at first (a script test with a stub);
  - the README notes are in place;
  - the VAPID keys are generated once.
- [ ] **Help:**
  - the pages and shots of section 5 are in place;
  - the completeness check passes.
- [ ] **`make check` is green. e2e:**
  - the member, on a phone viewport, submits a reading with a photo, and the chairman sees the thumbnail and approves
    it (`@smoke`);
  - the member turns on push, the chairman issues an invoice, and the fake push service receives «Новий рахунок за …»;
  - the member opens an invoice on a phone viewport and sees «Сплатити в банку».
