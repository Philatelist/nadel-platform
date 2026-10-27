package app.nadel.tariffs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
import app.nadel.money.Money;
import app.nadel.money.Rate;
import app.nadel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The land tax a plot is expected to be charged over a year, per tariff version ({@link ExpectedAmounts#yearByVersion},
 * feature 013 §3.7), in a cooperative of its own with worked numbers for 2025 (365 days):
 * <ul>
 * <li>35.0000 per sotka per year, 600 m2: 6 sotkas, 12 x 17.50 = 210.00 over 365 days;</li>
 * <li>40.0000 from 16.07.2025: July is 17.50 x 15/31 + 20.00 x 16/31 = 18.79, of which the 35.00 version takes
 * 17.50 x 15/31 = 8.47 and the 40.00 version the rest, 10.32; so 35.00 for 196 days (6 x 17.50 + 8.47 = 113.47) and
 * 40.00 for 169 days (10.32 + 5 x 20.00 = 110.32), together 223.79 over 365 days;</li>
 * <li>a first version from 10.03.2025: March 17.50 x 22/31 = 12.42, then 9 x 17.50, 169.92 over 297 days.</li>
 * </ul>
 */
class ExpectedAmountsTests extends IntegrationTest {

    @Autowired
    ExpectedAmounts expectedAmounts;

    @Autowired
    TransactionTemplate tx;

    private UUID tenant;
    private UUID landTax;

    @BeforeEach
    void createCooperative() {
        tenant = UUID.randomUUID();
        ownerJdbc().update("insert into tenant (id, name) values (?, 'Land tax test cooperative')", tenant);
        ownerJdbc().queryForList("select seed_base_roles(?)", tenant);
        landTax = ownerJdbc().queryForObject("select id from charge_type where tenant_id = ? and code = 'LAND_TAX'",
                UUID.class, tenant);
    }

    @AfterEach
    void removeCooperative() {
        for (String table : List.of("audit_entry", "tariff_scope_plot", "tariff_version", "tariff", "plot_status",
                "plot", "member_role", "role_permission", "role", "member", "charge_type", "cooperative_settings")) {
            ownerJdbc().update("delete from " + table + " where tenant_id = ?", tenant);
        }
        ownerJdbc().update("delete from tenant where id = ?", tenant);
    }

    @Test
    void thirtyFivePerSotkaOnSixHundredSquareMetresIsSixSotkasAndTwoHundredTenAYear() {
        UUID plot = plot("1", "600.00", "PLOT", false);
        UUID tariff = tariff("Земельний податок");
        UUID version = version(tariff, "35", "2024-01-01");

        YearExpectation expected = year(tenant, 2025, List.of(plot)).get(plot);

        assertThat(expected.areaM2()).isEqualByComparingTo("600");
        assertThat(expected.areaSotka()).isEqualByComparingTo("6");
        assertThat(expected.total()).isEqualTo(Money.parse("210.00"));
        assertThat(expected.shares()).containsExactly(new VersionShare(tariff, version, Basis.PER_AREA_SOTKA,
                Frequency.YEARLY, Rate.parse("35.0000"), LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), 365,
                Money.parse("210.00")));
    }

    @Test
    void twoRatesInTheYearAreOneSharePerVersionWithItsDaysAndTheMonthOfTheChangeIsSplitOnce() {
        UUID plot = plot("1", "600.00", "PLOT", false);
        UUID tariff = tariff("Земельний податок");
        UUID first = version(tariff, "35", "2024-01-01");
        UUID second = version(tariff, "40", "2025-07-16");

        YearExpectation expected = year(tenant, 2025, List.of(plot)).get(plot);

        assertThat(expected.shares()).extracting(share -> share.versionId() + " " + share.rate() + " " + share.from()
                + ".." + share.to() + " " + share.days() + " " + share.amount()).containsExactly(
                        first + " 35.0000 2025-01-01..2025-07-15 196 113.47",
                        second + " 40.0000 2025-07-16..2025-12-31 169 110.32");
        assertThat(expected.shares().stream().mapToLong(VersionShare::days).sum()).isEqualTo(365);
        assertThat(expected.total()).isEqualTo(Money.parse("223.79"));
        // the total is what the month run charges: the calculator's amount of every month, each rounded once
        assertThat(expected.total()).isEqualTo(monthByMonth(List.of(
                terms("35", "2024-01-01"), terms("40", "2025-07-16")), new BigDecimal("600.00"), 2025));
    }

    @Test
    void aFirstVersionThatStartsInTheYearCountsFromItsFirstDay() {
        UUID plot = plot("1", "600.00", "PLOT", false);
        version(tariff("Земельний податок"), "35", "2025-03-10");

        YearExpectation expected = year(tenant, 2025, List.of(plot)).get(plot);

        assertThat(expected.shares()).singleElement().satisfies(share -> {
            assertThat(share.from()).isEqualTo(LocalDate.of(2025, 3, 10));
            assertThat(share.days()).isEqualTo(297);
            assertThat(share.amount()).isEqualTo(Money.parse("169.92"));
        });
        assertThat(year(tenant, 2024, List.of(plot))).isEmpty();
    }

    @Test
    void plotsTheTariffDoesNotApplyToAreLeftOut() {
        UUID charged = plot("1", "600.00", "PLOT", false);
        UUID excluded = plot("2", "600.00", "PLOT", false);
        UUID common = plot("road", "0.00", "COMMON", false);
        UUID archived = plot("3", "600.00", "PLOT", true);
        UUID tariff = tariff("Земельний податок");
        version(tariff, "35", "2024-01-01");
        ownerJdbc().update("insert into tariff_scope_plot (tenant_id, tariff_id, plot_id, kind, reason) "
                + "values (?, ?, ?, 'EXCLUDED', 'Звільнено')", tenant, tariff, excluded);

        Map<UUID, YearExpectation> expected = year(tenant, 2025, List.of(excluded, charged, common, archived,
                UUID.randomUUID(), charged));

        assertThat(expected).containsOnlyKeys(charged);
    }

    @Test
    void aTariffForSelectedPlotsChargesOnlyThem() {
        UUID selected = plot("1", "612.00", "PLOT", false);
        UUID other = plot("2", "600.00", "PLOT", false);
        UUID tariff = tariff("Земельний податок");
        version(tariff, "35", "2024-01-01");
        ownerJdbc().update("update tariff set scope_kind = 'SELECTED_PLOTS' where id = ?", tariff);
        ownerJdbc().update("insert into tariff_scope_plot (tenant_id, tariff_id, plot_id, kind) "
                + "values (?, ?, ?, 'SELECTED')", tenant, tariff, selected);

        Map<UUID, YearExpectation> expected = year(tenant, 2025, List.of(other, selected));

        assertThat(expected).containsOnlyKeys(selected);
        // 35 x 6.12 / 12 = 17.85 a month
        assertThat(expected.get(selected).areaSotka()).isEqualByComparingTo("6.12");
        assertThat(expected.get(selected).total()).isEqualTo(Money.parse("214.20"));
    }

    @Test
    void twoTariffsOnOnePlotChargeNothingAsInTheMonthRun() {
        UUID plot = plot("1", "600.00", "PLOT", false);
        version(tariff("Земельний податок"), "35", "2024-01-01");
        version(tariff("Ще один податок"), "40", "2025-07-01");

        YearExpectation expected = year(tenant, 2025, List.of(plot)).get(plot);

        // January to June only the first tariff applies; from July both do, a conflict
        assertThat(expected.shares()).singleElement().satisfies(share -> {
            assertThat(share.to()).isEqualTo(LocalDate.of(2025, 6, 30));
            assertThat(share.days()).isEqualTo(181);
        });
        assertThat(expected.total()).isEqualTo(Money.parse("105.00"));
    }

    @Test
    void anInactiveTypeAnUnknownCodeOrNoPlotsGiveNothing() {
        UUID plot = plot("1", "600.00", "PLOT", false);
        version(tariff("Земельний податок"), "35", "2024-01-01");

        assertThat(expectedAmountsOf("NO_SUCH_TYPE", List.of(plot))).isEmpty();
        assertThat(expectedAmountsOf("LAND_TAX", List.of())).isEmpty();
        ownerJdbc().update("update charge_type set active = false where id = ?", landTax);
        assertThat(expectedAmountsOf("LAND_TAX", List.of(plot))).isEmpty();
    }

    @Test
    void thePlotsAndTariffsOfAnotherCooperativeAreInvisible() {
        UUID plot = plot("1", "600.00", "PLOT", false);
        version(tariff("Земельний податок"), "35", "2024-01-01");

        assertThat(year(OTHER_TENANT, 2025, List.of(plot))).isEmpty();
        assertThat(year(tenant, 2025, List.of(plot))).containsOnlyKeys(plot);
    }

    @Test
    void itRunsOnlyInTheCallersTransaction() {
        UUID plot = plot("1", "600.00", "PLOT", false);

        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> expectedAmounts.yearByVersion("LAND_TAX", 2025,
                List.of(plot)))).isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---- fixture

    private Map<UUID, YearExpectation> year(UUID cooperative, int year, List<UUID> plots) {
        return TenantContext.runAs(cooperative, () -> tx.execute(status -> expectedAmounts.yearByVersion("LAND_TAX",
                year, plots)));
    }

    private Map<UUID, YearExpectation> expectedAmountsOf(String code, List<UUID> plots) {
        return TenantContext.runAs(tenant, () -> tx.execute(status -> expectedAmounts.yearByVersion(code, 2025,
                plots)));
    }

    private static Money monthByMonth(List<VersionTerms> versions, BigDecimal areaM2, int year) {
        Money total = Money.ZERO;
        for (Month month : Month.values()) {
            total = total.plus(ChargeCalculator.breakdown(versions, areaM2, YearMonth.of(year, month), DaySet.ALL)
                    .orElseThrow().amount());
        }
        return total;
    }

    private static VersionTerms terms(String rate, String validFrom) {
        return new VersionTerms(LocalDate.parse(validFrom), Basis.PER_AREA_SOTKA, Frequency.YEARLY, Rate.parse(rate),
                null);
    }

    private UUID plot(String number, String areaM2, String kind, boolean archived) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2, archived_at) "
                + "values (?, ?, ?, ?, ?, case when ? then now() end)", id, tenant, number, kind,
                new BigDecimal(areaM2), archived);
        return id;
    }

    private UUID tariff(String name) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into tariff (id, tenant_id, charge_type_id, name) values (?, ?, ?, ?)", id, tenant,
                landTax, name);
        return id;
    }

    /** A yearly per-sotka version of the tariff. */
    private UUID version(UUID tariff, String rate, String validFrom) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into tariff_version (id, tenant_id, tariff_id, valid_from, basis, frequency, rate) "
                + "values (?, ?, ?, ?::date, 'PER_AREA_SOTKA', 'YEARLY', ?)", id, tenant, tariff, validFrom,
                new BigDecimal(rate));
        return id;
    }
}
