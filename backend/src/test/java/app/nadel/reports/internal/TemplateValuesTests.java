package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.identity.Permission;
import app.nadel.money.Money;
import app.nadel.problem.ApiProblemException;
import app.nadel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import nadel.contract.model.ReportTemplateFieldType;
import nadel.contract.model.ReportTemplateValueReason;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The values of a template fill ({@link TemplateValues}, feature 013 §4.1) on the worked ledger of
 * {@link ReportsTestSupport#bookTheLedger} (January to March 2025) plus, for the period 01.01-31.03.2025:
 * <pre>
 * land tax   a yearly tariff of 35.00 per sotka from 01.01.2025 for every plot (P1 and P2, 600 m2 each: 210.00 a year
 *            each, 420.00 expected); P1 charged 17.50 for March (booked in March)
 * water      P2 connected to the cooperative's supply from 01.01.2025, meter WS-2 read 10 on 01.01 and 25 on 31.03
 *            (15 m3); a fixed fee of 25.00 for March
 * tree       ТП-1 1 000 -> 1 300; Квартал А under it, 500 on 01.01 and no reading after it (its end is not known);
 *            P1's meter S-P1 under the quarter, 100 -> 160
 * requisites official name, legal address, bank, IBAN in the settings and ЄДРПОУ 12345678 on the cooperative
 * </pre>
 * Charged with service days in the period, without opening balances: membership January 30.00 + 30.00, February
 * 30.00 + 30.00 - 30.00, March 25.00 + 25.00, the two adjustments -5.00 each = 130.00 (the opening balance of 1500.00 is
 * not a charge of the period); electricity 0.00 (its service starts on 16.12.2024); land tax 17.50; water 25.00. The
 * debts as of 31.03.2025: P1 1500.00 + 30.00 + 30.00 + 25.00 - 10.00 + 17.50 = 1592.50, P2 30.00 + 52.50 + 25.00 +
 * 25.00 = 132.50, 1725.00 in all.
 * <p>
 * Every total and row set is compared with the built-in report of the same period through the API, so a template and
 * the report never disagree.
 */
class TemplateValuesTests extends ReportsTestSupport {

    private static final LocalDate FROM = LocalDate.parse("2025-01-01");
    private static final LocalDate TO = LocalDate.parse("2025-03-31");
    private static final Instant NOW = Instant.parse("2026-10-24T09:15:30.250Z");

    @Autowired
    TemplateValues values;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ReportLimits limits;

    @Test
    void theCooperativeThePeriodAndTheFileAreSingleValues() {
        worked();

        Map<String, TemplateValue> single = singles(ReportLanguage.UK, "cooperative.name", "cooperative.officialName",
                "cooperative.edrpou", "cooperative.address", "cooperative.bankName", "cooperative.iban",
                "period.from", "period.to", "period.year", "generatedAt", "generatedBy");

        assertThat(single.get("cooperative.name")).isEqualTo(text(COOPERATIVE));
        assertThat(single.get("cooperative.officialName")).isEqualTo(text("Садівниче товариство «Звіти»"));
        assertThat(single.get("cooperative.edrpou")).isEqualTo(text("12345678"));
        assertThat(single.get("cooperative.address")).isEqualTo(text("Київська обл., с. Тестове, вул. Центральна, 1"));
        assertThat(single.get("cooperative.bankName")).isEqualTo(text("АТ «Тестбанк»"));
        assertThat(single.get("cooperative.iban")).isEqualTo(text("UA213223130000026007233566001"));
        assertThat(single.get("period.from")).isEqualTo(TemplateValue.of(ReportTemplateFieldType.DATE, FROM));
        assertThat(single.get("period.to")).isEqualTo(TemplateValue.of(ReportTemplateFieldType.DATE, TO));
        assertThat(single.get("period.year")).isEqualTo(TemplateValue.of(ReportTemplateFieldType.INT, 2025L));
        // Kyiv wall time, to the second
        assertThat(single.get("generatedAt")).isEqualTo(TemplateValue.of(ReportTemplateFieldType.DATE_TIME,
                LocalDateTime.parse("2026-10-24T12:15:30")));
        assertThat(single.get("generatedBy")).isEqualTo(text("Звітний CHAIRMAN"));
    }

    @Test
    void aRequisiteThatIsNotEnteredIsNotKnownAndAnUnknownKeyHasNoValue() {
        bookTheLedger();

        Map<String, TemplateValue> single = singles(ReportLanguage.UK, "cooperative.officialName",
                "cooperative.edrpou", "cooperative.address", "cooperative.bankName", "cooperative.iban");

        assertThat(single.values()).allSatisfy(value -> {
            assertThat(value.isUnknown()).isTrue();
            assertThat(value.reason()).isEqualTo(ReportTemplateValueReason.NOT_SET);
        });
        boolean checked = fill(ReportLanguage.UK, resolver -> {
            assertThat(resolver.single("nonsense.field")).isEmpty();
            assertThat(resolver.single("totals.charged.NO_SUCH_TYPE")).isEmpty();
            assertThat(resolver.placeholder("rows.unknownSet.number")).isEmpty();
            assertThatThrownBy(() -> resolver.single("rows.plots.number"))
                    .isInstanceOf(IllegalArgumentException.class);
            return true;
        });
        assertThat(checked).isTrue();
    }

    @Test
    void theTotalsAreTheTotalsOfTheBuiltInReportsForTheSamePeriod() throws Exception {
        worked();

        Map<String, TemplateValue> single = singles(ReportLanguage.UK, "totals.charged.MEMBERSHIP",
                "totals.charged.ELECTRICITY", "totals.charged.LAND_TAX", "totals.charged.WATER",
                "totals.charged." + custom, "totals.debt", "totals.plots.count", "totals.plots.areaSotka",
                "totals.landTax.expected", "totals.landTax.charged");

        assertThat(single.get("totals.charged.MEMBERSHIP")).isEqualTo(money("130.00"));
        assertThat(single.get("totals.charged.ELECTRICITY")).isEqualTo(money("0.00"));
        assertThat(single.get("totals.charged.LAND_TAX")).isEqualTo(money("17.50"));
        assertThat(single.get("totals.charged.WATER")).isEqualTo(money("25.00"));
        assertThat(single.get("totals.charged." + custom)).isEqualTo(money("0.00"));
        assertThat(single.get("totals.debt")).isEqualTo(money("1725.00"));
        assertThat(single.get("totals.plots.count")).isEqualTo(TemplateValue.of(ReportTemplateFieldType.INT, 2L));
        assertThat(((BigDecimal) single.get("totals.plots.areaSotka").value()).toPlainString()).isEqualTo("12.0000");
        assertThat(single.get("totals.landTax.expected")).isEqualTo(money("420.00"));
        assertThat(single.get("totals.landTax.charged")).isEqualTo(money("17.50"));

        // the same totals as the reports
        JsonNode balances = sheet(preview("/api/v1/reports/plot-balances?asOf=" + TO), 0);
        assertThat(single.get("totals.debt").value().toString()).isEqualTo(total(balances, "total"));
        assertThat(balances.get("rowCount").asInt()).isEqualTo(2);
        JsonNode landTax = sheet(preview("/api/v1/reports/land-tax?year=2025"), 0);
        assertThat(single.get("totals.landTax.expected").value().toString()).isEqualTo(total(landTax, "expected"));
        assertThat(single.get("totals.landTax.charged").value().toString()).isEqualTo(total(landTax, "charged"));
        // by service period, the regular charges, reversals and adjustments of the type (no opening balances)
        JsonNode byService = sheet(preview("/api/v1/reports/charges-by-month?fromMonth=2025-01&toMonth=2025-03"), 1);
        Money membershipByService = Money.ZERO;
        for (Map<String, String> row : rows(byService)) {
            if (membership.toString().equals(row.get("chargeType"))) {
                membershipByService = membershipByService.plus(Money.parse(row.get("effective")))
                        .minus(Money.parse(row.get("opening")));
            }
        }
        assertThat(single.get("totals.charged.MEMBERSHIP").value()).isEqualTo(membershipByService);
    }

    @Test
    void thePlotsAreThePlotBalancesAtTheEndOfThePeriodWithTheirLandTax() throws Exception {
        worked();

        List<Map<String, TemplateValue>> plots = rows(TemplateRowSet.PLOTS, ReportLanguage.UK);

        assertThat(plots).hasSize(2);
        Map<String, TemplateValue> first = plots.getFirst();
        assertThat(first.get("rows.plots.number")).isEqualTo(text("1"));
        assertThat(first.get("rows.plots.street")).isEqualTo(text("Лісова"));
        assertThat(((BigDecimal) first.get("rows.plots.area").value()).toPlainString()).isEqualTo("600.00");
        assertThat(((BigDecimal) first.get("rows.plots.areaSotka").value()).toPlainString()).isEqualTo("6.0000");
        assertThat(first.get("rows.plots.ownersCount")).isEqualTo(TemplateValue.of(ReportTemplateFieldType.INT, 0L));
        assertThat(first.get("rows.plots.debt.total")).isEqualTo(money("1592.50"));
        assertThat(first.get("rows.plots.debt.MEMBERSHIP")).isEqualTo(money("1575.00"));
        assertThat(first.get("rows.plots.debt." + membership)).isEqualTo(money("1575.00"));
        assertThat(first.get("rows.plots.debt.LAND_TAX")).isEqualTo(money("17.50"));
        assertThat(first.get("rows.plots.debt." + custom)).isEqualTo(money("0.00"));
        assertThat(((BigDecimal) first.get("rows.plots.landTax.rate").value()).toPlainString()).isEqualTo("35.0000");
        assertThat(first.get("rows.plots.landTax.expected")).isEqualTo(money("210.00"));
        assertThat(first.get("rows.plots.landTax.charged")).isEqualTo(money("17.50"));
        assertThat(plots.get(1).get("rows.plots.landTax.charged")).isEqualTo(money("0.00"));

        // row for row the plot balances report as of the end of the period, codes in the file's language
        JsonNode balances = sheet(preview("/api/v1/reports/plot-balances?asOf=" + TO), 0);
        ReportTexts uk = ReportTexts.of(ReportLanguage.UK);
        List<Map<String, String>> report = rows(balances);
        for (int index = 0; index < report.size(); index++) {
            Map<String, String> expected = report.get(index);
            Map<String, TemplateValue> actual = plots.get(index);
            assertThat(actual.get("rows.plots.number").value()).isEqualTo(expected.get("number"));
            assertThat(actual.get("rows.plots.debt.total").value().toString()).isEqualTo(expected.get("total"));
            assertThat(actual.get("rows.plots.debt.ELECTRICITY").value().toString())
                    .isEqualTo(expected.get("debt." + electricity));
            assertThat(String.valueOf(actual.get("rows.plots.oldestUnpaid").value()))
                    .isEqualTo(expected.get("oldestUnpaid"));
            assertThat(String.valueOf(actual.get("rows.plots.daysOverdue").value()))
                    .isEqualTo(expected.get("daysOverdue"));
            assertThat(actual.get("rows.plots.status").value())
                    .isEqualTo(uk.text("reports.filters.options.statuses." + expected.get("status")));
        }
        // and the land tax report's plots
        Map<String, String> landTaxP1 = rows(sheet(preview("/api/v1/reports/land-tax?year=2025"), 0)).getFirst();
        assertThat(first.get("rows.plots.landTax.rate").value().toString()).isEqualTo(landTaxP1.get("rate"));
        assertThat(first.get("rows.plots.landTax.expected").value().toString()).isEqualTo(landTaxP1.get("expected"));
        assertThat(first.get("rows.plots.landTax.charged").value().toString()).isEqualTo(landTaxP1.get("charged"));
    }

    @Test
    void aPlotThatTwoRatesChargedInTheYearHasNoSingleRateAndAPeriodEndingLaterIsAsOfToday() {
        worked();
        perSotkaYearly(landTaxTariff, "40.00", "2025-07-01");

        List<Map<String, TemplateValue>> plots = fill(ReportLanguage.EN, resolver -> resolver.rows(
                TemplateRowSet.PLOTS));
        assertThat(plots.getFirst().get("rows.plots.landTax.rate"))
                .isEqualTo(TemplateValue.unknown(ReportTemplateValueReason.SEVERAL_RATES));
        // half a year at 35.00 and half at 40.00 on 6 sotkas
        assertThat(plots.getFirst().get("rows.plots.landTax.expected").isPresent()).isTrue();

        LocalDate today = LocalDate.now(KYIV);
        LocalDate asOf = TenantContext.runAs(tenant, () -> tx.execute(status -> values.resolver(today.minusDays(3),
                today.plusDays(30), ReportLanguage.UK, "x", Instant.now(), Set.of(Permission.REPORTS_VIEW)).asOf()));
        assertThat(asOf).isEqualTo(today);
    }

    @Test
    void theMonthsAreTheBillingMonthsOfTheChargesByMonthReport() throws Exception {
        worked();

        List<Map<String, TemplateValue>> months = rows(TemplateRowSet.MONTHS, ReportLanguage.EN);

        assertThat(months).extracting(row -> row.get("rows.months.month").value())
                .containsExactly(YearMonth.of(2025, 1), YearMonth.of(2025, 2), YearMonth.of(2025, 3));
        assertThat(months.getFirst().get("rows.months.status")).isEqualTo(text("Closed"));
        assertThat(months.getFirst().get("rows.months.opening")).isEqualTo(money("1500.00"));

        List<Map<String, String>> subtotals = new ArrayList<>();
        for (Map<String, String> row : rows(sheet(preview(
                "/api/v1/reports/charges-by-month?fromMonth=2025-01&toMonth=2025-03"), 0))) {
            if ("SUBTOTAL".equals(row.get("@kind"))) {
                subtotals.add(row);
            }
        }
        assertThat(subtotals).hasSize(3);
        ReportTexts en = ReportTexts.of(ReportLanguage.EN);
        for (int index = 0; index < 3; index++) {
            Map<String, String> expected = subtotals.get(index);
            Map<String, TemplateValue> actual = months.get(index);
            assertThat(actual.get("rows.months.month").value().toString()).isEqualTo(expected.get("month"));
            assertThat(actual.get("rows.months.status").value())
                    .isEqualTo(en.text("billing.status." + expected.get("status")));
            for (String amount : List.of("regular", "opening", "reversals", "adjustments", "effective")) {
                assertThat(actual.get("rows.months." + amount).value().toString()).as(amount)
                        .isEqualTo(expected.get(amount));
            }
        }
    }

    @Test
    void theMetersAreTheElectricityTreeAndAValueNotKnownStaysNotKnown() throws Exception {
        worked();

        List<Map<String, TemplateValue>> meters = rows(TemplateRowSet.METERS, ReportLanguage.UK);

        assertThat(meters).extracting(row -> row.get("rows.meters.name").value()).containsExactly("ТП-1",
                "Квартал А", "1");
        Map<String, TemplateValue> quarter = meters.get(1);
        assertThat(quarter.get("rows.meters.level")).isEqualTo(TemplateValue.of(ReportTemplateFieldType.INT, 1L));
        assertThat(quarter.get("rows.meters.kind")).isEqualTo(text(ReportTexts.of(ReportLanguage.UK)
                .text("reports.filters.options.meterKinds.QUARTER")));
        assertThat(quarter.get("rows.meters.startValue")).isEqualTo(TemplateValue.of(ReportTemplateFieldType.QUANTITY,
                new BigDecimal("500.000")));
        // never 0: no reading after 01.01, so the end and everything worked out from it are not known
        assertThat(quarter.get("rows.meters.endValue"))
                .isEqualTo(TemplateValue.unknown(ReportTemplateValueReason.NO_READING));
        assertThat(quarter.get("rows.meters.consumption"))
                .isEqualTo(TemplateValue.unknown(ReportTemplateValueReason.DERIVED_FROM_UNKNOWN));
        assertThat(quarter.get("rows.meters.lossesKwh"))
                .isEqualTo(TemplateValue.unknown(ReportTemplateValueReason.DERIVED_FROM_UNKNOWN));
        assertThat(meters.get(2).get("rows.meters.consumption"))
                .isEqualTo(TemplateValue.of(ReportTemplateFieldType.QUANTITY, new BigDecimal("60.000")));
        assertThat(meters.get(2).get("rows.meters.childrenConsumption")).isEqualTo(TemplateValue.EMPTY);

        List<Map<String, String>> report = rows(sheet(preview("/api/v1/reports/electricity-tree?from=" + FROM
                + "&to=" + TO), 0));
        assertThat(report).hasSize(meters.size());
        Map<String, String> columns = Map.of("rows.meters.serial", "serial", "rows.meters.startValue", "start",
                "rows.meters.endValue", "end", "rows.meters.consumption", "consumption",
                "rows.meters.childrenConsumption", "children", "rows.meters.lossesKwh", "losses",
                "rows.meters.lossesPercent", "lossesPercent", "rows.meters.plotsFed", "plotsFed");
        for (int index = 0; index < report.size(); index++) {
            for (Map.Entry<String, String> column : columns.entrySet()) {
                assertThat(json(meters.get(index).get(column.getKey()))).as(index + " " + column.getKey())
                        .isEqualTo(report.get(index).get(column.getValue()));
            }
        }
    }

    @Test
    void theWaterRowsAreTheWaterReportsPlots() throws Exception {
        worked();

        List<Map<String, TemplateValue>> water = rows(TemplateRowSet.WATER, ReportLanguage.UK);

        assertThat(water).hasSize(1);
        Map<String, TemplateValue> p2 = water.getFirst();
        assertThat(p2.get("rows.water.number")).isEqualTo(text("2"));
        assertThat(p2.get("rows.water.source")).isEqualTo(text(ReportTexts.of(ReportLanguage.UK)
                .text("reports.filters.options.waterSource.COOPERATIVE_SUPPLY")));
        assertThat(p2.get("rows.water.connectedDays")).isEqualTo(TemplateValue.of(ReportTemplateFieldType.INT, 90L));
        assertThat(p2.get("rows.water.serial")).isEqualTo(text("WS-2"));
        assertThat(p2.get("rows.water.cubicMetres")).isEqualTo(TemplateValue.of(ReportTemplateFieldType.QUANTITY,
                new BigDecimal("15.000")));
        assertThat(p2.get("rows.water.total")).isEqualTo(money("25.00"));

        Map<String, String> report = rows(sheet(preview("/api/v1/reports/water?from=" + FROM + "&to=" + TO), 0))
                .getFirst();
        for (String column : List.of("number", "street", "connectedDays", "serial", "cubicMetres", "total")) {
            assertThat(json(p2.get("rows.water." + column))).as(column).isEqualTo(report.get(column));
        }
    }

    /**
     * Feature 015 §6: P1 pays 40.00 in cash on 10.02.2025 (25.00 on its January membership, the charge after its
     * -5.00 adjustment; 15.00 left), P2 pays 20.00 in cash on 15.03.2025 that is reversed, and 52.50 by bank on
     * 05.04.2025, after the period, on its electricity. Paid on the charges of the period as of its end: membership
     * 25.00, electricity 0.00 (its charge starts in December and its payment came later); the prepayment at the end of
     * the period: P1's 15.00. The payments rows are those of the payments report for the days of the period.
     */
    @Test
    void thePaymentFieldsAreTheLedgersAsOfTheEndOfThePeriodAndThePaymentsReportsRows() throws Exception {
        worked();
        UUID januaryOfP1 = ownerJdbc().queryForObject("select id from charge where plot_id = ? and kind = 'REGULAR' "
                + "and service_from = date '2025-01-01'", UUID.class, p1);
        allocate(payment(p1, "CASH", "40.00", LocalDate.of(2025, 2, 10), 5L, null, null), januaryOfP1, "25.00");
        reversePayment(payment(p2, "CASH", "20.00", LocalDate.of(2025, 3, 15), 6L, null, null));
        UUID electricityOfP2 = ownerJdbc().queryForObject("select id from charge where plot_id = ? "
                + "and charge_type_id = ?", UUID.class, p2, electricity);
        allocate(payment(p2, "BANK", "52.50", LocalDate.of(2025, 4, 5), null, "ПД-3", null), electricityOfP2,
                "52.50");

        Map<String, TemplateValue> single = singles(ReportLanguage.UK, "totals.paid.MEMBERSHIP",
                "totals.paid.ELECTRICITY", "totals.paid." + custom, "totals.prepayment");
        assertThat(single.get("totals.paid.MEMBERSHIP")).isEqualTo(money("25.00"));
        assertThat(single.get("totals.paid.ELECTRICITY")).isEqualTo(money("0.00"));
        assertThat(single.get("totals.paid." + custom)).isEqualTo(money("0.00"));
        assertThat(single.get("totals.prepayment")).isEqualTo(money("15.00"));

        List<Map<String, TemplateValue>> payments = rows(TemplateRowSet.PAYMENTS, ReportLanguage.UK);
        assertThat(payments).hasSize(2);
        Map<String, TemplateValue> cash = payments.getFirst();
        assertThat(cash.keySet()).noneMatch(key -> key.contains("payer") || key.contains("note"));
        assertThat(cash.get("rows.payments.source")).isEqualTo(text("Готівка"));
        assertThat(cash.get("rows.payments.document")).isEqualTo(text("5"));
        assertThat(cash.get("rows.payments.reversed")).isEqualTo(text("Ні"));
        assertThat(payments.getLast().get("rows.payments.reversed")).isEqualTo(text("Так"));
        assertThat(rows(TemplateRowSet.PAYMENTS, ReportLanguage.EN).getLast().get("rows.payments.reversed"))
                .isEqualTo(text("Yes"));

        // the same values as the payments report for the days of the period
        List<Map<String, String>> report = rows(sheet(preview("/api/v1/reports/payments?from=" + FROM + "&to=" + TO),
                0));
        assertThat(report).hasSize(2);
        for (int index = 0; index < 2; index++) {
            for (String column : List.of("receivedOn", "billingMonth", "document", "number", "street", "amount",
                    "allocated", "prepayment")) {
                assertThat(json(payments.get(index).get("rows.payments." + column))).as(column)
                        .isEqualTo(report.get(index).get(column));
            }
        }
    }

    @Test
    void aPaymentFieldIsRefusedWithoutPaymentsViewAndTheOtherFieldsAreNot() {
        worked();

        boolean checked = TenantContext.runAs(tenant, () -> tx.execute(status -> {
            TemplateValues.Resolver resolver = values.resolver(FROM, TO, ReportLanguage.UK, "Звітний", NOW,
                    Set.of(Permission.REPORTS_VIEW, Permission.MEMBERS_VIEW));
            assertThat(resolver.single("totals.debt")).isPresent();
            assertThat(resolver.rows(TemplateRowSet.PLOTS)).hasSize(2);
            assertThatThrownBy(() -> resolver.single("totals.paid.MEMBERSHIP"))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> resolver.single("totals.prepayment")).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> resolver.rows(TemplateRowSet.PAYMENTS)).isInstanceOf(AccessDeniedException.class);
            // nothing at all without the base permission
            TemplateValues.Resolver none = values.resolver(FROM, TO, ReportLanguage.UK, "Звітний", NOW,
                    Set.of(Permission.PAYMENTS_VIEW));
            assertThatThrownBy(() -> none.single("totals.debt")).isInstanceOf(AccessDeniedException.class);
            return true;
        }));
        assertThat(checked).isTrue();
    }

    @Test
    void aRowSetOverTheRowCapIsTooLarge() {
        worked();
        limits.rowCap(1);
        try {
            assertThatThrownBy(() -> rows(TemplateRowSet.PLOTS, ReportLanguage.UK))
                    .isInstanceOfSatisfying(ApiProblemException.class,
                            e -> assertThat(e.code()).isEqualTo("REPORT_TOO_LARGE"));
            assertThat(rows(TemplateRowSet.WATER, ReportLanguage.UK)).hasSize(1);
        } finally {
            limits.rowCap(ReportLimits.DEFAULT_ROW_CAP);
        }
    }

    // ---- fixture

    private UUID custom;
    private UUID landTaxTariff;

    private void worked() {
        bookTheLedger();
        UUID landTax = typeId("LAND_TAX");
        landTaxTariff = tariff(landTax, "Земельний податок");
        perSotkaYearly(landTaxTariff, "35.00", "2025-01-01");
        charge(p1, landTax, "REGULAR", "TARIFF", "17.50", "2025-03-01", "2025-03-31", "2025-03");

        UUID water = typeId("WATER");
        connectWater(p2, null, "2025-01-01", null);
        UUID waterMeter = meter("PLOT", "WATER", p2, null, "WS-2", "2025-01-01", "0");
        reading(waterMeter, "2025-01-01", "10", "APPROVED");
        reading(waterMeter, "2025-03-31", "25", "APPROVED");
        charge(p2, water, "REGULAR", "TARIFF", "25.00", "2025-03-01", "2025-03-31", "2025-03");

        UUID substation = meter("SUBSTATION", "ELECTRICITY", null, "ТП-1", "S-TP1", "2025-01-01", "0");
        reading(substation, "2025-01-01", "1000", "APPROVED");
        reading(substation, "2025-03-31", "1300", "APPROVED");
        UUID quarter = meter("QUARTER", "ELECTRICITY", null, "Квартал А", "S-QA", "2025-01-01", "0");
        parent(quarter, substation);
        reading(quarter, "2025-01-01", "500", "APPROVED");
        UUID plotMeter = meter("PLOT", "ELECTRICITY", p1, null, "S-P1", "2025-01-01", "0");
        parent(plotMeter, quarter);
        reading(plotMeter, "2025-01-01", "100", "APPROVED");
        reading(plotMeter, "2025-03-31", "160", "APPROVED");

        custom = UUID.randomUUID();
        ownerJdbc().update("insert into charge_type (id, tenant_id, name, name_customized, position) "
                + "values (?, ?, 'Охорона', true, 9)", custom, tenant);
        ownerJdbc().update("insert into cooperative_settings (tenant_id, official_name, legal_address, bank_name, "
                + "iban) values (?, ?, ?, ?, ?)", tenant, "Садівниче товариство «Звіти»",
                "Київська обл., с. Тестове, вул. Центральна, 1", "АТ «Тестбанк»", "UA213223130000026007233566001");
        ownerJdbc().update("update tenant set edrpou = '12345678' where id = ?", tenant);
    }

    private void parent(UUID meter, UUID parent) {
        ownerJdbc().update("insert into meter_parent (tenant_id, meter_id, parent_meter_id, valid_from) "
                + "values (?, ?, ?, date '2025-01-01')", tenant, meter, parent);
    }

    // ---- the fill

    /** What a test reads from the resolver, inside one transaction of the cooperative. */
    private <T> T fill(ReportLanguage language, Function<TemplateValues.Resolver, T> read) {
        return TenantContext.runAs(tenant, () -> tx.execute(status -> read.apply(values.resolver(FROM, TO, language,
                "Звітний CHAIRMAN", NOW, Set.of(Permission.REPORTS_VIEW, Permission.PAYMENTS_VIEW)))));
    }

    private Map<String, TemplateValue> singles(ReportLanguage language, String... keys) {
        return fill(language, resolver -> {
            Map<String, TemplateValue> result = new LinkedHashMap<>();
            for (String key : keys) {
                result.put(key, resolver.single(key).orElseThrow(() -> new AssertionError("unknown " + key)));
            }
            return result;
        });
    }

    private List<Map<String, TemplateValue>> rows(TemplateRowSet set, ReportLanguage language) {
        return fill(language, resolver -> resolver.rows(set));
    }

    private static TemplateValue text(String value) {
        return TemplateValue.of(ReportTemplateFieldType.TEXT, value);
    }

    private static TemplateValue money(String value) {
        return TemplateValue.of(ReportTemplateFieldType.MONEY, Money.parse(value));
    }

    /** A value as the preview writes a cell: the value as a string, absent when empty or not known. */
    private static @Nullable String json(TemplateValue value) {
        if (value.isUnknown()) {
            return "?" + value.reason().getValue();
        }
        if (!value.isPresent()) {
            return null;
        }
        return value.value() instanceof BigDecimal number ? number.toPlainString() : value.value().toString();
    }

    // ---- the reports

    private JsonNode preview(String path) throws Exception {
        return body(send(chairman, get(path)).andExpect(status().isOk()));
    }

    private static JsonNode sheet(JsonNode preview, int index) {
        return preview.get("sheets").get(index);
    }

    /** The rows of a preview sheet by column key; a value not known is {@code ?<reason>}, the kind is {@code @kind}. */
    private static List<Map<String, String>> rows(JsonNode sheet) {
        List<String> keys = new ArrayList<>();
        sheet.get("columns").forEach(column -> keys.add(column.get("key").asString()));
        List<Map<String, String>> rows = new ArrayList<>();
        sheet.get("rows").forEach(row -> {
            Map<String, String> values = new HashMap<>();
            values.put("@kind", row.get("kind").asString());
            for (int index = 0; index < keys.size(); index++) {
                JsonNode cell = row.get("cells").get(index);
                values.put(keys.get(index), cell.has("value") && !cell.get("value").isNull()
                        ? cell.get("value").asString()
                        : cell.has("reason") && !cell.get("reason").isNull() ? "?" + cell.get("reason").asString()
                        : null);
            }
            rows.add(values);
        });
        return rows;
    }

    private static String total(JsonNode sheet, String column) {
        List<String> keys = new ArrayList<>();
        sheet.get("columns").forEach(node -> keys.add(node.get("key").asString()));
        return sheet.get("totals").get(0).get("cells").get(keys.indexOf(column)).get("value").asString();
    }
}
