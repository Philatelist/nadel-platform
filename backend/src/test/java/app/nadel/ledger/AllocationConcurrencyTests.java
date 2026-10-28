package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Two entries that place money on one plot at the same moment (feature 015 §2.2, technical considerations 2.4): the
 * plot ledger lock ({@code PlotLedgerLock}) makes the second wait until the first commits and then read the plot's
 * ledger afresh, so the payments never place more than a payment holds or more than is open on a charge. Both saves
 * run in real concurrent transactions; a third transaction holds the receipt counter row, so the first save stops
 * there while still holding its plot ledger lock, and the test sees where the second one waits: on the plot ledger
 * lock (an advisory lock), not after reading a ledger that is about to change. Without that lock the second save would
 * read the same open amounts and only the deferred backstop of V27 would refuse it at commit.
 * <p>
 * The same holds against the other writers of a plot's allocations (slice 5): the automatic allocation of prepayment
 * after a {@link ChargesBooked}, and a recalculation whose adjustment lowers a paid charge and takes money back. Each
 * waits on the plot ledger lock of the entry, then reads the plot's ledger afresh: no deadlock, and no charge or
 * payment is over-allocated.
 */
class AllocationConcurrencyTests extends PaymentApiSupport {

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PlatformTransactionManager transactionManager;

    /** The recalculation and tariff of the recalculation case, removed before the ledger rows they point at. */
    private UUID recalculationPlot;
    private UUID customTariff;

    @AfterEach
    void removeRecalculation() {
        if (recalculationPlot == null) {
            return;
        }
        UUID plot = recalculationPlot;
        List<UUID> booked = ownerJdbc().queryForList("select distinct recalculation_id from recalculation_line "
                + "where plot_id = ?", UUID.class, plot);
        asLedgerOwner(sql -> {
            sql.update("delete from allocation where plot_id = ? and releases_allocation_id is not null", plot);
            sql.update("delete from allocation where plot_id = ?", plot);
            sql.update("delete from payment where plot_id = ?", plot);
            sql.update("delete from recalculation_line where plot_id = ?", plot);
            sql.update("delete from charge_part where charge_id in (select id from charge where plot_id = ?)", plot);
            sql.update("delete from charge where plot_id = ? and adjusts_charge_id is not null", plot);
            sql.update("delete from charge where plot_id = ?", plot);
            booked.forEach(id -> sql.update("delete from recalculation where id = ?", id));
        });
        booked.forEach(id -> ownerJdbc().update("delete from audit_entry where entity_id = ?", id));
        ownerJdbc().update("delete from tariff_scope_plot where tariff_id = ?", customTariff);
        ownerJdbc().update("delete from tariff_version where tariff_id = ?", customTariff);
        ownerJdbc().update("delete from tariff where id = ?", customTariff);
        recalculationPlot = null;
    }

    /** Where the concurrent saves wait while the counter row is held. */
    private record Waits(int onTheCounter, int onAPlotLedgerLock) {
    }

