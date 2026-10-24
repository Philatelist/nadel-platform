package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.meters.MeterResource;
import app.nadel.meters.PlotMeterPointsChanged;
import java.time.YearMonth;
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
 * The manual recalculation of the charges of plot meter intervals (feature 012 §2.1, slice 4): electricity and water
 * consumption and fixed-percentage losses, compared per interval (the meter, the source and the dates of its two
 * readings). A new rate reverses and charges again an interval of an open month and adjusts one of a closed month in
 * the earliest open month with the original's service period and debt date; fixed losses follow the consumption; a
 * queued interval is charged and its item resolved, an interval that can no longer be charged is reversed (open
 * month) or adjusted to 0.00 (closed month) and queued (a retry or the month run keeps it queued until it can be
 * charged, then charges it once under the recalculation's suffix, a closed month's in the earliest open month); water is charged for the connected days only while the water type is active; a second run finds nothing;
 * new charges and adjustments read the points of their interval ({@code meter_value_use}). Months are relative to the
 * current month in Europe/Kyiv: the readings are dated in the previous month ("September"), the current one
 * ("October") is open. Electricity 4.32 per kWh: 65 kWh = 280.80.
 */
class IntervalRecalculationTests extends ReadingChargeSupport {

    private static final String RECALCULATIONS = "/api/v1/recalculations";
    private static final String REASON = "Виправлено тариф на електроенергію";

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PlatformTransactionManager transactions;

    private YearMonth september;
    private YearMonth october;
    private UUID plot;

    @BeforeEach
    void plotSeven() {
        september = current().minusMonths(1);
        october = current();
        plot = plot("7", "600");
    }

    private String day(YearMonth month, int day) {
        return month.atDay(day).toString();
    }

