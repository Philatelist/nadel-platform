# Functional Specification: Ready for real use — inviting members, tariff fixes, small review items

- **Topic:** The board gives a member access to the system from the member card, without the platform owner; a tariff
  created by mistake can be deleted and a used tariff can be archived; the tariff, payment and expense screens stop
  confusing a non-technical treasurer; the review items of 009–019 and the end-to-end checks are tidied; the help is
  updated.
- **Status:** Completed
- **Author:** Alex Usov
- **Source:** `docs/features/020-go-live-polish.md`

---

## 1. Overview and Rationale (The "Why")

The cooperative «Надія» is about to work in the system for real. Three things stop it:

- A member gets access only when the platform owner creates a sign-in account by hand on the server. The board cannot
  let its own members in, and every new member waits for the platform owner.
- A tariff created by mistake cannot be removed. It stays in the list forever and confuses everyone who opens it.
- A few screens confuse a non-technical treasurer: the overlap warning on tariffs does not say why it matters, the new
  tariff form hides the charge types that are switched off, the payment form makes the treasurer pick the only owner
  of a plot by hand, and the allocation dialog wraps its total onto two lines.

The owner's reviews of features 009–019 also left small items: a duplicate file can be attached to an expense twice,
and some end-to-end checks leave changes behind or pass only on a retry.

Success means the chairman of «Надія» invites every member from the member card, members set their own password from
the letter and sign in, and the treasurer can clean up the tariff list and enter payments without asking for help.

---

## 2. Functional Requirements (The "What")

### 2.1 Inviting a member into the system

There is still no self-registration. A member gets access only when the board invites them.

**Who.** A new permission «Запрошувати в систему» lets a person invite members, resend the invitation and switch access
off. The Chairman always has it. Other roles get it only when the board adds it to them.

**The access block on the member card.** The member card shows the member's access status, one of:

- «Не запрошено» — the member has never been invited;
- «Запрошено {дата}, чекає на пароль» — the letter was sent, the member has not set a password yet;
- «Має доступ, останній вхід {дата}» — the member has set a password; the date is their last sign-in (or «ще не
  входив» before the first one);
- «Доступ вимкнено» — the board switched access off, or the member was archived.

Next to the status, a person with the invite permission sees the actions that fit the status:

