package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/**
 * Acceptance tests of the whole feature "Ledger, charges and period closing" (functional-spec.md of 011) as one
 * cooperative lives it, through the public HTTP API: the chairman previews and books a month, a reading charges
 * electricity, a debt turns the plot red and a reversal turns it yellow, months close in order and a late charge goes to
 * the earliest open month, the board's queue of what could not be charged is fixed and retried before the month is
 * closed, and every refusal and every audit entry on the way. The slice tests keep the per-case rules (losses and
 * common consumers runs, water, the import sheets, the database guards, tenant isolation); this class keeps the
 * stories that cross them. The cooperative is the test's own (see {@link FreshCooperativeSupport}); dates are relative
 * to the current month in Europe/Kyiv.
 * <pre>
 * @layer: integration
 * @spec: 011-ledger-charges-period-closing
 * @regression
 * </pre>
 */
@Tag("regression")
@Timeout(240)
class LedgerAcceptanceTests extends ReadingChargeSupport {

    private static final String PLOT = "/api/v1/plots/%s";
    private static final String MONTHS = "/api/v1/billing-months";
    private static final String CLOSE = "/api/v1/billing-months/%s/close";
    private static final String OPENING = "/api/v1/plots/%s/opening-balances";

    private JsonNode account(String bearer, UUID plot) throws Exception {
        return body(send(bearer, get(ACCOUNT.formatted(plot)), null).andExpect(status().isOk()));
    }

    private String statusOf(UUID plot) throws Exception {
        return body(send(chairman, get(PLOT.formatted(plot)), null).andExpect(status().isOk())).get("status")
                .asString();
    }

