package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * Common consumer runs over the API (feature 011 §2.6): the split modes of the §3 rows (equally per plot 54.00, the
 * well among the connected plots 24/24/12 kWh, service users 43.20, not charged), the booking with its charges, parts,
 * value uses and audit, the missing tariff, the overlap refusal, the reversal of a whole run with the run's reason, an
 * unknown consumer queued and charged later by "Повторити", a stale preview, the readings a booked run uses, validation,
 * the settings of a consumer (split and service users) and the permissions. Each test has a cooperative of its own; the
 * meters and readings are written as the database owner (no reading events, so no reading charges).
 */
class CommonConsumerRunsTests extends FreshCooperativeSupport {

    private static final String RUNS = "/api/v1/common-consumer-runs";
    private static final String PREVIEW = RUNS + "?preview=true";
    private static final String BOOK = RUNS + "?preview=false";
    private static final String BILLING = "/api/v1/common-consumers/%s/billing";
    private static final String FROM = "2026-09-01";
    private static final String TO = "2026-09-30";
    private static final String BEFORE = "2026-08-31";
    private static final String INSTALLED = "2026-01-01";

    private UUID p1;
    private UUID p2;
    private UUID p3;
    private UUID p4;

    @BeforeEach
    void plotsAndACommonConsumersTariff() {
        tariff("COMMON_CONSUMERS", "Спільні споживачі", "PER_KWH", "MONTHLY", "4.3200", INSTALLED);
        p1 = plot("1", "600");
        p2 = plot("2", "600");
        p3 = plot("3", "600");
        p4 = plot("4", "600");
        // common land and archived plots never share
        plot("Дорога", "0.00", "COMMON", false);
        plot("5", "600", "PLOT", true);
    }

    // ---- the split modes (§3)

