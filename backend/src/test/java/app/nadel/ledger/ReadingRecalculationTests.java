package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import app.nadel.meters.MeterResource;
import app.nadel.meters.PlotMeterPointsChanged;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Correcting readings with a recalculation (feature 012 §2.5, slice 7): {@code POST /recalculations} with the causes
 * {@code READING_ENTER}, {@code _CORRECT} and {@code _DELETE} applies the reading change and plans in one transaction;
 * a preview rolls both back, a booking saves the change and the recalculation together; a plan without lines saves the
 * change alone. The change publishes no point event, so the ledger's listener never books the new intervals a second
 * time. The reading endpoints keep refusing a used reading (409 {@code READING_IN_USE}).
 * <p>
 * The readings of the requirement's examples, shifted one month back so that the first interval can lie in a closed
 * month while the second is open and no date is in the future: the meter installed on the 3rd three months ago with
 * 8 140 ("03.08"), 8 205 on the 5th two months ago ("05.09"), 8 270 on the 5th of the previous month ("05.10").
 * Electricity 4.32 per kWh: 65 kWh = 280.80, 75 kWh = 324.00, 55 kWh = 237.60, 130 kWh = 561.60.
 */
class ReadingRecalculationTests extends ReadingChargeSupport {

    private static final String RECALCULATIONS = "/api/v1/recalculations";
    private static final String REASON = "Виправлено показ лічильника";

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PlatformTransactionManager transactions;

    private YearMonth august;
    private YearMonth september;
    private YearMonth october;
    private UUID plot;

    @BeforeEach
    void plotSeven() {
        august = current().minusMonths(3);
        september = current().minusMonths(2);
        october = current().minusMonths(1);
        plot = plot("7", "600");
    }

    private String day(YearMonth month, int day) {
        return month.atDay(day).toString();
    }

    /** The meter and the readings of the examples. */
    private record Readings(UUID meter, UUID first, UUID second) {
    }

    /** 8 140 -> 8 205 -> 8 270, both intervals charged by the listener (280.80 each). */
    private Readings chargedReadings(int charges) throws Exception {
        tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200", "2020-01-01");
        UUID meter = electricityMeter(plot, day(august, 3), "8140");
        UUID first = entered(meter, day(september, 5), "8205");
        awaitCharges(charges / 2);
        UUID second = entered(meter, day(october, 5), "8270");
        awaitCharges(charges);
        return new Readings(meter, first, second);
    }

    // ---- requests

