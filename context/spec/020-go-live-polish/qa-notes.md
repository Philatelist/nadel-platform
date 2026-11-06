# 020 QA notes

## FS 2.4.2 Readings clean-up: already fixed by feature 014, no code change

The meter specs clean up their data, and a rerun sweeps what an interrupted run left:

- `web/e2e/meters.spec.ts`: `cleanup()` (line 87) calls `cleanupMeterPlot` for each fixture (line 93); every test
  that creates a plot calls it from its `finally` (lines 176, 221, 270, 303; the submit/approve test starts at line 180 and its `finally` calls it at line 221).
  `test.beforeAll` (line 101) sweeps the plots numbered `E2E-M-` left by earlier runs (lines 105-109).
- `web/e2e/support/api.ts`: `cleanupMeterPlot` (line 238) rejects pending readings, deletes or retires each meter,
  ends the ownership and archives the plot; it never throws.
- `web/e2e/meters-acceptance.spec.ts`: `cleanLeftovers` (line 154) is run before the specs (`beforeAll`, line 169), and each
  acceptance spec cleans up in its `finally` (for example lines 238, 269, 375, 474).

## FS 2.4.4 Flaky "a member from creation to archive"

`web/e2e/acceptance.spec.ts` now waits for the archive response and for the filtered list request before it asserts
"Nothing found". Not run in this slice. The final slice must prove it with:

`pnpm --filter web exec playwright test acceptance --grep "creation to archive" --repeat-each=10`

## Acceptance criteria → tests

Short names: **MIT** `members/internal/MemberInvitationTests`, **MAT** `members/internal/MemberAccessTests`,
**MAK** `members/internal/MemberAccessKeycloakTests`, **MIK** `members/internal/MemberInvitationKeycloakTests`
(Keycloak container), **FCC** `members/internal/FirstChairmanCommandTests`, **TAT** `ledger/TariffArchiveTests`,
**TDT** `ledger/TariffDeleteTests` (all under `backend/src/test/java/app/nadel/`). E2E files are in `web/e2e/`,
web unit tests in `web/src/`.

### 2.1 Inviting a member

