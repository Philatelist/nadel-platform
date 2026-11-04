package app.nadel.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.ledger.PaymentMoneyMoved;
import app.nadel.ledger.PaymentSource;
import app.nadel.money.Money;
import app.nadel.tenancy.TenantContext;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Member payments in the journal (feature 018 §2.2, §2.17 #1 and #10; technical considerations 2.2, ADR 0019): a payment
 * saved on the payments screen writes exactly one {@code MEMBER_PAYMENT} movement on the cash or bank account, in the
 * payment's own transaction (a split one per part); its reversal writes the negated movement dated on the day of the
 * reversal in the month that day is booked in, pointing at the payment's movement with the reversal's reason. The
 * listener is synchronous: a failure rolls the payment back, and the event never reaches the outbox.
 */
class PaymentMovementsTests extends TreasuryApiSupport {

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    String chairman;
    UUID chairmanId;
    String treasurer;
    UUID treasurerId;

    @BeforeEach
    void people() {
        chairman = person(tenantA, "CHAIRMAN");
        chairmanId = lastPerson;
        treasurer = person(tenantA, "TREASURER");
        treasurerId = lastPerson;
    }

    @Test
    void aCashPaymentWritesOneMovementOnTheCashAccountOnItsDayAndInItsMonth() throws Exception {
        UUID plot = paymentPlot(tenantA);
        LocalDate received = today().minusDays(2);

        UUID payment = pay(treasurer, plot, "CASH", "500.00", received);

        assertThat(storedMovements(tenantA)).singleElement().satisfies(movement -> {
            assertThat(movement.get("account")).isEqualTo("CASH");
            assertThat(movement.get("kind")).isEqualTo("MEMBER_PAYMENT");
            assertThat(movement.get("amount")).isEqualTo("500.00");
            assertThat(movement.get("moved_on")).isEqualTo(received.toString());
            assertThat(movement.get("booking_month")).isEqualTo(paymentMonth(payment));
            assertThat(movement.get("source_type")).isEqualTo("PAYMENT");
            assertThat(movement.get("source_id")).isEqualTo(payment);
            assertThat(movement.get("payment_row_id")).isEqualTo(payment);
            assertThat(movement.get("reverses_movement_id")).isNull();
            assertThat(movement.get("reason")).isNull();
            assertThat(movement.get("created_by")).isEqualTo(treasurerId);
        });
        assertThat(storedMovements(tenantB)).isEmpty();
    }

    @Test
    void aSplitBankPaymentWritesOneMovementPerPartOnTheBankAccount() throws Exception {
        UUID payer = member(tenantA);
        UUID first = paymentPlot(tenantA);
        UUID second = paymentPlot(tenantA);
        own(tenantA, payer, first);
        own(tenantA, payer, second);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "BANK");
        body.put("amount", "300.00");
        body.put("receivedOn", today().toString());
        body.put("bankReference", "ПД-77");
        body.put("split", Map.of("payerMemberId", payer.toString(), "parts", List.of(
                Map.of("plotId", first.toString(), "amount", "100.00"),
                Map.of("plotId", second.toString(), "amount", "200.00"))));

        JsonNode saved = body(save(treasurer, body).andExpect(status().isCreated())).get("payments");

        assertThat(saved).hasSize(2);
        List<Map<String, Object>> movements = storedMovements(tenantA);
        assertThat(movements).hasSize(2).allSatisfy(movement -> {
            assertThat(movement.get("account")).isEqualTo("BANK");
            assertThat(movement.get("kind")).isEqualTo("MEMBER_PAYMENT");
            assertThat(movement.get("moved_on")).isEqualTo(today().toString());
        });
        assertThat(movements).extracting(movement -> movement.get("amount"))
                .containsExactlyInAnyOrder("100.00", "200.00");
        assertThat(movements).extracting(movement -> movement.get("payment_row_id")).containsExactlyInAnyOrder(
                UUID.fromString(saved.get(0).get("id").asString()), UUID.fromString(saved.get(1).get("id").asString()));
    }

    @Test
    void aReversalWritesTheNegatedMovementTodayInTheOpenMonthAndPointsAtThePaymentsMovement() throws Exception {
        UUID plot = paymentPlot(tenantA);
        // received on the first of the previous month, which is then closed: the money leaves the cash today, in the
        // current month, while the payment's movement keeps its day and month
        LocalDate received = today().minusMonths(1).withDayOfMonth(1);
        UUID payment = pay(treasurer, plot, "CASH", "500.00", received);
        closeMonth(tenantA, YearMonth.from(received));

        reverse(chairman, payment, "Помилкова ділянка");

        UUID reversal = reversalRow(payment);
        List<Map<String, Object>> movements = storedMovements(tenantA);
        assertThat(movements).hasSize(2);
        Map<String, Object> original = movements.get(0);
        Map<String, Object> reversing = movements.get(1);
        assertThat(original.get("payment_row_id")).isEqualTo(payment);
        assertThat(original.get("moved_on")).isEqualTo(received.toString());
        assertThat(original.get("booking_month")).isEqualTo(YearMonth.from(received).atDay(1).toString());
        assertThat(reversing.get("account")).isEqualTo("CASH");
        assertThat(reversing.get("kind")).isEqualTo("MEMBER_PAYMENT");
        assertThat(reversing.get("amount")).isEqualTo("-500.00");
        assertThat(reversing.get("moved_on")).isEqualTo(today().toString());
        assertThat(reversing.get("booking_month")).isEqualTo(YearMonth.from(today()).atDay(1).toString());
        assertThat(reversing.get("source_type")).isEqualTo("PAYMENT");
        assertThat(reversing.get("payment_row_id")).isEqualTo(reversal);
        assertThat(reversing.get("reverses_movement_id")).isEqualTo(original.get("id"));
        assertThat(reversing.get("reason")).isEqualTo("Помилкова ділянка");
        assertThat(reversing.get("created_by")).isEqualTo(chairmanId);
    }

    @Test
    void aFailingListenerRollsThePaymentBack() throws Exception {
        UUID plot = paymentPlot(tenantA);
        // a cooperative without its cash account: the listener cannot write the movement
        ownerJdbc().update("delete from money_account where tenant_id = ? and kind = 'CASH'", tenantA);

        save(treasurer, payment(plot, "CASH", "120.00", today())).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(ownerJdbc().queryForObject("select count(*) from payment where tenant_id = ?", Integer.class,
                tenantA)).isZero();
        assertThat(ownerJdbc().queryForObject("select count(*) from allocation where tenant_id = ?", Integer.class,
                tenantA)).isZero();
        assertThat(ownerJdbc().queryForList("select last_number from receipt_counter where tenant_id = ?",
                Long.class, tenantA)).as("the receipt number is not used either").allMatch(last -> last == 0L);
        assertThat(storedMovements(tenantA)).isEmpty();
        // the bank account still works
        pay(treasurer, plot, "BANK", "120.00", today());
        assertThat(storedMovements(tenantA)).singleElement()
                .satisfies(movement -> assertThat(movement.get("account")).isEqualTo("BANK"));
    }

    @Test
    void theMovementIsWrittenInThePublishingTransactionAndNeverReachesTheOutbox() throws Exception {
        UUID plot = paymentPlot(tenantA);
        // a payment row of the ledger without its movement (as the database owner): the event alone writes it
        UUID payment = UUID.randomUUID();
        UUID month = billingMonth(tenantA, today().withDayOfMonth(1).toString());
        ownerJdbc().update("insert into payment (id, tenant_id, plot_id, kind, source, amount, received_on, "
                + "billing_month_id, group_id) values (?, ?, ?, 'PAYMENT', 'BANK', 75.00, ?, ?, ?)", payment, tenantA,
                plot, today(), month, payment);
        PaymentMoneyMoved event = new PaymentMoneyMoved(tenantA, List.of(new PaymentMoneyMoved.Line(payment, payment,
                false, PaymentSource.BANK, Money.parse("75.00"), today(), YearMonth.from(today()), null,
                Instant.now())));

        Map<String, Object> seen = TenantContext.runAs(tenantA, () -> tx.execute(status -> {
            events.publishEvent(event);
            Map<String, Object> inside = new LinkedHashMap<>();
            inside.put("movements", jdbc.queryForObject("select count(*) from account_movement where "
                    + "payment_row_id = ?", Integer.class, payment));
            // the registry would store a publication in this very transaction, at publish time
            inside.put("publications", jdbc.queryForObject("select count(*) from event_publication "
                    + "where event_type like ?", Integer.class, "%PaymentMoneyMoved%"));
            status.setRollbackOnly();
            return inside;
        }));

        assertThat(seen.get("movements")).as("written synchronously, before the publisher returns").isEqualTo(1);
        assertThat(seen.get("publications")).as("no outbox row for the event").isEqualTo(0);
        assertThat(storedMovements(tenantA)).as("rolled back with the publishing transaction").isEmpty();
        // a payment through the API leaves no publication of the event either
        pay(treasurer, plot, "CASH", "10.00", today());
        assertThat(ownerJdbc().queryForObject("select count(*) from event_publication where event_type like ?",
                Integer.class, "%PaymentMoneyMoved%")).isZero();
    }

    @Test
    void theListenerIsAPlainSynchronousEventListener() throws Exception {
        Method on = Arrays.stream(Class.forName("app.nadel.treasury.internal.PaymentMovementListener")
                        .getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(EventListener.class)).findFirst().orElseThrow();
        assertThat(Arrays.stream(on.getAnnotations()).map(Annotation::annotationType).map(Class::getName))
                .as("never an outbox (transactional) or asynchronous listener")
                .noneMatch(name -> name.endsWith("TransactionalEventListener") || name.endsWith("ApplicationModuleListener")
                        || name.endsWith("Async") || name.endsWith("Transactional"));
    }

    @Test
    void anEventOfAnotherCooperativeIsRefused() {
        PaymentMoneyMoved event = new PaymentMoneyMoved(tenantB, List.of(new PaymentMoneyMoved.Line(UUID.randomUUID(),
                UUID.randomUUID(), false, PaymentSource.CASH, Money.parse("1.00"), today(), YearMonth.from(today()),
                null, Instant.now())));

        assertThatThrownBy(() -> TenantContext.runAs(tenantA, () -> tx.execute(status -> {
            events.publishEvent(event);
            return null;
        }))).isInstanceOf(IllegalStateException.class).hasMessageContaining("bound cooperative");
    }

    // ---- helpers

    /** The billing month (first day, ISO) the payment row is booked in. */
    private String paymentMonth(UUID payment) {
        return ownerJdbc().queryForObject("select bm.month::text from payment p join billing_month bm "
                + "on bm.id = p.billing_month_id where p.id = ?", String.class, payment);
    }

    private void closeMonth(UUID tenant, YearMonth month) {
        ownerJdbc().update("insert into billing_month (tenant_id, month) values (?, ?) "
                + "on conflict (tenant_id, month) do nothing", tenant, month.atDay(1));
        ownerJdbc().update("update billing_month set status = 'CLOSED', closed_at = now() where tenant_id = ? "
                + "and month = ?", tenant, month.atDay(1));
    }
}