    @Test
    void streetLightingEquallyPerPlotPreviewsFourPlotsAt54AndWritesNothing() throws Exception {
        UUID lighting = consumer("Освітлення", 1000, 1050);
        billing(lighting, "EQUAL_PER_PLOT", List.of()).andExpect(status().isOk());

        JsonNode preview = body(send(chairman, post(PREVIEW), range()).andExpect(status().isOk()));

        assertThat(preview.get("preview").asBoolean()).isTrue();
        assertThat(preview.get("noTariff").asBoolean()).isFalse();
        assertThat(preview.get("billingMonth").asString()).isEqualTo("2026-09");
        JsonNode line = only(preview.get("consumers"));
        assertThat(line.get("meterId").asString()).isEqualTo(lighting.toString());
        assertThat(line.get("name").asString()).isEqualTo("Освітлення");
        assertThat(line.get("split").asString()).isEqualTo("EQUAL_PER_PLOT");
        assertThat(line.get("consumption").asString()).isEqualTo("50.000");
        assertThat(line.get("outcome").asString()).isEqualTo("CHARGED");
        assertThat(line.get("plotDays").asLong()).isEqualTo(4 * 30);
        assertThat(lines(line.get("plots"), "plotNumber", "days", "kwh", "amount")).containsExactly(
                "1|30|12.500|54.00", "2|30|12.500|54.00", "3|30|12.500|54.00", "4|30|12.500|54.00");
        assertThat(line.get("amount").asString()).isEqualTo("216.00");
        assertThat(preview.get("total").asString()).isEqualTo("216.00");
        assertThat(preview.get("plots").asInt()).isEqualTo(4);
        assertThat(preview.get("charges").asInt()).isEqualTo(4);
        assertThat(preview.get("created").asInt()).isZero();
        assertThat(chargeCount()).isZero();
        assertThat(runCount()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theWellIsSharedPerPlotDayAmongItsConnectedPlotsAndTheBookingIsWrittenAndAudited() throws Exception {
        // the cooperative's default split is CONNECTED_PLOTS
        UUID well = consumer("Свердловина №1", 300, 360);
        connect(p1, well, "2026-05-01", null);
        connect(p2, well, "2026-05-01", null);
        connect(p3, well, "2026-09-16", null);
        // p4 is connected to the cooperative's supply, not to the well
        connectToWater(p4, "2026-05-01", null);
        String digest = body(send(chairman, post(PREVIEW), range()).andExpect(status().isOk())).get("digest")
                .asString();

        JsonNode booked = body(send(chairman, post(BOOK), range(digest)).andExpect(status().isOk()));

        assertThat(booked.get("preview").asBoolean()).isFalse();
        assertThat(booked.get("created").asInt()).isEqualTo(3);
        JsonNode line = only(booked.get("consumers"));
        assertThat(line.get("split").asString()).isEqualTo("CONNECTED_PLOTS");
        // 30 + 30 + 15 = 75 plot-days, 0.8 kWh each
        assertThat(line.get("plotDays").asLong()).isEqualTo(75);
        assertThat(lines(line.get("plots"), "plotNumber", "days", "kwh", "amount")).containsExactly(
                "1|30|24.000|103.68", "2|30|24.000|103.68", "3|15|12.000|51.84");
        assertThat(line.get("plots").get(0).has("chargeId")).isTrue();
        UUID run = UUID.fromString(booked.get("runId").asString());
        assertThat(charges(p4)).isEmpty();
        assertThat(charges(p3)).singleElement().satisfies(charge -> {
            assertThat(charge.get("amount")).isEqualTo("51.84");
            assertThat(charge.get("kind")).isEqualTo("REGULAR");
            assertThat(charge.get("source")).isEqualTo("COMMON_CONSUMER");
            assertThat(date(charge.get("service_from"))).hasToString(FROM);
            assertThat(date(charge.get("service_to"))).hasToString(TO);
            assertThat(date(charge.get("accrued_on"))).hasToString(TO);
            assertThat(charge.get("source_key")).isEqualTo("COMMON_CONSUMER_RUN:" + run + ":" + well + ":" + p3);
            assertThat(date(charge.get("billing_month"))).hasToString("2026-09-01");
        });
        assertThat(owner().queryForList("select distinct charge_type_id from charge where run_id = ?", UUID.class,
                run)).containsExactly(typeId("COMMON_CONSUMERS"));
        assertThat(owner().queryForList("select p.days || '|' || p.units_num || '/' || p.units_den || '|' || p.rate "
                + "from charge_part p join charge c on c.id = p.charge_id where c.plot_id = ?", String.class, p3))
                .singleElement().satisfies(part -> assertThat(part).startsWith("15|").endsWith("|4.3200"));
        assertThat(owner().queryForList("select meter_id::text || '@' || used_on::text from meter_value_use "
                + "where run_id = ?", String.class, run)).containsExactlyInAnyOrder(well + "@" + BEFORE,
                        well + "@" + TO);

        // the account names the consumer, the run and the split
        JsonNode account = body(send(chairman, get(ACCOUNT.formatted(p3)), null).andExpect(status().isOk()));
        JsonNode charge = account.get("charges").get(0);
        assertThat(charge.get("source").asString()).isEqualTo("COMMON_CONSUMER");
        assertThat(charge.get("chargeType").get("code").asString()).isEqualTo("COMMON_CONSUMERS");
        JsonNode calculation = charge.get("calculation");
        assertThat(calculation.get("runId").asString()).isEqualTo(run.toString());
        assertThat(calculation.get("consumerMeterId").asString()).isEqualTo(well.toString());
        assertThat(calculation.get("consumerName").asString()).isEqualTo("Свердловина №1");
        assertThat(calculation.get("split").asString()).isEqualTo("CONNECTED_PLOTS");
        assertThat(lines(calculation.get("parts"), "quantity", "rate", "days", "from", "to"))
                .containsExactly("12.000|4.3200|15|2026-09-16|" + TO);

        // the history and the details
        JsonNode summary = only(body(send(chairman, get(RUNS), null).andExpect(status().isOk())));
        assertThat(summary.get("status").asString()).isEqualTo("BOOKED");
        assertThat(summary.get("total").asString()).isEqualTo("259.20");
        assertThat(summary.get("plots").asInt()).isEqualTo(3);
        assertThat(summary.get("charges").asInt()).isEqualTo(3);
        assertThat(summary.get("chargedConsumers").asInt()).isEqualTo(1);
        assertThat(summary.get("skippedConsumers").asInt()).isZero();
        assertThat(summary.get("billingMonth").asString()).isEqualTo("2026-09");
        assertThat(summary.get("reversible").asBoolean()).isTrue();
        assertThat(summary.get("createdByName").asString()).isNotBlank();
        JsonNode details = body(send(chairman, get(RUNS + "/" + run), null).andExpect(status().isOk()));
        assertThat(lines(only(details.get("consumers")).get("plots"), "plotNumber", "kwh", "amount", "reversed"))
                .containsExactly("1|24.000|103.68|false", "2|24.000|103.68|false", "3|12.000|51.84|false");

        await().atMost(Duration.ofSeconds(10)).until(() -> auditEntries("COMMON_CONSUMER_RUN_BOOKED").size() == 1);
        Map<String, Object> entry = auditEntries("COMMON_CONSUMER_RUN_BOOKED").getFirst();
        assertThat(entry.get("entity_type")).isEqualTo("COMMON_CONSUMER_RUN");
        assertThat(entry.get("entity_label")).isEqualTo(FROM + "/" + TO);
        assertThat((String) entry.get("changes")).contains("\"total\"", "259.20", "\"consumers\"", "\"count\"");
    }

    @Test
    void serviceUsersPay43AndOtherPlotsNothingWhileANotChargedConsumerIsOnlyListed() throws Exception {
        UUID lighting = consumer("Освітлення", 1000, 1030);
        UUID office = consumer("Правління", 500, 540);
        billing(lighting, "SERVICE_USERS", List.of(p1, p2, p4)).andExpect(status().isOk());
        billing(office, "NOT_CHARGED", List.of()).andExpect(status().isOk());

        JsonNode booked = body(send(chairman, post(BOOK), range()).andExpect(status().isOk()));

        // consumers by name
        assertThat(lines(booked.get("consumers"), "name", "split", "outcome", "consumption", "amount"))
                .containsExactly("Освітлення|SERVICE_USERS|CHARGED|30.000|129.60",
                        "Правління|NOT_CHARGED|NOT_CHARGED|40.000|0.00");
        assertThat(lines(booked.get("consumers").get(0).get("plots"), "plotNumber", "kwh", "amount"))
                .containsExactly("1|10.000|43.20", "2|10.000|43.20", "4|10.000|43.20");
        assertThat(booked.get("consumers").get(1).get("plots")).isEmpty();
        assertThat(charges(p3)).isEmpty();
        assertThat(chargeCount()).isEqualTo(3);
        assertThat(owner().queryForList("select outcome from common_consumer_run_line where tenant_id = ? "
                + "order by position", String.class, tenant)).containsExactly("CHARGED", "NOT_CHARGED");
        // the run counts the consumer that was not charged as skipped
        JsonNode summary = only(body(send(chairman, get(RUNS), null)));
        assertThat(summary.get("chargedConsumers").asInt()).isEqualTo(1);
        assertThat(summary.get("skippedConsumers").asInt()).isEqualTo(1);
    }

    // ---- the tariff, overlaps and stale previews

    @Test
    void withoutACommonConsumersTariffThePreviewSaysSoAndNothingCanBeBooked() throws Exception {
        owner().update("delete from tariff_version where tenant_id = ?", tenant);
        owner().update("delete from tariff where tenant_id = ?", tenant);
        consumer("Освітлення", 1000, 1050);
        owner().update("insert into cooperative_settings (tenant_id, common_consumer_default_split) "
                + "values (?, 'EQUAL_PER_PLOT')", tenant);

        JsonNode preview = body(send(chairman, post(PREVIEW), range()).andExpect(status().isOk()));

        assertThat(preview.get("noTariff").asBoolean()).isTrue();
        assertThat(preview.get("total").asString()).isEqualTo("0.00");
        assertThat(lines(only(preview.get("consumers")).get("plots"), "plotNumber", "kwh", "amount"))
                .containsExactly("1|12.500|", "2|12.500|", "3|12.500|", "4|12.500|");
        send(chairman, post(BOOK), range()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_COMMON_CONSUMERS_TARIFF"));
        assertThat(runCount()).isZero();
        assertThat(chargeCount()).isZero();
    }

    @Test
    void aConsumerChargedForSomeOfTheDaysIsShownAndItsBookingRefused() throws Exception {
        UUID lighting = consumer("Освітлення", 1000, 1050);
        billing(lighting, "EQUAL_PER_PLOT", List.of()).andExpect(status().isOk());
        reading(lighting, "2026-10-15", "1080");
        send(chairman, post(BOOK), range()).andExpect(status().isOk());
        UUID first = UUID.fromString(only(body(send(chairman, get(RUNS), null))).get("id").asString());

        JsonNode preview = body(send(chairman, post(PREVIEW), range("2026-09-15", "2026-10-15"))
                .andExpect(status().isOk()));

        JsonNode line = only(preview.get("consumers"));
        assertThat(line.get("outcome").asString()).isEqualTo("ALREADY_CHARGED");
        assertThat(line.get("warning").get("code").asString()).isEqualTo("ALREADY_CHARGED");
        assertThat(line.get("warning").get("runId").asString()).isEqualTo(first.toString());
        assertThat(line.get("warning").get("from").asString()).isEqualTo(FROM);
        assertThat(line.get("plots")).isEmpty();
        send(chairman, post(BOOK), range("2026-09-15", "2026-10-15")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMON_CONSUMER_RANGE_OVERLAP"));
        // the next days are free
        send(chairman, post(BOOK), range("2026-10-01", "2026-10-15")).andExpect(status().isOk());
        assertThat(runCount()).isEqualTo(2);
    }

    @Test
    void aBookingWithAnOutdatedDigestIsRefusedAndWritesNothing() throws Exception {
        UUID lighting = consumer("Освітлення", 1000, 1050);
        billing(lighting, "EQUAL_PER_PLOT", List.of()).andExpect(status().isOk());
        String digest = body(send(chairman, post(PREVIEW), range()).andExpect(status().isOk())).get("digest")
                .asString();
        // the board marks the consumer as covered by the membership fee
        billing(lighting, "NOT_CHARGED", List.of()).andExpect(status().isOk());

        send(chairman, post(BOOK), range(digest)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PREVIEW_OUTDATED"));
        assertThat(runCount()).isZero();
        assertThat(chargeCount()).isZero();
    }

    // ---- reversal

    @Test
    void aReversalReversesEveryChargeWithTheRunsReasonAndFreesTheDays() throws Exception {
        UUID lighting = consumer("Освітлення", 1000, 1050);
        billing(lighting, "EQUAL_PER_PLOT", List.of()).andExpect(status().isOk());
        UUID run = UUID.fromString(body(send(chairman, post(BOOK), range()).andExpect(status().isOk()))
                .get("runId").asString());

        JsonNode reversed = body(send(chairman, post(RUNS + "/" + run + "/reverse"),
                Map.of("reason", "  Помилкові покази  ")).andExpect(status().isOk()));

        assertThat(reversed.get("status").asString()).isEqualTo("REVERSED");
        assertThat(reversed.get("reverseReason").asString()).isEqualTo("Помилкові покази");
        assertThat(reversed.get("reversible").asBoolean()).isFalse();
        assertThat(lines(only(reversed.get("consumers")).get("plots"), "plotNumber", "reversed"))
                .containsExactly("1|true", "2|true", "3|true", "4|true");
        assertThat(owner().queryForList("select amount::text || '|' || reason || '|' || source from charge "
                + "where kind = 'REVERSAL' and run_id = ?", String.class, run)).hasSize(4)
                .containsOnly("-54.00|Помилкові покази|MANUAL_REVERSAL");
        assertThat(owner().queryForList("select distinct run_status from common_consumer_run_line where run_id = ?",
                String.class, run)).containsExactly("REVERSED");

        send(chairman, post(RUNS + "/" + run + "/reverse"), Map.of("reason", "Ще раз"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RUN_ALREADY_REVERSED"));
        // the same days can be charged again
        send(chairman, post(BOOK), range()).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(10)).until(() -> auditEntries("COMMON_CONSUMER_RUN_REVERSED").size() == 1);
        assertThat((String) auditEntries("COMMON_CONSUMER_RUN_REVERSED").getFirst().get("changes"))
                .contains("Помилкові покази", "-216.00");
    }

    @Test
    void aReversalNeedsAReasonAndAnOpenMonth() throws Exception {
        UUID lighting = consumer("Освітлення", 1000, 1050);
        billing(lighting, "EQUAL_PER_PLOT", List.of()).andExpect(status().isOk());
        UUID run = UUID.fromString(body(send(chairman, post(BOOK), range()).andExpect(status().isOk()))
                .get("runId").asString());

        send(chairman, post(RUNS + "/" + run + "/reverse"), Map.of("reason", "   "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        closeMonth("2026-09");
        send(chairman, post(RUNS + "/" + run + "/reverse"), Map.of("reason", "Помилка"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RUN_IN_CLOSED_MONTH"));
        assertThat(only(body(send(chairman, get(RUNS), null))).get("reversible").asBoolean()).isFalse();
        send(chairman, post(RUNS + "/" + UUID.randomUUID() + "/reverse"), Map.of("reason", "Помилка"))
                .andExpect(status().isNotFound());
        send(chairman, get(RUNS + "/" + UUID.randomUUID()), null).andExpect(status().isNotFound());
    }

    // ---- unknown values and the queue

    @Test
    void anUnknownConsumerIsSkippedQueuedAndChargedLaterByARetry() throws Exception {
        UUID lighting = consumer("Освітлення", 1000, 1050);
        UUID well = consumer("Свердловина №1", 300, null);
        billing(lighting, "EQUAL_PER_PLOT", List.of()).andExpect(status().isOk());
        billing(well, "EQUAL_PER_PLOT", List.of()).andExpect(status().isOk());

        JsonNode booked = body(send(chairman, post(BOOK), range()).andExpect(status().isOk()));

        assertThat(lines(booked.get("consumers"), "name", "outcome")).containsExactly("Освітлення|CHARGED",
                "Свердловина №1|UNKNOWN");
        JsonNode warning = booked.get("consumers").get(1).get("warning");
        assertThat(warning.get("code").asString()).isEqualTo("UNKNOWN_VALUE");
        assertThat(warning.get("meterId").asString()).isEqualTo(well.toString());
        assertThat(warning.get("lastReadingOn").asString()).isEqualTo(BEFORE);
        assertThat(booked.get("consumers").get(1).has("consumption")).isFalse();

        JsonNode queue = body(send(chairman, get(NOT_CHARGED), null).andExpect(status().isOk()));
        assertThat(lines(queue, "kind", "reason", "meterId", "from", "to")).containsExactly(
                "COMMON_CONSUMER|UNKNOWN_CONSUMER_VALUE|" + well + "|" + FROM + "|" + TO);
        assertThat(only(queue).get("chargeType").get("code").asString()).isEqualTo("COMMON_CONSUMERS");
        UUID item = UUID.fromString(only(queue).get("id").asString());

        // still unknown: the retry keeps the item
        assertThat(body(send(chairman, post(RETRY.formatted(item)), null).andExpect(status().isOk()))
                .get("resolved").asBoolean()).isFalse();

        // the well is read after the range end: its retry books a run of the well alone
        reading(well, TO, "340");
        assertThat(body(send(chairman, post(RETRY.formatted(item)), null).andExpect(status().isOk()))
                .get("resolved").asBoolean()).isTrue();

        assertThat(runCount()).isEqualTo(2);
        // 40 kWh / 4 = 10 kWh = 43.20 each, after 12.5 kWh = 54.00 of the lighting
        assertThat(owner().queryForList("select amount::text from charge where plot_id = ? order by created_at",
                String.class, p1)).containsExactly("54.00", "43.20");
        assertThat(body(send(chairman, get(NOT_CHARGED), null))).isEmpty();
        await().atMost(Duration.ofSeconds(10)).until(() -> owner().queryForObject(
                "select count(*) from audit_entry where tenant_id = ? and action = 'NOT_CHARGED_RETRIED' "
                        + "and entity_type = 'METER' and entity_id = ?", Integer.class, tenant, well) == 2);
    }

    @Test
    void aRunOverSomeDaysOfAQueuedConsumerNarrowsItsItemAndARunOverTheRestResolvesIt() throws Exception {
        // the well is read on 31.08 only: the run of September queues it
        UUID well = consumer("Свердловина №1", 300, null);
        billing(well, "EQUAL_PER_PLOT", List.of()).andExpect(status().isOk());
        send(chairman, post(BOOK), range()).andExpect(status().isOk());
        assertThat(lines(body(send(chairman, get(NOT_CHARGED), null)), "meterId", "from", "to"))
                .containsExactly(well + "|" + FROM + "|" + TO);

        // read on 15.09: a run of 01.09-15.09 charges the well, the item keeps 16.09-30.09
        reading(well, "2026-09-15", "320");
        JsonNode first = body(send(chairman, post(BOOK), range(FROM, "2026-09-15")).andExpect(status().isOk()));
        assertThat(lines(first.get("consumers"), "name", "consumption", "outcome"))
                .containsExactly("Свердловина №1|20.000|CHARGED");
        assertThat(lines(body(send(chairman, get(NOT_CHARGED), null)), "kind", "reason", "meterId", "from", "to"))
                .containsExactly("COMMON_CONSUMER|UNKNOWN_CONSUMER_VALUE|" + well + "|2026-09-16|" + TO);
        assertThat(only(body(send(chairman, get(NOT_CHARGED), null))).get("chargeType").get("code").asString())
                .isEqualTo("COMMON_CONSUMERS");
        assertThat(owner().queryForList("select status from not_charged_item where tenant_id = ? and meter_id = ? "
                + "and from_date = ?::date and to_date = ?::date", String.class, tenant, well, FROM, TO))
                .containsExactly("RESOLVED");

        // read on 30.09: a run of the days left charges them and resolves the item
        reading(well, TO, "340");
        send(chairman, post(BOOK), range("2026-09-16", TO)).andExpect(status().isOk());
        assertThat(body(send(chairman, get(NOT_CHARGED), null))).isEmpty();
    }

    @Test
    void aRetryOfAnItemOtherRunsChargedSomeDaysOfNarrowsItToTheDaysLeft() throws Exception {
        UUID lighting = consumer("Освітлення", 1000, 1050);
        billing(lighting, "EQUAL_PER_PLOT", List.of()).andExpect(status().isOk());
        send(chairman, post(BOOK), range()).andExpect(status().isOk());
        // an open item of the lighting over 16.09-10.10, of which the run above charged 16.09-30.09
        UUID item = UUID.randomUUID();
        owner().update("insert into not_charged_item (id, tenant_id, item_key, kind, charge_type_id, meter_id, "
                + "from_date, to_date, reason) values (?, ?, ?, 'COMMON_CONSUMER', ?, ?, '2026-09-16', '2026-10-10', "
                + "'UNKNOWN_CONSUMER_VALUE')", item, tenant, "COMMON_CONSUMER:" + lighting + ":2026-09-16:2026-10-10",
                typeId("COMMON_CONSUMERS"), lighting);

        assertThat(body(send(chairman, post(RETRY.formatted(item)), null).andExpect(status().isOk()))
                .get("resolved").asBoolean()).isTrue();

        assertThat(lines(body(send(chairman, get(NOT_CHARGED), null)), "kind", "meterId", "from", "to"))
                .containsExactly("COMMON_CONSUMER|" + lighting + "|2026-10-01|2026-10-10");
        assertThat(runCount()).isEqualTo(1);
    }

    @Test
    void theReadingsABookedRunUsesCannotBeCorrectedUntilItIsReversed() throws Exception {
        UUID lighting = consumer("Освітлення", 1000, 1050);
        billing(lighting, "EQUAL_PER_PLOT", List.of()).andExpect(status().isOk());
        UUID reading = owner().queryForObject("select id from meter_reading where meter_id = ? and taken_on = ?::date",
                UUID.class, lighting, TO);
        UUID run = UUID.fromString(body(send(chairman, post(BOOK), range()).andExpect(status().isOk()))
                .get("runId").asString());

        send(chairman, put("/api/v1/readings/" + reading), Map.of("takenOn", TO, "value", "1060"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("READING_IN_USE"));

        send(chairman, post(RUNS + "/" + run + "/reverse"), Map.of("reason", "Помилковий показ"))
                .andExpect(status().isOk());
        send(chairman, put("/api/v1/readings/" + reading), Map.of("takenOn", TO, "value", "1060"))
                .andExpect(status().isOk());
    }

    // ---- rules of the request

    @Test
    void theRangeAndTheConsumersAreChecked() throws Exception {
        UUID lighting = consumer("Освітлення", 1000, 1050);
        UUID quarter = UUID.randomUUID();
        owner().update("insert into meter (id, tenant_id, kind, resource, serial, name, installed_on, initial_value) "
                + "values (?, ?, 'QUARTER', 'ELECTRICITY', ?, 'Квартал А', ?::date, 0)", quarter, tenant,
                "CC-" + quarter.toString().substring(0, 8), INSTALLED);

        send(chairman, post(PREVIEW), range(TO, FROM)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("to"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        send(chairman, post(PREVIEW), range(FROM, current().plusMonths(1).atDay(1).toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("DATE_IN_FUTURE"));
        Map<String, Object> notAConsumer = range();
        notAConsumer.put("consumerIds", List.of(lighting.toString(), quarter.toString()));
        send(chairman, post(PREVIEW), notAConsumer).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("consumerIds[1]"))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        send(chairman, post(PREVIEW), Map.of("from", FROM)).andExpect(status().isBadRequest());
    }

    @Test
    void aRunCanBeLimitedToSomeConsumers() throws Exception {
        consumer("Освітлення", 1000, 1050);
        UUID well = consumer("Свердловина №1", 300, 340);
        owner().update("insert into cooperative_settings (tenant_id, common_consumer_default_split) "
                + "values (?, 'EQUAL_PER_PLOT')", tenant);
        Map<String, Object> onlyWell = range();
        onlyWell.put("consumerIds", List.of(well.toString()));

        JsonNode preview = body(send(chairman, post(PREVIEW), onlyWell).andExpect(status().isOk()));

        assertThat(lines(preview.get("consumers"), "name", "split", "amount"))
                .containsExactly("Свердловина №1|EQUAL_PER_PLOT|172.80");
    }

    // ---- the settings of a consumer

    @Test
    void theSettingsOfAConsumerAreReadChangedValidatedAndAudited() throws Exception {
        UUID well = consumer("Свердловина №1", 300, 360);
        UUID archived = owner().queryForObject("select id from plot where tenant_id = ? and number = '5'", UUID.class,
                tenant);
        UUID road = owner().queryForObject("select id from plot where tenant_id = ? and number = 'Дорога'", UUID.class,
                tenant);

        JsonNode initial = body(send(chairman, get(BILLING.formatted(well)), null).andExpect(status().isOk()));
        assertThat(initial.get("meterId").asString()).isEqualTo(well.toString());
        assertThat(initial.get("name").asString()).isEqualTo("Свердловина №1");
        assertThat(initial.get("active").asBoolean()).isTrue();
        assertThat(initial.has("split")).isFalse();
        assertThat(initial.get("effectiveSplit").asString()).isEqualTo("CONNECTED_PLOTS");
        assertThat(initial.get("servicePlotIds")).isEmpty();

        JsonNode saved = body(billing(well, "SERVICE_USERS", List.of(p3, p1)).andExpect(status().isOk()));
        assertThat(saved.get("split").asString()).isEqualTo("SERVICE_USERS");
        assertThat(saved.get("effectiveSplit").asString()).isEqualTo("SERVICE_USERS");
        assertThat(lines(saved.get("servicePlotIds"))).containsExactly(p1.toString(), p3.toString());
        assertThat(lines(body(send(chairman, get(BILLING.formatted(well)), null)).get("servicePlotIds")))
                .containsExactly(p1.toString(), p3.toString());
        // saving the same again changes nothing and writes no entry
        billing(well, "SERVICE_USERS", List.of(p1, p3)).andExpect(status().isOk());
        // back to the cooperative's default, without users
        JsonNode reset = body(billing(well, null, List.of()).andExpect(status().isOk()));
        assertThat(reset.has("split")).isFalse();
        assertThat(reset.get("effectiveSplit").asString()).isEqualTo("CONNECTED_PLOTS");
        assertThat(owner().queryForObject("select count(*) from common_consumer_billing where tenant_id = ?",
                Integer.class, tenant)).isZero();

        await().atMost(Duration.ofSeconds(10)).until(
                () -> auditEntries("COMMON_CONSUMER_BILLING_CHANGED").size() == 2);
        List<Map<String, Object>> entries = auditEntries("COMMON_CONSUMER_BILLING_CHANGED");
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.get("entity_type")).isEqualTo("METER");
            assertThat(entry.get("entity_label")).isEqualTo("Свердловина №1");
        });
        assertThat(entries.stream().map(entry -> (String) entry.get("changes")).toList())
                .anySatisfy(changes -> assertThat(changes).contains("SERVICE_USERS", "1, 3"));

        // validation
        billing(well, "SERVICE_USERS", List.of(p1, UUID.randomUUID(), p1, archived, road))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("servicePlotIds[1]"))
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_PLOT"))
                .andExpect(jsonPath("$.errors[1].code").value("DUPLICATE"))
                .andExpect(jsonPath("$.errors[2].code").value("PLOT_ARCHIVED"))
                .andExpect(jsonPath("$.errors[3].code").value("PLOT_COMMON_LAND"));
        send(chairman, put(BILLING.formatted(well)), Map.of("split", "SOMETIMES", "servicePlotIds", List.of()))
                .andExpect(status().isBadRequest());
        // a meter that is not a common consumer, or unknown
        UUID plotMeter = UUID.randomUUID();
        owner().update("insert into meter (id, tenant_id, kind, resource, serial, plot_id, installed_on, "
                + "initial_value) values (?, ?, 'PLOT', 'ELECTRICITY', ?, ?, ?::date, 0)", plotMeter, tenant,
                "CC-" + plotMeter.toString().substring(0, 8), p1, INSTALLED);
        send(chairman, get(BILLING.formatted(plotMeter)), null).andExpect(status().isNotFound());
        billing(plotMeter, "EQUAL_PER_PLOT", List.of()).andExpect(status().isNotFound());
        send(chairman, get(BILLING.formatted(UUID.randomUUID())), null).andExpect(status().isNotFound());
    }

    @Test
    void aReplacedConsumerShowsAndIsChargedWithTheSettingsOfItsChain() throws Exception {
        UUID old = consumer("Освітлення", 1000, null);
        billing(old, "SERVICE_USERS", List.of(p1, p2)).andExpect(status().isOk());
        // replaced on 15.09 at 1 020; the new meter starts at 0 and reads 30 on 30.09: 20 + 30 = 50 kWh
        UUID next = replace(old, "2026-09-15", "1020", "0");
        reading(next, TO, "30");

        // the new meter shows the settings the old one was given, and a run charges with them
        JsonNode inherited = body(send(chairman, get(BILLING.formatted(next)), null).andExpect(status().isOk()));
        assertThat(inherited.get("meterId").asString()).isEqualTo(next.toString());
        assertThat(inherited.get("split").asString()).isEqualTo("SERVICE_USERS");
        assertThat(inherited.get("effectiveSplit").asString()).isEqualTo("SERVICE_USERS");
        assertThat(lines(inherited.get("servicePlotIds"))).containsExactly(p1.toString(), p2.toString());
        JsonNode consumers = body(send(chairman, post(PREVIEW), range()).andExpect(status().isOk()))
                .get("consumers");
        assertThat(lines(consumers, "split", "consumption", "outcome")).containsExactly("SERVICE_USERS|50.000|CHARGED");
        assertThat(lines(only(consumers).get("plots"), "plotNumber", "kwh", "amount"))
                .containsExactly("1|25.000|108.00", "2|25.000|108.00");

        // users stored on the new meter alone do not hide the split of the old one: each is the latest that has one
        owner().update("insert into common_consumer_user (tenant_id, consumer_meter_id, plot_id) values (?, ?, ?)",
                tenant, next, p3);
        JsonNode mixed = body(send(chairman, get(BILLING.formatted(old)), null).andExpect(status().isOk()));
        assertThat(mixed.get("split").asString()).isEqualTo("SERVICE_USERS");
        assertThat(lines(mixed.get("servicePlotIds"))).containsExactly(p3.toString());
        JsonNode mixedLine = only(body(send(chairman, post(PREVIEW), range()).andExpect(status().isOk()))
                .get("consumers"));
        assertThat(mixedLine.get("split").asString()).isEqualTo("SERVICE_USERS");
        assertThat(lines(mixedLine.get("plots"), "plotNumber", "kwh", "amount")).containsExactly("3|50.000|216.00");
    }

    @Test
    void aChangeThroughAnyMeterOfAReplacedConsumerIsSavedForTheConsumerAndCharged() throws Exception {
        UUID old = consumer("Освітлення", 1000, null);
        billing(old, "SERVICE_USERS", List.of(p1, p2)).andExpect(status().isOk());
        UUID next = replace(old, "2026-09-15", "1020", "0");
        reading(next, TO, "30");

        // changed through the old meter: saved on the new one, the old rows removed
        JsonNode saved = body(billing(old, "EQUAL_PER_PLOT", List.of()).andExpect(status().isOk()));
        assertThat(saved.get("split").asString()).isEqualTo("EQUAL_PER_PLOT");
        assertThat(owner().queryForList("select consumer_meter_id from common_consumer_billing where tenant_id = ?",
                UUID.class, tenant)).containsExactly(next);
        assertThat(owner().queryForObject("select count(*) from common_consumer_user where tenant_id = ?",
                Integer.class, tenant)).isZero();
        for (UUID meter : List.of(old, next)) {
            JsonNode shown = body(send(chairman, get(BILLING.formatted(meter)), null).andExpect(status().isOk()));
            assertThat(shown.get("effectiveSplit").asString()).isEqualTo("EQUAL_PER_PLOT");
            assertThat(shown.get("servicePlotIds")).isEmpty();
        }
        JsonNode booked = body(send(chairman, post(BOOK), range()).andExpect(status().isOk()));
        assertThat(lines(booked.get("consumers"), "split", "amount")).containsExactly("EQUAL_PER_PLOT|216.00");

        // back to the cooperative's default through the new meter: nothing of the old settings comes back
        JsonNode reset = body(billing(next, null, List.of()).andExpect(status().isOk()));
        assertThat(reset.has("split")).isFalse();
        assertThat(body(send(chairman, get(BILLING.formatted(old)), null)).get("effectiveSplit").asString())
                .isEqualTo("CONNECTED_PLOTS");
        assertThat(owner().queryForObject("select count(*) from common_consumer_billing where tenant_id = ?",
                Integer.class, tenant)).isZero();
    }

    // ---- permissions

    @Test
    void theTreasurerSeesTheRunsButRunsReversesAndSetsNothingAndAMemberNothing() throws Exception {
        UUID lighting = consumer("Освітлення", 1000, 1050);
        billing(lighting, "EQUAL_PER_PLOT", List.of()).andExpect(status().isOk());
        UUID run = UUID.fromString(body(send(chairman, post(BOOK), range()).andExpect(status().isOk()))
                .get("runId").asString());

        send(treasurer, get(RUNS), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reversible").value(false));
        send(treasurer, get(RUNS + "/" + run), null).andExpect(status().isOk());
        send(treasurer, post(PREVIEW), range()).andExpect(status().isForbidden());
        send(treasurer, post(BOOK), range()).andExpect(status().isForbidden());
        send(treasurer, post(RUNS + "/" + run + "/reverse"), Map.of("reason", "Помилка"))
                .andExpect(status().isForbidden());
        send(treasurer, get(BILLING.formatted(lighting)), null).andExpect(status().isForbidden());
        send(treasurer, put(BILLING.formatted(lighting)), Map.of("servicePlotIds", List.of()))
                .andExpect(status().isForbidden());
        for (String bearer : List.of(member,
                TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer())) {
            send(bearer, get(RUNS), null).andExpect(status().isForbidden());
            send(bearer, get(RUNS + "/" + run), null).andExpect(status().isForbidden());
            send(bearer, post(PREVIEW), range()).andExpect(status().isForbidden());
            send(bearer, post(RUNS + "/" + run + "/reverse"), Map.of("reason", "Помилка"))
                    .andExpect(status().isForbidden());
            send(bearer, get(BILLING.formatted(lighting)), null).andExpect(status().isForbidden());
        }
        assertThat(runCount()).isEqualTo(1);
    }

    // ---- fixtures

    /** A common consumer meter read on 31.08 and, unless {@code after} is null, on 30.09. */
    private UUID consumer(String name, int before, Integer after) {
        UUID id = UUID.randomUUID();
        owner().update("insert into meter (id, tenant_id, kind, resource, serial, name, installed_on, initial_value) "
                + "values (?, ?, 'COMMON_CONSUMER', 'ELECTRICITY', ?, ?, ?::date, 0)", id, tenant,
                "CC-" + id.toString().substring(0, 8), name, INSTALLED);
        reading(id, BEFORE, Integer.toString(before));
        if (after != null) {
            reading(id, TO, Integer.toString(after));
        }
        return id;
    }

    private void reading(UUID meter, String on, String value) {
        owner().update("insert into meter_reading (tenant_id, meter_id, taken_on, value, source, status) "
                + "values (?, ?, ?::date, ?, 'ENTERED', 'APPROVED')", tenant, meter, on, new BigDecimal(value));
    }

    /** Replaces the meter as the chairman (the API's replacement); returns the new meter. */
    private UUID replace(UUID meter, String on, String finalReading, String initialReading) throws Exception {
        Map<String, Object> replacement = new LinkedHashMap<>();
        replacement.put("replacedOn", on);
        replacement.put("finalReading", finalReading);
        replacement.put("serial", "CC-NEW-" + UUID.randomUUID().toString().substring(0, 6));
        replacement.put("initialReading", initialReading);
        return UUID.fromString(body(send(chairman, post("/api/v1/meters/" + meter + "/replace"), replacement)
                .andExpect(status().isCreated())).get("id").asString());
    }

    /** Connects the plot to the well from {@code from} until {@code until} (null: lasting). */
    private void connect(UUID plot, UUID well, String from, String until) {
        owner().update("insert into plot_water_connection (tenant_id, plot_id, source_meter_id, valid_from, valid_to) "
                + "values (?, ?, ?, ?::date, ?::date)", tenant, plot, well, from, until);
    }

    /** Saves the settings of the consumer as the chairman; {@code split} null for the cooperative's default. */
    private ResultActions billing(UUID consumer, String split, List<UUID> users) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (split != null) {
            body.put("split", split);
        }
        body.put("servicePlotIds", users.stream().map(UUID::toString).toList());
        return send(chairman, put(BILLING.formatted(consumer)), body);
    }

    private static Map<String, Object> range() {
        return range(FROM, TO);
    }

    private static Map<String, Object> range(String from, String to) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from);
        body.put("to", to);
        return body;
    }

    private static Map<String, Object> range(String digest) {
        Map<String, Object> body = range();
        body.put("digest", digest);
        return body;
    }

    private int runCount() {
        return owner().queryForObject("select count(*) from common_consumer_run where tenant_id = ?", Integer.class,
                tenant);
    }

    private List<Map<String, Object>> auditEntries(String action) {
        return owner().queryForList("select entity_type, entity_label, cast(changes as text) as changes "
                + "from audit_entry where tenant_id = ? and action = ?", tenant, action);
    }

    private static JsonNode only(JsonNode array) {
        assertThat(array).hasSize(1);
        return array.get(0);
    }

    /** The given fields of every element joined by {@code |}; without fields, the elements themselves. */
    private static List<String> lines(JsonNode array, String... fields) {
        List<String> lines = new ArrayList<>();
        for (JsonNode element : array) {
            if (fields.length == 0) {
                lines.add(element.asString());
                continue;
            }
            List<String> values = new ArrayList<>();
            for (String field : fields) {
                JsonNode value = element.get(field);
                values.add(value == null || value.isNull() ? "" : value.asString());
            }
            lines.add(String.join("|", values));
        }
        return lines;
    }
}
