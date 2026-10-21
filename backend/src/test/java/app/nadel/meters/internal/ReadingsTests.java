package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import app.nadel.meters.ReadingUsage;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.JsonNode;

/**
 * Readings through the API (spec 010, sections 2.4 and 2.5): the default date, a date changed for one row, the future,
 * the working period, a lower reading, a reading between two others, the same date twice, the unusual-consumption
 * warning, correction, deletion, readings a charge uses (a test {@link ReadingUsage} next to the default one), a batch
 * with a result per row, and who may call which endpoint.
 */
class ReadingsTests extends MeterTestSupport {

    private static final String STREET = "MT вул. Вишнева";

    /** A usage that a test switches on for chosen meters, in addition to the default that says "never used". */
    @TestConfiguration(proxyBeanMethods = false)
    static class UsedMeters {

        final Set<UUID> used = ConcurrentHashMap.newKeySet();
        final List<String> asked = new ArrayList<>();

        @Bean
        ReadingUsage testReadingUsage() {
            return (tenantId, meterId, from, to) -> {
                synchronized (asked) {
                    asked.add(meterId + " " + from + " " + to);
                }
                return used.contains(meterId);
            };
        }
    }

    @Autowired
    UsedMeters usage;

    @AfterEach
    void forgetUsedMeters() {
        usage.used.clear();
        synchronized (usage.asked) {
            usage.asked.clear();
        }
    }

    // ---- the date

    @Test
    void aReadingSavedWithoutADateIsDatedToday() throws Exception {
        UUID meter = plotMeter("2026-06-01", "0");

        JsonNode result = enter(meter, null, "10");

        assertThat(result.get("index").asInt()).isZero();
        assertThat(result.get("meterId").asString()).isEqualTo(meter.toString());
        assertThat(result.get("outcome").asString()).isEqualTo("SAVED");
        assertThat(result.get("warnings")).isEmpty();
        assertThat(result.get("supersededReadingIds")).isEmpty();
        JsonNode reading = result.get("reading");
        assertThat(reading.get("takenOn").asString()).isEqualTo(kyivToday().toString());
        assertThat(reading.get("value").asString()).isEqualTo("10.000");
        assertThat(reading.get("status").asString()).isEqualTo("APPROVED");
        assertThat(reading.get("source").asString()).isEqualTo("ENTERED");
        assertThat(reading.get("changeable").asBoolean()).isTrue();
        assertThat(reading.get("interval").get("from").asString()).isEqualTo("2026-06-01");
        assertThat(reading.get("interval").get("to").asString()).isEqualTo(kyivToday().toString());
        assertThat(reading.get("interval").get("consumption").asString()).isEqualTo("10.000");
    }

    @Test
    void onlyTheRowWhoseDateWasChangedIsDatedDifferently() throws Exception {
        UUID first = plotMeter("2026-06-01", "0");
        UUID second = plotMeter("2026-06-01", "0");
        LocalDate changed = kyivToday().minusDays(3);

        JsonNode rows = enterRows(chairman(), List.of(row(first, null, "5"), row(second, changed.toString(), "7")));

        assertThat(rows.get(0).get("reading").get("takenOn").asString()).isEqualTo(kyivToday().toString());
        assertThat(rows.get(1).get("reading").get("takenOn").asString()).isEqualTo(changed.toString());
    }

    @Test
    void aReadingDatedInTheFutureIsRefused() throws Exception {
        UUID meter = plotMeter("2026-06-01", "0");

        JsonNode result = enter(meter, kyivToday().plusDays(1).toString(), "10");

        assertThat(result.get("outcome").asString()).isEqualTo("REJECTED");
        assertThat(result.has("reading")).isFalse();
        assertThat(result.get("error").get("code").asString()).isEqualTo("READING_DATE_IN_FUTURE");
        assertThat(result.get("error").get("field").asString()).isEqualTo("takenOn");
        assertThat(result.get("error").get("params").get("today").asString()).isEqualTo(kyivToday().toString());
        assertThat(readingCount(meter)).isZero();
    }

