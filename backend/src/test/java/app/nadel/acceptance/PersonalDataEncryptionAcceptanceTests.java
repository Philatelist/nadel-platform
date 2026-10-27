package app.nadel.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance tests of the whole feature "Personal data encryption", driven through the public HTTP API with the demo
 * data and read back as the schema owner. They complement the per-slice tests ({@code MemberSearchTests},
 * {@code AuditPlaintextScanTests}, {@code AuditReadTests}, {@code ImportPlanEncryptionTests},
 * {@code ImportPreviewMembersTests}, {@code DebtorsReportTests}, the migration tests) with the criteria that cross
 * slices: one flow whose stored result and logs are scanned for every contact, copied ciphertext, the unchanged card
 * and form, sign-in and e-mail uniqueness. The upgrade of existing data is in {@code PersonalDataUpgradeAcceptanceTests}.
 * <p>
 * Each test names the functional-spec criteria it covers as {@code §2.x #n} (the n-th acceptance criterion).
 * <pre>
 * @layer: integration
 * @spec: 014-personal-data-encryption
 * @regression
 * </pre>
 */
@Tag("regression")
@ExtendWith(OutputCaptureExtension.class)
class PersonalDataEncryptionAcceptanceTests extends IntegrationTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String CHAIRMAN_EMAIL = "chairman@demo.local";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private final String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    private final List<UUID> otherCooperativeMembers = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // members this test created through the API or the import are found by their unique last name
        for (UUID id : ownerJdbc().queryForList("select id from member where last_name like ?", UUID.class,
                "%" + tag)) {
            ownerJdbc().update("delete from member_role where member_id = ?", id);
            ownerJdbc().update("delete from member where id = ?", id);
        }
        for (UUID id : otherCooperativeMembers) {
            ownerJdbc().update("delete from member where id = ?", id);
        }
        ownerJdbc().update("delete from import_run");
        // the chairman's first sign-in linked the demo member
        unlinkByEmail(DEMO_TENANT, CHAIRMAN_EMAIL);
    }

    // ---- §2.1 #1, §2.2 #2-#4, §2.3 #3, §2.7 #1: one flow, then the storage and the logs are scanned -----------

    /**
     * §2.1 #1: after a member is created, changed, found by search, imported and signed in, the stored member list,
     * the audit log, the pending import and the message queue hold none of the six values, but the names.
     * §2.2 #2-#4: the last 4 digits, the whole phone in the three usual writings and the whole e-mail in capitals
     * find the member; the part before {@code @} and the middle of the phone do not.
     * §2.3 #3: an import row with an existing member's e-mail, or with the name and phone, is an update.
     * §2.7 #1: none of the phones, e-mails, addresses, handles or notes of the flow is in the server logs.
     */
    @Test
    void createUpdateSearchImportAndSignInLeaveNoContactInStorageOrLogs(CapturedOutput output) throws Exception {
        Contacts created = contacts("a", "+38067");
        Contacts changed = contacts("b", "+38050");
        String lastName = "Flow" + tag;

        UUID member = create(lastName, created);
        send(put("/api/v1/members/" + member), body(lastName, changed)).andExpect(status().isOk());

        // search: last 4 digits, three writings of the whole phone, the e-mail in capitals
        String digits = changed.phone().substring(1);
        String local = "0" + digits.substring(3);
        for (String q : List.of(changed.phone().substring(changed.phone().length() - 4), changed.phone(),
                formattedPhone(changed.phone()), local, digits, changed.email().toUpperCase())) {
            assertThat(search(q)).as("q=%s", q).contains(member);
        }
        for (String q : List.of(changed.email().substring(0, changed.email().indexOf('@')),
                changed.phone().substring(5, 11), created.phone(), created.email())) {
            assertThat(search(q)).as("q=%s", q).doesNotContain(member);
        }

        // sign-in of another person by e-mail: linked, and the e-mail never reaches the logs
        String signInEmail = "signin." + tag + "@example.com";
        UUID person = newMember(DEMO_TENANT, "Signin" + tag, "Person", null, signInEmail);
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION,
                        TestTokens.bearer(DEMO_TENANT, "sub-" + tag, signInEmail.toUpperCase())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(person.toString()));

        // import: the first row finds the member by its e-mail, the second one by its name with its phone
        String byPhoneName = "ByPhone" + tag;
        UUID byPhone = newMember(DEMO_TENANT, byPhoneName, "Person", null, null, "+380931112233");
        Contacts imported = contacts("c", "+38063");
        List<Object> updateByEmail = row("M-1", lastName, "Test", null, changed.phone() + ", +380931230000",
                changed.email(), null, null, null, null);
        List<Object> updateByNamePhone = row("M-2", byPhoneName, "Person", null, "0931112233", null,
                "1 Acceptance street " + tag, null, null, null);
        List<Object> fresh = row("M-3", "Imported" + tag, "Person", null, imported.phone(), imported.email(),
                imported.address(), "TELEGRAM", imported.telegram(), imported.viber());
        String preview = mvc.perform(multipart("/api/v1/imports")
                        .file(new MockMultipartFile("file", "members.xlsx", XLSX,
                                workbook(updateByEmail, updateByNamePhone, fresh)))
                        .header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.counts.members.created").value(1))
                .andExpect(jsonPath("$.counts.members.updated").value(2))
                .andExpect(jsonPath("$.counts.members.unchanged").value(0))
                .andExpect(jsonPath("$.committable").value(true))
                .andReturn().getResponse().getContentAsString();
        String importId = json.readTree(preview).get("id").asString();

        // the pending import holds the rows only sealed
        String pendingImport = ownerJdbc().queryForObject(
                "select row_to_json(r)::text || encode(r.plan_enc, 'escape') from import_run r where id = ?::uuid",
                String.class, importId);
        assertThat(pendingImport).contains("PREVIEW").doesNotContain("Imported" + tag);
        assertNone(pendingImport, created, changed, imported);

        mvc.perform(post("/api/v1/imports/" + importId + "/commit").header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(status().isOk());

        // the audit entries of the three members and of the changed one are written by the outbox listener
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(ownerJdbc().queryForObject(
                "select count(*) from audit_entry where entity_label like ?", Integer.class, "%" + tag + "%"))
                .isGreaterThanOrEqualTo(5));
        assertThat(byPhone).isNotNull();

        String members = scan("select row_to_json(t)::text from member t where last_name like '%" + tag + "'");
        String audit = scan("select row_to_json(t)::text from audit_entry t where entity_label like '%" + tag + "%'");
        String queue = scan("select row_to_json(t)::text from event_publication t");
        String committedImport = scan("select row_to_json(t)::text from import_run t");
        for (String store : List.of(members, audit, queue, committedImport)) {
            assertNone(store, created, changed, imported);
        }
        // the names stay readable: sorting and search by part of a surname rely on it
        assertThat(members).contains(lastName).contains("Imported" + tag);
        assertThat(audit).contains(lastName);
        assertThat(audit).contains("\"enc\"");
        // the imported member is a real, readable member: search finds it by its phone and its e-mail
        assertThat(search(imported.phone())).isNotEmpty();
        assertThat(search(imported.email())).isNotEmpty();

        assertNone(output.getAll(), created, changed, imported);
        assertThat(output.getAll()).doesNotContain(signInEmail).doesNotContain(signInEmail.toUpperCase())
                .doesNotContain("+380931112233").doesNotContain("0931112233");
    }

    // ---- §2.1 #2: ciphertext copied elsewhere ---------------------------------------------------------------

    /**
     * §2.1 #2: a sealed value copied onto another member, onto another field of the same member and onto a member of
     * another cooperative is not readable there, and the answer carries none of the copied member's data.
     */
    @Test
    void aSealedValueCopiedToAnotherMemberFieldOrCooperativeIsUnreadableAndLeaksNothing() throws Exception {
        Contacts source = contacts("s", "+38067");
        UUID sourceMember = create("Source" + tag, source);
        UUID otherMember = create("Target" + tag, contacts("t", "+38050"));
        UUID sameMember = create("Same" + tag, contacts("u", "+38063"));

        String otherChairman = otherChairman();
        UUID foreign = createIn(otherChairman, "Foreign" + tag, contacts("f", "+38093"));

        // another member
        ownerJdbc().update("update member set email_enc = (select email_enc from member where id = ?), "
                + "phones_enc = (select phones_enc from member where id = ?), "
                + "notes_enc = (select notes_enc from member where id = ?) where id = ?",
                sourceMember, sourceMember, sourceMember, otherMember);
        // another field of the same member
        ownerJdbc().update("update member set notes_enc = email_enc where id = ?", sameMember);
        // another cooperative
        ownerJdbc().update("update member set email_enc = (select email_enc from member where id = ?) where id = ?",
                sourceMember, foreign);

        for (ResultActions answer : List.of(card(chairman(), otherMember), card(chairman(), sameMember),
                card(otherChairman, foreign))) {
            answer.andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("PERSONAL_DATA_UNREADABLE"))
                    .andExpect(result -> assertNone(result.getResponse().getContentAsString(), source));
        }
        // the owner of the value reads it as before
        card(chairman(), sourceMember).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(source.email()));
    }

    // ---- §2.1 #3: the card and the form are unchanged -------------------------------------------------------

    /**
     * §2.1 #3: the card shows every contact exactly as entered, and saving the form with what the card showed
     * leaves the contacts unchanged. (§2.1 #4, the debtors report, is in {@code DebtorsReportTests}.)
     */
    @Test
    void theCardShowsEveryContactAsEnteredAndSavingTheFormUnchangedKeepsThem() throws Exception {
        Contacts entered = contacts("r", "+38067");
        UUID member = create("Round" + tag, entered);

        JsonNode card = readCard(member);
        assertCard(card, entered);

        // the form: send back what the card showed
        Map<String, Object> form = new LinkedHashMap<>();
        for (String field : List.of("lastName", "firstName", "email", "postalAddress", "deliveryChannel",
                "telegramUsername", "viberPhone", "notes")) {
            form.put(field, card.get(field).asString());
        }
        form.put("phones", List.of(entered.phone(), "+380931230001"));
        send(put("/api/v1/members/" + member), form).andExpect(status().isOk());
        JsonNode second = readCard(member);
        send(put("/api/v1/members/" + member), form).andExpect(status().isOk());

        assertThat(readCard(member)).isEqualTo(second);
        assertCard(second, entered);
        assertThat(second.get("phones")).hasSize(2);
        assertThat(search(entered.phone().substring(entered.phone().length() - 4))).contains(member);
    }

    // ---- §2.3 #1, #2: sign-in and e-mail uniqueness ---------------------------------------------------------

    /**
     * §2.3 #1: a first sign-in with the e-mail in other letter case is linked to the active member. §2.3 #2: a second
     * active member with the same e-mail (other case) is refused with {@code EMAIL_TAKEN}, which does not repeat
     * the e-mail; negative: an archived holder's e-mail does not block a new member.
     */
    @Test
    void firstSignInIgnoresLetterCaseAndASecondActiveMemberCannotShareTheEmail() throws Exception {
        String email = "holder." + tag + "@example.com";
        UUID holder = newMember(DEMO_TENANT, "Holder" + tag, "Person", null, email);

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION,
                        TestTokens.bearer(DEMO_TENANT, "sub-holder-" + tag, "Holder." + tag + "@EXAMPLE.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(holder.toString()));
        assertThat(subjectOf(DEMO_TENANT, holder)).isEqualTo("sub-holder-" + tag);

        Contacts second = contacts("d", "+38067");
        send(post("/api/v1/members"), body("Second" + tag, second.withEmail(email.toUpperCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString().toLowerCase())
                        .doesNotContain(email));

        archiveMember(DEMO_TENANT, holder);
        create("Reuse" + tag, second.withEmail(email));
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** Unique contacts of this test, one set per letter; {@code phonePrefix} is {@code +38067} and the like. */
    private Contacts contacts(String set, String phonePrefix) {
        return new Contacts(phonePrefix + digits(7), set + "." + tag + "@example.com",
                set + " Garden street " + tag, set + "_tg_" + tag, "+38099" + digits(7), set + " private note " + tag);
    }

    private static String digits(int count) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < count; i++) {
            digits.append(ThreadLocalRandom.current().nextInt(10));
        }
        return digits.toString();
    }

    /** {@code +380 67 123 45 67} for {@code +380671234567}. */
    private static String formattedPhone(String phone) {
        return phone.substring(0, 4) + " " + phone.substring(4, 6) + " " + phone.substring(6, 9) + " "
                + phone.substring(9, 11) + " " + phone.substring(11);
    }

    private static void assertNone(String text, Contacts... sets) {
        for (Contacts set : sets) {
            assertThat(text).doesNotContain(set.phone()).doesNotContain(set.phone().substring(1))
                    .doesNotContain(set.email()).doesNotContain(set.address()).doesNotContain(set.telegram())
                    .doesNotContain(set.viber()).doesNotContain(set.viber().substring(1)).doesNotContain(set.notes());
        }
    }

    private static void assertCard(JsonNode card, Contacts entered) {
        assertThat(card.get("phones").get(0).asString()).isEqualTo(entered.phone());
        assertThat(card.get("email").asString()).isEqualTo(entered.email());
        assertThat(card.get("postalAddress").asString()).isEqualTo(entered.address());
        assertThat(card.get("telegramUsername").asString()).isEqualTo(entered.telegram());
        assertThat(card.get("viberPhone").asString()).isEqualTo(entered.viber());
        assertThat(card.get("notes").asString()).isEqualTo(entered.notes());
    }

    private Map<String, Object> body(String lastName, Contacts contacts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lastName", lastName);
        body.put("firstName", "Test");
        body.put("phones", List.of(contacts.phone()));
        body.put("email", contacts.email());
        body.put("postalAddress", contacts.address());
        body.put("deliveryChannel", "TELEGRAM");
        body.put("telegramUsername", contacts.telegram());
        body.put("viberPhone", contacts.viber());
        body.put("notes", contacts.notes());
        return body;
    }

    private UUID create(String lastName, Contacts contacts) throws Exception {
        return createIn(chairman(), lastName, contacts);
    }

    private UUID createIn(String bearer, String lastName, Contacts contacts) throws Exception {
        String response = mvc.perform(post("/api/v1/members").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body(lastName, contacts))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(response).get("id").asString());
        if (!bearer.equals(chairman())) {
            otherCooperativeMembers.add(id);
        }
        return id;
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, chairman())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions card(String bearer, UUID member) throws Exception {
        return mvc.perform(get("/api/v1/members/" + member).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private JsonNode readCard(UUID member) throws Exception {
        return json.readTree(card(chairman(), member).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private List<UUID> search(String q) throws Exception {
        String page = mvc.perform(get("/api/v1/members").param("size", "100").param("includeArchived", "true")
                        .param("q", q).header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(page).path("items").valueStream()
                .map(item -> UUID.fromString(item.path("id").asString())).toList();
    }

    private static String chairman() {
        return TestTokens.bearer(DEMO_TENANT, "sub-" + CHAIRMAN_EMAIL, CHAIRMAN_EMAIL);
    }

    /** A signed-in chairman of the other cooperative (a fixture member, removed after the test). */
    private String otherChairman() {
        String email = "chairman-" + tag + "@other.local";
        UUID id = newMember(OTHER_TENANT, email);
        giveBaseRole(OTHER_TENANT, id, "CHAIRMAN");
        return TestTokens.bearer(OTHER_TENANT, UUID.randomUUID().toString(), email);
    }

    /** The rows of a query as one text, read as the schema owner (no policy hides a row). */
    private static String scan(String sql) {
        return String.join(" ", ownerJdbc().queryForList(sql, String.class));
    }

    private static List<Object> row(Object... cells) {
        return java.util.Arrays.asList(cells);
    }

    /** An import workbook with an empty Plots sheet and the given rows of the Members sheet. */
    private static byte[] workbook(List<?>... members) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new Workbook(out, "Nadel tests", "1.0")) {
            Worksheet plots = workbook.newWorksheet("Plots");
            List<String> plotHeader = List.of("number", "street", "kind", "area_m2", "area_sotky", "cadastral_number",
                    "has_building", "notes");
            for (int c = 0; c < plotHeader.size(); c++) {
                plots.value(0, c, plotHeader.get(c));
            }
            Worksheet sheet = workbook.newWorksheet("Members");
            List<String> header = List.of("external_id", "last_name", "first_name", "middle_name", "phones", "email",
                    "postal_address", "delivery_channel", "telegram_username", "viber_phone");
            for (int c = 0; c < header.size(); c++) {
                sheet.value(0, c, header.get(c));
            }
            for (int r = 0; r < members.length; r++) {
                for (int c = 0; c < members[r].size(); c++) {
                    if (members[r].get(c) instanceof String text) {
                        sheet.value(r + 1, c, text);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** The six personal values of one member. */
    private record Contacts(String phone, String email, String address, String telegram, String viber,
            String notes) {

        Contacts withEmail(String other) {
            return new Contacts(phone, other, address, telegram, viber, notes);
        }
    }
}
