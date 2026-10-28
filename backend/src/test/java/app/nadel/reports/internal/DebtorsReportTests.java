package app.nadel.reports.internal;

import static app.nadel.reports.internal.PlotBalancesReportTests.columnKeys;
import static app.nadel.reports.internal.PlotBalancesReportTests.filters;
import static app.nadel.reports.internal.PlotBalancesReportTests.notes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.reports.internal.PlotBalancesReportTests.Rows;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;

/**
 * Debtors (functional spec 2.5 and 2.14, feature 013 §3.3) through the report API on the worked balances of
 * {@link ReportsTestSupport#bookTheBalances()}: with the default thresholds only the plots over the red amount or older
 * than the red days are listed, with every current owner's name and contacts; lowering the amount lists more plots; the
 * age alone is enough; the defaults follow the settings; without {@code MEMBERS_VIEW} the report is not listed and is
 * 403 for the preview and the file, and the definition refuses on its own; the audit entry is marked as personal data
 * and holds no name or contact.
 */
class DebtorsReportTests extends ReportsTestSupport {

    private static final String REPORT = "/api/v1/reports/debtors";
    private static final String FILE = "/api/v1/reports/debtors.xlsx";

    @Autowired
    DebtorsReport definition;

    @BeforeEach
    void book() {
        bookTheBalances();
    }

    @Test
    void withTheDefaultsOnlyRedPlotsAreListedWithEveryCurrentOwnerAndTheirContacts() throws Exception {
        JsonNode preview = body(send(treasurer, get(REPORT)).andExpect(status().isOk()));

        JsonNode sheet = preview.get("sheets").get(0);
        assertThat(sheet.get("key").asString()).isEqualTo("main");
        List<String> keys = columnKeys(sheet);
        assertThat(keys.subList(0, 5)).containsExactly("number", "street", "owners", "phones", "emails");
        assertThat(keys.subList(keys.size() - 3, keys.size())).containsExactly("total", "oldestUnpaid",
                "daysOverdue");
        // plot 1 is older than 90 days, plot 2 is above 1000.00; plot 3 (300.00, 20 days) and plot 11 are not
        assertThat(lines(sheet)).containsExactly(
                "1 Лісова | Шевченко Тарас Григорович; Коваль Марія | +380501112233, +380671112233; +380631112233 "
                        + "| taras@reports.test | 600.00 52.50 652.50 " + daysAgo(120) + " 120",
                "2 Садова | Бондар Олена | +380441112233 | olena@reports.test | 1500.00 0.00 1500.00 "
                        + daysAgo(10) + " 10",
                "TOTAL | - | - | - | 2100.00 52.50 2152.50 - -");
        // the defaults are the cooperative's red amount and red days
        assertThat(filters(preview)).containsExactly("minDebt=1000.00", "olderThanDays=90");
        assertThat(notes(preview)).containsExactly("OLDEST_UNPAID_IS_OLDEST_CHARGE");
        // the owner of 2019 no longer owns plot 1
        assertThat(sheet.toString()).doesNotContain("Колишній").doesNotContain("+380991112233");
    }

    /**
     * Functional spec 2.11, feature 015 §6: the debt and its age are counted after payments. Plot 2 pays its 1 500.00
     * and is no longer listed; plot 1 pays its 600.00 membership fee (120 days old), so what is left, 52.50 of
     * electricity 30 days old, is neither above the amount nor older than the days.
     */
    @Test
    void aPlotWhoseDebtIsPaidIsNotListedAndTheAgeIsThatOfTheOldestChargeLeftUnpaid() throws Exception {
        UUID membershipOf2 = ownerJdbc().queryForObject("select id from charge where plot_id = ?", UUID.class, b2);
        allocate(payment(b2, "CASH", "1500.00", today.minusDays(5), 1L, null, null), membershipOf2, "1500.00");
        UUID membershipOf1 = ownerJdbc().queryForObject("select id from charge where plot_id = ? "
                + "and charge_type_id = ?", UUID.class, b1, membership);
        allocate(payment(b1, "BANK", "600.00", today.minusDays(3), null, "ПД-9", null), membershipOf1, "600.00");

        JsonNode preview = body(send(treasurer, get(REPORT)).andExpect(status().isOk()));
        assertThat(numbers(preview)).isEmpty();

        // with any debt and any age, plot 1 is listed with what is left and the age of the electricity
        JsonNode anyDebt = body(send(treasurer, get(REPORT).param("minDebt", "0.00").param("olderThanDays", "0"))
                .andExpect(status().isOk()));
        assertThat(numbers(anyDebt)).containsExactly("1", "3", "11");
        assertThat(lines(anyDebt.get("sheets").get(0)).getFirst()).endsWith(
                "| 0.00 52.50 52.50 " + daysAgo(30) + " 30");
    }