    @Test
    void aReadingOutsideTheWorkingPeriodOfTheMeterIsRefused() throws Exception {
        UUID meter = plotMeter("2026-06-01", "8000");

        JsonNode before = enter(meter, "2026-05-31", "8000").get("error");
        assertThat(before.get("code").asString()).isEqualTo("READING_DATE_OUTSIDE_METER");
        assertThat(before.get("field").asString()).isEqualTo("takenOn");
        assertThat(before.get("params").get("installedOn").asString()).isEqualTo("2026-06-01");

        send(chairman(), post(METERS + "/" + meter + "/remove"), Map.of("removedOn", "2026-09-01",
                "finalReading", "8100")).andExpect(status().isOk());
        JsonNode after = enter(meter, "2026-09-02", "8100").get("error");
        assertThat(after.get("code").asString()).isEqualTo("READING_DATE_OUTSIDE_METER");
        assertThat(after.get("params").get("removedOn").asString()).isEqualTo("2026-09-01");
        // a removed meter still takes a reading within its working period
        assertThat(enter(meter, "2026-08-01", "8050").get("outcome").asString()).isEqualTo("SAVED");
        assertThat(readingCount(meter)).isEqualTo(1);
    }

    // ---- the order

    @Test
    void aReadingLowerThanThePreviousOneIsRefused() throws Exception {
        UUID meter = plotMeter("2026-06-01", "8000");
        entered(meter, "2026-09-03", "8140");

        JsonNode error = enter(meter, "2026-10-05", "8100").get("error");

        assertThat(error.get("code").asString()).isEqualTo("READING_OUT_OF_ORDER");
        assertThat(error.get("field").asString()).isEqualTo("value");
        assertThat(error.get("params").get("previousTakenOn").asString()).isEqualTo("2026-09-03");
        assertThat(error.get("params").get("previousValue").asString()).isEqualTo("8140.000");
        assertThat(readingCount(meter)).isEqualTo(1);
    }

    @Test
    void aReadingBetweenTwoOthersIsAcceptedAndSplitsTheInterval() throws Exception {
        UUID meter = plotMeter("2026-06-01", "8000");
        entered(meter, "2026-09-03", "8140");
        entered(meter, "2026-10-05", "8205");

        JsonNode reading = enter(meter, "2026-09-20", "8170").get("reading");

        assertThat(reading.get("interval").get("from").asString()).isEqualTo("2026-09-03");
        assertThat(reading.get("interval").get("days").asInt()).isEqualTo(17);
        assertThat(reading.get("interval").get("consumption").asString()).isEqualTo("30.000");
        assertThat(intervals(history(meter))).containsExactly("2026-09-20..2026-10-05=35.000",
                "2026-09-03..2026-09-20=30.000", "2026-06-01..2026-09-03=140.000");
        // the charge question covers the readings around the new one
        assertThat(usage.asked).contains(meter + " 2026-09-03 2026-10-05");
        // too high for the place between them
        assertThat(enter(meter, "2026-09-25", "8206").get("error").get("params").get("nextTakenOn").asString())
                .isEqualTo("2026-10-05");
    }

    @Test
    void theSameDateTwiceSupersedesTheFirstReading() throws Exception {
        UUID meter = plotMeter("2026-06-01", "8000");
        UUID first = entered(meter, "2026-10-05", "8205");

        JsonNode result = enter(meter, "2026-10-05", "8250");

        UUID second = UUID.fromString(result.get("reading").get("id").asString());
        assertThat(result.get("supersededReadingIds").get(0).asString()).isEqualTo(first.toString());
        assertThat(result.get("reading").get("interval").get("consumption").asString()).isEqualTo("250.000");
        assertThat(readingStatus(first)).isEqualTo("SUPERSEDED");
        assertThat(readingStatus(second)).isEqualTo("APPROVED");
        JsonNode history = history(meter);
        JsonNode readings = history.get("readings");
        assertThat(readings.get(0).get("id").asString()).isEqualTo(second.toString());
        assertThat(readings.get(1).get("id").asString()).isEqualTo(first.toString());
        assertThat(readings.get(1).get("status").asString()).isEqualTo("SUPERSEDED");
        assertThat(readings.get(1).get("supersededById").asString()).isEqualTo(second.toString());
        assertThat(readings.get(1).has("interval")).isFalse();
        assertThat(readings.get(1).get("changeable").asBoolean()).isFalse();
        assertThat(intervals(history)).containsExactly("2026-06-01..2026-10-05=250.000");

        // the same value on the same date again changes nothing
        JsonNode again = enter(meter, "2026-10-05", "8250");
        assertThat(again.get("reading").get("id").asString()).isEqualTo(second.toString());
        assertThat(again.get("supersededReadingIds")).isEmpty();
        assertThat(readingCount(meter)).isEqualTo(2);
    }

