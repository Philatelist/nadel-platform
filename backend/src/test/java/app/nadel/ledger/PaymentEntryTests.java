package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Entering a payment for one plot end to end ({@code POST /payments?preview=}, feature 015 §2.1, worked examples 1, 2,
 * 10, 11, 16 and 18): the proposal over every open charge, the treasurer's own lines, the booking month of a payment
 * received in a closed month, the refusals (common land, archived, future date, zero amount, too long texts, a bank
 * reference on cash, an unknown or foreign plot) and the stale digest. A preview writes nothing. The list and the card
 * show what was saved.
 */
class PaymentEntryTests extends PaymentApiSupport {

    /** The debts of examples 1, 2 and 10: membership 600.00 (January) and electricity 250.00 (February). */
    private record Debts(UUID plot, UUID membership, UUID electricity) {
    }

    private Debts example1Debts() {
        UUID plot = newPlot();
        return new Debts(plot, tariffCharge(plot, "MEMBERSHIP", "600.00", "2026-01"),
                tariffCharge(plot, "ELECTRICITY", "250.00", "2026-02"));
    }

    private BigDecimal allocated(UUID charge) {
        return ownerJdbc().queryForObject("select coalesce(sum(amount), 0) from allocation where charge_id = ?",
                BigDecimal.class, charge);
    }

    private static JsonNode line(JsonNode part, UUID charge) {
        for (JsonNode line : part.get("lines")) {
            if (line.get("chargeId").asString().equals(charge.toString())) {
                return line;
            }
        }
        throw new AssertionError("No proposal line for the charge");
    }

    @Test
    void example1TheProposalPaysTheOldestChargeFirstAndTheSaveBooksIt() throws Exception {
        Debts debts = example1Debts();

        JsonNode preview = body(preview(treasurer(), cash(debts.plot(), "700.00")).andExpect(status().isOk()));
        JsonNode part = preview.get("parts").get(0);
        assertThat(preview.get("preview").asBoolean()).isTrue();
        assertThat(preview.get("payments")).isEmpty();
        assertThat(part.get("lines").findValuesAsString("chargeId"))
                .containsExactly(debts.membership().toString(), debts.electricity().toString());
        assertThat(line(part, debts.membership()).get("proposed").asString()).isEqualTo("600.00");
        assertThat(line(part, debts.membership()).get("open").asString()).isEqualTo("600.00");
        assertThat(line(part, debts.membership()).get("chargeType").get("code").asString()).isEqualTo("MEMBERSHIP");
        assertThat(line(part, debts.membership()).get("billingMonth").asString()).isEqualTo("2026-01");
        assertThat(line(part, debts.electricity()).get("proposed").asString()).isEqualTo("100.00");
        assertThat(part.get("allocatedTotal").asString()).isEqualTo("700.00");
        assertThat(part.get("prepayment").asString()).isEqualTo("0.00");
        assertThat(part.get("plotNumber").asString()).isEqualTo(plotNumber(debts.plot()));

        Long before = lastReceipt();
        JsonNode saved = body(save(treasurer(), with(cash(debts.plot(), "700.00"), "digest",
                preview.get("digest").asString())).andExpect(status().isCreated()));
        JsonNode payment = saved.get("payments").get(0);
        assertThat(saved.get("preview").asBoolean()).isFalse();
        assertThat(payment.get("receiptNumber").asLong()).isEqualTo(before == null ? 1 : before + 1);
        assertThat(payment.get("groupId").asString()).isEqualTo(payment.get("id").asString());
        assertThat(payment.get("plotId").asString()).isEqualTo(debts.plot().toString());
        assertThat(payment.get("amount").asString()).isEqualTo("700.00");
        assertThat(allocated(debts.membership())).isEqualByComparingTo("600.00");
        assertThat(allocated(debts.electricity())).isEqualByComparingTo("100.00");

        // open electricity 150.00 after saving: a new preview shows it
        JsonNode next = body(preview(treasurer(), cash(debts.plot(), "1.00")).andExpect(status().isOk()));
        assertThat(next.get("parts").get(0).get("lines")).hasSize(1);
        assertThat(line(next.get("parts").get(0), debts.electricity()).get("open").asString()).isEqualTo("150.00");
        Map<String, Object> row = ownerJdbc().queryForMap("select p.kind, p.source, a.origin, a.created_by from payment p "
                + "join allocation a on a.payment_id = p.id where a.charge_id = ?", debts.membership());
        assertThat(row).containsEntry("kind", "PAYMENT").containsEntry("source", "CASH")
                .containsEntry("origin", "ENTRY")
                .containsEntry("created_by", demoMember("treasurer@demo.local"));
    }

