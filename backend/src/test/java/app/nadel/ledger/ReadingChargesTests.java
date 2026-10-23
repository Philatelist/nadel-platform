package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.meters.MeterResource;
import app.nadel.meters.PlotMeterPointsChanged;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Feature 011 §2.4: every approved reading of a plot meter charges the interval from the previous point (the days rule,
 * the tariff, one rounding), asynchronously through the outbox; a replacement and a removal charge the old meter's last
 * interval; a reading in a closed month is booked in the next open month; what cannot be charged goes to the "not
 * charged" queue and is charged by a retry or the month run once the cause is fixed, and an item whose interval vanished
 * (a deletion joined it with the next) becomes obsolete; water charges nothing while its
 * charge type is inactive; fixed-percentage losses add a losses charge; a redelivered event charges nothing twice.
 */
class ReadingChargesTests extends ReadingChargeSupport {

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void anEnteredReadingChargesTheIntervalFromThePreviousPoint() throws Exception {
        UUID plot = plot("7", "600");
        electricity();
        // previous point 22.08: 112.4, reading 03.09: 205.7 -> 93.3 kWh x 4.32 = 403.06, 23.08-03.09 (12 days)
        UUID meter = electricityMeter(plot, "2026-08-22", "112.4");
        UUID reading = entered(meter, "2026-09-03", "205.7");
        awaitCharges(1);

        List<Map<String, Object>> charges = readingCharges(plot);
        assertThat(charges).singleElement().satisfies(charge -> {
            assertThat(charge.get("amount")).isEqualTo("403.06");
            assertThat(charge.get("kind")).isEqualTo("REGULAR");
            assertThat(charge.get("source")).isEqualTo("CONSUMPTION");
            assertThat(charge.get("service_from")).isEqualTo("2026-08-23");
            assertThat(charge.get("service_to")).isEqualTo("2026-09-03");
            assertThat(charge.get("accrued_on")).isEqualTo("2026-09-03");
            assertThat(charge.get("billing_month")).isEqualTo("2026-09");
            assertThat(charge.get("source_key")).isEqualTo("CONSUMPTION:INSTALL:" + meter + ":" + reading);
            assertThat(charge.get("meter_id")).isEqualTo(meter);
            assertThat(charge.get("created_by")).isNull();
        });
        UUID chargeId = (UUID) charges.getFirst().get("id");
        assertThat(usedOn(chargeId)).containsExactly("2026-08-22", "2026-09-03");
        assertThat(owner().queryForList("select days, units_num / units_den as units, rate::text as rate "
                + "from charge_part where charge_id = ?", chargeId)).singleElement().satisfies(part -> {
                    assertThat(part.get("days")).isEqualTo(12);
                    assertThat(part.get("units").toString()).startsWith("93.3");
                    assertThat(part.get("rate")).isEqualTo("4.3200");
                });

        JsonNode account = body(send(chairman, get(ACCOUNT.formatted(plot)), null).andExpect(status().isOk()));
        JsonNode calculation = account.get("charges").get(0).get("calculation");
        assertThat(calculation.get("amount").asString()).isEqualTo("403.06");
        JsonNode part = calculation.get("parts").get(0);
        assertThat(part.get("quantity").asString()).isEqualTo("93.300");
        assertThat(part.get("unit").asString()).isEqualTo("KWH");
        assertThat(part.get("rate").asString()).isEqualTo("4.3200");
        assertThat(part.get("days").asInt()).isEqualTo(12);
        assertThat(part.get("from").asString()).isEqualTo("2026-08-23");
        assertThat(part.get("to").asString()).isEqualTo("2026-09-03");
        assertThat(calculation.get("readings")).hasSize(2);
        assertThat(calculation.get("readings").get(0).get("value").asString()).isEqualTo("112.400");
        assertThat(calculation.get("readings").get(1).get("takenOn").asString()).isEqualTo("2026-09-03");
    }

