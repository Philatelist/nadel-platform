package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterReports;
import app.nadel.meters.MeterResource;
import app.nadel.meters.MissingReadingRow;
import app.nadel.meters.Quantity;
import app.nadel.meters.WaterSourceKind;
import app.nadel.meters.WaterUseRow;
import app.nadel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The water use ({@link MeterReports#waterUse}, feature 013 §3.5) and the missing readings
 * ({@link MeterReports#missingReadings}, §3.6) on a cooperative of its own, over October 2025 (plots 1 to 6 on one
 * street, a well "Свердловина" and a quarter meter):
 * <pre>
 * plot 1  water supply since 01.01; water meter 100 (01.10) -> 130 (31.10)             = 30 m3, 31 days
 *         electricity read 15.10
 * plot 2  well from 16.10; water meter 200 (01.10) -> 230 (31.10)                     = 15 m3 calculated, 16 days
 *         electricity read 10.09 (1 000), a member's reading of 20.10 waiting for approval
 * plot 3  water supply 01.01-30.09 only (not connected in October); water meter never read
 *         electricity meter installed 05.11 (after the range)
 * plot 4  water supply since 01.01; water meter read 01.09 only                        = unknown since 01.09
 *         electricity meter removed 20.09 (before the range)
 * plot 5  well since 01.01, no water meter; electricity meter (initial 7) never read
 * plot 6  water supply 01.01-10.10, then the well; water meter 500 (01.10) -> 562 (31.10): 62/3 + 124/3 = 62 m3
 *         electricity meter installed 10.10 (in the range)
 * </pre>
 */
class MeterReportsWaterAndMissingTests extends IntegrationTest {

    private static final LocalDate FROM = LocalDate.parse("2025-10-01");
    private static final LocalDate TO = LocalDate.parse("2025-10-31");
    private static final String INSTALLED = "2025-01-01";

    @Autowired
    MeterReports reports;

    @Autowired
    TransactionTemplate tx;

    private UUID tenant;
    private UUID p1;
    private UUID p2;
    private UUID p3;
    private UUID p4;
    private UUID p5;
    private UUID p6;
    private UUID well;
    private UUID quarter;
    private UUID e2;
    private UUID e5;
    private UUID w1;
    private UUID w2;
    private UUID w3;
    private UUID w4;
    private UUID w6;

    @BeforeEach
    void buildTheCooperative() {
        tenant = UUID.randomUUID();
        JdbcTemplate owner = ownerJdbc();
        owner.update("insert into tenant (id, name) values (?, 'Water report cooperative')", tenant);
        owner.queryForList("select seed_base_roles(?)", tenant);
        p1 = plot("1");
        p2 = plot("2");
        p3 = plot("3");
        p4 = plot("4");
        p5 = plot("5");
        p6 = plot("6");
        well = meter("COMMON_CONSUMER", "ELECTRICITY", null, "Свердловина", INSTALLED, "0");
        quarter = meter("QUARTER", "ELECTRICITY", null, "Квартал А", INSTALLED, "0");

        connect(p1, null, INSTALLED, null);
        w1 = meter("PLOT", "WATER", p1, null, INSTALLED, "0");
        read(w1, "2025-10-01", "100", "APPROVED");
        read(w1, "2025-10-31", "130", "APPROVED");
        UUID e1 = meter("PLOT", "ELECTRICITY", p1, null, INSTALLED, "0");
        read(e1, "2025-10-15", "50", "APPROVED");

        connect(p2, well, "2025-10-16", null);
        w2 = meter("PLOT", "WATER", p2, null, INSTALLED, "0");
        read(w2, "2025-10-01", "200", "APPROVED");
        read(w2, "2025-10-31", "230", "APPROVED");
        e2 = meter("PLOT", "ELECTRICITY", p2, null, INSTALLED, "0");
        read(e2, "2025-09-10", "1000", "APPROVED");
        read(e2, "2025-10-20", "1100", "PENDING");

        connect(p3, null, INSTALLED, "2025-09-30");
        w3 = meter("PLOT", "WATER", p3, null, INSTALLED, "0");
        meter("PLOT", "ELECTRICITY", p3, null, "2025-11-05", "0");

        connect(p4, null, INSTALLED, null);
        w4 = meter("PLOT", "WATER", p4, null, INSTALLED, "0");
        read(w4, "2025-09-01", "40", "APPROVED");
        UUID e4 = meter("PLOT", "ELECTRICITY", p4, null, INSTALLED, "0");
        owner.update("update meter set removed_on = date '2025-09-20', final_value = 90 where id = ?", e4);

        connect(p5, well, INSTALLED, null);
        e5 = meter("PLOT", "ELECTRICITY", p5, null, INSTALLED, "7");

        connect(p6, null, INSTALLED, "2025-10-10");
        connect(p6, well, "2025-10-11", null);
        w6 = meter("PLOT", "WATER", p6, null, INSTALLED, "0");
        read(w6, "2025-10-01", "500", "APPROVED");
        read(w6, "2025-10-31", "562", "APPROVED");
        meter("PLOT", "ELECTRICITY", p6, null, "2025-10-10", "0");
    }

    @AfterEach
    void removeTheCooperative() {
        JdbcTemplate owner = ownerJdbc();
        for (String table : List.of("meter_reading", "plot_water_connection", "meter", "audit_entry", "plot_status",
                "plot", "member_role", "role_permission", "role", "member", "charge_type", "cooperative_settings")) {
            owner.update("delete from " + table + " where tenant_id = ?", tenant);
        }
        deleteDataKeys(tenant);
        owner.update("delete from tenant where id = ?", tenant);
    }

    // ---- water use

    @Test
    void everyConnectedPlotHasItsConnectedDaysAndTheCubicMetresOfThoseDays() {
        assertThat(waterUse(tenant, FROM, TO, null)).extracting(this::line).containsExactly(
                "1 COOPERATIVE_SUPPLY 31 2025-10-01..2025-10-31 W1 30.000 exact",
                "2 METER Свердловина 16 2025-10-16..2025-10-31 W2 15.000 calculated",
                "4 COOPERATIVE_SUPPLY 31 2025-10-01..2025-10-31 W4 unknown VALUE_UNKNOWN 2025-09-01",
                "5 METER Свердловина 31 2025-10-01..2025-10-31 no meter",
                "6 COOPERATIVE_SUPPLY 10 2025-10-01..2025-10-10 W6 20.667 calculated",
                "6 METER Свердловина 21 2025-10-11..2025-10-31 W6 41.333 calculated");
    }

    @Test
    void aPlotConnectedForPartOfTheRangeCountsOnlyThoseDays() {
        WaterUseRow p2Row = waterUse(tenant, FROM, TO, null).stream().filter(row -> row.plotId().equals(p2))
                .findFirst().orElseThrow();

        // 16.10-31.10 only: the value on 16.10 is 200 + 30 * 15 / 30 = 215, so 230 - 215, not the month's 30
        assertThat(p2Row.connectedDays()).isEqualTo(16);
        assertThat(p2Row.cubicMetres()).isEqualTo(m3(15, 1));
        assertThat(p2Row.calculated()).isTrue();
        assertThat(p2Row.sourceMeterId()).isEqualTo(well);
    }

    @Test
    void theSlicesOfAPlotThatChangedSourceAddUpToItsWholeRange() {
        List<WaterUseRow> p6Rows = waterUse(tenant, FROM, TO, null).stream().filter(row -> row.plotId().equals(p6))
                .toList();

        assertThat(p6Rows).extracting(WaterUseRow::cubicMetres).containsExactly(m3(62, 3), m3(124, 3));
        assertThat(p6Rows.get(0).cubicMetres().plus(p6Rows.get(1).cubicMetres())).isEqualTo(m3(62, 1));
    }

    @Test
    void aPlotNotConnectedOnAnyDayOfTheRangeIsNotListed() {
        assertThat(waterUse(tenant, FROM, TO, null)).extracting(WaterUseRow::plotId).doesNotContain(p3);
        // in September plot 3 was connected, plot 2 was not
        assertThat(waterUse(tenant, LocalDate.parse("2025-09-01"), LocalDate.parse("2025-09-30"), null))
                .extracting(WaterUseRow::plotId).contains(p3).doesNotContain(p2);
    }

    @Test
    void theSourceFilterKeepsOnlyThePlotsOfThatKindOfSource() {
        assertThat(waterUse(tenant, FROM, TO, WaterSourceKind.METER)).extracting(row -> number(row.plotId()))
                .containsExactly("2", "5", "6");
        assertThat(waterUse(tenant, FROM, TO, WaterSourceKind.COOPERATIVE_SUPPLY))
                .extracting(row -> number(row.plotId())).containsExactly("1", "4", "6");
    }

    @Test
    void aWaterMeterWithoutAReadingAfterTheRangeGivesUnknownCubicMetresNeverZero() {
        WaterUseRow p4Row = waterUse(tenant, FROM, TO, null).stream().filter(row -> row.plotId().equals(p4))
                .findFirst().orElseThrow();

        assertThat(p4Row.isKnown()).isFalse();
        assertThat(p4Row.cubicMetres()).isNull();
        assertThat(p4Row.lastReadingOn()).isEqualTo(LocalDate.parse("2025-09-01"));
        assertThat(p4Row.meterId()).isEqualTo(w4);
    }

    @Test
    void anotherCooperativeNeverSeesTheWaterUse() {
        assertThat(waterUse(OTHER_TENANT, FROM, TO, null)).extracting(WaterUseRow::plotId)
                .doesNotContain(p1, p2, p4, p5, p6);
    }

    // ---- missing readings

    @Test
    void anActiveMeterWithoutAnApprovedReadingInTheRangeIsListedWithItsLastReading() {
        assertThat(missing(tenant, Set.of(), Set.of(MeterKind.PLOT), null, 100)).extracting(this::line)
                .containsExactly(
                        "2 ELECTRICITY PLOT 2025-09-10 1000.000 reading 51 pending",
                        "4 WATER PLOT 2025-09-01 40.000 reading 60 -",
                        "5 ELECTRICITY PLOT 2025-01-01 7.000 initial 303 -");
    }

    @Test
    void aMeterWithOnlyAReadingWaitingForApprovalIsListedAndMarked() {
        MissingReadingRow row = missing(tenant, Set.of(), Set.of(), null, 100).stream()
                .filter(r -> r.meterId().equals(e2)).findFirst().orElseThrow();

        assertThat(row.pendingApprovalInRange()).isTrue();
        assertThat(row.lastReadingInitial()).isFalse();
        assertThat(row.daysSinceLastReading()).isEqualTo(51);
    }

    @Test
    void aMeterReadInTheRangeAWaterMeterOfAPlotNotConnectedAndMetersOutsideTheRangeAreNotListed() {
        List<UUID> listed = missing(tenant, Set.of(), Set.of(), null, 100).stream().map(MissingReadingRow::meterId)
                .toList();

        // read in the range (w1, w2, w6, e1), not connected (w3), installed after or in it, removed before it
        assertThat(listed).doesNotContain(w1, w2, w6, w3).hasSize(5);
        assertThat(meterIdsOf(Set.of(p1, p3, p6))).allSatisfy(meter -> assertThat(listed).doesNotContain(meter));
    }

    @Test
    void theOtherKindsFollowThePlotMetersByKindAndName() {
        assertThat(missing(tenant, Set.of(), Set.of(), null, 100)).extracting(this::line).containsExactly(
                "2 ELECTRICITY PLOT 2025-09-10 1000.000 reading 51 pending",
                "4 WATER PLOT 2025-09-01 40.000 reading 60 -",
                "5 ELECTRICITY PLOT 2025-01-01 7.000 initial 303 -",
                "Квартал А ELECTRICITY QUARTER 2025-01-01 0.000 initial 303 -",
                "Свердловина ELECTRICITY COMMON_CONSUMER 2025-01-01 0.000 initial 303 -");
    }

    @Test
    void theResourceKindAndPlotFiltersAndTheLimitNarrowTheList() {
        assertThat(missing(tenant, Set.of(MeterResource.ELECTRICITY), Set.of(MeterKind.PLOT), null, 100))
                .extracting(MissingReadingRow::meterId).containsExactly(e2, e5);
        assertThat(missing(tenant, Set.of(), Set.of(MeterKind.COMMON_CONSUMER), null, 100))
                .extracting(MissingReadingRow::meterId).containsExactly(well);
        // a plot filter leaves the named meters out; plot 3's water meter stays out (not connected)
        assertThat(missing(tenant, Set.of(), Set.of(), List.of(p5, p3), 100)).extracting(MissingReadingRow::meterId)
                .containsExactly(e5);
        assertThat(missing(tenant, Set.of(), Set.of(), null, 2)).extracting(MissingReadingRow::meterId)
                .containsExactly(e2, w4);
        assertThat(missing(tenant, Set.of(MeterResource.WATER), Set.of(MeterKind.PLOT), null, 100))
                .extracting(MissingReadingRow::meterId).containsExactly(w4);
    }

    @Test
    void anotherCooperativeNeverSeesTheMeters() {
        assertThat(missing(OTHER_TENANT, Set.of(), Set.of(), null, 1000)).extracting(MissingReadingRow::meterId)
                .doesNotContain(e2, e5, w4, well, quarter);
    }

    @Test
    void invalidArgumentsAreRefused() {
        assertThatThrownBy(() -> waterUse(tenant, TO, FROM, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> missing(tenant, Set.of(), Set.of(), null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> tx.execute(status -> reports.missingReadings(TO,
                FROM, Set.of(), Set.of(), null, 10)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itRunsOnlyInTheCallersTransaction() {
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> reports.waterUse(FROM, TO, null)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> TenantContext.runAs(tenant,
                () -> reports.missingReadings(FROM, TO, Set.of(), Set.of(), null, 10)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---- reading the answers

    private List<WaterUseRow> waterUse(UUID cooperative, LocalDate from, LocalDate to,
            @Nullable WaterSourceKind source) {
        return TenantContext.runAs(cooperative, () -> tx.execute(status -> reports.waterUse(from, to, source)));
    }

    private List<MissingReadingRow> missing(UUID cooperative, Set<MeterResource> resources, Set<MeterKind> kinds,
            @Nullable Collection<UUID> plotIds, int limit) {
        return TenantContext.runAs(cooperative, () -> tx.execute(status -> reports.missingReadings(FROM, TO,
                resources, kinds, plotIds, limit)));
    }

    private String line(WaterUseRow row) {
        String head = String.join(" ", number(row.plotId()), row.source().name(),
                row.sourceName() == null ? "" : row.sourceName(), String.valueOf(row.connectedDays()),
                row.periods().stream().map(p -> p.from() + ".." + p.to()).collect(Collectors.joining(",")))
                .replace("  ", " ");
        if (row.meterId() == null) {
            return head + " no meter";
        }
        String meter = Map.of(w1, "W1", w2, "W2", w4, "W4", w6, "W6").getOrDefault(row.meterId(), "?");
        if (!row.isKnown()) {
            return head + " " + meter + " unknown " + row.reason() + " " + row.lastReadingOn();
        }
        return head + " " + meter + " " + row.cubicMetres().toDecimal().setScale(3).toPlainString() + " "
                + (row.calculated() ? "calculated" : "exact");
    }

    private String line(MissingReadingRow row) {
        return String.join(" ", row.plotId() == null ? row.name() : row.plotNumber(), row.resource().name(),
                row.kind().name(), row.lastReadingOn().toString(), row.lastReadingValue().toString(),
                row.lastReadingInitial() ? "initial" : "reading", String.valueOf(row.daysSinceLastReading()),
                row.pendingApprovalInRange() ? "pending" : "-");
    }

    private String number(UUID plot) {
        return ownerJdbc().queryForObject("select number from plot where id = ?", String.class, plot);
    }

    private List<UUID> meterIdsOf(Set<UUID> plots) {
        return ownerJdbc().queryForList("select id from meter where plot_id = any(?::uuid[])", UUID.class,
                "{" + plots.stream().map(UUID::toString).collect(Collectors.joining(",")) + "}");
    }

    private static Quantity m3(long numerator, long denominator) {
        return Quantity.ofFraction(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    // ---- fixtures, written as the database owner

    private UUID plot(String number) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, street, kind, area_m2) values (?, ?, ?, "
                + "'Яблунева', 'PLOT', 600)", id, tenant, number);
        ownerJdbc().update("insert into plot_status (plot_id, tenant_id, status) values (?, ?, 'GREY')", id, tenant);
        return id;
    }

    private UUID meter(String kind, String resource, @Nullable UUID plot, @Nullable String name, String installedOn,
            String initial) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into meter (id, tenant_id, kind, resource, serial, name, plot_id, installed_on, "
                + "initial_value) values (?, ?, ?, ?, ?, ?, ?, ?::date, ?)", id, tenant, kind, resource,
                "WM-" + id.toString().substring(0, 8), name, plot, installedOn, new BigDecimal(initial));
        return id;
    }

    private void connect(UUID plot, @Nullable UUID source, String from, @Nullable String until) {
        ownerJdbc().update("insert into plot_water_connection (tenant_id, plot_id, source_meter_id, valid_from, "
                + "valid_to) values (?, ?, ?, ?::date, ?::date)", tenant, plot, source, from, until);
    }

    private void read(UUID meter, String on, String value, String status) {
        ownerJdbc().update("insert into meter_reading (tenant_id, meter_id, taken_on, value, source, status) "
                + "values (?, ?, ?::date, ?, ?, ?)", tenant, meter, on, new BigDecimal(value),
                "PENDING".equals(status) ? "MEMBER" : "ENTERED", status);
    }
}
