# Functional Specification: The member's phone — installable app, push, meter photo

- **Topic:** Members and the board use Nadel on a phone: the web app is installed from the browser like an app,
  sends a short push notification when something happens, lets a member attach a photo of the meter to a reading,
  works on a narrow phone screen and lets a member pay an invoice from the phone through their bank app. The separate
  native mobile app shell is removed. The test stand gets three operating fixes.
- **Status:** Implemented — pending manual check
- **Author:** Alex Usov
- **Source:** `docs/features/021-member-pwa.md`

---

## 1. Overview and Rationale (The "Why")

Members of a garden cooperative mostly have a phone, not a computer. Today they can open the web account in the phone
browser, but:

- it is a web page they have to find again every time, not an app on the home screen;
- nothing tells them that a new invoice was issued, that their payment was recorded or that their meter reading was
  accepted; they learn it only from e-mail or Telegram, if those are set up;
- the board cannot see the meter itself, so a mistyped reading is approved or argued about later;
- some screens are hard to use on a narrow screen, and paying an invoice means retyping the bank details by hand.

The owner decided (11.11.2026) to deliver phone access as an **installable web app**, not as store apps: one code
base, no App Store or Google Play review or developer accounts, and every update reaches everyone at once. The unused
native app shell is removed so its tooling and its accepted security exceptions go away.

Success means a member of «Надія» installs Nadel on the phone in under a minute, gets «Новий рахунок за …» on the
phone the day the invoices are issued, submits a reading with a photo at the meter, and pays the invoice from their
bank app without typing the IBAN; the chairman approves readings by looking at the photo.

---

## 2. Functional Requirements (The "What")

### 2.1 Installing the app on a phone

- The app can be installed from the browser. Installed, it opens full screen with its own icon and the name shown in
  the app header today («Наділ»), in the colors of the app, and starts on the user's usual home screen («Мій кабінет»
  for a member).
  - **Acceptance Criteria:**
    - [?] When a member opens the app in Chrome on Android or in desktop Chrome or Edge, then the browser recognises
      it as installable (it passes the browser's installability check), and after installing, the app opens from its
      own icon without the browser's address bar and shows «Мій кабінет». (Manual check: manifest, 192/512/maskable icons and an active worker with scope / are proved by `pwa/pwa.spec.ts` (manifest and icons test, "a service worker with scope / becomes active"; manifest served 200 with display standalone, see also `docs/screenshots/021-member-pwa-install-card-chrome.png`); the browser's own installability verdict, the install itself and the standalone window need HTTPS on a real origin and a real browser (DevTools Application, Manifest or Lighthouse on the stand).)
    - [x] When the member opens the installed app with a working connection, then they see the same screens and data as
      in the browser. (Evidence: the installed app is the same SPA; `pwa/pwa.spec.ts` online flow and every `*.phone.spec.ts`; the standalone window itself is a real-phone check.)
- **Personal data is never kept on the phone.** The app keeps only its own screens, styles, fonts, icons and the help
  pages on the phone, so it opens fast and the help works offline. It never keeps a member's data, an invoice PDF or a
  photo on the phone after use, because a phone can be shared.
  - **Acceptance Criteria:**
    - [x] Given a member used the app (opened the account, downloaded an invoice PDF, viewed a meter photo), when the
      phone's app storage is inspected afterwards, then it holds no member data, no PDF and no photo. (Evidence: `pwa/pwa.spec.ts` "after a member used the app the browser caches hold only static files" (only precache, no /api, no PDF, no photo); `sw.contract.test.ts`.)
