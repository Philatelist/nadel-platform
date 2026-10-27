package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.meters.Quantity;
import app.nadel.money.Money;
import app.nadel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The water charges of a range of days ({@link LedgerReadModel#waterCharged}, the water report of feature 013 §3.5) on
 * a fixed ledger of a cooperative of its own, for October 2025 (October closed, November open):
 * <pre>
 * WC1 P1 water by meter 23.09-04.10, 5 m3 at 30.0000 = 150.00 (October)
 *     A1 adjustment in November by a recalculation: 6 m3 = 180.00, so +30.00 (+1 m3)
 * WF1 P1 fixed water fee 01.10-31.10, 25.00 (October)
 *     R1 reversed by hand in November, -25.00
 * PS1 P1 share of the well pump (split CONNECTED_PLOTS) 01.10-31.10, 20 kWh at 4.3200 = 86.40 (common consumers type)
 * LS1 P1 share of the street lighting (split EQUAL_PER_PLOT) 01.10-31.10, 54.00: not water
 * WC2 P2 water by meter 05.10-31.10, 2 m3 at 30.0000 = 60.00 (November)
 * P1  water by meter 01.11-20.11 (outside the range) and electricity in October (not water)
 * </pre>
 * P1's rows add up to 266.40 (150.00 + 30.00 + 86.40 + 25.00 - 25.00), P2's to 60.00.
 */
class LedgerWaterChargedTests extends FreshCooperativeSupport {

    private static final LocalDate FROM = LocalDate.parse("2025-10-01");
    private static final LocalDate TO = LocalDate.parse("2025-10-31");

    @Autowired
    LedgerReadModel readModel;

    @Autowired
    TransactionTemplate tx;

    private UUID p1;
    private UUID p2;
    private UUID water;
    private UUID commonConsumers;
    private UUID wellPump;
    private UUID wc1;
    private UUID a1;
    private UUID wf1;
    private UUID r1;
    private UUID ps1;
    private UUID ls1;
    private UUID wc2;

    private UUID otherCharge;

    @BeforeEach
    void bookTheLedger() {
        p1 = plot("1", "600");
        p2 = plot("2", "600");
        UUID p3 = plot("3", "600");
        water = typeId("WATER");
        commonConsumers = typeId("COMMON_CONSUMERS");
        UUID electricity = typeId("ELECTRICITY");
        Tariff byMeter = tariff("WATER", "Вода за лічильником", "PER_M3", "MONTHLY", "30.0000", "2025-01-01");
        Tariff fee = tariff("WATER", "Абонплата за воду", "PER_PLOT", "MONTHLY", "25.0000", "2025-01-01");
        Tariff common = tariff("COMMON_CONSUMERS", "Спільні споживачі", "PER_KWH", "MONTHLY", "4.3200", "2025-01-01");
        UUID w1 = meter(p1, "PLOT", "WATER", null);
        UUID w2 = meter(p2, "PLOT", "WATER", null);
        wellPump = meter(null, "COMMON_CONSUMER", "ELECTRICITY", "Свердловина");
        UUID lighting = meter(null, "COMMON_CONSUMER", "ELECTRICITY", "Освітлення");
        UUID run = UUID.randomUUID();
        owner().update("insert into common_consumer_run (id, tenant_id, from_date, to_date, billing_month_id, total) "
                + "values (?, ?, date '2025-10-01', date '2025-10-31', ?, 140.40)", run, tenant, monthRow("2025-10"));

        wc1 = charge(p1, water, "CONSUMPTION", w1, null, null, "150.00", "2025-09-23", "2025-10-04", "2025-10",
                "2025-10-05 10:00:00+03");
        part(wc1, byMeter.version(), 12, "5", "1", "30.0000");
        wf1 = charge(p1, water, "TARIFF", null, null, null, "25.00", "2025-10-01", "2025-10-31", "2025-10",
                "2025-10-31 12:00:00+02");
        part(wf1, fee.version(), 31, "1", "1", "25.0000");
        ps1 = charge(p1, commonConsumers, "COMMON_CONSUMER", null, run, share(wellPump, "CONNECTED_PLOTS"), "86.40",
                "2025-10-01", "2025-10-31", "2025-10", "2025-10-31 18:00:00+02");
        part(ps1, common.version(), 31, "20", "1", "4.3200");
        ls1 = charge(p1, commonConsumers, "COMMON_CONSUMER", null, run, share(lighting, "EQUAL_PER_PLOT"), "54.00",
                "2025-10-01", "2025-10-31", "2025-10", "2025-10-31 18:00:00+02");
        part(ls1, common.version(), 31, "25", "2", "4.3200");
        wc2 = charge(p2, water, "CONSUMPTION", w2, null, null, "60.00", "2025-10-05", "2025-10-31", "2025-11",
                "2025-11-01 09:00:00+02");
        part(wc2, byMeter.version(), 27, "2", "1", "30.0000");
        closeMonth("2025-10");
        a1 = adjustment(wc1, "30.00", "2025-11-03 10:00:00+02");
        part(a1, byMeter.version(), 12, "6", "1", "30.0000");
        r1 = UUID.randomUUID();
        owner().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, reverses_charge_id, reason, created_at) "
                + "select ?, tenant_id, plot_id, charge_type_id, ?, 'REVERSAL', 'MANUAL_REVERSAL', -amount, "
                + "service_from, service_to, accrued_on, id, 'Не був підключений', "
                + "timestamptz '2025-11-02 09:00:00+02' from charge where id = ?", r1, monthRow("2025-11"), wf1);

        // outside the range or not water
        charge(p1, water, "CONSUMPTION", w1, null, null, "45.00", "2025-11-01", "2025-11-20", "2025-11",
                "2025-11-21 09:00:00+02");
        charge(p1, electricity, "CONSUMPTION", null, null, null, "40.00", "2025-10-01", "2025-10-31", "2025-11",
                "2025-11-01 09:00:00+02");
        charge(p3, water, "CONSUMPTION", null, null, null, "30.00", "2025-10-01", "2025-10-31", "2025-11",
                "2025-11-01 09:00:00+02");
    }

    @AfterEach
    void removeOtherCooperativeRows() {
        if (otherCharge != null) {
            UUID charge = otherCharge;
            asLedgerOwner(sql -> sql.update("delete from charge where id = ?", charge));
            otherCharge = null;
        }
    }

    @Test
    void everyWaterChargeReversalAndAdjustmentIsItsOwnRowWithTheOriginalServicePeriod() {
        List<ChargedRow> rows = charged(tenant, FROM, TO, List.of(p1, p2), 100);

        assertThat(rows).extracting(this::line).containsExactly(
                "WC1 REGULAR CONSUMPTION P1 water 2025-09-23..2025-10-04 2025-10 - 5 30.0000 150.00",
                "A1 ADJUSTMENT CONSUMPTION P1 water 2025-09-23..2025-10-04 2025-11 WC1 1 30.0000 30.00",
                "PS1 REGULAR COMMON_CONSUMER P1 common 2025-10-01..2025-10-31 2025-10 - 20 4.3200 86.40",
                "WF1 REGULAR TARIFF P1 water 2025-10-01..2025-10-31 2025-10 - none 25.0000 25.00",
                "R1 REVERSAL TARIFF P1 water 2025-10-01..2025-10-31 2025-11 WF1 none 25.0000 -25.00",
                "WC2 REGULAR CONSUMPTION P2 water 2025-10-05..2025-10-31 2025-11 - 2 30.0000 60.00");
        assertThat(rows.get(2).consumerMeterId()).isEqualTo(wellPump);
        assertThat(rows.get(0).consumerMeterId()).isNull();
        assertThat(rows.get(0).meterId()).isNotNull();
        assertThat(rows.get(3).meterId()).isNull();
    }

    @Test
    void aWellPumpShareIsWaterAndAnotherCommonConsumersShareIsNot() {
        assertThat(charged(tenant, FROM, TO, List.of(p1), 100)).extracting(ChargedRow::chargeId).contains(ps1)
                .doesNotContain(ls1);
    }

    @Test
    void theRowsOfAPlotAddUpToItsEffectiveWaterCharges() {
        Map<UUID, Money> byPlot = charged(tenant, FROM, TO, List.of(p1, p2), 100).stream().collect(Collectors
                .groupingBy(ChargedRow::plotId, Collectors.reducing(Money.ZERO, ChargedRow::amount, Money::plus)));

        assertThat(byPlot.get(p1)).hasToString("266.40");
        assertThat(byPlot.get(p2)).hasToString("60.00");
        // the m3 of a charge add up like its amount: 5 + 1 = 6
        assertThat(charged(tenant, FROM, TO, List.of(p1), 100).stream()
                .filter(row -> wc1.equals(row.chargeId()) || wc1.equals(row.originalChargeId()))
                .map(ChargedRow::quantity).reduce(Quantity.ZERO, Quantity::plus)).isEqualTo(m3(6));
    }

    @Test
    void onlyThePlotsAskedForAndTheChargesOverlappingTheRangeAreListed() {
        assertThat(charged(tenant, FROM, TO, List.of(p2), 100)).extracting(ChargedRow::chargeId)
                .containsExactly(wc2);
        assertThat(charged(tenant, FROM, TO, List.of(), 100)).isEmpty();
        assertThat(charged(tenant, LocalDate.parse("2025-10-05"), TO, List.of(p1, p2), 100))
                .extracting(ChargedRow::chargeId).containsExactly(ps1, wf1, r1, wc2);
        assertThat(charged(tenant, LocalDate.parse("2025-08-01"), LocalDate.parse("2025-08-31"), List.of(p1), 100))
                .isEmpty();
    }

    @Test
    void theLimitCutsTheListInItsOrderAlsoOverSeveralChunksOfPlots() {
        assertThat(charged(tenant, FROM, TO, List.of(p1, p2), 3)).extracting(ChargedRow::chargeId)
                .containsExactly(wc1, a1, ps1);
        // 1 200 plots asked (three queries): the merged rows keep the order of one query
        List<UUID> many = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            many.add(UUID.randomUUID());
        }
        many.set(1100, p1);
        many.set(10, p2);
        assertThat(charged(tenant, FROM, TO, many, 100)).extracting(ChargedRow::chargeId)
                .containsExactly(wc1, a1, ps1, wf1, r1, wc2);
        assertThat(charged(tenant, FROM, TO, many, 4)).extracting(ChargedRow::chargeId)
                .containsExactly(wc1, a1, ps1, wf1);
    }

    @Test
    void theChargesOfAnotherCooperativeAreNeverListed() {
        Side other = side(OTHER_TENANT);
        UUID otherWater = ownerJdbc().queryForObject(
                "select id from charge_type where tenant_id = ? and code = 'WATER'", UUID.class, OTHER_TENANT);
        otherCharge = UUID.randomUUID();
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, "
                + "source, amount, service_from, service_to, accrued_on, source_key) values (?, ?, ?, ?, ?, "
                + "'REGULAR', 'CONSUMPTION', 99.00, date '2025-10-01', date '2025-10-31', date '2025-10-31', ?)",
                otherCharge, OTHER_TENANT, other.plot(), otherWater, other.month(), "TEST:" + otherCharge);

        assertThat(charged(tenant, FROM, TO, List.of(p1, p2, other.plot()), 100)).extracting(ChargedRow::chargeId)
                .doesNotContain(otherCharge).hasSize(6);
        assertThat(charged(OTHER_TENANT, FROM, TO, List.of(p1, p2, other.plot()), 100))
                .extracting(ChargedRow::chargeId).containsExactly(otherCharge);
    }

    @Test
    void invalidArgumentsAreRefused() {
        assertThatThrownBy(() -> charged(tenant, TO, FROM, List.of(p1), 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> charged(tenant, FROM, TO, List.of(p1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itRunsOnlyInTheCallersTransaction() {
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> readModel.waterCharged(FROM, TO, List.of(p1), 10)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---- fixture

    private List<ChargedRow> charged(UUID cooperative, LocalDate from, LocalDate to, Collection<UUID> plots,
            int limit) {
        return TenantContext.runAs(cooperative, () -> tx.execute(status -> readModel.waterCharged(from, to, plots,
                limit)));
    }

    private String line(ChargedRow row) {
        return String.join(" ", name(row.chargeId()), row.kind().name(), row.source().name(),
                row.plotId().equals(p1) ? "P1" : "P2", row.chargeTypeId().equals(water) ? "water" : "common",
                row.serviceFrom() + ".." + row.serviceTo(), row.billingMonth().toString(),
                row.originalChargeId() == null ? "-" : name(row.originalChargeId()),
                row.quantity() == null ? "none" : row.quantity().toString(),
                row.rate() == null ? "mixed" : row.rate().toPlainString(), row.amount().toString());
    }

    private String name(UUID charge) {
        Map<UUID, String> names = new HashMap<>(Map.of(wc1, "WC1", a1, "A1", wf1, "WF1", r1, "R1", ps1, "PS1", ls1,
                "LS1", wc2, "WC2"));
        return names.getOrDefault(charge, charge.toString());
    }

    private static Quantity m3(long value) {
        return Quantity.ofFraction(BigInteger.valueOf(value), BigInteger.ONE);
    }

    /** The calculation a common consumers run stores with a share: the consumer and its split. */
    private static String share(UUID consumer, String split) {
        return "{\"consumerMeterId\": \"" + consumer + "\", \"split\": \"" + split + "\"}";
    }

    private UUID monthRow(String month) {
        owner().update("insert into billing_month (tenant_id, month) values (?, ?::date) "
                + "on conflict (tenant_id, month) do nothing", tenant, month + "-01");
        return owner().queryForObject("select id from billing_month where tenant_id = ? and month = ?::date",
                UUID.class, tenant, month + "-01");
    }

    private UUID meter(@Nullable UUID plot, String kind, String resource, @Nullable String name) {
        UUID id = UUID.randomUUID();
        owner().update("insert into meter (id, tenant_id, kind, resource, serial, name, plot_id, installed_on, "
                + "initial_value) values (?, ?, ?, ?, ?, ?, ?, date '2025-01-01', 0)", id, tenant, kind, resource,
                "WC-" + id.toString().substring(0, 8), name, plot);
        return id;
    }

    /** A regular charge written as the database owner, debt dated on its last service day. */
    private UUID charge(UUID plot, UUID type, String source, @Nullable UUID meter, @Nullable UUID run,
            @Nullable String calculation, String amount, String from, String to, String bookedIn, String at) {
        UUID id = UUID.randomUUID();
        owner().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key, meter_id, run_id, calculation, "
                + "created_at) values (?, ?, ?, ?, ?, 'REGULAR', ?, ?, ?::date, ?::date, ?::date, ?, ?, ?, ?::jsonb, "
                + "?::timestamptz)", id, tenant, plot, type, monthRow(bookedIn), source, new BigDecimal(amount), from,
                to, to, "TEST:" + id, meter, run, calculation, at);
        return id;
    }

    private void part(UUID charge, UUID version, int days, String num, String den, String rate) {
        owner().update("insert into charge_part (tenant_id, charge_id, tariff_version_id, days, units_num, "
                + "units_den, rate) values (?, ?, ?, ?, ?, ?, ?)", tenant, charge, version, days, new BigDecimal(num),
                new BigDecimal(den), new BigDecimal(rate));
    }

    /** An adjustment of the charge booked in November by a recalculation of its own, at the given time. */
    private UUID adjustment(UUID original, String amount, String at) {
        UUID recalculation = UUID.randomUUID();
        owner().update("insert into recalculation (id, tenant_id, cause, reason, total, line_count, plot_count, "
                + "digest) values (?, ?, 'MANUAL', 'Виправлено покази', ?, 1, 1, 'test')", recalculation, tenant,
                new BigDecimal(amount));
        UUID id = UUID.randomUUID();
        owner().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key, meter_id, adjusts_charge_id, "
                + "recalculation_id, reason, created_at) select ?, tenant_id, plot_id, charge_type_id, ?, "
                + "'ADJUSTMENT', 'RECALCULATION', ?, service_from, service_to, accrued_on, ?, meter_id, id, ?, "
                + "'Виправлено покази', ?::timestamptz from charge where id = ?", id, monthRow("2025-11"),
                new BigDecimal(amount), "ADJUSTMENT:" + recalculation + ":" + original, recalculation, at, original);
        return id;
    }
}