    @Test
    void aMembersReadingChargesNothingUntilItIsApproved() throws Exception {
        UUID plot = plot("7", "600");
        electricity();
        UUID meter = electricityMeter(plot, "2026-08-22", "112.4");
        UUID memberId = owner().queryForObject("select id from member where tenant_id = ? and email like 'member-%'",
                UUID.class, tenant);
        owner().update("insert into ownership (tenant_id, plot_id, member_id, share_numerator, share_denominator, "
                + "valid_from) values (?, ?, ?, 1, 1, date '2020-01-01')", tenant, plot, memberId);

        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("meterId", meter.toString());
        submission.put("takenOn", "2026-09-03");
        submission.put("value", "205.7");
        UUID pending = UUID.fromString(body(send(member, post("/api/v1/plots/%s/readings".formatted(plot)),
                submission).andExpect(status().isCreated())).get("reading").get("id").asString());
        awaitListeners();
        assertThat(chargeCount()).isZero();

        send(chairman, post(READINGS + "/" + pending + "/approve"), null).andExpect(status().isOk());
        awaitCharges(1);
        assertThat(readingCharges(plot)).singleElement()
                .satisfies(charge -> assertThat(charge.get("amount")).isEqualTo("403.06"));
    }

    @Test
    void aReplacementChargesTheLastIntervalOfTheOldMeterAndTheFirstOfTheNewOne() throws Exception {
        UUID plot = plot("7", "600");
        electricity();
        UUID old = electricityMeter(plot, "2026-07-01", "8100");
        UUID first = entered(old, "2026-08-03", "8140");
        awaitCharges(1);
        // removed 20.08 at 8 205 (65 kWh = 280.80); the new meter installed at 3 reads 69 on 05.09 (66 kWh = 285.12)
        Map<String, Object> replacement = new LinkedHashMap<>();
        replacement.put("replacedOn", "2026-08-20");
        replacement.put("finalReading", "8205");
        replacement.put("serial", "RC-NEW-" + UUID.randomUUID().toString().substring(0, 6));
        replacement.put("initialReading", "3");
        UUID next = UUID.fromString(body(send(chairman, post(METERS + "/" + old + "/replace"), replacement)
                .andExpect(status().isCreated())).get("id").asString());
        awaitCharges(2);
        entered(next, "2026-09-05", "69");
        awaitCharges(3);

        assertThat(readingCharges(plot)).extracting(c -> c.get("amount"), c -> c.get("meter_id"),
                c -> c.get("service_from"), c -> c.get("service_to"))
                .containsExactly(
                        tuple("172.80", old, "2026-07-02", "2026-08-03"),
                        tuple("280.80", old, "2026-08-04", "2026-08-20"),
                        tuple("285.12", next, "2026-08-21", "2026-09-05"));
        assertThat(readingCharges(plot).get(1).get("source_key")).isEqualTo(
                "CONSUMPTION:" + first + ":REMOVAL:" + old);
    }

    @Test
    void aRemovalChargesTheIntervalUpToTheFinalValueAndTheInitialValueChargesNothing() throws Exception {
        UUID plot = plot("7", "600");
        electricity();
        UUID meter = electricityMeter(plot, "2026-08-01", "0");
        awaitListeners();
        assertThat(chargeCount()).isZero();

        send(chairman, post(METERS + "/" + meter + "/remove"),
                Map.of("removedOn", "2026-08-11", "finalReading", "10")).andExpect(status().isOk());
        awaitCharges(1);
        assertThat(readingCharges(plot)).singleElement().satisfies(charge -> {
            assertThat(charge.get("amount")).isEqualTo("43.20");
            assertThat(charge.get("source_key")).isEqualTo("CONSUMPTION:INSTALL:" + meter + ":REMOVAL:" + meter);
        });
    }

