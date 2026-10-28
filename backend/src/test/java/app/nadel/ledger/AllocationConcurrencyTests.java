package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
import java.util.concurrent.ThreadLocalRandom;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Two entries that place money on one plot at the same moment (feature 015 §2.2, technical considerations 2.4): the
 * plot ledger lock ({@code PlotLedgerLock}) makes the second wait until the first commits and then read the plot's
 * ledger afresh, so the payments never place more than a payment holds or more than is open on a charge. Both saves
 * run in real concurrent transactions; a third transaction holds the receipt counter row, so the first save stops
 * there while still holding its plot ledger lock, and the test sees where the second one waits: on the plot ledger
 * lock (an advisory lock), not after reading a ledger that is about to change. Without that lock the second save would
 * read the same open amounts and only the deferred backstop of V27 would refuse it at commit.
 */
class AllocationConcurrencyTests extends PaymentApiSupport {

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
}
