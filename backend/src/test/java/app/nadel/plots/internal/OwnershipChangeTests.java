package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** POST /plots/{id}/ownership-changes: the change end to end, every refusal, permissions, status and audit. */
class OwnershipChangeTests extends OwnershipTestSupport {

    /** An existing demo member that is archived (Honcharenko, sold plot 9 in 2025). */
    private static final UUID ARCHIVED_MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000020");

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    // ---- the change end to end

    @Test
    void soldPlotGetsTwoCoOwnersAndThePreviousOwnerMovesToTheHistory() throws Exception {
        UUID plot = demoPlot("9");
        UUID anna = demoMember("Alpha", "Anna");
        UUID boris = demoMember("Beta", "Boris");

        change(chairman(), plot, "2026-03-01", List.of(owner(boris, "1/2"), owner(anna, "1/2")), "Sale contract of 2026-02-20")
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.plotId").value(plot.toString()))
                .andExpect(jsonPath("$.current", hasSize(2)))
                .andExpect(jsonPath("$.current[*].memberName", contains("Alpha Anna", "Beta Boris")))
                .andExpect(jsonPath("$.current[*].share", contains("1/2", "1/2")))
                .andExpect(jsonPath("$.current[*].validFrom", everyItem(is("2026-03-01"))))
                .andExpect(jsonPath("$.current[*].validTo").doesNotExist())
                .andExpect(jsonPath("$.current[*].basis", everyItem(is("Sale contract of 2026-02-20"))))
                .andExpect(jsonPath("$.history", hasSize(2)))
                .andExpect(jsonPath("$.history[0].memberName").value("Lytvynenko Svitlana"))
                .andExpect(jsonPath("$.history[0].validFrom").value("2025-06-01"))
                .andExpect(jsonPath("$.history[0].validTo").value("2026-02-28"))
                .andExpect(jsonPath("$.history[1].memberName").value("Honcharenko Mykola"))
                .andExpect(jsonPath("$.history[1].validTo").value("2025-05-31"));

        // the same picture from the read endpoint, and the new rows belong to the cooperative of the token
        ownerships(treasurer(), plot)
                .andExpect(jsonPath("$.current[*].memberName", contains("Alpha Anna", "Beta Boris")))
                .andExpect(jsonPath("$.history[0].validTo").value("2026-02-28"));
        assertThat(ownershipRows(plot)).extracting(row -> row.get("tenant_id")).containsOnly(DEMO_TENANT);
        assertThat(statusOf(plot)).isEqualTo("GREEN");
    }

    @Test
    void theFirstOwnerOfAPlotWithoutOwnersTurnsItGreenWithAWholeShareByDefault() throws Exception {
        UUID plot = demoPlot("25");
        UUID anna = demoMember("Alpha", "Anna");
        String treasurer = treasurer();
        card(treasurer, plot).andExpect(jsonPath("$.status").value("GREY"));

        // no "share" key: the default is 1/1
        mvc.perform(post(PLOTS + "/" + plot + "/ownership-changes")
                        .header(HttpHeaders.AUTHORIZATION, chairman())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveFrom\":\"2026-02-01\",\"owners\":[{\"memberId\":\"" + anna + "\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.current", hasSize(1)))
                .andExpect(jsonPath("$.current[0].share").value("1/1"))
                .andExpect(jsonPath("$.current[0].basis").doesNotExist())
                .andExpect(jsonPath("$.history", empty()));