    @Test
    void aReadingOfAClosedMonthIsBookedInTheNextOpenMonthWithItsOwnServicePeriod() throws Exception {
        UUID plot = plot("7", "600");
        electricity();
        closeMonth("2026-08");
        UUID meter = electricityMeter(plot, "2026-08-12", "0");
        entered(meter, "2026-08-28", "10");
        awaitCharges(1);

        assertThat(readingCharges(plot)).singleElement().satisfies(charge -> {
            assertThat(charge.get("billing_month")).isEqualTo("2026-09");
            assertThat(charge.get("service_from")).isEqualTo("2026-08-13");
            assertThat(charge.get("service_to")).isEqualTo("2026-08-28");
            assertThat(charge.get("accrued_on")).isEqualTo("2026-08-28");
        });
    }

    @Test
    void anIntervalWithoutATariffForEveryDayIsQueuedAndChargedByARetryOnceTheVersionExists() throws Exception {
        UUID plot = plot("7", "600");
        Tariff tariff = tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200", "2026-09-01");
        UUID meter = electricityMeter(plot, "2026-08-20", "0");
        UUID reading = entered(meter, "2026-09-05", "16");
        awaitListeners();

        assertThat(chargeCount()).isZero();
        assertThat(openItems()).singleElement().satisfies(item -> {
            assertThat(item.get("kind")).isEqualTo("INTERVAL");
            assertThat(item.get("reason")).isEqualTo("NO_TARIFF_FOR_ALL_DAYS");
            assertThat(item.get("item_key")).isEqualTo("INTERVAL:" + meter + ":" + reading);
            assertThat(item.get("plot_id")).isEqualTo(plot);
            assertThat(item.get("meter_id")).isEqualTo(meter);
            assertThat(item.get("charge_type_id")).isEqualTo(typeId("ELECTRICITY"));
            assertThat(date(item.get("from_date"))).isEqualTo(LocalDate.parse("2026-08-21"));
            assertThat(date(item.get("to_date"))).isEqualTo(LocalDate.parse("2026-09-05"));
        });
        JsonNode listed = body(send(chairman, get(NOT_CHARGED), null).andExpect(status().isOk())).get(0);
        assertThat(listed.get("kind").asString()).isEqualTo("INTERVAL");
        assertThat(listed.get("meterId").asString()).isEqualTo(meter.toString());
        assertThat(listed.get("tariffs").get(0).get("id").asString()).isEqualTo(tariff.id().toString());
        UUID item = (UUID) openItems().getFirst().get("id");

        JsonNode unresolved = body(send(chairman, post(RETRY.formatted(item)), null).andExpect(status().isOk()));
        assertThat(unresolved.get("resolved").asBoolean()).isFalse();
        assertThat(unresolved.get("item").get("reason").asString()).isEqualTo("NO_TARIFF_FOR_ALL_DAYS");

        version(tariff.id(), "PER_KWH", "MONTHLY", "4.3200", "2026-08-01", null);
        JsonNode resolved = body(send(chairman, post(RETRY.formatted(item)), null).andExpect(status().isOk()));
        assertThat(resolved.get("resolved").asBoolean()).isTrue();
        awaitListeners();
        assertThat(readingCharges(plot)).singleElement().satisfies(charge -> {
            assertThat(charge.get("amount")).isEqualTo("69.12");
            assertThat(charge.get("created_by")).isNotNull();
        });
        assertThat(openItems()).isEmpty();
        assertThat(owner().queryForObject("select count(*) from audit_entry where tenant_id = ? "
                + "and action = 'NOT_CHARGED_RETRIED'", Integer.class, tenant)).isEqualTo(2);
    }