    @Test
    void aLowerAmountListsMorePlotsAndTheAgeAloneIsEnough() throws Exception {
        assertThat(numbers(body(send(treasurer, get(REPORT).param("minDebt", "200.00"))
                .andExpect(status().isOk())))).containsExactly("1", "2", "3");
        // plot 11 has no owner: listed with empty owner cells, its debt is still owed
        JsonNode lowest = body(send(treasurer, get(REPORT).param("minDebt", "50.00")).andExpect(status().isOk()));
        assertThat(lines(lowest.get("sheets").get(0))).contains(
                "11 Лісова | - | - | - | 100.00 0.00 100.00 " + daysAgo(5) + " 5");
        // a debt exactly at the amount is not above it
        assertThat(numbers(body(send(treasurer, get(REPORT).param("minDebt", "300.00"))
                .andExpect(status().isOk())))).containsExactly("1", "2");
        // OR: no plot is above 5000.00, but plots 1 (120 days) and 3 (20 days) are older than 15 days
        assertThat(numbers(body(send(treasurer, get(REPORT).param("minDebt", "5000.00").param("olderThanDays", "15"))
                .andExpect(status().isOk())))).containsExactly("1", "3");
        // a reversed charge leaves no debt and no age: plot 10 never matches
        assertThat(numbers(body(send(treasurer, get(REPORT).param("minDebt", "0.00").param("olderThanDays", "0"))
                .andExpect(status().isOk())))).containsExactly("1", "2", "3", "11");
    }

    @Test
    void theChargeTypeAndStreetFiltersNarrowTheDebtThatCounts() throws Exception {
        // electricity only: plot 1's 52.50 is 30 days old, under both thresholds
        JsonNode electricityOnly = body(send(treasurer, get(REPORT).param("chargeTypeIds", electricity.toString())
                .param("olderThanDays", "29")).andExpect(status().isOk()));
        assertThat(columnKeys(electricityOnly.get("sheets").get(0))).containsExactly("number", "street", "owners",
                "phones", "emails", "debt." + electricity, "total", "oldestUnpaid", "daysOverdue");
        assertThat(numbers(electricityOnly)).containsExactly("1");
        assertThat(numbers(body(send(treasurer, get(REPORT).param("chargeTypeIds", electricity.toString()))
                .andExpect(status().isOk())))).isEmpty();

        assertThat(numbers(body(send(treasurer, get(REPORT).param("street", "Садова")).andExpect(status().isOk()))))
                .containsExactly("2");
    }

    @Test
    void theDefaultsFollowTheCooperativesThresholds() throws Exception {
        thresholds("250.00", 365);

        send(treasurer, get("/api/v1/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[2].code").value("debtors"))
                .andExpect(jsonPath("$[2].personalData").value(true))
                .andExpect(jsonPath("$[2].filters[0].key").value("minDebt"))
                .andExpect(jsonPath("$[2].filters[0].type").value("MONEY"))
                .andExpect(jsonPath("$[2].filters[0].defaults[0].value").value("250.00"))
                .andExpect(jsonPath("$[2].filters[1].key").value("olderThanDays"))
                .andExpect(jsonPath("$[2].filters[1].type").value("INT"))
                .andExpect(jsonPath("$[2].filters[1].defaults[0].value").value("365"))
                .andExpect(jsonPath("$[2].filters[2].key").value("chargeTypes"))
                .andExpect(jsonPath("$[2].filters[3].key").value("street"));
        // plot 1 (652.50) and 3 (300.00) are above 250.00, plot 2 too; nothing is older than 365 days
        assertThat(numbers(body(send(treasurer, get(REPORT)).andExpect(status().isOk()))))
                .containsExactly("1", "2", "3");
    }

