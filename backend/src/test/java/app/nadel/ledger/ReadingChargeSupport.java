package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Plot meters and readings of a throw-away cooperative for the reading charge tests: meters are inserted as the database
 * owner (any installation date), readings go through the API (so the reading commands publish their events), and the
 * asynchronous listener is waited for.
 */
abstract class ReadingChargeSupport extends FreshCooperativeSupport {

    static final String READINGS = "/api/v1/readings";
    static final String METERS = "/api/v1/meters";

    /** A plot meter installed on the date with the initial value. */
    UUID meter(UUID plot, String resource, String installedOn, String initial) {
        UUID id = UUID.randomUUID();
        owner().update("insert into meter (id, tenant_id, kind, resource, serial, plot_id, installed_on, "
                + "initial_value) values (?, ?, 'PLOT', ?, ?, ?, ?::date, ?)", id, tenant, resource,
                "RC-" + id.toString().substring(0, 8), plot, installedOn, new BigDecimal(initial));
        return id;
    }

    UUID electricityMeter(UUID plot, String installedOn, String initial) {
        return meter(plot, "ELECTRICITY", installedOn, initial);
    }

    /** The electricity tariff of the examples: 4.3200 UAH/kWh from 01.01.2026, for all plots. */
    Tariff electricity() {
        return tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200", "2026-01-01");
    }

    /** Enters one reading as the chairman; returns the result row (outcome, reading or error). */
    JsonNode enter(UUID meter, String takenOn, String value) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("meterId", meter.toString());
        row.put("takenOn", takenOn);
        row.put("value", value);
        return body(send(chairman, post(READINGS), Map.of("rows", List.of(row))).andExpect(status().isOk()))
                .get("rows").get(0);
    }

    /** Enters one reading that must be saved; returns its id. */
    UUID entered(UUID meter, String takenOn, String value) throws Exception {
        JsonNode result = enter(meter, takenOn, value);
        assertThat(result.get("outcome").asString()).isEqualTo("SAVED");
        return UUID.fromString(result.get("reading").get("id").asString());
    }

    /** Waits until the cooperative has at least {@code count} charges and the listeners are done. */
    void awaitCharges(int count) {
        await().atMost(Duration.ofSeconds(15)).until(() -> chargeCount() >= count);
        awaitListeners();
    }

    /** Waits until every event publication of the cooperative is complete (the listeners ran). */
    void awaitListeners() {
        await().atMost(Duration.ofSeconds(15)).until(() -> owner().queryForObject(
                "select count(*) from event_publication where completion_date is null and serialized_event like ?",
                Integer.class, "%" + tenant + "%") == 0);
    }

    /** The open "not charged" items of the cooperative. */
    List<Map<String, Object>> openItems() {
        return owner().queryForList("select id, item_key, kind, reason, plot_id, meter_id, charge_type_id, "
                + "from_date, to_date, params::text as params from not_charged_item where tenant_id = ? "
                + "and status = 'OPEN' order by created_at", tenant);
    }

    /** The meter values a charge read, as dates. */
    List<String> usedOn(UUID charge) {
        return owner().queryForList("select used_on::text from meter_value_use where charge_id = ? order by used_on",
                String.class, charge);
    }

    /** The charges of the plot with their source and meter, oldest first. */
    List<Map<String, Object>> readingCharges(UUID plot) {
        return owner().queryForList("select c.id, c.amount::text as amount, c.kind, c.source, c.service_from::text "
                + "as service_from, c.service_to::text as service_to, c.accrued_on::text as accrued_on, "
                + "c.source_key, c.meter_id, c.tariff_id, c.created_by, to_char(m.month, 'YYYY-MM') as billing_month "
                + "from charge c join billing_month m on m.id = c.billing_month_id where c.plot_id = ? "
                + "order by c.accrued_on, c.source, c.created_at", plot);
    }
}