    private static Waits waits() {
        return new Waits(ownerJdbc().queryForObject("select count(*) from pg_stat_activity where "
                + "wait_event_type = 'Lock' and query like '%receipt_counter%'", Integer.class),
                ownerJdbc().queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock' "
                        + "and wait_event = 'advisory' and query like '%pg_advisory_xact_lock%'", Integer.class));
    }

    /** The receipt counter of the demo cooperative set to {@code last} (as the owner; the counter only steps by 1). */
    private static void lastReceiptIs(long last) {
        ownerJdbc().update("delete from receipt_counter where tenant_id = ?", DEMO_TENANT);
        ownerJdbc().update("insert into receipt_counter (tenant_id, last_number) values (?, ?)", DEMO_TENANT, last);
    }

    /** The outcome of two saves run at the same moment, and where they waited while the counter row was held. */
    private record Race(List<MvcResult> results, Waits waits) {

        List<Integer> statuses() {
            return results.stream().map(result -> result.getResponse().getStatus()).toList();
        }
    }

    /**
     * Runs both saves while a third transaction holds the receipt counter row, waits until both are blocked (one on
     * the counter, the other wherever the code makes it wait), records where, then lets them finish.
     */
    private Race race(Callable<MvcResult> first, Callable<MvcResult> second) throws Exception {
        lastReceiptIs(40);
        // the billing month exists, so neither save waits for the other's insert of it
        month(DEMO_TENANT, YearMonth.from(kyivToday()).toString());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection blocker = ownerJdbc().getDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement(
                    "select last_number from receipt_counter where tenant_id = ? for update")) {
                lock.setObject(1, DEMO_TENANT);
                lock.executeQuery().close();
            }
            Future<MvcResult> a = pool.submit(first);
            Future<MvcResult> b = pool.submit(second);
            Waits seen;
            try {
                await().atMost(Duration.ofSeconds(10)).until(() -> {
                    Waits now = waits();
                    return now.onTheCounter() + now.onAPlotLedgerLock() == 2;
                });
                seen = waits();
            } catch (ConditionTimeoutException e) {
                seen = waits();
            }
            blocker.commit();
            blocker.setAutoCommit(true);
            return new Race(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)), seen);
        } finally {
            pool.shutdownNow();
        }
    }

    private BigDecimal allocatedOn(UUID charge) {
        return ownerJdbc().queryForObject("select coalesce(sum(amount), 0) from allocation where charge_id = ?",
                BigDecimal.class, charge);
    }

    /** Fails when any payment of the plots has more placed on charges than it holds. */
    private void noPaymentIsOverAllocated(UUID... plots) {
        for (UUID plot : plots) {
            List<Map<String, Object>> over = ownerJdbc().queryForList("select p.id from payment p where p.plot_id = ? "
                    + "and (select coalesce(sum(a.amount), 0) from allocation a where a.payment_id = p.id) > p.amount",
                    plot);
            assertThat(over).as("payments with more allocated than they hold").isEmpty();
        }
    }

    /** What each payment of the plot placed on charges, as amount -> allocated, any order. */
    private List<String> placedByPayment(UUID plot) {
        return ownerJdbc().queryForList("select p.amount || ' -> ' || coalesce((select sum(a.amount) from "
                + "allocation a where a.payment_id = p.id), 0.00) from payment p where p.plot_id = ?", String.class,
                plot);
    }

    @Test
    void twoEntriesOnOnePlotNeverPlaceMoreThanIsOpenOnTheCharge() throws Exception {
        UUID plot = newPlot();
        UUID membership = tariffCharge(plot, "MEMBERSHIP", "600.00", "2026-01");

        Race race = race(() -> save(treasurer(), cash(plot, "500.00")).andReturn(),
                () -> save(chairman(), cash(plot, "500.00")).andReturn());

        // the mechanism: the second save waited on the plot ledger lock, before it read the plot's ledger
        assertThat(race.waits()).as("one save waits on the counter, the other on the plot ledger lock "
                + "(answers %s)", race.statuses())
                .isEqualTo(new Waits(1, 1));
        assertThat(race.statuses()).containsExactly(201, 201);
        // the first took 500.00 of the 600.00; the second read the 100.00 left and kept 400.00 as prepayment
        assertThat(allocatedOn(membership)).isEqualByComparingTo("600.00");
        assertThat(placedByPayment(plot)).containsExactlyInAnyOrder("500.00 -> 500.00", "500.00 -> 100.00");
        noPaymentIsOverAllocated(plot);
        assertThat(lastReceipt()).isEqualTo(42L);
    }

    @Test
    void aSplitAndAPaymentOfOneOfItsPlotsAtTheSameMomentKeepTheInvariants() throws Exception {
        UUID payer = newMember(DEMO_TENANT, "payer-" + UUID.randomUUID() + "@race.test");
        long base = ThreadLocalRandom.current().nextLong(10_000, 90_000) * 100;
        UUID shared = newPlot();
        UUID other = newPlot();
        ownerJdbc().update("update plot set number = ? where id = ?", String.valueOf(base + 1), shared);
        ownerJdbc().update("update plot set number = ? where id = ?", String.valueOf(base + 2), other);
        own(payer, shared);
        own(payer, other);
        UUID sharedCharge = tariffCharge(shared, "MEMBERSHIP", "300.00", "2026-01");
        UUID otherCharge = tariffCharge(other, "MEMBERSHIP", "100.00", "2026-01");
        Map<String, Object> split = new LinkedHashMap<>();
        split.put("source", "CASH");
        split.put("amount", "400.00");
        split.put("receivedOn", kyivToday().toString());
        split.put("split", Map.of("payerMemberId", payer.toString()));

        Race race = race(() -> save(treasurer(), split).andReturn(),
                () -> save(chairman(), cash(shared, "300.00")).andReturn());

        assertThat(race.waits()).as("one save waits on the counter, the other on the plot ledger lock "
                + "(answers %s)", race.statuses())
                .isEqualTo(new Waits(1, 1));
        assertThat(race.statuses()).containsExactly(201, 201);
        // whichever came first, each charge is paid exactly once and the rest is prepayment
        assertThat(allocatedOn(sharedCharge)).isEqualByComparingTo("300.00");
        assertThat(allocatedOn(otherCharge)).isEqualByComparingTo("100.00");
        noPaymentIsOverAllocated(shared, other);
        List<BigDecimal> totals = new ArrayList<>(ownerJdbc().queryForList("select coalesce(sum(amount), 0) "
                + "from payment where plot_id in (?, ?)", BigDecimal.class, shared, other));
        assertThat(totals.get(0)).isEqualByComparingTo("700.00");
        // the split's parts share one receipt number; the single payment has the other
        assertThat(ownerJdbc().queryForList("select distinct receipt_number from payment where plot_id in (?, ?) "
                + "order by receipt_number", Long.class, shared, other)).containsExactly(41L, 42L);
        assertThat(lastReceipt()).isEqualTo(42L);
    }

    /**
     * Runs {@code first} (a save) while a third transaction holds the receipt counter row, waits until it holds the
     * plot ledger lock and waits on the counter, then starts {@code second}, waits until that waits on the plot ledger
     * lock, records where both wait, and lets them finish. {@code second} runs in the test's pool; it may start work
     * that continues on another thread (the asynchronous allocator).
     */
    private Waits behind(Callable<MvcResult> first, Callable<Object> second, List<Object> results) throws Exception {
        lastReceiptIs(40);
        month(DEMO_TENANT, YearMonth.from(kyivToday()).toString());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection blocker = ownerJdbc().getDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement(
                    "select last_number from receipt_counter where tenant_id = ? for update")) {
                lock.setObject(1, DEMO_TENANT);
                lock.executeQuery().close();
            }
            Future<MvcResult> a = pool.submit(first);
            await().atMost(Duration.ofSeconds(10)).until(() -> waits().onTheCounter() == 1);
            Future<Object> b = pool.submit(second);
            Waits seen;
            try {
                await().atMost(Duration.ofSeconds(10)).until(() -> waits().onAPlotLedgerLock() == 1);
                seen = waits();
            } catch (ConditionTimeoutException e) {
                seen = waits();
            }
            blocker.commit();
            blocker.setAutoCommit(true);
            // neither waits for the other in a circle: both finish well within the timeout
            results.add(a.get(30, TimeUnit.SECONDS));
            results.add(b.get(30, TimeUnit.SECONDS));
            return seen;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anEntryAndTheAutomaticAllocationOnOnePlotKeepTheInvariants() throws Exception {
        ownerJdbc().update("update cooperative_settings set auto_allocate_prepayment = true where tenant_id = ?",
                DEMO_TENANT);
        UUID plot = newPlot();
        // 300.00 paid before anything was charged, then membership 500.00 booked (as the owner: no event yet)
        UUID prepaid = saved(treasurer(), payment(plot, "BANK", "300.00", kyivToday().toString(), List.of()));
        UUID membership = tariffCharge(plot, "MEMBERSHIP", "500.00", "2026-01");
        List<Object> results = new ArrayList<>();

        Waits seen = behind(() -> save(treasurer(), cash(plot, "400.00")).andReturn(), () -> {
            // the booking's event: the allocator runs after this commit, on its own thread
            new TransactionTemplate(transactionManager).executeWithoutResult(
                    status -> events.publishEvent(new ChargesBooked(DEMO_TENANT, Set.of(plot))));
            return null;
        }, results);

        assertThat(seen).as("the entry waits on the counter, the allocator on the plot ledger lock")
                .isEqualTo(new Waits(1, 1));
        assertThat(((MvcResult) results.get(0)).getResponse().getStatus()).isEqualTo(201);
        // the entry placed 400.00 of the 500.00; the allocator then read the 100.00 left and placed it from the 300.00
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(allocatedOn(membership))
                .isEqualByComparingTo("500.00"));
        assertThat(ownerJdbc().queryForObject("select coalesce(sum(amount), 0) from allocation where payment_id = ? "
                + "and origin = 'PREPAYMENT_AUTO'", BigDecimal.class, prepaid)).isEqualByComparingTo("100.00");
        assertThat(ledgerPrepayment(plot)).isEqualByComparingTo("200.00");
        noPaymentIsOverAllocated(plot);
    }

    @Test
    void anEntryAndARecalculationThatLowersThePaidChargeKeepTheInvariants() throws Exception {
        UUID plot = newPlot();
        recalculationPlot = plot;
        // a charge type of its own with a tariff of 500.00 a month for this plot only; March is closed and was
        // charged 600.00 under the tariff's key, so a recalculation adjusts it by -100.00
        UUID type = customChargeType(false);
        customTariff = UUID.randomUUID();
        ownerJdbc().update("insert into tariff (id, tenant_id, charge_type_id, name, scope_kind) values (?, ?, ?, "
                + "'Race', 'SELECTED_PLOTS')", customTariff, DEMO_TENANT, type);
        ownerJdbc().update("insert into tariff_scope_plot (tenant_id, tariff_id, plot_id, kind) values (?, ?, ?, "
                + "'SELECTED')", DEMO_TENANT, customTariff, plot);
        ownerJdbc().update("insert into tariff_version (id, tenant_id, tariff_id, valid_from, basis, frequency, rate) "
                + "values (?, ?, ?, date '2020-01-01', 'PER_PLOT', 'MONTHLY', 500.0000)", UUID.randomUUID(),
                DEMO_TENANT, customTariff);
        UUID march = closedMonth(DEMO_TENANT, "2026-03");
        UUID charge = UUID.randomUUID();
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, "
                + "source, amount, service_from, service_to, accrued_on, source_key, tariff_id) values (?, ?, ?, ?, ?, "
                + "'REGULAR', 'TARIFF', 600.00, date '2026-03-01', date '2026-03-31', date '2026-03-01', ?, ?)",
                charge, DEMO_TENANT, plot, type, march, "TARIFF:" + customTariff + ":" + plot + ":2026-03",
                customTariff);
        saved(treasurer(), payment(plot, "BANK", "400.00", kyivToday().toString(), null));
        Map<String, Object> body = recalculationOf(plot, type);
        JsonNode preview = body(send(chairman(), post("/api/v1/recalculations").param("preview", "true"), body)
                .andExpect(status().isOk()));
        assertThat(preview.get("lines")).singleElement().satisfies(line -> {
            assertThat(line.get("action").asString()).isEqualTo("ADJUST");
            assertThat(line.get("difference").asString()).isEqualTo("-100.00");
        });
        body.put("reason", "Тариф виправлено");
        body.put("digest", preview.get("digest").asString());
        List<Object> results = new ArrayList<>();

        Waits seen = behind(() -> save(treasurer(), cash(plot, "300.00")).andReturn(),
                () -> send(chairman(), post("/api/v1/recalculations").param("preview", "false"), body).andReturn(),
                results);

        assertThat(seen).as("the entry waits on the counter, the recalculation on the plot ledger lock")
                .isEqualTo(new Waits(1, 1));
        assertThat(((MvcResult) results.get(0)).getResponse().getStatus()).isEqualTo(201);
        assertThat(((MvcResult) results.get(1)).getResponse().getStatus()).isEqualTo(200);
        // the entry paid the 200.00 left of 600.00; the adjustment then took back 100.00 of that latest allocation
        assertThat(allocatedOn(charge)).isEqualByComparingTo("500.00");
        List<Map<String, Object>> releases = ownerJdbc().queryForList("select amount, release_cause from allocation "
                + "where plot_id = ? and amount < 0", plot);
        assertThat(releases).singleElement().satisfies(release -> {
            assertThat((BigDecimal) release.get("amount")).isEqualByComparingTo("-100.00");
            assertThat(release.get("release_cause")).isEqualTo("CHARGE_ADJUSTED");
        });
        assertThat(placedByPayment(plot)).containsExactlyInAnyOrder("400.00 -> 400.00", "300.00 -> 100.00");
        noPaymentIsOverAllocated(plot);
    }

    private static Map<String, Object> recalculationOf(UUID plot, UUID type) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("from", "2026-03");
        scope.put("to", "2026-03");
        scope.put("chargeTypeIds", List.of(type));
        scope.put("plotIds", List.of(plot));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cause", Map.of("type", "MANUAL", "manual", scope));
        return body;
    }
}