    @Test
    void aDeletionJoinsTheIntervalsAndTheItemOfTheVanishedIntervalBecomesObsolete() throws Exception {
        UUID plot = plot("7", "600");
        tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200", "2026-09-01");
        UUID meter = electricityMeter(plot, "2026-08-20", "0");
        UUID middle = entered(meter, "2026-08-25", "5");
        UUID last = entered(meter, "2026-09-05", "16");
        awaitListeners();
        assertThat(openItems()).extracting(i -> i.get("item_key")).containsExactlyInAnyOrder(
                "INTERVAL:" + meter + ":" + middle, "INTERVAL:" + meter + ":" + last);

        send(chairman, delete(READINGS + "/" + middle), null).andExpect(status().isNoContent());
        awaitListeners();

        // 21.08-05.09 is still not covered: the item of the last reading now starts at the installation
        assertThat(openItems()).singleElement().satisfies(item -> {
            assertThat(item.get("item_key")).isEqualTo("INTERVAL:" + meter + ":" + last);
            assertThat(date(item.get("from_date"))).isEqualTo(LocalDate.parse("2026-08-21"));
            assertThat(item.get("params").toString()).contains("INSTALL:" + meter);
        });
        assertThat(owner().queryForObject("select status from not_charged_item where item_key = ?", String.class,
                "INTERVAL:" + meter + ":" + middle)).isEqualTo("OBSOLETE");
        assertThat(chargeCount()).isZero();
    }

    @Test
    void theMonthRunRetriesTheOpenIntervalsOfEveryMonth() throws Exception {
        UUID plot = plot("7", "600");
        Tariff tariff = tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200", "2026-09-01");
        UUID meter = electricityMeter(plot, "2026-08-20", "0");
        entered(meter, "2026-09-05", "16");
        awaitListeners();
        assertThat(openItems()).hasSize(1);

        version(tariff.id(), "PER_KWH", "MONTHLY", "4.3200", "2026-08-01", null);
        send(chairman, post(RUN.formatted(ym(0), false)), null).andExpect(status().isOk());
        awaitListeners();

        assertThat(readingCharges(plot)).singleElement().satisfies(charge -> {
            assertThat(charge.get("amount")).isEqualTo("69.12");
            assertThat(charge.get("billing_month")).isEqualTo("2026-09");
        });
        assertThat(openItems()).isEmpty();
    }

    @Test
    void conflictingTariffsAreQueuedNamingBoth() throws Exception {
        UUID plot = plot("12", "600");
        Tariff first = electricity();
        Tariff second = tariff("ELECTRICITY", "Нічний", "PER_KWH", "MONTHLY", "2.1600", "2026-01-01");
        UUID meter = electricityMeter(plot, "2026-08-22", "0");
        entered(meter, "2026-09-03", "10");
        awaitListeners();

        assertThat(chargeCount()).isZero();
        assertThat(openItems()).singleElement().satisfies(item -> {
            assertThat(item.get("reason")).isEqualTo("CONFLICTING_TARIFFS");
            assertThat(item.get("params").toString()).contains(first.id().toString(), second.id().toString());
        });
    }

    @Test
    void waterReadingsChargeAndQueueNothingWhileWaterIsInactive() throws Exception {
        UUID plot = plot("7", "600");
        tariff("WATER", "Вода", "PER_M3", "MONTHLY", "20.0000", "2026-01-01");
        UUID meter = meter(plot, "WATER", "2026-08-22", "0");
        entered(meter, "2026-09-03", "5");
        awaitListeners();

        assertThat(chargeCount()).isZero();
        assertThat(openItems()).isEmpty();
    }

    @Test
    void aWaterReadingOfAPlotNotConnectedForTheWholeIntervalChargesAndQueuesNothing() throws Exception {
        activateWater();
        UUID connected = plot("1", "600");
        Tariff water = tariff("WATER", "Вода", "PER_M3", "MONTHLY", "20.0000", "2026-01-01");
        select(water, connected);
        // disconnected before the interval: the tariff would charge it
        UUID disconnected = plot("7", "600");
        select(water, disconnected);
        connectToWater(disconnected, "2020-05-01", "2026-08-22");
        // never connected and outside the tariff's scope: it would be queued as OUTSIDE_SCOPE if it were connected
        UUID dry = plot("9", "600");
        entered(meter(disconnected, "WATER", "2026-08-22", "0"), "2026-09-03", "5");
        entered(meter(dry, "WATER", "2026-08-22", "0"), "2026-09-03", "5");
        awaitListeners();

        assertThat(chargeCount()).isZero();
        assertThat(openItems()).isEmpty();
    }