| # | Criterion | Tests |
|---|---|---|
| 1 | Invite: status «Запрошено», letter arrives | MIT `theChairmanInvitesAMemberAndTheLetterCarriesTheOneTimeLink`; `MemberAccess.test.tsx` "NOT_INVITED: shows the status and an invite button that sends the invitation"; e2e `member-invite.spec.ts` "the chairman invites a member who sets a password and signs in @smoke" (letter read from Mailpit) |
| 2 | Set password, «Пароль задано», sign in, card «Має доступ…» | MIK `theMemberSetsThePasswordFromTheLetterAndSignsIn`; MIT `aLinkIsInspectedAndAcceptedOnceAndTheMemberThenSignsIn`; `SetPasswordPage.test.tsx` "sets the password and offers sign-in"; same e2e smoke test |
| 3 | Resend: new letter, old link invalid | MAT `aResendMakesTheOldLinkInvalidAndSendsANewLetter`; MIT `everyLinkThatCannotBeUsedIsTheSameInvalidAnswer`; e2e "sending again invalidates the old link and the new one works" |
| 4 | Link older than 72 h | MIK `aLinkOlderThanSeventyTwoHoursIsInvalid`; `SetPasswordPage.test.tsx` "shows the invalid text for a token the server refuses" |
| 5 | Disable access: status, cannot sign in, card unchanged | MAT `disableTurnsAccessOffOnceAndTheAccountOff`; MAK `disableTurnsTheAccountOffAndTheMemberCannotSignIn`; `MemberAccess.test.tsx` "turning access off asks first and names the member"; e2e "turning access off shows the status and blocks the sign-in" |
| 6 | Archive switches access off, restore keeps it off | MAT `archiveTurnsAccessOffAndRestoreLeavesItOff`; MAK `archiveTurnsTheAccountOffAndRestoreLeavesItOff`; e2e "archiving a member with access turns it off and restoring does not bring it back"; race: MAT `anArchiveWhileTheInvitationLetterIsOnItsWayWins`, `aDisableWhileTheResentLetterIsOnItsWayWins` |
| 7 | E-mail change: new link to the new address, sign in with new e-mail | MAT `anEmailChangeMovesTheLoginAndSendsANewLinkToTheNewAddress`, `anEmailChangeOfAMemberWithAccessInvitesThemAgain`; MAK `anEmailChangeMovesTheLoginAndTheMemberSignsInWithTheNewOne`, `anEmailChangeOfAnInvitedMemberKillsTheOldLink`; negative MAK `anEmailThatSignsInToAnotherCooperativeIsRefusedOnChange` |
| 8 | No e-mail: invite disabled with the reason | MIT `anInvitationIsRefusedWhenItCannotBeSent`; `MemberAccess.test.tsx` "a blocked invite is disabled and says why", "a missing mailbox is explained"; e2e "a member without an e-mail cannot be invited and the reason is shown" |
| 9 | Linked Telegram: the bot also sends the link | `notifications/internal/InvitationTelegramTests` `aMemberWithTelegramGetsTheSameLinkFromTheBot` (negatives: `withoutABotOrALinkTheLetterIsTheInvitation`, `aRefusedBotNeverFailsTheInvitation`, `aLetterThatIsNotSentSendsNothingByTheBot`) |
| 10 | E-mail of another cooperative refused, no letter | MIT `anEmailThatSignsInToAnotherCooperativeIsRefusedWithoutALetter`; MIK `anEmailThatSignsInToAnotherCooperativeIsRefused`; `MemberAccess.test.tsx` "shows the refusal for an e-mail used in another cooperative"; e2e "an e-mail used in another cooperative is refused" |
| 11 | Existing account of this cooperative linked, not duplicated | MIT `anAccountOfTheCooperativeWithTheEmailIsLinkedNotDuplicated`; MIK `theExistingAccountOfTheCooperativeIsLinkedNotDuplicated`; negative MIT `anAccountLinkedToAnotherMemberIsInUse` |
| 12 | No permission: status only, direct attempt refused | MIT `onlyAHolderOfTheInvitePermissionInvites`, `theCardShowsTheAccessToViewersOfMembersOnly`; MAT `onlyAHolderOfTheInvitePermissionResendsOrDisables`; `MemberAccess.test.tsx` "without MEMBERS_INVITE: only the status"; e2e "the treasurer sees the access status but no buttons" |
| 13 | First-chairman command: link once, set password, sign in | FCC `aNewCooperativeAndItsChairmanAreCreatedAndTheLinkIsPrintedExactlyOnce`, `acceptingThePrintedLinkGivesTheChairmanAccess`, `resendPrintsANewLinkAndTheOldOneNoLongerWorks`; negatives `invalidArgumentsExitWithANonZeroCodeAndCreateNothing`, `anEmailThatSignsInToAnotherCooperativeFailsWithANonZeroCode`, `aSecondRunCreatesNothingAndPrintsNoLink`; `tenancy/internal/TenantProvisioningTests` |
| 14 | Audit: invited / resent / disabled, no link or password | MAT `aResendMakesTheOldLinkInvalidAndSendsANewLetter` (MEMBER_INVITE_RESENT, MEMBER_INVITED counted), `disableTurnsAccessOffOnceAndTheAccountOff` (MEMBER_ACCESS_DISABLED); MIT `theLinkAndThePasswordAreNeverWrittenDown`; MIK `theMemberSetsThePasswordFromTheLetterAndSignsIn` (log output checked) |

Rules without a bullet of their own: tenant isolation of the new tables `MemberInvitationTenantIsolationTests`; token
shape and hash `InvitationTokensTests`; sign-in service down / bad secret `KeycloakAdminClient*Tests`; password rules at
the boundary MIT `thePasswordIsCheckedAtTheBoundary` and `SetPasswordPage.test.tsx` (field error mapping).

### 2.2 Tariffs

