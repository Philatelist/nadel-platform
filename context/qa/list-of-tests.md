# List of tests

| File | Test Name | Layer | Positive/Negative | @regression | Status | Notes |
| ---- | --------- | ----- | ----------------- | ----------- | ------ | ----- |
| backend/src/test/java/app/nadel/acceptance/MembersAccessAcceptanceTests.java | demoChairmanSeesThirtyToFortyMembersAcrossTwoPages | integration | positive | yes | OK | spec 002-members-access, §2.13 #1 |
| backend/src/test/java/app/nadel/acceptance/MembersAccessAcceptanceTests.java | demoChairmanSeesTheFiveRolesAndSecretaryIsGivenToOneMember | integration | positive | yes | OK | §2.13 #2 |
| backend/src/test/java/app/nadel/acceptance/MembersAccessAcceptanceTests.java | chairmanOnboardsASecretaryAndTheAuditLogTellsTheWholeStory | integration | positive | yes | OK | cross-slice journey: §2.3, §2.4, §2.6, §2.8, §2.9 |
| backend/src/test/java/app/nadel/acceptance/MembersAccessAcceptanceTests.java | validationMessagesComeBackAsFieldCodesAndNothingIsSaved | integration | negative | yes | OK | §2.3 #2-#3, §2.6 #3-#5 |
| backend/src/test/java/app/nadel/acceptance/MembersAccessAcceptanceTests.java | untickingAuditViewForTreasurerRemovesTheAuditLogFromTheTreasurer | integration | positive | yes | OK | §2.2 #3 |
| backend/src/test/java/app/nadel/acceptance/MembersAccessAcceptanceTests.java | theAuditApiOffersNoWayToChangeOrRemoveAnEntry | integration | negative | yes | OK | §2.9 #8 |
| backend/src/test/java/app/nadel/acceptance/MembersAccessAcceptanceTests.java | noTaxPassportOrBirthDataExistsInTheContractOrOnTheCard | integration | negative | yes | OK | §2.6 #10 |
| backend/src/test/java/app/nadel/acceptance/MembersAccessAcceptanceTests.java | aChairmanOfTheOtherCooperativeCannotReachAnythingOfTheDemoCooperative | integration | negative | yes | OK | §2.12 |
| backend/src/test/java/app/nadel/acceptance/MembersAccessAcceptanceTests.java | thePlatformOperatorBelongsToNoCooperativeAndSeesNoCooperativeData | integration | negative | yes | OK | §2.4 #5 |
| backend/src/test/java/app/nadel/acceptance/MembersAccessAcceptanceTests.java | writesWithoutThePermissionAreRefusedAndLeaveTheMemberUnchanged | integration | negative | yes | OK | §2.11 #4 |
| backend/src/test/java/app/nadel/acceptance/MembersAccessAcceptanceTests.java | anEditorWithoutManageRolesEditsFieldsButNotRoles | integration | negative | yes | OK | §2.8 #4 |
| backend/src/test/java/app/nadel/acceptance/MembersAccessAcceptanceTests.java | businessErrorsReachAClientThatAcceptsJson | integration | negative | yes | FAILING | GAP: DELETE /roles/{id} answers 406 to Accept: application/json |
| web/e2e/acceptance.spec.ts | §2.10/§2.11 landing and navigation (2 specs) | e2e | positive | yes | OK | treasurer and chairman |
| web/e2e/acceptance.spec.ts | §2.5 ordinary member | e2e | negative | yes | OK | |
| web/e2e/acceptance.spec.ts | §2.7 / §2.8 members list and card (3 specs) | e2e | positive+negative | yes | OK | |
| web/e2e/acceptance.spec.ts | §2.2 / §2.13 roles screen (2 specs) | e2e | positive | yes | OK | Ukrainian default and English switch |
| web/e2e/acceptance.spec.ts | §2.3 custom role lifecycle | e2e | positive+negative | yes | FAILING | same GAP: role delete from the browser |
| web/e2e/acceptance.spec.ts | §2.6 member from creation to archive and restore, and audit trail | e2e | positive+negative | yes | OK | |
| web/e2e/acceptance.spec.ts | §2.2 #3 unticking View audit log for Treasurer | e2e | positive | yes | OK | |
| web/e2e/acceptance.spec.ts | §2.4 #3 archived member not linked | e2e | negative | yes | OK | |
| web/e2e/acceptance.spec.ts | §2.4 #5 operator; §2.12 other cooperative (2 specs) | e2e | negative | yes | OK | |
| web/e2e/acceptance.spec.ts | §2.7 #2 Show archived while searching | e2e | positive | yes | OK | |
| web/src/designSystem.acceptance.test.ts | 2.3 fonts self-hosted (index.html, latin+cyrillic imports, Plex tokens) | unit | negative+positive | yes | OK | spec 003-ui-design-system, 2.3 |
| web/src/designSystem.acceptance.test.ts | 2.4 spacing, radius and target-size tokens | unit | positive | yes | OK | spec 003 |
| web/src/designSystem.acceptance.test.ts | 2.9 mobile uses shared tokens and Plex fonts, no hex literals | unit | negative+positive | yes | OK | spec 003 |
| web/e2e/design-system.spec.ts | 2.3 no outside font request; serif h1 / sans body; Cyrillic subset loaded | e2e | negative+positive | yes | OK | spec 003 |
| web/e2e/design-system.spec.ts | 2.1 light palette on Members (page ground, white panel, accent button) | e2e | positive | yes | OK | spec 003 |
| web/e2e/design-system.spec.ts | 2.2 dark appearance changes Members to the dark palette | e2e | positive | yes | OK | spec 003 |
| web/e2e/design-system.spec.ts | 2.4 buttons and inputs on Members and Member form are at least 44 px | e2e | positive | yes | OK | spec 003 |
| web/e2e/design-system.spec.ts | 2.4 header buttons incl. language switch at least 44 px | e2e | negative | yes | OK | Gap fixed: LocaleSwitch buttons are 44 px with 10 px corners (rounded-control); test also asserts the radius |
| web/e2e/design-system.spec.ts | 2.6 chairman header contents and active menu item | e2e | positive | yes | OK | spec 003 |
| web/e2e/design-system.spec.ts | 2.6 Menu pressed twice opens then closes at 390 px | e2e | positive | yes | OK | spec 003 |
| web/e2e/design-system.spec.ts | 2.6 plain member has header and no menu or Menu button | e2e | negative | yes | OK | spec 003 |
| web/e2e/design-system.spec.ts | 2.8 Tab reaches menu links, search field and Add member with visible outline | e2e | positive | yes | OK | spec 003 |