    @Test
    void withoutMembersViewTheReportIsNotListedAndRefused() throws Exception {
        String auditor = personWithPermissions("Ревізор", "REPORTS_VIEW");

        send(auditor, get("/api/v1/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItem("plot-balances")))
                .andExpect(jsonPath("$[*].code", not(hasItem("debtors"))));
        send(auditor, get(REPORT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        send(auditor, get(FILE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        send(auditor, get("/api/v1/reports/plot-balances")).andExpect(status().isOk());

        // with MEMBERS_VIEW as well the same custom role may run it
        String withMembers = personWithPermissions("Ревізор з членами", "REPORTS_VIEW", "MEMBERS_VIEW");
        send(withMembers, get("/api/v1/reports"))
                .andExpect(jsonPath("$[*].code", hasItem("debtors")));
        send(withMembers, get(REPORT)).andExpect(status().isOk());
        send(treasurer, get("/api/v1/reports"))
                .andExpect(jsonPath("$[*].code", hasItem("debtors")));
        send(member, get(REPORT)).andExpect(status().isForbidden());

        // only the two runs that were allowed are in the log
        awaitOutbox();
        assertThat(reportEntries()).extracting(entry -> entry.get("entity_label"))
                .containsExactly("plot-balances", "debtors");
    }

    @Test
    void theDefinitionItselfRefusesACallerWithoutMembersView() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("auditor", null, "PERM_REPORTS_VIEW"));
        try {
            assertThatThrownBy(() -> definition.load(new ReportFilters(),
                    new ReportContext(today, 20_001, List.of()))).isInstanceOf(AccessDeniedException.class);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void theFileListsTheOwnersAndTheAuditEntryIsPersonalDataWithoutNamesOrContacts() throws Exception {
        byte[] bytes = send(treasurer, get(FILE + "?lang=uk"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"debtors_" + today + "_" + today + ".xlsx\""))
                .andReturn().getResponse().getContentAsByteArray();
        Rows rows = PlotBalancesReportTests.rowsOf(bytes);
        assertThat(rows.text(1, 0)).isEqualTo("Боржники");
        assertThat(rows.text(2, 0)).isEqualTo(
                "Фільтри: Борг від: 1000.00; Старше, днів: 90; Види нарахувань: усі; Вулиця: усі");
        assertThat(rows.text(7, 2)).isEqualTo("Власники");
        assertThat(rows.text(7, 3)).isEqualTo("Телефони");
        assertThat(rows.text(7, 4)).isEqualTo("Ел. пошта");
        assertThat(rows.text(7, 5)).isEqualTo("Членський внесок: борг");
        assertThat(rows.text(8, 2)).isEqualTo("Шевченко Тарас Григорович; Коваль Марія");
        assertThat(rows.text(8, 3)).isEqualTo("+380501112233, +380671112233; +380631112233");
        assertThat(rows.text(10, 0)).isEqualTo("Усього");
        Rows english = PlotBalancesReportTests.rowsOf(send(treasurer, get(FILE + "?lang=en")
                .param("minDebt", "200.00")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray());
        assertThat(english.text(2, 0)).isEqualTo(
                "Filters: Debt from: 200.00; Older than, days: 90; Charge types: all; Street: all");
        assertThat(english.text(7, 2)).isEqualTo("Owners");

        List<Map<String, Object>> entries = awaitReportEntries(2);
        for (Map<String, Object> entry : entries) {
            assertThat(entry.get("entity_label")).isEqualTo("debtors");
            assertThat((String) entry.get("changes")).doesNotContain("Шевченко", "Коваль", "Бондар", "+380", "taras@",
                    "olena@");
        }
        assertThat(changes(entries.get(0))).containsExactly("report=debtors", "output=XLSX",
                "filters=[{\"param\":\"minDebt\",\"value\":\"1000.00\"},"
                        + "{\"param\":\"olderThanDays\",\"value\":\"90\"}]",
                "rows=2", "personalData=true");
        assertThat(changes(entries.get(1))).contains("personalData=true", "rows=3");
    }

    // ---- helpers

    private String daysAgo(int days) {
        return today.minusDays(days).toString();
    }

    /**
     * Number and street, then the owner, phone and e-mail cells, then the membership and electricity debts, total,
     * oldest date and days.
     */
    private List<String> lines(JsonNode sheet) {
        List<String> keys = columnKeys(sheet);
        List<Integer> amounts = new ArrayList<>();
        for (String key : List.of("debt." + membership, "debt." + electricity, "total", "oldestUnpaid",
                "daysOverdue")) {
            amounts.add(keys.indexOf(key));
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode row : sheet.get("rows")) {
            List<String> values = values(row.get("cells"));
            lines.add(values.get(0) + " " + values.get(1) + " | " + String.join(" | ", values.subList(2, 5)) + " | "
                    + String.join(" ", amounts.stream().map(values::get).toList()));
        }
        for (JsonNode total : sheet.get("totals")) {
            List<String> values = values(total.get("cells"));
            lines.add("TOTAL | " + String.join(" | ", values.subList(2, 5)) + " | "
                    + String.join(" ", amounts.stream().map(values::get).toList()));
        }
        return lines;
    }

    private static List<String> values(JsonNode cells) {
        List<String> values = new ArrayList<>();
        cells.forEach(cell -> values.add(cell.has("value") ? cell.get("value").asString() : "-"));
        return values;
    }

    private static List<String> numbers(JsonNode preview) {
        List<String> numbers = new ArrayList<>();
        preview.get("sheets").get(0).get("rows").forEach(row -> numbers.add(row.get("cells").get(0).get("value")
                .asString()));
        return numbers;
    }
}
