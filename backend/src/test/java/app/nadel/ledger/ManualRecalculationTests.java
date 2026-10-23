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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The manual recalculation of tariff charges through the API (feature 012 §2.1, §2.2, §2.3, §2.6 to §2.8, slice 3):
 * the preview writes nothing, the booking reverses and charges again in the open month and adjusts the closed month in
 * the earliest open month, with the original's service period and debt date; a second run finds nothing; a stale
 * preview, a missing or too long reason, a future month and a range over 24 months are refused; a closed month keeps
 * its charges and totals; a charge reversed by hand stays; a month the recalculation reversed and queued stays queued
 * on a retry or a month run until it can be charged, then is charged once under the recalculation's suffix; the record,
 * its lines and its audit entry; who may preview and book. Months are relative to the current month in Europe/Kyiv: the
 * previous month ("September") is closed, the current one ("October") open. Membership 50.00 per sotka per year:
 * 600 m2 pays 25.00 a month, 700 m2 29.17.
 */
class ManualRecalculationTests extends FreshCooperativeSupport {

    private static final String RECALCULATIONS = "/api/v1/recalculations";
    private static final String REASON = "Виправлено площу ділянки";

    private String september;
    private String october;
    private UUID plot7;
    private UUID plot9;
    private Tariff membership;