| # | Criterion | Tests |
|---|---|---|
| 1 | Delete a never-used tariff, audit «Тариф видалено» | TDT `anUnusedTariffIsDeletedWithEveryVersionAndItsScope` (TARIFF_DELETED row); `TariffPage.test.tsx` "offers deleting a deletable tariff with TARIFFS_MANAGE and confirms before calling the API"; e2e `tariffs-acceptance.spec.ts` "a tariff without charges is deleted from its page, a demo tariff with charges has no delete button". Negatives: TDT `aTariffTheMonthRunChargedFromIsRefused`, `aTariffWhoseChargesWereReversedIsStillRefused`, `aChargePartOfASoftlyDeletedVersionRefusesTheDelete`, `onlyTariffsViewWithTariffsManageMayDelete` |
| 2 | Used tariff: no delete, archive offered | `TariffPage.test.tsx` "offers archiving for a used tariff…", "hides the delete button for a tariff that is not deletable…", "hides archiving without TARIFFS_MANAGE and for a deletable tariff"; same e2e (demo tariff with charges); `ledger/internal/LedgerTariffUsageTests` |
| 3 | Archived from the 1st: month run charges nothing, earlier month as before | TAT `aTariffArchivedFromTheFirstOfMarchChargesFebruaryAndNotMarch`, `anEndInsideAMonthChargesTheDaysUpToIt`, `theExpectedAmountsOfAPlotStopAtTheEnd`; negatives `anEndOnOrBeforeTheLastChargedDayIsRefused`, `theEndMustBeAfterTheStartOfTheLastVersion` |
| 4 | Archived leaves active list, under «Архівні», «Діє до», history | TAT `theListFiltersByStatusAndChargeType`, `theCardShowsTheEndOnTheTariffAndOnItsLastVersion`, `theVersionsAndTheScopeOfAnArchivedTariffAreFrozen`, `theArchiveIsAuditedWithTheEndDate`; `TariffsPage.test.tsx` "asks for active tariffs first and for archived ones after the switch", "shows the last day of an archived tariff"; `TariffPage.test.tsx` "shows an archived tariff read-only with its last day"; `ArchiveTariffDialog.test.tsx`; e2e "the new tariff form lists the active tariffs of the chosen type; the archived tab opens" |
| 5 | Overlap warning with the new text and the plots | `TariffConflicts.test.tsx` "names the other tariff, the warning text and the plot numbers" (negative: "renders nothing without conflicts") |
| 6 | New form lists active tariffs of the chosen type with plots | `TariffFormPage.test.tsx` "lists the active tariffs of the chosen type with their plots, shortened when long", "says "none" when…"; e2e "the new tariff form lists the active tariffs of the chosen type; the archived tab opens" |
| 7 | Inactive type «(неактивний — увімкнути)», choose switches it on | `TariffFormPage.test.tsx` "lists inactive charge types as an enable option…", "enables an inactive charge type with its current values, then selects it"; negative "keeps the previous selection and shows the problem when enabling fails" |
| 8 | Link «Немає потрібного виду?» opens charge types | `TariffFormPage.test.tsx` "links to the charge types" |

### 2.3 Payments

| # | Criterion | Tests |
|---|---|---|
| 1 | One owner preselected | `PaymentForm.test.tsx` "payer by default › preselects the only owner of the plot", "applies the default again when the plot changes"; NEW e2e `go-live-polish-acceptance.spec.ts` |
| 2 | Two owners: empty, listed first | `PaymentForm.test.tsx` "leaves the payer empty with two owners and offers them first", "does not override a payer the user chose or cleared" |
| 3 | 360 px: total on one line | NEW e2e `go-live-polish-acceptance.spec.ts` (jsdom cannot measure layout; `ProposalTable` uses `whitespace-nowrap`) |
| 4 | Over the open amount: «Більше, ніж залишок» above the amount | `PaymentForm.test.tsx` "keeps the table while an intermediate value exceeds…", `PaymentFormSplit.test.tsx`, `ReallocateDialog.test.tsx` "keeps the table while an intermediate value exceeds the charge…" (checks the alert precedes the input); NEW e2e (position above the input) |

### 2.4 Small items

| # | Criterion | Tests |
|---|---|---|
| 1 | Same file twice: «Цей файл уже додано», one copy | `ExpenseForm.test.tsx` "refuses the same file twice and accepts it again after removal"; `lib/fileFingerprint.test.ts` (same name, size, content; negative: different content or name; no `crypto.subtle`) |
| 2 | Readings clean-up (014 evidence) | Evidence section "FS 2.4.2" above; `meters.spec.ts` `finally` → `cleanupMeterPlot`, `meters-acceptance.spec.ts` `cleanLeftovers` |
| 3 | Electrician role restored | `support/api.ts` `restoreElectrician`, called from `privacy.spec.ts` and `reports-acceptance.spec.ts` `finally`; `acceptance.spec.ts` "§2.4 #3: archiving a member keeps the roles…" now uses a throwaway member |
| 4 | "creation to archive" passes 10× | `acceptance.spec.ts` "§2.6, §2.8 #2, #3, …: a member from creation to archive and restore, and its audit trail", run with `--repeat-each=10` (result below) |

### 2.5 Help and guides

| # | Criterion | Tests |
|---|---|---|
| 1 | Help pages describe new buttons/messages, uk+en, screenshots | `src/help/helpContent.test.ts` "has no completeness issues" (both languages, screenshots exist); e2e `help.spec.ts` "020: the set-password page opens signed out…" |
| 2 | Completeness check passes | `src/help/helpContent.test.ts`, run by `make check` |
| 3 | Owner manual: client, secret, first-chairman command | NEW `src/help/ownerManual.test.ts` (uk and en) |

