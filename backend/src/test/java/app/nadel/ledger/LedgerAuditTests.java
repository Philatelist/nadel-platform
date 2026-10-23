package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * A reversal and an opening balance are written to the audit log as codes and parameters (entity type {@code CHARGE}
 * about the plot, the plot number as the label, the charge type token, amounts with two decimals, ISO dates, the month
 * as {@code YYYY-MM}, the reason as typed); a refused change writes nothing. Both publish {@link ChargesBooked} for the
 * plot.
 */
@RecordApplicationEvents
class LedgerAuditTests extends LedgerApiSupport {

    @Autowired
    ApplicationEvents events;

    @Test
    void aReversalWritesOneEntryAboutThePlot() throws Exception {
        UUID plot = newPlot();
        UUID charge = tariffCharge(plot, "MEMBERSHIP", "25.00", "2026-10");

        send(chairman(), post(REVERSE.formatted(charge)), Map.of("reason", " Помилкове нарахування "))
                .andExpect(status().isCreated());

        Map<String, Object> entry = awaitEntries(plot, "CHARGE_REVERSED", 1).getFirst();
        assertThat(entry.get("entity_type")).isEqualTo("CHARGE");
        assertThat(entry.get("entity_label")).isEqualTo(plotNumber(plot));
        assertThat(entry.get("tenant_id")).isEqualTo(DEMO_TENANT);
        assertThat(entry.get("actor_member_id")).isEqualTo(demoMember("chairman@demo.local"));
        assertThat(changes(entry)).containsExactly(new Change("chargeType", null, "base:MEMBERSHIP"),
                new Change("amount", null, "-25.00"), new Change("serviceFrom", null, "2026-10-01"),
                new Change("serviceTo", null, "2026-10-31"), new Change("billingMonth", null, "2026-10"),
                new Change("reason", null, "Помилкове нарахування"));
        assertThat(events.stream(ChargesBooked.class))
                .containsExactly(new ChargesBooked(DEMO_TENANT, Set.of(plot)));
    }

    @Test
    void anOpeningBalanceWritesItsDateAndReason() throws Exception {
        UUID plot = newPlot();
        UUID custom = customChargeType(false);
        String customName = ownerJdbc().queryForObject("select name from charge_type where id = ?", String.class,
                custom);

        send(chairman(), post(OPENING.formatted(plot)), opening(typeId(DEMO_TENANT, "MEMBERSHIP"), "1500.00",
                "2026-09-01", "Борг за 2025 рік")).andExpect(status().isCreated());
        send(chairman(), post(OPENING.formatted(plot)), opening(custom, "12.30", "2026-09-15", "   "))
                .andExpect(status().isCreated());

        var entries = awaitEntries(plot, "OPENING_BALANCE_ENTERED", 2);
        assertThat(entries.get(0).get("entity_type")).isEqualTo("CHARGE");
        assertThat(entries.get(0).get("entity_label")).isEqualTo(plotNumber(plot));
        assertThat(changes(entries.get(0))).containsExactly(new Change("chargeType", null, "base:MEMBERSHIP"),
                new Change("amount", null, "1500.00"), new Change("date", null, "2026-09-01"),
                new Change("billingMonth", null, "2026-09"), new Change("reason", null, "Борг за 2025 рік"));
        // a custom type is written under its name; a blank reason is left out
        assertThat(changes(entries.get(1))).containsExactly(new Change("chargeType", null, customName),
                new Change("amount", null, "12.30"), new Change("date", null, "2026-09-15"),
                new Change("billingMonth", null, "2026-09"));
        assertThat(events.stream(ChargesBooked.class)).hasSize(2)
                .allSatisfy(event -> assertThat(event.plotIds()).containsExactly(plot));
        // the reason is stored on the charge too; a blank one is none
        assertThat(ownerJdbc().queryForList("select reason from charge where plot_id = ? order by amount desc",
                String.class, plot)).containsExactly("Борг за 2025 рік", null);
    }

    @Test
    void aRefusedReversalWritesNothing() throws Exception {
        UUID plot = newPlot();
        UUID charge = tariffCharge(plot, "MEMBERSHIP", "25.00", "2026-10");
        send(chairman(), post(REVERSE.formatted(charge)), Map.of("reason", "Помилка")).andExpect(status().isCreated());

        send(chairman(), post(REVERSE.formatted(charge)), Map.of("reason", "Ще раз")).andExpect(status().isConflict());
        send(chairman(), post(OPENING.formatted(plot)), opening(typeId(DEMO_TENANT, "MEMBERSHIP"), "0.00",
                "2026-09-01", null)).andExpect(status().isBadRequest());
        // a following change proves the earlier events would have been written by now
        send(chairman(), post(OPENING.formatted(plot)), opening(typeId(DEMO_TENANT, "LAND_TAX"), "5.00",
                "2026-09-01", null)).andExpect(status().isCreated());

        awaitEntries(plot, "OPENING_BALANCE_ENTERED", 1);
        assertThat(entries(plot, "CHARGE_REVERSED")).hasSize(1);
        assertThat(events.stream(ChargesBooked.class)).hasSize(2);
    }
}
