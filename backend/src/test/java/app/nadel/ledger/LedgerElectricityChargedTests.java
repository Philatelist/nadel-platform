package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.meters.Quantity;
import app.nadel.money.Money;
import app.nadel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The electricity charges of a range of days ({@link LedgerReadModel#electricityCharged}, the "Charged" sheet of
 * feature 013 §3.4) on a fixed ledger of a cooperative of its own, for October 2025 (October closed, November open):
 * <pre>
 * C1  P1 consumption 23.09-04.10, 65 kWh at 4.3200 = 280.80 (October)
 *     A1 adjustment in November by a recalculation: 70 kWh = 302.40, so +21.60 (+5 kWh)
 *     A2 adjustment in November by a later one: 66 kWh = 285.12, so -17.28 (-4 kWh)
 * L1  P1 fixed-percentage losses of the same interval, 5 % = 3.25 kWh at 4.3200 = 14.04
 * CC1 P1 common consumer run share 01.10-31.10, 12.5 kWh at 4.3200 = 54.00 (the common consumers type)
 * C2  P2 consumption 05.10-31.10, 7 kWh at 4.3250 + 1 kWh at 4.3350 = 34.61, booked in November
 *     R2 reversed by hand in November, -34.61
 * P3  consumption 01.09-30.09 and 01.11-20.11 (outside the range); P1 water, membership and an opening balance in
 *     October (not electricity)
 * </pre>
 * P1's rows add up to 353.16, its electricity and common consumer debts on the account; P2's to 0.00.
 */
class LedgerElectricityChargedTests extends FreshCooperativeSupport {

    private static final LocalDate FROM = LocalDate.parse("2025-10-01");
    private static final LocalDate TO = LocalDate.parse("2025-10-31");

    @Autowired
    LedgerReadModel readModel;

    @Autowired
    TransactionTemplate tx;

    private UUID p1;
    private UUID p2;
    private UUID p3;
    private UUID electricity;
    private UUID commonConsumers;
    private UUID c1;
    private UUID a1;
    private UUID a2;
    private UUID l1;
    private UUID cc1;
    private UUID c2;
    private UUID r2;
    private UUID commonRun;

    private UUID otherCharge;

    @BeforeEach
    void bookTheLedger() {
        p1 = plot("1", "600");
        p2 = plot("2", "600");
        p3 = plot("3", "600");
        electricity = typeId("ELECTRICITY");
        commonConsumers = typeId("COMMON_CONSUMERS");
        Tariff power = tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200", "2025-01-01");
        UUID v4325 = version(power.id(), "PER_KWH", "MONTHLY", "4.3250", "2025-10-05", null);
        UUID v4335 = version(power.id(), "PER_KWH", "MONTHLY", "4.3350", "2025-10-20", null);
        Tariff common = tariff("COMMON_CONSUMERS", "Спільні споживачі", "PER_KWH", "MONTHLY", "4.3200", "2025-01-01");
        UUID m1 = meter(p1);
        UUID m2 = meter(p2);
        UUID m3 = meter(p3);

        c1 = charge(p1, electricity, "CONSUMPTION", m1, null, "280.80", "2025-09-23", "2025-10-04", "2025-10",
                "2025-10-05 10:00:00+03");
        part(c1, power.version(), 12, "65", "1", "4.3200");
        l1 = charge(p1, electricity, "LOSSES", m1, null, "14.04", "2025-09-23", "2025-10-04", "2025-10",
                "2025-10-05 10:00:00+03");
        part(l1, power.version(), 12, "13", "4", "4.3200");
        commonRun = UUID.randomUUID();
        owner().update("insert into common_consumer_run (id, tenant_id, from_date, to_date, billing_month_id, total) "
                + "values (?, ?, date '2025-10-01', date '2025-10-31', ?, 54.00)", commonRun, tenant,
                monthRow("2025-10"));
        cc1 = charge(p1, commonConsumers, "COMMON_CONSUMER", null, commonRun, "54.00", "2025-10-01", "2025-10-31",
                "2025-10", "2025-10-31 18:00:00+02");
        part(cc1, common.version(), 31, "25", "2", "4.3200");
        c2 = charge(p2, electricity, "CONSUMPTION", m2, null, "34.61", "2025-10-05", "2025-10-31", "2025-11",
                "2025-11-01 09:00:00+02");
        part(c2, v4325, 15, "7", "1", "4.3250");
        part(c2, v4335, 12, "1", "1", "4.3350");
        closeMonth("2025-10");
        a1 = adjustment(c1, "21.60", "2025-11-03 10:00:00+02");
        part(a1, power.version(), 12, "70", "1", "4.3200");
        a2 = adjustment(c1, "-17.28", "2025-11-10 10:00:00+02");
        part(a2, power.version(), 12, "66", "1", "4.3200");
        r2 = UUID.randomUUID();
        owner().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, reverses_charge_id, meter_id, reason, created_at) "
                + "select ?, tenant_id, plot_id, charge_type_id, billing_month_id, 'REVERSAL', 'MANUAL_REVERSAL', "
                + "-amount, service_from, service_to, accrued_on, id, meter_id, 'Помилкові покази', "
                + "timestamptz '2025-11-02 09:00:00+02' from charge where id = ?", r2, c2);

        // outside the range or not electricity
        charge(p3, electricity, "CONSUMPTION", m3, null, "40.00", "2025-09-01", "2025-09-30", "2025-10",
                "2025-10-01 09:00:00+03");
        charge(p3, electricity, "CONSUMPTION", m3, null, "41.00", "2025-11-01", "2025-11-20", "2025-11",
                "2025-11-21 09:00:00+02");
        charge(p1, typeId("WATER"), "CONSUMPTION", null, null, "60.00", "2025-10-01", "2025-10-31", "2025-11",
                "2025-11-01 09:00:00+02");
        charge(p1, typeId("MEMBERSHIP"), "TARIFF", null, null, "100.00", "2025-10-01", "2025-10-31", "2025-11",
                "2025-11-01 09:00:00+02");
        owner().update("insert into charge (tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on) values (?, ?, ?, ?, 'OPENING', 'OPENING', 500.00, "
                + "date '2025-10-10', date '2025-10-10', date '2025-10-10')", tenant, p1, electricity,
                monthRow("2025-11"));
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
    void everyChargeReversalAndAdjustmentIsItsOwnRowWithTheOriginalServicePeriod() {
        List<ChargedRow> rows = charged(tenant, FROM, TO, 100);

        assertThat(rows).extracting(this::line).containsExactly(
                "C1 REGULAR CONSUMPTION P1 electricity 2025-09-23..2025-10-04 2025-10 - 65 4.3200 280.80",
                "A1 ADJUSTMENT CONSUMPTION P1 electricity 2025-09-23..2025-10-04 2025-11 C1 5 4.3200 21.60",
                "A2 ADJUSTMENT CONSUMPTION P1 electricity 2025-09-23..2025-10-04 2025-11 C1 -4 4.3200 -17.28",
                "L1 REGULAR LOSSES P1 electricity 2025-09-23..2025-10-04 2025-10 - 13/4 4.3200 14.04",
                "CC1 REGULAR COMMON_CONSUMER P1 common 2025-10-01..2025-10-31 2025-10 - 25/2 4.3200 54.00",
                "C2 REGULAR CONSUMPTION P2 electricity 2025-10-05..2025-10-31 2025-11 - 8 mixed 34.61",
                "R2 REVERSAL CONSUMPTION P2 electricity 2025-10-05..2025-10-31 2025-11 C2 -8 mixed -34.61");
        ChargedRow mixed = rows.get(5);
        assertThat(mixed.parts()).extracting(part -> part.quantity() + "@" + part.rate().toPlainString())
                .containsExactly("7@4.3250", "1@4.3350");
        assertThat(rows.get(6).parts()).extracting(part -> part.quantity() + "@" + part.rate().toPlainString())
                .containsExactly("-7@4.3250", "-1@4.3350");
        assertThat(rows.get(4).runId()).isEqualTo(commonRun);
        assertThat(rows.get(0).meterId()).isNotNull();
        assertThat(rows.get(4).chargeTypeId()).isEqualTo(commonConsumers);
    }

    @Test
    void theRowsOfAPlotAddUpToItsElectricityChargesOnTheAccount() throws Exception {
        List<ChargedRow> rows = charged(tenant, FROM, TO, 100);
        Map<UUID, Money> byPlot = rows.stream().collect(Collectors.groupingBy(ChargedRow::plotId,
                Collectors.reducing(Money.ZERO, ChargedRow::amount, Money::plus)));

        assertThat(byPlot).containsOnlyKeys(p1, p2);
        assertThat(byPlot.get(p1)).hasToString("353.16");
        assertThat(byPlot.get(p2)).hasToString("0.00");
        // P1's account: electricity 280.80 + 21.60 - 17.28 + 14.04 (+ the opening balance, not a charge of the range)
        JsonNode account = body(send(chairman, get(ACCOUNT.formatted(p1)), null).andExpect(status().isOk()));
        assertThat(debt(account, "ELECTRICITY")).isEqualTo("799.16");
        assertThat(debt(account, "COMMON_CONSUMERS")).isEqualTo("54.00");
        assertThat(new BigDecimal("799.16").subtract(new BigDecimal("500.00")).add(new BigDecimal("54.00")))
                .isEqualByComparingTo(byPlot.get(p1).value());
        // the kWh of a charge add up like its amount: 65 + 5 - 4 = 66, P2 8 - 8 = 0
        assertThat(rows.stream().filter(row -> row.plotId().equals(p2)).map(ChargedRow::quantity)
                .reduce(Quantity.ZERO, Quantity::plus)).isEqualTo(Quantity.ZERO);
        assertThat(rows.stream().filter(row -> c1.equals(row.chargeId()) || c1.equals(row.originalChargeId()))
                .map(ChargedRow::quantity).reduce(Quantity.ZERO, Quantity::plus)).isEqualTo(kwh(66));
    }

    @Test
    void aChargeIsListedWhenItsServicePeriodOverlapsTheRange() {
        assertThat(charged(tenant, LocalDate.parse("2025-10-05"), TO, 100)).extracting(ChargedRow::chargeId)
                .containsExactly(cc1, c2, r2);
        assertThat(charged(tenant, LocalDate.parse("2025-10-04"), LocalDate.parse("2025-10-04"), 100))
                .extracting(ChargedRow::chargeId).containsExactly(c1, a1, a2, l1, cc1);
        assertThat(charged(tenant, LocalDate.parse("2025-08-01"), LocalDate.parse("2025-08-31"), 100)).isEmpty();
    }

    @Test
    void theLimitCutsTheListInItsOrder() {
        assertThat(charged(tenant, FROM, TO, 3)).extracting(ChargedRow::chargeId).containsExactly(c1, a1, a2);
    }

    @Test
    void theChargesOfAnotherCooperativeAreNeverListed() {
        Side other = side(OTHER_TENANT);
        otherCharge = UUID.randomUUID();
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, "
                + "source, amount, service_from, service_to, accrued_on, source_key, meter_id) values (?, ?, ?, ?, ?, "
                + "'REGULAR', 'CONSUMPTION', 99.00, date '2025-10-01', date '2025-10-31', date '2025-10-31', ?, ?)",
                otherCharge, OTHER_TENANT, other.plot(), other.chargeType(), other.month(), "TEST:" + otherCharge,
                other.meter());

        assertThat(charged(tenant, FROM, TO, 100)).extracting(ChargedRow::chargeId).doesNotContain(otherCharge)
                .hasSize(7);
        assertThat(charged(OTHER_TENANT, FROM, TO, 100)).extracting(ChargedRow::chargeId).contains(otherCharge)
                .doesNotContain(c1, cc1, c2);
    }

    @Test
    void invalidArgumentsAreRefused() {
        assertThatThrownBy(() -> charged(tenant, TO, FROM, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> charged(tenant, FROM, TO, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itRunsOnlyInTheCallersTransaction() {
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> readModel.electricityCharged(FROM, TO, 10)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---- fixture

    private List<ChargedRow> charged(UUID cooperative, LocalDate from, LocalDate to, int limit) {
        return TenantContext.runAs(cooperative, () -> tx.execute(status -> readModel.electricityCharged(from, to,
                limit)));
    }

    private String line(ChargedRow row) {
        return String.join(" ", name(row.chargeId()), row.kind().name(), row.source().name(),
                row.plotId().equals(p1) ? "P1" : row.plotId().equals(p2) ? "P2" : "P3",
                row.chargeTypeId().equals(electricity) ? "electricity" : "common",
                row.serviceFrom() + ".." + row.serviceTo(), row.billingMonth().toString(),
                row.originalChargeId() == null ? "-" : name(row.originalChargeId()), row.quantity().toString(),
                row.rate() == null ? "mixed" : row.rate().toPlainString(), row.amount().toString());
    }

    private String name(UUID charge) {
        Map<UUID, String> names = Map.of(c1, "C1", a1, "A1", a2, "A2", l1, "L1", cc1, "CC1", c2, "C2", r2, "R2");
        return names.getOrDefault(charge, charge.toString());
    }

    private static Quantity kwh(long value) {
        return Quantity.ofFraction(BigInteger.valueOf(value), BigInteger.ONE);
    }

    private static String debt(JsonNode account, String code) {
        for (JsonNode debt : account.get("debts")) {
            if (code.equals(debt.get("chargeTypeCode").asString())) {
                return debt.get("debt").asString();
            }
        }
        return null;
    }

    private UUID monthRow(String month) {
        owner().update("insert into billing_month (tenant_id, month) values (?, ?::date) "
                + "on conflict (tenant_id, month) do nothing", tenant, month + "-01");
        return owner().queryForObject("select id from billing_month where tenant_id = ? and month = ?::date",
                UUID.class, tenant, month + "-01");
    }

    private UUID meter(UUID plot) {
        UUID id = UUID.randomUUID();
        owner().update("insert into meter (id, tenant_id, kind, resource, serial, plot_id, installed_on, "
                + "initial_value) values (?, ?, 'PLOT', 'ELECTRICITY', ?, ?, date '2025-01-01', 0)", id, tenant,
                "EC-" + id.toString().substring(0, 8), plot);
        return id;
    }

    /** A regular charge written as the database owner, debt dated on its last service day. */
    private UUID charge(UUID plot, UUID type, String source, UUID meter, UUID run, String amount, String from,
            String to, String bookedIn, String at) {
        UUID id = UUID.randomUUID();
        owner().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key, meter_id, run_id, created_at) values (?, "
                + "?, ?, ?, ?, 'REGULAR', ?, ?, ?::date, ?::date, ?::date, ?, ?, ?, ?::timestamptz)", id, tenant, plot,
                type, monthRow(bookedIn), source, new BigDecimal(amount), from, to, to, "TEST:" + id, meter, run, at);
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