- **Without a connection** the app says so instead of failing, and the help still opens.
  - **Acceptance Criteria:**
    - [x] Given the app was opened once, when the member opens it with no connection, then they see «Немає з'єднання з
      інтернетом. Дані з'являться, коли зв'язок відновиться.» and no member data. (Evidence: `pwa/pwa.spec.ts` offline tests; `OfflineBanner.test.tsx`; `docs/screenshots/021-member-pwa-offline-banner.png`.)
    - [x] Given there is no connection, when the member opens the help, then the help pages open as usual. (Evidence: `pwa/pwa.spec.ts` "the public help opens with no connection" and "the help panel still opens".)
    - [x] When the connection comes back, then the message disappears and the data loads without restarting the app. (Evidence: `pwa/pwa.spec.ts` "the connection brings the data back"; `OfflineBanner.test.tsx`.)
- **A new version** is offered, never forced in the middle of work.
  - **Acceptance Criteria:**
    - [x] Given a new version of the app was published, when the member has the app open, then a bar «Доступна нова
      версія» with the button «Оновити» appears, and pressing «Оновити» loads the new version. (Evidence: `pwa/pwa.spec.ts` "the update bar"; `registerPwa.test.tsx`; `docs/screenshots/021-member-pwa-update-bar.png`.)
    - [x] Given the member is filling in a form (for example a reading), when a new version becomes available, then the
      app does not reload by itself and what the member typed stays on the screen. (Evidence: `pwa/pwa.spec.ts` "waits for a dirty form, never reloads by itself"; `dirtyForms.test.tsx`.)
- **The install offer** on the account page.
  - **Acceptance Criteria:**
    - [?] Given a member opens «Мій кабінет» in Chrome on Android or in desktop Chrome or Edge and the app is not
      installed, when the browser allows installing, then they see «Встановити застосунок», and pressing it opens the
      browser's own install dialog. (Manual check: the card with «Встановити застосунок» and the call of the browser's `prompt()` are proved by `pwa/pwa.spec.ts` "the install offer" with a synthetic `beforeinstallprompt` (`docs/screenshots/021-member-pwa-install-card-chrome.png`); the browser's own install dialog on Android or desktop Chrome/Edge needs a real browser over HTTPS.)
    - [?] Given a member opens «Мій кабінет» in Safari on an iPhone, when the app is not installed, then they see a
      short card with the steps «Поділитися → На початковий екран». (Manual check: the card with the steps «Поділитися → На початковий екран» is rendered with an iPhone Safari user agent (`docs/screenshots/021-member-pwa-install-card-ios.png`, `pwa/pwa.spec.ts`); the real iPhone with Safari is a manual check.)
    - [x] When the member presses «Не показувати» on either offer, then the offer is not shown again on that device. (Evidence: `pwa/pwa.spec.ts` "the install offer" (dismiss sticks after reload, both offers); `installPrompt.test.ts`.)
    - [x] When the app is already installed (opened from its icon), then neither offer is shown. (Evidence: `installPrompt.test.ts` "shows nothing when installed or dismissed"; `pwa/pwa.spec.ts` (Chrome on iPhone shows nothing).)
- **The board** installs it the same way; their screens do not change apart from section 2.4.
- **Signing out** removes what the app kept for that user on the device and turns off push on that device.
  - **Acceptance Criteria:**
    - [x] Given a member turned push on, when they sign out, then that device no longer receives their push
      notifications and the next user of the phone sees nothing of theirs. (Evidence: `useSignOut.test.ts` (clean-up, push device deleted); `push.spec.ts` "deletes a device of their own"; `pwa/pwa.spec.ts` cache test.)
- The public help stays as in feature 019, and on the test stand every page stays hidden from search engines.

### 2.2 Push notifications

- **Push is an extra signal, not a delivery channel.** Invoices are still delivered by e-mail, Telegram or on paper as
  before. A push only says that something happened and opens the right screen.
- **Off when the platform has no push keys.** When the server has no push keys configured, push is off for everyone,
  its switch is not shown, and the rest of the app works normally.
  - **Acceptance Criteria:**
    - [x] Given the server has no push keys, when a member opens «Сповіщення» in the account, then there is no push
      switch, and the server and the app work as before. (Evidence: `PushOffTests.withoutKeysPushIsOffAndTheRestWorks`; `PushDeviceSwitch.test.tsx` "renders nothing when the server has push disabled".)