        card(treasurer, plot).andExpect(jsonPath("$.status").value("GREEN"));
        listPlots(treasurer, "status=GREEN&size=100").andExpect(jsonPath("$.items[?(@.number == '25')]", hasSize(1)));
        assertThat(statusOf(plot)).isEqualTo("GREEN");
    }

    @Test
    void anEmptyOwnerListEndsOwnershipAndTheMemberStaysInTheHistory() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        seedOwner(plot, anna, "1/1", "2020-01-01", null);
        assertThat(statusOf(plot)).isEqualTo("GREEN");

        change(chairman(), plot, "2026-03-01", List.of(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.current", empty()))
                .andExpect(jsonPath("$.history", hasSize(1)))
                .andExpect(jsonPath("$.history[0].memberName").value("Alpha Anna"))
                .andExpect(jsonPath("$.history[0].validTo").value("2026-02-28"));

        assertThat(statusOf(plot)).isEqualTo("GREY");
        card(treasurer(), plot).andExpect(jsonPath("$.status").value("GREY"));
    }

    @Test
    void theNewOwnersStartOnTheDateAndThePreviousOnesEndTheDayBefore() throws Exception {
        String chairman = chairman();
        UUID anna = demoMember("Alpha", "Anna");
        UUID boris = demoMember("Beta", "Boris");

        // 1 January: the previous year ends; 1 March of a leap year: 29 February; an ordinary day
        for (String[] dates : new String[][] {{"2026-01-01", "2025-12-31"}, {"2024-03-01", "2024-02-29"},
                {"2026-03-01", "2026-02-28"}, {"2026-07-15", "2026-07-14"}}) {
            UUID plot = newPlot();
            seedOwner(plot, anna, "1/1", "2020-01-01", null);

            change(chairman, plot, dates[0], List.of(owner(boris, "1/1")), null).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.current[0].validFrom").value(dates[0]))
                    .andExpect(jsonPath("$.history[0].memberName").value("Alpha Anna"))
                    .andExpect(jsonPath("$.history[0].validTo").value(dates[1]));
        }
    }

    @Test
    void theChangeMayBeDatedTodayInTheKyivCalendar() throws Exception {
        UUID plot = newPlot();
        seedOwner(plot, demoMember("Alpha", "Anna"), "1/1", "2020-01-01", null);
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Kyiv"));

        change(chairman(), plot, today.toString(), List.of(owner(demoMember("Beta", "Boris"), "1/1")), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.history[0].validTo").value(today.minusDays(1).toString()));
    }

    @Test
    void aMemberWhoStaysAnOwnerGetsANewRowFromTheDate() throws Exception {
        // the old row of the member is closed first, so the exclusion constraint never sees two open rows of one member
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID boris = demoMember("Beta", "Boris");
        seedOwner(plot, anna, "1/1", "2020-01-01", null);

        change(chairman(), plot, "2026-03-01", List.of(owner(anna, "1/2"), owner(boris, "1/2")), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.current[*].memberName", contains("Alpha Anna", "Beta Boris")))
                .andExpect(jsonPath("$.history", hasSize(1)))
                .andExpect(jsonPath("$.history[0].memberName").value("Alpha Anna"))
                .andExpect(jsonPath("$.history[0].share").value("1/1"))
                .andExpect(jsonPath("$.history[0].validTo").value("2026-02-28"));
    }

    @Test
    void aPlotThatLostItsOwnersGetsNewOnesLaterAndKeepsAllTheHistory() throws Exception {
        UUID plot = newPlot();
        seedOwner(plot, demoMember("Alpha", "Anna"), "1/1", "2020-01-01", "2025-12-31");
        String chairman = chairman();

        // a date after the end of the last owner is fine, even if the plot is empty now
        change(chairman, plot, "2026-01-01", List.of(owner(demoMember("Beta", "Boris"), "1/1")), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.current", hasSize(1)))
                .andExpect(jsonPath("$.history", hasSize(1)))
                .andExpect(jsonPath("$.history[0].validTo").value("2025-12-31"));
        assertThat(statusOf(plot)).isEqualTo("GREEN");
    }

    @Test
    void threeThirdsAreAcceptedAndStoredAsThirds() throws Exception {
        UUID plot = newPlot();
        change(chairman(), plot, "2026-03-01", List.of(owner(demoMember("Alpha", "Anna"), "1/3"),
                owner(demoMember("Beta", "Boris"), "1/3"), owner(demoMember("Gamma", "Hanna"), "1/3")), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.current[*].share", contains("1/3", "1/3", "1/3")));
    }

    @Test
    void aShareIsReducedToLowestTerms() throws Exception {
        UUID plot = newPlot();
        change(chairman(), plot, "2026-03-01", List.of(owner(demoMember("Alpha", "Anna"), "2/4"),
                owner(demoMember("Beta", "Boris"), "3/6")), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.current[*].share", contains("1/2", "1/2")));
        assertThat(ownershipRows(plot)).extracting(row -> row.get("share")).containsOnly("1/2");
    }

    @Test
    void aBlankBasisIsNotStored() throws Exception {
        UUID plot = newPlot();
        change(chairman(), plot, "2026-03-01", List.of(owner(demoMember("Alpha", "Anna"), "1/1")), "   ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.current[0].basis").doesNotExist());
    }

    // ---- refusals: the code, the field, and nothing changes

    @Test
    void sharesThatDoNotAddUpToOneAreRefused() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID boris = demoMember("Beta", "Boris");
        seedOwner(plot, anna, "1/1", "2020-01-01", null);
        List<Map<String, Object>> before = ownershipRows(plot);

        for (List<Map<String, Object>> owners : List.of(
                List.of(owner(anna, "1/2"), owner(boris, "1/3")),
                List.of(owner(anna, "2/3"), owner(boris, "2/3")),
                List.of(owner(boris, "1/2")))) {
            change(chairman(), plot, "2026-03-01", owners, null)
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("SHARES_NOT_ONE"))
                    .andExpect(jsonPath("$.errors[0].field").value("owners"))
                    .andExpect(jsonPath("$.errors[0].code").value("SHARES_NOT_ONE"));
        }
        assertThat(ownershipRows(plot)).isEqualTo(before);
        assertThat(statusOf(plot)).isEqualTo("GREEN");
    }

    @Test
    void aMemberListedTwiceIsRefusedOnTheSecondRow() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");

        change(chairman(), plot, "2026-03-01", List.of(owner(anna, "1/2"), owner(demoMember("Beta", "Boris"), "1/4"),
                owner(anna, "1/4")), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_OWNER"))
                .andExpect(jsonPath("$.errors[0].field").value("owners[2].memberId"))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE_OWNER"));
        assertThat(ownershipRows(plot)).isEmpty();
    }

    @Test
    void invalidSharesAreABadRequestWithTheRowOfEachOne() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID boris = demoMember("Beta", "Boris");
        UUID hanna = demoMember("Gamma", "Hanna");
        String chairman = chairman();

        // bigger than a whole, denominator over 1000: the shape fits n/d, the bounds do not
        change(chairman, plot, "2026-03-01", List.of(owner(anna, "1/2"), owner(boris, "3/2"), owner(hanna, "1/1001")),
                null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("owners[1].share"))
                .andExpect(jsonPath("$.errors[0].code").value("SHARE_INVALID"))
                .andExpect(jsonPath("$.errors[1].field").value("owners[2].share"))
                .andExpect(jsonPath("$.errors[1].code").value("SHARE_INVALID"));
        // not a fraction at all (rejected by the contract's pattern, with the same code)
        for (String share : List.of("abc", "0.5", "1/0", "0/2", "-1/2", " 1/2", "1/2 ", "1 / 2")) {
            change(chairman, plot, "2026-03-01", List.of(owner(anna, "1/2"), owner(boris, share)), null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("owners[1].share"))
                    .andExpect(jsonPath("$.errors[0].code").value("SHARE_INVALID"));
        }
        assertThat(ownershipRows(plot)).isEmpty();
    }

    @Test
    void aMemberThatDoesNotExistOrBelongsToAnotherCooperativeIsABadRequest() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID foreign = newMember(OTHER_TENANT, "Foreign", "Person", null, "foreign-" + UUID.randomUUID() + "@other.local");

        change(chairman(), plot, "2026-03-01", List.of(owner(anna, "1/2"), owner(foreign, "1/4"),
                owner(UUID.randomUUID(), "1/4")), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("owners[1].memberId"))
                .andExpect(jsonPath("$.errors[0].code").value("MEMBER_NOT_FOUND"))
                .andExpect(jsonPath("$.errors[1].field").value("owners[2].memberId"))
                .andExpect(content().string(not(containsString("Foreign"))));
        assertThat(ownershipRows(plot)).isEmpty();
    }

    @Test
    void anArchivedMemberCannotBecomeAnOwner() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");

        change(chairman(), plot, "2026-03-01", List.of(owner(anna, "1/2"), owner(ARCHIVED_MEMBER, "1/2")), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_ARCHIVED"))
                .andExpect(jsonPath("$.errors[0].field").value("owners[1].memberId"))
                .andExpect(jsonPath("$.errors[0].code").value("MEMBER_ARCHIVED"))
                .andExpect(content().string(not(containsString("Honcharenko"))));
        assertThat(ownershipRows(plot)).isEmpty();
    }

    @Test
    void aDateInTheFutureIsRefused() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        seedOwner(plot, anna, "1/1", "2020-01-01", null);
        List<Map<String, Object>> before = ownershipRows(plot);
        LocalDate tomorrow = LocalDate.now(ZoneId.of("Europe/Kyiv")).plusDays(1);

        change(chairman(), plot, tomorrow.toString(), List.of(owner(demoMember("Beta", "Boris"), "1/1")), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNERSHIP_DATE_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("effectiveFrom"))
                .andExpect(jsonPath("$.errors[0].code").value("OWNERSHIP_DATE_INVALID"));
        assertThat(ownershipRows(plot)).isEqualTo(before);
        assertThat(statusOf(plot)).isEqualTo("GREEN");
    }

    @Test
    void aDateNotAfterTheStartOfACurrentOwnerIsRefused() throws Exception {
        UUID plot = newPlot();
        seedOwner(plot, demoMember("Alpha", "Anna"), "1/1", "2025-06-01", null);
        UUID boris = demoMember("Beta", "Boris");
        List<Map<String, Object>> before = ownershipRows(plot);

        for (String date : List.of("2025-06-01", "2025-05-31", "2019-01-01")) {
            change(chairman(), plot, date, List.of(owner(boris, "1/1")), null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("OWNERSHIP_DATE_INVALID"))
                    .andExpect(jsonPath("$.errors[0].field").value("effectiveFrom"));
        }
        assertThat(ownershipRows(plot)).isEqualTo(before);
        // the day after the start is the earliest possible date
        change(chairman(), plot, "2025-06-02", List.of(owner(boris, "1/1")), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.history[0].validFrom").value("2025-06-01"))
                .andExpect(jsonPath("$.history[0].validTo").value("2025-06-01"));
    }

    @Test
    void aDateNotAfterTheEndOfAPastOwnerIsRefused() throws Exception {
        UUID plot = newPlot();
        seedOwner(plot, demoMember("Alpha", "Anna"), "1/1", "2015-06-01", "2025-05-31");
        UUID boris = demoMember("Beta", "Boris");

        change(chairman(), plot, "2025-05-31", List.of(owner(boris, "1/1")), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNERSHIP_DATE_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("effectiveFrom"));
        // the same refusal for demo plot 9, which was sold on 2025-06-01 (its current owner starts then)
        change(chairman(), demoPlot("9"), "2025-06-01", List.of(owner(boris, "1/1")), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNERSHIP_DATE_INVALID"));
        assertThat(ownershipRows(demoPlot("9"))).hasSize(2);
    }

    @Test
    void anArchivedPlotAndCommonLandCannotChangeOwners() throws Exception {
        UUID anna = demoMember("Alpha", "Anna");
        UUID archived = newPlot();
        archivePlot(archived);
        UUID commonLand = newPlot("COMMON");

        change(chairman(), archived, "2026-03-01", List.of(owner(anna, "1/1")), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_ARCHIVED"));
        change(chairman(), commonLand, "2026-03-01", List.of(owner(anna, "1/1")), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_COMMON_LAND"));
        // an empty list is refused for common land as well
        change(chairman(), commonLand, "2026-03-01", List.of(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_COMMON_LAND"));
        assertThat(ownershipRows(archived)).isEmpty();
        assertThat(ownershipRows(commonLand)).isEmpty();
    }

    @Test
    void aPlotOfAnotherCooperativeAndAnUnknownPlotAreNotFound() throws Exception {
        UUID foreign = ownerJdbc().queryForObject(
                "select id from plot where tenant_id = ? and number = '1'", UUID.class, OTHER_TENANT);
        UUID anna = demoMember("Alpha", "Anna");
        String chairman = chairman();

        change(chairman, foreign, "2026-03-01", List.of(owner(anna, "1/1")), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(content().string(not(containsString("Melnyk"))));
        change(chairman, UUID.randomUUID(), "2026-03-01", List.of(owner(anna, "1/1")), null)
                .andExpect(status().isNotFound());
        assertThat(ownershipRows(foreign)).hasSize(1);
    }

    @Test
    void theRequestIsValidatedAtTheBoundary() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        String chairman = chairman();

        raw(chairman, plot, "{\"owners\":[]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("effectiveFrom"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        raw(chairman, plot, "{\"effectiveFrom\":\"2026-03-01\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("owners"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        raw(chairman, plot, "{\"effectiveFrom\":\"01.03.2026\",\"owners\":[]}").andExpect(status().isBadRequest());
        raw(chairman, plot, "{\"effectiveFrom\":\"2026-03-01\",\"owners\":[{\"share\":\"1/1\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("owners[0].memberId"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add(owner(UUID.randomUUID(), "1/21"));
        }
        change(chairman, plot, "2026-03-01", tooMany, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("owners"));
        change(chairman, plot, "2026-03-01", List.of(owner(anna, "1/1")), "x".repeat(501))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("basis"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        raw(chairman, plot, "not json").andExpect(status().isBadRequest());
        mvc.perform(post(PLOTS + "/not-a-uuid/ownership-changes").header(HttpHeaders.AUTHORIZATION, chairman)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"effectiveFrom\":\"2026-03-01\",\"owners\":[]}"))
                .andExpect(status().isBadRequest());
        assertThat(ownershipRows(plot)).isEmpty();
    }

    @Test
    void theCooperativeComesFromTheTokenNotFromTheBody() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");

        raw(chairman(), plot, "{\"effectiveFrom\":\"2026-03-01\",\"tenantId\":\"" + OTHER_TENANT
                + "\",\"owners\":[{\"memberId\":\"" + anna + "\",\"tenant_id\":\"" + OTHER_TENANT + "\"}]}")
                .andExpect(status().isCreated());
        assertThat(ownershipRows(plot)).extracting(row -> row.get("tenant_id")).containsOnly(DEMO_TENANT);
    }

    // ---- permissions

    @Test
    void onlyThoseWhoMayViewPlotsAndEditOwnershipCanChangeOwners() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        List<Map<String, Object>> owners = List.of(owner(anna, "1/1"));

        // the treasurer sees plots but has no OWNERSHIP_EDIT; a member has neither; a custom role may have one only
        for (String bearer : List.of(treasurer(), member(), customRoleToken("OWNERSHIP_EDIT"),
                customRoleToken("PLOTS_VIEW", "PLOTS_EDIT", "MEMBERS_VIEW"))) {
            change(bearer, plot, "2026-03-01", owners, null)
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        assertThat(ownershipRows(plot)).isEmpty();
    }

    @Test
    void permissionsDecideNotRoleNames() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");

        change(customRoleToken("PLOTS_VIEW", "OWNERSHIP_EDIT"), plot, "2026-03-01", List.of(owner(anna, "1/1")), null)
                .andExpect(status().isCreated());
    }

    @Test
    void thePlatformOperatorAndAnAnonymousCallerAreRefused() throws Exception {
        UUID plot = newPlot();
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();
        String body = "{\"effectiveFrom\":\"2026-03-01\",\"owners\":[]}";

        raw(operator, plot, body).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mvc.perform(post(PLOTS + "/" + plot + "/ownership-changes").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---- audit

    @Test
    void theChangeIsOneAuditEntryNamingTheDateTheOwnersAndTheBasis() throws Exception {
        UUID plot = demoPlot("9");
        UUID anna = demoMember("Alpha", "Anna");
        UUID boris = demoMember("Beta", "Boris");

        change(chairman(), plot, "2026-03-01", List.of(owner(boris, "1/3"), owner(anna, "2/3")), "Inheritance")
                .andExpect(status().isCreated());

        List<Map<String, Object>> entries = awaitAudit(plot, 1);
        Map<String, Object> entry = entries.getFirst();
        assertThat(entry.get("action")).isEqualTo("OWNERSHIP_CHANGED");
        assertThat(entry.get("entity_type")).isEqualTo("OWNERSHIP");
        assertThat(entry.get("entity_label")).isEqualTo("9");
        assertThat(entry.get("actor_member_id")).isNotNull();
        JsonNode changes = json.readTree((String) entry.get("changes"));
        assertThat(changes).hasSize(3);
        assertThat(isNull(field(changes, "effectiveFrom"), "old")).isTrue();
        assertThat(field(changes, "effectiveFrom").get("new").asString()).isEqualTo("2026-03-01");
        assertThat(field(changes, "owners").get("old").asString()).isEqualTo("[{\"member\":\"Lytvynenko Svitlana\",\"share\":\"1/1\"}]");
        assertThat(field(changes, "owners").get("new").asString()).isEqualTo("[{\"member\":\"Alpha Anna\",\"share\":\"2/3\"},"
                + "{\"member\":\"Beta Boris\",\"share\":\"1/3\"}]");
        assertThat(isNull(field(changes, "basis"), "old")).isTrue();
        assertThat(field(changes, "basis").get("new").asString()).isEqualTo("Inheritance");
        // and the audit log lists it under the new entity type
        mvc.perform(get("/api/v1/audit?entityType=OWNERSHIP&entityId=" + plot)
                        .header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].action").value("OWNERSHIP_CHANGED"))
                .andExpect(jsonPath("$.items[0].entityType").value("OWNERSHIP"))
                .andExpect(jsonPath("$.items[0].entityLabel").value("9"));
    }

    @Test
    void endingOwnershipIsAuditedWithoutNewOwnersAndFirstOwnersWithoutOldOnes() throws Exception {
        UUID ended = newPlot();
        seedOwner(ended, demoMember("Alpha", "Anna"), "1/1", "2020-01-01", null);
        UUID first = newPlot();
        String chairman = chairman();

        change(chairman, ended, "2026-03-01", List.of(), null).andExpect(status().isCreated());
        change(chairman, first, "2026-03-01", List.of(owner(demoMember("Beta", "Boris"), "1/1")), null)
                .andExpect(status().isCreated());

        JsonNode endedChanges = json.readTree((String) awaitAudit(ended, 1).getFirst().get("changes"));
        assertThat(endedChanges).hasSize(2); // no basis was given
        assertThat(field(endedChanges, "owners").get("old").asString())
                .isEqualTo("[{\"member\":\"Alpha Anna\",\"share\":\"1/1\"}]");
        assertThat(isNull(field(endedChanges, "owners"), "new")).isTrue();
        JsonNode firstChanges = json.readTree((String) awaitAudit(first, 1).getFirst().get("changes"));
        assertThat(isNull(field(firstChanges, "owners"), "old")).isTrue();
        assertThat(field(firstChanges, "owners").get("new").asString())
                .isEqualTo("[{\"member\":\"Beta Boris\",\"share\":\"1/1\"}]");
    }

    @Test
    void aRefusedChangeWritesNoAuditEntry() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");

        change(chairman(), plot, "2026-03-01", List.of(owner(anna, "1/2")), null).andExpect(status().isConflict());

        Thread.sleep(500); // an event, if one were published, would be written within this time
        assertThat(auditEntries(plot)).isEmpty();
    }

    // ---- helpers

    private static Map<String, Object> owner(UUID member, String share) {
        Map<String, Object> owner = new LinkedHashMap<>();
        owner.put("memberId", member);
        owner.put("share", share);
        return owner;
    }

    private ResultActions change(String bearer, UUID plot, String effectiveFrom, List<Map<String, Object>> owners,
            String basis) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("effectiveFrom", effectiveFrom);
        body.put("owners", owners);
        if (basis != null) {
            body.put("basis", basis);
        }
        return raw(bearer, plot, json.writeValueAsString(body));
    }

    private ResultActions raw(String bearer, UUID plot, String body) throws Exception {
        return mvc.perform(post(PLOTS + "/" + plot + "/ownership-changes")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions ownerships(String bearer, UUID plot) throws Exception {
        return mvc.perform(get(PLOTS + "/" + plot + "/ownerships").header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions card(String bearer, UUID plot) throws Exception {
        return mvc.perform(get(PLOTS + "/" + plot).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions listPlots(String bearer, String query) throws Exception {
        return mvc.perform(get(PLOTS + "?" + query).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    /** Waits until the plot has {@code count} audit entries (written asynchronously) and returns them, oldest first. */
    private List<Map<String, Object>> awaitAudit(UUID plot, int count) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(auditEntries(plot)).hasSize(count));
        return auditEntries(plot);
    }

    private List<Map<String, Object>> auditEntries(UUID plot) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForList(
                "select action, actor_member_id, entity_type, entity_label, cast(changes as text) as changes "
                        + "from audit_entry where entity_id = ? order by occurred_at, id", plot));
    }

    /** A value that is absent or null: the stored change keeps an explicit null for an empty side. */
    private static boolean isNull(JsonNode change, String side) {
        return change.get(side) == null || change.get(side).isNull();
    }

    private static JsonNode field(JsonNode changes, String name) {
        for (JsonNode change : changes) {
            if (name.equals(change.get("field").asString())) {
                return change;
            }
        }
        throw new AssertionError("no change for " + name);
    }
}
