package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GET /members?q= over sealed contacts (spec 014, 2.2): names by any part, an e-mail only whole (case ignored), a phone
 * whole in any usual spelling or by exactly its last 4 digits; a part of an e-mail or digits from the middle of a phone
 * find nothing. Members are written through the API, so the stored ciphertext, blind indexes and phone index are the
 * application's own, and a phone change rewrites the phone index.
 */
class MemberSearchTests extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private final List<UUID> created = new java.util.ArrayList<>();

    @AfterEach
    void removeCreated() {
        for (UUID id : created) {
            inTenant(DEMO_TENANT, sql -> sql.update("delete from member where id = ?", id));
        }
        created.clear();
        // the chairman's first sign-in linked the demo member
        inTenant(DEMO_TENANT, sql -> sql.update("update member set keycloak_subject = null "
                + "where id = 'aaaaaaaa-0000-0000-0000-000000000001'"));
    }

    @Test
    void aFullPhoneIsFoundInEveryUsualSpellingAndByItsLastFourDigits() throws Exception {
        String phone = uniquePhone();
        String local = phone.substring(3); // 0XXXXXXXXX
        UUID member = create("Phonezz" + tag(), "Test", null, List.of("+380 93 000 00 01", formatted(phone)));

        for (String q : List.of(phone, "+38 " + local.substring(0, 3) + " " + local.substring(3), local,
                phone.substring(1), "(" + local.substring(0, 3) + ") " + local.substring(3, 6) + "-"
                        + local.substring(6, 8) + "-" + local.substring(8))) {
            assertThat(search(q)).as("q=%s", q).contains(member);
        }
        assertThat(search(phone.substring(phone.length() - 4))).as("last 4 digits").contains(member);
        assertThat(search(phone.substring(4, 10))).as("digits from the middle").doesNotContain(member);
        assertThat(search(phone.substring(phone.length() - 5))).as("last 5 digits").doesNotContain(member);
        assertThat(search(phone.substring(phone.length() - 3))).as("last 3 digits").doesNotContain(member);
    }

    @Test
    void anEmailIsFoundOnlyWholeIgnoringCase() throws Exception {
        String email = "search." + tag() + "@example.com";
        UUID member = create("Mailzz" + tag(), "Test", email, List.of());

        assertThat(search(email)).contains(member);
        assertThat(search("  " + email.toUpperCase() + " ")).as("upper case, padded").contains(member);
        assertThat(search(email.substring(0, email.indexOf('@')))).as("the part before @").doesNotContain(member);
        assertThat(search(email.substring(0, email.length() - 1))).as("all but the last letter").doesNotContain(member);
        assertThat(search("@example.com")).as("the domain").doesNotContain(member);
    }

    @Test
    void namesAreStillFoundByAnyPartIgnoringCase() throws Exception {
        String tag = tag();
        UUID member = create("Шевченкоzz" + tag, "Оксана", null, List.of());

        assertThat(search("ЧЕНКОZZ" + tag.toUpperCase())).contains(member);
        assertThat(search("оксан")).contains(member);
    }

    @Test
    void aChangedPhoneIsFoundByTheNewNumberOnly() throws Exception {
        String before = uniquePhone();
        String after = uniquePhone();
        String lastName = "Changezz" + tag();
        UUID member = create(lastName, "Test", null, List.of(before));

        Map<String, Object> body = body(lastName, "Test", null, List.of(after));
        send(put("/api/v1/members/" + member), body).andExpect(status().isOk());

        assertThat(search(after)).contains(member);
        assertThat(search(after.substring(after.length() - 4))).contains(member);
        assertThat(search(before)).doesNotContain(member);
        assertThat(search(before.substring(before.length() - 4))).doesNotContain(member);
        assertThat(ownerJdbc().queryForObject("select count(*) from member_phone_index where member_id = ?",
                Integer.class, member)).isEqualTo(1);
    }

    @Test
    void anotherCooperativeNeverFindsTheMemberByItsContacts() throws Exception {
        String phone = uniquePhone();
        String email = "cross." + tag() + "@example.com";
        UUID member = create("Crosszz" + tag(), "Test", email, List.of(phone));
        String other = viewer(OTHER_TENANT);

        for (String q : List.of(phone, phone.substring(phone.length() - 4), email)) {
            assertThat(idsOf(getJson(other, q))).as(q).doesNotContain(member);
        }
    }

    @Test
    void contactsAreStoredOnlySealed() throws Exception {
        String phone = uniquePhone();
        String email = "sealed." + tag() + "@example.com";
        UUID member = create("Sealedzz" + tag(), "Test", email, List.of(phone));

        String row = ownerJdbc().queryForObject("select row_to_json(m)::text from member m where id = ?",
                String.class, member);
        assertThat(row).doesNotContain(email).doesNotContain(phone).doesNotContain(phone.substring(1));
        Contacts contacts = decryptedContacts(DEMO_TENANT, member);
        assertThat(contacts.email()).isEqualTo(email);
        assertThat(contacts.phones()).containsExactly(phone);
    }

    @Test
    void aSealedValueCopiedToAnotherMemberIsUnreadableNeverEmpty() throws Exception {
        String email = "copied." + tag() + "@example.com";
        UUID source = create("Copyzz" + tag(), "Source", email, List.of());
        UUID target = create("Copyzz" + tag(), "Target", null, List.of());
        ownerJdbc().update("update member set email_enc = (select email_enc from member where id = ?) where id = ?",
                source, target);

        mvc.perform(get("/api/v1/members/" + target).header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("PERSONAL_DATA_UNREADABLE"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain(email));
        // a save would write back what the card showed, so the edit is refused as well
        send(put("/api/v1/members/" + target), body("Copyzz", "Target", null, List.of()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("PERSONAL_DATA_UNREADABLE"));
    }

    // ---- helpers

    /** A phone no other test uses: +38093 and 7 random digits. */
    private static String uniquePhone() {
        long digits = Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 10_000_000L);
        return "+38093" + String.format("%07d", digits);
    }

    private static String formatted(String phone) {
        return phone.substring(0, 3) + " (" + phone.substring(3, 6) + ") " + phone.substring(6, 9) + "-"
                + phone.substring(9, 11) + "-" + phone.substring(11);
    }

    private static String tag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private UUID create(String lastName, String firstName, String email, List<String> phones) throws Exception {
        String response = send(post("/api/v1/members"), body(lastName, firstName, email, phones))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(response).get("id").asString());
        created.add(id);
        return id;
    }

    private static Map<String, Object> body(String lastName, String firstName, String email, List<String> phones) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lastName", lastName);
        body.put("firstName", firstName);
        if (email != null) {
            body.put("email", email);
        }
        body.put("phones", phones);
        return body;
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, chairman())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private List<UUID> search(String q) throws Exception {
        return idsOf(getJson(chairman(), q));
    }

    private static String chairman() {
        return TestTokens.bearer(DEMO_TENANT, "sub-chairman@demo.local", "chairman@demo.local");
    }

    /** A token for a fresh member of the cooperative with the Treasurer base role (MEMBERS_VIEW). */
    private String viewer(UUID tenant) {
        String email = "viewer-" + tag() + "@other.local";
        UUID member = newMember(tenant, email);
        giveBaseRole(tenant, member, "TREASURER");
        return TestTokens.bearer(tenant, UUID.randomUUID().toString(), email);
    }

    /** One page of 100 members, archived included, for the query (passed as a parameter, never re-encoded). */
    private JsonNode getJson(String bearer, String q) throws Exception {
        String body = mvc.perform(get("/api/v1/members").param("size", "100").param("includeArchived", "true")
                        .param("q", q).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static List<UUID> idsOf(JsonNode page) {
        return page.path("items").valueStream().map(item -> UUID.fromString(item.path("id").asString())).toList();
    }
}
