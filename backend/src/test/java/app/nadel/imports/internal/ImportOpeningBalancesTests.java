package app.nadel.imports.internal;

import static app.nadel.imports.internal.ImportWorkbooks.PLOT_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.plot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The "Opening balances" sheet ({@code Вхідне сальдо}, feature 011 §2.9) through the API: the preview counts the
 * balances in both languages, the commit books them as opening balances with the rules of the plot account (the key
 * {@code OPENING:{importId}:{row}}, the month of the date, the reason, the audit entry, the plot status), every row
 * problem is reported with sheet, row, column and code, a re-import of the same file books nothing twice, a reversed
 * balance can be imported again, and a balance entered between the preview and the commit makes the preview stale.
 * Balances are booked on plots of the test ({@code CMT-}) only; their charges, the billing months the test opened and
 * the charge types it created are removed after each test.
 */
class ImportOpeningBalancesTests extends ImportCommitSupport {

    private static final List<String> HEADER = List.of("plot_number", "charge_type", "amount", "date", "reason");
    private static final List<String> HEADER_UK = List.of("Номер ділянки", "Вид нарахування", "Сума", "Дата",
            "Підстава");
    private static final DateTimeFormatter DOTTED = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Autowired
    private Clock clock;

    private final Set<UUID> monthsBefore = new HashSet<>();
    private final List<UUID> chargeTypes = new ArrayList<>();
    /** The seeded WATER type as the demo data has it, when a test renamed it. */
    private Map<String, Object> waterTypeBefore;

