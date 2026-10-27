# Functional Specification: Personal data encryption

- **Topic:** Members' contacts and notes are kept encrypted, so a copy or leak of the stored data reveals no phone
  numbers, e-mails, addresses, messenger handles or notes. Search, sign-in, import, reports and the audit log keep
  working, with search by phone and e-mail narrowed to exact matches. Three small fixes ride along: plots with a water
  connection cannot be archived or turned into common land, and three unreliable end-to-end checks are made reliable.
- **Status:** Implemented — pending manual check
- **Author:** Alex Usov
- **Source:** `docs/features/014-personal-data-encryption.md` (cross-cutting, between roadmap phases 2 and 3)

---

## 1. Overview and Rationale (The "Why")

The cooperative's board keeps members' phone numbers, e-mails, postal addresses, Telegram and Viber contacts and free
notes in the app. Today anyone who obtains a copy of the stored data (a backup left on a laptop, a stolen dump, a
read-only leak) reads all of them. Phase 3 will add bank statements and payers, which makes the stored data more
valuable still. Encryption has to be in place before that, so that payment data is protected from its first day.

After this feature, such a copy shows members' names but none of their contacts or notes. Each cooperative's contacts
are locked with that cooperative's own key, and every key is in turn locked by one master key that the platform owner
keeps only on the server and in an offline safe place, never next to the backups.

The board notices only two things: search by phone and e-mail now needs the full value (or the last 4 digits of a
phone), and someone allowed to read the audit log but not the member list sees personal values there as "hidden".

**Success looks like:** the chairman types the last 4 digits of a member's phone into the member search and finds the
member; a backup of the database contains no contact of any member in readable form; a server started with a wrong
master key refuses to start with a clear message instead of failing later on each screen.

---

## 2. Functional Requirements (The "What")

All texts are shown in the user's language only (Ukrainian by default, English when chosen). Names and anything typed
by users are shown as entered.

### 2.1 Contacts are stored encrypted

- Phones, e-mail, postal address, Telegram username, Viber phone and notes of every member are stored only in encrypted
  form, in the member list, in the audit log, in a pending Excel import and in the queue of messages between parts of
  the app. Names (last, first and middle) stay readable so that search by part of a surname and sorting by name keep
  working.
- An encrypted value is tied to its cooperative, its member and its field: copied anywhere else, it cannot be read.
- Each cooperative has its own key; the keys are themselves locked with the platform's master key.
- The app still never stores a tax number, passport data or a date of birth.
- The member card, the member form, the debtors report and invoices show contacts exactly as before.
  - **Acceptance Criteria:**
    - [x] Given a member created with a phone, an e-mail, an address, a Telegram username, a Viber phone and a note,
          when someone reads the stored member list, the stored audit log, a pending import and the message queue
          directly (bypassing the app), then none of those six values is found in readable form, while the member's
          name is. — verified: PersonalDataEncryptionAcceptanceTests (owner scan of member, audit_entry, import_run incl. plan_enc, event_publication), AuditPlaintextScanTests, ImportPlanEncryptionTests — full `make check` 2969/0
    - [x] Given an encrypted contact of one member, when it is copied onto another member, another field or a member
          of another cooperative, then the app cannot read it there and shows no other member's data. — verified: PersonalDataEncryptionAcceptanceTests.aSealedValueCopiedToAnotherMemberFieldOrCooperative… (500 PERSONAL_DATA_UNREADABLE, no leak), TenantKeysTests
    - [x] When the chairman opens a member card and the member form after this change, then every contact is shown
          exactly as it was entered, and saving the form without changes keeps them unchanged. — verified through the API: PersonalDataEncryptionAcceptanceTests.theCardShowsEveryContactAsEntered… (card and form screens unchanged in this feature; rendering pending, see below)
    - [x] When the treasurer opens the debtors report and downloads it, then the phones and e-mails of the debtors
          are shown as before. — verified: DebtorsReportTests 7/0 (sealed fixtures)

### 2.2 Member search

- The member search finds:
  - names by any part, ignoring case (as now);
  - an e-mail only by the whole address, ignoring case;
  - a phone by the whole number written in any usual way (`+380 50 123 45 67`, `0501234567`, `380501234567`), or by
    exactly its last 4 digits.
