package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/**
 * The audit of a payment (feature 015 §2.12, technical considerations 2.8): one {@code PAYMENT_RECORDED} entry about
 * the plot (entity type {@code PAYMENT}) with the amount, source, date, billing month and receipt number or bank
 * reference, and the payer note sealed with the 014 mechanism; {@code PAYMENT_REALLOCATED} with the old and new lines,
 * {@code PAYMENT_REVERSED} with the reason and {@code PREPAYMENT_ALLOCATED} with the lines and their total. The note is never readable outside the app: not in
 * {@code payment}, not in {@code audit_entry}, not in the outbox ({@code event_publication}) and not in the log, even
 * at debug level. The audit listing opens it only for a reader who may view members.
 */
@ExtendWith(OutputCaptureExtension.class)
class PaymentAuditTests extends PaymentApiSupport {

    private static final String AUDIT = "/api/v1/audit";
    /**
     * Unique per run: other tests legitimately store readable member first names in the audit log (names are not
     * sealed since 014), so a fixed name here would collide with their rows in the global scans below.
     */
    private static final String PAYER = "Оксана-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String NOTE = "Онука " + PAYER + " Петренко, +380671112233";

    private UUID cashWithNote(UUID plot) throws Exception {
        JsonNode saved = body(save(treasurer(), with(with(cash(plot, "250.00"), "payerNote", "  " + NOTE + " "),
                "payerMemberId", demoMember("member@demo.local").toString())).andExpect(status().isCreated()));
        return UUID.fromString(saved.get("payments").get(0).get("id").asString());
    }

    @Test
    void aSavedPaymentIsAuditedWithItsValuesAndTheNoteSealed() throws Exception {
        UUID plot = newPlot();
        tariffCharge(plot, "MEMBERSHIP", "600.00", "2026-01");
        UUID payment = cashWithNote(plot);
        long receipt = ownerJdbc().queryForObject("select receipt_number from payment where id = ?", Long.class,
                payment);

        Map<String, Object> entry = awaitEntries(plot, "PAYMENT_RECORDED", 1).get(0);
        assertThat(entry).containsEntry("entity_type", "PAYMENT").containsEntry("entity_label", plotNumber(plot))
                .containsEntry("actor_member_id", demoMember("treasurer@demo.local"));
        List<Change> changes = plainChanges(entry);
        JsonNode stored = json.readTree((String) entry.get("changes"));
        JsonNode note = null;
        for (JsonNode change : stored) {
            if (change.get("field").asString().equals("payerNote")) {
                note = change;
            }
        }
        assertThat(note).isNotNull();
        assertThat(note.get("new").has("enc")).isTrue();
        assertThat(changes).contains(new Change("amount", null, "250.00"), new Change("source", null, "CASH"),
                new Change("receivedOn", null, kyivToday().toString()),
                new Change("billingMonth", null, kyivToday().toString().substring(0, 7)),
                new Change("receiptNumber", null, Long.toString(receipt)));
        assertThat(changes).extracting(Change::field).doesNotContain("bankReference");
        assertThat((String) entry.get("changes")).doesNotContain(NOTE).doesNotContain(PAYER)
                .contains("\"enc\"");

        // the chairman (MEMBERS_VIEW) reads the note in the log, a reader without it sees it hidden
        JsonNode opened = payerNoteChange(chairman(), plot);
        assertThat(opened.get("newValue").asString()).isEqualTo(NOTE);
        JsonNode hidden = payerNoteChange(customRoleToken("AUDIT_VIEW"), plot);
        assertThat(hidden.get("hidden").asBoolean()).isTrue();
        assertThat(hidden.has("newValue")).isFalse();
    }

    @Test
    void aBankPaymentIsAuditedWithItsReferenceAndNoNote() throws Exception {
        UUID plot = newPlot();

        save(treasurer(), with(payment(plot, "BANK", "80.00", kyivToday().toString(), null), "bankReference",
                "ПД-4711")).andExpect(status().isCreated());

        List<Change> changes = plainChanges(awaitEntries(plot, "PAYMENT_RECORDED", 1).get(0));
        assertThat(changes).contains(new Change("source", null, "BANK"), new Change("bankReference", null, "ПД-4711"));
        assertThat(changes).extracting(Change::field).doesNotContain("receiptNumber", "payerNote");
    }

