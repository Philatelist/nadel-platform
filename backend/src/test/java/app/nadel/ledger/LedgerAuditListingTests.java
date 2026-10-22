package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The audit entries of a month run ({@code BILLING_MONTH_RUN}) and of a retried "not charged" item
 * ({@code NOT_CHARGED_RETRIED}) are listed by {@code GET /audit} with their entity type, label and values: every action
 * the ledger writes must be a value of the contract's {@code AuditAction}, or the listing cannot map the stored entry.
 */
class LedgerAuditListingTests extends FreshCooperativeSupport {

    private static final String AUDIT = "/api/v1/audit";

    @Test
    void aBookedRunAndARetryAreListedInTheAuditLog() throws Exception {
        String month = ym(0);
        plot("7", "600.00");
        UUID plot12 = plot("12", "600.00");
        tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "50.0000", "2026-01-01");
        Tariff extra = tariff("MEMBERSHIP", "Додатковий внесок", "PER_PLOT", "MONTHLY", "100.0000", "2026-01-01");
        select(extra, plot12);

        // plot 7 is charged 25.00; plot 12 is in two membership tariffs and goes to the queue
        JsonNode booked = body(send(chairman, post(RUN.formatted(month, false)), null).andExpect(status().isOk()));
        UUID item = UUID.fromString(booked.get("notCharged").get(0).get("id").asString());
        unselect(extra, plot12);
        send(chairman, post(RETRY.formatted(item)), null).andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(owner().queryForObject(
                "select count(*) from audit_entry where tenant_id = ? "
                        + "and action in ('BILLING_MONTH_RUN', 'NOT_CHARGED_RETRIED')",
                Integer.class, tenant)).isEqualTo(2));

        JsonNode page = body(send(chairman, get(AUDIT), null).andExpect(status().isOk()));
        JsonNode run = only(page, "BILLING_MONTH_RUN");
        assertThat(run.get("entityType").asString()).isEqualTo("BILLING_MONTH");
        assertThat(run.get("entityId").asString()).isEqualTo(owner().queryForObject(
                "select id from billing_month where tenant_id = ? and month = ?::date", UUID.class, tenant,
                month + "-01").toString());
        assertThat(run.get("entityLabel").asString()).isEqualTo(month);
        assertThat(changes(run)).containsExactly(Map.entry("count", "1"), Map.entry("total", "25.00"));

        JsonNode retried = only(page, "NOT_CHARGED_RETRIED");
        assertThat(retried.get("entityType").asString()).isEqualTo("CHARGE");
        assertThat(retried.get("entityId").asString()).isEqualTo(plot12.toString());
        assertThat(retried.get("entityLabel").asString()).isEqualTo("12");
        assertThat(changes(retried)).containsExactly(Map.entry("kind", "TARIFF_MONTH"),
                Map.entry("reason", "CONFLICTING_TARIFFS"), Map.entry("chargeType", "base:MEMBERSHIP"),
                Map.entry("month", month), Map.entry("outcome", "RESOLVED"));

        // the filters of the log find them too
        JsonNode months = body(send(chairman, get(AUDIT).param("entityType", "BILLING_MONTH"), null)
                .andExpect(status().isOk()));
        only(months, "BILLING_MONTH_RUN");
        JsonNode plotEntries = body(send(chairman,
                get(AUDIT).param("entityType", "CHARGE").param("entityId", plot12.toString()), null)
                .andExpect(status().isOk()));
        only(plotEntries, "NOT_CHARGED_RETRIED");
    }

    /** The one entry of the page with this action. */
    private static JsonNode only(JsonNode page, String action) {
        List<JsonNode> found = new ArrayList<>();
        page.get("items").forEach(entry -> {
            if (entry.get("action").asString().equals(action)) {
                found.add(entry);
            }
        });
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    /** The changes of an entry as field to new value, in order; none of these entries has an old value. */
    private static Map<String, String> changes(JsonNode entry) {
        Map<String, String> changes = new LinkedHashMap<>();
        entry.get("changes").forEach(change -> {
            assertThat(change.has("oldValue")).isFalse();
            changes.put(change.get("field").asString(), change.get("newValue").asString());
        });
        return changes;
    }
}
