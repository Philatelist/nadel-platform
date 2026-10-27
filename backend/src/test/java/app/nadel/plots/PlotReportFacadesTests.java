package app.nadel.plots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
import app.nadel.money.Money;
import app.nadel.plots.PlotStatuses.Debt;
import app.nadel.settings.CooperativeSettings;
import app.nadel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The plot facades of the staff reports (feature 013): the current owners of plots ({@link PlotOwners}) and their
 * status ({@link PlotStatuses}) on throw-away plots of the demo cooperative, each with a cached debt chosen against the
 * cooperative's red threshold, plus another cooperative's plot.
 */
class PlotReportFacadesTests extends IntegrationTest {

    @Autowired
    PlotOwners owners;

    @Autowired
    PlotStatuses statuses;

    @Autowired
    CooperativeSettings settings;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    Clock clock;

    private final List<UUID> plots = new ArrayList<>();
    private final List<UUID> members = new ArrayList<>();

    private LocalDate today;
    private Money redAmount;
    private int redDays;

    @BeforeEach
    void readTheThreshold() {
        today = LocalDate.now(clock);
        redAmount = as(DEMO_TENANT, settings::redAmount);
        redDays = as(DEMO_TENANT, settings::redDays);
        assertThat(redAmount.value()).as("the fixture debts of 10.00 stay below the red amount")
                .isGreaterThan(new BigDecimal("10.00"));
    }

    @AfterEach
    void removeFixtures() {
        for (UUID plot : plots) {
            ownerJdbc().update("delete from ownership where plot_id = ?", plot);
            ownerJdbc().update("delete from plot_status where plot_id = ?", plot);
            ownerJdbc().update("delete from plot where id = ?", plot);
        }
        for (UUID member : members) {
            ownerJdbc().update("delete from member where id = ?", member);
        }
        plots.clear();
        members.clear();
    }

    // ---- owners

    @Test
    void ownerCountsCountOnlyCurrentOwnersAndEveryPlotIsAKey() {
        UUID first = member(DEMO_TENANT);
        UUID second = member(DEMO_TENANT);
        UUID former = member(DEMO_TENANT);
        UUID shared = plot(DEMO_TENANT, "PO-1", "PLOT");
        UUID sold = plot(DEMO_TENANT, "PO-2", "PLOT");
        UUID empty = plot(DEMO_TENANT, "PO-3", "PLOT");
        own(shared, second, "2024-03-01", null);
        own(shared, first, "2024-01-01", null);
        own(shared, former, "2020-01-01", "2023-12-31");
        own(sold, former, "2020-01-01", "2024-06-30");
        UUID theirs = plot(OTHER_TENANT, "PO-4", "PLOT");
        own(theirs, member(OTHER_TENANT), "2024-01-01", null);
        UUID unknown = UUID.randomUUID();

        Map<UUID, Integer> counts = as(DEMO_TENANT, () -> owners.ownerCounts(List.of(shared, sold, empty, theirs,
                unknown, shared)));
        Map<UUID, List<UUID>> ids = as(DEMO_TENANT, () -> owners.currentOwnerIds(List.of(shared, sold, theirs)));

        assertThat(counts).containsExactly(Map.entry(shared, 2), Map.entry(sold, 0), Map.entry(empty, 0),
                Map.entry(theirs, 0), Map.entry(unknown, 0));
        assertThat(ids.get(shared)).as("the earliest owner first").containsExactly(first, second);
        assertThat(ids.get(sold)).isEmpty();
        assertThat(ids.get(theirs)).as("another cooperative's owners are invisible (RLS)").isEmpty();
        assertThat(as(OTHER_TENANT, () -> owners.ownerCounts(List.of(theirs)))).containsEntry(theirs, 1);
        assertThat(as(DEMO_TENANT, () -> owners.currentOwnerIds(List.of()))).isEmpty();
    }

    // ---- statuses

