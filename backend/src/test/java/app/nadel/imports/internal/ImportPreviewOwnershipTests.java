package app.nadel.imports.internal;

import static app.nadel.imports.internal.ImportWorkbooks.MEMBER_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.OWNERSHIP_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.PLOT_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.member;
import static app.nadel.imports.internal.ImportWorkbooks.owner;
import static app.nadel.imports.internal.ImportWorkbooks.plot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST /imports with the Ownership sheet: counts against the recorded owners (demo plot 7 owned by Bondarenko and Moroz
 * at 1/2 each, demo plot 9 owned by one member), problems with sheet, row, column and code, limits, nothing saved.
 */
class ImportPreviewOwnershipTests extends ImportsIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate SINCE = LocalDate.of(2020, 3, 1);

    private final String tag = UUID.randomUUID().toString().substring(0, 8);

    private String plotNo(String name) {
        return name + tag;
    }

    private String email(String local) {
        return local + "." + tag + "@import.example.com";
    }

    /** A new plot of 600 square metres. */
    private List<Object> newPlot(String name) {
        return plot(plotNo(name), 600);
    }

    private List<Object> newMember(String key, String name) {
        return member(key, name + tag, "Person", email(name.toLowerCase()));
    }

    private static byte[] workbook(List<List<?>> plots, List<List<?>> members, List<List<?>> ownership) {
        return ImportWorkbooks.builder()
                .sheet("Plots", PLOT_HEADER, plots.toArray(List<?>[]::new))
                .sheet("Members", MEMBER_HEADER, members.toArray(List<?>[]::new))
                .sheet("Ownership", OWNERSHIP_HEADER, ownership.toArray(List<?>[]::new))
                .build();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private Map<String, Object> runRow(String id) {
        return ownerJdbc().queryForMap("select * from import_run where id = ?::uuid", id);
    }

    /** {@code SHEET/row/column/code} of every problem, in the order of the preview. */
    private static List<String> problems(JsonNode preview) {
        List<String> problems = new ArrayList<>();
        for (JsonNode problem : preview.get("problems")) {
            problems.add(problem.get("sheet").asString() + "/" + problem.get("row").asInt() + "/"
                    + (problem.has("column") ? problem.get("column").asString() : "-") + "/"
                    + problem.get("code").asString());
        }
        return problems;
    }

    private JsonNode preview(byte[] file) throws Exception {
        return body(upload(file).andExpect(status().isCreated()).andReturn());
    }

    // ---- counts

    @Test
    void newPlotsGetTheirFirstOwnersAndEachOwnershipRowIsCounted() throws Exception {
        byte[] file = workbook(
                List.of(newPlot("a"), newPlot("b")),
                List.of(newMember("M-1", "One"), newMember("M-2", "Two"), newMember("M-3", "Three")),
                List.of(owner(plotNo("a"), "M-1", "1/2", "01.03.2020"),
                        owner(plotNo("a"), "M-2", "1/2", "01.03.2020"),
                        owner(plotNo("b"), "M-3", "1", SINCE)));

        MvcResult result = upload(file)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.counts.ownerships.created").value(3))
                .andExpect(jsonPath("$.counts.ownerships.updated").value(0))
                .andExpect(jsonPath("$.counts.ownerships.unchanged").value(0))
                .andExpect(jsonPath("$.counts.plots.created").value(2))
                .andExpect(jsonPath("$.counts.members.created").value(3))
                .andExpect(jsonPath("$.problems", hasSize(0)))
                .andExpect(jsonPath("$.committable").value(true))
                .andReturn();

        Map<String, Object> run = runRow(body(result).get("id").asString());
        assertThat(run).containsEntry("status", "PREVIEW");
        assertThat((String) run.get("plan_digest")).matches("[0-9a-f]{64}");
        JsonNode plan = JSON.readTree(run.get("plan").toString());
        assertThat(plan.get("version").asInt()).isEqualTo(1);
        assertThat(plan.get("ownership")).hasSize(3);
        JsonNode stored = plan.get("ownership").get(0);
        assertThat(stored.get("row").asInt()).isEqualTo(2);
        assertThat(stored.get("plotNumber").asString()).isEqualTo(plotNo("a"));
        assertThat(stored.get("memberKey").asString()).isEqualTo("M-1");
        assertThat(stored.get("share").asString()).isEqualTo("1/2");
        assertThat(stored.get("validFrom").asString()).isEqualTo("2020-03-01");
        assertThat(plan.get("ownership").get(2).get("validFrom").asString()).isEqualTo("2020-03-01");
        assertThat(JSON.readTree(run.get("counts").toString()).get("ownerships").get("created").asInt()).isEqualTo(3);
    }

    @Test
    void anExistingUnownedDemoPlotAndMembersFoundByEmailGetOwners() throws Exception {
        byte[] file = workbook(
                List.of(),
                List.of(member("D-1", "Bondarenko", "Andrii", "member@demo.local"),
                        member("D-2", "Moroz", "Halyna", "halyna.moroz@example.com")),
                List.of(owner("26", "D-1", "2/3", SINCE), owner("26", "D-2", "1/3", SINCE)));

        upload(file)
                .andExpect(jsonPath("$.counts.members.unchanged").value(2))
                .andExpect(jsonPath("$.counts.ownerships.created").value(2))
                .andExpect(jsonPath("$.problems", hasSize(0)))
                .andExpect(jsonPath("$.committable").value(true));
    }

    @Test
    void theRecordedOwnersOfDemoPlot7AreUnchangedAndAreNotCommittable() throws Exception {
        byte[] file = workbook(
                List.of(),
                List.of(member("D-1", "Bondarenko", "Andrii", "member@demo.local"),
                        member("d-2", "Moroz", "Halyna", "halyna.moroz@example.com")),
                // the dates differ from the recorded ones and the key is written in another letter case: both ignored
                List.of(owner("7", "d-1", "1/2", "05.06.2023"), owner("7", "D-2", "2/4", "05.06.2023")));

        upload(file)
                .andExpect(jsonPath("$.counts.ownerships.unchanged").value(2))
                .andExpect(jsonPath("$.counts.ownerships.created").value(0))
                .andExpect(jsonPath("$.counts.ownerships.updated").value(0))
                .andExpect(jsonPath("$.problemCount").value(0))
                .andExpect(jsonPath("$.committable").value(false));
    }

    // ---- problems

    @Test
    void aDifferentOwnerOfDemoPlot9IsAConflictAskingForAChangeOfOwner() throws Exception {
        byte[] file = workbook(
                List.of(),
                List.of(newMember("M-1", "Buyer")),
                List.of(owner("9", "M-1", "1", SINCE)));

        MvcResult result = upload(file).andExpect(status().isCreated())
                .andExpect(jsonPath("$.problems[0].sheet").value("OWNERSHIP"))
                .andExpect(jsonPath("$.problems[0].row").value(2))
                .andExpect(jsonPath("$.problems[0].column").value("plot_number"))
                .andExpect(jsonPath("$.problems[0].code").value("OWNERSHIP_CONFLICT"))
                .andExpect(jsonPath("$.problems[0].params.plot").value("9"))
                .andExpect(jsonPath("$.counts.ownerships.created").value(0))
                .andExpect(jsonPath("$.committable").value(false))
                .andReturn();

        assertThat(runRow(body(result).get("id").asString())).containsEntry("status", "HAS_ERRORS")
                .containsEntry("plan", null).containsEntry("plan_digest", null);
    }

    @Test
    void eachOwnershipProblemNamesSheetRowColumnAndCode() throws Exception {
        byte[] file = workbook(
                List.of(newPlot("a"), newPlot("b"), newPlot("c"), newPlot("d"), newPlot("e")),
                List.of(newMember("M-1", "One"), newMember("M-2", "Two")),
                List.of(
                        owner(plotNo("a"), "M-1", "1/2", SINCE),                         // 2: shares 1/2 + 1/3
                        owner(plotNo("a"), "M-2", "1/3", SINCE),                         // 3
                        owner("no-such-plot", "M-1", "1", SINCE),                         // 4: unknown plot
                        owner("R-1", "M-1", "1", SINCE),                                  // 5: common land
                        owner(plotNo("b"), "M-ghost", "1", SINCE),                        // 6: unknown member key
                        owner(plotNo("c"), "M-1", "1/2", SINCE),                          // 7: dates differ
                        owner(plotNo("c"), "M-2", "1/2", SINCE.plusDays(1)),              // 8
                        owner(plotNo("d"), "M-1", "1/2", SINCE),                          // 9: same member twice
                        owner(plotNo("d"), "m-1", "1/2", SINCE),                          // 10
                        owner(plotNo("e"), "M-1", "1/2", LocalDate.now().plusYears(1)),   // 11: in the future
                        owner(plotNo("e"), "M-2", "x", SINCE),                            // 12: not a share
                        owner(null, "M-1", "1", SINCE),                                   // 13: no plot
                        owner("9", "M-1", "1", SINCE)));                                  // 14: conflict

        JsonNode preview = preview(file);

        assertThat(problems(preview)).containsExactly(
                "OWNERSHIP/2/share/SHARES_NOT_ONE",
                "OWNERSHIP/4/plot_number/UNKNOWN_PLOT",
                "OWNERSHIP/5/plot_number/PLOT_COMMON_LAND",
                "OWNERSHIP/6/member_external_id/UNKNOWN_MEMBER_KEY",
                "OWNERSHIP/8/valid_from/OWNERSHIP_DATE_MISMATCH",
                "OWNERSHIP/10/member_external_id/DUPLICATE_OWNER",
                "OWNERSHIP/11/valid_from/OWNERSHIP_DATE_INVALID",
                "OWNERSHIP/12/share/SHARE_INVALID",
                "OWNERSHIP/13/plot_number/REQUIRED",
                "OWNERSHIP/14/plot_number/OWNERSHIP_CONFLICT");
        assertThat(preview.get("committable").asBoolean()).isFalse();
        assertThat(preview.get("counts").get("ownerships").get("created").asInt()).isZero();
    }

    @Test
    void aMemberRowWithAProblemIsReportedOnTheMembersSheetOnlyAndItsPlotIsNotCheckedFurther() throws Exception {
        byte[] file = workbook(
                List.of(newPlot("a")),
                List.of(member("M-1", "Bad" + tag, "Person", "not-an-email"), newMember("M-2", "Good")),
                // 1/2 + 1/3 would be SHARES_NOT_ONE, but the first owner's member row is wrong: nothing to add
                List.of(owner(plotNo("a"), "m-1", "1/2", SINCE), owner(plotNo("a"), "M-2", "1/3", SINCE)));

        JsonNode preview = preview(file);

        assertThat(problems(preview)).containsExactly("MEMBERS/2/email/INVALID");
        assertThat(preview.get("counts").get("ownerships").get("created").asInt()).isZero();
    }

    @Test
    void aPlotRowWithAProblemIsReportedOnThePlotsSheetOnly() throws Exception {
        byte[] file = workbook(
                List.of(plot(plotNo("a"), null, "PLOT", null, null, null, null, null)),
                List.of(newMember("M-1", "One")),
                List.of(owner(plotNo("a"), "M-1", "7", SINCE.plusYears(30))));

        assertThat(problems(preview(file))).containsExactly("PLOTS/2/area_m2/AREA_BOTH_OR_NONE");
    }

    @Test
    void anUnreadableDateIsReportedOnceAndAnEmptyKeyIsRequired() throws Exception {
        byte[] file = workbook(
                List.of(newPlot("a")),
                List.of(newMember("M-1", "One")),
                List.of(owner(plotNo("a"), "M-1", "1", "2020-03-01"),
                        owner(plotNo("a"), null, "1", null)));

        assertThat(problems(preview(file))).containsExactly(
                "OWNERSHIP/2/valid_from/DATE_INVALID",
                "OWNERSHIP/3/member_external_id/REQUIRED",
                "OWNERSHIP/3/valid_from/REQUIRED");
    }

    @Test
    void aPlotWithoutADateIsRequiredToHaveOne() throws Exception {
        byte[] file = workbook(List.of(newPlot("a")), List.of(newMember("M-1", "One")),
                List.of(owner(plotNo("a"), "M-1", "1", null)));

        assertThat(problems(preview(file))).containsExactly("OWNERSHIP/2/valid_from/REQUIRED");
    }

    @Test
    void problemsOfAllSheetsAreListedInSheetOrder() throws Exception {
        byte[] file = workbook(
                List.of(plot(plotNo("a"), null, "PLOT", null, null, null, null, null)),
                List.of(member("M-1", "Bad" + tag, "Person", "nope")),
                List.of(owner("no-such-plot", "M-1", "1", SINCE)));

        assertThat(problems(preview(file))).containsExactly(
                "PLOTS/2/area_m2/AREA_BOTH_OR_NONE",
                "MEMBERS/2/email/INVALID",
                "OWNERSHIP/2/plot_number/UNKNOWN_PLOT");
    }

    // ---- reading the sheet

    @Test
    void aSheetWithoutItsRequiredHeadersReportsEachAndReadsNoRows() throws Exception {
        byte[] file = ImportWorkbooks.builder()
                .sheet("Plots", PLOT_HEADER)
                .sheet("Ownership", List.of("plot_number", "share"), List.of("1", "1"))
                .build();

        JsonNode preview = preview(file);

        assertThat(problems(preview)).containsExactly(
                "OWNERSHIP/1/member_external_id/HEADER_MISSING", "OWNERSHIP/1/valid_from/HEADER_MISSING");
        assertThat(preview.get("counts").get("ownerships").get("created").asInt()).isZero();
    }

    @Test
    void anEmptyOwnershipSheetMissesAllFourColumns() throws Exception {
        byte[] file = ImportWorkbooks.builder().sheet("Plots", PLOT_HEADER).sheetWithRows("Ownership", List.of())
                .build();

        assertThat(problems(preview(file))).containsExactly(
                "OWNERSHIP/1/plot_number/HEADER_MISSING", "OWNERSHIP/1/member_external_id/HEADER_MISSING",
                "OWNERSHIP/1/share/HEADER_MISSING", "OWNERSHIP/1/valid_from/HEADER_MISSING");
    }

    @Test
    void aWorkbookWithoutAnOwnershipSheetIsAccepted() throws Exception {
        upload(ImportWorkbooks.plots(plot(plotNo("only"), 600)))
                .andExpect(jsonPath("$.counts.ownerships.created").value(0))
                .andExpect(jsonPath("$.problemCount").value(0));
    }

    @Test
    void ukrainianHeadersInAnyOrderAndCaseAreReadWithBothKindsOfDates() throws Exception {
        byte[] file = ImportWorkbooks.builder()
                .sheet("Plots", PLOT_HEADER, plot(plotNo("a"), 600))
                .sheet("Members", MEMBER_HEADER, newMember("К-1", "Owner"))
                .sheet("ownership", List.of("ВЛАСНИК З", "частка", "Ключ члена", "Номер ділянки"),
                        List.of("05.06.2021", "1/2", "к-1", plotNo("a")))
                .build();
        byte[] withExcelDate = ImportWorkbooks.builder()
                .sheet("Plots", PLOT_HEADER, plot(plotNo("b"), 600))
                .sheet("Members", MEMBER_HEADER, newMember("К-1", "Owner"))
                .sheet("ownership", List.of("Власник з", "Частка", "Ключ члена", "Номер ділянки"),
                        List.of(LocalDate.of(2021, 6, 5), "1", "К-1", plotNo("b")))
                .build();

        MvcResult result = upload(withExcelDate).andExpect(jsonPath("$.problemCount").value(0))
                .andExpect(jsonPath("$.counts.ownerships.created").value(1)).andReturn();
        assertThat(JSON.readTree(runRow(body(result).get("id").asString()).get("plan").toString())
                .get("ownership").get(0).get("validFrom").asString()).isEqualTo("2021-06-05");
        // 1/2 alone is not a whole plot, which proves the Ukrainian columns were all found
        assertThat(problems(preview(file))).containsExactly("OWNERSHIP/2/share/SHARES_NOT_ONE");
    }

    @Test
    void commentRowsAndEmptyRowsAreSkippedAndRowNumbersAreKept() throws Exception {
        List<List<?>> rows = new ArrayList<>();
        rows.add(List.of("# a comment before the header"));
        rows.add(OWNERSHIP_HEADER);
        rows.add(owner("# plot", "# key", "# share", "# date"));
        rows.add(List.of());
        rows.add(owner(plotNo("a"), "M-1", "1", SINCE));
        rows.add(owner("no-such-plot", "M-1", "1", SINCE));

        byte[] file = ImportWorkbooks.builder()
                .sheet("Plots", PLOT_HEADER, plot(plotNo("a"), 600))
                .sheet("Members", MEMBER_HEADER, newMember("M-1", "One"))
                .sheetWithRows("Ownership", rows)
                .build();

        JsonNode preview = preview(file);

        assertThat(problems(preview)).containsExactly("OWNERSHIP/6/plot_number/UNKNOWN_PLOT");
        assertThat(preview.get("counts").get("ownerships").get("created").asInt()).isEqualTo(1);
    }

    @Test
    void theUnchangedTemplateHasNoOwnership() throws Exception {
        upload(new TemplateWriter().write())
                .andExpect(jsonPath("$.counts.ownerships.created").value(0))
                .andExpect(jsonPath("$.counts.ownerships.unchanged").value(0))
                .andExpect(jsonPath("$.problemCount").value(0));
    }

    // ---- limit and side effects

    @Test
    void moreThanSixThousandOwnershipRowsAreRefused() throws Exception {
        List<List<?>> rows = new ArrayList<>();
        for (int i = 0; i < 6001; i++) {
            rows.add(owner("P" + i, "M-" + i, "1", SINCE));
        }

        upload(ImportWorkbooks.builder().sheet("Plots", PLOT_HEADER).sheet("Ownership", OWNERSHIP_HEADER,
                rows.toArray(List<?>[]::new)).build())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_LIMIT_EXCEEDED"));
    }

    @Test
    void thePreviewSavesNoPlotMemberOwnershipOrAuditEntry() throws Exception {
        int plots = count("plot");
        int members = count("member");
        int ownerships = count("ownership");
        int audit = count("audit_entry");
        int outbox = ownerJdbc().queryForObject("select count(*) from event_publication", Integer.class);

        upload(workbook(List.of(newPlot("a")), List.of(newMember("M-1", "One")),
                List.of(owner(plotNo("a"), "M-1", "1", SINCE))))
                .andExpect(jsonPath("$.counts.ownerships.created").value(1));

        assertThat(count("plot")).isEqualTo(plots);
        assertThat(count("member")).isEqualTo(members);
        assertThat(count("ownership")).isEqualTo(ownerships);
        assertThat(count("audit_entry")).isEqualTo(audit);
        assertThat(ownerJdbc().queryForObject("select count(*) from event_publication", Integer.class))
                .isEqualTo(outbox);
    }
}