    @Test
    void theNoteIsNeverStoredOrLoggedInReadableForm(CapturedOutput output) throws Exception {
        Logger app = (Logger) LoggerFactory.getLogger("app.nadel");
        Level level = app.getLevel();
        app.setLevel(Level.DEBUG);
        // completed publications are deleted: a copy of every row written to the outbox is kept for the check
        ownerJdbc().execute("create table outbox_copy (serialized_event text)");
        ownerJdbc().execute("""
                create function outbox_copy() returns trigger language plpgsql security definer as $$
                begin
                    insert into outbox_copy values (new.serialized_event);
                    return new;
                end;
                $$""");
        ownerJdbc().execute("create trigger outbox_copy after insert on event_publication for each row "
                + "execute function outbox_copy()");
        try {
            UUID plot = newPlot();
            UUID payment = cashWithNote(plot);
            awaitEntries(plot, "PAYMENT_RECORDED", 1);

            byte[] stored = ownerJdbc().queryForObject("select payer_note_enc from payment where id = ?",
                    byte[].class, payment);
            assertThat(stored).isNotEmpty();
            assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain(PAYER);
            assertThat(ownerJdbc().queryForObject("select count(*) from payment where id = ? "
                    + "and position(convert_to(?, 'UTF8') in payer_note_enc) > 0", Integer.class, payment, PAYER))
                    .isZero();
            assertThat(ownerJdbc().queryForObject("select count(*) from audit_entry where cast(changes as text) "
                    + "like ?", Integer.class, "%" + PAYER + "%")).isZero();
            assertThat(ownerJdbc().queryForObject("select count(*) from outbox_copy where serialized_event "
                    + "like ? and serialized_event like '%PAYMENT_RECORDED%' and serialized_event like '%payerNote%'",
                    Integer.class, "%" + plot + "%")).isOne();
            assertThat(ownerJdbc().queryForObject("select count(*) from outbox_copy where serialized_event like ? "
                    + "or serialized_event like ?", Integer.class, "%" + PAYER + "%", "%380671112233%")).isZero();
            assertThat(ownerJdbc().queryForObject("select count(*) from event_publication where serialized_event "
                    + "like ?", Integer.class, "%" + PAYER + "%")).isZero();
            // the card opens it for a reader who may view members
            assertThat(body(card(treasurer(), payment).andExpect(status().isOk())).get("payerNote").asString())
                    .isEqualTo(NOTE);
            assertThat(output.getAll()).contains("Payment " + payment).doesNotContain(PAYER)
                    .doesNotContain("380671112233");
        } finally {
            app.setLevel(level);
            ownerJdbc().execute("drop trigger if exists outbox_copy on event_publication");
            ownerJdbc().execute("drop function if exists outbox_copy()");
            ownerJdbc().execute("drop table if exists outbox_copy");
        }
    }