    @Test
    void aWaterReadingIsChargedForTheConnectedDaysOfItsInterval() throws Exception {
        activateWater();
        UUID plot = plot("7", "600");
        tariff("WATER", "Вода", "PER_M3", "MONTHLY", "20.0000", "2026-01-01");
        // 01.09-30.09 (30 days) 30 m3, connected from 16.09: 15 days = 15 m3 x 20 = 300.00
        connectToWater(plot, "2026-09-16", null);
        UUID meter = meter(plot, "WATER", "2026-08-31", "0");
        entered(meter, "2026-09-30", "30");
        awaitCharges(1);

        assertThat(readingCharges(plot)).singleElement().satisfies(charge -> {
            assertThat(charge.get("amount")).isEqualTo("300.00");
            assertThat(charge.get("service_from")).isEqualTo("2026-09-16");
            assertThat(charge.get("service_to")).isEqualTo("2026-09-30");
        });
    }

    @Test
    void fixedPercentageLossesAddALossesChargeOverTheSameParts() throws Exception {
        UUID plot = plot("7", "600");
        electricity();
        Map<String, Object> losses = new LinkedHashMap<>();
        losses.put("lossesMode", "FIXED_PERCENT");
        losses.put("lossesPercent", "5");
        assertThat(body(send(chairman, put("/api/v1/settings"), losses).andExpect(status().isOk()))
                .get("lossesPercent").asString()).isEqualTo("5.00");
        // 65 kWh -> CONSUMPTION 280.80 and LOSSES 3.25 kWh = 14.04
        UUID meter = electricityMeter(plot, "2026-09-03", "8140");
        entered(meter, "2026-09-20", "8205");
        awaitCharges(2);

        assertThat(readingCharges(plot)).extracting(c -> c.get("source"), c -> c.get("amount"))
                .containsExactly(tuple("CONSUMPTION", "280.80"),
                        tuple("LOSSES", "14.04"));
        JsonNode account = body(send(chairman, get(ACCOUNT.formatted(plot)), null).andExpect(status().isOk()));
        JsonNode lossesCharge = null;
        for (JsonNode charge : account.get("charges")) {
            if (charge.get("source").asString().equals("LOSSES")) {
                lossesCharge = charge;
            }
        }
        assertThat(lossesCharge).isNotNull();
        assertThat(lossesCharge.get("chargeType").get("code").asString()).isEqualTo("ELECTRICITY");
        JsonNode calculation = lossesCharge.get("calculation");
        assertThat(calculation.get("lossesPercent").asString()).isEqualTo("5.00");
        assertThat(calculation.get("consumption").asString()).isEqualTo("65.000");
        assertThat(calculation.get("parts").get(0).get("quantity").asString()).isEqualTo("3.250");
    }

    @Test
    void aRedeliveredEventChargesNothingTwice() throws Exception {
        UUID plot = plot("7", "600");
        electricity();
        UUID meter = electricityMeter(plot, "2026-08-22", "112.4");
        entered(meter, "2026-09-03", "205.7");
        awaitCharges(1);

        new TransactionTemplate(transactions).executeWithoutResult(status -> events.publishEvent(
                new PlotMeterPointsChanged(tenant, meter, plot, MeterResource.ELECTRICITY, LocalDate.parse("2026-09-03"),
                        PlotMeterPointsChanged.Cause.READING_ENTERED)));
        awaitListeners();

        assertThat(chargeCount()).isEqualTo(1);
        assertThat(owner().queryForObject("select count(*) from meter_value_use where tenant_id = ?", Integer.class,
                tenant)).isEqualTo(2);
    }
}
