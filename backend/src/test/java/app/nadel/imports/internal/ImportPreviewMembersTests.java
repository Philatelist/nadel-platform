package app.nadel.imports.internal;

import static app.nadel.imports.internal.ImportWorkbooks.MEMBER_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.PLOT_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.member;
import static app.nadel.imports.internal.ImportWorkbooks.members;
import static app.nadel.imports.internal.ImportWorkbooks.plot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** POST /imports with the Members sheet: counts against the cooperative's members, problems, limits, nothing saved. */
class ImportPreviewMembersTests extends ImportsIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String tag = UUID.randomUUID().toString().substring(0, 8);

    private String email(String local) {
        return local + "." + tag + "@import.example.com";
    }

    private String last(String name) {
        return name + tag;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private Map<String, Object> runRow(String id) {
        return ownerJdbc().queryForMap("select * from import_run where id = ?::uuid", id);
    }

    // ---- counts

    @Test
    void thePreviewCountsNewUpdatedAndUnchangedMembers() throws Exception {
        String updatedEmail = email("updated");
        String unchangedEmail = email("unchanged");
        newMember(DEMO_TENANT, last("Updated"), "Person", null, updatedEmail, "+380671110101");
        newMember(DEMO_TENANT, last("Unchanged"), "Person", null, unchangedEmail, "+380671110102");
        newMember(DEMO_TENANT, last("Byphone"), "Person", null, null, "+380671110103");

        byte[] file = members(
                member("M-1", last("Fresh"), "Person", email("fresh")),
                member("M-2", last("Updated"), "Person", null, "0671110199", updatedEmail, null, null, null, null),
                member("M-3", last("Unchanged"), "Person", null, "067 111 01 02", unchangedEmail, null, null, null,
                        null),
                member("M-4", last("Byphone"), "Person", null, "+380671110103", null, null, null, null, null));

        upload(file)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.counts.members.created").value(1))
                .andExpect(jsonPath("$.counts.members.updated").value(1))
                .andExpect(jsonPath("$.counts.members.unchanged").value(2))
                .andExpect(jsonPath("$.counts.plots.created").value(0))
                .andExpect(jsonPath("$.problems", hasSize(0)))
                .andExpect(jsonPath("$.committable").value(true));
    }

    @Test
    void aDemoMemberThatIsAlreadyAsInTheFileIsUnchanged() throws Exception {
        // demo member 6: matched by e-mail, every other cell equal to the stored value
        byte[] file = members(member("M-1", "Savchenko", "Viktor", "Oleksiiovych", "+380672000006",
                "viktor.savchenko@example.com", null, "EMAIL", null, null));

        upload(file)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.counts.members.unchanged").value(1))
                .andExpect(jsonPath("$.counts.members.created").value(0))
                .andExpect(jsonPath("$.counts.members.updated").value(0))
                .andExpect(jsonPath("$.committable").value(false));
    }

    @Test
    void aFileWithPlotsAndMembersCountsBoth() throws Exception {
        byte[] file = ImportWorkbooks.builder()
                .sheet("Plots", PLOT_HEADER, plot("im" + tag, 600))
                .sheet("Members", MEMBER_HEADER, member("M-1", last("Both"), "Person", email("both")))
                .build();

        upload(file)
                .andExpect(jsonPath("$.counts.plots.created").value(1))
                .andExpect(jsonPath("$.counts.members.created").value(1))
                .andExpect(jsonPath("$.problemCount").value(0));
    }

    @Test
    void theUnchangedTemplateHasNoMembers() throws Exception {
        upload(new TemplateWriter().write(TemplateLanguage.UK))
                .andExpect(jsonPath("$.counts.members.created").value(0))
                .andExpect(jsonPath("$.counts.members.updated").value(0))
                .andExpect(jsonPath("$.counts.members.unchanged").value(0))
                .andExpect(jsonPath("$.problemCount").value(0));
    }

    // ---- saves nothing

    @Test
    void thePreviewSavesNoMemberAndNoAuditEntryButStoresTheRows() throws Exception {
        int membersBefore = count("member");
        int auditBefore = count("audit_entry");
        int outboxBefore = ownerJdbc().queryForObject("select count(*) from event_publication", Integer.class);
        String freshEmail = email("stored");

        MvcResult result = upload(members(
                member("M-1", last("Stored"), "Person", "Middle", "0671110111, +380931110112", freshEmail, "1 Garden St",
                        "email", null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.counts.members.created").value(1))
                .andReturn();

        assertThat(count("member")).isEqualTo(membersBefore);
        assertThat(count("audit_entry")).isEqualTo(auditBefore);
        assertThat(ownerJdbc().queryForObject("select count(*) from event_publication", Integer.class))
                .isEqualTo(outboxBefore);
        Map<String, Object> run = runRow(body(result).get("id").asString());
        assertThat(run).containsEntry("status", "PREVIEW");
        JsonNode plan = JSON.readTree(run.get("plan").toString());
        assertThat(plan.get("members")).hasSize(1);
        JsonNode stored = plan.get("members").get(0);
        assertThat(stored.get("row").asInt()).isEqualTo(2);
        assertThat(stored.get("externalId").asString()).isEqualTo("M-1");
        assertThat(stored.get("lastName").asString()).isEqualTo(last("Stored"));
        assertThat(stored.get("phones")).hasSize(2);
        assertThat(stored.get("phones").get(0).asString()).isEqualTo("0671110111");
        assertThat(stored.get("phones").get(1).asString()).isEqualTo("+380931110112");
        assertThat(stored.get("email").asString()).isEqualTo(freshEmail);
        assertThat(stored.get("deliveryChannel").asString()).isEqualTo("email");
        assertThat(stored.has("telegramUsername")).isFalse();
        JsonNode counts = JSON.readTree(run.get("counts").toString());
        assertThat(counts.get("members").get("created").asInt()).isEqualTo(1);
    }

    // ---- problems

    @Test
    void eachMemberProblemNamesSheetRowColumnAndCode() throws Exception {
        newMember(DEMO_TENANT, last("Twin"), "Person", null, null, "+380671110121");
        newMember(DEMO_TENANT, last("Twin"), "Person", null, null, "+380671110121");
        newMember(DEMO_TENANT, last("Holder"), "Email", null, email("holder"), "+380671110122");
        newMember(DEMO_TENANT, last("Wanted"), "Person", null, null, "+380671110123");

        byte[] file = members(
                member("M-1", last("Fine"), "Person", email("fine")),
                member("m-1", last("Dup"), "Person", email("dup")),
                member("M-3", last("Badmail"), "Person", "not-an-email"),
                member("M-4", last("Badphone"), "Person", null, "12-34", null, null, null, null, null),
                member("M-5", last("Channel"), "Person", null, null, email("channel"), null, "TELEGRAM", null, null),
                member("M-6", last("Nokey"), "Person", null, null, null, null, null, null, null),
                member("M-7", null, "Person", email("nolast")),
                member("M-8", last("Twin"), "Person", null, "0671110121", null, null, null, null, null),
                member("M-9", last("Wanted"), "Person", null, "0671110123", email("holder"), null, null, null, null));

        MvcResult result = upload(file)
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode preview = body(result);
        List<String> problems = new ArrayList<>();
        for (JsonNode problem : preview.get("problems")) {
            problems.add(problem.get("sheet").asString() + "/" + problem.get("row").asInt() + "/"
                    + problem.get("column").asString() + "/" + problem.get("code").asString());
        }
        assertThat(problems).containsExactly(
                "MEMBERS/3/external_id/DUPLICATE_IN_FILE",
                "MEMBERS/4/email/INVALID",
                "MEMBERS/5/phones/PHONE_INVALID",
                "MEMBERS/6/telegram_username/CHANNEL_CONTACT_MISSING",
                "MEMBERS/7/email/MEMBER_KEY_MISSING",
                "MEMBERS/8/last_name/REQUIRED",
                "MEMBERS/9/phones/MEMBER_AMBIGUOUS",
                "MEMBERS/10/email/EMAIL_TAKEN");
        assertThat(preview.get("committable").asBoolean()).isFalse();
        assertThat(preview.get("counts").get("members").get("created").asInt()).isEqualTo(1);
        assertThat(runRow(preview.get("id").asString())).containsEntry("status", "HAS_ERRORS")
                .containsEntry("problem_count", 8);
        assertThat(runRow(preview.get("id").asString()).get("plan")).isNull();
    }

    @Test
    void anEmailOfOneMemberWithTheNameAndPhoneOfAnotherIsReportedOnTheEmailColumn() throws Exception {
        newMember(DEMO_TENANT, last("Holder"), "Email", null, email("holder2"), "+380671110131");
        newMember(DEMO_TENANT, last("Wanted"), "Person", null, null, "+380671110132");

        upload(members(member("M-1", last("Wanted"), "Person", null, "0671110132", email("holder2"), null, null, null,
                null)))
                .andExpect(jsonPath("$.problems[0].sheet").value("MEMBERS"))
                .andExpect(jsonPath("$.problems[0].row").value(2))
                .andExpect(jsonPath("$.problems[0].column").value("email"))
                .andExpect(jsonPath("$.problems[0].code").value("EMAIL_TAKEN"));
    }

    @Test
    void plotProblemsAreListedBeforeMemberProblems() throws Exception {
        byte[] file = ImportWorkbooks.builder()
                .sheet("Plots", PLOT_HEADER, plot("p" + tag, null, "PLOT", 600, 6, null, null, null))
                .sheet("Members", MEMBER_HEADER, member("M-1", last("Bad"), "Person", "nope"))
                .build();

        upload(file)
                .andExpect(jsonPath("$.problemCount").value(2))
                .andExpect(jsonPath("$.problems[0].sheet").value("PLOTS"))
                .andExpect(jsonPath("$.problems[1].sheet").value("MEMBERS"))
                .andExpect(jsonPath("$.problems[1].row").value(2))
                .andExpect(jsonPath("$.problems[1].column").value("email"))
                .andExpect(jsonPath("$.problems[1].code").value("INVALID"));
    }

    @Test
    void aMembersSheetWithoutItsRequiredHeadersReportsEachAndReadsNoRows() throws Exception {
        byte[] file = ImportWorkbooks.builder()
                .sheet("Plots", PLOT_HEADER)
                .sheet("Members", List.of("external_id", "email"), List.of("M-1", "a@example.com"))
                .build();

        upload(file)
                .andExpect(jsonPath("$.problemCount").value(2))
                .andExpect(jsonPath("$.problems[0].sheet").value("MEMBERS"))
                .andExpect(jsonPath("$.problems[0].row").value(1))
                .andExpect(jsonPath("$.problems[0].column").value("last_name"))
                .andExpect(jsonPath("$.problems[0].code").value("HEADER_MISSING"))
                .andExpect(jsonPath("$.problems[1].column").value("first_name"))
                .andExpect(jsonPath("$.counts.members.created").value(0));
    }

    @Test
    void anEmptyMembersSheetMissesTheRequiredHeaders() throws Exception {
        byte[] file = ImportWorkbooks.builder()
                .sheet("Plots", PLOT_HEADER)
                .sheetWithRows("Members", List.of())
                .build();

        upload(file)
                .andExpect(jsonPath("$.problemCount").value(3))
                .andExpect(jsonPath("$.problems[0].column").value("external_id"))
                .andExpect(jsonPath("$.problems[0].code").value("HEADER_MISSING"));
    }

    @Test
    void aWorkbookWithoutAMembersSheetIsAccepted() throws Exception {
        upload(ImportWorkbooks.plots(plot("only" + tag, 600)))
                .andExpect(jsonPath("$.counts.members.created").value(0))
                .andExpect(jsonPath("$.problemCount").value(0));
    }

    // ---- reading the sheet

    @Test
    void ukrainianHeadersInAnyOrderAndCaseAreRead() throws Exception {
        String email = email("uk");
        byte[] file = ImportWorkbooks.builder()
                .sheet("Plots", PLOT_HEADER)
                .sheet("members", List.of("ТЕЛЕФОНИ", "Електронна пошта", "Ім'я", "прізвище", "Ключ", "По батькові",
                        "Поштова адреса", "Канал"),
                        List.of("0671110141", email, "Олена", "Шевченко" + tag, "К-1", "Іванівна", "Київ", "email"))
                .build();

        MvcResult result = upload(file)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.problemCount").value(0))
                .andExpect(jsonPath("$.counts.members.created").value(1))
                .andReturn();

        JsonNode stored = JSON.readTree(runRow(body(result).get("id").asString()).get("plan").toString())
                .get("members").get(0);
        assertThat(stored.get("externalId").asString()).isEqualTo("К-1");
        assertThat(stored.get("lastName").asString()).isEqualTo("Шевченко" + tag);
        assertThat(stored.get("firstName").asString()).isEqualTo("Олена");
        assertThat(stored.get("middleName").asString()).isEqualTo("Іванівна");
        assertThat(stored.get("postalAddress").asString()).isEqualTo("Київ");
        assertThat(stored.get("phones").get(0).asString()).isEqualTo("0671110141");
    }

    @Test
    void phonesInANumberCellKeepTheirLeadingZeroAsFarAsPossible() throws Exception {
        MvcResult result = upload(members(
                member("M-1", last("Num"), "One", null, 671110151, null, null, null, null, null),
                member("M-2", last("Num"), "Two", null, "0671110152; 093 111 01 53,", null, null, null, null, null),
                member("M-3", last("Num"), "Three", null, 380671110154L, null, null, null, null, 671110155)))
                .andExpect(jsonPath("$.problemCount").value(0))
                .andExpect(jsonPath("$.counts.members.created").value(3))
                .andReturn();

        JsonNode plan = JSON.readTree(runRow(body(result).get("id").asString()).get("plan").toString())
                .get("members");
        assertThat(plan.get(0).get("phones").get(0).asString()).isEqualTo("0671110151");
        assertThat(plan.get(1).get("phones")).hasSize(2);
        assertThat(plan.get(1).get("phones").get(1).asString()).isEqualTo("093 111 01 53");
        assertThat(plan.get(2).get("phones").get(0).asString()).isEqualTo("380671110154");
        assertThat(plan.get(2).get("viberPhone").asString()).isEqualTo("0671110155");
    }

    @Test
    void commentRowsAndEmptyRowsAreSkippedAndRowNumbersAreKept() throws Exception {
        List<List<?>> rows = new ArrayList<>();
        rows.add(List.of("# a comment before the header"));
        rows.add(MEMBER_HEADER);
        rows.add(member("# instruction", "Last", "First", null, "phones", "email", null, "EMAIL, ...", null, null));
        rows.add(List.of());
        rows.add(member("M-1", last("Skip"), "Person", email("skip")));
        rows.add(member("M-2", last("Bad"), "Person", "nope"));

        upload(ImportWorkbooks.builder().sheet("Plots", PLOT_HEADER).sheetWithRows("Members", rows).build())
                .andExpect(jsonPath("$.counts.members.created").value(1))
                .andExpect(jsonPath("$.problemCount").value(1))
                .andExpect(jsonPath("$.problems[0].row").value(6))
                .andExpect(jsonPath("$.problems[0].column").value("email"));
    }

    // ---- limit

    @Test
    void moreThanThreeThousandMemberRowsAreRefused() throws Exception {
        List<List<?>> rows = new ArrayList<>();
        for (int i = 0; i < 3001; i++) {
            rows.add(member("M-" + i, "Last" + i, "First", "m" + i + "." + tag + "@import.example.com"));
        }

        upload(members(rows.toArray(List<?>[]::new)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_LIMIT_EXCEEDED"));
        assertThat(count("import_run")).isZero();
    }

    @Test
    void exactlyThreeThousandMemberRowsAreAccepted() throws Exception {
        List<List<?>> rows = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            rows.add(member("M-" + i, "Last" + i, "First", "m" + i + "." + tag + "@import.example.com"));
        }

        upload(members(rows.toArray(List<?>[]::new)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.counts.members.created").value(3000))
                .andExpect(jsonPath("$.problemCount").value(0));
    }
}