    @Test
    void enteredReallocatedAndReversedAreThreeEntries() throws Exception {
        UUID plot = newPlot();
        UUID membership = tariffCharge(plot, "MEMBERSHIP", "600.00", "2026-01");
        UUID electricity = tariffCharge(plot, "ELECTRICITY", "250.00", "2026-02");
        UUID payment = UUID.fromString(body(save(treasurer(), cash(plot, "700.00")).andExpect(status().isCreated()))
                .get("payments").get(0).get("id").asString());
        long receipt = ownerJdbc().queryForObject("select receipt_number from payment where id = ?", Long.class,
                payment);

        reallocate(treasurer(), payment, false, lines(List.of(line(electricity, "250.00"),
                line(membership, "450.00")), null)).andExpect(status().isCreated());
        reverse(chairman(), payment, "Помилкова ділянка").andExpect(status().isCreated());

        awaitEntries(plot, "PAYMENT_RECORDED", 1);
        Map<String, Object> reallocated = awaitEntries(plot, "PAYMENT_REALLOCATED", 1).get(0);
        assertThat(reallocated).containsEntry("entity_type", "PAYMENT").containsEntry("entity_label", plotNumber(plot))
                .containsEntry("actor_member_id", demoMember("treasurer@demo.local"));
        assertThat(changes(reallocated)).containsExactly(new Change("amount", null, "700.00"),
                new Change("receivedOn", null, kyivToday().toString()),
                new Change("receiptNumber", null, Long.toString(receipt)),
                new Change("lines", "MEMBERSHIP 2026-01 600.00; ELECTRICITY 2026-02 100.00",
                        "ELECTRICITY 2026-02 250.00; MEMBERSHIP 2026-01 450.00"));
        Map<String, Object> reversed = awaitEntries(plot, "PAYMENT_REVERSED", 1).get(0);
        assertThat(reversed).containsEntry("entity_type", "PAYMENT")
                .containsEntry("actor_member_id", demoMember("chairman@demo.local"));
        assertThat(changes(reversed)).containsExactly(new Change("amount", null, "-700.00"),
                new Change("receiptNumber", null, Long.toString(receipt)),
                new Change("billingMonth", null, kyivToday().toString().substring(0, 7)),
                new Change("reason", null, "Помилкова ділянка"));

        // the audit listing reads every action back (the contract knows them)
        JsonNode page = body(send(chairman(), get(AUDIT).param("entityType", "PAYMENT").param("entityId",
                plot.toString()), null).andExpect(status().isOk()));
        List<String> actions = new ArrayList<>();
        page.get("items").forEach(item -> actions.add(item.get("action").asString()));
        assertThat(actions).containsExactlyInAnyOrder("PAYMENT_RECORDED", "PAYMENT_REALLOCATED", "PAYMENT_REVERSED");
    }

    @Test
    void aPrepaymentAllocatedByHandIsAuditedWithItsLinesAndTotal() throws Exception {
        UUID plot = newPlot();
        save(treasurer(), payment(plot, "BANK", "150.00", kyivToday().toString(), null))
                .andExpect(status().isCreated());
        UUID landTax = tariffCharge(plot, "LAND_TAX", "120.00", "2026-03");

        allocatePrepayment(treasurer(), plot, false, lines(List.of(line(landTax, "100.00")), null))
                .andExpect(status().isCreated());

        Map<String, Object> entry = awaitEntries(plot, "PREPAYMENT_ALLOCATED", 1).get(0);
        assertThat(entry).containsEntry("entity_type", "PAYMENT").containsEntry("entity_label", plotNumber(plot))
                .containsEntry("actor_member_id", demoMember("treasurer@demo.local"));
        assertThat(changes(entry)).containsExactly(new Change("lines", null, "LAND_TAX 2026-03 100.00"),
                new Change("total", null, "100.00"));
        JsonNode page = body(send(chairman(), get(AUDIT).param("entityType", "PAYMENT").param("entityId",
                plot.toString()), null).andExpect(status().isOk()));
        assertThat(page.get("items").findValuesAsString("action")).contains("PREPAYMENT_ALLOCATED");
    }

    /** The stored changes whose values are plain text (a sealed value is an object {@code {"enc": ...}}). */
    private List<Change> plainChanges(Map<String, Object> entry) {
        List<Change> result = new ArrayList<>();
        for (JsonNode change : json.readTree((String) entry.get("changes"))) {
            JsonNode value = change.get("new");
            if (value != null && value.isString()) {
                result.add(new Change(change.get("field").asString(), null, value.asString()));
            }
        }
        return result;
    }

    private JsonNode payerNoteChange(String bearer, UUID plot) throws Exception {
        JsonNode page = body(send(bearer, get(AUDIT).param("entityType", "PAYMENT").param("entityId",
                plot.toString()), null).andExpect(status().isOk()));
        assertThat(page.get("items")).hasSize(1);
        JsonNode item = page.get("items").get(0);
        assertThat(item.get("action").asString()).isEqualTo("PAYMENT_RECORDED");
        for (JsonNode change : item.get("changes")) {
            if (change.get("field").asString().equals("payerNote")) {
                return change;
            }
        }
        throw new AssertionError("No payerNote change");
    }
}
