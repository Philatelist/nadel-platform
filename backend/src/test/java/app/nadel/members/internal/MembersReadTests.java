package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GET /members and GET /members/{id}: permission, search, paging, archived filter and visibility.
 * <p>
 * Almost everything runs on fixture members with a random token in their names, so the tests do not depend on the
 * size of the demo data. The demo data is used in two places only, both documented where used: the other
 * cooperative's list ({@code bbbbbbbb-...-01}) and the Secretary custom role of Polishchuk Oksana.
 */
class MembersReadTests extends IntegrationTest {

    static final UUID DEMO_MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
    static final UUID DEMO_SECRETARY = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000009");
    static final UUID OTHER_MEMBER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    // ---- permission -------------------------------------------------------------------------------------------

    @Test
    void listIsAllowedForAHolderOfMembersView() throws Exception {
        String token = viewer(DEMO_TENANT);

        getAs(token, "/api/v1/members?size=5")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.totalItems").isNumber())
                .andExpect(jsonPath("$.totalPages").isNumber());
    }

    @Test
    void listIsDeniedWithoutMembersView() throws Exception {
        UUID plain = newMember(DEMO_TENANT, "plain-" + UUID.randomUUID() + "@demo.local");
        giveBaseRole(DEMO_TENANT, plain, "MEMBER");
        String token = TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), emailOf(DEMO_TENANT, plain));

        getAs(token, "/api/v1/members")
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void listIsDeniedForThePlatformOperator() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        getAs(operator, "/api/v1/members")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void listRequiresAToken() throws Exception {
        mvc.perform(get("/api/v1/members"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---- list content -----------------------------------------------------------------------------------------

    @Test
    void listRowCarriesContactsAndRolesButNoNotesOrAddress() throws Exception {
        String token = viewer(DEMO_TENANT);
        String tag = tag();
        UUID member = newMember(DEMO_TENANT, tag + "Row", "Olena", "Ivanivna", tag + "@demo.local",
                "+380501234567", "+380931234567");
        giveBaseRole(DEMO_TENANT, member, "MEMBER");
        giveBaseRole(DEMO_TENANT, member, "TREASURER");
        inTenant(DEMO_TENANT, sql -> sql.update(
                "update member set notes = 'private note', postal_address = 'private address' where id = ?", member));

        getAs(token, "/api/v1/members?q=" + tag)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(member.toString()))
                .andExpect(jsonPath("$.items[0].lastName").value(tag + "Row"))
                .andExpect(jsonPath("$.items[0].firstName").value("Olena"))
                .andExpect(jsonPath("$.items[0].middleName").value("Ivanivna"))
                .andExpect(jsonPath("$.items[0].email").value(tag + "@demo.local"))
                .andExpect(jsonPath("$.items[0].phones[0]").value("+380501234567"))
                .andExpect(jsonPath("$.items[0].phones[1]").value("+380931234567"))
                .andExpect(jsonPath("$.items[0].deliveryChannel").value("PAPER"))
                .andExpect(jsonPath("$.items[0].archived").value(false))
                .andExpect(jsonPath("$.items[0].roles", hasSize(2)))
                .andExpect(jsonPath("$.items[0].roles[0].name").value("Member"))
                .andExpect(jsonPath("$.items[0].roles[0].baseCode").value("MEMBER"))
                .andExpect(jsonPath("$.items[0].roles[1].name").value("Treasurer"))
                .andExpect(content().string(not(containsString("notes"))))
                .andExpect(content().string(not(containsString("private note"))))
                .andExpect(content().string(not(containsString("private address"))));
    }

    @Test
    void listShowsACustomRoleWithoutBaseCode() throws Exception {
        // Demo data: Polishchuk Oksana (09) holds the custom Secretary role plus the base Member role.
        getAs(viewer(DEMO_TENANT), "/api/v1/members?q=polishchuk")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + DEMO_SECRETARY + "')]", hasSize(1)))
                .andExpect(jsonPath("$.items[?(@.id == '" + DEMO_SECRETARY + "')].roles[?(@.name == 'Secretary')]",
                        hasSize(1)))
                .andExpect(jsonPath("$.items[?(@.id == '" + DEMO_SECRETARY
                        + "')].roles[?(@.name == 'Secretary')].baseCode").isEmpty());
    }

    // ---- search -----------------------------------------------------------------------------------------------

    @Test
    void searchMatchesNamesAndEmailCaseInsensitively() throws Exception {
        String token = viewer(DEMO_TENANT);
        String tag = tag();
        UUID member = newMember(DEMO_TENANT, "Kovalchuk" + tag, "Svitlana", "Mykolaivna", tag + "@Demo.local".toLowerCase());

        for (String q : List.of("kovalchuk" + tag, "KOVALCHUK" + tag.toUpperCase(), "valchuk" + tag.substring(0, 3),
                "svitlan", "SVITLANA", "mykolaiv", tag + "@demo", tag.toUpperCase() + "@DEMO.LOCAL")) {
            assertThat(idsOf(getJson(token, "/api/v1/members?size=100&q=" + q)))
                    .as("q=%s", q).contains(member);
        }
        assertThat(idsOf(getJson(token, "/api/v1/members?q=" + tag + "nomatch"))).isEmpty();
    }

    @Test
    void searchMatchesCyrillicNamesCaseInsensitively() throws Exception {
        String token = viewer(DEMO_TENANT);
        String tag = tag();
        UUID member = newMember(DEMO_TENANT, "Шевченко" + tag, "Оксана", null, null);

        assertThat(idsOf(getJson(token, "/api/v1/members?q=" + "шевч"))).contains(member);
        assertThat(idsOf(getJson(token, "/api/v1/members?q=" + "ШЕВЧЕНКО" + tag.toUpperCase()))).contains(member);
        assertThat(idsOf(getJson(token, "/api/v1/members?q=" + "оксан"))).contains(member);
    }

    @Test
    void searchByDigitsMatchesAnyPhoneIgnoringFormatting() throws Exception {
        String token = viewer(DEMO_TENANT);
        UUID member = newMember(DEMO_TENANT, "Phone" + tag(), "Test", null, null,
                "+380501234567", "+38 (093) 777-88-99");

        assertThat(idsOf(getJson(token, "/api/v1/members?size=100&q=" + "+380 50 123")))
                .as("first phone, formatted query").contains(member);
        assertThat(idsOf(getJson(token, "/api/v1/members?size=100&q=" + "0937778899")))
                .as("second phone stored with punctuation").contains(member);
        assertThat(idsOf(getJson(token, "/api/v1/members?size=100&q=" + "38093")))
                .as("second phone, formatted storage").contains(member);
        assertThat(idsOf(getJson(token, "/api/v1/members?size=100&q=" + "99999999999"))).doesNotContain(member);
    }

    @Test
    void searchTreatsLikeWildcardsAsPlainCharacters() throws Exception {
        String token = viewer(DEMO_TENANT);
        String tag = tag();
        UUID underscore = newMember(DEMO_TENANT, tag + "_a", "Test", null, null);
        UUID plain = newMember(DEMO_TENANT, tag + "Xa", "Test", null, null);
        UUID percent = newMember(DEMO_TENANT, tag + "%b", "Test", null, null);

        assertThat(idsOf(getJson(token, "/api/v1/members?q=" + tag + "_"))).containsExactly(underscore);
        assertThat(idsOf(getJson(token, "/api/v1/members?q=" + tag + "%"))).containsExactly(percent);
        assertThat(idsOf(getJson(token, "/api/v1/members?q=" + tag))).containsExactlyInAnyOrder(underscore, plain, percent);
        // a lone wildcard must not behave as "match everything"
        assertThat(idsOf(getJson(token, "/api/v1/members?size=100&q=" + "%"))).doesNotContain(plain);
    }

    @Test
    void blankQueryMeansNoFilter() throws Exception {
        String token = viewer(DEMO_TENANT);
        long all = getJson(token, "/api/v1/members").path("totalItems").asLong();

        assertThat(getJson(token, "/api/v1/members?q=").path("totalItems").asLong()).isEqualTo(all);
        assertThat(getJson(token, "/api/v1/members?q=" + "   ").path("totalItems").asLong()).isEqualTo(all);
    }

    @Test
    void searchTrimsTheQuery() throws Exception {
        String token = viewer(DEMO_TENANT);
        UUID member = newMember(DEMO_TENANT, "Trim" + tag(), "Test", null, null);

        assertThat(idsOf(getJson(token, "/api/v1/members?q=" + "  trim  "))).contains(member);
    }

    // ---- sort and paging --------------------------------------------------------------------------------------

    @Test
    void membersAreSortedByLastNameThenFirstNameIgnoringCase() throws Exception {
        String token = viewer(DEMO_TENANT);
        String tag = tag();
        UUID zoe = newMember(DEMO_TENANT, tag + "b", "Zoe", null, null);
        UUID anna = newMember(DEMO_TENANT, tag + "B", "anna", null, null);
        UUID first = newMember(DEMO_TENANT, tag + "A", "Zed", null, null);
        UUID last = newMember(DEMO_TENANT, tag + "c", "Adam", null, null);

        assertThat(idsOf(getJson(token, "/api/v1/members?q=" + tag))).containsExactly(first, anna, zoe, last);
    }

    @Test
    void pagingSplitsTheSortedResult() throws Exception {
        String token = viewer(DEMO_TENANT);
        String tag = tag();
        UUID a = newMember(DEMO_TENANT, tag, "A", null, null);
        UUID b = newMember(DEMO_TENANT, tag, "B", null, null);
        UUID c = newMember(DEMO_TENANT, tag, "C", null, null);
        UUID d = newMember(DEMO_TENANT, tag, "D", null, null);
        UUID e = newMember(DEMO_TENANT, tag, "E", null, null);

        getAs(token, "/api/v1/members?size=2&page=0&q=" + tag)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalItems").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(a.toString()))
                .andExpect(jsonPath("$.items[1].id").value(b.toString()));
        getAs(token, "/api/v1/members?size=2&page=1&q=" + tag)
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.items[0].id").value(c.toString()))
                .andExpect(jsonPath("$.items[1].id").value(d.toString()));
        getAs(token, "/api/v1/members?size=2&page=2&q=" + tag)
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(e.toString()));
        getAs(token, "/api/v1/members?size=2&page=9&q=" + tag)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalItems").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void defaultsAreFirstPageOfTwenty() throws Exception {
        getAs(viewer(DEMO_TENANT), "/api/v1/members")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void invalidPagingAndQueryAreABadRequest() throws Exception {
        String token = viewer(DEMO_TENANT);

        for (String query : List.of("size=101", "size=0", "page=-1", "q=" + "x".repeat(101))) {
            getAs(token, "/api/v1/members?" + query)
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        getAs(token, "/api/v1/members?size=101")
                .andExpect(jsonPath("$.errors[0].field").value("size"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        getAs(token, "/api/v1/members?size=abc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    // ---- archived filter --------------------------------------------------------------------------------------

    @Test
    void archivedMembersAreHiddenUnlessRequested() throws Exception {
        String token = viewer(DEMO_TENANT);
        String tag = tag();
        UUID active = newMember(DEMO_TENANT, tag + "A", "Test", null, null);
        UUID archived = newMember(DEMO_TENANT, tag + "B", "Test", null, null);
        archiveMember(DEMO_TENANT, archived);

        assertThat(idsOf(getJson(token, "/api/v1/members?q=" + tag))).containsExactly(active);
        assertThat(idsOf(getJson(token, "/api/v1/members?includeArchived=false&q=" + tag))).containsExactly(active);
        getAs(token, "/api/v1/members?includeArchived=true&q=" + tag)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].id").value(active.toString()))
                .andExpect(jsonPath("$.items[0].archived").value(false))
                .andExpect(jsonPath("$.items[1].id").value(archived.toString()))
                .andExpect(jsonPath("$.items[1].archived").value(true));
    }

    @Test
    void archivedCountsDoNotLeakIntoTheTotalsByDefault() throws Exception {
        String token = viewer(DEMO_TENANT);
        String tag = tag();
        newMember(DEMO_TENANT, tag, "A", null, null);
        archiveMember(DEMO_TENANT, newMember(DEMO_TENANT, tag, "B", null, null));

        assertThat(getJson(token, "/api/v1/members?q=" + tag).path("totalItems").asLong()).isEqualTo(1);
        assertThat(getJson(token, "/api/v1/members?includeArchived=true&q=" + tag).path("totalItems").asLong())
                .isEqualTo(2);
    }

    // ---- card visibility --------------------------------------------------------------------------------------

    @Test
    void aMemberOpensOnlyTheirOwnCardAndWithoutNotes() throws Exception {
        String tag = tag();
        String email = tag + "@demo.local";
        UUID own = newMember(DEMO_TENANT, "Own" + tag, "Test", null, email);
        giveBaseRole(DEMO_TENANT, own, "MEMBER");
        inTenant(DEMO_TENANT, sql -> sql.update("update member set notes = 'private note' where id = ?", own));
        UUID other = newMember(DEMO_TENANT, "Other" + tag, "Test", null, null);
        String token = TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);

        getAs(token, "/api/v1/members/" + own)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(own.toString()))
                .andExpect(content().string(not(containsString("notes"))))
                .andExpect(content().string(not(containsString("private note"))));
        getAs(token, "/api/v1/members/" + other)
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        getAs(token, "/api/v1/members/" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aViewerOpensAnyCardOfTheirCooperativeIncludingNotes() throws Exception {
        String tag = tag();
        UUID member = newMember(DEMO_TENANT, "Card" + tag, "Test", null, null);
        inTenant(DEMO_TENANT, sql -> sql.update("update member set notes = 'private note' where id = ?", member));

        getAs(viewer(DEMO_TENANT), "/api/v1/members/" + member)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(member.toString()))
                .andExpect(jsonPath("$.lastName").value("Card" + tag))
                .andExpect(jsonPath("$.notes").value("private note"));
    }

    @Test
    void aViewerOpensAnArchivedCard() throws Exception {
        UUID member = newMember(DEMO_TENANT, "Gone" + tag(), "Test", null, null);
        archiveMember(DEMO_TENANT, member);

        getAs(viewer(DEMO_TENANT), "/api/v1/members/" + member)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true))
                .andExpect(jsonPath("$.archivedAt").isNotEmpty());
    }

    @Test
    void aViewerGetsNotFoundForAnUnknownId() throws Exception {
        getAs(viewer(DEMO_TENANT), "/api/v1/members/" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---- tenant isolation -------------------------------------------------------------------------------------

    @Test
    void anotherCooperativeSeesOnlyItsOwnMembers() throws Exception {
        // Demo data: the other cooperative has at least member bbbbbbbb-...-01.
        String token = viewer(OTHER_TENANT);
        List<UUID> demoIds = inTenant(DEMO_TENANT, sql -> sql.queryForList("select id from member", UUID.class));
        List<UUID> otherIds = inTenant(OTHER_TENANT, sql -> sql.queryForList(
                "select id from member where archived_at is null", UUID.class));

        List<UUID> listed = idsOf(getJson(token, "/api/v1/members?size=100"));

        assertThat(listed).containsExactlyInAnyOrderElementsOf(otherIds).contains(OTHER_MEMBER);
        assertThat(listed).doesNotContainAnyElementsOf(demoIds);
        assertThat(getJson(token, "/api/v1/members?size=100").path("totalItems").asLong())
                .isEqualTo(otherIds.size());
    }

    @Test
    void searchNeverCrossesCooperatives() throws Exception {
        String tag = tag();
        UUID demoMember = newMember(DEMO_TENANT, "Cross" + tag, "Test", null, null, "+380509990000");

        assertThat(idsOf(getJson(viewer(OTHER_TENANT), "/api/v1/members?q=" + tag))).isEmpty();
        assertThat(idsOf(getJson(viewer(OTHER_TENANT), "/api/v1/members?q=380509990000"))).isEmpty();
        assertThat(idsOf(getJson(viewer(DEMO_TENANT), "/api/v1/members?q=" + tag))).containsExactly(demoMember);
    }

    @Test
    void aViewerOfAnotherCooperativeGetsNotFoundForADemoCard() throws Exception {
        // Demo data: aaaaaaaa-...-03 is a member of the Demo cooperative.
        getAs(viewer(OTHER_TENANT), "/api/v1/members/" + DEMO_MEMBER)
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void theOperatorOpensNoCard() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        getAs(operator, "/api/v1/members/" + DEMO_MEMBER).andExpect(status().isNotFound());
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** A token for a fresh member of the cooperative with the Treasurer base role (MEMBERS_VIEW). */
    private String viewer(UUID tenant) {
        String email = "viewer-" + tag() + "@" + (tenant.equals(DEMO_TENANT) ? "demo" : "other") + ".local";
        UUID member = newMember(tenant, email);
        giveBaseRole(tenant, member, "TREASURER");
        return TestTokens.bearer(tenant, UUID.randomUUID().toString(), email);
    }

    private String emailOf(UUID tenant, UUID member) {
        return inTenant(tenant, sql -> sql.queryForObject("select email from member where id = ?", String.class, member));
    }

    /** Lower-case token that is unique per call and does not occur in demo data. */
    private static String tag() {
        return "zq" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private ResultActions getAs(String bearer, String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private JsonNode getJson(String bearer, String path) throws Exception {
        String body = getAs(bearer, path).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static List<UUID> idsOf(JsonNode page) {
        return page.path("items").valueStream().map(item -> UUID.fromString(item.path("id").asString())).toList();
    }
}