- **The member turns it on per device.** In the account's «Сповіщення» block, «Сповіщення на цьому пристрої» asks the
  browser's permission and turns push on for this device only. A user can have several devices; each can be turned off.
  - **Acceptance Criteria:**
    - [x] When a member turns on «Сповіщення на цьому пристрої» and allows it in the browser, then the switch shows on,
      and the device receives the member's push notifications. (Evidence: `push.spec.ts` (device registered, one encrypted push decrypted by the test device); `PushDevicesTests`; the switch turned on in the browser with a stubbed PushManager: `docs/screenshots/021-member-pwa-push-switch-off.png`, `docs/screenshots/021-member-pwa-push-switch-on.png`; a real notification from a real push service is a manual stand check (see qa-notes).)
    - [x] When the member turns the switch off, then that device stops receiving them; their other devices keep
      receiving them. (Evidence: `PushDevicesTests` (delete own, others kept); `push.spec.ts` "deletes a device of their own"; `PushDeviceSwitch.test.tsx`; the switch turns off in the stubbed-browser run.)
    - [x] Given the member refused the browser's permission, when they look at the switch, then it stays off with a
      short explanation that notifications are blocked in the browser settings. (Evidence: `PushDeviceSwitch.test.tsx` (blocked text); the headless browser reported the permission as blocked in the account and showed the switch disabled with the explanation.)
    - [x] Given a member uses Safari on an iPhone outside the installed app, when they open «Сповіщення», then instead
      of the switch they read that on an iPhone notifications work only in the installed app (iOS 16.4 or later). (Evidence: `PushDeviceSwitch.test.tsx` (iOS text, unsupported text); `pushDevice.test.ts` with an iPhone user agent.)