### Rows closed by tests added in slice 9

- FS 2.3 #3 (360 px, one line) and the browser side of #1/#4: `web/e2e/go-live-polish-acceptance.spec.ts`
  (`@layer: e2e`, `@spec: 020-go-live-polish`, `@regression`).
- FS 2.5 #3 (owner manual): `web/src/help/ownerManual.test.ts` (`@layer: unit`, `@regression`).
- Coverage floor tests (feature code that no test reached): `KeycloakAdminClientFailureTests`
  (`aSearchThatIsNotAnsweredWith200IsUnavailable`, `anAccountWithoutAValidIdIsRefusedAndNeverReturned`,
  `theCooperativeOfAnAccountIsReadFromAnArrayOrAStringAndNothingElse`), `FirstChairmanCommandTests`
  (`aProblemOrAnUnexpectedErrorOfTheWorkIsAnErrorCodeAndNeverTheStackTrace`, `theArgumentsNeverPrintTheirPersonalValues`),
  `InvitationTelegramTests.aBotWhoseStoredTokenCannotBeReadNeverFailsTheInvitation`,
  `MemberAccessTests` (`aDeliveryThatBreaksRevokesTheLinkAndChangesNothing`,
  `aLinkPrinterThatBreaksRevokesTheLinkAndChangesNothing`, `aLastSignInThatCannotBeStoredNeverStopsTheSignIn`).

## Slice 9 verification run: findings

| Where | Cause | Fix |
|---|---|---|
| `infra/keycloak/nadel-realm.json` | The policy `length(8)` made Keycloak refuse to import the demo users (password `nadel`, 5 characters): the container exited with `invalidPasswordMinLengthMessage` | the demo passwords are stored pre-hashed (`credentialData`/`secretData`), so the policy applies to new passwords only |
| same | `fullScopeAllowed: false` on `nadel-backend-admin` left the `realm-management` roles out of the token (Admin API 403) | `clientScopeMappings` for `manage-users`, `view-users`, `query-users` |
| same | the Admin API cannot change a user name when `editUsernameAllowed` is off (HTTP 400 on a login change) | `"editUsernameAllowed": true` |
| `IntegrationTest.java` | the new Keycloak and command contexts pushed the shared test Postgres past 100 connections (`remaining connection slots are reserved`), failing five unrelated classes | `max_connections=400` for the test container |
| `TariffsTenantIsolationTests` | asserted that DELETE on `tariff` is denied; V35 grants it on purpose | split: charge types stay denied, tariff TRUNCATE stays denied, tariff DELETE is scoped by RLS and stopped by the versions' FK |
| `RoleLogicTests` | permission index shifted by `MEMBERS_INVITE` | expects the new permission at index 2 |
| `InvitationTenantFunctionTests` | `text || "char"` is ambiguous in PostgreSQL | `provolatile::text` |
| `PaymentForm.test.tsx` | the "payer by default" tests matched the owner name inside the suggestion button, so they passed without the feature (RED check showed it) | they now assert the picker's change button and the absence of the suggestions |
| help | the `member-card-access` shot was missing | shot added to the front matter (uk, en), referenced in the body, images produced |

## Slice 9 results

- `make check`: backend 4360 tests, 0 failures (Keycloak-tagged classes included), line coverage 0.9771 against the
  floor 0.977; client tests 56 + 10 + 130 + 1112, all passing; typecheck, lint, Prettier, contract generation green.
- E2E (stack as `make e2e` starts it, own database): `member-invite`, `tariffs-acceptance`, `payments`,
  `cashbook-expense-handover`, `acceptance`, `privacy`, `reports-acceptance`, `help`, `go-live-polish-acceptance`
  (102 tests incl. substring matches), `--grep @smoke` 23 passed; "a member from creation to archive" 10 of 10.
- Screenshots: `member-card` (with the new `member-card-access`), `tariff-card`, `tariffs`, `tariff-form`,
  `payments-enter`, `expense-form` in uk and en; `make guide` rebuilt the PDFs; the help completeness test passes.
- RED/GREEN checked by reverting the covered hunk: invite accept (`markUsed`), disable/archive race
  (`requireIssuable`), tariff delete (audit entry), tariff archive month run (end of the tariff not passed to the
  book), payer default, duplicate attachment.
