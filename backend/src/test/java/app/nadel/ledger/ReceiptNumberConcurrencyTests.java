package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Receipt numbers (feature 015 §2.3, worked example 17): one sequence per cooperative, taken inside the saving
 * transaction, so two cash payments saved at the same moment get the next two numbers (no gap, no duplicate), a save
 * that fails after taking its number leaves no gap, and a bank payment takes no number.
 */
class ReceiptNumberConcurrencyTests extends PaymentApiSupport {

    @AfterEach
    void dropFailingTrigger() {
        ownerJdbc().execute("drop trigger if exists receipt_test_failure on payment");
        ownerJdbc().execute("drop function if exists receipt_test_failure()");
    }

    /** The receipt counter of the demo cooperative set to {@code last} (as the owner; the counter only steps by 1). */
    private static void lastReceiptIs(long last) {
        ownerJdbc().update("delete from receipt_counter where tenant_id = ?", DEMO_TENANT);
        ownerJdbc().update("insert into receipt_counter (tenant_id, last_number) values (?, ?)", DEMO_TENANT, last);
    }

    private long receiptOf(MvcResult result) throws Exception {
        JsonNode saved = json.readTree(result.getResponse().getContentAsString());
        return saved.get("payments").get(0).get("receiptNumber").asLong();
    }

    private int waitingForTheCounter() {
        return ownerJdbc().queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock' "
                + "and query like '%receipt_counter%'", Integer.class);
    }

    @Test
    void example17TwoCashPaymentsSavedAtTheSameMomentGetTheNextTwoNumbers() throws Exception {
        UUID first = newPlot();
        UUID second = newPlot();
        lastReceiptIs(40);
        // the billing month exists, so neither save waits for the other's insert of it
        month(DEMO_TENANT, YearMonth.from(kyivToday()).toString());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection blocker = ownerJdbc().getDataSource().getConnection()) {
            // a third transaction holds the counter row, so both saves reach it before either takes a number
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement(
                    "select last_number from receipt_counter where tenant_id = ? for update")) {
                lock.setObject(1, DEMO_TENANT);
                lock.executeQuery().close();
            }
            Future<MvcResult> a = pool.submit(() -> save(treasurer(), cash(first, "10.00")).andReturn());
            Future<MvcResult> b = pool.submit(() -> save(chairman(), cash(second, "20.00")).andReturn());
            boolean bothWaited = true;
            try {
                await().atMost(Duration.ofSeconds(10)).until(() -> waitingForTheCounter() == 2);
            } catch (ConditionTimeoutException e) {
                // the saves did not wait for the counter row: let them finish, then fail below
                bothWaited = false;
            }
            blocker.commit();
            blocker.setAutoCommit(true);

            MvcResult resultA = a.get(30, TimeUnit.SECONDS);
            MvcResult resultB = b.get(30, TimeUnit.SECONDS);
            assertThat(List.of(resultA.getResponse().getStatus(), resultB.getResponse().getStatus()))
                    .containsExactly(201, 201);
            assertThat(List.of(receiptOf(resultA), receiptOf(resultB))).containsExactlyInAnyOrder(41L, 42L);
            assertThat(bothWaited).as("both saves waited for the receipt counter row").isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(lastReceipt()).isEqualTo(42L);
        assertThat(ownerJdbc().queryForList("select receipt_number from payment where plot_id in (?, ?) "
                + "order by receipt_number", Long.class, first, second)).containsExactly(41L, 42L);
    }

    @Test
    void aSaveThatFailsAfterTakingItsNumberLeavesNoGap() throws Exception {
        UUID plot = newPlot();
        lastReceiptIs(40);
        // the payment row of 13.13 fails after the counter stepped, inside the saving transaction
        ownerJdbc().execute("""
                create function receipt_test_failure() returns trigger language plpgsql as $$
                begin
                    if new.amount = 13.13 then
                        raise exception 'receipt_test_failure';
                    end if;
                    return new;
                end;
                $$""");
        ownerJdbc().execute("create trigger receipt_test_failure before insert on payment for each row "
                + "execute function receipt_test_failure()");

        save(treasurer(), cash(plot, "13.13")).andExpect(status().is5xxServerError());
        assertThat(lastReceipt()).isEqualTo(40L);
        assertThat(paymentCount(plot)).isZero();

        long next = receiptOf(save(treasurer(), cash(plot, "10.00")).andExpect(status().isCreated()).andReturn());
        assertThat(next).isEqualTo(41L);
    }

    @Test
    void aBankPaymentTakesNoNumberAndAReversedNumberIsNeverReused() throws Exception {
        UUID plot = newPlot();
        lastReceiptIs(40);

        MvcResult bank = save(treasurer(), payment(plot, "BANK", "10.00", kyivToday().toString(), null))
                .andExpect(status().isCreated()).andReturn();
        assertThat(json.readTree(bank.getResponse().getContentAsString()).get("payments").get(0)
                .has("receiptNumber")).isFalse();
        assertThat(lastReceipt()).isEqualTo(40L);

        long first = receiptOf(save(treasurer(), cash(plot, "10.00")).andExpect(status().isCreated()).andReturn());
        // the cash payment is reversed (the reversal endpoint comes with a later slice): its number stays its own
        asLedgerOwner(sql -> sql.update("insert into payment (tenant_id, plot_id, kind, source, amount, received_on, "
                + "billing_month_id, group_id, reverses_payment_id, reason) select tenant_id, plot_id, "
                + "'PAYMENT_REVERSAL', source, -amount, received_on, billing_month_id, group_id, id, 'Помилка' "
                + "from payment where receipt_number = ? and plot_id = ?", first, plot));
        long second = receiptOf(save(treasurer(), cash(plot, "10.00")).andExpect(status().isCreated()).andReturn());

        assertThat(List.of(first, second)).containsExactly(41L, 42L);
        assertThat(ownerJdbc().queryForList("select receipt_number from payment where plot_id = ? "
                + "and receipt_number is not null order by receipt_number", Long.class, plot))
                .containsExactly(41L, 42L);
        Map<String, Object> listed = ownerJdbc().queryForMap("select count(*) as n from payment where plot_id = ? "
                + "and kind = 'PAYMENT_REVERSAL'", plot);
        assertThat(listed).containsEntry("n", 1L);
    }
}