- «Запросити в систему» when the status is «Не запрошено» or «Доступ вимкнено»;
- «Надіслати ще раз» when the status is «Запрошено…» (a new link; the previous link stops working);
- «Вимкнути доступ» when the status is «Запрошено…» or «Має доступ» (with a confirmation; the card and all the
  member's data stay).

People without the permission see the status but no actions. Members do not see this block on their own card.

**Delivery of the invitation.**

- A member with an e-mail gets a letter from the cooperative's own mailbox: «Задайте пароль», with a one-time link. The
  link works for 72 hours and only once.
- A member with no e-mail cannot sign in, because the e-mail is the login. The invite button is disabled with the reason
  «Додайте e-mail — він буде логіном». If that member has a linked Telegram, then after an e-mail is added the
  invitation is sent both by letter and by the bot, with the same link.
- When the cooperative has no mailbox set up, the invite button is disabled with the reason «Налаштуйте поштову
  скриньку кооперативу».

**Setting the password.** The link opens a page of the application, «Задайте пароль». The member sees their e-mail,
enters a new password twice and confirms. Then they see «Пароль задано» and a button «Увійти» that leads to the normal
sign-in. A link that was already used, was replaced by a newer one, has expired, or belongs to a member whose access
was switched off shows «Посилання недійсне або застаріло. Попросіть правління надіслати нове.» The password must meet
the sign-in rules (at least 8 characters); otherwise the page says what is wrong.

**Rules.**

- Archiving a member switches their access off. Restoring the member does not switch it on again; the board invites
  again.
- When the e-mail of a member with access (or an open invitation) changes, the sign-in login changes to the new e-mail,
  the new address must be confirmed, and the member gets a letter to the new address with a new link.
- Co-owners are invited one by one, each with their own e-mail.
- An e-mail already used to sign in to another cooperative is refused with «Цей e-mail уже використовується в іншому
  кооперативі», without saying which cooperative.
- A sign-in account that already exists for this cooperative with the same e-mail is linked to the member, not
  duplicated.
- **The first chairman of a new cooperative** is invited by the platform owner with a server command, using the same
  invitation. Because the new cooperative has no mailbox yet, the command shows the one-time link once on the platform
  owner's screen, who passes it to the chairman.
- **Audit.** «Запрошено в систему», «Запрошення надіслано повторно», «Доступ вимкнено». The audit never contains the link
  or a password, and neither do the logs.
- **Demo.** The invitation works against the local sign-in service, and letters arrive in the local mailbox viewer.

**Acceptance criteria**

- [x] Given a member with an e-mail and status «Не запрошено», when the chairman clicks «Запросити в систему», then the
      status becomes «Запрошено {сьогодні}, чекає на пароль» and a letter «Задайте пароль» arrives at the member's
      e-mail.
      _Verified (verify run 2026-11-06): Verified live (Ukrainian UI, local Keycloak + Mailpit): card shows «Запрошено 06.11.2026, 15:52, чекає на пароль»; Mailpit letter subject «Задайте пароль — СТ «Надія»». Screenshots `docs/screenshots/020-go-live-polish-access-not-invited.png`, `docs/screenshots/020-go-live-polish-access-invited.png`._
- [x] When the member opens the link from the letter, enters a valid password twice and confirms, then they see «Пароль
      задано», can sign in with the e-mail and password, and the card shows «Має доступ, останній вхід {дата}».
      _Verified (verify run 2026-11-06): Verified live: set-password form (`docs/screenshots/020-go-live-polish-setpassword-form.png`; 5-character password keeps the button disabled, `docs/screenshots/020-go-live-polish-setpassword-too-short.png`), «Пароль задано» (`docs/screenshots/020-go-live-polish-setpassword-done.png`), sign-in at Keycloak succeeds (`docs/screenshots/020-go-live-polish-member-signed-in.png`), card «Має доступ, останній вхід 06.11.2026, 15:56» (`docs/screenshots/020-go-live-polish-access-active.png`). The used link then shows the invalid text._
- [x] Given an invited member, when the chairman clicks «Надіслати ще раз», then a new letter arrives, and when the member
      opens the old link, then they see «Посилання недійсне або застаріло. Попросіть правління надіслати нове.»
      _Verified (verify run 2026-11-06): Verified live: after «Надіслати ще раз» 2 letters in Mailpit; old link shows «Посилання недійсне або застаріло…» (`docs/screenshots/020-go-live-polish-setpassword-old-link-invalid.png`)._
- [x] Given a link older than 72 hours, when the member opens it, then they see the same «Посилання недійсне…» message.
      _Verified (verify run 2026-11-06): Verified live by moving `member_invitation.expires_at` of the open link one hour into the past in the verification DB: the link shows the same invalid text (`docs/screenshots/020-go-live-polish-setpassword-expired-link.png`). A real 72-hour wait was not possible; the 72 h rule is also covered by `MemberInvitationKeycloakTests.aLinkOlderThanSeventyTwoHoursIsInvalid`._
- [x] Given a member with access, when the chairman clicks «Вимкнути доступ» and confirms, then the status becomes
      «Доступ вимкнено», the member can no longer sign in, and the member card and data are unchanged.
      _Verified (verify run 2026-11-06): Verified live: confirmation dialog (`docs/screenshots/020-go-live-polish-access-disable-confirm.png`), status «Доступ вимкнено», card and data still there (`docs/screenshots/020-go-live-polish-access-disabled.png`); sign-in at Keycloak is refused (`docs/screenshots/020-go-live-polish-signin-refused-after-disable.png`)._
- [x] Given a member with access, when the board archives the member, then the member can no longer sign in, and when the
      board restores the member, then the status stays «Доступ вимкнено».
      _Verified (verify run 2026-11-06): Verified live: archiving a member with access shows the access-off note (`docs/screenshots/020-go-live-polish-access-member-archived.png`); after restore the status stays «Доступ вимкнено» (`docs/screenshots/020-go-live-polish-access-restored-stays-disabled.png`); the member cannot sign in with the old password._
- [x] Given a member with access, when the board changes their e-mail, then a letter with a new link arrives at the new
      address and the member signs in with the new e-mail after setting the password.
      _Verified (verify run 2026-11-06): Verified live: PUT of a new e-mail on a member with access answers 200, a letter «Задайте пароль» arrives at the new address, the member sets a password and signs in with the new e-mail (`docs/screenshots/020-go-live-polish-access-email-changed.png`)._
- [x] Given a member with no e-mail, when the chairman opens the card, then «Запросити в систему» is disabled with
      «Додайте e-mail — він буде логіном».
      _Verified (verify run 2026-11-06): Verified live: invite button disabled with «Додайте e-mail — він буде логіном» (`docs/screenshots/020-go-live-polish-access-no-email.png`)._
- [x] Given a member with a linked Telegram, when the chairman invites them, then the bot also sends the link.
      _Verified (verify run 2026-11-06): Verified live against the fake Telegram API (:8091): demo member with a linked Telegram chat is invited; the bot message to that chat contains the same one-time token as the letter (`docs/screenshots/020-go-live-polish-access-telegram-member-invited.png`). Real Telegram not used._
- [x] Given an e-mail already used to sign in to another cooperative, when the chairman invites a member with it, then they
      see «Цей e-mail уже використовується в іншому кооперативі» and no letter is sent.
      _Verified (verify run 2026-11-06): Verified live: invited member with `member@other.local` sees «Цей e-mail уже використовується в іншому кооперативі», no letter reaches Mailpit for that address (`docs/screenshots/020-go-live-polish-access-other-cooperative-refused.png`)._
- [x] Given a sign-in account of this cooperative already exists with the member's e-mail, when the chairman invites the
      member, then no second account is created and the member signs in with the existing one after setting the
      password.
      _Verified (verify run 2026-11-06): Verified live: an account of the demo cooperative (tenant attribute set) was created in Keycloak with the member's e-mail before the invitation; after the invitation the member set a password and signed in, and Keycloak still holds exactly one user with that e-mail (`docs/screenshots/020-go-live-polish-access-existing-account-linked.png`). Also `MemberInvitationKeycloakTests.theExistingAccountOfTheCooperativeIsLinkedNotDuplicated`._
- [x] Given a treasurer without the invite permission, when they open a member card, then they see the access status but
      no invite, resend or disable buttons, and a direct attempt is refused.
      _Verified (verify run 2026-11-06): Verified live: treasurer sees the status with no invite/resend/disable buttons (`docs/screenshots/020-go-live-polish-access-treasurer-status-only.png`); direct POST invitation, resend and disable all answer 403. A member's own account page has no access block (`docs/screenshots/020-go-live-polish-account-member-view.png`)._
- [x] When the platform owner runs the first-chairman command for a new cooperative, then a one-time link appears once on
      their screen, and the chairman sets a password with it and signs in.
      _Verified (verify run 2026-11-06, after verify fix 7cfa226): the first run failed at start-up under the `command` profile (no web server) because three controllers injected `HttpServletRequest` in their constructors; fixed by reading `Accept-Language` per call. `FirstChairmanJarCommandTests` starts a separate JVM exactly as `infra/stand/first-chairman.sh` does (RED before the fix, GREEN after): exit 0, exactly one link printed, the member is CHAIRMAN. Real-jar smoke on local infra: exit 0, one link, no Tomcat. Sign-in with the link: `docs/screenshots/020-go-live-polish-first-chairman-signed-in.png`._
- [x] When the audit log is opened after an invite, a resend and a disable, then it shows the three actions with the
      member and the person who did them, and no link or password.
      _Verified (verify run 2026-11-06): Verified live: the audit log lists «Запрошено в систему» and the resend and disable actions with the member and the author (`docs/screenshots/020-go-live-polish-audit-invitation-actions.png`); the audit API response contains MEMBER_INVITED, MEMBER_INVITE_RESENT, MEMBER_ACCESS_DISABLED and no token, `set-password` or password text._

### 2.2 Tariffs

**Deleting a tariff.** A tariff that has never been used by any charge (from any of its versions, including reversed
charges) shows «Видалити тариф» on its page. After the confirmation «Видалити тариф «{назва}» разом з усіма версіями?
Це не можна скасувати.» the tariff disappears. The delete is written to the audit as «Тариф видалено».

**Archiving a tariff.** A tariff used by a charge shows «Архівувати» instead. The person picks the date from which the
tariff stops applying (the end of its last version, not a new rate). From that date:

- the tariff no longer produces charges in the month run;
- it disappears from the list of active tariffs, and appears under «Архівні»;
- its page stays readable with its history, with the note «Діє до {дата}».

An archived tariff cannot get new versions. The rule «a tariff always has a version» stays for active tariffs. The
archive is written to the audit as «Тариф архівовано».

**The overlap warning says why.** «Тарифи одного виду нарахування не можуть діяти на ті самі ділянки. Ці ділянки зараз
не нараховуються. Змініть ділянки одного з тарифів або оберіть інший вид нарахування.» Then the list of plots, as now.

**The new tariff form.**

- Under «Вид нарахування», the form lists the active tariffs of the chosen type with their plots, so an overlap is seen
  before saving.
- A link «Немає потрібного виду? Види нарахувань» leads to the charge types page.
- Charge types that are switched off (for example «Вода») show in the list as «{назва} (неактивний — увімкнути)».
  Choosing one, a person with the tariff permission switches the type on and continues; a person without it cannot
  choose it.

**Acceptance criteria**

- [x] Given a tariff never used by a charge, when the treasurer clicks «Видалити тариф» and confirms, then the tariff is
      gone from the list and the audit shows «Тариф видалено».
      _Verified (verify run 2026-11-06): Verified live: dialog «Видалити тариф «…» разом з усіма версіями? Це не можна скасувати.» (`docs/screenshots/020-go-live-polish-tariff-delete-confirm.png`), tariff gone from the list (`docs/screenshots/020-go-live-polish-tariff-deleted-list.png`), audit shows «Тариф видалено» (`docs/screenshots/020-go-live-polish-audit-tariff-deleted.png`)._
- [x] Given a tariff used by a charge, when the treasurer opens it, then «Видалити тариф» is not offered and «Архівувати»
      is.
      _Verified (verify run 2026-11-06): Verified live: demo tariff with charges has no «Видалити тариф» button and offers «Архівувати» (`docs/screenshots/020-go-live-polish-tariff-page-used-archive-offered.png`)._
- [x] Given a used tariff archived from the 1st of a month, when the month run for that month runs, then the tariff
      charges nothing for that month, and when the month run for an earlier month runs, then it charges as before.
      _Verified (verify run 2026-11-06): Non-visual rule, proved by integration tests run here against a real database: `TariffArchiveTests` (17 tests, 0 failures) including `aTariffArchivedFromTheFirstOfMarchChargesFebruaryAndNotMarch`; `TariffDeleteTests` (6, 0 failures)._
- [x] When a tariff is archived, then it leaves the active list, shows under «Архівні», and its page shows «Діє до
      {дата}» with the full history.
      _Verified (verify run 2026-11-06): Verified live: after archiving (end 2099-01-01) the page shows «Діє до 31.12.2098» with its history (`docs/screenshots/020-go-live-polish-tariff-page-archived.png`), the tariff is missing from the active list (`docs/screenshots/020-go-live-polish-tariffs-active-without-archived.png`) and listed under «Архівні» (`docs/screenshots/020-go-live-polish-tariffs-archived-tab.png`)._
- [x] Given two tariffs of the same charge type on the same plots, when the treasurer opens the tariff list, then the
      warning shows the new text and the plots.
      _Verified (verify run 2026-11-06): Verified live: two tariffs of one type on all plots give the warning with the new text and the plot list «Ділянки: 1, 2 … та ще 20» (`docs/screenshots/020-go-live-polish-tariffs-overlap-warning.png`)._
- [x] Given an active tariff of type «Членський внесок», when the treasurer chooses that type in the new tariff form, then
      that tariff and its plots are listed under the field.
      _Verified (verify run 2026-11-06): Verified live: choosing «Членський внесок» lists «Діючі тарифи цього виду: Для членів кооперативу — ділянки: 1, 2, 3, 4, 5, 6, 7, 8, і ще 21» (`docs/screenshots/020-go-live-polish-tariff-form-active-tariffs-of-type.png`)._
- [x] Given «Вода» is switched off, when the treasurer opens the type list in the new tariff form, then it shows «Вода
      (неактивний — увімкнути)», and when they choose it, then the type is switched on and selected.
      _Verified (verify run 2026-11-06): Verified live: option «Вода (неактивний — увімкнути)»; choosing it switches the type on (the option label becomes «Вода») and selects it (`docs/screenshots/020-go-live-polish-tariff-form-inactive-type-enabled.png`)._
- [x] When the treasurer clicks «Немає потрібного виду? Види нарахувань», then the charge types page opens.
      _Verified (verify run 2026-11-06): Verified live: the link «Види нарахувань» opens `/charge-types` (`docs/screenshots/020-go-live-polish-charge-types-page-from-link.png`)._

### 2.3 Payments

**Payer by default.** When the plot has exactly one current owner, that owner is preselected as the payer in the payment
form. With several owners, the field stays empty and asks to choose, with the owners listed first. (The owner confirms
this behaviour at review.)

**The allocation dialog.**

- The total «Розподілено X з Y» stays on one line.
- When an amount entered for a charge is more than what is left on it, a line above that amount says «Більше, ніж
  залишок {сума}».

**Acceptance criteria**

- [x] Given a plot with exactly one current owner, when the treasurer chooses the plot in the payment form, then that
      owner is already selected as the payer.
      _Verified (verify run 2026-11-06): Verified live: plot with one owner, payer is preselected (`docs/screenshots/020-go-live-polish-payment-form-one-owner-preselected.png`)._
- [x] Given a plot with two current owners, when the treasurer chooses the plot, then the payer field is empty, asks to
      choose, and lists the two owners first.
      _Verified (verify run 2026-11-06): Verified live: plot with two owners, payer empty («Не вказано»), both owners listed first under «Власники ділянки» (`docs/screenshots/020-go-live-polish-payment-form-two-owners-empty-payer.png`)._
- [?] When the allocation dialog is open on a narrow screen (360 px), then «Розподілено X з Y» stays on one line.
      _Owner review (verify run 2026-11-06): Measured at 360 px: the row «Розподілено 400,00 ₴ з 400,00 ₴» is 36.5 px high against a 20 px line, so one line (not two). Look is for the owner: the allocation table is 422 px wide inside a 286 px scroll container, so at 360 px the total text is cut at the right edge until the table is scrolled sideways (`docs/screenshots/020-go-live-polish-payment-allocation-360px.png`)._
- [x] When the treasurer enters more than the open amount on a line, then «Більше, ніж залишок {сума}» shows above that
      amount.
      _Verified (verify run 2026-11-06): Verified live at 360 px: entering 1500.00 against a 1 000,00 ₴ line shows «Більше, ніж залишок 1 000,00 ₴» above the amount (measured: bottom of the message 1189 px, top of the input 1193 px) (`docs/screenshots/020-go-live-polish-payment-allocation-over-amount-360px.png`)._

### 2.4 Small items from reviews

1. **Duplicate attachments.** When the same file (same name, size and content) is added twice to an expense, the form
   says «Цей файл уже додано» and does not add it again.
2. **Readings end-to-end clean-up (010).** Already fixed by feature 014: the readings checks remove their test plot even
   when they fail between the submission and the approval. Closed with that evidence; no change.
3. **The electrician's demo role.** End-to-end checks never leave extra permissions on the demo role «Електрик» or extra
   roles on the demo electrician: their clean-up restores both.
4. **Flaky end-to-end check.** The check «a member from creation to archive» waits for the member list to refresh after
   archiving before it searches for «Nothing found», and passes 10 runs in a row locally.

**Acceptance criteria**

- [x] When the treasurer adds the same file twice to an expense, then the form shows «Цей файл уже додано» and keeps one
      copy.
      _Verified (verify run 2026-11-06): Verified live: adding `dup.png` twice shows «Цей файл уже додано» and one copy stays (`docs/screenshots/020-go-live-polish-expense-duplicate-file.png`)._
- [x] When the readings end-to-end check fails between submission and approval, then its test plot is still removed
      (evidence from 014 recorded).
      _Verified (verify run 2026-11-06): Evidence from 014 recorded in `qa-notes.md` (FS 2.4.2): `web/e2e/meters.spec.ts` runs `cleanupMeterPlot` in a `finally` for every test that creates a plot, and a `beforeAll` sweep removes leftovers; `meters-acceptance.spec.ts` has `cleanLeftovers`._
- [x] When any end-to-end check that changes the electrician's role or roles finishes or fails, then the role «Електрик»
      has exactly its demo permissions and the demo electrician has exactly the demo roles.
      _Verified (verify run 2026-11-06): Code check: `web/e2e/support/api.ts` `restoreElectrician` (puts the role «Електрик» back to METERS_VIEW and READINGS_ENTER and the member back to Member plus «Електрик») is called from the `finally` of `privacy.spec.ts` and `reports-acceptance.spec.ts`; `acceptance.spec.ts` (§2.4 #3) now uses a throwaway member for archiving. No other spec changes the electrician's role or roles (grep)._
- [x] When the check «a member from creation to archive» runs 10 times in a row locally, then it passes every time.
      _Verified (verify run 2026-11-06): `pnpm exec playwright test acceptance --grep "creation to archive" --repeat-each=10` run here against the verification stack: 10 passed (1.4 min)._

### 2.5 Help and guides

- Every changed screen updates its help pages in Ukrainian and English and their screenshots: the member card
  (invitations and access), the tariff pages and form, the payment form, the expense form, and the new «Задайте пароль»
  page.
- The help «Початок роботи» for the board explains how members get access.
- The owner manual explains the sign-in service's client for invitations and where its secret lives on the server, and
  replaces the manual procedure of creating a member's account by hand; the stand guide and the deployment notes list
  the new server settings and the first-chairman command.

**Acceptance criteria**

- [x] When the board opens the help of the member card, the tariff pages, the payment form and the expense form, then the
      pages describe the new buttons and messages, in both languages, with fresh screenshots.
      _Verified (verify run 2026-11-06): Rendered `/help/<page>` for member-card, tariffs, tariff-card, tariff-form, payments-enter, expense-form and getting-started (screenshots `docs/screenshots/020-go-live-polish-help-*.png`, English: `docs/screenshots/020-go-live-polish-help-member-card-en.png`); the pages carry screenshots and text for the invite buttons and statuses, tariff delete/archive, overlap text, form hints, payer default, «Більше, ніж залишок», duplicate file and the set-password page, in uk and en (grep of the Markdown). The page `set-password` exists in both languages._
- [x] When the help completeness check runs, then it passes.
      _Verified (verify run 2026-11-06): `pnpm --filter web exec vitest run src/help`: 9 files, 40 tests passed, including `helpContent.test.ts` (completeness)._
- [x] When the platform owner reads the owner manual, then it explains the invitations client, its secret, and the
      first-chairman command, and no longer tells them to create members' accounts by hand.
      _Verified (verify run 2026-11-06): `docs/guide/{uk,en}/owner-manual.html`: section «Giving a member access (the board invites)» (line 447 in en) states that accounts are no longer created by hand, names the invitations client and its secret `NADEL_KEYCLOAK_ADMIN_CLIENT_SECRET` and rotation via `stand-init.sh`, and describes the first-chairman command (line 538); `src/help/ownerManual.test.ts` passed in the same vitest run. Note: the documented first-chairman command currently fails at start-up (see section 2.1, first-chairman criterion)._

---

## 3. Scope and Boundaries

### In-Scope

- Invite, resend, disable access, access status on the member card, the set-password page, the archive and e-mail
  change rules, the first-chairman command, the invite permission and its audit.
- Tariff delete, tariff archive from a date, the overlap warning text, the new tariff form hints.
- Payer by default and the allocation dialog items.
- Duplicate attachment warning, end-to-end hygiene items, closing the readings clean-up item.
- Help pages, screenshots, owner manual and deployment notes for the above.
- Demo data so the demo shows a member without access who can be invited.

### Out-of-Scope

- Self-registration.
- Invitation by SMS.
- Single sign-on with Google or Apple.
- The platform operator's screens.
- The payroll feature.
- The mobile apps.
- Feedback from the test stand (a follow-up feature).

---

## 4. Assumptions & Decisions

User answers (Phase 1):

- **The set-password link is the application's own one-time link**, sent from the cooperative's mailbox; the page
  «Задайте пароль» belongs to the application. (The sign-in service cannot hand back its own link for another mailbox
  to send.)
- **The first chairman's link is shown once on the platform owner's screen**, because the new cooperative has no mailbox
  yet; it is never written to logs or audit.

Assumptions (taken automatically, recommended answers):

- The decision record for invitations is number 0020 (0019 is already used).
- The invite button is disabled when the cooperative has no mailbox set up.
- A member with a Telegram link but no e-mail still needs an e-mail first; the bot repeats the link once there is one.
- The new permission is named «Запрошувати в систему»; only the Chairman has it by default.
- "Last sign-in" is the last time the member opened the application signed in, recorded by the application.
- The access status block is not shown to the member themself.
- A tariff counts as used when any charge was ever made from it, including charges later reversed; such a tariff can
  only be archived.
- Archiving is written to the audit as «Тариф архівовано» (the source names only the delete action).
- An archived tariff cannot be un-archived in this feature.
- The readings end-to-end clean-up (item 4.2) was fixed by 014 and is closed with that evidence.
- The end-to-end check that archives the demo electrician is changed to use a throwaway member, because archiving now
  switches access off and restoring does not switch it on.
- The run executes no tests until all slices are implemented; one verification at the end (user instruction for this
  run).

---

## Change Log

- [YYYY-MM-DD] — [source reference] — [what behavior changed and why]
