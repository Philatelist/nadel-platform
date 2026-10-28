package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterReports;
import app.nadel.meters.MeterTreeQuantity;
import app.nadel.meters.MeterTreeReport;
import app.nadel.meters.MeterTreeReportRow;
import app.nadel.meters.MeterTreeReportSlice;
import app.nadel.meters.MeterTreeUnknownReason;
import app.nadel.meters.MeterTreeValue;
import app.nadel.meters.Quantity;
import app.nadel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tree of the electricity report ({@link MeterReports#tree}, feature 013 §3.4) on a cooperative of its own, over
 * October 2025 (values on 01.10 and 31.10):
 * <pre>
 * ТП-1 (substation)      10 000 -> 12 300 = 2 300
 * ├── Квартал А          5 000 -> 6 100 = 1 100; children 1 030 (calculated), losses 70 = 6.36 %
 * │   ├── Освітлення     read 300 on 01.09 and 390 on 30.11: 330 -> 360 = 30, both values calculated
 * │   ├── plot 1         1 000 -> 1 600 = 600
 * │   └── plot 2         old meter 2 000 -> replaced on 15.10 at 2 150, new meter 3 -> 253: 150 + 250 = 400
 * └── Квартал Б          7 000 -> no reading after 01.10: unknown, so its losses are too
 *     └── plot 3         3 000 -> 4 000 = 1 000
 * </pre>
 * The substation's children's sum and losses are unknown while quarter Б is; with Б read on 31.10 at 8 050 they are
 * 2 150 and 150 (6.52 %). Every row also equals the node of {@code GET /meter-tree} for the same range.
 */
class MeterReportsTests extends IntegrationTest {

    private static final LocalDate FROM = LocalDate.parse("2025-10-01");
    private static final LocalDate TO = LocalDate.parse("2025-10-31");
    private static final String INSTALLED = "2025-01-01";

    @Autowired
    MeterReports reports;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private UUID tenant;
    private String chairman;
    private UUID p1;
    private UUID p2;
    private UUID p3;
    private UUID substation;
    private UUID quarterA;
    private UUID quarterB;
    private UUID lighting;
    private UUID meterP1;
    private UUID oldP2;
    private UUID newP2;
    private UUID meterP3;

    @BeforeEach
    void buildTheTree() {
        tenant = UUID.randomUUID();
        JdbcTemplate owner = ownerJdbc();
        owner.update("insert into tenant (id, name) values (?, 'Meter report cooperative')", tenant);
        owner.queryForList("select seed_base_roles(?)", tenant);
        UUID member = UUID.randomUUID();
        String email = "chairman-" + member + "@tree.test";
        owner.update("insert into member (id, tenant_id, last_name, first_name) values (?, ?, 'Test', 'Chairman')",
                member, tenant);
        insertContacts(tenant, member, email);
        owner.update("insert into member_role (tenant_id, member_id, role_id) select ?, ?, id from role "
                + "where tenant_id = ? and base_code = 'CHAIRMAN'", tenant, member, tenant);
        chairman = TestTokens.bearer(tenant, "sub-" + email, email);

        p1 = plot("1");
        p2 = plot("2");
        p3 = plot("3");
        substation = named("SUBSTATION", "ТП-1", null);
        read(substation, "2025-10-01", "10000");
        read(substation, "2025-10-31", "12300");
        quarterA = named("QUARTER", "Квартал А", substation);
        read(quarterA, "2025-10-01", "5000");
        read(quarterA, "2025-10-31", "6100");
        quarterB = named("QUARTER", "Квартал Б", substation);
        read(quarterB, "2025-10-01", "7000");
        lighting = named("COMMON_CONSUMER", "Освітлення", quarterA);
        read(lighting, "2025-09-01", "300");
        read(lighting, "2025-11-30", "390");
        meterP1 = plotMeter(p1, quarterA, INSTALLED, null);
        read(meterP1, "2025-10-01", "1000");
        read(meterP1, "2025-10-31", "1600");
        oldP2 = plotMeter(p2, quarterA, INSTALLED, null);
        read(oldP2, "2025-10-01", "2000");
        // the replacement as the meter commands write it: the old meter removed with its final reading, its period
        // ending on the day, the new one replacing it from that day under the same parent
        owner.update("update meter set removed_on = date '2025-10-15', final_value = 2150 where id = ?", oldP2);
        owner.update("update meter_parent set valid_to = date '2025-10-15' where meter_id = ?", oldP2);
        newP2 = plotMeter(p2, quarterA, "2025-10-15", oldP2);
        read(newP2, "2025-10-31", "253");
        meterP3 = plotMeter(p3, quarterB, INSTALLED, null);
        read(meterP3, "2025-10-01", "3000");
        read(meterP3, "2025-10-31", "4000");
    }

    @AfterEach
    void removeTheCooperative() {
        JdbcTemplate owner = ownerJdbc();
        owner.update("delete from meter_reading where tenant_id = ?", tenant);
        owner.update("delete from meter_parent where tenant_id = ?", tenant);
        owner.update("delete from meter where tenant_id = ? and replaces_meter_id is not null", tenant);
        for (String table : List.of("meter", "audit_entry", "plot_status", "plot", "member_role", "role_permission",
                "role", "member", "charge_type", "cooperative_settings")) {
            owner.update("delete from " + table + " where tenant_id = ?", tenant);
        }
        deleteDataKeys(tenant);
        owner.update("delete from tenant where id = ?", tenant);
    }

    @Test
    void everyNodeShowsItsValuesConsumptionChildrenLossesAndPlotsInTheOrderOfTheTree() {
        MeterTreeReport report = tree(tenant, FROM, TO);

        assertThat(report.from()).isEqualTo(FROM);
        assertThat(report.to()).isEqualTo(TO);
        assertThat(report.rows()).extracting(this::line).containsExactly(
                "0 - ТП-1 SUBSTATION 2300 exact | children ? CHILD_VALUE_UNKNOWN 2025-10-01"
                        + " | losses ? CHILD_VALUE_UNKNOWN 2025-10-01 - | plots 1,2,3",
                "1 ТП-1 Квартал А QUARTER 1100 exact | children 1030 calculated | losses 70 calculated 6.36"
                        + " | plots 1,2",
                "2 Квартал А Освітлення COMMON_CONSUMER 30 calculated | plots ",
                "2 Квартал А 1 PLOT 600 exact | plots 1",
                "2 Квартал А 2 PLOT 400 exact | plots 2",
                "1 ТП-1 Квартал Б QUARTER ? VALUE_UNKNOWN 2025-10-01 | children 1000 exact"
                        + " | losses ? VALUE_UNKNOWN 2025-10-01 - | plots 3",
                "2 Квартал Б 3 PLOT 1000 exact | plots 3");
        MeterTreeReportRow light = row(report, lighting);
        assertThat(light.slices()).singleElement().satisfies(slice -> {
            assertThat(value(slice.start())).isEqualTo("CALCULATED 330");
            assertThat(value(slice.end())).isEqualTo("CALCULATED 360");
        });
        MeterTreeReportRow b = row(report, quarterB);
        assertThat(value(b.slices().getFirst().end())).isEqualTo("UNKNOWN 2025-10-01");
        assertThat(b.plotsFed()).isEqualTo(1);
    }

    @Test
    void aReplacedMeterAndItsSuccessorAreOneRowReadOffTheMeterThatWorkedOnEachDay() {
        MeterTreeReportRow node = row(tree(tenant, FROM, TO), oldP2);

        assertThat(node.meterId()).isEqualTo(newP2);
        assertThat(node.meterIds()).containsExactly(oldP2, newP2);
        assertThat(node.plotId()).isEqualTo(p2);
        assertThat(node.slices()).singleElement().satisfies(slice -> {
            assertThat(slice.start().meterId()).isEqualTo(oldP2);
            assertThat(value(slice.start())).isEqualTo("REAL 2000");
            assertThat(slice.end().meterId()).isEqualTo(newP2);
            assertThat(value(slice.end())).isEqualTo("REAL 253");
            assertThat(quantity(slice.consumption())).isEqualTo("400 exact");
        });
    }

    @Test
    void withEveryValueKnownTheLossesAreTheConsumptionMinusTheChildren() {
        read(quarterB, "2025-10-31", "8050");

        MeterTreeReport report = tree(tenant, FROM, TO);

        MeterTreeReportRow top = row(report, substation);
        assertThat(quantity(top.childrenConsumption())).isEqualTo("2150 exact");
        assertThat(quantity(top.losses().kwh())).isEqualTo("150 exact");
        assertThat(top.losses().percent()).isEqualByComparingTo("6.52");
        MeterTreeReportRow b = row(report, quarterB);
        assertThat(quantity(b.consumption())).isEqualTo("1050 exact");
        assertThat(quantity(b.losses().kwh())).isEqualTo("50 exact");
        assertThat(b.losses().percent()).isEqualByComparingTo("4.76");
    }

    @Test
    void everyRowEqualsTheNodeTheTreeEndpointServes() throws Exception {
        for (LocalDate[] range : List.of(new LocalDate[] {FROM, TO},
                new LocalDate[] {LocalDate.parse("2025-10-10"), LocalDate.parse("2025-10-20")},
                new LocalDate[] {LocalDate.parse("2025-09-01"), LocalDate.parse("2025-11-30")})) {
            JsonNode endpoint = json.readTree(mvc.perform(get("/api/v1/meter-tree")
                    .param("from", range[0].toString()).param("to", range[1].toString())
                    .header(HttpHeaders.AUTHORIZATION, chairman))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            List<String> expected = new ArrayList<>();
            describe(endpoint.get("roots"), 0, null, expected);

            List<String> facade = tree(tenant, range[0], range[1]).rows().stream().map(MeterReportsTests::describe)
                    .toList();

            assertThat(facade).hasSize(7).containsExactlyElementsOf(expected);
        }
    }

    @Test
    void anotherCooperativeNeverSeesTheTree() {
        List<UUID> shown = tree(OTHER_TENANT, FROM, TO).rows().stream().map(MeterTreeReportRow::meterId).toList();

        assertThat(shown).doesNotContain(substation, quarterA, quarterB, lighting, meterP1, newP2, meterP3);
    }

    @Test
    void aRangeEndingBeforeItStartsIsRefused() {
        assertThatThrownBy(() -> tree(tenant, TO, FROM)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itRunsOnlyInTheCallersTransaction() {
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> reports.tree(FROM, TO)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---- reading the answers

    private MeterTreeReport tree(UUID cooperative, LocalDate from, LocalDate to) {
        return TenantContext.runAs(cooperative, () -> tx.execute(status -> reports.tree(from, to)));
    }

    private static MeterTreeReportRow row(MeterTreeReport report, UUID meter) {
        return report.rows().stream().filter(row -> row.meterIds().contains(meter)).findFirst().orElseThrow();
    }

    /** {@code level parent name kind consumption | children | losses | plots} with plot numbers for plot ids. */
    private String line(MeterTreeReportRow row) {
        StringBuilder line = new StringBuilder();
        line.append(row.level()).append(' ').append(row.parentMeterId() == null ? "-" : nameOf(row.parentMeterId()))
                .append(' ').append(row.kind() == MeterKind.PLOT ? row.plotNumber() : row.name()).append(' ')
                .append(row.kind()).append(' ').append(quantity(row.consumption()));
        if (row.childrenConsumption() != null) {
            line.append(" | children ").append(quantity(row.childrenConsumption()));
            line.append(" | losses ").append(quantity(row.losses().kwh())).append(' ')
                    .append(row.losses().percent() == null ? "-" : row.losses().percent().toPlainString());
        }
        line.append(" | plots ").append(String.join(",", row.fedPlotIds().stream()
                .map(id -> id.equals(p1) ? "1" : id.equals(p2) ? "2" : id.equals(p3) ? "3" : "?").toList()));
        return line.toString();
    }

    private String nameOf(UUID meter) {
        return meter.equals(substation) ? "ТП-1" : meter.equals(quarterA) ? "Квартал А"
                : meter.equals(quarterB) ? "Квартал Б" : meter.toString();
    }

    /** {@code kwh exact|calculated} of a known quantity, {@code ? REASON lastReadingOn} of an unknown one. */
    private static String quantity(@Nullable MeterTreeQuantity quantity) {
        if (quantity == null) {
            return "none";
        }
        if (!quantity.isKnown()) {
            return "? " + quantity.reason() + " " + quantity.lastReadingOn();
        }
        return plain(quantity.kwh()) + " " + (quantity.calculated() ? "calculated" : "exact");
    }

    private static String value(MeterTreeValue value) {
        return value.kind() + " " + (value.value() == null ? value.lastReadingOn() : plain(value.value()));
    }

    /** A whole quantity as a number; a fraction as {@code n/d}. */
    private static String plain(Quantity quantity) {
        return quantity.denominator().equals(BigInteger.ONE) ? quantity.numerator().toString() : quantity.toString();
    }

    // ---- the endpoint and the facade in one shape

    /** The nodes of the endpoint, depth first, each as {@link #describe(MeterTreeReportRow)} writes a row. */
    private static void describe(JsonNode nodes, int level, @Nullable String parent, List<String> lines) {
        for (JsonNode node : nodes) {
            JsonNode meter = node.get("meter");
            List<String> slices = new ArrayList<>();
            for (JsonNode slice : node.get("slices")) {
                slices.add(slice.get("from").asString() + ".." + slice.get("to").asString() + " "
                        + valueAt(slice.get("start")) + " " + valueAt(slice.get("end")) + " "
                        + consumption(slice.get("consumption")));
            }
            List<String> ids = new ArrayList<>();
            node.get("meterIds").forEach(id -> ids.add(id.asString()));
            JsonNode plot = meter.get("plot");
            lines.add(String.join(" ", String.valueOf(level), String.valueOf(parent), meter.get("id").asString(),
                    ids.toString(), meter.get("kind").asString(), meter.get("serial").asString(), text(meter, "name"),
                    plot == null ? "null" : plot.get("id").asString(), plot == null ? "null" : text(plot, "number"),
                    plot == null ? "null" : text(plot, "street"), meter.get("installedOn").asString(),
                    text(meter, "removedOn"), String.valueOf(meter.get("active").asBoolean()), slices.toString(),
                    consumption(node.get("total"))));
            describe(node.get("children"), level + 1, meter.get("id").asString(), lines);
        }
    }

    private static String describe(MeterTreeReportRow row) {
        List<String> slices = new ArrayList<>();
        for (MeterTreeReportSlice slice : row.slices()) {
            slices.add(slice.from() + ".." + slice.to() + " " + valueAt(slice.start()) + " " + valueAt(slice.end())
                    + " " + consumption(slice.consumption()));
        }
        return String.join(" ", String.valueOf(row.level()), String.valueOf(row.parentMeterId()),
                row.meterId().toString(), row.meterIds().stream().map(UUID::toString).toList().toString(),
                row.kind().name(), row.serial(), String.valueOf(row.name()), String.valueOf(row.plotId()),
                String.valueOf(row.plotNumber()), String.valueOf(row.street()), row.installedOn().toString(),
                String.valueOf(row.removedOn()), String.valueOf(row.active()), slices.toString(),
                consumption(row.consumption()));
    }

    private static String valueAt(JsonNode value) {
        return value.get("kind").asString() + "@" + value.get("meterId").asString() + "=" + text(value, "value") + "/"
                + text(value, "lastReadingOn");
    }

    private static String valueAt(MeterTreeValue value) {
        return value.kind() + "@" + value.meterId() + "=" + (value.value() == null ? "null"
                : value.value().toMeterValue().toString()) + "/" + value.lastReadingOn();
    }

    private static String consumption(JsonNode range) {
        return range.get("known").asBoolean() + ":" + range.get("calculated").asBoolean() + ":"
                + text(range, "consumption") + ":" + text(range, "lastReadingOn");
    }

    /** The endpoint's shape: a known quantity rounded once for display, an unknown one with its date only. */
    private static String consumption(MeterTreeQuantity quantity) {
        return quantity.isKnown() + ":" + quantity.calculated() + ":"
                + (quantity.isKnown() ? quantity.kwh().toMeterValue().toString() : "null") + ":"
                + quantity.lastReadingOn();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "null" : value.asString();
    }

    // ---- fixtures, written as the database owner

    private UUID plot(String number) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, street, kind, area_m2) values (?, ?, ?, "
                + "'Яблунева', 'PLOT', 600)", id, tenant, number);
        ownerJdbc().update("insert into plot_status (plot_id, tenant_id, status) values (?, ?, 'GREY')", id, tenant);
        return id;
    }

    private UUID named(String kind, String name, @Nullable UUID parent) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into meter (id, tenant_id, kind, resource, serial, name, installed_on, "
                + "initial_value) values (?, ?, ?, 'ELECTRICITY', ?, ?, ?::date, 0)", id, tenant, kind,
                "MR-" + id.toString().substring(0, 8), name, INSTALLED);
        place(id, parent, INSTALLED);
        return id;
    }

    private UUID plotMeter(UUID plot, UUID parent, String installedOn, @Nullable UUID replaces) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into meter (id, tenant_id, kind, resource, serial, plot_id, installed_on, "
                + "initial_value, replaces_meter_id) values (?, ?, 'PLOT', 'ELECTRICITY', ?, ?, ?::date, ?, ?)", id,
                tenant, "MR-" + id.toString().substring(0, 8), plot, installedOn,
                new BigDecimal(replaces == null ? "0" : "3"), replaces);
        place(id, parent, installedOn);
        return id;
    }

    private void place(UUID meter, @Nullable UUID parent, String from) {
        if (parent != null) {
            ownerJdbc().update("insert into meter_parent (tenant_id, meter_id, parent_meter_id, valid_from) "
                    + "values (?, ?, ?, ?::date)", tenant, meter, parent, from);
        }
    }

    private void read(UUID meter, String on, String value) {
        ownerJdbc().update("insert into meter_reading (tenant_id, meter_id, taken_on, value, source, status) "
                + "values (?, ?, ?::date, ?, 'ENTERED', 'APPROVED')", tenant, meter, on, new BigDecimal(value));
    }
}
