package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The account of a plot, reversals and opening balances through the API (feature 011 §2.1, §2.9 and the §3 rows "Manual
 * reversal" and "Opening balance"): debts per charge type, the reversal rules, booking in the earliest open month, and
 * who may do what per endpoint (chairman, treasurer, a custom role with one permission, a member for their own plot and
 * another plot, the other cooperative, the platform operator). Adjustments (feature 012 §2.3, written through the test
 * support until the booking arrives): they count in the debt, link to the charge they adjust and to their
 * recalculation (only for {@code CHARGES_VIEW}), the kind filter, and they are never reversed. Payments (feature 015
 * §2.9, §2.10): the payments and their reversals next to the charges with their dates, paid and open per charge, the
 * debt after payments and the prepayment, the placements of the prepayment, the "payments only" filter, and what a
 * member, a custom role and the board see of them.
 */
class LedgerAccountTests extends PaymentApiSupport {

    private static final String OCTOBER = "2026-10";

    private JsonNode account(String bearer, UUID plot) throws Exception {
        return body(send(bearer, get(ACCOUNT.formatted(plot)), null).andExpect(status().isOk()));
    }

    private static JsonNode charge(JsonNode account, UUID id) {
        for (JsonNode charge : account.get("charges")) {
            if (charge.get("id").asString().equals(id.toString())) {
                return charge;
            }
        }
        throw new AssertionError("No charge " + id + " in " + account);
    }

    // ---- the account and the debt per charge type

    @Test
    void theAccountShowsTheChargesAndTheDebtPerChargeType() throws Exception {
        // functional spec 2.1: membership 25.00 and land tax 17.50, total 42.50
        UUID plot = newPlot();
        UUID membership = tariffCharge(plot, "MEMBERSHIP", "25.00", OCTOBER);
        UUID land = tariffCharge(plot, "LAND_TAX", "17.50", OCTOBER);

        JsonNode account = account(chairman(), plot);

        assertThat(account.get("plotId").asString()).isEqualTo(plot.toString());
        assertThat(account.get("debts")).hasSize(2);
        assertThat(account.get("debts").get(0).get("chargeTypeCode").asString()).isEqualTo("MEMBERSHIP");
        assertThat(account.get("debts").get(0).get("chargeTypeId").asString())
                .isEqualTo(typeId(DEMO_TENANT, "MEMBERSHIP").toString());
        assertThat(account.get("debts").get(0).get("nameCustomized").asBoolean()).isFalse();
        assertThat(account.get("debts").get(0).get("debt").asString()).isEqualTo("25.00");
        assertThat(account.get("debts").get(1).get("chargeTypeCode").asString()).isEqualTo("LAND_TAX");
        assertThat(account.get("debts").get(1).get("debt").asString()).isEqualTo("17.50");
        assertThat(account.get("total").asString()).isEqualTo("42.50");
        assertThat(account.get("charges")).hasSize(2);

        JsonNode fee = charge(account, membership);
        assertThat(fee.get("kind").asString()).isEqualTo("REGULAR");
        assertThat(fee.get("source").asString()).isEqualTo("TARIFF");
        assertThat(fee.get("amount").asString()).isEqualTo("25.00");
        assertThat(fee.get("billingMonth").asString()).isEqualTo(OCTOBER);
        assertThat(fee.get("billingMonthClosed").asBoolean()).isFalse();
        assertThat(fee.get("serviceFrom").asString()).isEqualTo("2026-10-01");
        assertThat(fee.get("serviceTo").asString()).isEqualTo("2026-10-31");
        assertThat(fee.get("accruedOn").asString()).isEqualTo("2026-10-01");
        assertThat(fee.get("chargeType").get("code").asString()).isEqualTo("MEMBERSHIP");
        assertThat(fee.get("reversible").asBoolean()).isTrue();
        assertThat(fee.get("calculation").get("amount").asString()).isEqualTo("25.00");
        assertThat(fee.get("calculation").get("parts")).isEmpty();
        assertThat(fee.get("calculation").get("readings")).isEmpty();
        assertThat(fee.has("reversedBy")).isFalse();
        assertThat(fee.has("createdByName")).isFalse(); // booked by the system
        assertThat(charge(account, land).get("chargeType").get("code").asString()).isEqualTo("LAND_TAX");
    }

    @Test
    void aPlotWithoutChargesHasNoDebt() throws Exception {
        JsonNode account = account(chairman(), newPlot());

        assertThat(account.get("debts")).isEmpty();
        assertThat(account.get("total").asString()).isEqualTo("0.00");
        assertThat(account.get("charges")).isEmpty();
    }

    @Test
    void chargesAreListedNewestFirstAndTheRangeLimitsTheListButNotTheDebts() throws Exception {
        UUID plot = newPlot();
        UUID august = tariffCharge(plot, "MEMBERSHIP", "25.00", "2026-08");
        UUID september = tariffCharge(plot, "MEMBERSHIP", "25.00", "2026-09");
        UUID october = tariffCharge(plot, "MEMBERSHIP", "25.00", OCTOBER);

        JsonNode all = account(chairman(), plot);
        assertThat(all.get("charges").valueStream().map(c -> c.get("id").asString()).toList()).containsExactly(october.toString(),
                september.toString(), august.toString());

        JsonNode limited = body(send(chairman(), get(ACCOUNT.formatted(plot)).param("from", "2026-09-01")
                .param("to", "2026-09-30"), null).andExpect(status().isOk()));
        assertThat(limited.get("charges").valueStream().map(c -> c.get("id").asString()).toList()).containsExactly(september.toString());
        assertThat(limited.get("total").asString()).isEqualTo("75.00");
        assertThat(limited.get("debts").get(0).get("debt").asString()).isEqualTo("75.00");

        JsonNode fromOnly = body(send(chairman(), get(ACCOUNT.formatted(plot)).param("from", "2026-09-02"), null)
                .andExpect(status().isOk()));
        assertThat(fromOnly.get("charges").valueStream().map(c -> c.get("id").asString()).toList()).containsExactly(october.toString());
    }

    @Test
    void aRangeThatEndsBeforeItStartsIsRefused() throws Exception {
        send(chairman(), get(ACCOUNT.formatted(newPlot())).param("from", "2026-10-02").param("to", "2026-10-01"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("from"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
    }

    // ---- reversals (§3 "Manual reversal")

    @Test
    void aReversalCancelsTheChargeAndLowersTheDebt() throws Exception {
        UUID plot = newPlot();
        UUID membership = tariffCharge(plot, "MEMBERSHIP", "25.00", OCTOBER);
        tariffCharge(plot, "LAND_TAX", "17.50", OCTOBER);

        JsonNode reversal = body(send(chairman(), post(REVERSE.formatted(membership)),
                Map.of("reason", "  Помилкове нарахування  ")).andExpect(status().isCreated()));

        assertThat(reversal.get("kind").asString()).isEqualTo("REVERSAL");
        assertThat(reversal.get("source").asString()).isEqualTo("MANUAL_REVERSAL");
        assertThat(reversal.get("amount").asString()).isEqualTo("-25.00");
        assertThat(reversal.get("plotId").asString()).isEqualTo(plot.toString());
        assertThat(reversal.get("reverses").asString()).isEqualTo(membership.toString());
        assertThat(reversal.get("reason").asString()).isEqualTo("Помилкове нарахування");
        assertThat(reversal.get("chargeType").get("code").asString()).isEqualTo("MEMBERSHIP");
        assertThat(reversal.get("billingMonth").asString()).isEqualTo(OCTOBER);
        assertThat(reversal.get("serviceFrom").asString()).isEqualTo("2026-10-01");
        assertThat(reversal.get("serviceTo").asString()).isEqualTo("2026-10-31");
        assertThat(reversal.get("accruedOn").asString()).isEqualTo("2026-10-01");
        assertThat(reversal.get("reversible").asBoolean()).isFalse();
        assertThat(reversal.get("calculation").get("amount").asString()).isEqualTo("-25.00");
        assertThat(reversal.get("createdByName").asString()).isNotBlank();

        JsonNode account = account(chairman(), plot);
        assertThat(account.get("debts").get(0).get("chargeTypeCode").asString()).isEqualTo("MEMBERSHIP");
        assertThat(account.get("debts").get(0).get("debt").asString()).isEqualTo("0.00");
        assertThat(account.get("debts").get(1).get("debt").asString()).isEqualTo("17.50");
        assertThat(account.get("total").asString()).isEqualTo("17.50");
        assertThat(account.get("charges")).hasSize(3);
        JsonNode original = charge(account, membership);
        assertThat(original.get("reversedBy").asString()).isEqualTo(reversal.get("id").asString());
        assertThat(original.get("reversible").asBoolean()).isFalse();
        assertThat(original.get("amount").asString()).isEqualTo("25.00"); // the original is never changed
    }

    @Test
    void aChargeIsReversedAtMostOnce() throws Exception {
        UUID charge = tariffCharge(newPlot(), "MEMBERSHIP", "25.00", OCTOBER);
        send(chairman(), post(REVERSE.formatted(charge)), Map.of("reason", "Помилка")).andExpect(status().isCreated());

        send(chairman(), post(REVERSE.formatted(charge)), Map.of("reason", "Ще раз"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHARGE_ALREADY_REVERSED"));
    }

    @Test
    void aReversalCannotBeReversed() throws Exception {
        UUID charge = tariffCharge(newPlot(), "MEMBERSHIP", "25.00", OCTOBER);
        UUID reversal = UUID.fromString(body(send(chairman(), post(REVERSE.formatted(charge)),
                Map.of("reason", "Помилка")).andExpect(status().isCreated())).get("id").asString());

        send(chairman(), post(REVERSE.formatted(reversal)), Map.of("reason", "Сторно сторно"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVERSAL_NOT_REVERSIBLE"));
    }

    @Test
    void aChargeInAClosedMonthIsNotReversedByHand() throws Exception {
        UUID plot = newPlot();
        UUID charge = tariffCharge(plot, "MEMBERSHIP", "25.00", "2024-03");
        closedMonth(DEMO_TENANT, "2024-03");

        assertThat(charge(account(chairman(), plot), charge).get("billingMonthClosed").asBoolean()).isTrue();
        assertThat(charge(account(chairman(), plot), charge).get("reversible").asBoolean()).isFalse();
        send(chairman(), post(REVERSE.formatted(charge)), Map.of("reason", "Помилка"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHARGE_IN_CLOSED_MONTH"));
        assertThat(chargeCount(plot)).isEqualTo(1);
    }

    @Test
    void aReversalNeedsAReason() throws Exception {
        UUID plot = newPlot();
        UUID charge = tariffCharge(plot, "MEMBERSHIP", "25.00", OCTOBER);

        for (Object body : new Object[] {Map.of("reason", "   "), Map.of("reason", ""), Map.of()}) {
            send(chairman(), post(REVERSE.formatted(charge)), body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("reason"))
                    .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        }
        send(chairman(), post(REVERSE.formatted(charge)), Map.of("reason", "x".repeat(501)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        assertThat(chargeCount(plot)).isEqualTo(1);
    }

    @Test
    void anUnknownChargeIsNotFound() throws Exception {
        send(chairman(), post(REVERSE.formatted(UUID.randomUUID())), Map.of("reason", "Помилка"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---- adjustments (feature 012 §2.3)

    private static final String CLOSED = "2024-03";
    private static final String OPEN = "2024-04";
    private static final String RATE_CORRECTED = "Виправлено ставку членського внеску";

    /** The 60 -> 50 example on one plot: March 30.00 (closed) adjusted by -5.00 in April, April 25.00. */
    private record Adjusted(UUID plot, UUID original, UUID april, UUID recalculation, UUID adjustment) {
    }

    private Adjusted adjustedPlot() {
        UUID plot = newPlot();
        UUID original = tariffCharge(plot, "MEMBERSHIP", "30.00", CLOSED);
        closedMonth(DEMO_TENANT, CLOSED);
        UUID april = tariffCharge(plot, "MEMBERSHIP", "25.00", OPEN);
        UUID recalculation = recalculation(DEMO_TENANT, "MANUAL", RATE_CORRECTED, "-5.00", 1, 1,
                demoMember("chairman@demo.local"), "2026-10-20T10:00:00Z", "{\"causeType\": \"MANUAL\"}");
        UUID adjustment = adjustment(original, recalculation, "-5.00", OPEN, RATE_CORRECTED);
        return new Adjusted(plot, original, april, recalculation, adjustment);
    }

    @Test
    void anAdjustmentCountsInTheDebtAndLinksToTheChargeItAdjusts() throws Exception {
        // functional spec 2.3: "Коригування за вересень: -5,00" in October, the membership debt 50.00
        Adjusted given = adjustedPlot();

        JsonNode account = account(chairman(), given.plot());

        assertThat(account.get("debts")).hasSize(1);
        assertThat(account.get("debts").get(0).get("debt").asString()).isEqualTo("50.00");
        assertThat(account.get("total").asString()).isEqualTo("50.00");
        assertThat(account.get("charges")).hasSize(3);
        JsonNode adjustment = charge(account, given.adjustment());
        assertThat(adjustment.get("kind").asString()).isEqualTo("ADJUSTMENT");
        assertThat(adjustment.get("source").asString()).isEqualTo("RECALCULATION");
        assertThat(adjustment.get("amount").asString()).isEqualTo("-5.00");
        assertThat(adjustment.get("billingMonth").asString()).isEqualTo(OPEN);
        assertThat(adjustment.get("billingMonthClosed").asBoolean()).isFalse();
        assertThat(adjustment.get("serviceFrom").asString()).isEqualTo("2024-03-01");
        assertThat(adjustment.get("serviceTo").asString()).isEqualTo("2024-03-31");
        assertThat(adjustment.get("accruedOn").asString()).isEqualTo("2024-03-01");
        assertThat(adjustment.get("reason").asString()).isEqualTo(RATE_CORRECTED);
        assertThat(adjustment.get("adjusts").get("chargeId").asString()).isEqualTo(given.original().toString());
        assertThat(adjustment.get("adjusts").get("billingMonth").asString()).isEqualTo(CLOSED);
        assertThat(adjustment.get("adjusts").get("serviceFrom").asString()).isEqualTo("2024-03-01");
        assertThat(adjustment.get("adjusts").get("serviceTo").asString()).isEqualTo("2024-03-31");
        assertThat(adjustment.get("recalculationId").asString()).isEqualTo(given.recalculation().toString());
        assertThat(adjustment.get("reversible").asBoolean()).isFalse(); // its month is open, but never reversed
        assertThat(adjustment.has("adjustedBy")).isFalse();
        assertThat(adjustment.has("reverses")).isFalse();

        JsonNode original = charge(account, given.original());
        assertThat(original.get("amount").asString()).isEqualTo("30.00"); // the original is never changed
        assertThat(original.get("adjustedBy").valueStream().map(JsonNode::asString).toList())
                .containsExactly(given.adjustment().toString());
        assertThat(original.has("adjusts")).isFalse();
        assertThat(original.has("recalculationId")).isFalse();
        JsonNode april = charge(account, given.april());
        assertThat(april.has("adjustedBy")).isFalse();
        assertThat(april.get("reversible").asBoolean()).isTrue();

        // the treasurer views charges, so sees the recalculation too
        assertThat(charge(account(treasurer(), given.plot()), given.adjustment()).get("recalculationId").asString())
                .isEqualTo(given.recalculation().toString());
    }

    @Test
    void theKindFilterLimitsTheListButNotTheDebts() throws Exception {
        Adjusted given = adjustedPlot();

        JsonNode adjustments = body(send(chairman(), get(ACCOUNT.formatted(given.plot())).param("kind", "ADJUSTMENT"),
                null).andExpect(status().isOk()));
        assertThat(adjustments.get("charges").valueStream().map(c -> c.get("id").asString()).toList())
                .containsExactly(given.adjustment().toString());
        assertThat(adjustments.get("total").asString()).isEqualTo("50.00");
        // the adjustment still names its original, which is not listed
        assertThat(adjustments.get("charges").get(0).get("adjusts").get("billingMonth").asString()).isEqualTo(CLOSED);

        JsonNode regular = body(send(chairman(), get(ACCOUNT.formatted(given.plot())).param("kind", "REGULAR"), null)
                .andExpect(status().isOk()));
        assertThat(regular.get("charges").valueStream().map(c -> c.get("id").asString()).toList())
                .containsExactlyInAnyOrder(given.original().toString(), given.april().toString());

        send(chairman(), get(ACCOUNT.formatted(given.plot())).param("kind", "BONUS"), null)
                .andExpect(status().isBadRequest());
    }

    @Test
    void aMemberSeesTheAdjustmentsOfTheirOwnPlotWithoutTheRecalculation() throws Exception {
        Adjusted given = adjustedPlot();
        own(demoMember("member@demo.local"), given.plot());

        JsonNode account = account(member(), given.plot());

        assertThat(account.get("total").asString()).isEqualTo("50.00");
        JsonNode adjustment = charge(account, given.adjustment());
        assertThat(adjustment.get("amount").asString()).isEqualTo("-5.00");
        assertThat(adjustment.get("reason").asString()).isEqualTo(RATE_CORRECTED);
        assertThat(adjustment.get("adjusts").get("chargeId").asString()).isEqualTo(given.original().toString());
        assertThat(adjustment.has("recalculationId")).isFalse();
        assertThat(adjustment.has("createdByName")).isFalse();
        assertThat(charge(account, given.original()).get("adjustedBy")).hasSize(1);
    }

    @Test
    void anAdjustmentIsNeverReversedByHand() throws Exception {
        Adjusted given = adjustedPlot();

        send(chairman(), post(REVERSE.formatted(given.adjustment())), Map.of("reason", "Помилка"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADJUSTMENT_NOT_REVERSIBLE"));
        assertThat(chargeCount(given.plot())).isEqualTo(3);
        assertThat(account(chairman(), given.plot()).get("total").asString()).isEqualTo("50.00");
    }

    // ---- opening balances (§3 "Opening balance")

    @Test
    void anOpeningBalanceIsBookedInTheMonthOfItsDateAndRaisesTheDebt() throws Exception {
        // functional spec 2.9: 1 500.00 for membership on plot 12 as of 01.09.2026, reason "Борг за 2025 рік"
        UUID plot = newPlot();
        UUID membership = typeId(DEMO_TENANT, "MEMBERSHIP");

        JsonNode opening = body(send(chairman(), post(OPENING.formatted(plot)),
                opening(membership, "1500.00", "2026-09-01", "Борг за 2025 рік")).andExpect(status().isCreated()));

        assertThat(opening.get("kind").asString()).isEqualTo("OPENING");
        assertThat(opening.get("source").asString()).isEqualTo("OPENING");
        assertThat(opening.get("amount").asString()).isEqualTo("1500.00");
        assertThat(opening.get("plotId").asString()).isEqualTo(plot.toString());
        assertThat(opening.get("chargeType").get("id").asString()).isEqualTo(membership.toString());
        assertThat(opening.get("billingMonth").asString()).isEqualTo("2026-09");
        assertThat(opening.get("serviceFrom").asString()).isEqualTo("2026-09-01");
        assertThat(opening.get("serviceTo").asString()).isEqualTo("2026-09-01");
        assertThat(opening.get("accruedOn").asString()).isEqualTo("2026-09-01");
        assertThat(opening.get("reversible").asBoolean()).isTrue();
        assertThat(opening.get("reason").asString()).isEqualTo("Борг за 2025 рік");
        assertThat(opening.get("calculation").get("amount").asString()).isEqualTo("1500.00");

        JsonNode account = account(chairman(), plot);
        assertThat(account.get("debts").get(0).get("debt").asString()).isEqualTo("1500.00");
        assertThat(account.get("total").asString()).isEqualTo("1500.00");
        assertThat(account.get("charges").get(0).get("id").asString()).isEqualTo(opening.get("id").asString());
        assertThat(account.get("charges").get(0).get("reason").asString()).isEqualTo("Борг за 2025 рік");
        assertThat(ownerJdbc().queryForObject("select status from billing_month where tenant_id = ? "
                + "and month = date '2026-09-01'", String.class, DEMO_TENANT)).isEqualTo("OPEN");
    }

    @Test
    void anOpeningBalanceOfAClosedMonthIsBookedInTheNextMonthWithItsOwnDate() throws Exception {
        UUID plot = newPlot();
        closedMonth(DEMO_TENANT, "2024-03");

        JsonNode opening = body(send(chairman(), post(OPENING.formatted(plot)),
                opening(typeId(DEMO_TENANT, "LAND_TAX"), "300.00", "2024-03-10", null))
                .andExpect(status().isCreated()));

        assertThat(opening.get("billingMonth").asString()).isEqualTo("2024-04");
        assertThat(opening.get("billingMonthClosed").asBoolean()).isFalse();
        assertThat(opening.get("accruedOn").asString()).isEqualTo("2024-03-10");
        assertThat(opening.get("serviceTo").asString()).isEqualTo("2024-03-10");
        assertThat(opening.has("reason")).isFalse(); // none given
    }

    @Test
    void anOpeningBalanceCanBeReversedWhileItsMonthIsOpen() throws Exception {
        UUID plot = newPlot();
        UUID opening = UUID.fromString(body(send(chairman(), post(OPENING.formatted(plot)),
                opening(typeId(DEMO_TENANT, "MEMBERSHIP"), "1500.00", "2026-09-01", null))
                .andExpect(status().isCreated())).get("id").asString());

        JsonNode reversal = body(send(chairman(), post(REVERSE.formatted(opening)), Map.of("reason", "Не той рік"))
                .andExpect(status().isCreated()));

        assertThat(reversal.get("amount").asString()).isEqualTo("-1500.00");
        assertThat(reversal.get("billingMonth").asString()).isEqualTo("2026-09");
        assertThat(account(chairman(), plot).get("total").asString()).isEqualTo("0.00");
    }

    @Test
    void anOpeningBalanceMustBeGreaterThanZeroAndNotInTheFuture() throws Exception {
        UUID plot = newPlot();
        UUID membership = typeId(DEMO_TENANT, "MEMBERSHIP");

        for (String amount : new String[] {"0.00", "-5.00"}) {
            send(chairman(), post(OPENING.formatted(plot)), opening(membership, amount, "2026-09-01", null))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("amount"))
                    .andExpect(jsonPath("$.errors[0].code").value("AMOUNT_NOT_POSITIVE"));
        }
        send(chairman(), post(OPENING.formatted(plot)),
                opening(membership, "10.00", kyivToday().plusDays(1).toString(), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("date"))
                .andExpect(jsonPath("$.errors[0].code").value("DATE_IN_FUTURE"));
        send(chairman(), post(OPENING.formatted(plot)), opening(membership, "1234567890123.00", "2026-09-01", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("amount"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        send(chairman(), post(OPENING.formatted(plot)), opening(membership, "12.5", "2026-09-01", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("amount"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        send(chairman(), post(OPENING.formatted(plot)), Map.of("amount", "10.00", "date", "2026-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("chargeTypeId"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        send(chairman(), post(OPENING.formatted(plot)), opening(membership, "10.00", "2026-09-01", "x".repeat(501)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        // today itself is allowed
        send(chairman(), post(OPENING.formatted(plot)), opening(membership, "0.01", kyivToday().toString(), null))
                .andExpect(status().isCreated());
        assertThat(chargeCount(plot)).isEqualTo(1);
    }

    @Test
    void anOpeningBalanceNeedsAKnownActiveChargeTypeAndAnActivePlot() throws Exception {
        UUID plot = newPlot();

        send(chairman(), post(OPENING.formatted(plot)), opening(UUID.randomUUID(), "10.00", "2026-09-01", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.errors[0].field").value("chargeTypeId"))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        send(chairman(), post(OPENING.formatted(plot)), opening(customChargeType(true), "10.00", "2026-09-01", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHARGE_TYPE_ARCHIVED"))
                .andExpect(jsonPath("$.errors[0].field").value("chargeTypeId"));
        UUID membership = typeId(DEMO_TENANT, "MEMBERSHIP");
        send(chairman(), post(OPENING.formatted(newPlot(DEMO_TENANT, "PLOT", true))),
                opening(membership, "10.00", "2026-09-01", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_ARCHIVED"));
        send(chairman(), post(OPENING.formatted(newPlot(DEMO_TENANT, "COMMON", false))),
                opening(membership, "10.00", "2026-09-01", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_COMMON_LAND"));
        send(chairman(), post(OPENING.formatted(UUID.randomUUID())), opening(membership, "10.00", "2026-09-01", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        // a custom type that is active is accepted
        JsonNode custom = body(send(chairman(), post(OPENING.formatted(plot)),
                opening(customChargeType(false), "10.00", "2026-09-01", null)).andExpect(status().isCreated()));
        assertThat(custom.get("chargeType").has("code")).isFalse();
        assertThat(custom.get("chargeType").get("nameCustomized").asBoolean()).isTrue();
    }

    // ---- the plot guard

    @Test
    void aPlotWithChargesIsNotDeleted() throws Exception {
        UUID plot = newPlot();
        tariffCharge(plot, "MEMBERSHIP", "25.00", OCTOBER);

        send(chairman(), delete("/api/v1/plots/" + plot), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_CHARGES"));
        assertThat(ownerJdbc().queryForObject("select count(*) from plot where id = ?", Integer.class, plot))
                .isEqualTo(1);

        UUID clean = newPlot();
        send(chairman(), delete("/api/v1/plots/" + clean), null).andExpect(status().isNoContent());
    }

    // ---- who may see an account

    @Test
    void theTreasurerSeesEveryAccountButCannotReverse() throws Exception {
        UUID plot = newPlot();
        UUID charge = tariffCharge(plot, "MEMBERSHIP", "25.00", OCTOBER);

        JsonNode account = account(treasurer(), plot);

        assertThat(account.get("total").asString()).isEqualTo("25.00");
        assertThat(charge(account, charge).get("reversible").asBoolean()).isFalse();
    }

    @Test
    void aMemberSeesTheAccountOfTheirOwnPlotOnly() throws Exception {
        UUID own = newPlot();
        own(demoMember("member@demo.local"), own);
        UUID charge = tariffCharge(own, "MEMBERSHIP", "25.00", OCTOBER);
        send(chairman(), post(REVERSE.formatted(charge)), Map.of("reason", "Помилка")).andExpect(status().isCreated());
        UUID other = newPlot();
        tariffCharge(other, "MEMBERSHIP", "25.00", OCTOBER);

        JsonNode account = account(member(), own);
        assertThat(account.get("charges")).hasSize(2);
        assertThat(account.get("total").asString()).isEqualTo("0.00");
        account.get("charges").forEach(c -> {
            assertThat(c.get("reversible").asBoolean()).isFalse();
            assertThat(c.has("createdByName")).isFalse(); // the board's names only with CHARGES_VIEW
        });

        send(member(), get(ACCOUNT.formatted(other)), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        send(member(), get(ACCOUNT.formatted(UUID.randomUUID())), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
    }

    @Test
    void anotherCooperativeNeverSeesTheAccount() throws Exception {
        UUID plot = newPlot();
        UUID charge = tariffCharge(plot, "MEMBERSHIP", "25.00", OCTOBER);
        String other = otherChairman();

        send(other, get(ACCOUNT.formatted(plot)), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        send(other, post(REVERSE.formatted(charge)), Map.of("reason", "Чуже"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        send(other, post(OPENING.formatted(plot)),
                opening(typeId(OTHER_TENANT, "MEMBERSHIP"), "10.00", "2026-09-01", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        assertThat(chargeCount(plot)).isEqualTo(1);
    }

    @Test
    void thePlatformOperatorHasNoCooperativeData() throws Exception {
        UUID plot = newPlot();
        UUID charge = tariffCharge(plot, "MEMBERSHIP", "25.00", OCTOBER);

        send(operator(), get(ACCOUNT.formatted(plot)), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        send(operator(), post(REVERSE.formatted(charge)), Map.of("reason", "Помилка"))
                .andExpect(status().isForbidden());
        send(operator(), post(OPENING.formatted(plot)),
                opening(typeId(DEMO_TENANT, "MEMBERSHIP"), "10.00", "2026-09-01", null))
                .andExpect(status().isForbidden());
    }

    @Test
    void withoutATokenEverythingIsUnauthenticated() throws Exception {
        UUID plot = newPlot();

        send(null, get(ACCOUNT.formatted(plot)), null).andExpect(status().isUnauthorized());
        send(null, post(REVERSE.formatted(UUID.randomUUID())), Map.of("reason", "x"))
                .andExpect(status().isUnauthorized());
        send(null, post(OPENING.formatted(plot)), opening(UUID.randomUUID(), "10.00", "2026-09-01", null))
                .andExpect(status().isUnauthorized());
    }

    // ---- who may reverse and enter opening balances

    @Test
    void onlyChargesReverseMayReverse() throws Exception {
        UUID plot = newPlot();
        UUID charge = tariffCharge(plot, "MEMBERSHIP", "25.00", OCTOBER);
        own(demoMember("member@demo.local"), plot);

        for (String bearer : new String[] {treasurer(), member(), customRoleToken("CHARGES_VIEW", "CHARGES_RUN")}) {
            send(bearer, post(REVERSE.formatted(charge)), Map.of("reason", "Помилка"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        assertThat(chargeCount(plot)).isEqualTo(1);

        String reverser = customRoleToken("CHARGES_REVERSE");
        send(reverser, post(REVERSE.formatted(charge)), Map.of("reason", "Помилка"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdByName").doesNotExist());
        assertThat(chargeCount(plot)).isEqualTo(2);
    }

    @Test
    void onlyChargesRunMayEnterAnOpeningBalance() throws Exception {
        UUID plot = newPlot();
        own(demoMember("member@demo.local"), plot);
        Map<String, Object> body = opening(typeId(DEMO_TENANT, "MEMBERSHIP"), "10.00", "2026-09-01", null);

        for (String bearer : new String[] {treasurer(), member(),
                customRoleToken("CHARGES_VIEW", "CHARGES_REVERSE")}) {
            send(bearer, post(OPENING.formatted(plot)), body)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        assertThat(chargeCount(plot)).isZero();

        JsonNode created = body(send(customRoleToken("CHARGES_RUN"), post(OPENING.formatted(plot)), body)
                .andExpect(status().isCreated()));
        assertThat(created.get("reversible").asBoolean()).isFalse(); // the runner may not reverse
        assertThat(chargeCount(plot)).isEqualTo(1);
    }

    @Test
    void aCustomRoleWithChargesViewSeesEveryAccount() throws Exception {
        UUID plot = newPlot();
        tariffCharge(plot, "MEMBERSHIP", "25.00", OCTOBER);

        assertThat(account(customRoleToken("CHARGES_VIEW"), plot).get("total").asString()).isEqualTo("25.00");
        send(customRoleToken("METERS_VIEW"), get(ACCOUNT.formatted(plot)), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
    }

    // ---- payments on the account (feature 015 §2.9, §2.10)

    private static JsonNode payment(JsonNode account, UUID id) {
        for (JsonNode payment : account.get("payments")) {
            if (payment.get("id").asString().equals(id.toString())) {
                return payment;
            }
        }
        throw new AssertionError("No payment " + id + " in " + account);
    }

    private static List<String> ids(JsonNode list) {
        return list.valueStream().map(item -> item.get("id").asString()).toList();
    }

    /** Membership 600.00 (January) and electricity 250.00 (February), paid 100.00 by bank on 15 January. */
    private record Paid(UUID plot, UUID membership, UUID electricity, UUID bank) {
    }

    private Paid paidPlot() throws Exception {
        UUID plot = newPlot();
        UUID membership = tariffCharge(plot, "MEMBERSHIP", "600.00", "2026-01");
        UUID electricity = tariffCharge(plot, "ELECTRICITY", "250.00", "2026-02");
        UUID bank = saved(treasurer(), with(payment(plot, "BANK", "100.00", "2026-01-15", null), "bankReference",
                "ПД-5"));
        return new Paid(plot, membership, electricity, bank);
    }

    @Test
    void paymentsAreListedWithTheirDatesNextToTheChargesAndEachChargeShowsPaidAndOpen() throws Exception {
        Paid given = paidPlot();
        JsonNode saved = body(save(treasurer(), with(cash(given.plot(), "700.00"), "payerNote", "Принесла дочка"))
                .andExpect(status().isCreated())).get("payments").get(0);
        UUID cash = UUID.fromString(saved.get("id").asString());

        JsonNode account = account(chairman(), given.plot());

        // the debt after payments: membership 600.00 paid in full, electricity 250.00 - 200.00
        assertThat(account.get("debts").get(0).get("chargeTypeCode").asString()).isEqualTo("MEMBERSHIP");
        assertThat(account.get("debts").get(0).get("debt").asString()).isEqualTo("0.00");
        assertThat(account.get("debts").get(1).get("chargeTypeCode").asString()).isEqualTo("ELECTRICITY");
        assertThat(account.get("debts").get(1).get("debt").asString()).isEqualTo("50.00");
        assertThat(account.get("total").asString()).isEqualTo("50.00");
        assertThat(account.get("prepayment").asString()).isEqualTo("0.00");
        JsonNode membership = charge(account, given.membership());
        assertThat(membership.get("amount").asString()).isEqualTo("600.00");
        assertThat(membership.get("paid").asString()).isEqualTo("600.00");
        assertThat(membership.get("open").asString()).isEqualTo("0.00");
        JsonNode electricity = charge(account, given.electricity());
        assertThat(electricity.get("paid").asString()).isEqualTo("200.00");
        assertThat(electricity.get("open").asString()).isEqualTo("50.00");

        // both lists newest first, each with the date the client interleaves them by: today's cash, the February
        // charge, the bank payment of 15 January, the January charge
        assertThat(ids(account.get("charges"))).containsExactly(given.electricity().toString(),
                given.membership().toString());
        assertThat(ids(account.get("payments"))).containsExactly(cash.toString(), given.bank().toString());
        assertThat(account.get("charges").get(0).get("accruedOn").asString()).isEqualTo("2026-02-01");
        assertThat(account.get("charges").get(1).get("accruedOn").asString()).isEqualTo("2026-01-01");
        JsonNode bank = payment(account, given.bank());
        assertThat(bank.get("receivedOn").asString()).isEqualTo("2026-01-15");
        assertThat(bank.get("kind").asString()).isEqualTo("PAYMENT");
        assertThat(bank.get("source").asString()).isEqualTo("BANK");
        assertThat(bank.get("billingMonth").asString()).isEqualTo("2026-01");
        assertThat(bank.get("bankReference").asString()).isEqualTo("ПД-5");
        assertThat(bank.has("receiptNumber")).isFalse();
        assertThat(bank.get("amount").asString()).isEqualTo("100.00");
        assertThat(bank.get("allocations")).hasSize(1);
        assertThat(bank.get("allocations").get(0).get("chargeId").asString()).isEqualTo(given.membership().toString());
        assertThat(bank.get("allocations").get(0).get("amount").asString()).isEqualTo("100.00");

        JsonNode paidCash = payment(account, cash);
        assertThat(paidCash.get("receivedOn").asString()).isEqualTo(kyivToday().toString());
        assertThat(paidCash.get("source").asString()).isEqualTo("CASH");
        assertThat(paidCash.get("receiptNumber").asLong()).isEqualTo(saved.get("receiptNumber").asLong());
        assertThat(paidCash.get("groupId").asString()).isEqualTo(cash.toString());
        assertThat(paidCash.get("reversed").asBoolean()).isFalse();
        assertThat(paidCash.has("reversedBy")).isFalse();
        // what the cash paid: 500.00 of membership (January), then 200.00 of electricity
        JsonNode lines = paidCash.get("allocations");
        assertThat(lines).hasSize(2);
        assertThat(lines.findValuesAsString("chargeId")).containsExactlyInAnyOrder(given.membership().toString(),
                given.electricity().toString());
        assertThat(lines.findValuesAsString("amount")).containsExactlyInAnyOrder("500.00", "200.00");
        assertThat(lines.findValuesAsString("origin")).containsOnly("ENTRY");
        lines.forEach(line -> {
            assertThat(line.get("system").asBoolean()).isFalse();
            assertThat(line.get("chargeType").get("code").asString()).isIn("MEMBERSHIP", "ELECTRICITY");
            assertThat(line.has("serviceFrom")).isTrue();
        });
        // the board: the payer note (PAYMENTS_VIEW and MEMBERS_VIEW) and the author (CHARGES_VIEW)
        assertThat(paidCash.get("payerNote").asString()).isEqualTo("Принесла дочка");
        assertThat(paidCash.get("createdByName").asString()).isNotBlank();
        assertThat(account.get("prepaymentAllocations")).isEmpty();
    }

    @Test
    void moneyAboveTheDebtIsThePrepaymentAndThePlotOwesNothing() throws Exception {
        UUID plot = newPlot();
        UUID membership = tariffCharge(plot, "MEMBERSHIP", "100.00", OCTOBER);
        saved(treasurer(), cash(plot, "150.00"));

        JsonNode account = account(chairman(), plot);

        assertThat(account.get("debts").get(0).get("debt").asString()).isEqualTo("0.00");
        assertThat(account.get("total").asString()).isEqualTo("0.00");
        assertThat(account.get("prepayment").asString()).isEqualTo("50.00");
        assertThat(charge(account, membership).get("paid").asString()).isEqualTo("100.00");
        assertThat(charge(account, membership).get("open").asString()).isEqualTo("0.00");
    }

    @Test
    void reversalsAndAdjustmentsCarryNoPaidOrOpenAndAnAdjustmentChangesWhatIsOpen() throws Exception {
        Adjusted given = adjustedPlot();
        saved(treasurer(), payment(given.plot(), "CASH", "10.00", kyivToday().toString(),
                List.of(line(given.original(), "10.00"))));

        JsonNode account = account(chairman(), given.plot());

        // March 30.00 adjusted by -5.00: 25.00 effective, 10.00 paid, 15.00 open; April 25.00 open
        assertThat(charge(account, given.original()).get("paid").asString()).isEqualTo("10.00");
        assertThat(charge(account, given.original()).get("open").asString()).isEqualTo("15.00");
        assertThat(charge(account, given.april()).get("open").asString()).isEqualTo("25.00");
        assertThat(charge(account, given.adjustment()).has("paid")).isFalse();
        assertThat(charge(account, given.adjustment()).has("open")).isFalse();
        assertThat(account.get("total").asString()).isEqualTo("40.00");
    }

    @Test
    void aReversedPaymentIsShownReversedNextToItsReversalAndItsMoneyIsTakenBack() throws Exception {
        Paid given = paidPlot();
        JsonNode reversed = body(reverse(chairman(), given.bank(), "Помилковий платіж").andExpect(status().isCreated()));
        UUID reversal = UUID.fromString(reversed.get("reversedBy").get("id").asString());

        JsonNode account = account(chairman(), given.plot());

        assertThat(account.get("total").asString()).isEqualTo("850.00");
        assertThat(account.get("prepayment").asString()).isEqualTo("0.00");
        assertThat(charge(account, given.membership()).get("paid").asString()).isEqualTo("0.00");
        assertThat(charge(account, given.membership()).get("open").asString()).isEqualTo("600.00");
        // the reversal keeps the day of its payment and was entered later: listed first
        assertThat(ids(account.get("payments"))).containsExactly(reversal.toString(), given.bank().toString());
        JsonNode original = payment(account, given.bank());
        assertThat(original.get("reversed").asBoolean()).isTrue();
        assertThat(original.get("reversedBy").asString()).isEqualTo(reversal.toString());
        assertThat(original.get("amount").asString()).isEqualTo("100.00"); // the payment is never changed
        assertThat(original.get("allocations")).hasSize(2);
        assertThat(original.get("allocations").get(0).get("amount").asString()).isEqualTo("100.00");
        assertThat(original.get("allocations").get(1).get("amount").asString()).isEqualTo("-100.00");
        assertThat(original.get("allocations").get(1).get("releaseCause").asString()).isEqualTo("PAYMENT_REVERSED");
        JsonNode reversalRow = payment(account, reversal);
        assertThat(reversalRow.get("kind").asString()).isEqualTo("PAYMENT_REVERSAL");
        assertThat(reversalRow.get("amount").asString()).isEqualTo("-100.00");
        assertThat(reversalRow.get("receivedOn").asString()).isEqualTo("2026-01-15");
        assertThat(reversalRow.get("reverses").asString()).isEqualTo(given.bank().toString());
        assertThat(reversalRow.get("reason").asString()).isEqualTo("Помилковий платіж");
        assertThat(reversalRow.get("reversed").asBoolean()).isFalse();
        assertThat(reversalRow.get("allocations")).isEmpty();
    }

    @Test
    void placementsOfThePrepaymentAreListedAsAllocatedFromPrepayment() throws Exception {
        UUID plot = newPlot();
        // paid before the charges were booked (as the owner, no event): all of it waits as prepayment
        UUID payment = saved(treasurer(), payment(plot, "CASH", "120.00", "2026-01-10", List.of()));
        UUID membership = tariffCharge(plot, "MEMBERSHIP", "60.00", "2026-01");
        UUID electricity = tariffCharge(plot, "ELECTRICITY", "40.00", "2026-02");
        UUID land = tariffCharge(plot, "LAND_TAX", "30.00", "2026-03");
        // by hand: «Розподілити аванс» places 60.00 on the membership
        allocatePrepayment(treasurer(), plot, false, lines(List.of(line(membership, "60.00")), null))
                .andExpect(status().isCreated());
        // by the system: an automatic allocation, as the allocator writes it (two rows of one run, no author)
        ownerJdbc().update("insert into allocation (id, tenant_id, payment_id, charge_id, plot_id, amount, origin) "
                + "values (?, ?, ?, ?, ?, 40.00, 'PREPAYMENT_AUTO'), (?, ?, ?, ?, ?, 20.00, 'PREPAYMENT_AUTO')",
                UUID.randomUUID(), DEMO_TENANT, payment, electricity, plot, UUID.randomUUID(), DEMO_TENANT, payment,
                land, plot);

        JsonNode account = account(member(plot), plot);

        assertThat(account.get("total").asString()).isEqualTo("10.00");
        assertThat(account.get("prepayment").asString()).isEqualTo("0.00");
        JsonNode placements = account.get("prepaymentAllocations");
        assertThat(placements).hasSize(2);
        JsonNode automatic = placements.get(0); // newest first
        assertThat(automatic.get("origin").asString()).isEqualTo("PREPAYMENT_AUTO");
        assertThat(automatic.get("system").asBoolean()).isTrue();
        assertThat(automatic.get("amount").asString()).isEqualTo("60.00");
        assertThat(automatic.get("allocatedOn").asString()).isEqualTo(kyivToday().toString());
        assertThat(ids(automatic.get("lines"))).hasSize(2);
        assertThat(automatic.get("lines").get(0).get("chargeId").asString()).isEqualTo(electricity.toString());
        assertThat(automatic.get("lines").get(1).get("chargeId").asString()).isEqualTo(land.toString());
        assertThat(automatic.get("lines").findValuesAsString("origin")).containsOnly("PREPAYMENT_AUTO");
        JsonNode manual = placements.get(1);
        assertThat(manual.get("origin").asString()).isEqualTo("PREPAYMENT_MANUAL");
        assertThat(manual.get("system").asBoolean()).isFalse();
        assertThat(manual.get("amount").asString()).isEqualTo("60.00");
        assertThat(manual.get("lines").get(0).get("chargeId").asString()).isEqualTo(membership.toString());
        // the payment lists everything it paid, the placements included
        assertThat(payment(account, payment).get("allocations").findValuesAsString("origin"))
                .containsExactlyInAnyOrder("PREPAYMENT_MANUAL", "PREPAYMENT_AUTO", "PREPAYMENT_AUTO");
    }

    @Test
    void thePaymentsOnlyFilterListsOnlyPaymentsAndTheirReversalsAndNeverChangesTheDebts() throws Exception {
        Paid given = paidPlot();
        UUID cash = saved(treasurer(), cash(given.plot(), "50.00"));
        reverse(chairman(), cash, "Помилка").andExpect(status().isCreated());
        JsonNode all = account(chairman(), given.plot());

        JsonNode payments = body(send(chairman(), get(ACCOUNT.formatted(given.plot())).param("kind", "PAYMENTS"),
                null).andExpect(status().isOk()));

        assertThat(payments.get("charges")).isEmpty();
        assertThat(payments.get("prepaymentAllocations")).isEmpty();
        assertThat(payments.get("payments")).hasSize(3);
        assertThat(payments.get("payments").findValuesAsString("kind")).containsExactlyInAnyOrder("PAYMENT", "PAYMENT",
                "PAYMENT_REVERSAL");
        assertThat(payments.get("total").asString()).isEqualTo(all.get("total").asString()).isEqualTo("750.00");
        assertThat(payments.get("debts")).isEqualTo(all.get("debts"));
        assertThat(payments.get("prepayment").asString()).isEqualTo(all.get("prepayment").asString());
        // with the range: only the payments received in it
        JsonNode january = body(send(chairman(), get(ACCOUNT.formatted(given.plot())).param("kind", "PAYMENTS")
                .param("from", "2026-01-01").param("to", "2026-01-31"), null).andExpect(status().isOk()));
        assertThat(ids(january.get("payments"))).containsExactly(given.bank().toString());
        assertThat(january.get("total").asString()).isEqualTo("750.00");
        // a charge kind lists no payments
        JsonNode regular = body(send(chairman(), get(ACCOUNT.formatted(given.plot())).param("kind", "REGULAR"), null)
                .andExpect(status().isOk()));
        assertThat(regular.get("charges")).hasSize(2);
        assertThat(regular.get("payments")).isEmpty();
        assertThat(regular.get("total").asString()).isEqualTo("750.00");
    }

    @Test
    void aMemberSeesThePaymentsOfTheirOwnPlotWithReceiptNumbersButNeverTheNoteOrTheAuthor() throws Exception {
        Paid given = paidPlot();
        own(demoMember("member@demo.local"), given.plot());
        JsonNode saved = body(save(treasurer(), with(cash(given.plot(), "700.00"), "payerNote", "Принесла дочка"))
                .andExpect(status().isCreated())).get("payments").get(0);
        UUID cash = UUID.fromString(saved.get("id").asString());

        JsonNode account = account(member(), given.plot());

        assertThat(account.get("total").asString()).isEqualTo("50.00");
        assertThat(account.get("prepayment").asString()).isEqualTo("0.00");
        assertThat(charge(account, given.electricity()).get("open").asString()).isEqualTo("50.00");
        assertThat(account.get("payments")).hasSize(2);
        JsonNode paid = payment(account, cash);
        assertThat(paid.get("receiptNumber").asLong()).isEqualTo(saved.get("receiptNumber").asLong());
        assertThat(paid.get("amount").asString()).isEqualTo("700.00");
        assertThat(paid.get("allocations").findValuesAsString("amount")).containsExactlyInAnyOrder("500.00", "200.00");
        assertThat(payment(account, given.bank()).get("bankReference").asString()).isEqualTo("ПД-5");
        account.get("payments").forEach(payment -> {
            assertThat(payment.has("payerNote")).as("the payer note").isFalse();
            assertThat(payment.has("createdByName")).as("the author").isFalse();
        });

        // a plot that is not theirs: the same 404 as an unknown one, no amount
        UUID other = newPlot();
        saved(treasurer(), cash(other, "10.00"));
        send(member(), get(ACCOUNT.formatted(other)), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"))
                .andExpect(jsonPath("$.payments").doesNotExist());
    }

    @Test
    void theBoardSeesPaymentsAsTheirPermissionsAllow() throws Exception {
        Paid given = paidPlot();
        saved(treasurer(), with(cash(given.plot(), "10.00"), "payerNote", "Сусід"));

        // PAYMENTS_VIEW without MEMBERS_VIEW: no payer note; CHARGES_VIEW: the author
        JsonNode noMembers = account(customRoleToken("CHARGES_VIEW", "PAYMENTS_VIEW"), given.plot());
        assertThat(noMembers.get("payments")).hasSize(2);
        noMembers.get("payments").forEach(payment -> {
            assertThat(payment.has("payerNote")).isFalse();
            assertThat(payment.get("createdByName").asString()).isNotBlank();
        });
        // the treasurer views members too
        assertThat(account(treasurer(), given.plot()).get("payments").findValuesAsString("payerNote"))
                .containsExactly("Сусід");
        // CHARGES_VIEW without PAYMENTS_VIEW: the debts after payments, but no payment
        JsonNode chargesOnly = account(customRoleToken("CHARGES_VIEW"), given.plot());
        assertThat(chargesOnly.get("total").asString()).isEqualTo("740.00");
        assertThat(chargesOnly.get("payments")).isEmpty();
        assertThat(chargesOnly.get("prepaymentAllocations")).isEmpty();
    }

    /** The member's token after making them an owner of the plot. */
    private String member(UUID plot) {
        own(demoMember("member@demo.local"), plot);
        return member();
    }
}