- **What is sent** (texts carry no amount, no plot number, no name and no address, because they show on a locked
  phone):

  | Event | Who receives it | Text | Opens |
  |---|---|---|---|
  | Invoices for a month are issued | the owners of each plot with an invoice, who have push on | «Новий рахунок за {місяць}» | the invoices in «Мій кабінет» |
  | A payment is recorded, when the cooperative sends payment notices | the member who paid, when the payment names them | «Платіж зараховано» | the charges in «Мій кабінет» |
  | The member's reading is approved / rejected | the member who submitted it | «Показ лічильника прийнято» / «Показ лічильника відхилено» | the member's readings |
  | An announcement is sent | its recipients | «Нове оголошення від правління» | «Мій кабінет» |
  | A member submits a reading | users who approve readings | «Нові покази на перевірку», at most one per hour per user | «Перевірка показів» |

  - **Acceptance Criteria:**
    - [x] When the chairman issues invoices for a month, then every owner of a plot with an invoice who has push on
      receives «Новий рахунок за {місяць}», and tapping it opens their invoices. (Evidence: `InvoicePushTests`, `PushJobTests`; `push.spec.ts` "issuing an invoice sends one encrypted push"; `member-pwa-acceptance.spec.ts` test 1 (uk text and path).)
    - [x] Given the cooperative sends payment notices, when the treasurer records a payment naming a member, then that
      member receives «Платіж зараховано»; when the cooperative does not send payment notices, then nothing is sent. (Evidence: `PushEventsTests.aRecordedPaymentPushesItsPayerOnlyOncePerDevice`, `nothingIsPushedWhileTheCooperativeSendsNoPaymentNotices...`; `push-events.spec.ts` payment test.)
    - [x] When the board approves or rejects a member's reading, then the member who submitted it receives «Показ
      лічильника прийнято» or «Показ лічильника відхилено», and tapping it opens their readings. (Evidence: `PushEventsTests.theSubmitterIsToldOfTheBoardsDecision`; `push-events.spec.ts` "approving the reading..." (opens the member's readings).)
    - [x] When the board sends an announcement, then each recipient with push on receives «Нове оголошення від
      правління». (Evidence: `PushEventsTests.everyRecipientOfAnAnnouncement...`; `push-events.spec.ts` announcement test.)
    - [x] When members submit three readings within an hour, then each user who approves readings receives «Нові
      покази на перевірку» once in that hour, and tapping it opens «Перевірка показів». (Evidence: `PushEventsTests.theApproversExceptTheSubmitter...`, `twoSubmissionsAtOnceClaimTheThrottleOnce`; `push-events.spec.ts` (one per hour; opens «Перевірка показів»).)
    - [x] When any of these notifications arrives, then its text contains no amount, no plot number, no name and no
      address. (Evidence: `PushPayloadTests` (known fields only); `push.spec.ts` (decrypted JSON has no name, email, plot number); `pushPayload.test.ts`; `member-pwa-acceptance.spec.ts` test 1.)
- **Sending never blocks the board's work.** A device that no longer exists stops receiving notifications and is
  forgotten; other failures are retried later and never stop the action that caused them.
  - **Acceptance Criteria:**
    - [x] Given a member's device was reset and its subscription no longer exists, when the next notification is sent,
      then that device is forgotten and is not tried again. (Evidence: `InvoicePushTests.aDeviceThatIsGone...`; `PushOutcomeTests`; `push.spec.ts` "a device the push service reports gone is dropped".)
    - [x] Given the push service is unreachable, when the chairman issues invoices, then the invoices are issued as usual
      and the notifications are sent once the service is back. (Evidence: `InvoicePushTests.anOutageIsRetried...`, `fiveRefusalsInARow...`; the invoices are issued first and the sending is a separate job.)
- **Access and privacy.** A user sees and turns off only their own devices; other users and other cooperatives see
  nothing. The device address is never shown to anyone. Turning push on or off is not in the audit log.
  - **Acceptance Criteria:**
    - [x] When a user tries to see or turn off another user's device, then they are refused as if it did not exist; the
      same for a user of another cooperative. (Evidence: `PushDevicesTests.anotherMembersAndAnotherCooperativesDevicesAreNotFound`; `PushTenantIsolationTests`; `push.spec.ts` "another member gets 404".)
- **Leaving the cooperative.** Archiving a member or turning off their access removes all their devices.
  - **Acceptance Criteria:**
    - [x] Given a member has push on two devices, when the chairman archives them or turns off their access, then
      neither device receives any further notification. (Evidence: `PushEventsTests.archivingAMemberOrDisablingTheirAccessRemovesEveryDevice`; `push-events.spec.ts` ("turned off by ..." variants).)

### 2.3 A photo of the meter with a reading

- **Attaching a photo.** When submitting a reading, the member can attach one photo: «Додати фото лічильника». On a
  phone this opens the camera or the gallery. The electrician and the board can attach one too when entering
  readings.
  - **Acceptance Criteria:**
    - [x] When a member submits a reading with a photo from the camera or the gallery, then the reading is saved with
      the photo, and the reading shows a thumbnail of it. (Evidence: `meter-photo.spec.ts` smoke "the member submits a reading with a photo on a phone..."; `ReadingPhotoUploadTests`; `ReadingPhotoThumb.test.tsx`; `docs/screenshots/021-member-pwa-reading-dialog-with-photo.png`, `docs/screenshots/021-member-pwa-reading-submitted.png`.)
    - [x] When the electrician or the chairman enters a reading with a photo on the readings screen, then the reading
      is saved with the photo. (Evidence: `meter-photo-rule.spec.ts` "the electrician saves a reading without a photo and with one through «Add a photo»"; `ReadingEntryPhotoTests`; `docs/screenshots/021-member-pwa-readings-round-row-photo.png`.)
- **Files as for expense receipts** (feature 018): JPEG, PNG and HEIC photos (an iPhone photo), the same size limit and
  the same error messages; the photo is reduced on the phone to at most 2 000 pixels on the long side before it is
  sent, so it uploads fast.
  - **Acceptance Criteria:**
    - [x] When a member picks a file that is not JPEG, PNG or HEIC, or a file over the size limit, then they see the
      same messages as for expense receipts and the reading is not saved. (Evidence: `meter-photo.spec.ts` "a file that is not a photo..."; `ReadingPhotoUploadTests`; `FileSnifferTests`.)
    - [x] When a member attaches a 4 000-pixel photo, then the saved photo is at most 2 000 pixels on its long side. (Evidence: `meter-photo.spec.ts` "a 4000 px photo is stored at no more than 2000 px"; `normalizeImage.test.ts`.)
- **The photo rule.** A new cooperative setting «Фото показу обов'язкове для членів», off by default. When it is on, a
  member's reading without a photo is refused. The board and the electrician never need a photo.
  - **Acceptance Criteria:**
    - [x] Given the setting is on, when a member submits a reading without a photo, then they see «Додайте фото
      лічильника: правління просить фото до кожного показу.» and the reading is not saved. (Evidence: `meter-photo-rule.spec.ts` (member refused in the dialog; API `READING_PHOTO_REQUIRED`); `ReadingPhotoRuleTests`; `docs/screenshots/021-member-pwa-reading-photo-rule-error.png`, `docs/screenshots/021-member-pwa-settings-photo-rule.png`.)
    - [x] Given the setting is on, when the electrician enters a reading without a photo, then it is saved. (Evidence: `meter-photo-rule.spec.ts` electrician test; `ReadingPhotoRuleTests`.)
    - [x] Given the setting is off, when a member submits a reading without a photo, then it is saved. (Evidence: `meter-photo-rule.spec.ts` "rule off: a member saves a reading without a photo".)
- **Approving with the photo.** «Перевірка показів» shows the thumbnail next to each pending reading; a tap opens the
  full photo, so the board compares the digits before approving.
  - **Acceptance Criteria:**
    - [x] Given a pending reading has a photo, when the chairman opens «Перевірка показів», then the thumbnail is next
      to the reading, and a tap opens the photo full size. (Evidence: `meter-photo.spec.ts` smoke; `ApprovalsPage.test.tsx`; `docs/screenshots/021-member-pwa-approvals-thumbnail.png`, `docs/screenshots/021-member-pwa-approvals-photo-viewer.png`.)
- **Who can open a photo:** the owners of the plot, and users who enter or approve readings. Everyone else, and every
  user of another cooperative, is refused as if the photo did not exist.
  - **Acceptance Criteria:**
    - [x] When an owner of the plot, the electrician or the chairman opens the photo, then it opens. (Evidence: `ReadingPhotoAccessTests` (owner, electrician, approver, chairman allowed).)
    - [x] When a member who does not own the plot, or a user of another cooperative, tries to open it, then they are
      refused as if it did not exist. (Evidence: `ReadingPhotoAccessTests` (other member, other cooperative: 404); `meter-photo.spec.ts` "another person gets not found for the photo".)
- **The photo stays with the reading.** A superseded or rejected reading keeps its photo; a photo cannot be deleted; a
  wrong photo is replaced by submitting the reading again. The reading's audit entry says whether it had a photo,
  never the file itself.
  - **Acceptance Criteria:**
    - [x] Given a reading with a photo was rejected, when the board opens it in the history, then the photo is still
      there, and there is no delete action. (Evidence: `ReadingPhotoAccessTests.aRejectedReadingKeepsItsPhoto`, `aSupersededReadingKeepsItsPhoto`; `ReadingPhotoTests` (append-only guard, no delete action in the API).)
    - [x] When a reading with a photo is submitted, then its audit entry shows «фото: так». (Evidence: `ReadingAuditTests.aMemberSubmissionWritesItsDateValueAndPhoto...`; `auditValue.meters.test.tsx`; uk catalog `catalogs.test.ts`.)

### 2.4 Phone layouts and paying from the phone

- These screens work at 360 pixels wide without sideways scrolling, with buttons and links at least 44 pixels tall:
  «Мій кабінет» and all its blocks; the member's readings and the photo step; the member's invoices; «Перевірка
  показів» and the readings entry; «Новий платіж»; the help panel.
  - **Acceptance Criteria:**
    - [x] When each of these screens is opened on a 360-pixel-wide phone, then nothing scrolls sideways and every
      button is at least 44 pixels tall. (Evidence: `account.phone.spec.ts`, `screens.phone.spec.ts`, `pay-from-phone.spec.ts` (no side scroll, controls at least 44 px on every listed screen at 360 px); the on-screen keyboard is not part of the criterion.)
- **Paying from the phone.** Next to each invoice in «Мій кабінет»:
  - «Сплатити в банку» opens the invoice's NBU payment link: on a phone the phone offers the bank apps; on a computer
    the page with the QR code opens;
  - «Скопіювати IBAN» and «Скопіювати призначення» copy the payment details, with a short confirmation.
  - **Acceptance Criteria:**
    - [x] When a member taps «Сплатити в банку» next to an invoice, then the NBU payment link of that invoice opens. (Evidence: `pay-from-phone.spec.ts` "the member pays from an invoice" (@smoke: href is the invoice's NBU link, new tab); `InvoicePaymentActions.test.tsx`; `docs/screenshots/021-member-pwa-invoice-pay-in-bank.png`.)
    - [x] When the member taps «Скопіювати IBAN», then the cooperative's IBAN is copied and «Скопійовано» appears
      briefly; the same for «Скопіювати призначення» with the payment purpose of the invoice. (Evidence: `pay-from-phone.spec.ts` (clipboard holds the IBAN and the purpose, «Скопійовано» shown); `CopyButton.test.tsx`; `docs/screenshots/021-member-pwa-invoice-copy-confirmation.png`.)
    - [x] Given the cooperative has no IBAN, when the member looks at an invoice, then the payment buttons are not
      shown. (Evidence: `pay-from-phone.spec.ts` "an invoice without payment details shows no payment buttons"; `MyInvoicesPaymentTests`.)

### 2.5 Help and guides

- New help pages in Ukrainian and English, with phone screenshots: «Встановити на телефон» (Android and iPhone, with
  the iOS version note) and «Сповіщення на телефоні» (turning on and off, what is sent, why there are no amounts).
- Updated pages: the account, the member's readings and readings approvals (the photo), the settings (the photo rule),
  the invoice («Сплатити в банку»), «Початок роботи» for a member.
- The owner manual: the push keys (how to create them, where they go on the server and on the stand, that changing
  them turns push off on every device until members turn it on again); a secure (HTTPS) address is required for
  installing and for push.
  - **Acceptance Criteria:**
    - [x] When a member opens the help on the phone, then the two new pages are there in their language with phone
      screenshots, and the help completeness check passes. (Evidence: `helpContent.test.ts` (completeness, both languages), `ownerManual.test.ts`, `HelpPageView.test.tsx`; `screens.phone.spec.ts` help panel on a phone; `docs/screenshots/021-member-pwa-help-panel-account-with-install-link.png`, `docs/screenshots/021-member-pwa-help-install-page.png`.)

### 2.6 Removing the native app shell

- The decision is written down (ADR 0021 «Phone access as a PWA»): it replaces the mobile part of the earlier client
  decision, gives the reasons (one code base, no stores, the cooperative's budget), the limits accepted (on an iPhone
  push only in the installed app; no reading sync in the background) and what would bring native apps back.
- The native app shell and its tooling are removed; the security exceptions accepted only for it are removed too; the
  dependency audit and the vulnerability scan pass without them.
- The text for the files only the repository owner changes (the agent rules and the CI workflow) is given in the pull
  request.
  - **Acceptance Criteria:**
    - [?] When the dependency audit and the vulnerability scan run after the removal, then both pass with no exception
      whose reason is the native app. (Manual check: the exception entries for the native app are absent (`mobile/`, `osv-scanner.toml`, `pnpm-workspace.yaml` checked statically, see qa-notes); the verdicts of `pnpm audit` and OSV-Scanner come from the CI supply-chain job (network), not run locally.)

### 2.7 Test stand fixes

- **Sign-in service start-up.** The stand set-up waits until the sign-in service's admin login really works: it
  retries every 5 seconds for up to 2 minutes, then stops with a clear message.
  - **Acceptance Criteria:**
    - [x] Given the sign-in service refuses the admin login for the first 30 seconds, when the stand set-up runs, then
      it waits and completes. (Evidence: `infra/stand/tests/stand-init.test.sh` (`make stand-test`, green).)
    - [x] Given the admin login never works, when 2 minutes pass, then the set-up stops with a message saying the
      sign-in service admin login is not available. (Evidence: `infra/stand/tests/stand-init.test.sh` (stops after 2 minutes with the message).)
- **Stand notes.** The stand guide warns never to remove all volumes (it would delete the HTTPS certificates, of which
  only 5 can be issued per week), gives the command to reset only the data, and says the cooperative's mailbox settings
  must be entered again after a data reset.
- **Push keys on the stand** are created once when missing and kept across later runs.
  - **Acceptance Criteria:**
    - [x] Given the stand has no push keys, when the set-up runs twice, then keys are created on the first run and the
      same keys are kept on the second. (Evidence: `infra/stand/tests/stand-init.test.sh` (keys created on the first run, kept on the second).)

---

## 3. Scope and Boundaries

### In-Scope

- Installable web app, offline message, update bar, install offers (Android/desktop Chrome or Edge, iPhone steps).
- Push notifications per device for the five events of 2.2, with a fake push service for development and tests.
- One meter photo per reading, the photo rule setting, thumbnails in approvals, access rules.
- Phone layouts of the listed screens; «Сплатити в банку» and the copy buttons.
- Help pages and owner manual updates.
- ADR 0021 and removal of the native app shell with its security exceptions.
- The three stand fixes.

### Out-of-Scope

- Native apps and store publishing.
- Online card payment and bank APIs (phase 8).
- Recognising the digits on a meter photo (phase 8).
- Offline data or offline submission of readings.
- SMS and Viber.
- Push to the board for events other than new readings to check.
- A member screen for reading announcements (an announcement push opens «Мій кабінет»).

---

## 4. Assumptions & Decisions

The owner's decisions in the source document stand as written. The pipeline ran with `questions: blocking` and found
no blocking question; the details the document leaves open were decided as follows (`AUTO`, see `pipeline-log.md`):

- Built from scratch: no earlier work on any branch.
- The payment push goes to the member named as the payer of the payment; a payment that names no member sends no
  push. It follows the cooperative's existing «payment notices» setting.
- The announcement push opens «Мій кабінет»: members have no announcement screen, and adding one is out of scope.
- «The invoice view» is the invoice in the member's invoice list in «Мій кабінет» (there is no separate invoice
  screen); the payment buttons are added there.
- Meter photos are kept like expense receipts (feature 018).
- The message for the photo rule is «Додайте фото лічильника: правління просить фото до кожного показу.» (English:
  "Add a photo of the meter: the board asks for a photo with every reading.").
- The browser's installability is proved locally by an automated check of the app's install information and its
  offline worker on a production build; a run of the Lighthouse tool and the iPhone behaviour (install card, push in
  the installed app) are checked by hand on a real phone.
- Push encryption is built without a new server library; the web uses a maintained open-source plugin for the offline
  worker, recorded in ADR 0021.
- The stand start-up retry is proved by a script test that imitates a sign-in service refusing the first logins.

---

## Change Log

_Dated amendments made after the spec was first written. Leave empty until the first amendment._