    @BeforeAll
    static void createOwnerRole() {
        ownerJdbc().execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nadel_owner') THEN
                        CREATE ROLE nadel_owner NOLOGIN BYPASSRLS;
                    END IF;
                END
                $$""");
        ownerJdbc().execute("GRANT USAGE ON SCHEMA public TO nadel_owner");
    }

    @BeforeEach
    void rememberMonths() {
        monthsBefore.addAll(ownerJdbc().queryForList("select id from billing_month", UUID.class));
    }

    /** Runs before the cleanup of {@link ImportCommitSupport}, which deletes the plots: their charges go first. */
    @AfterEach
    void removeChargesMonthsAndTypes() {
        try {
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                    .until(ImportCommitSupport::noPublicationIsOutstanding);
        } catch (ConditionTimeoutException e) {
            // clean up anyway
        }
        asLedgerOwner(sql -> {
            String plots = "select id from plot where tenant_id = '" + DEMO_TENANT + "' and number like 'CMT-%'";
            sql.update("delete from charge where plot_id in (" + plots + ") and reverses_charge_id is not null");
            sql.update("delete from charge where plot_id in (" + plots + ")");
            for (UUID month : ownerJdbc().queryForList("select id from billing_month", UUID.class)) {
                if (!monthsBefore.contains(month)) {
                    sql.update("delete from billing_month where id = ?", month);
                }
            }
        });
        monthsBefore.clear();
        chargeTypes.forEach(id -> ownerJdbc().update("delete from charge_type where id = ?", id));
        chargeTypes.clear();
        if (waterTypeBefore != null) {
            ownerJdbc().update("update charge_type set name = ?, name_customized = ? where id = ?",
                    waterTypeBefore.get("name"), waterTypeBefore.get("name_customized"), waterTypeBefore.get("id"));
            waterTypeBefore = null;
        }
    }

    /** Runs the work as nadel_owner with the ledger maintenance setting, which alone may delete ledger rows. */
    private static void asLedgerOwner(java.util.function.Consumer<JdbcTemplate> work) {
        JdbcTemplate sql = ownerJdbc();
        new TransactionTemplate(new DataSourceTransactionManager(sql.getDataSource())).executeWithoutResult(status -> {
            sql.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO nadel_owner");
            sql.execute("SET LOCAL ROLE nadel_owner");
            sql.execute("SET LOCAL app.ledger_maintenance = 'on'");
            work.accept(sql);
        });
    }

    // ---- helpers

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The first day of the current month: never in the future, and its month is open. */
    private LocalDate firstOfMonth() {
        return today().withDayOfMonth(1);
    }

    private static List<Object> balance(Object plot, Object chargeType, Object amount, Object date, Object reason) {
        List<Object> row = new ArrayList<>();
        row.add(plot);
        row.add(chargeType);
        row.add(amount);
        row.add(date);
        row.add(reason);
        return row;
    }

    private static byte[] file(String plotsSheet, List<String> plotHeader, List<List<?>> plots, String sheet,
            List<String> header, List<List<?>> balances) {
        return ImportWorkbooks.builder()
                .sheet(plotsSheet, plotHeader, plots.toArray(List<?>[]::new))
                .sheet(sheet, header, balances.toArray(List<?>[]::new))
                .build();
    }

    private static byte[] english(List<List<?>> plots, List<List<?>> balances) {
        return file("Plots", PLOT_HEADER, plots, "Opening balances", HEADER, balances);
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

    private static JsonNode openingCounts(JsonNode preview) {
        return preview.get("counts").get("openingBalances");
    }

    private UUID chargeType(String code) {
        return ownerJdbc().queryForObject("select id from charge_type where tenant_id = ? and code = ?", UUID.class,
                DEMO_TENANT, code);
    }

    /** A custom charge type of the demo cooperative, archived or not; removed after the test. */
    private UUID customType(String name, boolean archived) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into charge_type (id, tenant_id, name, name_customized, position, archived_at) "
                + "values (?, ?, ?, true, 90, case when ? then now() else null end)", id, DEMO_TENANT, name, archived);
        chargeTypes.add(id);
        return id;
    }

    /** The opening balances of a plot, with their source key, amount, date and reason. */
    private List<Map<String, Object>> openings(UUID plot) {
        return ownerJdbc().queryForList("select c.source_key, c.amount, c.accrued_on, c.service_from, c.reason, "
                + "c.charge_type_id, to_char(m.month, 'YYYY-MM') as month from charge c "
                + "join billing_month m on m.id = c.billing_month_id where c.plot_id = ? and c.kind = 'OPENING' "
                + "order by c.created_at, c.source_key", plot);
    }

    private UUID plotIdOf(String number) {
        return ownerJdbc().queryForObject("select id from plot where tenant_id = ? and number = ?", UUID.class,
                DEMO_TENANT, number);
    }

    /** An opening balance written as the database owner, as if entered on the plot account. */
    private UUID enteredOpening(UUID plot, UUID chargeType, String amount, LocalDate date) {
        UUID month = ownerJdbc().queryForObject("insert into billing_month (tenant_id, month) values (?, ?) "
                + "on conflict (tenant_id, month) do update set month = excluded.month returning id", UUID.class,
                DEMO_TENANT, date.withDayOfMonth(1));
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, calculation) values (?, ?, ?, ?, ?, 'OPENING', "
                + "'OPENING', ?::numeric, ?, ?, ?, '{}'::jsonb)", id, DEMO_TENANT, plot, chargeType, month, amount,
                date, date, date);
        return id;
    }

    // ---- preview and commit in both languages

    @Test
    void aUkrainianSheetWithThreeRowsIsPreviewedAndCommittedAsThreeOpeningBalances() throws Exception {
        UUID b = storedPlot("b");
        UUID c = storedPlot("c");
        LocalDate date = firstOfMonth();
        byte[] workbook = file("Ділянки", PLOT_HEADER, List.of(newPlot("a")), "Вхідне сальдо", HEADER_UK, List.of(
                balance(plotNo("a"), "Членський внесок", "1500,00", date.format(DOTTED), "Борг за 2025 рік"),
                balance(plotNo("b").toLowerCase(), "  земельний   податок ", new BigDecimal("250.5"), date, null),
                balance(plotNo("c"), "Ремонт дороги", 1200, date, "  ")));

        JsonNode preview = preview(workbook);

        assertThat(problems(preview)).isEmpty();
        assertThat(openingCounts(preview).get("created").asInt()).isEqualTo(3);
        assertThat(openingCounts(preview).get("updated").asInt()).isZero();
        assertThat(openingCounts(preview).get("unchanged").asInt()).isZero();
        assertThat(preview.get("committable").asBoolean()).isTrue();
        String importId = preview.get("id").asString();
        // a preview books nothing
        assertThat(openings(b)).isEmpty();

        commit(importId).andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.openingBalances.created").value(3));

        UUID a = plotIdOf(plotNo("a"));
        String month = YearMonth.from(date).toString();
        List<Map<String, Object>> ofA = openings(a);
        assertThat(ofA).hasSize(1);
        assertThat(ofA.getFirst().get("source_key")).isEqualTo("OPENING:" + importId + ":2");
        assertThat(ofA.getFirst().get("amount")).isEqualTo(new BigDecimal("1500.00"));
        assertThat(ofA.getFirst().get("accrued_on").toString()).isEqualTo(date.toString());
        assertThat(ofA.getFirst().get("service_from").toString()).isEqualTo(date.toString());
        assertThat(ofA.getFirst().get("reason")).isEqualTo("Борг за 2025 рік");
        assertThat(ofA.getFirst().get("charge_type_id")).isEqualTo(chargeType("MEMBERSHIP"));
        assertThat(ofA.getFirst().get("month")).isEqualTo(month);
        List<Map<String, Object>> ofB = openings(b);
        assertThat(ofB).hasSize(1);
        assertThat(ofB.getFirst().get("source_key")).isEqualTo("OPENING:" + importId + ":3");
        assertThat(ofB.getFirst().get("amount")).isEqualTo(new BigDecimal("250.50"));
        assertThat(ofB.getFirst().get("charge_type_id")).isEqualTo(chargeType("LAND_TAX"));
        // without a reason the import itself is the reference (the source key)
        assertThat(ofB.getFirst().get("reason")).isNull();
        List<Map<String, Object>> ofC = openings(c);
        assertThat(ofC).hasSize(1);
        assertThat(ofC.getFirst().get("amount")).isEqualTo(new BigDecimal("1200.00"));
        assertThat(ofC.getFirst().get("reason")).isNull();

        // the plot account shows the balance like one entered by hand, and the debt follows
        mvc.perform(get("/api/v1/plots/" + b + "/account").header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.charges[0].kind").value("OPENING"))
                .andExpect(jsonPath("$.charges[0].amount").value("250.50"))
                .andExpect(jsonPath("$.charges[0].billingMonth").value(month));
        awaitAudit("OPENING_BALANCE_ENTERED", plotNo(""), 3);
        // the debt behind the plot status follows the import's booking (ChargesBooked)
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(ownerJdbc().queryForObject(
                "select debt from plot_status where plot_id = ?", BigDecimal.class, a))
                .isEqualByComparingTo("1500.00"));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(ownerJdbc().queryForList(
                "select c->>'new' from audit_entry e, jsonb_array_elements(e.changes) c where e.tenant_id = ? "
                        + "and e.action = 'IMPORT_COMMITTED' and e.entity_id = ?::uuid "
                        + "and c->>'field' = 'openingBalancesCreated'", String.class, DEMO_TENANT, importId))
                .containsExactly("3"));
    }

    @Test
    void anEnglishSheetWorksTheSameWayAndTheSameFileUploadedAgainBooksNothingTwice() throws Exception {
        UUID p = storedPlot("p");
        LocalDate date = firstOfMonth().minusMonths(1);
        byte[] workbook = english(List.of(), List.of(
                balance(plotNo("p"), "Membership fee", 1500, date, "Debt for 2025")));

        JsonNode first = preview(workbook);
        assertThat(problems(first)).isEmpty();
        assertThat(openingCounts(first).get("created").asInt()).isEqualTo(1);
        commit(first.get("id").asString()).andExpect(status().isOk());
        assertThat(openings(p)).hasSize(1);

        JsonNode again = preview(workbook);
        assertThat(problems(again)).isEmpty();
        assertThat(openingCounts(again).get("created").asInt()).isZero();
        assertThat(openingCounts(again).get("unchanged").asInt()).isEqualTo(1);
        assertThat(again.get("committable").asBoolean()).isFalse();
        commit(again.get("id").asString()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_NOTHING_TO_COMMIT"));
        assertThat(openings(p)).hasSize(1);

        // a mistake is reversed on the plot account; then the corrected file books the balance again
        UUID charge = ownerJdbc().queryForObject("select id from charge where plot_id = ? and kind = 'OPENING'",
                UUID.class, p);
        mvc.perform(post("/api/v1/charges/" + charge + "/reverse").header(HttpHeaders.AUTHORIZATION, chairman())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Wrong amount\"}"))
                .andExpect(status().isCreated());
        JsonNode afterReversal = preview(workbook);
        assertThat(openingCounts(afterReversal).get("created").asInt()).isEqualTo(1);
        commit(afterReversal.get("id").asString()).andExpect(status().isOk());
        assertThat(openings(p)).hasSize(2);
    }

    @Test
    void aRowDatedInAClosedMonthIsBookedInTheEarliestOpenMonthKeepingItsOwnDate() throws Exception {
        UUID p = storedPlot("p");
        UUID q = storedPlot("q");
        // 10.03.2024 and 20.03.2024 are in a closed month (and before the last closed one of the cooperative)
        ownerJdbc().update("insert into billing_month (tenant_id, month, status, closed_at) "
                + "values (?, date '2024-03-01', 'CLOSED', now()) on conflict (tenant_id, month) do nothing",
                DEMO_TENANT);
        LocalDate first = LocalDate.of(2024, 3, 10);
        LocalDate second = LocalDate.of(2024, 3, 20);
        byte[] workbook = english(List.of(), List.of(
                balance(plotNo("p"), "Membership fee", 100, first, null),
                balance(plotNo("p"), "Land tax", 200, second, null),
                balance(plotNo("q"), "Membership fee", 300, first, null)));

        JsonNode preview = preview(workbook);
        assertThat(problems(preview)).isEmpty();
        commit(preview.get("id").asString()).andExpect(status().isOk());

        String earliestOpen = ownerJdbc().queryForObject("select to_char(min(month), 'YYYY-MM') from billing_month "
                + "where tenant_id = ? and status = 'OPEN' and month > (select max(month) from billing_month "
                + "where tenant_id = ? and status = 'CLOSED')", String.class, DEMO_TENANT, DEMO_TENANT);
        assertThat(earliestOpen).isNotNull().isNotEqualTo("2024-03");
        for (Map<String, Object> opening : openings(p)) {
            assertThat(opening.get("month")).isEqualTo(earliestOpen);
        }
        assertThat(openings(p)).extracting(o -> o.get("accrued_on").toString())
                .containsExactlyInAnyOrder(first.toString(), second.toString());
        assertThat(openings(q)).singleElement().satisfies(o -> {
            assertThat(o.get("month")).isEqualTo(earliestOpen);
            assertThat(o.get("accrued_on").toString()).isEqualTo(first.toString());
        });
        // no charge went into the closed month
        assertThat(ownerJdbc().queryForObject("select count(*) from charge c join billing_month m "
                + "on m.id = c.billing_month_id where m.tenant_id = ? and m.month = date '2024-03-01'",
                Integer.class, DEMO_TENANT)).isZero();
    }

    @Test
    void aRenamedSeededTypeIsFoundByItsNewNameOnlyAndADefaultNameOfATypeThatKeepsItIsFoundInBothLanguages()
            throws Exception {
        storedPlot("p");
        waterTypeBefore = ownerJdbc().queryForMap("select id, name, name_customized from charge_type "
                + "where tenant_id = ? and code = 'WATER'", DEMO_TENANT);
        ownerJdbc().update("update charge_type set name = ?, name_customized = true where id = ?",
                "Водопостачання " + tag, waterTypeBefore.get("id"));
        LocalDate date = firstOfMonth();

        JsonNode preview = preview(english(List.of(), List.of(
                balance(plotNo("p"), "Вода", 10, date, null),
                balance(plotNo("p"), "Water", 10, date, null),
                balance(plotNo("p"), "водопостачання " + tag, 10, date, null),
                balance(plotNo("p"), "Електроенергія", 10, date, null),
                balance(plotNo("p"), "electricity", 10, date, null))));

        assertThat(problems(preview)).containsExactly(
                "OPENING_BALANCES/2/charge_type/UNKNOWN_CHARGE_TYPE",
                "OPENING_BALANCES/3/charge_type/UNKNOWN_CHARGE_TYPE",
                "OPENING_BALANCES/6/charge_type/DUPLICATE_IN_FILE");
        assertThat(openingCounts(preview).get("created").asInt()).isEqualTo(2);
    }

    @Test
    void aBalanceEnteredBetweenThePreviewAndTheCommitMakesThePreviewStale() throws Exception {
        UUID p = storedPlot("p");
        LocalDate date = firstOfMonth();
        JsonNode preview = preview(english(List.of(), List.of(
                balance(plotNo("p"), "Членський внесок", "100.00", date, null))));
        assertThat(openingCounts(preview).get("created").asInt()).isEqualTo(1);

        enteredOpening(p, chargeType("MEMBERSHIP"), "100.00", date);

        commit(preview.get("id").asString()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_STALE"));
        assertThat(openings(p)).hasSize(1);
    }

    // ---- problems

    @Test
    void everyRowProblemIsReportedOnItsRowAndColumnWithACodeAndNothingIsBooked() throws Exception {
        UUID p = storedPlot("p");
        UUID q = storedPlot("q");
        customType("Archived fee " + tag, true);
        ownerJdbc().update("update plot set kind = 'COMMON' where id = ?", q);
        LocalDate date = firstOfMonth();
        enteredOpening(p, chargeType("LAND_TAX"), "300.00", date);
        String tooLong = "x".repeat(501);
        byte[] workbook = english(List.of(
                newPlot("a"),
                plot(plotNo("broken"), null, "PLOT", 600, null, null, "perhaps", null)), List.of(
                balance(plotNo("nowhere"), "Membership fee", 10, date, null),
                balance(plotNo("p"), "No such fee", 10, date, null),
                balance(plotNo("p"), "archived fee " + tag, 10, date, null),
                balance(plotNo("p"), "Membership fee", 0, date, null),
                balance(plotNo("p"), "Membership fee", "-5", date, null),
                balance(plotNo("p"), "Membership fee", "10.005", date, null),
                balance(plotNo("p"), "Membership fee", "lots", date, null),
                balance(plotNo("p"), "Membership fee", 10, today().plusDays(1), null),
                balance(plotNo("p"), "Membership fee", 10, "31.02.2026", null),
                balance(plotNo("p"), "Membership fee", 10, date, tooLong),
                balance(plotNo("q"), "Membership fee", 10, date, null),
                balance(plotNo("a"), "Water", 10, date, null),
                balance(plotNo("a"), "вода", 20, date, null),
                balance(plotNo("p"), "Land tax", "300.01", date, null),
                balance(plotNo("broken"), "Membership fee", 10, date, null),
                balance(null, null, null, null, "only a reason"),
                balance(plotNo("p"), "Membership fee", "1234567890123.00", date, null)));

        JsonNode preview = preview(workbook);

        assertThat(problems(preview)).containsExactly(
                "PLOTS/3/has_building/YES_NO_INVALID",
                "OPENING_BALANCES/2/plot_number/UNKNOWN_PLOT",
                "OPENING_BALANCES/3/charge_type/UNKNOWN_CHARGE_TYPE",
                "OPENING_BALANCES/4/charge_type/CHARGE_TYPE_ARCHIVED",
                "OPENING_BALANCES/5/amount/AMOUNT_NOT_POSITIVE",
                "OPENING_BALANCES/6/amount/AMOUNT_NOT_POSITIVE",
                "OPENING_BALANCES/7/amount/INVALID",
                "OPENING_BALANCES/8/amount/NUMBER_INVALID",
                "OPENING_BALANCES/9/date/DATE_IN_FUTURE",
                "OPENING_BALANCES/10/date/DATE_INVALID",
                "OPENING_BALANCES/11/reason/TOO_LONG",
                "OPENING_BALANCES/12/plot_number/PLOT_COMMON_LAND",
                "OPENING_BALANCES/14/charge_type/DUPLICATE_IN_FILE",
                "OPENING_BALANCES/15/amount/OPENING_BALANCE_EXISTS",
                "OPENING_BALANCES/17/plot_number/REQUIRED",
                "OPENING_BALANCES/17/charge_type/REQUIRED",
                "OPENING_BALANCES/17/amount/REQUIRED",
                "OPENING_BALANCES/17/date/REQUIRED",
                "OPENING_BALANCES/18/amount/INVALID");
        JsonNode exists = preview.get("problems").get(13);
        assertThat(exists.get("params").get("amount").asString()).isEqualTo("300.00");
        // rows without a problem are counted, as on every sheet, but a file with problems cannot be committed
        assertThat(openingCounts(preview).get("created").asInt()).isEqualTo(1);
        assertThat(preview.get("committable").asBoolean()).isFalse();
        commit(preview.get("id").asString()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_HAS_ERRORS"));
        assertThat(openings(p)).hasSize(1);
    }

    @Test
    void aSheetWithoutARequiredColumnIsReportedOnTheHeaderAndAnEmptySheetMissesEveryRequiredColumn()
            throws Exception {
        byte[] missingAmount = ImportWorkbooks.builder()
                .sheet("Plots", PLOT_HEADER)
                .sheet("Opening balances", List.of("plot_number", "charge_type", "date"),
                        List.of(plotNo("p"), "Membership fee", "01.01.2026"))
                .build();
        assertThat(problems(preview(missingAmount))).containsExactly("OPENING_BALANCES/1/amount/HEADER_MISSING");

        byte[] empty = ImportWorkbooks.builder()
                .sheet("Plots", PLOT_HEADER)
                .sheetWithRows("Вхідне сальдо", List.of())
                .build();
        assertThat(problems(preview(empty))).containsExactly(
                "OPENING_BALANCES/1/plot_number/HEADER_MISSING",
                "OPENING_BALANCES/1/charge_type/HEADER_MISSING",
                "OPENING_BALANCES/1/amount/HEADER_MISSING",
                "OPENING_BALANCES/1/date/HEADER_MISSING");
    }
}