- Searching by a part of an e-mail or by digits in the middle of a phone number no longer finds anything.
- The hint in the search field says what can be searched: «ПІБ, повний телефон або останні 4 цифри, точний email»
  ("Full name, full phone or its last 4 digits, exact e-mail").
  - **Acceptance Criteria:**
    - [x] When the chairman types part of a member's surname in any letter case, then the member is found as before. — verified: MemberSearchTests, MembersReadTests
    - [x] When the chairman types the last 4 digits of a member's phone, then that member is found. — verified: MemberSearchTests, PersonalDataEncryptionAcceptanceTests (API); e2e members-search.spec.ts @smoke written, runs in CI
    - [x] When the chairman types the member's full phone as `+380 50 123 45 67`, as `0501234567` or as
          `380501234567`, then the member is found each time. — verified: MemberSearchTests (5 spellings), PersonalDataEncryptionAcceptanceTests
    - [x] When the chairman types the member's whole e-mail in capital letters, then the member is found; when they
          type only the part before `@`, then the member is not found by the e-mail. — verified: MemberSearchTests, PersonalDataEncryptionAcceptanceTests
    - [?] When the chairman opens the Members screen, then the search field shows the new hint in the selected
          language. — not rendered: the owner's running stack holds ports 8080/5173 and Keycloak redirects only to :5173; MembersPage.test.tsx (component) green; to confirm in the review run or CI e2e

### 2.3 Sign-in, e-mail uniqueness and Excel import keep working

- On a person's first sign-in, they are linked to the active member with the same e-mail, as now.
- Two active members of one cooperative still cannot share an e-mail; the board sees the same "e-mail already taken"
  message as now.
- The Excel import still recognises existing members by e-mail, and by name together with phone.
- A pending import (kept for 30 minutes before it is confirmed) holds contacts only in encrypted form; expired pending
  imports are still discarded.
  - **Acceptance Criteria:**
    - [x] Given an active member with an e-mail and a sign-in account with the same e-mail written in other letter
          case, when that person signs in for the first time, then they see their own member account. — verified: PersonalDataEncryptionAcceptanceTests.firstSignInIgnoresLetterCase…, MemberLinkingTests 13/0, DemoContactsCallbackTests (Chairman@Demo.Local)
    - [x] When the chairman saves a second active member with the e-mail of an existing active member, then they see
          the existing "e-mail already taken" message. — verified through the API: 409 EMAIL_TAKEN without echoing the e-mail (PersonalDataEncryptionAcceptanceTests); message text unchanged in i18n
    - [x] When the chairman imports an Excel file that contains an existing member's e-mail, or the member's name with
          the phone, then the preview shows the row as an update of that member, not as a new one. — verified: PersonalDataEncryptionAcceptanceTests (preview created=1, updated=2), MemberImportApplyTests

### 2.4 Audit log

- Changes of phones, e-mail, address, Telegram, Viber and notes are recorded as before.
- The old and new values of those fields are shown only to a reader who may also view members. Anyone else allowed to
  read the audit log sees that the field changed, with the word «приховано» ("hidden") instead of the values.
