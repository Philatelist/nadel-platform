package app.nadel.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import app.nadel.reports.internal.ReportsFixtures;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance tests of the whole feature "Reports and report templates" (functional-spec.md of 013) through the public
 * HTTP API, for the criteria the per-report tests of the {@code reports} module do not reach on their own: what each
 * role sees of the Reports section (§2.1, §2.5, §2.14), the preview of 200 rows against the file of all rows of a real
 * report (§2.2), no "paid" column anywhere (§2.2), the debts of a report against the plot account (§2.4), the journey
 * of a template through its versions, the tax slot, the permissions, the archive and the audit (§2.11, §2.12), the
 * saved IBAN in a generated file (§2.13) and the demo "Land tax statement" (§2.16). The per-case rules stay in the
 * tests of the {@code reports} module; {@code acceptance-coverage.md} of the spec maps every criterion to its tests.
 * <p>
 * Every test works in a cooperative of its own (the ledger is append-only, so the demo cooperative is left alone),
 * removed with its ledger afterwards; the demo test restores what it loads. Criteria are named {@code §2.x #n} in the
 * order of the spec.
 * <pre>
 * @layer: integration
 * @spec: context/spec/013-reports
 * @regression
 * </pre>
 */
@Tag("regression")
@Timeout(180)
class ReportsAcceptanceTests extends IntegrationTest {

    private static final String REPORTS = "/api/v1/reports";
    private static final String TEMPLATES = "/api/v1/report-templates";
    private static final String SETTINGS = "/api/v1/settings";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final String IBAN = "UA213223130000026007233566001";
    private static final List<String> ALL_REPORTS = List.of("charges-by-month", "plot-balances", "debtors",
            "electricity-tree", "water", "missing-readings", "land-tax", "corrections");

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private UUID tenant;
    private String chairman;
    private String treasurer;
    private String member;

    @BeforeAll
    static void ownerRole() {
        ReportsFixtures.createOwnerRole();
    }

    @BeforeEach
    void createCooperative() {
        tenant = UUID.randomUUID();
        ownerJdbc().update("insert into tenant (id, name) values (?, ?)", tenant, "СТ «Приймання звітів»");
        ownerJdbc().queryForList("select seed_base_roles(?)", tenant);
        chairman = ReportsFixtures.person(tenant, "CHAIRMAN");
        treasurer = ReportsFixtures.person(tenant, "TREASURER");
        member = ReportsFixtures.person(tenant, "MEMBER");
    }

    @AfterEach
    void removeCooperative() {
        ReportsFixtures.removeCooperative(tenant);
    }

    // ---- §2.1 and §2.14 who sees which reports

    /** §2.1 #1, §2.14 #1: the chairman and the treasurer are offered all eight reports; only Debtors holds personal data. */
    @Test
    void section21_theChairmanAndTheTreasurerAreOfferedEveryBuiltInReport() throws Exception {
        for (String caller : List.of(chairman, treasurer)) {
            JsonNode reports = body(send(caller, get(REPORTS)).andExpect(status().isOk()));
            assertThat(codes(reports)).containsExactlyInAnyOrderElementsOf(ALL_REPORTS);
            for (JsonNode report : reports) {
                assertThat(report.get("personalData").asBoolean()).as(report.get("code").asString())
                        .isEqualTo(report.get("code").asString().equals("debtors"));
            }
        }
        // negative: a member has no report at all, whatever the address
        for (String code : ALL_REPORTS) {
            send(member, get(REPORTS + "/" + code)).andExpect(status().isForbidden());
        }
        send(member, get(REPORTS)).andExpect(status().isForbidden());
    }

    /** §2.14 #3, §2.5 #2: a custom role with "View reports" but not "View members" sees every report except Debtors. */
    @Test
    void section214_aCustomRoleWithReportsViewButWithoutMembersViewSeesEveryReportExceptDebtors() throws Exception {
        String auditor = personWithPermissions("Ревізор", "REPORTS_VIEW");

        List<String> listed = codes(body(send(auditor, get(REPORTS)).andExpect(status().isOk())));
        assertThat(listed).containsExactlyInAnyOrderElementsOf(
                ALL_REPORTS.stream().filter(code -> !code.equals("debtors")).toList());
        // its address shows no access, for the preview and for the file
        send(auditor, get(REPORTS + "/debtors")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        send(auditor, get(REPORTS + "/debtors.xlsx")).andExpect(status().isForbidden());
        // every other report runs
        for (String code : listed) {
            send(auditor, get(REPORTS + "/" + code)).andExpect(status().isOk());
        }

        // negative: "View members" alone is not "View reports"; with both the same role also gets Debtors
        String membersOnly = personWithPermissions("Секретар", "MEMBERS_VIEW");
        send(membersOnly, get(REPORTS)).andExpect(status().isForbidden());
        String both = personWithPermissions("Ревізор з членами", "REPORTS_VIEW", "MEMBERS_VIEW");
        assertThat(codes(body(send(both, get(REPORTS)).andExpect(status().isOk()))))
                .containsExactlyInAnyOrderElementsOf(ALL_REPORTS);
    }

    /** §2.14 #2: "View reports" and "Manage report templates" are on the roles screen with descriptions; roles hold them as stated. */
    @Test
    void section214_theTwoPermissionsHaveNamesAndDescriptionsAndTheBaseRolesHoldThemAsStated() throws Exception {
        JsonNode permissions = body(send(chairman, get("/api/v1/permissions")).andExpect(status().isOk()));
        Map<String, JsonNode> byCode = new HashMap<>();
        permissions.forEach(permission -> byCode.put(permission.get("code").asString(), permission));
        assertThat(byCode).containsKeys("REPORTS_VIEW", "REPORT_TEMPLATES_MANAGE");
        assertThat(byCode.get("REPORTS_VIEW").get("name").asString()).isEqualTo("View reports");
        assertThat(byCode.get("REPORT_TEMPLATES_MANAGE").get("name").asString()).isEqualTo("Manage report templates");
        for (String code : List.of("REPORTS_VIEW", "REPORT_TEMPLATES_MANAGE")) {
            assertThat(byCode.get(code).get("description").asString()).as(code).isNotBlank()
                    .isNotEqualTo(byCode.get(code).get("name").asString());
        }

        JsonNode roles = body(send(chairman, get("/api/v1/roles")).andExpect(status().isOk()));
        Map<String, Set<String>> held = new LinkedHashMap<>();
        roles.forEach(role -> {
            if (role.has("baseCode")) {
                Set<String> codes = new TreeSet<>();
                role.get("permissions").forEach(permission -> codes.add(permission.asString()));
                held.put(role.get("baseCode").asString(), codes);
            }
        });
        // the chairman has both; the treasurer builds reports but manages no template; a member has neither
        assertThat(held.get("CHAIRMAN")).contains("REPORTS_VIEW", "REPORT_TEMPLATES_MANAGE");
        assertThat(held.get("TREASURER")).contains("REPORTS_VIEW").doesNotContain("REPORT_TEMPLATES_MANAGE");
        assertThat(held.get("MEMBER")).doesNotContain("REPORTS_VIEW", "REPORT_TEMPLATES_MANAGE");
    }

    // ---- §2.2 rules common to every report

    /** §2.2 #3: a real report of 250 plots shows 200 rows and says 250; the file holds all 250 and the totals of the screen. */
    @Test
    void section22_thePreviewShowsTwoHundredRowsAndTheFileAllOfThemWithTheSameTotals() throws Exception {
        UUID membership = typeId("MEMBERSHIP");
        ownerJdbc().update("insert into plot (id, tenant_id, number, street, kind, area_m2) select gen_random_uuid(), "
                + "?, i::text, 'Широка', 'PLOT', 600 from generate_series(1, 250) i", tenant);
        ownerJdbc().update("insert into plot_status (plot_id, tenant_id, status) select id, tenant_id, 'GREY' "
                + "from plot where tenant_id = ?", tenant);
        // plot N owes N.00: the debt of all 250 plots is 31 375.00
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, "
                + "source, amount, service_from, service_to, accrued_on, source_key) select gen_random_uuid(), "
                + "p.tenant_id, p.id, ?, ?, 'REGULAR', 'TARIFF', p.number::numeric, date '2025-01-01', "
                + "date '2025-01-31', date '2025-01-01', 'ACC:' || p.id from plot p where p.tenant_id = ?",
                membership, monthRow("2025-01"), tenant);

        JsonNode preview = body(send(treasurer, get(REPORTS + "/plot-balances").param("street", "Широка"))
                .andExpect(status().isOk()));

        JsonNode sheet = preview.get("sheets").get(0);
        int total = columnIndex(sheet, "total");
        assertThat(sheet.get("rowCount").asInt()).isEqualTo(250);
        assertThat(sheet.get("truncated").asBoolean()).isTrue();
        assertThat(sheet.get("rows")).hasSize(200);
        // natural order of the numbers: 1, 2, ... 9, 10, ... 200 (not 1, 10, 100, 101 ...)
        assertThat(sheet.get("rows").get(8).get("cells").get(0).get("value").asString()).isEqualTo("9");
        assertThat(sheet.get("rows").get(9).get("cells").get(0).get("value").asString()).isEqualTo("10");
        assertThat(sheet.get("rows").get(199).get("cells").get(0).get("value").asString()).isEqualTo("200");
        // the totals are those of all 250 plots, not of the 200 shown
        BigDecimal screenTotal = new BigDecimal(sheet.get("totals").get(0).get("cells").get(total).get("value")
                .asString());
        assertThat(screenTotal).isEqualByComparingTo("31375.00");

        byte[] file = send(treasurer, get(REPORTS + "/plot-balances.xlsx").param("street", "Широка")
                .param("lang", "en")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(file))) {
            List<Row> rows = workbook.getFirstSheet().read();
            List<Row> plots = rows.stream().filter(row -> row.getCellText(0).matches("\\d+")).toList();
            assertThat(plots).hasSize(250);
            assertThat(plots.get(9).getCellText(0)).isEqualTo("10");
            BigDecimal listed = plots.stream().map(row -> row.getCellAsNumber(total).orElseThrow())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(listed).isEqualByComparingTo("31375.00");
            // the totals line of the file is the totals line of the screen
            assertThat(rows.getLast().getCellAsNumber(total).orElseThrow()).isEqualByComparingTo(screenTotal);
        }

        // negative: a street of fewer than 200 plots is not cut
        JsonNode small = body(send(treasurer, get(REPORTS + "/plot-balances").param("street", "Немає"))
                .andExpect(status().isOk())).get("sheets").get(0);
        assertThat(small.get("truncated").asBoolean()).isFalse();
        assertThat(small.get("rowCount").asInt()).isZero();
    }

    /** §2.2 #1: 20 001 rows are refused with the stated message code and no file or audit entry; exactly 20 000 are produced. */
    @Test
    void section22_aReportOfMoreThanTwentyThousandRowsIsRefusedAndOneOfTwentyThousandIsProduced() throws Exception {
        ownerJdbc().update("insert into plot (id, tenant_id, number, street, kind, area_m2) select gen_random_uuid(), "
                + "?, i::text, 'Велика', 'PLOT', 600 from generate_series(1, 20001) i", tenant);
        ownerJdbc().update("insert into plot_status (plot_id, tenant_id, status) select id, tenant_id, 'GREY' "
                + "from plot where tenant_id = ?", tenant);

        for (String path : List.of(REPORTS + "/plot-balances", REPORTS + "/plot-balances.xlsx")) {
            send(treasurer, get(path)).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("REPORT_TOO_LARGE"));
        }
        awaitOutbox();
        assertThat(ownerJdbc().queryForObject("select count(*) from audit_entry where tenant_id = ? and action = "
                + "'REPORT_GENERATED'", Integer.class, tenant)).isZero();

        // one plot fewer: exactly 20 000 rows are within the cap
        ownerJdbc().update("delete from plot_status where plot_id = (select id from plot where tenant_id = ? and "
                + "number = '20001')", tenant);
        ownerJdbc().update("delete from plot where tenant_id = ? and number = '20001'", tenant);
        JsonNode sheet = body(send(treasurer, get(REPORTS + "/plot-balances")).andExpect(status().isOk()))
                .get("sheets").get(0);
        assertThat(sheet.get("rowCount").asInt()).isEqualTo(20_000);
        assertThat(sheet.get("rows")).hasSize(200);
        send(treasurer, get(REPORTS + "/plot-balances.xlsx")).andExpect(status().isOk());
    }

    /** §2.2 (rule): until payments exist no report has a "paid" column, not even an empty one, on screen or in the file. */
    @Test
    void section22_noReportHasAPaidColumnOnScreenOrInTheFile() throws Exception {
        // a column header says "Paid" or "Payments"; the note about the oldest UNPAID charge is no column
        Pattern paid = Pattern.compile("(?i)\\s*(paid|paid amount|payments?|сплачено|оплачено|оплата|платежі)\\s*");
        for (String code : ALL_REPORTS) {
            JsonNode preview = body(send(chairman, get(REPORTS + "/" + code)).andExpect(status().isOk()));
            for (JsonNode sheet : preview.get("sheets")) {
                for (JsonNode column : sheet.get("columns")) {
                    String key = column.get("key").asString();
                    assertThat(key.toLowerCase()).as("%s column key", code).doesNotContain("payment")
                            .doesNotStartWith("paid");
                }
            }
            for (String language : List.of("en", "uk")) {
                byte[] file = send(chairman, get(REPORTS + "/" + code + ".xlsx").param("lang", language))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
                List<String> texts = new ArrayList<>();
                try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(file))) {
                    for (var sheet : workbook.getSheets().toList()) {
                        for (Row row : sheet.read()) {
                            row.forEach(cell -> texts.add(cell == null ? "" : cell.getText()));
                        }
                    }
                }
                assertThat(texts).as("%s %s file", code, language).isNotEmpty();
                assertThat(texts.stream().filter(text -> paid.matcher(text).matches()).toList()).as("%s %s file", code, language)
                        .isEmpty();
            }
        }
    }

    // ---- §2.4 plot balances against the plot account

    /** §2.4 #1: the debt of each plot per type and in all equals its account, with a reversal, an adjustment and an opening balance. */
    @Test
    void section24_thePlotBalancesDebtsAreThoseOnThePlotAccountsWithReversalsAndAdjustments() throws Exception {
        UUID membership = typeId("MEMBERSHIP");
        UUID electricity = typeId("ELECTRICITY");
        UUID p1 = plot("1", "Лісова");
        UUID p2 = plot("2", "Садова");
        UUID january = charge(p1, membership, "REGULAR", "TARIFF", "30.00", "2025-01-01", "2025-01-31", "2025-01");
        charge(p1, membership, "OPENING", "OPENING", "1500.00", "2025-01-01", "2025-01-01", "2025-01");
        charge(p2, electricity, "REGULAR", "CONSUMPTION", "52.50", "2024-12-16", "2025-01-15", "2025-01");
        UUID february = charge(p2, membership, "REGULAR", "TARIFF", "30.00", "2025-02-01", "2025-02-28", "2025-02");
        reverse(february);
        charge(p1, membership, "REGULAR", "TARIFF", "25.00", "2025-03-01", "2025-03-31", "2025-03");
        UUID recalculation = UUID.randomUUID();
        ownerJdbc().update("insert into recalculation (id, tenant_id, cause, reason, total, line_count, plot_count, "
                + "digest) values (?, ?, 'MANUAL', 'Виправлено ставку', -5.00, 1, 1, 'acceptance')", recalculation,
                tenant);
        ownerJdbc().update("insert into charge (tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key, adjusts_charge_id, recalculation_id, "
                + "reason) select tenant_id, plot_id, charge_type_id, ?, 'ADJUSTMENT', 'RECALCULATION', -5.00, "
                + "service_from, service_to, accrued_on, ?, id, ?, 'Виправлено ставку' from charge where id = ?",
                monthRow("2025-03"), "ADJUSTMENT:" + recalculation + ":" + january, recalculation, january);

        JsonNode report = body(send(treasurer, get(REPORTS + "/plot-balances")).andExpect(status().isOk()))
                .get("sheets").get(0);

        assertThat(report.get("rowCount").asInt()).isEqualTo(2);
        for (JsonNode row : report.get("rows")) {
            JsonNode cells = row.get("cells");
            UUID plot = row.get("cells").get(0).get("value").asString().equals("1") ? p1 : p2;
            JsonNode account = body(send(treasurer, get("/api/v1/plots/" + plot + "/account"))
                    .andExpect(status().isOk()));
            assertThat(new BigDecimal(cells.get(columnIndex(report, "total")).get("value").asString()))
                    .as("total of plot %s", cells.get(0).get("value").asString())
                    .isEqualByComparingTo(account.get("total").asString());
            for (JsonNode debt : account.get("debts")) {
                String key = "debt." + debt.get("chargeTypeId").asString();
                BigDecimal shown = new BigDecimal(cells.get(columnIndex(report, key)).get("value").asString());
                assertThat(shown).as("%s of plot %s", key, plot).isEqualByComparingTo(debt.get("debt").asString());
            }
        }
        // 30 - 5 + 1500 + 25 = 1550.00 and 52.50 (the reversed 30.00 is gone)
        assertThat(totalOf(report, "1")).isEqualByComparingTo("1550.00");
        assertThat(totalOf(report, "2")).isEqualByComparingTo("52.50");
    }

    // ---- §2.11 and §2.12 templates

    /**
     * §2.11 #1, #7, #8 and #9: the journey of a template. The chairman uploads version 1 and then version 2; generating
     * always uses the latest version; the treasurer (view reports only) generates but neither uploads, archives nor
     * downloads a version; the audit names the template, its version and the period; once archived the template cannot
     * generate, shows as archived and its versions stay downloadable; a member has no access to anything.
     */
    @Test
    void section211_aTemplateIsGeneratedFromItsLatestVersionByAViewerAndArchivedByTheChairman() throws Exception {
        byte[] first = template(sheet -> sheet.value(0, 0, "Версія 1: ${cooperative.name}"));
        byte[] second = template(sheet -> sheet.value(0, 0, "Версія 2: ${cooperative.name}"));

        JsonNode created = body(upload(chairman, "Відомість", null, first).andExpect(status().isCreated())
                .andExpect(jsonPath("$.template.latestVersion.versionNo").value(1)));
        UUID id = UUID.fromString(created.at("/template/id").asString());
        UUID v1 = UUID.fromString(created.at("/template/latestVersion/id").asString());
        assertThat(generatedFirstCell(treasurer, id, "2025-01-01", "2025-03-31")).isEqualTo("Версія 1: СТ «Приймання звітів»");

        JsonNode added = body(send(chairman, multipart(TEMPLATES + "/" + id + "/versions")
                .file(file("v2.xlsx", second))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.template.latestVersion.versionNo").value(2)));
        // generating always uses the latest version, the older one can still be downloaded
        assertThat(generatedFirstCell(treasurer, id, "2025-01-01", "2025-03-31")).isEqualTo("Версія 2: СТ «Приймання звітів»");
        byte[] old = send(chairman, get(TEMPLATES + "/" + id + "/versions/" + v1 + "/file")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(old).isEqualTo(first);

        // negative: the treasurer sees the template and generates, but manages nothing
        send(treasurer, get(TEMPLATES)).andExpect(status().isOk());
        send(treasurer, multipart(TEMPLATES).file(file("a.xlsx", first)).param("name", "Чужа"))
                .andExpect(status().isForbidden());
        send(treasurer, multipart(TEMPLATES + "/" + id + "/versions").file(file("a.xlsx", first)))
                .andExpect(status().isForbidden());
        send(treasurer, post(TEMPLATES + "/" + id + "/archive")).andExpect(status().isForbidden());
        send(treasurer, get(TEMPLATES + "/" + id + "/versions")).andExpect(status().isForbidden());
        send(treasurer, get(TEMPLATES + "/" + id + "/versions/" + v1 + "/file")).andExpect(status().isForbidden());
        // negative: neither permission, no access at all
        send(member, get(TEMPLATES)).andExpect(status().isForbidden());
        send(member, get(TEMPLATES + "/catalog")).andExpect(status().isForbidden());
        send(member, generate(id, "2025-01-01", "2025-03-31")).andExpect(status().isForbidden());

        // the audit: template name, version and period of every generation
        List<List<String>> entries = awaitTemplateEntries(2);
        assertThat(entries.get(0)).contains("version=1").anyMatch(c -> c.startsWith("template=" + id))
                .anyMatch(c -> c.contains("\"2025-01-01\"") && c.contains("\"2025-03-31\""));
        assertThat(entries.get(1)).contains("version=2");
        assertThat(ownerJdbc().queryForList("select entity_label from audit_entry where tenant_id = ? and action = "
                + "'REPORT_GENERATED' and entity_type = 'REPORT_TEMPLATE'", String.class, tenant))
                .containsOnly("Відомість");

        send(chairman, post(TEMPLATES + "/" + id + "/archive")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED")).andExpect(jsonPath("$.versionCount").value(2));
        send(treasurer, generate(id, "2025-01-01", "2025-03-31")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMPLATE_ARCHIVED"));
        JsonNode list = body(send(treasurer, get(TEMPLATES)).andExpect(status().isOk()));
        assertThat(templateStatus(list, id)).isEqualTo("ARCHIVED");
        for (String version : List.of(v1.toString(), added.at("/template/latestVersion/id").asString())) {
            send(chairman, get(TEMPLATES + "/" + id + "/versions/" + version + "/file")).andExpect(status().isOk());
        }
        // the refused generation wrote no audit entry
        assertThat(awaitTemplateEntries(2)).hasSize(2);
    }

    /** §2.12 #1 and #2: the tax slot is "not added" until the chairman uploads into it; then it generates from its latest version. */
    @Test
    void section212_theTaxSlotIsEmptyUntilATemplateIsUploadedIntoItAndThenGenerates() throws Exception {
        for (String caller : List.of(chairman, treasurer)) {
            send(caller, get(TEMPLATES)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].slot").value("TAX"))
                    .andExpect(jsonPath("$[0].status").value("NOT_ADDED"))
                    .andExpect(jsonPath("$[0].versionCount").value(0));
        }

        byte[] form = template(sheet -> sheet.value(0, 0, "Податкова: ${period.year}"));
        JsonNode created = body(upload(chairman, "Податковий звіт", "TAX", form).andExpect(status().isCreated()));
        UUID id = UUID.fromString(created.at("/template/id").asString());

        JsonNode slot = body(send(treasurer, get(TEMPLATES)).andExpect(status().isOk())).get(0);
        assertThat(slot.get("slot").asString()).isEqualTo("TAX");
        assertThat(slot.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(slot.get("latestVersion").get("versionNo").asInt()).isEqualTo(1);
        assertThat(generatedFirstCell(treasurer, id, "2025-01-01", "2025-12-31")).isEqualTo("Податкова: 2025");

        // a second template cannot take the slot while one is active; a new version of the first can
        upload(chairman, "Друга податкова", "TAX", form).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMPLATE_SLOT_TAKEN"));
        send(chairman, multipart(TEMPLATES + "/" + id + "/versions").file(file("v2.xlsx",
                template(sheet -> sheet.value(0, 0, "Податкова 2: ${period.year}"))))).andExpect(status().isCreated());
        assertThat(body(send(chairman, get(TEMPLATES)).andExpect(status().isOk())).get(0).get("latestVersion")
                .get("versionNo").asInt()).isEqualTo(2);
        assertThat(generatedFirstCell(chairman, id, "2025-01-01", "2025-12-31")).isEqualTo("Податкова 2: 2025");
    }

    // ---- §2.13 requisites

    /** §2.13 #5, #2, #4: a template with the IBAN placeholder is filled with the saved IBAN; a refused IBAN and a non-manager change nothing. */
    @Test
    void section213_theSavedIbanIsInTheGeneratedFileAndARefusedChangeLeavesItAlone() throws Exception {
        JsonNode created = body(upload(chairman, "Реквізити", null, template(sheet -> {
            sheet.value(0, 0, "${cooperative.iban}");
            sheet.value(1, 0, "Рахунок: ${cooperative.iban}");
        })).andExpect(status().isCreated()));
        UUID id = UUID.fromString(created.at("/template/id").asString());

        // nothing entered yet: the cell is empty and the app lists the missing value, never a made-up IBAN
        Generated before = generateFile(chairman, id);
        assertThat(before.cells()).doesNotContain(IBAN);
        assertThat(before.cells().stream().filter(c -> c.contains("UA")).toList()).isEmpty();
        assertThat(before.warnings()).isGreaterThan(0);

        send(chairman, put(SETTINGS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"officialName\": \"Садівниче товариство «Приймання»\", \"iban\": \"ua21 3223 1300 0002 6007 "
                        + "2335 6600 1\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.iban").value(IBAN));
        Generated after = generateFile(treasurer, id);
        assertThat(after.cells()).contains(IBAN, "Рахунок: " + IBAN);
        assertThat(after.warnings()).isZero();

        // negative: an invalid IBAN is refused and the saved one stays in the file
        send(chairman, put(SETTINGS).contentType(MediaType.APPLICATION_JSON).content("{\"iban\": \"UA00 1234\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IBAN_INVALID"));
        // negative: the treasurer does not manage the settings
        send(treasurer, put(SETTINGS).contentType(MediaType.APPLICATION_JSON).content("{\"iban\": \"" + IBAN + "\"}"))
                .andExpect(status().isForbidden());
        assertThat(generateFile(chairman, id).cells()).contains(IBAN, "Рахунок: " + IBAN);
    }

    // ---- §2.16 the demo template

    /** §2.16 #1: the demo chairman generates the "Land tax statement" for the current year: every plot with its area, rate and yearly amount. */
    @Test
    void section216_theDemoLandTaxStatementIsFilledWithEveryPlotItsAreaRateAndYearlyAmount() throws Exception {
        UUID template = null;
        try {
            runDemoScript();
            template = ownerJdbc().queryForObject("select id from report_template where tenant_id = ?", UUID.class,
                    DEMO_TENANT);
            String demoChairman = TestTokens.bearer(DEMO_TENANT, "sub-chairman@demo.local", "chairman@demo.local");
            int year = LocalDate.now(KYIV).getYear();

            JsonNode listed = body(send(demoChairman, get(TEMPLATES)).andExpect(status().isOk()));
            assertThat(templateStatus(listed, template)).isEqualTo("ACTIVE");
            JsonNode landTax = body(send(demoChairman, get(REPORTS + "/land-tax").param("year", Integer.toString(year)))
                    .andExpect(status().isOk())).get("sheets").get(0);
            JsonNode balances = body(send(demoChairman, get(REPORTS + "/plot-balances")).andExpect(status().isOk()))
                    .get("sheets").get(0);
            assertThat(landTax.get("rows")).as("the demo cooperative has land tax to show").isNotEmpty();

            var response = send(demoChairman, generate(template, year + "-01-01", year + "-12-31"))
                    .andExpect(status().isOk()).andReturn().getResponse();
            byte[] file = response.getContentAsByteArray();
            // the test database has no requisites entered: those are the only values the file may report unknown
            JsonNode warnings = json.readTree(new String(java.util.Base64.getUrlDecoder().decode(
                    response.getHeader("X-Report-Warnings-Detail")), StandardCharsets.UTF_8));
            warnings.forEach(warning -> assertThat(warning.get("placeholder").asString()).startsWith("cooperative."));

            try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(file))) {
                List<Row> rows = workbook.getFirstSheet().read();
                Map<String, Row> generated = new LinkedHashMap<>();
                for (Row row : rows) {
                    // the plot rows sit under the header row (the fifth row of the sample) and above the totals line
                    if (row.getRowNum() >= 5 && !row.getCellText(0).isBlank() && !row.getCellText(0).startsWith("Разом")) {
                        generated.put(row.getCellText(0), row);
                    }
                }
                Set<String> plotNumbers = new TreeSet<>();
                balances.get("rows").forEach(row -> plotNumbers.add(row.get("cells").get(0).get("value").asString()));
                assertThat(generated.keySet()).as("every plot of the cooperative has a row")
                        .containsExactlyInAnyOrderElementsOf(plotNumbers);
                int number = columnIndex(landTax, "number");
                for (JsonNode row : landTax.get("rows")) {
                    JsonNode cells = row.get("cells");
                    Row filled = generated.get(cells.get(number).get("value").asString());
                    assertThat(filled).as("plot %s", cells.get(number).get("value").asString()).isNotNull();
                    assertThat(filled.getCellAsNumber(1).orElseThrow())
                            .isEqualByComparingTo(cells.get(columnIndex(landTax, "areaM2")).get("value").asString());
                    assertThat(filled.getCellAsNumber(2).orElseThrow())
                            .isEqualByComparingTo(cells.get(columnIndex(landTax, "rate")).get("value").asString());
                    assertThat(filled.getCellAsNumber(3).orElseThrow())
                            .isEqualByComparingTo(cells.get(columnIndex(landTax, "expected")).get("value").asString());
                }
                assertThat(rows.stream().flatMap(row -> {
                    List<String> texts = new ArrayList<>();
                    row.forEach(cell -> texts.add(cell == null ? "" : cell.getText()));
                    return texts.stream();
                })).as("no placeholder is left in the file").noneMatch(text -> text.contains("${"));
            }
        } finally {
            removeDemoTemplates();
        }
    }

    // ---- fixtures

    private void runDemoScript() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/db/demo/R__demo_report_templates.sql")) {
            ownerJdbc().execute(new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("${demoledger}", "on"));
        }
    }

    private void removeDemoTemplates() {
        await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                "select count(*) from event_publication where completion_date is null", Integer.class) == 0);
        ReportsFixtures.asLedgerOwner(sql -> {
            sql.update("delete from audit_entry where entity_id in (select id from report_template where tenant_id = ?)",
                    DEMO_TENANT);
            sql.update("delete from report_template_version where tenant_id = ?", DEMO_TENANT);
            sql.update("delete from report_template where tenant_id = ?", DEMO_TENANT);
        });
    }

    /** No audit entry of the cooperative is still owed. */
    private void awaitOutbox() {
        await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                "select count(*) from event_publication where completion_date is null and serialized_event like ?",
                Integer.class, "%" + tenant + "%") == 0);
    }

    /** A member with a custom role of only these permissions; returns a token. */
    private String personWithPermissions(String roleName, String... permissions) {
        UUID role = UUID.randomUUID();
        ownerJdbc().update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, tenant, roleName);
        for (String permission : permissions) {
            ownerJdbc().update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, ?)", tenant,
                    role, permission);
        }
        UUID id = UUID.randomUUID();
        String email = "custom-" + id + "@reports.test";
        ownerJdbc().update("insert into member (id, tenant_id, last_name, first_name) "
                + "values (?, ?, 'Звітний', ?)", id, tenant, roleName);
        insertContacts(tenant, id, email);
        ownerJdbc().update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)", tenant, id, role);
        return TestTokens.bearer(tenant, "sub-" + email, email);
    }

    private UUID typeId(String code) {
        return ownerJdbc().queryForObject("select id from charge_type where tenant_id = ? and code = ?", UUID.class,
                tenant, code);
    }

    private UUID plot(String number, String street) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, street, kind, area_m2) "
                + "values (?, ?, ?, ?, 'PLOT', 600)", id, tenant, number, street);
        ownerJdbc().update("insert into plot_status (plot_id, tenant_id, status) values (?, ?, 'GREY')", id, tenant);
        return id;
    }

    private UUID monthRow(String month) {
        ownerJdbc().update("insert into billing_month (tenant_id, month) values (?, ?::date) "
                + "on conflict (tenant_id, month) do nothing", tenant, month + "-01");
        return ownerJdbc().queryForObject("select id from billing_month where tenant_id = ? and month = ?::date",
                UUID.class, tenant, month + "-01");
    }

    private UUID charge(UUID plot, UUID type, String kind, String source, String amount, String from, String to,
            String bookedIn) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, "
                + "source, amount, service_from, service_to, accrued_on, source_key) values (?, ?, ?, ?, ?, ?, ?, ?, "
                + "?::date, ?::date, ?::date, ?)", id, tenant, plot, type, monthRow(bookedIn), kind, source,
                new BigDecimal(amount), from, to, from, "TEST:" + id);
        return id;
    }

    private void reverse(UUID original) {
        ownerJdbc().update("insert into charge (tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, reverses_charge_id, reason) select tenant_id, "
                + "plot_id, charge_type_id, billing_month_id, 'REVERSAL', 'MANUAL_REVERSAL', -amount, service_from, "
                + "service_to, accrued_on, id, 'Помилкове нарахування' from charge where id = ?", original);
    }

    /** A workbook of one sheet filled by the consumer. */
    private static byte[] template(Consumer<Worksheet> fill) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new Workbook(out, "Nadel", "1.0")) {
            fill.accept(workbook.newWorksheet("Аркуш"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    // ---- requests

    private ResultActions send(String bearer, AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, XLSX, content);
    }

    private ResultActions upload(String bearer, String name, String slot, byte[] content) throws Exception {
        var request = multipart(TEMPLATES).file(file("form.xlsx", content)).param("name", name);
        if (slot != null) {
            request.param("slot", slot);
        }
        return send(bearer, request);
    }

    private static AbstractMockHttpServletRequestBuilder<?> generate(UUID template, String from, String to) {
        return post(TEMPLATES + "/" + template + "/generate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\": \"" + from + "\", \"to\": \"" + to + "\"}");
    }

    /** The text of the first cell of the file generated for the period. */
    private String generatedFirstCell(String bearer, UUID template, String from, String to) throws Exception {
        byte[] file = send(bearer, generate(template, from, to)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray();
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(file))) {
            return workbook.getFirstSheet().read().getFirst().getCellText(0);
        }
    }

    private record Generated(List<String> cells, int warnings) {
    }

    private Generated generateFile(String bearer, UUID template) throws Exception {
        var response = send(bearer, generate(template, "2025-01-01", "2025-03-31")).andExpect(status().isOk())
                .andReturn().getResponse();
        List<String> cells = new ArrayList<>();
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(
                response.getContentAsByteArray()))) {
            for (Row row : workbook.getFirstSheet().read()) {
                row.forEach(cell -> cells.add(cell == null ? "" : cell.getText()));
            }
        }
        return new Generated(cells, Integer.parseInt(response.getHeader("X-Report-Warnings")));
    }

    /** The changes of the template generation entries as {@code field=new} lists, oldest first, once there are {@code expected}. */
    private List<List<String>> awaitTemplateEntries(int expected) {
        String sql = "select cast(changes as text) from audit_entry where tenant_id = ? and action = "
                + "'REPORT_GENERATED' and entity_type = 'REPORT_TEMPLATE' order by occurred_at, id";
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                ownerJdbc().queryForList(sql, String.class, tenant)).hasSize(expected));
        List<List<String>> entries = new ArrayList<>();
        for (String changes : ownerJdbc().queryForList(sql, String.class, tenant)) {
            List<String> list = new ArrayList<>();
            json.readTree(changes).forEach(change -> list.add(change.get("field").asString() + "="
                    + change.get("new").asString()));
            entries.add(list);
        }
        return entries;
    }

    private static List<String> codes(JsonNode reports) {
        List<String> codes = new ArrayList<>();
        reports.forEach(report -> codes.add(report.get("code").asString()));
        return codes;
    }

    private static int columnIndex(JsonNode sheet, String key) {
        for (int index = 0; index < sheet.get("columns").size(); index++) {
            if (sheet.get("columns").get(index).get("key").asString().equals(key)) {
                return index;
            }
        }
        throw new AssertionError("no column " + key);
    }

    private static BigDecimal totalOf(JsonNode sheet, String plotNumber) {
        int total = columnIndex(sheet, "total");
        for (JsonNode row : sheet.get("rows")) {
            if (row.get("cells").get(0).get("value").asString().equals(plotNumber)) {
                return new BigDecimal(row.get("cells").get(total).get("value").asString());
            }
        }
        throw new AssertionError("no plot " + plotNumber);
    }

    private static String templateStatus(JsonNode list, UUID id) {
        for (JsonNode entry : list) {
            if (entry.has("id") && entry.get("id").asString().equals(id.toString())) {
                return entry.get("status").asString();
            }
        }
        throw new AssertionError("template " + id + " is not listed");
    }
}