    // ---- the warning

    @Test
    void anUnusualConsumptionIsSavedWithAWarning() throws Exception {
        UUID meter = plotMeter("2026-07-01", "1000");

        // the first interval has nothing to compare with
        assertThat(enter(meter, "2026-08-01", "1062").get("warnings")).isEmpty(); // 2 kWh a day
        JsonNode unusual = enter(meter, "2026-08-11", "1462"); // 40 kWh a day

        assertThat(unusual.get("outcome").asString()).isEqualTo("SAVED");
        assertThat(unusual.get("warnings").get(0).asString()).isEqualTo("UNUSUAL_CONSUMPTION");
        assertThat(enter(meter, "2026-08-21", "1482").get("warnings")).isEmpty();
        assertThat(readingCount(meter)).isEqualTo(3);
    }

    // ---- correction

    @Test
    void aCorrectionIsANewReadingAndTheIntervalsAroundItFollow() throws Exception {
        UUID meter = plotMeter("2026-06-01", "8000");
        UUID old = entered(meter, "2026-09-03", "8140");
        UUID later = entered(meter, "2026-10-05", "8205");

        JsonNode saved = body(send(chairman(), put(READINGS + "/" + old), correction("2026-09-10", "8150"))
                .andExpect(status().isOk()));

        UUID corrected = UUID.fromString(saved.get("reading").get("id").asString());
        assertThat(corrected).isNotEqualTo(old);
        assertThat(saved.get("reading").get("takenOn").asString()).isEqualTo("2026-09-10");
        assertThat(saved.get("reading").get("value").asString()).isEqualTo("8150.000");
        assertThat(saved.get("reading").get("interval").get("consumption").asString()).isEqualTo("150.000");
        assertThat(saved.get("supersededReadingIds").get(0).asString()).isEqualTo(old.toString());
        assertThat(readingStatus(old)).isEqualTo("SUPERSEDED");
        assertThat(intervals(history(meter))).containsExactly("2026-09-10..2026-10-05=55.000",
                "2026-06-01..2026-09-10=150.000");

        // a correction that changes nothing answers the reading as it is
        send(chairman(), put(READINGS + "/" + corrected), correction("2026-09-10", "8150.000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reading.id").value(corrected.toString()))
                .andExpect(jsonPath("$.supersededReadingIds").isEmpty());

        // moved onto the date of another counted reading, it replaces that one too
        JsonNode moved = body(send(chairman(), put(READINGS + "/" + corrected), correction("2026-10-05", "8300"))
                .andExpect(status().isOk()));
        assertThat(moved.get("supersededReadingIds")).hasSize(2);
        assertThat(readingStatus(corrected)).isEqualTo("SUPERSEDED");
        assertThat(readingStatus(later)).isEqualTo("SUPERSEDED");
        assertThat(intervals(history(meter))).containsExactly("2026-06-01..2026-10-05=300.000");
    }