    private static Map<String, Object> correct(UUID reading, String takenOn, String value, String reason,
            String digest) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("readingId", reading);
        change.put("reading", Map.of("takenOn", takenOn, "value", value));
        return body("READING_CORRECT", change, reason, digest);
    }

    private static Map<String, Object> deletion(UUID reading, String reason, String digest) {
        return body("READING_DELETE", Map.of("readingId", reading), reason, digest);
    }

    private static Map<String, Object> entry(UUID meter, String takenOn, String value, String reason, String digest) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("meterId", meter);
        change.put("reading", Map.of("takenOn", takenOn, "value", value));
        return body("READING_ENTER", change, reason, digest);
    }

    private static Map<String, Object> body(String type, Map<String, Object> change, String reason, String digest) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cause", Map.of("type", type, "reading", change));
        if (reason != null) {
            body.put("reason", reason);
        }
        if (digest != null) {
            body.put("digest", digest);
        }
        return body;
    }

    private JsonNode preview(Map<String, Object> body) throws Exception {
        return body(send(chairman, post(RECALCULATIONS).param("preview", "true"), body).andExpect(status().isOk()));
    }

    private JsonNode book(Map<String, Object> body) throws Exception {
        return body(send(chairman, post(RECALCULATIONS).param("preview", "false"), body)
                .andExpect(status().isOk()));
    }

    // ---- reads

    private Map<String, Object> charge(UUID id) {
        return owner().queryForMap("select c.kind, c.source, c.amount::text as amount, c.service_from::text as "
                + "service_from, c.service_to::text as service_to, c.accrued_on::text as accrued_on, c.reason, "
                + "c.source_key, c.meter_id, c.adjusts_charge_id, c.reverses_charge_id, c.recalculation_id, "
                + "to_char(m.month, 'YYYY-MM') as billing_month from charge c join billing_month m "
                + "on m.id = c.billing_month_id where c.id = ?", id);
    }

    private static UUID id(JsonNode line, String field) {
        return UUID.fromString(line.get(field).asString());
    }

    private static List<String> texts(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }

    private static List<String> lines(JsonNode result, String... fields) {
        List<String> lines = new ArrayList<>();
        for (JsonNode line : result.get("lines")) {
            List<String> values = new ArrayList<>();
            for (String field : fields) {
                JsonNode value = line.get(field);
                values.add(value == null || value.isNull() ? "" : value.asString());
            }
            lines.add(String.join("|", values));
        }
        return lines;
    }

    private static JsonNode lineOf(JsonNode result, String plotNumber) {
        return result.get("lines").valueStream().filter(line -> plotNumber.equals(line.get("plotNumber").asString()))
                .findFirst().orElseThrow();
    }

    private String plotTotal() {
        return owner().queryForObject("select coalesce(sum(amount), 0)::text from charge where plot_id = ?",
                String.class, plot);
    }

    private int recalculationCount() {
        return owner().queryForObject("select count(*) from recalculation where tenant_id = ?", Integer.class,
                tenant);
    }

    /** The counted readings of the meter as {@code date|value}, oldest first. */
    private List<String> approvedReadings(UUID meter) {
        return owner().queryForList("select taken_on::text || '|' || value::text from meter_reading "
                + "where meter_id = ? and status = 'APPROVED' order by taken_on", String.class, meter);
    }

    private UUID approvedReading(UUID meter, String takenOn) {
        return owner().queryForObject("select id from meter_reading where meter_id = ? and taken_on = ?::date "
                + "and status = 'APPROVED'", UUID.class, meter, takenOn);
    }

    private List<Map<String, Object>> auditEntries(String action, int count) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(owner().queryForObject(
                "select count(*) from audit_entry where tenant_id = ? and action = ?", Integer.class, tenant, action))
                .isEqualTo(count));
        return owner().queryForList("select entity_type, entity_id, cast(changes as text) as changes from audit_entry "
                + "where tenant_id = ? and action = ?", tenant, action);
    }

    private Map<String, String[]> changes(Map<String, Object> entry) throws Exception {
        Map<String, String[]> values = new LinkedHashMap<>();
        json.readTree((String) entry.get("changes")).forEach(change -> values.put(change.get("field").asString(),
                new String[] {value(change, "old"), value(change, "new")}));
        return values;
    }

    private static String value(JsonNode change, String key) {
        return change.has(key) && !change.get(key).isNull() ? change.get(key).asString() : null;
    }

    /** Publishes the point event the reading endpoints would publish, as a redelivery would. */
    private void redeliver(UUID meter, String on) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> events.publishEvent(
                new PlotMeterPointsChanged(tenant, meter, plot, MeterResource.ELECTRICITY, LocalDate.parse(on),
                        PlotMeterPointsChanged.Cause.READING_CORRECTED)));
        awaitListeners();
    }

    /** A token of a fresh member of the cooperative whose only role is a custom role with the permissions. */
    private String customRole(String... permissions) {
        UUID member = UUID.randomUUID();
        String email = "custom-" + member + "@run.test";
        owner().update("insert into member (id, tenant_id, last_name, first_name) values (?, ?, 'Test', 'Custom')",
                member, tenant);
        insertContacts(tenant, member, email);
        UUID role = UUID.randomUUID();
        owner().update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, tenant, "Custom " + role);
        for (String permission : permissions) {
            owner().update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, ?)", tenant,
                    role, permission);
        }
        owner().update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)", tenant, member,
                role);
        return TestTokens.bearer(tenant, "sub-" + email, email);
    }

    // ---- corrected reading, both intervals open

    @Test
    void correcting8205To8215ReversesAndChargesBothOpenIntervalsWithTheChangeInOneBooking() throws Exception {
        Readings readings = chargedReadings(2);
        List<Map<String, Object>> originals = readingCharges(plot);
        // without the recalculation step the used reading is still refused
        send(chairman, put(READINGS + "/" + readings.first()), Map.of("takenOn", day(september, 5), "value", "8215"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("READING_IN_USE"));
        int before = chargeCount();

        JsonNode preview = preview(correct(readings.first(), day(september, 5), "8215", null, null));

        assertThat(preview.get("preview").asBoolean()).isTrue();
        assertThat(preview.get("changeApplied").asBoolean()).isFalse();
        assertThat(preview.get("cause").asString()).isEqualTo("READING");
        assertThat(preview.get("causeType").asString()).isEqualTo("READING_CORRECT");
        assertThat(preview.get("scope").get("meterId").asString()).isEqualTo(readings.meter().toString());
        assertThat(preview.get("scope").get("readingId").asString()).isEqualTo(readings.first().toString());
        assertThat(texts(preview.get("scope").get("plotIds"))).containsExactly(plot.toString());
        // 75 kWh = 324.00 and 55 kWh = 237.60
        assertThat(lines(preview, "action", "booked", "expected", "difference", "bookedIn", "serviceFrom",
                "serviceTo")).containsExactly(
                        "REVERSE_AND_RECHARGE|280.80|324.00|43.20|" + september + "|" + day(august, 4) + "|"
                                + day(september, 5),
                        "REVERSE_AND_RECHARGE|280.80|237.60|-43.20|" + october + "|" + day(september, 6) + "|"
                                + day(october, 5));
        assertThat(preview.get("lines").valueStream().map(line -> texts(line.get("reasons"))).toList())
                .allSatisfy(reasons -> assertThat(reasons).containsExactly("READING"));
        assertThat(preview.get("total").asString()).isEqualTo("0.00");
        // a preview changes neither the reading nor the ledger
        assertThat(approvedReadings(readings.meter())).containsExactly(day(september, 5) + "|8205.000",
                day(october, 5) + "|8270.000");
        assertThat(chargeCount()).isEqualTo(before);
        assertThat(recalculationCount()).isZero();

        JsonNode booked = book(correct(readings.first(), day(september, 5), "8215", REASON,
                preview.get("digest").asString()));

        // the correction's new row has a new id: the digest does not depend on it
        assertThat(booked.get("digest").asString()).isEqualTo(preview.get("digest").asString());
        assertThat(booked.get("booked").asBoolean()).isTrue();
        assertThat(booked.get("changeApplied").asBoolean()).isTrue();
        UUID recalculation = UUID.fromString(booked.get("id").asString());
        assertThat(approvedReadings(readings.meter())).containsExactly(day(september, 5) + "|8215.000",
                day(october, 5) + "|8270.000");
        UUID corrected = approvedReading(readings.meter(), day(september, 5));
        assertThat(owner().queryForObject("select status from meter_reading where id = ?", String.class,
                readings.first())).isEqualTo("SUPERSEDED");
        JsonNode first = booked.get("lines").get(0);
        JsonNode second = booked.get("lines").get(1);
        assertThat(charge(id(first, "reversalChargeId"))).containsEntry("kind", "REVERSAL")
                .containsEntry("source", "RECALCULATION").containsEntry("amount", "-280.80")
                .containsEntry("reason", REASON).containsEntry("reverses_charge_id", originals.get(0).get("id"))
                .containsEntry("recalculation_id", recalculation);
        // the new intervals read the corrected reading: their base keys are new
        assertThat(charge(id(first, "newChargeId"))).containsEntry("kind", "REGULAR")
                .containsEntry("source", "CONSUMPTION").containsEntry("amount", "324.00")
                .containsEntry("source_key", "CONSUMPTION:INSTALL:" + readings.meter() + ":" + corrected)
                .containsEntry("recalculation_id", recalculation).containsEntry("billing_month", september.toString())
                .containsEntry("accrued_on", day(september, 5));
        assertThat(usedOn(id(first, "newChargeId"))).containsExactly(day(august, 3), day(september, 5));
        assertThat(charge(id(second, "reversalChargeId"))).containsEntry("amount", "-280.80")
                .containsEntry("reverses_charge_id", originals.get(1).get("id"));
        assertThat(charge(id(second, "newChargeId"))).containsEntry("amount", "237.60")
                .containsEntry("source_key", "CONSUMPTION:" + corrected + ":" + readings.second())
                .containsEntry("billing_month", october.toString());
        assertThat(chargeCount()).isEqualTo(before + 4);
        assertThat(plotTotal()).isEqualTo("561.60");

        // the record: cause READING, the meter as its cause
        assertThat(owner().queryForMap("select cause, cause_ref_id, reason from recalculation where id = ?",
                recalculation)).containsEntry("cause", "READING").containsEntry("cause_ref_id", readings.meter())
                .containsEntry("reason", REASON);

        // the audit log: the correction (only the changed field, the recalculation) and RECALCULATION_BOOKED
        Map<String, Object> correctedEntry = auditEntries("READING_CORRECTED", 1).getFirst();
        assertThat(correctedEntry.get("entity_type")).isEqualTo("METER_READING");
        assertThat(correctedEntry.get("entity_id")).isEqualTo(readings.meter());
        Map<String, String[]> readingChanges = changes(correctedEntry);
        assertThat(readingChanges.keySet()).containsExactly("value", "recalculationId");
        assertThat(readingChanges.get("value")).containsExactly("8205.000", "8215.000");
        assertThat(readingChanges.get("recalculationId")).containsExactly(null, recalculation.toString());
        Map<String, String[]> bookedChanges = changes(auditEntries("RECALCULATION_BOOKED", 1).getFirst());
        assertThat(bookedChanges.get("cause")[1]).isEqualTo("READING_CORRECT");
        assertThat(bookedChanges.get("reason")[1]).isEqualTo(REASON);
        assertThat(bookedChanges.get("total")[1]).isEqualTo("0.00");

        // the listener books nothing afterwards, not even for a redelivered event: the new keys are charged
        awaitListeners();
        redeliver(readings.meter(), day(september, 5));
        assertThat(chargeCount()).isEqualTo(before + 4);
        // the same correction again changes nothing
        assertThat(preview(correct(corrected, day(september, 5), "8215", null, null)).get("lines")).isEmpty();
    }

    // ---- corrected reading, the first interval in a closed month

    @Test
    void correctingAReadingOfAClosedMonthAdjustsItsIntervalAndTheListenerBooksNoDuplicate() throws Exception {
        Readings readings = chargedReadings(2);
        UUID firstCharge = (UUID) readingCharges(plot).getFirst().get("id");
        closeMonth(september.toString());
        int before = chargeCount();

        JsonNode preview = preview(correct(readings.first(), day(september, 5), "8215", null, null));

        assertThat(lines(preview, "action", "booked", "expected", "difference", "bookedIn")).containsExactly(
                "ADJUST|280.80|324.00|43.20|" + october,
                "REVERSE_AND_RECHARGE|280.80|237.60|-43.20|" + october);

        JsonNode booked = book(correct(readings.first(), day(september, 5), "8215", REASON,
                preview.get("digest").asString()));

        UUID recalculation = UUID.fromString(booked.get("id").asString());
        UUID adjustment = id(booked.get("lines").get(0), "adjustmentChargeId");
        assertThat(charge(adjustment)).containsEntry("kind", "ADJUSTMENT").containsEntry("source", "RECALCULATION")
                .containsEntry("amount", "43.20").containsEntry("adjusts_charge_id", firstCharge)
                .containsEntry("recalculation_id", recalculation).containsEntry("billing_month", october.toString())
                .containsEntry("service_from", day(august, 4)).containsEntry("service_to", day(september, 5))
                .containsEntry("accrued_on", day(september, 5));
        assertThat(charge(id(booked.get("lines").get(1), "newChargeId"))).containsEntry("amount", "237.60");
        // the closed month keeps its charge
        assertThat(owner().queryForObject("select count(*) from charge where reverses_charge_id = ?", Integer.class,
                firstCharge)).isZero();

        // no point event was published: the listener does not charge the corrected interval a second time
        awaitListeners();
        assertThat(chargeCount()).isEqualTo(before + 3);
        assertThat(plotTotal()).isEqualTo("561.60");
        // not even for a redelivered event: the adjusted charge of the interval stands under the old reading's key
        redeliver(readings.meter(), day(september, 5));
        assertThat(chargeCount()).isEqualTo(before + 3);
        assertThat(plotTotal()).isEqualTo("561.60");
        assertThat(preview(correct(approvedReading(readings.meter(), day(september, 5)), day(september, 5), "8215",
                null, null)).get("lines")).isEmpty();
    }

    @Test
    void aCorrectionWhoseAmountsStayTheSameKeepsTheChargesAndARedeliveredEventBooksNoDuplicate() throws Exception {
        Readings readings = chargedReadings(2);
        int before = chargeCount();

        // 65.001 kWh and 64.999 kWh: both intervals still 280.80, nothing to recalculate
        JsonNode preview = preview(correct(readings.first(), day(september, 5), "8205.001", null, null));
        assertThat(preview.get("lines")).isEmpty();
        JsonNode saved = book(correct(readings.first(), day(september, 5), "8205.001", null,
                preview.get("digest").asString()));
        assertThat(saved.get("booked").asBoolean()).isFalse();
        assertThat(approvedReadings(readings.meter())).containsExactly(day(september, 5) + "|8205.001",
                day(october, 5) + "|8270.000");

        // the charges of both intervals stand under the old reading's keys: a redelivered event books nothing
        redeliver(readings.meter(), day(september, 5));
        assertThat(chargeCount()).isEqualTo(before);
        assertThat(plotTotal()).isEqualTo("561.60");
    }

    // ---- deleted reading: the intervals merge

    @Test
    void deletingAChargedReadingReversesBothIntervalsAndChargesTheMergedOne() throws Exception {
        Readings readings = chargedReadings(2);
        send(chairman, delete(READINGS + "/" + readings.first()), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("READING_IN_USE"));

        JsonNode preview = preview(deletion(readings.first(), null, null));

        assertThat(lines(preview, "action", "booked", "expected", "difference", "bookedIn")).containsExactlyInAnyOrder(
                "REVERSE|280.80|0.00|-280.80|" + september,
                "REVERSE|280.80|0.00|-280.80|" + october,
                "NEW|0.00|561.60|561.60|" + october);
        assertThat(preview.get("total").asString()).isEqualTo("0.00");
        assertThat(approvedReadings(readings.meter())).hasSize(2);

        JsonNode booked = book(deletion(readings.first(), REASON, preview.get("digest").asString()));

        assertThat(approvedReadings(readings.meter())).containsExactly(day(october, 5) + "|8270.000");
        assertThat(owner().queryForObject("select count(*) from meter_reading where id = ?", Integer.class,
                readings.first())).isZero();
        JsonNode merged = booked.get("lines").valueStream().filter(line -> "NEW".equals(line.get("action").asString()))
                .findFirst().orElseThrow();
        assertThat(charge(id(merged, "newChargeId"))).containsEntry("amount", "561.60")
                .containsEntry("service_from", day(august, 4)).containsEntry("service_to", day(october, 5))
                .containsEntry("source_key", "CONSUMPTION:INSTALL:" + readings.meter() + ":" + readings.second())
                .containsEntry("billing_month", october.toString());
        assertThat(plotTotal()).isEqualTo("561.60");
        Map<String, String[]> deleted = changes(auditEntries("READING_DELETED", 1).getFirst());
        assertThat(deleted.get("value")).containsExactly("8205.000", null);
        assertThat(deleted.get("recalculationId")[1]).isEqualTo(booked.get("id").asString());
        awaitListeners();
        assertThat(plotTotal()).isEqualTo("561.60");
    }

    @Test
    void deletingAReadingWhoseFirstIntervalIsInAClosedMonthAdjustsItAndChargesTheMergedOneInTheEarliestOpenMonth()
            throws Exception {
        Readings readings = chargedReadings(2);
        closeMonth(september.toString());

        JsonNode preview = preview(deletion(readings.first(), null, null));

        assertThat(lines(preview, "action", "difference", "bookedIn")).containsExactlyInAnyOrder(
                "ADJUST|-280.80|" + october,
                "REVERSE|-280.80|" + october,
                "NEW|561.60|" + october);

        JsonNode booked = book(deletion(readings.first(), REASON, preview.get("digest").asString()));

        JsonNode adjust = booked.get("lines").valueStream()
                .filter(line -> "ADJUST".equals(line.get("action").asString())).findFirst().orElseThrow();
        assertThat(charge(id(adjust, "adjustmentChargeId"))).containsEntry("kind", "ADJUSTMENT")
                .containsEntry("amount", "-280.80").containsEntry("billing_month", october.toString());
        JsonNode merged = booked.get("lines").valueStream()
                .filter(line -> "NEW".equals(line.get("action").asString())).findFirst().orElseThrow();
        assertThat(charge(id(merged, "newChargeId"))).containsEntry("amount", "561.60")
                .containsEntry("billing_month", october.toString());
        awaitListeners();
        assertThat(plotTotal()).isEqualTo("561.60");
    }

    // ---- a back-dated reading inside a charged interval

    @Test
    void aReadingEnteredInsideAChargedIntervalSplitsItAndThePreviewSavesNothing() throws Exception {
        tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200", "2020-01-01");
        UUID meter = electricityMeter(plot, day(august, 3), "8140");
        entered(meter, day(september, 5), "8205");
        awaitCharges(1);
        JsonNode refused = enter(meter, day(august, 20), "8170");
        assertThat(refused.get("error").get("code").asString()).isEqualTo("READING_IN_USE");

        JsonNode preview = preview(entry(meter, day(august, 20), "8170", null, null));

        // 30 kWh = 129.60 and 35 kWh = 151.20 instead of 65 kWh = 280.80
        assertThat(lines(preview, "action", "booked", "expected", "serviceFrom", "serviceTo"))
                .containsExactlyInAnyOrder(
                        "REVERSE|280.80|0.00|" + day(august, 4) + "|" + day(september, 5),
                        "NEW|0.00|129.60|" + day(august, 4) + "|" + day(august, 20),
                        "NEW|0.00|151.20|" + day(august, 21) + "|" + day(september, 5));
        // the new reading's id is generated anew by the booking: the preview does not name it
        assertThat(preview.get("scope").has("readingId")).isFalse();
        assertThat(approvedReadings(meter)).containsExactly(day(september, 5) + "|8205.000");

        JsonNode booked = book(entry(meter, day(august, 20), "8170", REASON, preview.get("digest").asString()));

        assertThat(booked.get("digest").asString()).isEqualTo(preview.get("digest").asString());
        UUID added = approvedReading(meter, day(august, 20));
        assertThat(booked.get("scope").get("readingId").asString()).isEqualTo(added.toString());
        assertThat(approvedReadings(meter)).containsExactly(day(august, 20) + "|8170.000",
                day(september, 5) + "|8205.000");
        assertThat(plotTotal()).isEqualTo("280.80");
        Map<String, String[]> entered = changes(auditEntries("READING_ENTERED", 2).stream()
                .filter(e -> ((String) e.get("changes")).contains("recalculationId")).findFirst().orElseThrow());
        assertThat(entered.get("value")).containsExactly(null, "8170.000");
        awaitListeners();
        assertThat(plotTotal()).isEqualTo("280.80");
    }

    // ---- fixed-percentage losses follow the interval

    @Test
    void fixedLossesFollowTheCorrectedInterval() throws Exception {
        Map<String, Object> losses = new LinkedHashMap<>();
        losses.put("lossesMode", "FIXED_PERCENT");
        losses.put("lossesPercent", "5");
        send(chairman, put("/api/v1/settings"), losses).andExpect(status().isOk());
        Readings readings = chargedReadings(4);

        JsonNode preview = preview(correct(readings.first(), day(september, 5), "8215", null, null));

        // 75 kWh = 324.00 with losses 3.75 kWh = 16.20; 55 kWh = 237.60 with losses 2.75 kWh = 11.88
        assertThat(lines(preview, "action", "booked", "expected")).containsExactly(
                "REVERSE_AND_RECHARGE|280.80|324.00", "REVERSE_AND_RECHARGE|14.04|16.20",
                "REVERSE_AND_RECHARGE|280.80|237.60", "REVERSE_AND_RECHARGE|14.04|11.88");

        JsonNode booked = book(correct(readings.first(), day(september, 5), "8215", REASON,
                preview.get("digest").asString()));

        JsonNode loss = booked.get("lines").get(1);
        assertThat(charge(id(loss, "reversalChargeId"))).containsEntry("amount", "-14.04");
        assertThat(charge(id(loss, "newChargeId"))).containsEntry("source", "LOSSES").containsEntry("amount", "16.20");
        awaitListeners();
        assertThat(plotTotal()).isEqualTo("589.68");
    }

    // ---- a proportional losses run that read the meter

    /** Quarter A 1 100 kWh with P1 600 and P2 400 read at the end of the previous month and before it. */
    private record LossesRun(UUID quarter, UUID run, UUID quarterReading, Map<UUID, UUID> charges, UUID p1) {
    }

    private LossesRun bookedLossesRun() throws Exception {
        YearMonth month = current().minusMonths(1);
        String installed = month.minusMonths(6).atDay(1).toString();
        String before = month.atDay(1).minusDays(1).toString();
        String to = month.atEndOfMonth().toString();
        owner().update("insert into cooperative_settings (tenant_id, losses_mode) values (?, 'PROPORTIONAL')", tenant);
        tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200", installed);
        UUID p1 = plot;
        UUID p2 = plot("2", "600");
        UUID quarter = treeMeter("QUARTER", "Квартал А", null, null, installed, before, 5000, to, 6100);
        treeMeter("PLOT", null, p1, quarter, installed, before, 1000, to, 1600);
        treeMeter("PLOT", null, p2, quarter, installed, before, 2000, to, 2400);
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("from", month.atDay(1).toString());
        range.put("to", to);
        UUID run = UUID.fromString(body(send(chairman, post("/api/v1/losses-runs?preview=false"), range)
                .andExpect(status().isOk())).get("runId").asString());
        Map<UUID, UUID> charges = new LinkedHashMap<>();
        owner().queryForList("select plot_id, id from charge where run_id = ? and kind = 'REGULAR'", run)
                .forEach(row -> charges.put((UUID) row.get("plot_id"), (UUID) row.get("id")));
        return new LossesRun(quarter, run, approvedReading(quarter, to), charges, p1);
    }

    private UUID treeMeter(String kind, String name, UUID meterPlot, UUID parent, String installed, String before,
            int beforeValue, String to, int toValue) {
        UUID id = UUID.randomUUID();
        owner().update("insert into meter (id, tenant_id, kind, resource, serial, name, plot_id, installed_on, "
                + "initial_value) values (?, ?, ?, 'ELECTRICITY', ?, ?, ?, ?::date, 0)", id, tenant, kind,
                "RR-" + id.toString().substring(0, 8), name, meterPlot, installed);
        if (parent != null) {
            owner().update("insert into meter_parent (tenant_id, meter_id, parent_meter_id, valid_from) "
                    + "values (?, ?, ?, ?::date)", tenant, id, parent, installed);
        }
        for (Object[] reading : new Object[][] {{before, beforeValue}, {to, toValue}}) {
            owner().update("insert into meter_reading (tenant_id, meter_id, taken_on, value, source, status) "
                    + "values (?, ?, ?::date, ?, 'ENTERED', 'APPROVED')", tenant, id, reading[0],
                    new BigDecimal((Integer) reading[1]));
        }
        return id;
    }

    @Test
    void correctingAReadingALossesRunOfAnOpenMonthUsedReversesTheRunAndBooksItAgain() throws Exception {
        LossesRun losses = bookedLossesRun();
        String to = current().minusMonths(1).atEndOfMonth().toString();
        send(chairman, put(READINGS + "/" + losses.quarterReading()), Map.of("takenOn", to, "value", "6200"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("READING_IN_USE"));

        JsonNode preview = preview(correct(losses.quarterReading(), to, "6200", null, null));

        // a quarter meter has no intervals of its own: only the run, 200 kWh of losses now
        assertThat(lines(preview, "plotNumber", "action", "booked", "expected", "runId")).containsExactly(
                "2|REVERSE_AND_RECHARGE|172.80|345.60|" + losses.run(),
                "7|REVERSE_AND_RECHARGE|259.20|518.40|" + losses.run());
        assertThat(texts(preview.get("scope").get("plotIds"))).isEmpty();
        assertThat(preview.get("scope").get("meterId").asString()).isEqualTo(losses.quarter().toString());

        JsonNode booked = book(correct(losses.quarterReading(), to, "6200", REASON, preview.get("digest").asString()));

        assertThat(owner().queryForObject("select status from losses_run where id = ?", String.class, losses.run()))
                .isEqualTo("REVERSED");
        UUID rebooked = owner().queryForObject("select id from losses_run where tenant_id = ? and status = 'BOOKED'",
                UUID.class, tenant);
        assertThat(owner().queryForObject("select total::text from losses_run where id = ?", String.class, rebooked))
                .isEqualTo("864.00");
        assertThat(owner().queryForList("select source from charge where kind = 'REVERSAL' and run_id = ?",
                String.class, losses.run())).containsOnly("RECALCULATION").hasSize(2);
        assertThat(lineOf(booked, "7").get("newChargeId").asString()).isEqualTo(owner().queryForObject(
                "select id::text from charge where run_id = ? and plot_id = ?", String.class, rebooked, losses.p1()));
        assertThat(approvedReadings(losses.quarter())).last().isEqualTo(to + "|6200.000");
        // the run reads the corrected reading now: a second correction finds the rebooked run
        assertThat(preview(correct(approvedReading(losses.quarter(), to), to, "6200", null, null)).get("lines"))
                .isEmpty();
    }

    @Test
    void correctingAReadingALossesRunOfAClosedMonthUsedAdjustsEachPlot() throws Exception {
        LossesRun losses = bookedLossesRun();
        closeMonth(current().minusMonths(1).toString());
        String to = current().minusMonths(1).atEndOfMonth().toString();

        JsonNode preview = preview(correct(losses.quarterReading(), to, "6200", null, null));

        assertThat(lines(preview, "plotNumber", "action", "difference", "bookedIn")).containsExactly(
                "2|ADJUST|172.80|" + current(), "7|ADJUST|259.20|" + current());

        JsonNode booked = book(correct(losses.quarterReading(), to, "6200", REASON, preview.get("digest").asString()));

        assertThat(owner().queryForObject("select status from losses_run where id = ?", String.class, losses.run()))
                .isEqualTo("BOOKED");
        assertThat(charge(id(lineOf(booked, "7"), "adjustmentChargeId"))).containsEntry("kind", "ADJUSTMENT")
                .containsEntry("amount", "259.20").containsEntry("adjusts_charge_id", losses.charges().get(plot))
                .containsEntry("billing_month", current().toString());
    }

    // ---- a change that alters no amount

    @Test
    void aCorrectionThatChangesNoAmountIsSavedWithoutARecalculationAndKeepsTheQueue() throws Exception {
        // the tariff starts after the meter: the interval has no tariff for every day and is queued, not charged
        tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200", day(september, 1));
        UUID meter = electricityMeter(plot, day(august, 3), "8140");
        UUID reading = entered(meter, day(september, 5), "8205");
        awaitListeners();
        assertThat(openItems()).singleElement().satisfies(item -> assertThat(item.get("item_key"))
                .isEqualTo("INTERVAL:" + meter + ":" + reading));

        JsonNode preview = preview(correct(reading, day(september, 5), "8210", null, null));
        assertThat(preview.get("lines")).isEmpty();

        JsonNode saved = book(correct(reading, day(september, 5), "8210", null, preview.get("digest").asString()));

        assertThat(saved.get("booked").asBoolean()).isFalse();
        assertThat(saved.get("changeApplied").asBoolean()).isTrue();
        assertThat(saved.has("id")).isFalse();
        assertThat(approvedReadings(meter)).containsExactly(day(september, 5) + "|8210.000");
        assertThat(chargeCount()).isZero();
        assertThat(recalculationCount()).isZero();
        // no point event follows the change: the recalculation queued the new interval as the listener would
        UUID corrected = approvedReading(meter, day(september, 5));
        awaitListeners();
        assertThat(openItems()).singleElement().satisfies(item -> {
            assertThat(item.get("item_key")).isEqualTo("INTERVAL:" + meter + ":" + corrected);
            assertThat(item.get("reason")).isEqualTo("NO_TARIFF_FOR_ALL_DAYS");
        });
        assertThat(owner().queryForObject("select status from not_charged_item where item_key = ?", String.class,
                "INTERVAL:" + meter + ":" + reading)).isEqualTo("OBSOLETE");
        Map<String, String[]> corrected01 = changes(auditEntries("READING_CORRECTED", 1).getFirst());
        assertThat(corrected01.keySet()).containsExactly("value");
        assertThat(owner().queryForObject("select count(*) from audit_entry where tenant_id = ? "
                + "and action = 'RECALCULATION_BOOKED'", Integer.class, tenant)).isZero();
    }

    // ---- refusals

    @Test
    void aStalePreviewAMissingReasonAndABrokenRuleAreRefusedAndTheReadingStaysAsItWas() throws Exception {
        Readings readings = chargedReadings(2);
        String digest = preview(correct(readings.first(), day(september, 5), "8215", null, null)).get("digest")
                .asString();
        int before = chargeCount();

        send(chairman, post(RECALCULATIONS).param("preview", "false"),
                correct(readings.first(), day(september, 5), "8215", null, digest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        // the rate changed after the preview
        owner().update("update tariff_version set rate = 5 where tariff_id in (select id from tariff "
                + "where tenant_id = ?)", tenant);
        send(chairman, post(RECALCULATIONS).param("preview", "false"),
                correct(readings.first(), day(september, 5), "8215", REASON, digest))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PREVIEW_OUTDATED"));
        // the reading rules hold, their fields under cause.reading
        send(chairman, post(RECALCULATIONS).param("preview", "true"),
                correct(readings.first(), day(september, 5), "8300", null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_OUT_OF_ORDER"))
                .andExpect(jsonPath("$.errors[0].field").value("cause.reading.reading.value"));
        send(chairman, post(RECALCULATIONS).param("preview", "true"),
                body("READING_CORRECT", Map.of("meterId", readings.meter()), null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("cause.reading.readingId"))
                .andExpect(jsonPath("$.errors[1].field").value("cause.reading.reading"));

        assertThat(approvedReadings(readings.meter())).containsExactly(day(september, 5) + "|8205.000",
                day(october, 5) + "|8270.000");
        assertThat(chargeCount()).isEqualTo(before);
        assertThat(recalculationCount()).isZero();
    }

    @Test
    void theElectricianGets409OnTheReadingAnd403OnTheRecalculationAndOnlyAHolderOfBothPermissionsMayCorrect()
            throws Exception {
        Readings readings = chargedReadings(2);
        String electrician = customRole("READINGS_ENTER");
        String recalculationsOnly = customRole("CHARGES_VIEW", "RECALCULATION_RUN");

        send(electrician, put(READINGS + "/" + readings.first()), Map.of("takenOn", day(september, 5),
                "value", "8215"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("READING_IN_USE"));
        for (String bearer : new String[] {electrician, treasurer, member, recalculationsOnly}) {
            for (String preview : new String[] {"true", "false"}) {
                send(bearer, post(RECALCULATIONS).param("preview", preview),
                        correct(readings.first(), day(september, 5), "8215", REASON, null))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            }
        }

        assertThat(approvedReadings(readings.meter())).containsExactly(day(september, 5) + "|8205.000",
                day(october, 5) + "|8270.000");
        assertThat(recalculationCount()).isZero();
        assertThat(readingCharges(plot)).extracting(c -> c.get("kind"), c -> c.get("amount"))
                .containsExactly(tuple("REGULAR", "280.80"), tuple("REGULAR", "280.80"));
    }
}
