# QA notes — 021 member PWA

Traceability of every acceptance criterion of `functional-spec.md` section 2 and of `docs/features/021-member-pwa.md`
section 9 to the test that proves it. E2E specs live in `web/e2e/`; `pwa/pwa.spec.ts` runs through `make e2e-pwa`
(production build), `*.phone.spec.ts` in the Playwright `phone` project, the rest in the `chromium` project.
Backend tests are in `backend/src/test/java/app/nadel/...` (run by `./mvnw verify`, the orchestrator's job).

## Traceability

### 2.1 Installing the app

| Criterion | Proof |
|---|---|
| Installable (manifest, icons, SW with scope `/`) | `pwa/pwa.spec.ts`: manifest and icons match what the browser needs; SW becomes active. Real installability verdict: see "Not automatable" |
| Same screens and data when installed | The installed app is the same SPA; `pwa/pwa.spec.ts` online flow plus every phone spec. Standalone window itself: not automatable |
| No data cached (account, invoice PDF, meter photo) | `pwa/pwa.spec.ts` "after a member used the app the browser caches hold only static files"; `web/src/sw.contract.test.ts` (no runtime caching, one navigation route, API/Keycloak/files outside the fallback) |
| Offline message, no member data | `pwa/pwa.spec.ts` offline tests; `OfflineBanner.test.tsx` |
| Help opens offline | `pwa/pwa.spec.ts` "the public help opens with no connection" and "the help panel still opens" |
| Connection back clears the message and loads data | `pwa/pwa.spec.ts` "the connection brings the data back"; `OfflineBanner.test.tsx` |
| Update bar «Оновити», waits for a dirty form, never reloads by itself | `pwa/pwa.spec.ts` "the update bar"; `registerPwa.test.tsx`, `dirtyForms.test.tsx` |
| Android/desktop install offer, iPhone steps card, «Не показувати» sticks, nothing when installed | `pwa/pwa.spec.ts` "the install offer" (Chrome prompt, dismiss, Safari/Chrome on iPhone); `installPrompt.test.ts` |
| Caches cleared on sign-out | `useSignOut.test.ts` (keeps the precache, deletes every other cache, signs out even when clean-up fails or hangs); `pwa.spec.ts` cache test |

### 2.2 Push

| Criterion | Proof |
|---|---|
| No keys: no push block, server starts | `PushOffTests.withoutKeysPushIsOffAndTheRestWorks`; `PushDeviceSwitch.test.tsx` "renders nothing when the server has push disabled" |
| Switch on/off per device; other devices keep getting them | `PushDevicesTests` (register, list, delete own, ten newest); `push.spec.ts` "registered and listed without its address", "deletes a device of their own"; `PushDeviceSwitch.test.tsx`; `pushDevice.test.ts` |
| Permission refused: switch off with explanation; iPhone-Safari text; unsupported text | `PushDeviceSwitch.test.tsx` (blocked, iOS, unsupported); `push.spec.ts` "renders without a service worker and does not crash" |
| INVOICES_ISSUED «Новий рахунок за …» to every owner with push on | `InvoicePushTests`, `PushJobTests`; `push.spec.ts` "issuing an invoice sends one encrypted push"; NEW `member-pwa-acceptance.spec.ts` test 1 (uk device, decrypted, text the SW shows through `buildPushView`); `pushPayload.test.ts` |
| PAYMENT_RECORDED (only while payment notices are on) | `PushEventsTests.aRecordedPaymentPushesItsPayerOnlyOncePerDevice`, `nothingIsPushedWhileTheCooperativeSendsNoPaymentNotices...`; `push-events.spec.ts` payment test |
| Reading approved/rejected | `PushEventsTests.theSubmitterIsToldOfTheBoardsDecision`; `push-events.spec.ts` "approving the reading..." |
| ANNOUNCEMENT | `PushEventsTests.everyRecipientOfAnAnnouncement...`; `push-events.spec.ts` announcement test |
| READINGS_TO_APPROVE, one per hour, submitter excluded | `PushEventsTests.theApproversExceptTheSubmitter...`, `twoSubmissionsAtOnceClaimTheThrottleOnce`; `push-events.spec.ts` (one then none within the hour; board owner not told of own reading) |
| No amount, plot number, name or text in a push | `PushPayloadTests` (known fields only, no digits but the month); `push.spec.ts` (decrypted JSON checked for name, email, plot number); NEW acceptance test 1; `pushPayload.test.ts` |
| 404/410 deletes the subscription; retries on an outage; invoices issue when the push service is down | `InvoicePushTests.aDeviceThatIsGone...`, `anOutageIsRetried...`, `fiveRefusalsInARow...`; `PushOutcomeTests`; `push.spec.ts` "a device the push service reports gone is dropped" |
| Another user cannot see or delete a device (404) | `PushDevicesTests.anotherMembersAndAnotherCooperativesDevicesAreNotFound`, `thePlatformOperatorIsRefused`; `push.spec.ts` "another member gets 404"; "a call without a token is refused" |
| RLS between cooperatives | `PushTenantIsolationTests` (RLS, policy, tenant index, cross-tenant insert/update/delete, composite FKs) |
| Archiving a member or turning off access removes the devices | `PushEventsTests.archivingAMemberOrDisablingTheirAccessRemovesEveryDevice`, `aJobQueuedBeforeTheMemberLeft...`; `push-events.spec.ts` (two variants "turned off by ...") |
| Other cooperative gets nothing | `push-events.spec.ts` "the member of another cooperative gets nothing" |
| Push link opens the right place (path) | `push-events.spec.ts` "opens scrolled to «My meters»"; NEW acceptance test 2 (path of the push opened on a phone shows the invoice) |
| No personal data in logs | `InvoicePushTests.theLogsCarryTheCooperativeTheMemberTheCodeAndTheOutcome`, `PushEventsTests.theLogsCarryNoEndpointKeyOrName` |
| Address policy (https, known push services), keys validated, sealed storage | `PushEndpointPolicyTests`, `PushDevicesTests.theEndpointAndTheKeysAreSealed...`, `badInputIsRefusedWithTheField`; `push.spec.ts` "registering refuses..."; Web Push crypto: `WebPushEncryptorTests`, `VapidSignerTests`, `VapidKeysTests`, `WebPushClientTests`; `support/fakePush.ts` decrypts independently (RFC 8291) |

### 2.3 Meter photo

| Criterion | Proof |
|---|---|
| Member submits a reading with a photo; thumbnail shown | `meter-photo.spec.ts` "the member submits a reading with a photo on a phone and the chairman sees it and approves" (`@smoke`, required flow (a) verbatim); `ReadingPhotoUploadTests`; `ReadingPhotoThumb.test.tsx`, `SubmitReadingDialog.test.tsx` |
| Electrician or chairman enters a reading with a photo | `meter-photo-rule.spec.ts` "the electrician saves a reading without a photo and with one through «Add a photo»"; `ReadingEntryPhotoTests` |
| Not JPEG/PNG/HEIC or over the size limit: error, nothing saved | `meter-photo.spec.ts` "a file that is not a photo..." (API `FILE_TYPE_NOT_ALLOWED` and dialog error); `ReadingPhotoUploadTests` (PDF, unreadable, over 5 MB, HEIC not converted, content not name); `FileSnifferTests` |
| 4000 px photo stored at most 2000 px | `meter-photo.spec.ts` "a 4000 px photo is stored at no more than 2000 px"; `normalizeImage.test.ts` |
| HEIC converted on the client | `heicToJpeg.test.ts`, `normalizeImage.test.ts` (real iPhone HEIC: not automatable) |
| Metadata (EXIF/GPS) removed | `JpegMetadataStripperTests`, `PngMetadataStripperTests`, `ReadingPhotoUploadTests.aCameraJpegIsStoredWithItsMetadataRemoved` |
| Rule on: member refused without a photo (`READING_PHOTO_REQUIRED`); electrician/board saved without; rule off: saved | `meter-photo-rule.spec.ts` (four tests incl. API refusal and the chairman turning the rule on in Settings); `ReadingPhotoRuleTests`; `SettingsPage.test.tsx`, `SettingsTests` |
| Thumbnail beside the pending reading; opens the viewer | `meter-photo.spec.ts` smoke; `screens.phone.spec.ts` "«Readings to approve» with a photo fits the phone"; `ReadingPhotoThumb.test.tsx`; `ApprovalsPage.test.tsx` |
| Owner, electrician, approver, chairman open the photo; another member and another cooperative get 404 | `ReadingPhotoAccessTests` (allowed, denied, other cooperative, platform operator 403, anonymous 401, former owner); `meter-photo.spec.ts` "another person gets not found for the photo, even a member of the same cooperative" |
| Rejected/superseded/approved reading keeps its photo | `ReadingPhotoAccessTests.aRejectedReadingKeepsItsPhoto`, `aSupersededReadingKeepsItsPhoto`, `anApprovedReadingKeepsItsPhoto` |
| Audit entry shows «фото: так» | `ReadingAuditTests.aMemberSubmissionWritesItsDateValueAndPhoto...`, `ReadingPhotoUploadTests.theAuditEntrySaysWhetherAPhotoCame...`; `auditValue.meters.test.tsx`; uk catalog `catalogs.test.ts` |
| Table RLS, append-only guard | `ReadingPhotoTests` |

### 2.4 Phone layouts and paying

| Criterion | Proof |
|---|---|
| Every listed screen at 360 px: no side scroll, controls at least 44 px | `account.phone.spec.ts` (member and chairman account, readings dialog with the photo step, invoices); `screens.phone.spec.ts` (approvals with a photo, electrician round, «New payment», help panel); `pay-from-phone.spec.ts` |
| «Сплатити в банку» opens the NBU link | `pay-from-phone.spec.ts` "the member pays from an invoice" (`@smoke`: href is the invoice's NBU link, new tab, `noopener`); NEW acceptance test 2 (required flow (c): after a push path opens, the invoice shows «Pay in the bank app» on a phone); `InvoicePaymentActions.test.tsx` |
| Copy IBAN / purpose with «Скопійовано» | `pay-from-phone.spec.ts` (clipboard content); `CopyButton.test.tsx` |
| No IBAN: no payment buttons | `pay-from-phone.spec.ts` "an invoice without payment details shows no payment buttons"; `MyInvoicesPaymentTests`; `InvoicePaymentActions.test.tsx` |

### 2.5 Help

| Criterion | Proof |
|---|---|
| Two new pages (`member-install`, `member-push`) in uk and en with phone shots; completeness check | `web/src/help/helpContent.test.ts` (completeness), `ownerManual.test.ts`, `HelpPageView.test.tsx`; `screens.phone.spec.ts` help panel on the phone; `catalogs.test.ts` |

### 2.6 Removing the native shell

| Criterion | Proof |
|---|---|
| `mobile/` removed, ADR 0021, no native exceptions in the audit config | Static checks (this slice): `mobile/` absent, `docs/adr/0021-phone-access-as-a-pwa.md` present, no `mobile`/`expo` in `osv-scanner.toml` and `pnpm-workspace.yaml`. `pnpm audit` and OSV-Scanner run in CI only (supply-chain job, network) |
| Owner's `CLAUDE.md` and CI text in the PR | `context/spec/021-member-pwa/owner-proposals.md` (documentation, not a test) |

### 2.7 Stand

| Criterion | Proof |
|---|---|
| Sign-in service refuses the admin login for the first 30 s: set-up waits and completes | `infra/stand/tests/stand-init.test.sh` (stub docker, run by `make stand-test`, part of `make check`) |
| Never works: stops after 2 minutes with the message | same script |
| Push keys created once, kept on the second run | same script |
| Stand README notes | Documentation; `infra/stand/README.md` |

### Requirement section 9, e2e

| Required flow | Proof |
|---|---|
| (a) member on a phone submits a reading with a photo, chairman sees the thumbnail and approves (`@smoke`) | `meter-photo.spec.ts` (verbatim, `@smoke`) |
| (b) member turns push on, chairman issues an invoice, fake push service receives «Новий рахунок за …» | `push.spec.ts` (API level, English device); NEW `member-pwa-acceptance.spec.ts` test 1 closes the gap: Ukrainian device, decrypted INVOICES_ISSUED plus month, and the exact text `buildPushView` gives the service worker |
| (c) member opens an invoice on a phone and sees «Сплатити в банку» | `pay-from-phone.spec.ts` (`@smoke`); NEW acceptance test 2 reaches it through the push path |

## Gaps found and tests added

`web/e2e/member-pwa-acceptance.spec.ts` (`@layer: e2e`, `@spec: 021-member-pwa`, `@regression`), two tests:

1. Ukrainian push device, chairman issues an invoice, the decrypted message is INVOICES_ISSUED with the month, and
   `buildPushView` (the code the service worker runs) gives «Новий рахунок за <місяць>» with no digit, no plot number.
   RED: changing the uk text in `packages/shared/src/i18n/pushTexts.ts` fails with `Expected "Новий рахунок за листопад"`,
   `Received "Рахунки за листопад"`.
2. The path a push carries is opened on a 360 px phone: the invoice list shows «Pay in the bank app» and nothing
   scrolls sideways. RED: hiding the bank link in `InvoicePaymentActions.tsx` fails with "element(s) not found".

Flows (a) and (c) are covered verbatim by the two existing `@smoke` specs, so they were not duplicated.

## Not automatable locally

| What | Why | How to check by hand |
|---|---|---|
| Real browser push: subscribe with the browser's push service and show the notification | Headless Chromium cannot subscribe (no push service, no permission prompt); the stack uses synthetic subscriptions and the fake push service. The SW's `push` handler is covered by `sw.contract.test.ts` and `pushPayload.test.ts` only as code and as text | On the stand over HTTPS: turn the switch on in Chrome on Android, issue an invoice, see the notification and tap it |
| Push to an installed iOS app; the iOS install card on a real iPhone | Needs iOS 16.4+ Safari, "Add to Home Screen", real APNs-backed Web Push. The card text and the Safari/Chrome-on-iPhone decision are unit/e2e tested with a user-agent override only | iPhone: Safari, open the account, follow the card, install, open from the icon, turn push on |
| Android install dialog and the installed window (no address bar, own icon) | The browser's own UI; Playwright can only fire `beforeinstallprompt` | Chrome on Android over HTTPS: install, open from the icon |
| Lighthouse or DevTools installability verdict | Requires HTTPS on a real origin (localhost is exempt but the stand check is the real one) and Lighthouse is not a project dependency; the manifest and icon checks in `pwa.spec.ts` assert the individual rules | DevTools, Application, Manifest on the stand; or Lighthouse on the HTTPS stand |
| On-screen keyboard over the reading dialog | Headless Chromium has no software keyboard; layout at 360x740 is tested, the shrunken visual viewport is not | A real phone: open the readings dialog, focus the value field, check the buttons stay reachable |
| A real push from the stand | Needs real VAPID keys on the stand, HTTPS and a device | Stand: `stand-init.sh` creates the keys once; subscribe a phone, issue invoices |
| Real HEIC from an iPhone camera | The HEIC decode path is unit-tested with mocks | iPhone: submit a reading with a camera photo |
| `pnpm audit` / OSV-Scanner verdicts, gitleaks | Network scanners run by CI | CI supply-chain jobs |