    @Test
    void example2TheMoneyAboveTheDebtsStaysAsPrepayment() throws Exception {
        Debts debts = example1Debts();

        JsonNode part = body(preview(treasurer(), cash(debts.plot(), "1000.00")).andExpect(status().isOk()))
                .get("parts").get(0);
        assertThat(line(part, debts.membership()).get("proposed").asString()).isEqualTo("600.00");
        assertThat(line(part, debts.electricity()).get("proposed").asString()).isEqualTo("250.00");
        assertThat(part.get("prepayment").asString()).isEqualTo("150.00");

        save(treasurer(), payment(debts.plot(), "BANK", "1000.00", kyivToday().toString(), null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payments[0].receiptNumber").doesNotExist());
        assertThat(allocated(debts.membership())).isEqualByComparingTo("600.00");
        assertThat(allocated(debts.electricity())).isEqualByComparingTo("250.00");
        assertThat(ownerJdbc().queryForObject("select amount - (select sum(amount) from allocation a "
                + "where a.payment_id = p.id) from payment p where plot_id = ?", BigDecimal.class, debts.plot()))
                .isEqualByComparingTo("150.00");
    }

    @Test
    void example10TheTreasurersLinesAreSavedAsChosen() throws Exception {
        Debts debts = example1Debts();
        List<Map<String, Object>> lines = List.of(line(debts.electricity(), "250.00"),
                line(debts.membership(), "400.00"));

        JsonNode part = body(preview(treasurer(), payment(debts.plot(), "CASH", "700.00", kyivToday().toString(),
                lines)).andExpect(status().isOk())).get("parts").get(0);
        assertThat(line(part, debts.membership()).get("proposed").asString()).isEqualTo("400.00");
        assertThat(line(part, debts.electricity()).get("proposed").asString()).isEqualTo("250.00");

        save(treasurer(), payment(debts.plot(), "CASH", "700.00", kyivToday().toString(), lines))
                .andExpect(status().isCreated());

        assertThat(allocated(debts.membership())).isEqualByComparingTo("400.00");
        assertThat(allocated(debts.electricity())).isEqualByComparingTo("250.00");
        JsonNode next = body(preview(treasurer(), cash(debts.plot(), "1.00")).andExpect(status().isOk()));
        assertThat(line(next.get("parts").get(0), debts.membership()).get("open").asString()).isEqualTo("200.00");
    }

    @Test
    void anEmptyListOfLinesPlacesNothing() throws Exception {
        Debts debts = example1Debts();

        save(treasurer(), payment(debts.plot(), "CASH", "50.00", kyivToday().toString(), List.of()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parts[0].allocatedTotal").value("0.00"))
                .andExpect(jsonPath("$.parts[0].prepayment").value("50.00"));

        assertThat(paymentCount(debts.plot())).isOne();
        assertThat(allocationCount(debts.plot())).isZero();
    }

    @Test
    void example11MoreThanThePaymentIsRefusedAndNothingIsSaved() throws Exception {
        Debts debts = example1Debts();
        List<Map<String, Object>> lines = List.of(line(debts.membership(), "600.00"),
                line(debts.electricity(), "250.00"));

        save(treasurer(), payment(debts.plot(), "CASH", "700.00", kyivToday().toString(), lines))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALLOCATION_EXCEEDS_PAYMENT"))
                .andExpect(jsonPath("$.errors[0].field").value("lines"));

        assertThat(paymentCount(debts.plot())).isZero();
        assertThat(lastReceipt()).isNull();
    }

    @Test
    void aLineAboveWhatIsOpenOnItsChargeIsRefused() throws Exception {
        Debts debts = example1Debts();

        preview(treasurer(), payment(debts.plot(), "CASH", "700.00", kyivToday().toString(),
                List.of(line(debts.electricity(), "250.01"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALLOCATION_EXCEEDS_CHARGE"))
                .andExpect(jsonPath("$.errors[0].field").value("lines[0].amount"));
    }

    @Test
    void aLineOnAnotherPlotsChargeTwiceOrNotAboveZeroIsAValidationError() throws Exception {
        Debts debts = example1Debts();
        Debts other = example1Debts();

        save(treasurer(), payment(debts.plot(), "CASH", "700.00", kyivToday().toString(),
                List.of(line(other.membership(), "10.00"), line(debts.membership(), "10.00"),
                        line(debts.membership(), "10.00"), line(debts.electricity(), "0.00"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("lines[0].chargeId"))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.errors[1].field").value("lines[2].chargeId"))
                .andExpect(jsonPath("$.errors[1].code").value("DUPLICATE"))
                .andExpect(jsonPath("$.errors[2].field").value("lines[3].amount"))
                .andExpect(jsonPath("$.errors[2].code").value("AMOUNT_NOT_POSITIVE"));
        assertThat(paymentCount(debts.plot())).isZero();
    }

    @Test
    void example16APaymentReceivedInAClosedMonthCountsInTheNextOpenMonthAndKeepsItsDate() throws Exception {
        UUID plot = newPlot();
        UUID january = closedMonth(DEMO_TENANT, "2026-01");

        JsonNode saved = body(save(treasurer(), payment(plot, "CASH", "100.00", "2026-01-31", null))
                .andExpect(status().isCreated())).get("payments").get(0);

        assertThat(saved.get("billingMonth").asString()).isEqualTo("2026-02");
        assertThat(saved.get("receivedOn").asString()).isEqualTo("2026-01-31");
        assertThat(ownerJdbc().queryForObject("select count(*) from payment where billing_month_id = ?",
                Integer.class, january)).isZero();
        assertThat(ownerJdbc().queryForObject("select m.month::text from payment p join billing_month m "
                + "on m.id = p.billing_month_id where p.plot_id = ?", String.class, plot)).isEqualTo("2026-02-01");
    }

    @Test
    void example18AnOpeningBalanceReceivesThePayment() throws Exception {
        UUID plot = newPlot();
        UUID opening = UUID.fromString(body(send(chairman(), post(OPENING.formatted(plot)),
                opening(typeId(DEMO_TENANT, "MEMBERSHIP"), "1200.00", "2026-01-01", null))
                .andExpect(status().isCreated())).get("id").asString());

        JsonNode part = body(save(treasurer(), cash(plot, "500.00")).andExpect(status().isCreated()))
                .get("parts").get(0);

        assertThat(line(part, opening).get("kind").asString()).isEqualTo("OPENING");
        assertThat(line(part, opening).get("proposed").asString()).isEqualTo("500.00");
        assertThat(allocated(opening)).isEqualByComparingTo("500.00");
        JsonNode next = body(preview(treasurer(), cash(plot, "1.00")).andExpect(status().isOk()));
        assertThat(line(next.get("parts").get(0), opening).get("open").asString()).isEqualTo("700.00");
    }

    @Test
    void commonLandAndArchivedPlotsTakeNoPayment() throws Exception {
        UUID common = newPlot(DEMO_TENANT, "COMMON", false);
        UUID archived = newPlot(DEMO_TENANT, "PLOT", true);

        save(treasurer(), cash(common, "10.00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_COMMON_LAND"));
        preview(treasurer(), cash(archived, "10.00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_ARCHIVED"));
        save(treasurer(), cash(archived, "10.00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_ARCHIVED"));

        assertThat(paymentCount(common) + paymentCount(archived)).isZero();
    }

    @Test
    void theValuesAreChecked() throws Exception {
        UUID plot = newPlot();

        save(treasurer(), payment(plot, "CASH", "10.00", kyivToday().plusDays(1).toString(), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("receivedOn"))
                .andExpect(jsonPath("$.errors[0].code").value("DATE_IN_FUTURE"));
        save(treasurer(), cash(plot, "0.00")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"))
                .andExpect(jsonPath("$.errors[0].code").value("AMOUNT_NOT_POSITIVE"));
        save(treasurer(), with(cash(plot, "10.00"), "payerNote", "н".repeat(201))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("payerNote"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        save(treasurer(), with(payment(plot, "BANK", "10.00", kyivToday().toString(), null), "bankReference",
                "7".repeat(65))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("bankReference"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        save(treasurer(), with(cash(plot, "10.00"), "bankReference", "ПД-17")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("bankReference"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
        save(treasurer(), with(cash(plot, "10.00"), "payerMemberId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("payerMemberId"))
                .andExpect(jsonPath("$.errors[0].code").value("MEMBER_NOT_FOUND"));
        Map<String, Object> noPlot = cash(plot, "10.00");
        noPlot.remove("plotId");
        save(treasurer(), noPlot).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("plotId"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));

        assertThat(paymentCount(plot)).isZero();
        assertThat(lastReceipt()).isNull();
    }

    @Test
    void anUnknownPlotAndAPlotOfAnotherCooperativeAreNotFound() throws Exception {
        UUID foreign = newPlot(OTHER_TENANT, "PLOT", false);

        save(treasurer(), cash(UUID.randomUUID(), "10.00")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        save(treasurer(), cash(foreign, "10.00")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        preview(treasurer(), cash(foreign, "10.00")).andExpect(status().isNotFound());

        assertThat(paymentCount(foreign)).isZero();
    }

    @Test
    void aSaveWithTheDigestOfAnOutdatedPreviewIsRefusedAndWritesNothing() throws Exception {
        Debts debts = example1Debts();
        String digest = body(preview(treasurer(), cash(debts.plot(), "700.00")).andExpect(status().isOk()))
                .get("digest").asString();

        // another user books a charge on the plot after the preview
        tariffCharge(debts.plot(), "LAND_TAX", "120.00", "2026-03");

        save(treasurer(), with(cash(debts.plot(), "700.00"), "digest", digest))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PREVIEW_OUTDATED"));

        assertThat(paymentCount(debts.plot())).isZero();
        assertThat(allocationCount(debts.plot())).isZero();
        assertThat(lastReceipt()).isNull();
        // the digest of a fresh preview is accepted
        String fresh = body(preview(treasurer(), cash(debts.plot(), "700.00"))).get("digest").asString();
        assertThat(fresh).isNotEqualTo(digest);
        save(treasurer(), with(cash(debts.plot(), "700.00"), "digest", fresh)).andExpect(status().isCreated());
    }

    @Test
    void aPreviewWritesNothing() throws Exception {
        Debts debts = example1Debts();
        int months = ownerJdbc().queryForObject("select count(*) from billing_month", Integer.class);

        preview(treasurer(), with(with(cash(debts.plot(), "700.00"), "payerNote", "Сусідка Ганна"), "payerMemberId",
                demoMember("member@demo.local").toString())).andExpect(status().isOk());
        preview(treasurer(), payment(debts.plot(), "CASH", "700.00", "2025-06-15", null)).andExpect(status().isOk());

        assertThat(paymentCount(debts.plot())).isZero();
        assertThat(allocationCount(debts.plot())).isZero();
        assertThat(lastReceipt()).isNull();
        assertThat(ownerJdbc().queryForObject("select count(*) from billing_month", Integer.class)).isEqualTo(months);
        assertThat(entries(debts.plot(), "PAYMENT_RECORDED")).isEmpty();
    }

    @Test
    void theListAndTheCardShowTheSavedPayment() throws Exception {
        Debts debts = example1Debts();
        UUID payer = demoMember("member@demo.local");
        JsonNode cash = body(save(treasurer(), with(with(cash(debts.plot(), "700.00"), "payerMemberId",
                payer.toString()), "payerNote", "Принесла дочка")).andExpect(status().isCreated()))
                .get("payments").get(0);
        JsonNode bank = body(save(treasurer(), with(payment(debts.plot(), "BANK", "50.00", kyivToday().toString(),
                null), "bankReference", " ПД-17 ")).andExpect(status().isCreated())).get("payments").get(0);

        JsonNode cashOnly = body(send(treasurer(), get(PAYMENTS + "?source=CASH&plotId=" + debts.plot()), null)
                .andExpect(status().isOk()));
        assertThat(cashOnly.get("items")).hasSize(1);
        JsonNode item = cashOnly.get("items").get(0);
        assertThat(item.get("id").asString()).isEqualTo(cash.get("id").asString());
        assertThat(item.get("receiptNumber").asLong()).isEqualTo(cash.get("receiptNumber").asLong());
        assertThat(item.get("payer").get("memberId").asString()).isEqualTo(payer.toString());
        assertThat(item.get("allocated").asString()).isEqualTo("700.00");
        assertThat(item.get("prepayment").asString()).isEqualTo("0.00");
        assertThat(item.get("reversed").asBoolean()).isFalse();
        JsonNode all = body(send(treasurer(), get(PAYMENTS + "?plotId=" + debts.plot() + "&from="
                + kyivToday() + "&to=" + kyivToday()), null).andExpect(status().isOk()));
        assertThat(all.get("items").findValuesAsString("id")).hasSize(2);
        JsonNode bankRow = all.get("items").get(0).has("receiptNumber") ? all.get("items").get(1)
                : all.get("items").get(0);
        assertThat(bankRow.get("id").asString()).isEqualTo(bank.get("id").asString());
        assertThat(bankRow.get("bankReference").asString()).isEqualTo("ПД-17");
        // the open electricity (150.00 after the cash payment) receives the bank payment
        assertThat(bankRow.get("allocated").asString()).isEqualTo("50.00");
        assertThat(bankRow.get("prepayment").asString()).isEqualTo("0.00");
        send(treasurer(), get(PAYMENTS + "?from=2026-02-02&to=2026-02-01"), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("from"));

        JsonNode details = body(card(treasurer(), UUID.fromString(cash.get("id").asString()))
                .andExpect(status().isOk()));
        assertThat(details.get("payerNote").asString()).isEqualTo("Принесла дочка");
        assertThat(details.get("allocations")).hasSize(2);
        assertThat(details.get("allocations").findValuesAsString("origin")).containsOnly("ENTRY");
        assertThat(details.get("allocations").get(0).get("system").asBoolean()).isFalse();
        assertThat(details.get("groupParts")).isEmpty();
        assertThat(details.has("createdByName")).isTrue();
        assertThat(details.get("reversed").asBoolean()).isFalse();
        // without MEMBERS_VIEW the payer note is not returned
        String paymentsOnly = customRoleToken("PAYMENTS_VIEW");
        assertThat(body(card(paymentsOnly, UUID.fromString(cash.get("id").asString())).andExpect(status().isOk()))
                .has("payerNote")).isFalse();
        card(treasurer(), UUID.fromString(bank.get("id").asString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.payerNote").doesNotExist());
        card(treasurer(), UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void paymentsNeedTheirPermissions() throws Exception {
        UUID plot = newPlot();

        save(member(), cash(plot, "10.00")).andExpect(status().isForbidden());
        preview(customRoleToken("PAYMENTS_VIEW"), cash(plot, "10.00")).andExpect(status().isForbidden());
        send(member(), get(PAYMENTS), null).andExpect(status().isForbidden());
        send(operator(), get(PAYMENTS), null).andExpect(status().isForbidden());
        preview(customRoleToken("PAYMENTS_ENTER"), cash(plot, "10.00")).andExpect(status().isOk());
        assertThat(paymentCount(plot)).isZero();
    }

    @Test
    void aPlotWithPaymentsIsNotDeleted() throws Exception {
        UUID prepaid = newPlot();
        save(treasurer(), cash(prepaid, "100.00")).andExpect(status().isCreated());
        Debts debts = example1Debts();
        save(treasurer(), cash(debts.plot(), "100.00")).andExpect(status().isCreated());

        send(chairman(), delete("/api/v1/plots/" + prepaid), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_PAYMENTS"));
        // charges are named first
        send(chairman(), delete("/api/v1/plots/" + debts.plot()), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_CHARGES"));
        assertThat(ownerJdbc().queryForObject("select count(*) from plot where id in (?, ?)", Integer.class, prepaid,
                debts.plot())).isEqualTo(2);
    }
}
