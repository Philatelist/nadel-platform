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
| backend/src/test/java/app/nadel/acceptance/RoleNamesAcceptanceTests.java | aBaseRoleNameIsRefusedWhenEmptyBlankOrLongerThanSixtyAndStaysUnchanged | integration | negative | yes | OK | spec 004-role-names, §2.1 #4 on a base role; 60 characters accepted |
| backend/src/test/java/app/nadel/acceptance/RoleNamesAcceptanceTests.java | aRenamedTreasurerRoleIsReadUnderTheNewNameEverywhereAndRestoredToItsStandardName | integration | positive | yes | OK | §2.1 #1, §2.3 #2, §2.6 #1 (/me, member card, members list) |
| backend/src/test/java/app/nadel/members/internal/RolesTests.java | rename, restore, uniqueness, trim no-op, useDefaultName 400, 403 without ROLES_MANAGE, Chairman lock, audit token (004 cases) | integration | positive+negative | yes | OK | spec 004-role-names, §2.1-§2.3, §2.5, §2.6; slice 2 tests, kept as is |
| backend/src/test/java/app/nadel/acceptance/MembersAccessAcceptanceTests.java | renamingEveryBaseRoleDoesNotChangeWhoCanDoWhat | integration | positive | yes | OK | spec 004-role-names, §2.2 #1 |
| web/src/roleName.test.ts, web/src/roleNameClash.test.ts, web/src/roleNameSubmit.test.ts | display name, audit token rendering, clash detection, stored-name submit guard | unit | positive+negative | yes | OK | spec 004-role-names, §2.3, §2.4, §2.6, §2.7 |
| web/e2e/acceptance.spec.ts | 004 §2.1-§2.3: a base role is renamed, shown everywhere, restored, audited readably (English) | e2e | positive | yes | OK | spec 004-role-names |
| web/e2e/role-names.spec.ts | §2.7 #1, §2.3 #1/#3, §2.4 #1, §2.1 #4, §2.2 #2 (Ukrainian: Казначей, hint, clash warning, empty name, no Delete) | e2e | positive+negative | yes | OK | spec 004-role-names |
| web/e2e/role-names.spec.ts | §2.5 #1 treasurer gets no access to the Roles page | e2e | negative | yes | OK | spec 004-role-names; API 403 in RolesTests |
| web/e2e/role-names.spec.ts | §2.1 #1, §2.6 #1-#3, §2.3 #2 rename to Бухгалтер shown in header, account, member page, members list; audit uk/en; restore | e2e | positive | yes | OK | spec 004-role-names; restores in finally |
| backend/src/test/java/app/nadel/acceptance/RoleDescriptionsAcceptanceTests.java | aChangedTreasurerDescriptionIsReadByOthersAuditedAndRestoredToTheStandardText | integration | positive | yes | OK | spec 005-ui-preferences, §2.3 #2, #3, #5 (list, flag, audit read by the treasurer, restore); per-case rules stay in RolesTests |
| backend/src/test/java/app/nadel/acceptance/RoleDescriptionsAcceptanceTests.java | theTreasurerCannotChangeOrRestoreADescriptionAndTheRolesListStaysClosedToThem | integration | negative | yes | OK | §2.3 #6 (403 PERMISSION_DENIED on change and restore; partial overlap with RolesTests.aTreasurerWithoutRolesManage...) |
| backend/src/test/java/app/nadel/acceptance/RoleDescriptionsAcceptanceTests.java | aRestoreFlagOnACustomRoleIsAValidationErrorAndNothingChanges | integration | negative | yes | OK | 005 tech spec 2.3 (useDefaultDescription on a custom role, 400 INVALID) |
| web/e2e/ui-preferences.spec.ts | §2.1 #1-#6, §2.2 #1-#3, §2.3 #1-#5 (theme switch, header title, Ukrainian role descriptions, restore, audit; 10 specs) | e2e | positive+negative | yes | OK | spec 005-ui-preferences; Treasurer description restored in finally |
| web/e2e/ui-preferences-acceptance.spec.ts | §2.1 #6 Tab through System/Light/Dark with outline, pressed state, Enter selects | e2e | positive | yes | OK | spec 005; partial overlap with the shorter Shift+Tab spec in ui-preferences.spec.ts |
| web/e2e/ui-preferences-acceptance.spec.ts | §2.2 #3 long cooperative name: ellipsis, title attribute, full name in tab, phone width (mocked /me, 2 specs) | e2e | negative | yes | OK | spec 005; no data changed |
| web/e2e/ui-preferences-acceptance.spec.ts | §2.2 #4 operator sees "No cooperative — Nadel" in the chosen theme | e2e | negative | yes | OK | spec 005 |
| web/e2e/ui-preferences-acceptance.spec.ts | §2.3 #1 English base role descriptions on the Roles page | e2e | positive | yes | OK | spec 005; Ukrainian ones in ui-preferences.spec.ts |
| web/src/theme.unit.test.tsx, web/src/theme.test.ts, web/src/components/Layout.test.tsx, web/src/useDocumentTitle.test.tsx, web/src/roleName.test.ts, web/src/roleDescriptionSubmit.test.ts, web/src/roleRestore.test.ts | theme storage/apply/switch, CSS token sync, header title and fallback, tab title, role description display and submit, restore | unit | positive+negative | yes | OK | spec 005-ui-preferences, §2.1-§2.4 (slice tests, kept as is) |
| packages/ui-tokens/src/contrast.test.ts | contrast of every text/background pair in both themes (003 test) | unit | positive | yes | OK | spec 005 §2.4 #1, owned by 003 |
| backend/src/test/java/app/nadel/acceptance/PlotsRegistryAcceptanceTests.java | aPlotIsCreatedReadByThreeKindsOfViewerArchivedRestoredDeletedAndAudited | integration | positive | yes | OK | spec 006-plots-registry, §2.1 #1, §2.2 #2/#4, §2.3 #2/#4/#5/#6, §2.5 #1/#2: journey chairman, treasurer, member (list, card, map), archived delete 409, audit via API |
| backend/src/test/java/app/nadel/acceptance/PlotsRegistryAcceptanceTests.java | everyRefusedSaveNamesItsFieldAndSavesNothingWhileAnArchivedNumberIsFree | integration | negative | yes | OK | §2.1 #2-#5, §2.3 #3, §2.4 #1 (server side); partial overlap with PlotsWriteTests (per-case rules stay there) |
| backend/src/test/java/app/nadel/acceptance/PlotsRegistryAcceptanceTests.java | anAreaTypedInSquareMetresOrSotkasIsStoredAsSquareMetresWithoutRounding | integration | positive+negative | yes | OK | §2.1 #6 (600 m² / 6.5 sotkas stored as m², 3 decimals refused) |
| backend/src/test/java/app/nadel/acceptance/PlotsRegistryAcceptanceTests.java | theDemoCooperativeHasThirtyPlotsOnTwoStreetsAndARoadAllGreyListedInPages | integration | positive | yes | OK | §2.2 #2/#3/#5, §2.6 #1 (30 plots, 2 streets, 1 road, all GREY, pages continue in order) |
| backend/src/test/java/app/nadel/acceptance/PlotsRegistryAcceptanceTests.java | anOrdinaryMemberSeesEveryDemoPlotAsNumberStreetKindAndStatusOnly | integration | negative | yes | OK | §2.2 #4 on the demo data |
| web/e2e/plots-acceptance.spec.ts | §2.1 #1, #6 in Ukrainian: "6 соток", 600 m² and "6,5 сотки" with "Без власника" | e2e | positive | yes | OK | spec 006-plots-registry; ids from the POST response, cleanup via API |
| web/e2e/plots-acceptance.spec.ts | §2.1 grey marks in Ukrainian ("Без власника", "Спільні землі") and kind filter | e2e | positive | yes | OK | spec 006 |
| web/e2e/plots-acceptance.spec.ts | §2.2 #3, #5, #6, §2.6 #1: kind filter, paging, nothing found, error with Try again, demo cooperative | e2e | positive+negative | yes | OK | spec 006; error state via route mock |
| web/e2e/plots-acceptance.spec.ts | §2.2 #4, §2.3 #1: member never sees a note; edit form closed, API 403 | e2e | negative | yes | OK | spec 006 |
| web/e2e/plots-acceptance.spec.ts | §2.3 #6: treasurer sees no Add plot or form; archive/restore/delete refused with PERMISSION_DENIED | e2e | negative | yes | OK | spec 006; no data changed |
| web/e2e/plots-acceptance.spec.ts | §2.3 #3: restore refused with the number message when the number was taken meanwhile | e2e | negative | yes | OK | spec 006; also §2.1 #3 (archived number is free) |
| web/e2e/plots.spec.ts | treasurer list/search/card (incl. note of plot 9); member restricted; chairman create-edit-archive-restore-delete and audit | e2e | positive+negative | yes | OK | spec 006 slice 5 tests; hardened in slice 6 (id from POST response, archived delete asserts 409, cleanup cannot mask a failure) |
| backend/src/test/java/app/nadel/plots/internal/*.java, web/src/pages/Plot*.test.tsx, web/src/components/PlotForm.test.tsx, web/src/plotStatus.test.ts, packages/shared/src/area.test.ts | plots slice tests (read, write, map, isolation, natural sort, cadastral, snapshot, status, form, pages, area helpers) | unit+integration | positive+negative | yes | OK | spec 006 slices 1-4, kept as is |
| backend/src/test/java/app/nadel/imports/internal/ImportAcceptanceTests.java | section21_theDownloadedTemplateUploadsAsNothingToImportWithoutErrors | integration | positive | yes | OK | spec 008-excel-import, §2.1 #1, #2 (downloaded template re-uploaded) |
| backend/src/test/java/app/nadel/imports/internal/ImportAcceptanceTests.java | section21_ukrainianHeadersInAnotherOrderAndCaseAreReadAndSixSotkasAreSixHundredSquareMetres | integration | positive | yes | OK | §2.1 #3, #5 (read back through GET /plots) |
| backend/src/test/java/app/nadel/imports/internal/ImportAcceptanceTests.java | section21_bothAreasOrNeitherIsAnErrorOnTheAreaColumn | integration | negative | yes | OK | §2.1 #4 |
| backend/src/test/java/app/nadel/imports/internal/ImportAcceptanceTests.java | section22_theValidExampleAndTheMistakesFileAreChecked | integration | positive+negative | yes | OK | §2.2 #1, #2 |
| backend/src/test/java/app/nadel/imports/internal/ImportAcceptanceTests.java | section22_refusedFilesGiveClearCodesAndLeaveNothingBehind | integration | negative | yes | OK | §2.2 #3-#5 (10 MB, CSV, PDF, 2001 plots) |
| backend/src/test/java/app/nadel/imports/internal/ImportAcceptanceTests.java | section22_uploadingWithoutCommittingLeavesThePlotsListAsItWas | integration | negative | yes | OK | §2.2 #6 |
| backend/src/test/java/app/nadel/imports/internal/ImportAcceptanceTests.java | section23And25_theCommittedExampleShowsInThePlotsListTheHistoryAndTheAuditLog | integration | positive | yes | OK | §2.3 #1, §2.5 #1, #2 (plots list, history, audit API) |
| backend/src/test/java/app/nadel/imports/internal/ImportAcceptanceTests.java | section24_aSecondUploadIsUnchangedAndEditsUpdateInsteadOfDuplicating | integration | positive | yes | OK | §2.4 #1, #2, #3, #5 |
| backend/src/test/java/app/nadel/imports/internal/ImportAcceptanceTests.java | section24_aDifferentOwnerOfAnOwnedPlotIsAnErrorPointingToChangeOwner | integration | negative | yes | OK | §2.4 #4 |
| backend/src/test/java/app/nadel/imports/internal/ImportAcceptanceTests.java | section25_theTreasurerAndAMemberAreRefusedEverywhereAndNothingIsSaved | integration | negative | yes | OK | §2.5 #3 (API side) |
| backend/src/test/java/app/nadel/imports/internal/ImportAcceptanceTests.java | section25_anImportOfOneCooperativeNeverTouchesAnotherAndItsPeopleSeeNothingOfIt | integration | negative | yes | OK | §2.5 #4; partial overlap with ImportCommitTests, ImportRunTenantIsolationTests |
| web/e2e/imports-acceptance.spec.ts | §2.1 #3, #5, §2.4 #2 Ukrainian headers, "6 соток (600 м²)", street Garden St to Orchard St | e2e | positive | yes | OK | spec 008-excel-import; plots archived in finally |
| web/e2e/imports-acceptance.spec.ts | §2.2 #4, #5 CSV and 2,001 plots refused with the Ukrainian messages | e2e | negative | yes | OK | spec 008 |
| web/e2e/imports-acceptance.spec.ts | §2.4 #4 owned plot 9 gives the "змініть власника" error, Import disabled | e2e | negative | yes | OK | spec 008 |
| backend/src/test/java/app/nadel/imports/internal/*.java (other), web/e2e/imports.spec.ts, web/src/importFile.test.ts, web/src/pages/ImportPage.test.tsx, web/src/components/import/*.test.tsx | import slice tests (preview, commit, history, limits, template, isolation, UI) | unit+integration+e2e | positive+negative | yes | OK | spec 008-excel-import, slices 1-7, kept as is |