    @Test
    void currentIsTheMapStatusAndClassifyWithTheCachedDebtAndTodayGivesTheSame() {
        Map<UUID, Debt> cached = new LinkedHashMap<>();
        UUID redByAmount = ownedPlotWithDebt("PS-1", redAmount.plus(Money.parse("0.01")), today, cached);
        UUID redByAge = ownedPlotWithDebt("PS-2", Money.parse("10.00"), today.minusDays(redDays + 1L), cached);
        UUID yellow = ownedPlotWithDebt("PS-3", Money.parse("10.00"), today.minusDays(redDays), cached);
        UUID green = ownedPlotWithDebt("PS-4", Money.ZERO, null, cached);
        UUID prepaid = ownedPlotWithDebt("PS-5", Money.parse("-5.00"), null, cached);
        UUID noOwner = plot(DEMO_TENANT, "PS-6", "PLOT");
        cache(noOwner, Money.parse("50.00"), today, cached);
        UUID formerOwnerOnly = plot(DEMO_TENANT, "PS-7", "PLOT");
        own(formerOwnerOnly, member(DEMO_TENANT), "2020-01-01", "2024-12-31");
        cache(formerOwnerOnly, Money.parse("50.00"), today, cached);
        UUID road = plot(DEMO_TENANT, "PS-8", "COMMON");
        cache(road, Money.ZERO, null, cached);
        UUID theirs = plot(OTHER_TENANT, "PS-9", "PLOT");

        List<UUID> asked = new ArrayList<>(cached.keySet());
        asked.add(theirs);
        Map<UUID, PlotStatus> current = as(DEMO_TENANT, () -> statuses.current(asked));
        Map<UUID, PlotStatus> classified = as(DEMO_TENANT, () -> statuses.classify(cached, today));

        assertThat(current).containsOnlyKeys(cached.keySet());
        assertThat(current).containsEntry(redByAmount, PlotStatus.RED).containsEntry(redByAge, PlotStatus.RED)
                .containsEntry(yellow, PlotStatus.YELLOW).containsEntry(green, PlotStatus.GREEN)
                .containsEntry(prepaid, PlotStatus.GREEN).containsEntry(noOwner, PlotStatus.GREY)
                .containsEntry(formerOwnerOnly, PlotStatus.GREY).containsEntry(road, PlotStatus.GREY);
        assertThat(classified).isEqualTo(current);
    }

    @Test
    void classifyCountsTheDebtAgeToTheGivenDayWithTodaysThreshold() {
        Map<UUID, Debt> cached = new LinkedHashMap<>();
        UUID plot = ownedPlotWithDebt("PS-10", Money.parse("10.00"), today.minusDays(redDays), cached);
        UUID noOwner = plot(DEMO_TENANT, "PS-11", "PLOT");

        Map<UUID, PlotStatus> dayLater = as(DEMO_TENANT, () -> statuses.classify(Map.of(plot,
                new Debt(Money.parse("10.00"), today.minusDays(redDays)), noOwner,
                new Debt(Money.parse("10.00"), today)), today.plusDays(1)));
        Map<UUID, PlotStatus> otherDebt = as(DEMO_TENANT, () -> statuses.classify(Map.of(plot,
                new Debt(redAmount.plus(Money.parse("0.01")), today.minusDays(1))), today.minusDays(30)));
        Map<UUID, PlotStatus> noDebt = as(DEMO_TENANT, () -> statuses.classify(Map.of(plot,
                new Debt(Money.ZERO, null)), today.minusDays(30)));

        assertThat(dayLater).containsEntry(plot, PlotStatus.RED).containsEntry(noOwner, PlotStatus.GREY);
        assertThat(otherDebt).containsEntry(plot, PlotStatus.RED);
        assertThat(noDebt).containsEntry(plot, PlotStatus.GREEN);
        assertThat(as(DEMO_TENANT, () -> statuses.classify(Map.of(), today))).isEmpty();
        assertThat(as(OTHER_TENANT, () -> statuses.classify(Map.of(plot, new Debt(Money.ZERO, null)), today)))
                .as("another cooperative's plot is absent").isEmpty();
    }

    @Test
    void theFacadesRunOnlyInTheCallersTransaction() {
        UUID plot = plot(DEMO_TENANT, "PS-12", "PLOT");

        assertThatThrownBy(() -> TenantContext.runAs(DEMO_TENANT, () -> statuses.current(List.of(plot))))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> TenantContext.runAs(DEMO_TENANT, () -> owners.ownerCounts(List.of(plot))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---- fixture

    private <T> T as(UUID tenant, Supplier<T> work) {
        return TenantContext.runAs(tenant, () -> tx.execute(status -> work.get()));
    }

    private UUID ownedPlotWithDebt(String number, Money debt, LocalDate oldest, Map<UUID, Debt> cached) {
        UUID plot = plot(DEMO_TENANT, number, "PLOT");
        own(plot, member(DEMO_TENANT), "2024-01-01", null);
        cache(plot, debt, oldest, cached);
        return plot;
    }

    private void cache(UUID plot, Money debt, LocalDate oldest, Map<UUID, Debt> cached) {
        ownerJdbc().update("update plot_status set debt = ?, oldest_debt_date = ? where plot_id = ?", debt.value(),
                oldest, plot);
        cached.put(plot, new Debt(debt, oldest));
    }

    private UUID plot(UUID tenant, String number, String kind) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2) values (?, ?, ?, ?, 600)", id,
                tenant, number, kind);
        ownerJdbc().update("insert into plot_status (plot_id, tenant_id, status) values (?, ?, 'GREY')", id, tenant);
        plots.add(id);
        return id;
    }

    private UUID member(UUID tenant) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into member (id, tenant_id, last_name, first_name) "
                + "values (?, ?, 'Facade', 'Test')", id, tenant);
        members.add(id);
        return id;
    }

    private void own(UUID plot, UUID member, String from, String to) {
        ownerJdbc().update("insert into ownership (tenant_id, plot_id, member_id, share_numerator, share_denominator, "
                + "valid_from, valid_to) select tenant_id, id, ?, 1, 2, ?::date, ?::date from plot where id = ?",
                member, from, to, plot);
    }
}