    @Test
    void aCorrectionFollowsTheRulesOfAReading() throws Exception {
        UUID meter = plotMeter("2026-06-01", "8000");
        UUID first = entered(meter, "2026-09-03", "8140");
        UUID second = entered(meter, "2026-10-05", "8205");

        expectCorrectionConflict(second, correction("2026-10-05", "8100"), "READING_OUT_OF_ORDER", "value");
        expectCorrectionConflict(first, correction("2026-09-03", "8300"), "READING_OUT_OF_ORDER", "value");
        expectCorrectionConflict(second, correction(kyivToday().plusDays(1).toString(), "8300"),
                "READING_DATE_IN_FUTURE", "takenOn");
        expectCorrectionConflict(second, correction("2026-05-01", "7000"), "READING_DATE_OUTSIDE_METER", "takenOn");
        send(chairman(), put(READINGS + "/" + second), correction("2026-10-05", "8205.1234"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("value"))
                .andExpect(jsonPath("$.errors[0].code").value("METER_VALUE_INVALID"));
        send(chairman(), put(READINGS + "/" + UUID.randomUUID()), correction("2026-10-05", "8205"))
                .andExpect(status().isNotFound());

        UUID replacement = entered(meter, "2026-10-05", "8210");
        send(chairman(), put(READINGS + "/" + second), correction("2026-10-05", "8220"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_STATUS_INVALID"));
        assertThat(readingStatus(replacement)).isEqualTo("APPROVED");
        assertThat(readingCount(meter)).isEqualTo(3);
    }

    // ---- deletion

    @Test
    void aDeletionJoinsTheIntervalsAroundTheReading() throws Exception {
        UUID meter = plotMeter("2026-06-01", "8000");
        entered(meter, "2026-09-03", "8140");
        UUID middle = entered(meter, "2026-09-20", "8170");
        entered(meter, "2026-10-05", "8205");

        send(chairman(), delete(READINGS + "/" + middle), null).andExpect(status().isNoContent());

        assertThat(intervals(history(meter))).containsExactly("2026-09-03..2026-10-05=65.000",
                "2026-06-01..2026-09-03=140.000");
        assertThat(readingCount(meter)).isEqualTo(2);
        assertThat(usage.asked).contains(meter + " 2026-09-03 2026-10-05");
        send(chairman(), delete(READINGS + "/" + middle), null).andExpect(status().isNotFound());
    }

    @Test
    void onlyACountedReadingIsDeleted() throws Exception {
        UUID meter = plotMeter("2026-06-01", "8000");
        UUID first = entered(meter, "2026-10-05", "8205");
        UUID second = entered(meter, "2026-10-05", "8210");

        send(chairman(), delete(READINGS + "/" + first), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_STATUS_INVALID"));
        // deleting the successor leaves the replaced reading superseded, without a link
        send(chairman(), delete(READINGS + "/" + second), null).andExpect(status().isNoContent());
        assertThat(readingStatus(first)).isEqualTo("SUPERSEDED");
        assertThat(ownerJdbc().queryForObject("select superseded_by_id from meter_reading where id = ?", UUID.class,
                first)).isNull();
    }

    // ---- readings a charge uses

    @Test
    void readingsAChargeUsesAreNeitherCorrectedNorDeletedNorEnteredAround() throws Exception {
        UUID meter = plotMeter("2026-06-01", "8000");
        UUID reading = entered(meter, "2026-09-03", "8140");
        usage.used.add(meter);

        JsonNode refused = enter(meter, "2026-10-05", "8205");
        assertThat(refused.get("outcome").asString()).isEqualTo("REJECTED");
        assertThat(refused.get("error").get("code").asString()).isEqualTo("READING_IN_USE");
        send(chairman(), put(READINGS + "/" + reading), correction("2026-09-03", "8150"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_IN_USE"));
        send(chairman(), delete(READINGS + "/" + reading), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_IN_USE"));
        assertThat(history(meter).get("readings").get(0).get("changeable").asBoolean()).isFalse();
        assertThat(readingCount(meter)).isEqualTo(1);
        assertThat(readingStatus(reading)).isEqualTo("APPROVED");

        usage.used.clear();
        assertThat(history(meter).get("readings").get(0).get("changeable").asBoolean()).isTrue();
    }

    // ---- a batch

    @Test
    void anApprovalIsRefusedWhileAChargeUsesTheReadingsAround() throws Exception {
        UUID meter = plotMeter("2026-06-01", "0");
        entered(meter, "2026-09-01", "10");
        reading(meter, "2026-09-15", "20", "PENDING");
        UUID pending = ownerJdbc().queryForObject("select id from meter_reading where meter_id = ? "
                + "and status = 'PENDING'", UUID.class, meter);
        usage.used.add(meter);

        send(chairman(), post(READINGS + "/" + pending + "/approve"), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_IN_USE"));
        assertThat(readingStatus(pending)).isEqualTo("PENDING");
        synchronized (usage.asked) {
            assertThat(usage.asked).contains(meter + " 2026-09-01 2026-09-15");
        }

        usage.used.clear();
        send(chairman(), post(READINGS + "/" + pending + "/approve"), null).andExpect(status().isOk());
        assertThat(readingStatus(pending)).isEqualTo("APPROVED");
    }

    @Test
    void aBatchAnswersEveryRowOnItsOwn() throws Exception {
        UUID first = plotMeter("2026-06-01", "100");
        UUID second = plotMeter("2026-06-01", "200");

        JsonNode rows = enterRows(chairman(), List.of(
                row(first, "2026-09-01", "150"),
                row(first, kyivToday().plusDays(5).toString(), "160"),
                row(UUID.randomUUID(), "2026-09-01", "1"),
                row(second, "2026-09-01", "250"),
                row(second, "2026-09-10", "240")));

        assertThat(rows).hasSize(5);
        List<String> outcomes = new ArrayList<>();
        rows.forEach(row -> outcomes.add(row.get("index").asInt() + " " + row.get("outcome").asString()
                + (row.has("error") ? " " + row.get("error").get("code").asString() : "")));
        assertThat(outcomes).containsExactly("0 SAVED", "1 REJECTED READING_DATE_IN_FUTURE", "2 REJECTED NOT_FOUND",
                "3 SAVED", "4 REJECTED READING_OUT_OF_ORDER");
        assertThat(rows.get(2).get("error").get("field").asString()).isEqualTo("meterId");
        assertThat(readingCount(first)).isEqualTo(1);
        assertThat(readingCount(second)).isEqualTo(1);
    }

    @Test
    void aMalformedBatchIsRefusedAsAWhole() throws Exception {
        UUID meter = plotMeter("2026-06-01", "0");

        send(chairman(), post(READINGS), Map.of("rows", List.of(row(meter, "2026-09-01", "1"),
                row(meter, "2026-09-02", "1.2345")))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("rows[1].value"))
                .andExpect(jsonPath("$.errors[0].code").value("METER_VALUE_INVALID"));
        send(chairman(), post(READINGS), Map.of("rows", List.of())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("rows"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            tooMany.add(row(meter, null, "1"));
        }
        send(chairman(), post(READINGS), Map.of("rows", tooMany)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("rows"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        send(chairman(), post(READINGS), Map.of("rows", List.of(Map.of("value", "1"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("rows[0].meterId"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        assertThat(readingCount(meter)).isZero();
    }

    // ---- who may do what

    @Test
    void anElectricianLikeRoleEntersCorrectsDeletesAndReadsTheHistory() throws Exception {
        UUID meter = plotMeter("2026-06-01", "0");
        String electrician = customRoleToken("METERS_VIEW", "READINGS_ENTER");

        JsonNode rows = enterRows(electrician, List.of(row(meter, "2026-09-01", "10")));
        UUID reading = UUID.fromString(rows.get(0).get("reading").get("id").asString());
        JsonNode corrected = body(send(electrician, put(READINGS + "/" + reading), correction("2026-09-01", "12"))
                .andExpect(status().isOk()));
        send(electrician, get(METERS + "/" + meter + "/readings"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.readings[0].changeable").value(true));
        send(electrician, delete(READINGS + "/" + corrected.get("reading").get("id").asString()), null)
                .andExpect(status().isNoContent());
    }

    @Test
    void enteringWithoutViewingMetersSeesNoHistory() throws Exception {
        UUID meter = plotMeter("2026-06-01", "0");
        String enterer = customRoleToken("READINGS_ENTER");

        assertThat(enterRows(enterer, List.of(row(meter, "2026-09-01", "10"))).get(0).get("outcome").asString())
                .isEqualTo("SAVED");
        send(enterer, get(METERS + "/" + meter + "/readings"), null).andExpect(status().isNotFound());
    }

    @Test
    void theTreasurerSeesTheReadingsButChangesNothing() throws Exception {
        UUID meter = plotMeter("2026-06-01", "0");
        UUID reading = entered(meter, "2026-09-01", "10");
        String treasurer = demo("treasurer@demo.local");

        send(treasurer, get(METERS + "/" + meter + "/readings"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.readings[0].id").value(reading.toString()))
                .andExpect(jsonPath("$.readings[0].changeable").value(false));
        expectEveryWriteDenied(treasurer, meter, reading);
    }

    @Test
    void aMemberSeesTheReadingsOfTheirOwnPlotOnly() throws Exception {
        UUID own = newPlot(STREET);
        UUID other = newPlot(STREET);
        RoleMember member = customRoleMember(); // a member without permissions
        own(member.id(), own);
        UUID ownMeter = register(plotMeter(own, "ELECTRICITY", "MT-own", "2026-06-01", "0"));
        UUID otherMeter = register(plotMeter(other, "ELECTRICITY", "MT-other", "2026-06-01", "0"));
        UUID reading = entered(ownMeter, "2026-09-01", "10");
        String token = TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), member.email());

        send(token, get(METERS + "/" + ownMeter + "/readings"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.readings[0].value").value("10.000"))
                .andExpect(jsonPath("$.readings[0].changeable").value(false))
                .andExpect(jsonPath("$.intervals[0].consumption").value("10.000"));
        send(token, get(METERS + "/" + otherMeter + "/readings"), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        send(token, get(METERS + "/" + UUID.randomUUID() + "/readings"), null).andExpect(status().isNotFound());
        expectEveryWriteDenied(token, ownMeter, reading);
    }

    @Test
    void anotherCooperativeChangesNothingAndSeesNothing() throws Exception {
        UUID meter = plotMeter("2026-06-01", "0");
        UUID reading = entered(meter, "2026-09-01", "10");
        String other = otherChairman();

        send(other, get(METERS + "/" + meter + "/readings"), null).andExpect(status().isNotFound());
        JsonNode row = enterRows(other, List.of(row(meter, "2026-09-02", "11"))).get(0);
        assertThat(row.get("outcome").asString()).isEqualTo("REJECTED");
        assertThat(row.get("error").get("code").asString()).isEqualTo("NOT_FOUND");
        send(other, put(READINGS + "/" + reading), correction("2026-09-01", "12")).andExpect(status().isNotFound());
        send(other, delete(READINGS + "/" + reading), null).andExpect(status().isNotFound());
        assertThat(readingCount(meter)).isEqualTo(1);
        assertThat(readingStatus(reading)).isEqualTo("APPROVED");
    }

    @Test
    void thePlatformOperatorHasNoAccess() throws Exception {
        UUID meter = plotMeter("2026-06-01", "0");
        UUID reading = entered(meter, "2026-09-01", "10");
        String operator = operator();

        send(operator, get(METERS + "/" + meter + "/readings"), null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        expectEveryWriteDenied(operator, meter, reading);
    }

    @Test
    void withoutATokenTheAnswerIsUnauthorized() throws Exception {
        mvc.perform(get(METERS + "/" + UUID.randomUUID() + "/readings")).andExpect(status().isUnauthorized());
        mvc.perform(post(READINGS)).andExpect(status().isUnauthorized());
    }

    // ---- helpers

    /** A plot meter (electricity) on a new plot of the demo cooperative. */
    private UUID plotMeter(String installedOn, String initialReading) throws Exception {
        return register(plotMeter(newPlot(STREET), "ELECTRICITY", "MT-" + UUID.randomUUID().toString()
                .substring(0, 8), installedOn, initialReading));
    }

    private static Map<String, Object> correction(String takenOn, String value) {
        return Map.of("takenOn", takenOn, "value", value);
    }

    /** The intervals of a history as {@code from..to=consumption}, newest first. */
    private static List<String> intervals(JsonNode history) {
        List<String> result = new ArrayList<>();
        history.get("intervals").forEach(interval -> result.add(interval.get("from").asString() + ".."
                + interval.get("to").asString() + "=" + interval.get("consumption").asString()));
        return result;
    }

    private void expectCorrectionConflict(UUID reading, Map<String, Object> body, String code, String field)
            throws Exception {
        send(chairman(), put(READINGS + "/" + reading), body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].code").value(code));
        assertThat(readingStatus(reading)).isEqualTo("APPROVED");
    }

    private void expectEveryWriteDenied(String bearer, UUID meter, UUID reading) throws Exception {
        int before = readingCount(meter);
        send(bearer, post(READINGS), Map.of("rows", List.of(row(meter, "2026-09-02", "11"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        send(bearer, put(READINGS + "/" + reading), correction("2026-09-01", "12")).andExpect(status().isForbidden());
        send(bearer, delete(READINGS + "/" + reading), null).andExpect(status().isForbidden());
        send(bearer, get(READINGS + "/round"), null).andExpect(status().isForbidden());
        assertThat(readingCount(meter)).isEqualTo(before);
        assertThat(readingStatus(reading)).isEqualTo("APPROVED");
    }
}