    @BeforeEach
    void membershipAndLandTaxBookedForTwoMonths() {
        september = ym(1);
        october = ym(0);
        plot7 = plot("7", "600.00");
        plot9 = plot("9", "612.00");
        membership = tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "50.0000", "2020-01-01");
        tariff("LAND_TAX", "Земельний податок", "PER_AREA_SOTKA", "YEARLY", "35.0000", "2020-01-01");
    }

    /** The month run of September and October booked, then September closed. */
    private void bookSeptemberAndOctoberThenCloseSeptember() throws Exception {
        send(chairman, post(RUN.formatted(september, false)), null).andExpect(status().isOk());
        send(chairman, post(RUN.formatted(october, false)), null).andExpect(status().isOk());
        closeMonth(september);
    }

    private static Map<String, Object> manual(String from, String to, List<UUID> types, List<UUID> plots,
            String reason, String digest) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("from", from);
        scope.put("to", to);
        scope.put("chargeTypeIds", types);
        scope.put("plotIds", plots);
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

    private Map<String, Object> plot7Membership(String reason, String digest) {
        return manual(september, october, List.of(typeId("MEMBERSHIP")), List.of(plot7), reason, digest);
    }

    private JsonNode preview(Map<String, Object> body) throws Exception {
        return body(send(chairman, post(RECALCULATIONS).param("preview", "true"), body).andExpect(status().isOk()));
    }

    private JsonNode book(Map<String, Object> body) throws Exception {
        return body(send(chairman, post(RECALCULATIONS).param("preview", "false"), body)
                .andExpect(status().isOk()));
    }

    private Map<String, Object> membershipCharge(UUID plot, String month) {
        return charges(plot).stream().filter(c -> "REGULAR".equals(c.get("kind")))
                .filter(c -> membership.id().equals(c.get("tariff_id")))
                .filter(c -> date(c.get("service_from")).equals(YearMonth.parse(month).atDay(1)))
                .findFirst().orElseThrow();
    }

    private int recalculationCount() {
        return owner().queryForObject("select count(*) from recalculation where tenant_id = ?", Integer.class,
                tenant);
    }

    private String monthTotal(String month, String code) throws Exception {
        return total(body(send(chairman, get(MONTH_PAGE.formatted(month)), null).andExpect(status().isOk())), code);
    }

    private static String debt(JsonNode account, String code) {
        for (JsonNode debt : account.get("debts")) {
            if (code.equals(debt.get("chargeTypeCode").asString())) {
                return debt.get("debt").asString();
            }
        }
        return null;
    }

    // ---- the area 600 -> 700 example, end to end

    @Test
    void theAreaCorrectionAdjustsTheClosedMonthAndReversesAndChargesTheOpenOne() throws Exception {
        bookSeptemberAndOctoberThenCloseSeptember();
        UUID septemberCharge = (UUID) membershipCharge(plot7, september).get("id");
        UUID octoberCharge = (UUID) membershipCharge(plot7, october).get("id");
        String septemberTotal = monthTotal(september, "MEMBERSHIP");
        owner().update("update plot set area_m2 = 700 where id = ?", plot7);
        int before = chargeCount();

        JsonNode preview = preview(plot7Membership(null, null));

        assertThat(preview.get("preview").asBoolean()).isTrue();
        assertThat(preview.get("booked").asBoolean()).isFalse();
        assertThat(preview.has("id")).isFalse();
        assertThat(preview.get("cause").asString()).isEqualTo("MANUAL");
        assertThat(preview.get("causeType").asString()).isEqualTo("MANUAL");
        assertThat(preview.get("digest").asString()).hasSize(64);
        assertThat(preview.get("plotCount").asInt()).isEqualTo(1);
        assertThat(preview.get("total").asString()).isEqualTo("8.34");
        assertThat(total(preview, "MEMBERSHIP")).isEqualTo("8.34");
        assertThat(preview.get("scope").get("months").valueStream().map(JsonNode::asString).toList())
                .containsExactly(september, october);
        JsonNode lines = preview.get("lines");
        assertThat(lines).hasSize(2);
        JsonNode adjust = lines.get(0);
        assertThat(adjust.get("plotNumber").asString()).isEqualTo("7");
        assertThat(adjust.get("action").asString()).isEqualTo("ADJUST");
        assertThat(adjust.get("originalChargeId").asString()).isEqualTo(septemberCharge.toString());
        assertThat(adjust.get("serviceFrom").asString()).isEqualTo(YearMonth.parse(september).atDay(1).toString());
        assertThat(adjust.get("booked").asString()).isEqualTo("25.00");
        assertThat(adjust.get("adjustments").asString()).isEqualTo("0.00");
        assertThat(adjust.get("effective").asString()).isEqualTo("25.00");
        assertThat(adjust.get("expected").asString()).isEqualTo("29.17");
        assertThat(adjust.get("difference").asString()).isEqualTo("4.17");
        assertThat(adjust.get("bookedIn").asString()).isEqualTo(october);
        assertThat(adjust.get("reasons").valueStream().map(JsonNode::asString).toList()).containsExactly("AREA");
        assertThat(adjust.get("createdChargeIds")).isEmpty();
        JsonNode recharge = lines.get(1);
        assertThat(recharge.get("action").asString()).isEqualTo("REVERSE_AND_RECHARGE");
        assertThat(recharge.get("originalChargeId").asString()).isEqualTo(octoberCharge.toString());
        assertThat(recharge.get("booked").asString()).isEqualTo("25.00");
        assertThat(recharge.get("expected").asString()).isEqualTo("29.17");
        assertThat(recharge.get("difference").asString()).isEqualTo("4.17");
        assertThat(recharge.get("bookedIn").asString()).isEqualTo(october);
        // a preview writes nothing
        assertThat(chargeCount()).isEqualTo(before);
        assertThat(recalculationCount()).isZero();

        JsonNode booked = book(plot7Membership(REASON, preview.get("digest").asString()));

        assertThat(booked.get("preview").asBoolean()).isFalse();
        assertThat(booked.get("booked").asBoolean()).isTrue();
        assertThat(booked.get("digest").asString()).isEqualTo(preview.get("digest").asString());
        assertThat(booked.get("reason").asString()).isEqualTo(REASON);
        String id = booked.get("id").asString();
        UUID recalculation = UUID.fromString(id);
        assertThat(chargeCount()).isEqualTo(before + 3);

        // September (closed): an adjustment of +4.17 booked in October, with September's service period and date
        JsonNode bookedAdjust = booked.get("lines").get(0);
        UUID adjustmentId = UUID.fromString(bookedAdjust.get("adjustmentChargeId").asString());
        assertThat(bookedAdjust.get("createdChargeIds").valueStream().map(JsonNode::asString).toList())
                .containsExactly(adjustmentId.toString());
        Map<String, Object> adjustment = owner().queryForMap("select c.kind, c.source, c.amount::text as amount, "
                + "c.service_from, c.service_to, c.accrued_on, c.reason, c.adjusts_charge_id, c.recalculation_id, "
                + "m.month as billing_month from charge c join billing_month m on m.id = c.billing_month_id "
                + "where c.id = ?", adjustmentId);
        assertThat(adjustment.get("kind")).isEqualTo("ADJUSTMENT");
        assertThat(adjustment.get("source")).isEqualTo("RECALCULATION");
        assertThat(adjustment.get("amount")).isEqualTo("4.17");
        assertThat(date(adjustment.get("billing_month"))).isEqualTo(YearMonth.parse(october).atDay(1));
        assertThat(date(adjustment.get("service_from"))).isEqualTo(YearMonth.parse(september).atDay(1));
        assertThat(date(adjustment.get("service_to"))).isEqualTo(YearMonth.parse(september).atEndOfMonth());
        assertThat(date(adjustment.get("accrued_on"))).isEqualTo(YearMonth.parse(september).atDay(1));
        assertThat(adjustment.get("reason")).isEqualTo(REASON);
        assertThat(adjustment.get("adjusts_charge_id")).isEqualTo(septemberCharge);
        assertThat(adjustment.get("recalculation_id")).isEqualTo(recalculation);

        // October (open): the charge reversed with the reason and charged again at 29.17
        JsonNode bookedRecharge = booked.get("lines").get(1);
        UUID reversalId = UUID.fromString(bookedRecharge.get("reversalChargeId").asString());
        UUID newId = UUID.fromString(bookedRecharge.get("newChargeId").asString());
        assertThat(bookedRecharge.get("createdChargeIds").valueStream().map(JsonNode::asString).toList())
                .containsExactly(reversalId.toString(), newId.toString());
        Map<String, Object> reversal = owner().queryForMap("select kind, source, amount::text as amount, reason, "
                + "reverses_charge_id, recalculation_id from charge where id = ?", reversalId);
        assertThat(reversal.get("kind")).isEqualTo("REVERSAL");
        assertThat(reversal.get("source")).isEqualTo("RECALCULATION");
        assertThat(reversal.get("amount")).isEqualTo("-25.00");
        assertThat(reversal.get("reason")).isEqualTo(REASON);
        assertThat(reversal.get("reverses_charge_id")).isEqualTo(octoberCharge);
        assertThat(reversal.get("recalculation_id")).isEqualTo(recalculation);
        Map<String, Object> recharged = owner().queryForMap("select c.kind, c.source, c.amount::text as amount, "
                + "c.source_key, c.recalculation_id, c.accrued_on, m.month as billing_month from charge c "
                + "join billing_month m on m.id = c.billing_month_id where c.id = ?", newId);
        assertThat(recharged.get("kind")).isEqualTo("REGULAR");
        assertThat(recharged.get("source")).isEqualTo("TARIFF");
        assertThat(recharged.get("amount")).isEqualTo("29.17");
        assertThat(recharged.get("source_key"))
                .isEqualTo("TARIFF:" + membership.id() + ":" + plot7 + ":" + october + "#r" + recalculation);
        assertThat(recharged.get("recalculation_id")).isEqualTo(recalculation);
        assertThat(date(recharged.get("billing_month"))).isEqualTo(YearMonth.parse(october).atDay(1));
        assertThat(owner().queryForList("select units_num::text from charge_part where charge_id = ?", String.class,
                newId)).singleElement().satisfies(units -> assertThat(new java.math.BigDecimal(units))
                        .isEqualByComparingTo("7"));

        // the account: 25.00 + 4.17 (September) and 25.00 - 25.00 + 29.17 (October); plot 9 is untouched
        JsonNode account = body(send(chairman, get(ACCOUNT.formatted(plot7)), null).andExpect(status().isOk()));
        assertThat(debt(account, "MEMBERSHIP")).isEqualTo("58.34");
        JsonNode other = body(send(chairman, get(ACCOUNT.formatted(plot9)), null).andExpect(status().isOk()));
        assertThat(debt(other, "MEMBERSHIP")).isEqualTo("51.00");
        assertThat(charges(plot9)).hasSize(4);

        // the closed month keeps its charges and totals
        assertThat(monthTotal(september, "MEMBERSHIP")).isEqualTo(septemberTotal);
        assertThat(owner().queryForObject("select count(*) from charge c join billing_month m "
                + "on m.id = c.billing_month_id where c.tenant_id = ? and m.month = ?::date", Integer.class, tenant,
                september + "-01")).isEqualTo(4);

        // the record and its lines
        JsonNode record = body(send(chairman, get(RECALCULATIONS + "/" + id), null).andExpect(status().isOk()));
        assertThat(record.get("reason").asString()).isEqualTo(REASON);
        assertThat(record.get("total").asString()).isEqualTo("8.34");
        assertThat(record.get("causeType").asString()).isEqualTo("MANUAL");
        assertThat(record.get("scope").get("plotIds").get(0).asString()).isEqualTo(plot7.toString());
        assertThat(record.get("lines")).hasSize(2);
        assertThat(record.get("lines").get(0).get("action").asString()).isEqualTo("ADJUST");
        assertThat(record.get("lines").get(0).get("bookedIn").asString()).isEqualTo(october);
        assertThat(record.get("lines").get(0).get("reasons").get(0).asString()).isEqualTo("AREA");
        assertThat(record.get("lines").get(0).get("adjustmentChargeId").asString())
                .isEqualTo(adjustmentId.toString());
        assertThat(record.get("lines").get(1).get("newChargeId").asString()).isEqualTo(newId.toString());
        JsonNode list = body(send(chairman, get(RECALCULATIONS), null).andExpect(status().isOk()));
        assertThat(list.get("items").get(0).get("id").asString()).isEqualTo(id);
        assertThat(list.get("items").get(0).get("lineCount").asInt()).isEqualTo(2);
        assertThat(list.get("items").get(0).get("plotCount").asInt()).isEqualTo(1);

        // the audit entry: codes and parameters
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(owner().queryForObject(
                "select count(*) from audit_entry where tenant_id = ? and action = 'RECALCULATION_BOOKED'",
                Integer.class, tenant)).isEqualTo(1));
        Map<String, Object> entry = owner().queryForMap("select entity_type, entity_id, entity_label, "
                + "cast(changes as text) as changes from audit_entry where tenant_id = ? "
                + "and action = 'RECALCULATION_BOOKED'", tenant);
        assertThat(entry.get("entity_type")).isEqualTo("RECALCULATION");
        assertThat(entry.get("entity_id")).isEqualTo(recalculation);
        assertThat(entry.get("entity_label")).isEqualTo(september + "/" + october);
        Map<String, String> values = new LinkedHashMap<>();
        json.readTree((String) entry.get("changes"))
                .forEach(change -> values.put(change.get("field").asString(), change.get("new").asString()));
        assertThat(values).containsExactly(Map.entry("cause", "MANUAL"), Map.entry("from", september),
                Map.entry("to", october), Map.entry("chargeTypes", "base:MEMBERSHIP"), Map.entry("plots", "7"),
                Map.entry("reason", REASON), Map.entry("count", "2"), Map.entry("total", "8.34"));
    }

    @Test
    void aNewRateFromTheOpenMonthReversesAndChargesItAndLeavesTheClosedMonth() throws Exception {
        bookSeptemberAndOctoberThenCloseSeptember();
        version(membership.id(), "PER_AREA_SOTKA", "YEARLY", "60.0000", october + "-01", null);

        JsonNode preview = preview(manual(september, october, List.of(), List.of(), null, null));

        // plot 7: 30.00 instead of 25.00; plot 9 (612 m2): 30.60 instead of 25.50; land tax and September unchanged
        assertThat(preview.get("lines")).hasSize(2);
        assertThat(preview.get("lines").valueStream().map(l -> l.get("action").asString()).toList())
                .containsOnly("REVERSE_AND_RECHARGE");
        assertThat(preview.get("lines").valueStream().map(l -> l.get("plotNumber").asString()).toList())
                .containsExactly("7", "9");
        assertThat(preview.get("lines").get(0).get("expected").asString()).isEqualTo("30.00");
        assertThat(preview.get("lines").get(1).get("expected").asString()).isEqualTo("30.60");
        assertThat(preview.get("lines").get(0).get("reasons").get(0).asString()).isEqualTo("TARIFF_VERSION");
        assertThat(preview.get("total").asString()).isEqualTo("10.10");
        assertThat(preview.get("plotCount").asInt()).isEqualTo(2);

        book(manual(september, october, List.of(), List.of(), REASON, preview.get("digest").asString()));

        JsonNode account = body(send(chairman, get(ACCOUNT.formatted(plot7)), null).andExpect(status().isOk()));
        assertThat(debt(account, "MEMBERSHIP")).isEqualTo("55.00");
        assertThat(debt(account, "LAND_TAX")).isEqualTo("35.00");
        assertThat(owner().queryForObject("select count(*) from charge where tenant_id = ? and kind = 'ADJUSTMENT'",
                Integer.class, tenant)).isZero();
    }

    // ---- idempotency and a stale preview

    @Test
    void theSameRecalculationAgainFindsNoChangesAndBooksNothing() throws Exception {
        bookSeptemberAndOctoberThenCloseSeptember();
        owner().update("update plot set area_m2 = 700 where id = ?", plot7);
        book(plot7Membership(REASON, null));
        int before = chargeCount();

        JsonNode again = preview(plot7Membership(null, null));

        assertThat(again.get("lines")).isEmpty();
        assertThat(again.get("total").asString()).isEqualTo("0.00");
        assertThat(again.get("notCharged")).isEmpty();
        send(chairman, post(RECALCULATIONS).param("preview", "false"),
                plot7Membership(REASON, again.get("digest").asString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_RECALCULATE"));
        assertThat(chargeCount()).isEqualTo(before);
        assertThat(recalculationCount()).isEqualTo(1);
    }

    @Test
    void aChangeBetweenThePreviewAndTheBookingRefusesTheBookingAndWritesNothing() throws Exception {
        bookSeptemberAndOctoberThenCloseSeptember();
        owner().update("update plot set area_m2 = 700 where id = ?", plot7);
        String digest = preview(plot7Membership(null, null)).get("digest").asString();
        owner().update("update plot set area_m2 = 800 where id = ?", plot7);
        int before = chargeCount();

        send(chairman, post(RECALCULATIONS).param("preview", "false"), plot7Membership(REASON, digest))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PREVIEW_OUTDATED"));

        assertThat(chargeCount()).isEqualTo(before);
        assertThat(recalculationCount()).isZero();
        assertThat(owner().queryForObject("select count(*) from recalculation_line where tenant_id = ?",
                Integer.class, tenant)).isZero();
    }

    @Test
    void aChargeReversedByHandStaysReversed() throws Exception {
        bookSeptemberAndOctoberThenCloseSeptember();
        UUID octoberCharge = (UUID) membershipCharge(plot7, october).get("id");
        send(chairman, post(REVERSE.formatted(octoberCharge)), Map.of("reason", "Помилкове нарахування"))
                .andExpect(status().isCreated());
        owner().update("update plot set area_m2 = 700 where id = ?", plot7);

        JsonNode preview = preview(plot7Membership(null, null));

        assertThat(preview.get("lines")).singleElement()
                .satisfies(line -> assertThat(line.get("action").asString()).isEqualTo("ADJUST"));
    }

    // ---- a month a recalculation reversed and queued

    /** A month item opened by a recalculation that reversed the charge, and the recalculation. */
    private record ReversedMonth(Tariff extra, UUID item, UUID recalculation) {
    }

    /**
     * October of plot 7 booked, then a second membership tariff for plot 7: the recalculation of October reverses the
     * charge and queues the conflict.
     */
    private ReversedMonth octoberReversedAndQueued() throws Exception {
        bookSeptemberAndOctoberThenCloseSeptember();
        Tariff extra = tariff("MEMBERSHIP", "Додатковий внесок", "PER_PLOT", "MONTHLY", "100.0000", "2020-01-01");
        select(extra, plot7);
        Map<String, Object> scope = manual(october, october, List.of(typeId("MEMBERSHIP")), List.of(plot7), null,
                null);
        JsonNode preview = preview(scope);
        assertThat(preview.get("lines")).singleElement()
                .satisfies(line -> assertThat(line.get("action").asString()).isEqualTo("REVERSE"));
        JsonNode booked = book(manual(october, october, List.of(typeId("MEMBERSHIP")), List.of(plot7), REASON,
                preview.get("digest").asString()));
        UUID item = owner().queryForObject("select id from not_charged_item where tenant_id = ? and status = 'OPEN' "
                + "and kind = 'TARIFF_MONTH'", UUID.class, tenant);
        return new ReversedMonth(extra, item, UUID.fromString(booked.get("id").asString()));
    }

    private String itemStatus(UUID item) {
        return owner().queryForObject("select status from not_charged_item where id = ?", String.class, item);
    }

    private List<String> octoberMembershipKeys() {
        return charges(plot7).stream().filter(c -> "REGULAR".equals(c.get("kind")))
                .filter(c -> date(c.get("service_from")).equals(YearMonth.parse(october).atDay(1)))
                .filter(c -> membership.id().equals(c.get("tariff_id")))
                .map(c -> (String) c.get("source_key")).toList();
    }

    private String membershipDebt() throws Exception {
        return debt(body(send(chairman, get(ACCOUNT.formatted(plot7)), null).andExpect(status().isOk())),
                "MEMBERSHIP");
    }

    @Test
    void aRetryKeepsAMonthAReversalQueuedOpenUntilItCanBeChargedAndThenChargesItOnce() throws Exception {
        ReversedMonth reversed = octoberReversedAndQueued();
        String base = "TARIFF:" + membership.id() + ":" + plot7 + ":" + october;

        JsonNode unresolved = body(send(chairman, post(RETRY.formatted(reversed.item())), null)
                .andExpect(status().isOk()));
        assertThat(unresolved.get("resolved").asBoolean()).isFalse();
        assertThat(itemStatus(reversed.item())).isEqualTo("OPEN");
        assertThat(membershipDebt()).isEqualTo("25.00");

        unselect(reversed.extra(), plot7);
        JsonNode resolved = body(send(chairman, post(RETRY.formatted(reversed.item())), null)
                .andExpect(status().isOk()));

        assertThat(resolved.get("resolved").asBoolean()).isTrue();
        assertThat(itemStatus(reversed.item())).isEqualTo("RESOLVED");
        assertThat(octoberMembershipKeys()).containsExactly(base, base + "#r" + reversed.recalculation());
        assertThat(membershipDebt()).isEqualTo("50.00");
    }

    @Test
    void theMonthRunKeepsAMonthAReversalQueuedOpenUntilItCanBeChargedAndThenChargesItOnce() throws Exception {
        ReversedMonth reversed = octoberReversedAndQueued();
        String base = "TARIFF:" + membership.id() + ":" + plot7 + ":" + october;

        send(chairman, post(RUN.formatted(october, false)), null).andExpect(status().isOk());
        assertThat(itemStatus(reversed.item())).isEqualTo("OPEN");
        assertThat(membershipDebt()).isEqualTo("25.00");

        unselect(reversed.extra(), plot7);
        send(chairman, post(RUN.formatted(october, false)), null).andExpect(status().isOk());

        assertThat(itemStatus(reversed.item())).isEqualTo("RESOLVED");
        assertThat(octoberMembershipKeys()).containsExactly(base, base + "#r" + reversed.recalculation());
        assertThat(membershipDebt()).isEqualTo("50.00");
        int before = chargeCount();
        send(chairman, post(RUN.formatted(october, false)), null).andExpect(status().isOk());
        assertThat(chargeCount()).isEqualTo(before);
    }

    // ---- refusals

    @Test
    void aBookingNeedsAReasonOfAtMost500Characters() throws Exception {
        bookSeptemberAndOctoberThenCloseSeptember();
        owner().update("update plot set area_m2 = 700 where id = ?", plot7);
        int before = chargeCount();

        for (String reason : new String[] {null, "   "}) {
            send(chairman, post(RECALCULATIONS).param("preview", "false"), plot7Membership(reason, null))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("reason"))
                    .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        }
        send(chairman, post(RECALCULATIONS).param("preview", "false"), plot7Membership("я".repeat(501), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        // a preview needs no reason
        preview(plot7Membership(null, null));
        assertThat(chargeCount()).isEqualTo(before);
        assertThat(recalculationCount()).isZero();

        book(plot7Membership("я".repeat(500), null));
        assertThat(recalculationCount()).isEqualTo(1);
    }

    @Test
    void aFutureMonthAMonthBeforeTheStartAndAnotherCauseAreRefused() throws Exception {
        for (String preview : new String[] {"true", "false"}) {
            send(chairman, post(RECALCULATIONS).param("preview", preview),
                    manual(october, ym(-1), List.of(), List.of(), REASON, null))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("cause.manual.to"))
                    .andExpect(jsonPath("$.errors[0].code").value("DATE_IN_FUTURE"));
        }
        send(chairman, post(RECALCULATIONS).param("preview", "true"),
                manual(october, september, List.of(), List.of(), null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("cause.manual.to"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        send(chairman, post(RECALCULATIONS).param("preview", "true"), Map.of("cause", Map.of("type", "MANUAL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("cause.manual"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        send(chairman, post(RECALCULATIONS).param("preview", "true"),
                Map.of("cause", Map.of("type", "TARIFF_VERSION_UPDATE", "tariffVersion",
                        Map.of("tariffId", membership.id().toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("cause.type"))
                .andExpect(jsonPath("$.errors[0].code").value("UNSUPPORTED"));
    }

    @Test
    void aRangeOfMoreThan24MonthsIsRefused() throws Exception {
        send(chairman, post(RECALCULATIONS).param("preview", "true"),
                manual(ym(24), october, List.of(), List.of(), null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECALCULATION_RANGE_TOO_LONG"));

        JsonNode longest = preview(manual(ym(23), october, List.of(), List.of(), null, null));

        assertThat(longest.get("scope").get("months")).hasSize(24);
    }

    @Test
    void unknownArchivedAndCommonLandPlotsAndUnknownOrInactiveTypesAreRefused() throws Exception {
        UUID archived = plot("A-1", "600.00", "PLOT", true);
        UUID commonLand = plot("CL-1", "0.00", "COMMON", false);

        send(chairman, post(RECALCULATIONS).param("preview", "true"), manual(september, october,
                List.of(UUID.randomUUID(), typeId("WATER")), List.of(UUID.randomUUID(), archived, commonLand), null,
                null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("cause.manual.chargeTypeIds[0]"))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.errors[1].field").value("cause.manual.chargeTypeIds[1]"))
                .andExpect(jsonPath("$.errors[1].code").value("INVALID"));
        send(chairman, post(RECALCULATIONS).param("preview", "true"), manual(september, october, List.of(),
                List.of(UUID.randomUUID(), archived, commonLand), null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_PLOT"))
                .andExpect(jsonPath("$.errors[1].code").value("PLOT_ARCHIVED"))
                .andExpect(jsonPath("$.errors[2].code").value("PLOT_COMMON_LAND"));
    }

    // ---- who may preview and book

    @Test
    void onlyTheChairmanMayPreviewAndBookAndOtherCooperativesNeverReachThisOne() throws Exception {
        bookSeptemberAndOctoberThenCloseSeptember();
        owner().update("update plot set area_m2 = 700 where id = ?", plot7);
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        for (String bearer : new String[] {treasurer, member, operator}) {
            for (String preview : new String[] {"true", "false"}) {
                send(bearer, post(RECALCULATIONS).param("preview", preview), plot7Membership(REASON, null))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            }
        }
        assertThat(recalculationCount()).isZero();

        String id = book(plot7Membership(REASON, null)).get("id").asString();
        // the treasurer reads the record, a member does not
        send(treasurer, get(RECALCULATIONS + "/" + id), null).andExpect(status().isOk());
        send(member, get(RECALCULATIONS + "/" + id), null).andExpect(status().isForbidden());

        // a chairman of another cooperative sees neither the plots nor the record
        String email = "recalculation-other-" + UUID.randomUUID() + "@example.com";
        UUID otherMember = newMember(OTHER_TENANT, email);
        giveBaseRole(OTHER_TENANT, otherMember, "CHAIRMAN");
        String otherChairman = TestTokens.bearer(OTHER_TENANT, UUID.randomUUID().toString(), email);
        send(otherChairman, post(RECALCULATIONS).param("preview", "true"), plot7Membership(null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("cause.manual.chargeTypeIds[0]"));
        send(otherChairman, post(RECALCULATIONS).param("preview", "true"),
                manual(september, october, List.of(), List.of(plot7), null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_PLOT"));
        send(otherChairman, get(RECALCULATIONS + "/" + id), null).andExpect(status().isNotFound());
        assertThat(recalculationCount()).isEqualTo(1);
    }
}
