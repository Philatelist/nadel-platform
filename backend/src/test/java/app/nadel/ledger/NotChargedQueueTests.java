package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The "not charged" queue (feature 011 §2.7): the list with plot, charge type, reason and the tariffs to fix; a retry
 * that keeps the item while the cause is not fixed, charges and closes it once it is (also into the next open month
 * when its month was closed), closes it when nothing is left to charge; the next month run closing it; who may list and
 * retry; the audit entry. The queued case is plot 12 in the scopes of two membership tariffs.
 */
class NotChargedQueueTests extends FreshCooperativeSupport {

    private String month;
    private UUID plot12;
    private Tariff main;
    private Tariff extra;

    @BeforeEach
    void plot12InTwoMembershipTariffs() {
        month = ym(0);
        plot("7", "600.00");
        plot12 = plot("12", "600.00");
        main = tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "50.0000", "2026-01-01");
        extra = tariff("MEMBERSHIP", "Додатковий внесок", "PER_PLOT", "MONTHLY", "100.0000", "2026-01-01");
        select(extra, plot12);
    }

    private UUID queued(String runMonth) throws Exception {
        JsonNode booked = body(send(chairman, post(RUN.formatted(runMonth, false)), null)
                .andExpect(status().isOk()));
        return UUID.fromString(booked.get("notCharged").get(0).get("id").asString());
    }

    private JsonNode list() throws Exception {
        return body(send(chairman, get(NOT_CHARGED), null).andExpect(status().isOk()));
    }

    private JsonNode retry(UUID item) throws Exception {
        return body(send(chairman, post(RETRY.formatted(item)), null).andExpect(status().isOk()));
    }

    private JsonNode run(String runMonth, boolean preview, String digest) throws Exception {
        return body(send(chairman, post(RUN.formatted(runMonth, preview)), digest == null ? null : digest(digest))
                .andExpect(status().isOk()));
    }

    private String itemStatus(UUID item) {
        return owner().queryForObject("select status from not_charged_item where id = ?", String.class, item);
    }

    @Test
    void theListShowsThePlotTheChargeTypeTheReasonAndBothTariffs() throws Exception {
        UUID item = queued(month);

        assertThat(list()).singleElement().satisfies(entry -> {
            assertThat(entry.get("id").asString()).isEqualTo(item.toString());
            assertThat(entry.get("kind").asString()).isEqualTo("TARIFF_MONTH");
            assertThat(entry.get("reason").asString()).isEqualTo("CONFLICTING_TARIFFS");
            assertThat(entry.get("plotNumber").asString()).isEqualTo("12");
            assertThat(entry.get("chargeType").get("code").asString()).isEqualTo("MEMBERSHIP");
            assertThat(entry.get("chargeType").get("nameCustomized").asBoolean()).isFalse();
            assertThat(entry.get("month").asString()).isEqualTo(month);
            assertThat(entry.get("to").asString()).isEqualTo(YearMonth.parse(month).atEndOfMonth().toString());
            assertThat(entry.get("tariffs").get(0).get("id").asString()).isEqualTo(extra.id().toString());
            assertThat(entry.get("tariffs").get(1).get("id").asString()).isEqualTo(main.id().toString());
        });
    }

    @Test
    void theListHasTheNewestMonthFirstThenThePlotsInNaturalOrder() throws Exception {
        UUID plot2 = plot("2", "600.00");
        select(extra, plot2);
        queued(ym(1));
        queued(month);

        JsonNode items = list();

        assertThat(items).hasSize(4);
        assertThat(items.get(0).get("month").asString()).isEqualTo(month);
        assertThat(items.get(0).get("plotId").asString()).isEqualTo(plot2.toString());
        assertThat(items.get(1).get("plotId").asString()).isEqualTo(plot12.toString());
        assertThat(items.get(2).get("month").asString()).isEqualTo(ym(1));
    }

    @Test
    void aRetryWithoutAFixKeepsTheItemWithItsReason() throws Exception {
        UUID item = queued(month);

        JsonNode result = retry(item);

        assertThat(result.get("resolved").asBoolean()).isFalse();
        assertThat(result.get("item").get("id").asString()).isEqualTo(item.toString());
        assertThat(result.get("item").get("reason").asString()).isEqualTo("CONFLICTING_TARIFFS");
        assertThat(result.get("item").get("tariffs")).hasSize(2);
        assertThat(itemStatus(item)).isEqualTo("OPEN");
        assertThat(list()).hasSize(1);
        assertThat(charges(plot12)).isEmpty();
    }

    @Test
    void aRetryAfterTheFixChargesThePlotAndTheItemDisappears() throws Exception {
        UUID item = queued(month);
        unselect(extra, plot12);

        JsonNode result = retry(item);

        assertThat(result.get("resolved").asBoolean()).isTrue();
        assertThat(result.has("item")).isFalse();
        assertThat(itemStatus(item)).isEqualTo("RESOLVED");
        assertThat(owner().queryForObject("select resolved_at is not null from not_charged_item where id = ?",
                Boolean.class, item)).isTrue();
        assertThat(list()).isEmpty();
        List<Map<String, Object>> charges = charges(plot12);
        assertThat(charges).singleElement().satisfies(charge -> {
            assertThat(charge.get("tariff_id")).isEqualTo(main.id());
            assertThat(charge.get("amount")).isEqualTo("25.00");
            assertThat(date(charge.get("billing_month"))).isEqualTo(YearMonth.parse(month).atDay(1));
        });
        // a second retry of the closed item changes nothing
        assertThat(retry(item).get("resolved").asBoolean()).isTrue();
        assertThat(charges(plot12)).hasSize(1);
        // and the audit entry says what happened
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(owner().queryForObject(
                "select count(*) from audit_entry where tenant_id = ? and action = 'NOT_CHARGED_RETRIED'",
                Integer.class, tenant)).isEqualTo(1));
        Map<String, Object> entry = owner().queryForMap("select entity_type, entity_id, entity_label, "
                + "cast(changes as text) as changes from audit_entry where tenant_id = ? "
                + "and action = 'NOT_CHARGED_RETRIED'", tenant);
        assertThat(entry.get("entity_type")).isEqualTo("CHARGE");
        assertThat(entry.get("entity_id")).isEqualTo(plot12);
        assertThat(entry.get("entity_label")).isEqualTo("12");
        JsonNode changes = json.readTree((String) entry.get("changes"));
        assertThat(changes.toString()).contains("\"kind\"", "TARIFF_MONTH", "CONFLICTING_TARIFFS",
                "base:MEMBERSHIP", month, "RESOLVED");
    }

    @Test
    void aRetryWithNothingLeftToChargeClosesTheItem() throws Exception {
        UUID item = queued(month);
        exclude(main, plot12);
        unselect(extra, plot12);

        assertThat(retry(item).get("resolved").asBoolean()).isTrue();

        assertThat(itemStatus(item)).isEqualTo("OBSOLETE");
        assertThat(charges(plot12)).isEmpty();
        assertThat(list()).isEmpty();
    }

    @Test
    void aRetryOfAnItemOfAClosedMonthBooksInTheNextOpenMonth() throws Exception {
        UUID item = queued(ym(1));
        closeMonth(ym(1));
        unselect(extra, plot12);

        assertThat(retry(item).get("resolved").asBoolean()).isTrue();

        assertThat(charges(plot12)).singleElement().satisfies(charge -> {
            assertThat(date(charge.get("billing_month"))).isEqualTo(YearMonth.parse(month).atDay(1));
            assertThat(date(charge.get("service_from"))).isEqualTo(YearMonth.parse(ym(1)).atDay(1));
            assertThat(date(charge.get("accrued_on"))).isEqualTo(YearMonth.parse(ym(1)).atDay(1));
        });
    }

    @Test
    void theNextRunClosesTheItemsItCanNowCharge() throws Exception {
        UUID item = queued(month);
        unselect(extra, plot12);

        JsonNode rerun = body(send(chairman, post(RUN.formatted(month, false)), null).andExpect(status().isOk()));

        assertThat(rerun.get("created").asInt()).isEqualTo(1);
        assertThat(rerun.get("notCharged")).isEmpty();
        assertThat(itemStatus(item)).isEqualTo("RESOLVED");
        assertThat(charges(plot12)).hasSize(1);
        JsonNode page = body(send(chairman, get(MONTH_PAGE.formatted(month)), null).andExpect(status().isOk()));
        assertThat(page.get("checklist").get("notChargedCount").asInt()).isZero();
    }

    @Test
    void theNextRunMakesAnItemWithNothingLeftObsoleteAndAConflictOfOtherTariffsUpdatesIt() throws Exception {
        UUID item = queued(month);
        Tariff third = tariff("MEMBERSHIP", "Третій внесок", "PER_PLOT", "MONTHLY", "10.0000", "2026-01-01");
        select(third, plot12);
        unselect(extra, plot12);

        JsonNode rerun = body(send(chairman, post(RUN.formatted(month, false)), null).andExpect(status().isOk()));

        assertThat(rerun.get("notCharged").get(0).get("id").asString()).isEqualTo(item.toString());
        assertThat(itemStatus(item)).isEqualTo("OPEN");
        assertThat(retry(item).get("item").get("tariffs").get(0).get("name").asString()).isEqualTo("Третій внесок");

        exclude(main, plot12);
        unselect(third, plot12);
        body(send(chairman, post(RUN.formatted(month, false)), null).andExpect(status().isOk()));
        assertThat(itemStatus(item)).isEqualTo("OBSOLETE");
    }

    @Test
    void theNextMonthRunPreviewsAndBooksTheFixedItemOfAnEarlierMonthWithItsOwnServicePeriod() throws Exception {
        String earlier = ym(1);
        UUID item = queued(earlier);
        closeMonth(earlier);
        unselect(extra, plot12);
        int before = chargeCount();

        // the preview of this month lists the charge of the earlier item and writes nothing
        JsonNode preview = body(send(chairman, post(RUN.formatted(month, true)), null).andExpect(status().isOk()));

        // this month: plots 7 and 12 at 25.00 each; the earlier month's item: plot 12 at 25.00
        assertThat(preview.get("plots").asInt()).isEqualTo(2);
        assertThat(total(preview, "MEMBERSHIP")).isEqualTo("75.00");
        assertThat(preview.get("total").asString()).isEqualTo("75.00");
        assertThat(itemStatus(item)).isEqualTo("OPEN");
        assertThat(chargeCount()).isEqualTo(before);
        assertThat(charges(plot12)).isEmpty();

        JsonNode booked = run(month, false, preview.get("digest").asString());

        assertThat(booked.get("created").asInt()).isEqualTo(3);
        assertThat(booked.get("total").asString()).isEqualTo("75.00");
        assertThat(itemStatus(item)).isEqualTo("RESOLVED");
        assertThat(list()).isEmpty();
        Map<String, Object> earlierCharge = charges(plot12).stream()
                .filter(c -> date(c.get("service_from")).equals(YearMonth.parse(earlier).atDay(1))).findFirst()
                .orElseThrow();
        assertThat(earlierCharge.get("tariff_id")).isEqualTo(main.id());
        assertThat(earlierCharge.get("amount")).isEqualTo("25.00");
        assertThat(earlierCharge.get("source_key")).isEqualTo("TARIFF:" + main.id() + ":" + plot12 + ":" + earlier);
        assertThat(date(earlierCharge.get("service_to"))).isEqualTo(YearMonth.parse(earlier).atEndOfMonth());
        assertThat(date(earlierCharge.get("accrued_on"))).isEqualTo(YearMonth.parse(earlier).atDay(1));
        // its month was closed, so it is booked in the earliest open month after it
        assertThat(date(earlierCharge.get("billing_month"))).isEqualTo(YearMonth.parse(month).atDay(1));
        assertThat(charges(plot12)).hasSize(2);
    }

    @Test
    void theNextMonthRunKeepsAnEarlierItemOpenWhileItsConflictRemainsWithTheTariffsOfTheRun() throws Exception {
        String earlier = ym(1);
        UUID item = queued(earlier);
        Tariff third = tariff("MEMBERSHIP", "Третій внесок", "PER_PLOT", "MONTHLY", "10.0000", "2026-01-01");
        select(third, plot12);
        unselect(extra, plot12);

        JsonNode booked = run(month, false, null);

        assertThat(itemStatus(item)).isEqualTo("OPEN");
        assertThat(charges(plot12)).isEmpty();
        // this month's conflict is queued as well; the earlier item now names the tariffs of this run
        assertThat(booked.get("notCharged")).singleElement()
                .satisfies(entry -> assertThat(entry.get("month").asString()).isEqualTo(month));
        JsonNode earlierItem = null;
        for (JsonNode entry : list()) {
            if (entry.get("id").asString().equals(item.toString())) {
                earlierItem = entry;
            }
        }
        assertThat(earlierItem).isNotNull();
        List<String> names = new ArrayList<>();
        earlierItem.get("tariffs").forEach(t -> names.add(t.get("name").asString()));
        assertThat(names).containsExactly("Третій внесок", "Членський внесок");
    }

    @Test
    void anUnknownItemOrOneOfAnotherCooperativeIsNotFound() throws Exception {
        UUID item = queued(month);

        send(chairman, post(RETRY.formatted(UUID.randomUUID())), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        String demoChairman = TestTokens.bearer(DEMO_TENANT, "sub-chairman@demo.local", "chairman@demo.local");
        send(demoChairman, post(RETRY.formatted(item)), null).andExpect(status().isNotFound());
        JsonNode demoQueue = body(send(demoChairman, get(NOT_CHARGED), null).andExpect(status().isOk()));
        demoQueue.forEach(entry -> assertThat(entry.get("id").asString()).isNotEqualTo(item.toString()));
        unlinkByEmail(DEMO_TENANT, "chairman@demo.local");
    }

    @Test
    void onlyChargesRunMayRetry() throws Exception {
        UUID item = queued(month);
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        for (String bearer : List.of(treasurer, member, operator)) {
            send(bearer, post(RETRY.formatted(item)), null).andExpect(status().isForbidden());
        }
        send(treasurer, get(NOT_CHARGED), null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        send(operator, get(NOT_CHARGED), null).andExpect(status().isForbidden());
        assertThat(itemStatus(item)).isEqualTo("OPEN");
    }
}