    private void awaitStatus(UUID plot, String expected) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(statusOf(plot)).isEqualTo(expected));
    }

    /** The member of the cooperative owns the plot since 2020. */
    private void ownedByTheMember(UUID plot) {
        UUID memberId = owner().queryForObject("select id from member where tenant_id = ? and email like 'member-%'",
                UUID.class, tenant);
        owner().update("insert into ownership (tenant_id, plot_id, member_id, share_numerator, share_denominator, "
                + "valid_from) values (?, ?, ?, 1, 1, date '2020-01-01')", tenant, plot, memberId);
    }

    private JsonNode enterOpening(UUID plot, String amount, LocalDate date, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chargeTypeId", typeId("MEMBERSHIP").toString());
        body.put("amount", amount);
        body.put("date", date.toString());
        body.put("reason", reason);
        return body(send(chairman, post(OPENING.formatted(plot)), body).andExpect(status().isCreated()));
    }

    private JsonNode reverse(String bearer, String charge, Object reason, int expected) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        return body(send(bearer, post(REVERSE.formatted(charge)), body).andExpect(status().is(expected)));
    }

    private static JsonNode chargeOf(JsonNode account, String id) {
        for (JsonNode charge : account.get("charges")) {
            if (charge.get("id").asString().equals(id)) {
                return charge;
            }
        }
        throw new AssertionError("no charge " + id + " on the account");
    }

    private static String debtOf(JsonNode account, String code) {
        for (JsonNode debt : account.get("debts")) {
            if (debt.get("chargeTypeCode").asString().equals(code)) {
                return debt.get("debt").asString();
            }
        }
        return null;
    }

    @Test
    void theChairmanRunsAMonthChargesAReadingReversesAndClosesInOrderAndEveryRefusalHolds() throws Exception {
        YearMonth thisMonth = current();
        YearMonth lastMonth = thisMonth.minusMonths(1);
        UUID plot7 = plot("7", "600");
        UUID plot12 = plot("12", "1200");
        UUID plot20 = plot("20", "600");
        ownedByTheMember(plot7);
        ownedByTheMember(plot12);
        // 50 per sotka per year and 35 per sotka per year, billed monthly; 4.32 per kWh
        tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "50.0000", "2026-01-01");
        tariff("LAND_TAX", "Земельний податок", "PER_AREA_SOTKA", "YEARLY", "35.0000", "2026-01-01");
        electricity();

        // ---- the month run: preview writes nothing, booking is one click after it, a rerun adds nothing (§2.3)
        String month = thisMonth.toString();
        JsonNode preview = body(send(chairman, post(RUN.formatted(month, true)), null).andExpect(status().isOk()));
        assertThat(preview.get("plots").asInt()).isEqualTo(3);
        assertThat(total(preview, "MEMBERSHIP")).isEqualTo("100.00"); // 25.00 + 50.00 + 25.00
        assertThat(total(preview, "LAND_TAX")).isEqualTo("70.00"); // 17.50 + 35.00 + 17.50
        assertThat(chargeCount()).isZero();
        JsonNode booked = body(send(chairman, post(RUN.formatted(month, false)), digest(preview.get("digest")
                .asString())).andExpect(status().isOk()));
        assertThat(booked.get("created").asInt()).isEqualTo(6);
        JsonNode rerun = body(send(chairman, post(RUN.formatted(month, false)), null).andExpect(status().isOk()));
        assertThat(rerun.get("created").asInt()).isZero();
        assertThat(chargeCount()).isEqualTo(6);

        // ---- the account of plot 7: both charges, the debt per charge type, the total, the map colour (§2.1, §2.10)
        JsonNode seven = account(chairman, plot7);
        assertThat(debtOf(seven, "MEMBERSHIP")).isEqualTo("25.00");
        assertThat(debtOf(seven, "LAND_TAX")).isEqualTo("17.50");
        assertThat(seven.get("total").asString()).isEqualTo("42.50");
        awaitStatus(plot7, "YELLOW");
        assertThat(statusOf(plot20)).isEqualTo("GREY"); // no owner

        // ---- an approved reading charges electricity at once, with its calculation (§2.4)
        UUID meter = electricityMeter(plot7, lastMonth.atEndOfMonth().toString(), "112.4");
        entered(meter, thisMonth.atDay(1).toString(), "205.7");
        awaitCharges(7);
        JsonNode withReading = account(chairman, plot7);
        assertThat(debtOf(withReading, "ELECTRICITY")).isEqualTo("403.06");
        JsonNode electricity = null;
        for (JsonNode charge : withReading.get("charges")) {
            if (charge.get("source").asString().equals("CONSUMPTION")) {
                electricity = charge;
            }
        }
        assertThat(electricity).isNotNull();
        JsonNode part = electricity.get("calculation").get("parts").get(0);
        assertThat(part.get("quantity").asString()).isEqualTo("93.300");
        assertThat(part.get("rate").asString()).isEqualTo("4.3200");
        assertThat(electricity.get("calculation").get("readings")).hasSize(2);

        // ---- a reversal with a reason, at most once, never of a reversal, never without a reason (§2.1)
        String membership = null;
        for (JsonNode charge : seven.get("charges")) {
            if (charge.get("chargeType").get("code").asString().equals("MEMBERSHIP")) {
                membership = charge.get("id").asString();
            }
        }
        reverse(chairman, membership, "", 400);
        JsonNode reversal = reverse(chairman, membership, "Помилкове нарахування", 201);
        assertThat(reversal.get("amount").asString()).isEqualTo("-25.00");
        assertThat(reversal.get("reason").asString()).isEqualTo("Помилкове нарахування");
        send(chairman, post(REVERSE.formatted(membership)), Map.of("reason", "Ще раз")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHARGE_ALREADY_REVERSED"));
        send(chairman, post(REVERSE.formatted(reversal.get("id").asString())), Map.of("reason", "Сторно сторно"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVERSAL_NOT_REVERSIBLE"));
        JsonNode afterReversal = account(chairman, plot7);
        assertThat(debtOf(afterReversal, "MEMBERSHIP")).isEqualTo("0.00");
        assertThat(afterReversal.get("total").asString()).isEqualTo("420.56"); // 17.50 + 403.06
        assertThat(chargeOf(afterReversal, membership).get("amount").asString()).isEqualTo("25.00");

        // ---- an opening balance above the red amount turns the plot red, its reversal turns it yellow (§2.9, §2.10)
        LocalDate goLive = lastMonth.atDay(1);
        JsonNode opening = enterOpening(plot12, "1500.00", goLive, "Борг за 2025 рік");
        assertThat(opening.get("kind").asString()).isEqualTo("OPENING");
        assertThat(opening.get("billingMonth").asString()).isEqualTo(lastMonth.toString());
        assertThat(debtOf(account(chairman, plot12), "MEMBERSHIP")).isEqualTo("1550.00"); // 1500.00 + 50.00
        awaitStatus(plot12, "RED");
        reverse(chairman, opening.get("id").asString(), "Не той рік", 201);
        awaitStatus(plot12, "YELLOW");
        send(chairman, post(OPENING.formatted(plot12)), Map.of("chargeTypeId", typeId("MEMBERSHIP").toString(),
                "amount", "0.00", "date", goLive.toString())).andExpect(status().isBadRequest());
        JsonNode small = enterOpening(plot12, "200.00", goLive, "Борг за 2024 рік");

        // ---- months close in order after the check list; a closed month takes nothing more (§2.2)
        JsonNode lastPage = body(send(chairman, get(MONTH_PAGE.formatted(lastMonth)), null)
                .andExpect(status().isOk()));
        assertThat(lastPage.get("checklist").get("runDone").asBoolean()).isFalse();
        JsonNode thisPage = body(send(chairman, get(MONTH_PAGE.formatted(month)), null).andExpect(status().isOk()));
        assertThat(thisPage.get("checklist").get("runDone").asBoolean()).isTrue();
        send(chairman, post(CLOSE.formatted(month)), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSE_ORDER"));
        send(chairman, post(CLOSE.formatted(lastMonth)), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        send(chairman, post(REVERSE.formatted(small.get("id").asString())), Map.of("reason", "Помилка"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHARGE_IN_CLOSED_MONTH"));
        send(chairman, post(RUN.formatted(lastMonth.toString(), false)), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));
        send(chairman, post(RUN.formatted(thisMonth.plusMonths(1).toString(), false)), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERIOD_IN_FUTURE"));

        // a reading about the closed month is booked in the earliest open month with its own days
        UUID late = electricityMeter(plot12, lastMonth.atDay(1).toString(), "0");
        entered(late, lastMonth.atEndOfMonth().toString(), "100");
        awaitCharges(12);
        JsonNode lateCharge = null;
        for (JsonNode charge : account(chairman, plot12).get("charges")) {
            if (charge.get("source").asString().equals("CONSUMPTION")) {
                lateCharge = charge;
            }
        }
        assertThat(lateCharge).isNotNull();
        assertThat(lateCharge.get("amount").asString()).isEqualTo("432.00");
        assertThat(lateCharge.get("billingMonth").asString()).isEqualTo(month);
        assertThat(lateCharge.get("serviceFrom").asString()).isEqualTo(lastMonth.atDay(2).toString());
        assertThat(lateCharge.get("serviceTo").asString()).isEqualTo(lastMonth.atEndOfMonth().toString());

        // ---- who may do what (§2.12)
        send(treasurer, get(MONTHS), null).andExpect(status().isOk());
        account(treasurer, plot7);
        send(treasurer, post(RUN.formatted(month, false)), null).andExpect(status().isForbidden());
        send(treasurer, post(CLOSE.formatted(month)), null).andExpect(status().isForbidden());
        send(treasurer, post(REVERSE.formatted(small.get("id").asString())), Map.of("reason", "Помилка"))
                .andExpect(status().isForbidden());
        send(member, get(MONTHS), null).andExpect(status().isForbidden());
        send(member, get(NOT_CHARGED), null).andExpect(status().isForbidden());
        assertThat(account(member, plot7).get("total").asString()).isEqualTo("420.56");
        send(member, get(ACCOUNT.formatted(plot20)), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        send(null, get(MONTHS), null).andExpect(status().isUnauthorized());

        // ---- the audit log tells who did what (§2.12)
        awaitListeners();
        List<String> actions = owner().queryForList("select distinct action from audit_entry where tenant_id = ?",
                String.class, tenant);
        assertThat(actions).contains("BILLING_MONTH_RUN", "BILLING_MONTH_CLOSED", "CHARGE_REVERSED",
                "OPENING_BALANCE_ENTERED");
        assertThat(owner().queryForObject("select count(*) from audit_entry where tenant_id = ? "
                + "and action = 'CHARGE_REVERSED' and actor_member_id is not null", Integer.class, tenant)).isEqualTo(2);
    }

    @Test
    void whatCouldNotBeChargedIsListedBlocksNoClosingAndIsChargedByARetryOnceTheTariffIsFixed() throws Exception {
        YearMonth thisMonth = current();
        YearMonth lastMonth = thisMonth.minusMonths(1);
        UUID plot = plot("7", "600");
        tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "50.0000", "2026-01-01");
        // the first electricity version starts on the first day of the month: the interval before it has no tariff
        Tariff electricity = tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200",
                thisMonth.atDay(1).toString());
        send(chairman, post(RUN.formatted(thisMonth, false)), null).andExpect(status().isOk());
        UUID meter = electricityMeter(plot, lastMonth.atEndOfMonth().minusDays(4).toString(), "0");
        entered(meter, thisMonth.atDay(1).toString(), "100");
        awaitListeners();

        // the item names the plot, the charge type and the reason, and the month page counts it (§2.7, §2.2)
        JsonNode queue = body(send(chairman, get(NOT_CHARGED), null).andExpect(status().isOk()));
        assertThat(queue).hasSize(1);
        JsonNode item = queue.get(0);
        assertThat(item.get("reason").asString()).isEqualTo("NO_TARIFF_FOR_ALL_DAYS");
        assertThat(item.get("plotNumber").asString()).isEqualTo("7");
        assertThat(item.get("chargeType").get("code").asString()).isEqualTo("ELECTRICITY");
        JsonNode page = body(send(chairman, get(MONTH_PAGE.formatted(thisMonth)), null).andExpect(status().isOk()));
        assertThat(page.get("checklist").get("runDone").asBoolean()).isTrue();
        assertThat(page.get("checklist").get("notChargedCount").asInt()).isEqualTo(1);
        assertThat(readingCharges(plot)).extracting(c -> c.get("source")).doesNotContain("CONSUMPTION");

        // a retry without a fix keeps the item with its reason
        String itemId = item.get("id").asString();
        JsonNode stays = body(send(chairman, post(RETRY.formatted(itemId)), null).andExpect(status().isOk()));
        assertThat(stays.get("resolved").asBoolean()).isFalse();
        assertThat(stays.get("item").get("reason").asString()).isEqualTo("NO_TARIFF_FOR_ALL_DAYS");

        // the board adds the missing tariff version; the retry charges and the item disappears
        version(electricity.id(), "PER_KWH", "MONTHLY", "4.3200", lastMonth.atDay(1).toString(), null);
        JsonNode retried = body(send(chairman, post(RETRY.formatted(itemId)), null).andExpect(status().isOk()));
        assertThat(retried.get("resolved").asBoolean()).isTrue();
        awaitListeners();
        assertThat(body(send(chairman, get(NOT_CHARGED), null))).isEmpty();
        assertThat(debtOfPlot(plot, "ELECTRICITY")).isEqualTo("432.00");

        // the treasurer sees the queue but cannot retry; the member sees none of it
        send(treasurer, get(NOT_CHARGED), null).andExpect(status().isOk());
        send(treasurer, post(RETRY.formatted(itemId)), null).andExpect(status().isForbidden());
        send(member, get(NOT_CHARGED), null).andExpect(status().isForbidden());

        // none of this blocked the closing
        send(chairman, post(CLOSE.formatted(thisMonth)), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    private String debtOfPlot(UUID plot, String code) throws Exception {
        return debtOf(account(chairman, plot), code);
    }
}