- Audit entries made before this feature are protected the same way.
  - **Acceptance Criteria:**
    - [x] Given the chairman changed a member's phone, when the chairman opens the audit log, then the old and new
          phone are shown. — verified through the API: AuditReadTests.personalValuesAreShownToTheChairmanAndTheTreasurer; rendering unchanged for non-hidden values
    - [?] Given a custom role that may read the audit log but may not view members, when a person with that role
          opens the audit log, then the phone change is listed with «приховано» instead of the values, and other
          fields (for example a name change) are shown as before. — API verified (AuditReadTests: hidden=true, no values) and AuditPage.test.tsx; rendering not done (ports held by the owner's stack); e2e privacy.spec.ts written, runs in CI with the full-e2e label

### 2.5 Keys and start-up (platform owner)

- The master key is a random 32-byte value written as base64 (generated with `openssl rand -base64 32`) and given to
  the server only through its environment. It is never stored in the database, the logs, the repository or a backup.
- In production, the server refuses to start without a master key or with a malformed one. The message names the
  missing setting but never shows a key.
- The server checks the master key at start-up. With a wrong key it refuses to start with the code
  `PD_MASTER_KEY_MISMATCH`; it never starts and then fails on each screen.
- **Rotation:** the owner sets the new key as the master key and the old one as the previous master key. At start-up the
  server re-locks every cooperative's key with the new master key and writes, in counts only, how many keys it moved.
  The owner then removes the previous key.
- On a local run, the start command creates a random master key in a local file that is never committed, the first
  time it is missing. Automated tests use a fresh random key each run.
- A new cooperative gets its key the first time it is needed; existing cooperatives get theirs during the upgrade.
  - **Acceptance Criteria:**
    - [x] When the server is started in production without a master key, or with one that is not 32 bytes of base64,
          then it does not start and the message names the missing setting without showing any key. — verified: PersonalDataStartupTests (prod without key / malformed key; message names the variable, no value)
    - [x] When the server is started with a master key that differs from the one used before, then it does not start
          and the log shows `PD_MASTER_KEY_MISMATCH`. — verified: PersonalDataStartupTests.aRotationFromAToB (PD_MASTER_KEY_MISMATCH, no row changed)
    - [x] Given the old key as the previous master key and a new master key, when the server starts, then it starts,
          every cooperative's contacts are readable, the log reports the number of re-locked keys, and a later start
          without the previous key also succeeds. — verified: PersonalDataStartupTests.aRotationFromAToB (2 re-wrapped, counts-only log, later start without previous key)
    - [?] When a developer runs the local start command on a fresh checkout, then a master key is created in the
          local, ignored file and the app starts; the secret scanner reports nothing. — `infra/.env` creation verified in a temp copy of the Makefile (mode 600, never overwritten) and DevMasterKeySourceTests; app start via `make up` not run here (owner's stack running); gitleaks not installed locally — CI security job runs it

### 2.6 Upgrade of existing data

- The upgrade encrypts the contacts of every existing member, the personal values in existing audit entries and any
  pending import, for every cooperative, and then removes the readable copies.
  - **Acceptance Criteria:**
    - [x] Given two cooperatives with members, audit entries and a pending import stored before the upgrade, when the
          upgrade runs, then afterwards every contact of both cooperatives is shown correctly in the app and none is
          left readable in storage. — verified: PersonalDataUpgradeAcceptanceTests (V20 → latest, two cooperatives, prod profile), EncryptMemberContactsMigrationTests, SealAuditValuesMigrationTests, SealImportPlansMigrationTests

### 2.7 Nothing personal in logs or error messages

- Creating, changing, searching and importing members and signing in write no phone, e-mail or note to the server logs.
- Error messages never repeat a contact value.
  - **Acceptance Criteria:**
    - [x] When a member is created, updated, searched by phone and e-mail, imported, and a person signs in, then the
          server logs contain none of the test phone numbers, e-mails or notes. — verified: PersonalDataEncryptionAcceptanceTests (OutputCaptureExtension over create, update, search, import, sign-in)

### 2.8 Plots with a water connection

- A plot that has a lasting water connection cannot be archived and cannot be turned into common land. The board sees
  a message that the plot has a water connection, next to the plot kind for a kind change, or as a general message for
  archiving.
  - **Acceptance Criteria:**
    - [x] Given a plot with a lasting water connection, when the chairman archives it, then the plot stays active and
          they see the "plot has a water connection" message. — verified through the API: WaterConnectionPlotGuardTests (409 PLOT_HAS_WATER_CONNECTION, plot stays active); message i18n uk/en added; on-screen rendering uses the existing problem mapping (not rendered here)
    - [x] Given a plot with a lasting water connection, when the chairman changes its kind to common land, then the
          change is refused with the message next to the kind. — verified: WaterConnectionPlotGuardTests (errors[{field: kind}]), PlotForm.test.tsx renders the message under the kind field
    - [x] Given a plot without a water connection, when the chairman archives it, then it is archived as before. — verified: WaterConnectionPlotGuardTests (no connection / ended connection archive)

### 2.9 Reliable end-to-end checks

- The meter reading check (a member submits a reading, the chairman approves it) removes its test plot whatever the
  outcome of the check, and a later run removes any test plot an earlier interrupted run left behind.
- The three ownership checks that failed on plot 9 in the full run of 27.10.2026 pass reliably; their cause is fixed,
  not hidden by a retry or a longer wait.
  - **Acceptance Criteria:**
    - [?] Given a meter reading check interrupted between the submission and the approval, when the end-to-end suite
          is run again, then no test plot is left active in the demo data afterwards. — clean-up rewritten (cleanupMeterPlot in finally and beforeAll sweep), reviewed; Playwright not run locally (ports held by the owner's stack) — CI full-e2e run
    - [?] When the full end-to-end suite runs, then the ownership checks about plot 9 pass without retries. — root cause fixed in the app (useDebouncedSearchParam, unit-tested) and specs wait for ?q=9; to confirm with the CI full-e2e run

### 2.10 Guides and docs

- The owner manual (Ukrainian and English) explains what is encrypted, the keys, generating a key, rotation, the
  warning "a lost master key means lost contacts", the `PD_MASTER_KEY_MISMATCH` troubleshooting entry, the local key,
  and the changed recipe for adding a cooperative.
- The board guide (Ukrainian and English) explains the new search and notes that contacts are stored encrypted, without
  mentioning other cooperatives or the platform.
  - **Acceptance Criteria:**
    - [x] When the owner opens either language of the owner manual, then the security chapter describes the keys,
          rotation and the lost-key warning, and troubleshooting has `PD_MASTER_KEY_MISMATCH`. — verified: grep — PD_MASTER_KEY_MISMATCH and NADEL_PD_MASTER_KEY_PREVIOUS in both owner manuals; slice 7 review PASS
    - [x] When a board member opens either language of the board guide, then the members chapter describes the new
          search rules. — verified: board guides en:144 / uk:145 search rules; slice 7 review PASS

---

## 3. Scope and Boundaries

### In-Scope

- Encryption of members' phones, e-mail, postal address, Telegram username, Viber phone and notes, in the member list,
  the audit log, pending imports and the message queue.
- Per-cooperative keys, the master key, start-up checks and master key rotation.
- Exact search by phone, last 4 digits and e-mail; the new search hint.
- Hidden personal values in the audit log for readers who may not view members.
- Upgrade of existing data; local and test key handling.
- The water connection guard, the meter check clean-up and the three ownership checks.
- Owner manual, board guide, module documentation, decision record and deployment notes.

### Out-of-Scope

- Encrypting names.
- Changing a cooperative's own key and re-encrypting all values.
- Deleting a cooperative's key on request (planned for phase 7).
- The e-mail copy kept by the sign-in service.
- Encrypting any other data (phase 3 uses the same mechanism for payers in its own spec).

---

## Assumptions & Decisions

- No question blocked the run; nothing was asked. The requirement document was agreed with the owner on 22.10.2026.
- AUTO: encryption is done inside the members part of the app, where each value's member is known (decision record 0016).
- AUTO: a new cooperative's key is created the first time it is needed, since cooperatives are added by the platform
  owner by hand; the owner manual's recipe for adding a cooperative changes accordingly (contacts are entered in the
  app, not by hand in the database).
- AUTO: the start-up key check, the re-locking during rotation and the encryption of the demo cooperative's contacts
  run with the owner's database access, one cooperative at a time, because the app's own access sees one cooperative
  only.
- AUTO: a production mode is added to the server for the "refuse to start without a key" rule; local and test runs use
  a generated key, never a committed one.
- AUTO: "the two flaky end-to-end tests" are the ownership checks that failed on plot 9 in the full run on 27.10.2026
  (one marked flaky, two failed twice); all three are fixed by their cause. They may share a cause with the meter
  check clean-up.
- AUTO: changes to the CI workflow files are not made by this run; the app must start in CI without a configured key
  through the local/test key fallback, and any wanted CI change is proposed in the pull request.
- The member card and form look the same; contacts entered with surrounding spaces or upper-case e-mail are normalised
  as today.

---

## Change Log

- (none)
