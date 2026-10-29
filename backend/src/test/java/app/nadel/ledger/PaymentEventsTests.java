package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.money.Money;
import app.nadel.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The payment events of feature 017 §2.4: a saved payment publishes {@link PaymentRecorded} with its id (a split with
 * the id of every part), a reversal publishes {@link PaymentReversed} with the id of the reversed payment; ids only.
 * {@link LedgerReadModel#paymentsByIds} answers those ids with the rows of the payments report.
 */
@RecordApplicationEvents
class PaymentEventsTests extends PaymentApiSupport {

    @Autowired
    ApplicationEvents events;

    @Autowired
    LedgerReadModel readModel;

    @Autowired
    TransactionTemplate tx;

    @Test
    void aSavedPaymentPublishesItsId() throws Exception {
        UUID plot = newPlot();
        tariffCharge(plot, "MEMBERSHIP", "300.00", "2026-01");

        UUID payment = saved(treasurer(), cash(plot, "120.00"));

        assertThat(events.stream(PaymentRecorded.class))
                .containsExactly(new PaymentRecorded(DEMO_TENANT, List.of(payment)));
        assertThat(events.stream(PaymentReversed.class)).isEmpty();
    }

    @Test
    void aSplitPublishesOneEventWithEveryPart() throws Exception {
        UUID payer = newMember(DEMO_TENANT, "payer-" + UUID.randomUUID() + "@events.test");
        UUID first = newPlot();
        UUID second = newPlot();
        own(payer, first);
        own(payer, second);
        tariffCharge(first, "MEMBERSHIP", "300.00", "2026-01");
        tariffCharge(second, "MEMBERSHIP", "100.00", "2026-01");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "BANK");
        body.put("amount", "200.00");
        body.put("receivedOn", kyivToday().toString());
        body.put("split", Map.of("payerMemberId", payer.toString()));

        JsonNode saved = body(save(treasurer(), body).andExpect(status().isCreated())).get("payments");

        List<UUID> ids = new ArrayList<>();
        saved.forEach(part -> ids.add(UUID.fromString(part.get("id").asString())));
        assertThat(ids).hasSize(2);
        assertThat(events.stream(PaymentRecorded.class)).singleElement()
                .satisfies(event -> {
                    assertThat(event.tenantId()).isEqualTo(DEMO_TENANT);
                    assertThat(event.paymentIds()).containsExactlyInAnyOrderElementsOf(ids);
                });
    }

    @Test
    void aReversalPublishesTheReversedPaymentsId() throws Exception {
        UUID plot = newPlot();
        tariffCharge(plot, "MEMBERSHIP", "300.00", "2026-01");
        UUID payment = saved(treasurer(), cash(plot, "120.00"));
        events.clear();

        reverse(chairman(), payment, "Помилкова ділянка").andExpect(status().isCreated());

        assertThat(events.stream(PaymentReversed.class))
                .containsExactly(new PaymentReversed(DEMO_TENANT, List.of(payment)));
        assertThat(events.stream(PaymentRecorded.class)).isEmpty();
        UUID reversalRow = ownerJdbc().queryForObject("select id from payment where reverses_payment_id = ?",
                UUID.class, payment);
        assertThat(reversalRow).isNotEqualTo(payment);
    }

    @Test
    void paymentsByIdsAnswersThePaymentsAndLeavesOutReversalRowsAndUnknownIds() throws Exception {
        UUID plot = newPlot();
        tariffCharge(plot, "MEMBERSHIP", "300.00", "2026-01");
        UUID cash = saved(treasurer(), cash(plot, "120.00"));
        UUID bank = saved(treasurer(), with(payment(plot, "BANK", "50.00", kyivToday().toString(), null),
                "bankReference", "ПД-1"));
        reverse(chairman(), cash, "Помилкова ділянка").andExpect(status().isCreated());
        UUID reversalRow = ownerJdbc().queryForObject("select id from payment where reverses_payment_id = ?",
                UUID.class, cash);

        List<PaymentRow> rows = TenantContext.runAs(DEMO_TENANT, () -> tx.execute(status ->
                readModel.paymentsByIds(List.of(bank, cash, reversalRow, UUID.randomUUID(), cash))));

        assertThat(rows).extracting(PaymentRow::paymentId).containsExactlyInAnyOrder(cash, bank);
        PaymentRow reversed = rows.stream().filter(row -> row.paymentId().equals(cash)).findFirst().orElseThrow();
        assertThat(reversed.reversed()).isTrue();
        assertThat(reversed.amount()).isEqualTo(Money.parse("120.00"));
        assertThat(reversed.source()).isEqualTo(PaymentSource.CASH);
        assertThat(reversed.receiptNumber()).isNotNull();
        assertThat(reversed.plotId()).isEqualTo(plot);
        assertThat(reversed.receivedOn()).isEqualTo(kyivToday());
        PaymentRow bankRow = rows.stream().filter(row -> row.paymentId().equals(bank)).findFirst().orElseThrow();
        assertThat(bankRow.reversed()).isFalse();
        assertThat(bankRow.source()).isEqualTo(PaymentSource.BANK);
        List<PaymentRow> none = TenantContext.runAs(DEMO_TENANT, () -> tx.execute(status ->
                readModel.paymentsByIds(List.of())));
        assertThat(none).isEmpty();
        // another cooperative sees none of them
        List<PaymentRow> other = TenantContext.runAs(OTHER_TENANT, () -> tx.execute(status ->
                readModel.paymentsByIds(List.of(bank, cash))));
        assertThat(other).isEmpty();
    }

    @Test
    void theEventsCarryIdsOnlyAndAreNeverEmpty() {
        assertThat(PaymentRecorded.class.getRecordComponents()).extracting(c -> c.getName())
                .containsExactly("tenantId", "paymentIds");
        assertThat(PaymentReversed.class.getRecordComponents()).extracting(c -> c.getName())
                .containsExactly("tenantId", "paymentIds");
        assertThatThrownBy(() -> new PaymentRecorded(UUID.randomUUID(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentReversed(UUID.randomUUID(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentRecorded(null, List.of(UUID.randomUUID())))
                .isInstanceOf(NullPointerException.class);
    }
}