    private Map<String, Object> manual(List<UUID> types, String reason, String digest) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("from", september.toString());
        scope.put("to", october.toString());
        scope.put("chargeTypeIds", types);
        scope.put("plotIds", List.of(plot));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cause", Map.of("type", "MANUAL", "manual", scope));
        if (reason != null) {
            body.put("reason", reason);
        }
        if (digest != null) {
            body.put("digest", digest);
        }
        return body;
    }

    private Map<String, Object> electricityScope(String reason, String digest) {
        return manual(List.of(typeId("ELECTRICITY")), reason, digest);
    }

    private JsonNode preview(Map<String, Object> body) throws Exception {
        return body(send(chairman, post(RECALCULATIONS).param("preview", "true"), body).andExpect(status().isOk()));
    }

    private JsonNode book(Map<String, Object> body) throws Exception {
        return body(send(chairman, post(RECALCULATIONS).param("preview", "false"), body)
                .andExpect(status().isOk()));
    }

    private Map<String, Object> charge(UUID id) {
        return owner().queryForMap("select c.kind, c.source, c.amount::text as amount, c.service_from::text as "
                + "service_from, c.service_to::text as service_to, c.accrued_on::text as accrued_on, c.reason, "
                + "c.source_key, c.meter_id, c.adjusts_charge_id, c.reverses_charge_id, c.recalculation_id, "
                + "to_char(m.month, 'YYYY-MM') as billing_month from charge c join billing_month m "
                + "on m.id = c.billing_month_id where c.id = ?", id);
    }

    private List<String> partRates(UUID charge) {
        return owner().queryForList("select rate::text from charge_part where charge_id = ? order by rate",
                String.class, charge);
    }

    private static List<String> texts(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }

    private void fixedLossesOfFivePercent() throws Exception {
        Map<String, Object> losses = new LinkedHashMap<>();
        losses.put("lossesMode", "FIXED_PERCENT");
        losses.put("lossesPercent", "5");
        send(chairman, put("/api/v1/settings"), losses).andExpect(status().isOk());
    }

    // ---- a new rate: open month and closed month

    @Test
    void aNewRateReversesAndChargesAnIntervalOfAnOpenMonthAndASecondRunFindsNothing() throws Exception {
        Tariff electricity = tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200", "2020-01-01");
        UUID meter = electricityMeter(plot, day(september, 3), "8140");
        UUID reading = entered(meter, day(september, 20), "8205");
        awaitCharges(1);
        UUID original = (UUID) readingCharges(plot).getFirst().get("id");
        // the rate is 5.00 from the 1st of September: 65 kWh = 325.00
        version(electricity.id(), "PER_KWH", "MONTHLY", "5.0000", day(september, 1), null);

        JsonNode preview = preview(electricityScope(null, null));

        assertThat(preview.get("lines")).singleElement().satisfies(line -> {
            assertThat(line.get("action").asString()).isEqualTo("REVERSE_AND_RECHARGE");
            assertThat(line.get("originalChargeId").asString()).isEqualTo(original.toString());
            assertThat(line.get("booked").asString()).isEqualTo("280.80");
            assertThat(line.get("expected").asString()).isEqualTo("325.00");
            assertThat(line.get("difference").asString()).isEqualTo("44.20");
            assertThat(line.get("bookedIn").asString()).isEqualTo(september.toString());
            assertThat(line.get("serviceFrom").asString()).isEqualTo(day(september, 4));
            assertThat(line.get("serviceTo").asString()).isEqualTo(day(september, 20));
            assertThat(texts(line.get("reasons"))).containsExactly("TARIFF_VERSION");
        });
        assertThat(preview.get("total").asString()).isEqualTo("44.20");
        int before = chargeCount();

        JsonNode booked = book(electricityScope(REASON, preview.get("digest").asString()));

        UUID recalculation = UUID.fromString(booked.get("id").asString());
        JsonNode line = booked.get("lines").get(0);
        UUID reversal = UUID.fromString(line.get("reversalChargeId").asString());
        UUID recharged = UUID.fromString(line.get("newChargeId").asString());
        assertThat(chargeCount()).isEqualTo(before + 2);
        assertThat(charge(reversal)).containsEntry("kind", "REVERSAL").containsEntry("source", "RECALCULATION")
                .containsEntry("amount", "-280.80").containsEntry("reason", REASON)
                .containsEntry("reverses_charge_id", original).containsEntry("recalculation_id", recalculation)
                .containsEntry("billing_month", september.toString());
        // the interval's base key exists: the new charge gets the recalculation's suffix
        assertThat(charge(recharged)).containsEntry("kind", "REGULAR").containsEntry("source", "CONSUMPTION")
                .containsEntry("amount", "325.00").containsEntry("meter_id", meter)
                .containsEntry("source_key", "CONSUMPTION:INSTALL:" + meter + ":" + reading + "#r" + recalculation)
                .containsEntry("recalculation_id", recalculation).containsEntry("billing_month", september.toString())
                .containsEntry("service_from", day(september, 4)).containsEntry("accrued_on", day(september, 20));
        assertThat(partRates(recharged)).containsExactly("5.0000");
        // the new charge reads the two points of its interval, so the reading stays in use
        assertThat(usedOn(recharged)).containsExactly(day(september, 3), day(september, 20));
        send(chairman, put(READINGS + "/" + reading), Map.of("takenOn", day(september, 20), "value", "8210"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("READING_IN_USE"));

        // the same recalculation again: no changes, nothing to book
        JsonNode again = preview(electricityScope(null, null));
        assertThat(again.get("lines")).isEmpty();
        send(chairman, post(RECALCULATIONS).param("preview", "false"),
                electricityScope(REASON, again.get("digest").asString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_RECALCULATE"));
        assertThat(chargeCount()).isEqualTo(before + 2);
        // the listener sees the interval as charged: a redelivered event books nothing
        new TransactionTemplate(transactions).executeWithoutResult(status -> events.publishEvent(
                new PlotMeterPointsChanged(tenant, meter, plot, MeterResource.ELECTRICITY,
                        september.atDay(20), PlotMeterPointsChanged.Cause.READING_ENTERED)));
        awaitListeners();
        assertThat(chargeCount()).isEqualTo(before + 2);
    }

    @Test
    void aNewRateAdjustsAnIntervalOfAClosedMonthInTheEarliestOpenMonth() throws Exception {
        Tariff electricity = tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200", "2020-01-01");
        UUID meter = electricityMeter(plot, day(september, 3), "8140");
        entered(meter, day(september, 20), "8205");
        awaitCharges(1);
        UUID original = (UUID) readingCharges(plot).getFirst().get("id");
        closeMonth(september.toString());
        version(electricity.id(), "PER_KWH", "MONTHLY", "5.0000", day(september, 1), null);

        JsonNode preview = preview(electricityScope(null, null));

        assertThat(preview.get("lines")).singleElement().satisfies(line -> {
            assertThat(line.get("action").asString()).isEqualTo("ADJUST");
            assertThat(line.get("difference").asString()).isEqualTo("44.20");
            assertThat(line.get("bookedIn").asString()).isEqualTo(october.toString());
        });

        JsonNode booked = book(electricityScope(REASON, preview.get("digest").asString()));

        UUID recalculation = UUID.fromString(booked.get("id").asString());
        UUID adjustment = UUID.fromString(booked.get("lines").get(0).get("adjustmentChargeId").asString());
        assertThat(charge(adjustment)).containsEntry("kind", "ADJUSTMENT").containsEntry("source", "RECALCULATION")
                .containsEntry("amount", "44.20").containsEntry("reason", REASON)
                .containsEntry("adjusts_charge_id", original).containsEntry("recalculation_id", recalculation)
                .containsEntry("meter_id", meter).containsEntry("billing_month", october.toString())
                .containsEntry("service_from", day(september, 4)).containsEntry("service_to", day(september, 20))
                .containsEntry("accrued_on", day(september, 20));
        assertThat(partRates(adjustment)).containsExactly("5.0000");
        assertThat(usedOn(adjustment)).containsExactly(day(september, 3), day(september, 20));
        // the closed month keeps its charge: no reversal
        assertThat(owner().queryForObject("select count(*) from charge where tenant_id = ? and kind = 'REVERSAL'",
                Integer.class, tenant)).isZero();
        assertThat(preview(electricityScope(null, null)).get("lines")).isEmpty();
    }

    // ---- fixed-percentage losses

    @Test
    void fixedLossesAreReversedAndChargedAgainWithTheConsumption() throws Exception {
        tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200", "2020-01-01");
        fixedLossesOfFivePercent();
        // 8 140 -> 8 205 -> 8 270: two intervals of 65 kWh (280.80) with losses of 3.25 kWh (14.04) each
        UUID meter = electricityMeter(plot, day(september, 3), "8140");
        UUID middle = entered(meter, day(september, 10), "8205");
        awaitCharges(2);
        entered(meter, day(september, 20), "8270");
        awaitCharges(4);
        // the middle value was 8 215 (the reading keeps its id and date: the intervals keep their identities)
        owner().update("update meter_reading set value = 8215 where id = ?", middle);

        JsonNode preview = preview(electricityScope(null, null));

        // 75 kWh = 324.00 with losses 3.75 kWh = 16.20; 55 kWh = 237.60 with losses 2.75 kWh = 11.88
        assertThat(preview.get("lines")).extracting(l -> l.get("action").asString(), l -> l.get("booked").asString(),
                l -> l.get("expected").asString(), l -> l.get("difference").asString())
                .containsExactly(
                        tuple("REVERSE_AND_RECHARGE", "280.80", "324.00", "43.20"),
                        tuple("REVERSE_AND_RECHARGE", "14.04", "16.20", "2.16"),
                        tuple("REVERSE_AND_RECHARGE", "280.80", "237.60", "-43.20"),
                        tuple("REVERSE_AND_RECHARGE", "14.04", "11.88", "-2.16"));
        assertThat(preview.get("lines").valueStream().map(l -> texts(l.get("reasons"))).toList())
                .allSatisfy(reasons -> assertThat(reasons).containsExactly("READING"));
        assertThat(preview.get("total").asString()).isEqualTo("0.00");

        JsonNode booked = book(electricityScope(REASON, preview.get("digest").asString()));

        JsonNode losses = booked.get("lines").get(1);
        assertThat(charge(UUID.fromString(losses.get("reversalChargeId").asString())))
                .containsEntry("amount", "-14.04").containsEntry("source", "RECALCULATION");
        UUID newLosses = UUID.fromString(losses.get("newChargeId").asString());
        assertThat(charge(newLosses)).containsEntry("source", "LOSSES").containsEntry("amount", "16.20");
        assertThat(usedOn(newLosses)).containsExactly(day(september, 3), day(september, 10));
        // 324.00 + 16.20 + 237.60 + 11.88: the total stays 589.68
        assertThat(owner().queryForObject("select sum(amount)::text from charge where plot_id = ?", String.class,
                plot)).isEqualTo("589.68");
        assertThat(preview(electricityScope(null, null)).get("lines")).isEmpty();
    }

    // ---- the queue

    @Test
    void aQueuedIntervalIsChargedAfterABackDatedVersionAndItsItemResolved() throws Exception {
        YearMonth august = current().minusMonths(2);
        Tariff electricity = tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200",
                day(september, 1));
        UUID meter = electricityMeter(plot, day(august, 20), "0");
        UUID reading = entered(meter, day(september, 5), "16");
        awaitListeners();
        assertThat(chargeCount()).isZero();
        assertThat(openItems()).singleElement()
                .satisfies(item -> assertThat(item.get("reason")).isEqualTo("NO_TARIFF_FOR_ALL_DAYS"));
        // nothing booked yet and still not chargeable: no changes
        assertThat(preview(electricityScope(null, null)).get("lines")).isEmpty();
        version(electricity.id(), "PER_KWH", "MONTHLY", "4.3200", day(august, 1), null);

        JsonNode preview = preview(electricityScope(null, null));

        assertThat(preview.get("lines")).singleElement().satisfies(line -> {
            assertThat(line.get("action").asString()).isEqualTo("NEW");
            assertThat(line.get("expected").asString()).isEqualTo("69.12");
            assertThat(line.get("bookedIn").asString()).isEqualTo(september.toString());
        });
        assertThat(preview.get("notCharged")).isEmpty();

        JsonNode booked = book(electricityScope(REASON, preview.get("digest").asString()));

        UUID created = UUID.fromString(booked.get("lines").get(0).get("newChargeId").asString());
        assertThat(charge(created)).containsEntry("amount", "69.12")
                .containsEntry("source_key", "CONSUMPTION:INSTALL:" + meter + ":" + reading);
        assertThat(usedOn(created)).containsExactly(day(august, 20), day(september, 5));
        assertThat(openItems()).isEmpty();
        assertThat(owner().queryForObject("select status from not_charged_item where item_key = ?", String.class,
                "INTERVAL:" + meter + ":" + reading)).isEqualTo("RESOLVED");
    }

    @Test
    void anIntervalThatCanNoLongerBeChargedIsReversedAndQueued() throws Exception {
        Tariff electricity = tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200", "2020-01-01");
        UUID meter = electricityMeter(plot, day(september, 3), "8140");
        UUID reading = entered(meter, day(september, 20), "8205");
        awaitCharges(1);
        // the version turns out to start on the 15th: the interval 04.09-20.09 has no tariff for every day
        owner().update("update tariff_version set valid_from = ?::date where id = ?", day(september, 15),
                electricity.version());

        JsonNode preview = preview(electricityScope(null, null));

        assertThat(preview.get("lines")).singleElement().satisfies(line -> {
            assertThat(line.get("action").asString()).isEqualTo("REVERSE");
            assertThat(line.get("expected").asString()).isEqualTo("0.00");
            assertThat(line.get("difference").asString()).isEqualTo("-280.80");
            assertThat(line.get("notChargedReason").asString()).isEqualTo("NO_TARIFF_FOR_ALL_DAYS");
        });
        assertThat(preview.get("notCharged")).singleElement().satisfies(item -> {
            assertThat(item.get("kind").asString()).isEqualTo("INTERVAL");
            assertThat(item.get("reason").asString()).isEqualTo("NO_TARIFF_FOR_ALL_DAYS");
            assertThat(item.get("meterId").asString()).isEqualTo(meter.toString());
            assertThat(item.get("from").asString()).isEqualTo(day(september, 4));
            assertThat(item.get("to").asString()).isEqualTo(day(september, 20));
            assertThat(item.get("tariffs").get(0).get("id").asString()).isEqualTo(electricity.id().toString());
        });

        book(electricityScope(REASON, preview.get("digest").asString()));

        assertThat(openItems()).singleElement().satisfies(item -> {
            assertThat(item.get("item_key")).isEqualTo("INTERVAL:" + meter + ":" + reading);
            assertThat(item.get("kind")).isEqualTo("INTERVAL");
            assertThat(item.get("reason")).isEqualTo("NO_TARIFF_FOR_ALL_DAYS");
            assertThat(item.get("charge_type_id")).isEqualTo(typeId("ELECTRICITY"));
            assertThat(item.get("params").toString()).contains("INSTALL:" + meter);
        });
        assertThat(owner().queryForObject("select sum(amount)::text from charge where plot_id = ?", String.class,
                plot)).isEqualTo("0.00");
    }

    /** An interval charged, reversed by a recalculation that can no longer charge it, and queued. */
    private record ReversedInterval(Tariff electricity, UUID meter, UUID reading, UUID item, UUID recalculation) {

        String baseKey() {
            return "CONSUMPTION:INSTALL:" + meter + ":" + reading;
        }
    }

    private ReversedInterval reversedAndQueued() throws Exception {
        Tariff electricity = tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200", "2020-01-01");
        UUID meter = electricityMeter(plot, day(september, 3), "8140");
        UUID reading = entered(meter, day(september, 20), "8205");
        awaitCharges(1);
        owner().update("update tariff_version set valid_from = ?::date where id = ?", day(september, 15),
                electricity.version());
        JsonNode preview = preview(electricityScope(null, null));
        JsonNode booked = book(electricityScope(REASON, preview.get("digest").asString()));
        assertThat(booked.get("lines").get(0).get("action").asString()).isEqualTo("REVERSE");
        UUID item = (UUID) openItems().getFirst().get("id");
        return new ReversedInterval(electricity, meter, reading, item,
                UUID.fromString(booked.get("id").asString()));
    }

    /** The version starts on 1 January 2020 again: the interval can be charged. */
    private void fixTheVersion(ReversedInterval reversed) {
        owner().update("update tariff_version set valid_from = '2020-01-01' where id = ?",
                reversed.electricity().version());
    }

    private List<Map<String, Object>> regularCharges() {
        return readingCharges(plot).stream().filter(c -> "REGULAR".equals(c.get("kind"))).toList();
    }

    private String plotTotal() {
        return owner().queryForObject("select sum(amount)::text from charge where plot_id = ?", String.class, plot);
    }

    @Test
    void aRetryKeepsAnIntervalAReversalQueuedOpenUntilItCanBeChargedAndThenChargesItOnce() throws Exception {
        ReversedInterval reversed = reversedAndQueued();

        // still no tariff for every day: the item stays open and nothing is booked
        JsonNode unresolved = body(send(chairman, post(RETRY.formatted(reversed.item())), null)
                .andExpect(status().isOk()));
        assertThat(unresolved.get("resolved").asBoolean()).isFalse();
        assertThat(openItems()).singleElement().satisfies(item -> assertThat(item.get("id"))
                .isEqualTo(reversed.item()));
        assertThat(plotTotal()).isEqualTo("0.00");

        fixTheVersion(reversed);
        JsonNode resolved = body(send(chairman, post(RETRY.formatted(reversed.item())), null)
                .andExpect(status().isOk()));

        assertThat(resolved.get("resolved").asBoolean()).isTrue();
        assertThat(openItems()).isEmpty();
        // the base key is the reversed charge's: the new one goes under the recalculation's suffix
        assertThat(regularCharges()).extracting(c -> c.get("source_key"), c -> c.get("amount"))
                .containsExactly(tuple(reversed.baseKey(), "280.80"),
                        tuple(reversed.baseKey() + "#r" + reversed.recalculation(), "280.80"));
        assertThat(plotTotal()).isEqualTo("280.80");
        // charged now: a redelivered event books nothing
        int before = chargeCount();
        new TransactionTemplate(transactions).executeWithoutResult(status -> events.publishEvent(
                new PlotMeterPointsChanged(tenant, reversed.meter(), plot, MeterResource.ELECTRICITY,
                        september.atDay(20), PlotMeterPointsChanged.Cause.READING_ENTERED)));
        awaitListeners();
        assertThat(chargeCount()).isEqualTo(before);
    }

    @Test
    void theMonthRunKeepsAnIntervalAReversalQueuedOpenUntilItCanBeChargedAndThenChargesItOnce() throws Exception {
        ReversedInterval reversed = reversedAndQueued();

        send(chairman, post(RUN.formatted(october, false)), null).andExpect(status().isOk());
        assertThat(openItems()).singleElement().satisfies(item -> assertThat(item.get("id"))
                .isEqualTo(reversed.item()));
        assertThat(plotTotal()).isEqualTo("0.00");

        fixTheVersion(reversed);
        send(chairman, post(RUN.formatted(october, false)), null).andExpect(status().isOk());

        assertThat(openItems()).isEmpty();
        assertThat(owner().queryForObject("select status from not_charged_item where id = ?", String.class,
                reversed.item())).isEqualTo("RESOLVED");
        assertThat(regularCharges()).extracting(c -> c.get("source_key"), c -> c.get("amount"))
                .containsExactly(tuple(reversed.baseKey(), "280.80"),
                        tuple(reversed.baseKey() + "#r" + reversed.recalculation(), "280.80"));
        int before = chargeCount();
        send(chairman, post(RUN.formatted(october, false)), null).andExpect(status().isOk());
        assertThat(chargeCount()).isEqualTo(before);
        assertThat(plotTotal()).isEqualTo("280.80");
    }

    // ---- an interval of a closed month a recalculation adjusted to 0.00 and queued

    /**
     * September charged and closed, then the version turns out to start on the 15th: the recalculation adjusts the
     * interval to 0.00 in October (the closed month keeps its charge) and queues it.
     */
    private ReversedInterval adjustedToZeroAndQueued() throws Exception {
        Tariff electricity = tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200", "2020-01-01");
        UUID meter = electricityMeter(plot, day(september, 3), "8140");
        UUID reading = entered(meter, day(september, 20), "8205");
        awaitCharges(1);
        closeMonth(september.toString());
        owner().update("update tariff_version set valid_from = ?::date where id = ?", day(september, 15),
                electricity.version());
        JsonNode preview = preview(electricityScope(null, null));
        JsonNode booked = book(electricityScope(REASON, preview.get("digest").asString()));
        assertThat(booked.get("lines")).singleElement().satisfies(line -> {
            assertThat(line.get("action").asString()).isEqualTo("ADJUST");
            assertThat(line.get("difference").asString()).isEqualTo("-280.80");
            assertThat(line.get("bookedIn").asString()).isEqualTo(october.toString());
        });
        UUID item = (UUID) openItems().getFirst().get("id");
        return new ReversedInterval(electricity, meter, reading, item,
                UUID.fromString(booked.get("id").asString()));
    }

    /** Charged once more in October, with its own service period, under the recalculation's suffix. */
    private void chargedAgainInTheEarliestOpenMonth(ReversedInterval adjusted) throws Exception {
        assertThat(regularCharges()).extracting(c -> c.get("source_key"), c -> c.get("amount"),
                c -> c.get("billing_month"), c -> c.get("service_from"), c -> c.get("service_to"))
                .containsExactly(
                        tuple(adjusted.baseKey(), "280.80", september.toString(), day(september, 4),
                                day(september, 20)),
                        tuple(adjusted.baseKey() + "#r" + adjusted.recalculation(), "280.80", october.toString(),
                                day(september, 4), day(september, 20)));
        assertThat(plotTotal()).isEqualTo("280.80");
        assertThat(openItems()).isEmpty();
        assertThat(owner().queryForObject("select status from not_charged_item where id = ?", String.class,
                adjusted.item())).isEqualTo("RESOLVED");
        // a further run, a redelivered event or a recalculation books nothing
        int before = chargeCount();
        send(chairman, post(RUN.formatted(october, false)), null).andExpect(status().isOk());
        new TransactionTemplate(transactions).executeWithoutResult(status -> events.publishEvent(
                new PlotMeterPointsChanged(tenant, adjusted.meter(), plot, MeterResource.ELECTRICITY,
                        september.atDay(20), PlotMeterPointsChanged.Cause.READING_ENTERED)));
        awaitListeners();
        assertThat(preview(electricityScope(null, null)).get("lines")).isEmpty();
        assertThat(chargeCount()).isEqualTo(before);
    }

    @Test
    void aRetryKeepsAClosedMonthIntervalAdjustedToZeroQueuedOpenUntilItCanBeChargedAndThenChargesItOnce()
            throws Exception {
        ReversedInterval adjusted = adjustedToZeroAndQueued();

        // still no tariff for every day: a retry and the month run keep the item open and book nothing
        JsonNode unresolved = body(send(chairman, post(RETRY.formatted(adjusted.item())), null)
                .andExpect(status().isOk()));
        assertThat(unresolved.get("resolved").asBoolean()).isFalse();
        send(chairman, post(RUN.formatted(october, false)), null).andExpect(status().isOk());
        assertThat(openItems()).singleElement().satisfies(item -> assertThat(item.get("id"))
                .isEqualTo(adjusted.item()));
        assertThat(plotTotal()).isEqualTo("0.00");

        fixTheVersion(adjusted);
        JsonNode resolved = body(send(chairman, post(RETRY.formatted(adjusted.item())), null)
                .andExpect(status().isOk()));

        assertThat(resolved.get("resolved").asBoolean()).isTrue();
        chargedAgainInTheEarliestOpenMonth(adjusted);
    }

    @Test
    void theMonthRunKeepsAClosedMonthIntervalAdjustedToZeroQueuedOpenUntilItCanBeChargedAndThenChargesItOnce()
            throws Exception {
        ReversedInterval adjusted = adjustedToZeroAndQueued();

        send(chairman, post(RUN.formatted(october, false)), null).andExpect(status().isOk());
        assertThat(openItems()).singleElement().satisfies(item -> assertThat(item.get("id"))
                .isEqualTo(adjusted.item()));
        assertThat(plotTotal()).isEqualTo("0.00");

        fixTheVersion(adjusted);
        send(chairman, post(RUN.formatted(october, false)), null).andExpect(status().isOk());

        chargedAgainInTheEarliestOpenMonth(adjusted);
    }

    // ---- water

    @Test
    void waterIsChargedForTheConnectedDaysOnlyWhileTheWaterTypeIsActive() throws Exception {
        activateWater();
        tariff("WATER", "Вода", "PER_M3", "MONTHLY", "20.0000", "2020-01-01");
        // 02.09-21.09 (20 days) 20 m3, connected from 12.09: 10 days = 10 m3 x 20 = 200.00
        connectToWater(plot, day(september, 12), null);
        UUID meter = meter(plot, "WATER", day(september, 1), "0");
        entered(meter, day(september, 21), "20");
        awaitCharges(1);
        assertThat(readingCharges(plot)).singleElement()
                .satisfies(charge -> assertThat(charge.get("amount")).isEqualTo("200.00"));
        // the connection was entered late: the plot is connected from the 2nd
        owner().update("update plot_water_connection set valid_from = ?::date where plot_id = ?", day(september, 2),
                plot);

        // while water is inactive it is not in the scope: its charges are neither reversed nor charged
        owner().update("update charge_type set active = false where id = ?", typeId("WATER"));
        assertThat(preview(manual(List.of(), null, null)).get("lines")).isEmpty();
        owner().update("update charge_type set active = true where id = ?", typeId("WATER"));

        JsonNode preview = preview(manual(List.of(), null, null));

        assertThat(preview.get("lines")).singleElement().satisfies(line -> {
            assertThat(line.get("action").asString()).isEqualTo("REVERSE_AND_RECHARGE");
            assertThat(line.get("chargeType").get("code").asString()).isEqualTo("WATER");
            assertThat(line.get("booked").asString()).isEqualTo("200.00");
            assertThat(line.get("expected").asString()).isEqualTo("400.00");
            assertThat(texts(line.get("reasons"))).containsExactly("WATER_CONNECTION");
        });

        JsonNode booked = book(manual(List.of(), REASON, preview.get("digest").asString()));

        UUID recharged = UUID.fromString(booked.get("lines").get(0).get("newChargeId").asString());
        assertThat(charge(recharged)).containsEntry("source", "CONSUMPTION").containsEntry("amount", "400.00")
                .containsEntry("service_from", day(september, 2)).containsEntry("service_to", day(september, 21));
        assertThat(usedOn(recharged)).containsExactly(day(september, 1), day(september, 21));
    }
}
