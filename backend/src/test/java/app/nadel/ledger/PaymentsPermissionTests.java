package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * The permission matrix of payments (feature 015 §2.12, §7; CLAUDE.md "Security and data"): every payments endpoint
 * (the list, the card, the entry preview and save, the reversal, the reallocation preview and save, the prepayment
 * allocation preview and save) for the chairman, the treasurer, a member (with their own plot and with another plot),
 * the demo electrician, a member and a chairman of another cooperative, the platform operator and no token; and the
 * payments on the plot account for each of them. Viewing needs {@code PAYMENTS_VIEW}, entering, reallocating and
 * allocating prepayment {@code PAYMENTS_ENTER}, reversing {@code PAYMENTS_REVERSE} (the chairman only); another
 * cooperative's payment or plot is 404 even with every permission; the operator has none. A refused call writes
 * nothing.
 */
class PaymentsPermissionTests extends PaymentApiSupport {

    /**
     * A plot with membership 100.00 (January) and a cash payment of 30.00 placed on nothing (prepayment 30.00): paid
     * before the charge was booked (as the owner, no event), so the automatic allocation left it waiting.
     */
    private record Given(UUID plot, UUID charge, UUID payment) {
    }

    private Given given() throws Exception {
        UUID plot = newPlot();
        UUID payment = saved(treasurer(), payment(plot, "CASH", "30.00", kyivToday().toString(), List.of()));
        UUID charge = tariffCharge(plot, "MEMBERSHIP", "100.00", "2026-01");
        return new Given(plot, charge, payment);
    }

    /** The expected status of each endpoint, in the order they are called. */
    private record Expected(int list, int card, int entryPreview, int prepaymentPreview, int reallocatePreview,
            int prepaymentSave, int reallocateSave, int entrySave, int reverse) {

        static Expected all(int status) {
            return new Expected(status, status, status, status, status, status, status, status, status);
        }
    }

    /**
     * Calls every payments endpoint about the plot and its payment, previews before saves and the reversal last (so a
     * caller allowed everything finds a state each call accepts), and checks each status.
     */
    private void expect(String bearer, Given given, Expected expected) throws Exception {
        check(send(bearer, get(PAYMENTS + "?plotId=" + given.plot()), null), expected.list(), "list");
        check(card(bearer, given.payment()), expected.card(), "card");
        check(preview(bearer, cash(given.plot(), "10.00")), expected.entryPreview(), "entry preview");
        check(allocatePrepayment(bearer, given.plot(), true, lines(null, null)), expected.prepaymentPreview(),
                "prepayment preview");
        check(reallocate(bearer, given.payment(), true, lines(null, null)), expected.reallocatePreview(),
                "reallocation preview");
        check(allocatePrepayment(bearer, given.plot(), false, lines(null, null)), expected.prepaymentSave(),
                "prepayment save");
        check(reallocate(bearer, given.payment(), false, lines(null, null)), expected.reallocateSave(),
                "reallocation save");
        check(save(bearer, cash(given.plot(), "10.00")), expected.entrySave(), "entry save");
        check(reverse(bearer, given.payment(), "Помилковий платіж"), expected.reverse(), "reverse");
    }

    private static void check(ResultActions call, int expected, String endpoint) throws Exception {
        int actual = call.andReturn().getResponse().getStatus();
        assertThat(actual).as(endpoint).isEqualTo(expected);
        if (expected == 403) {
            call.andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
    }

    private JsonNode account(String bearer, UUID plot) throws Exception {
        return body(send(bearer, get(ACCOUNT.formatted(plot)), null).andExpect(status().isOk()));
    }

    private static String electrician() {
        return demo("electrician@demo.local");
    }

    /** A token of a fresh member of the other cooperative with the base role MEMBER. */
    private String otherMember() {
        String email = "payments-other-" + UUID.randomUUID() + "@example.com";
        UUID member = newMember(OTHER_TENANT, email);
        giveBaseRole(OTHER_TENANT, member, "MEMBER");
        return TestTokens.bearer(OTHER_TENANT, UUID.randomUUID().toString(), email);
    }

    // ---- the payments endpoints

    @Test
    void theChairmanMayDoEverything() throws Exception {
        Given given = given();

        expect(chairman(), given, new Expected(200, 200, 200, 200, 200, 201, 201, 201, 201));

        assertThat(paymentCount(given.plot())).isEqualTo(3); // the payment, its reversal and the new one
    }

    @Test
    void theTreasurerMayDoEverythingButReverse() throws Exception {
        Given given = given();

        expect(treasurer(), given, new Expected(200, 200, 200, 200, 200, 201, 201, 201, 403));

        assertThat(paymentCount(given.plot())).isEqualTo(2); // the payment and the new one, no reversal
        assertThat(body(card(treasurer(), given.payment())).get("reversed").asBoolean()).isFalse();
    }

    @Test
    void aMemberMayUseNoPaymentsEndpointForTheirOwnPlotOrAnother() throws Exception {
        Given own = given();
        own(demoMember("member@demo.local"), own.plot());
        Given other = given();
        int allocations = allocationCount(own.plot()) + allocationCount(other.plot());

        expect(member(), own, Expected.all(403));
        expect(member(), other, Expected.all(403));

        assertThat(paymentCount(own.plot()) + paymentCount(other.plot())).isEqualTo(2);
        assertThat(allocationCount(own.plot()) + allocationCount(other.plot())).isEqualTo(allocations);
    }

    @Test
    void theElectricianMayUseNoPaymentsEndpoint() throws Exception {
        Given given = given();

        expect(electrician(), given, Expected.all(403));

        assertThat(paymentCount(given.plot())).isEqualTo(1);
    }

    @Test
    void anotherCooperativeFindsNothingEvenWithEveryPermission() throws Exception {
        Given given = given();
        String otherChairman = otherChairman();

        // the list answers, without this cooperative's payment; everything about it is not found
        assertThat(body(send(otherChairman, get(PAYMENTS + "?plotId=" + given.plot()), null)
                .andExpect(status().isOk())).get("totalItems").asLong()).isZero();
        card(otherChairman, given.payment()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        for (boolean preview : new boolean[] {true, false}) {
            send(otherChairman, post(PAYMENTS + "?preview=" + preview), cash(given.plot(), "10.00"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
            allocatePrepayment(otherChairman, given.plot(), preview, lines(null, null))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
            reallocate(otherChairman, given.payment(), preview, lines(null, null)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        reverse(otherChairman, given.payment(), "Чуже").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // a member of the other cooperative has no payments permission at all
        expect(otherMember(), given, Expected.all(403));

        assertThat(paymentCount(given.plot())).isEqualTo(1);
        assertThat(allocationCount(given.plot())).isZero();
    }

    @Test
    void thePlatformOperatorMayUseNoPaymentsEndpoint() throws Exception {
        Given given = given();

        expect(operator(), given, Expected.all(403));

        assertThat(paymentCount(given.plot())).isEqualTo(1);
    }

    @Test
    void withoutATokenEveryPaymentsEndpointIsUnauthenticated() throws Exception {
        Given given = given();

        expect(null, given, Expected.all(401));

        assertThat(paymentCount(given.plot())).isEqualTo(1);
    }

    // ---- the payments on the plot account

    @Test
    void theBoardSeesThePaymentsOnEveryAccount() throws Exception {
        Given given = given();

        for (String bearer : List.of(chairman(), treasurer())) {
            JsonNode account = account(bearer, given.plot());
            assertThat(account.get("payments").findValuesAsString("id")).containsExactly(given.payment().toString());
            assertThat(account.get("prepayment").asString()).isEqualTo("30.00");
            assertThat(account.get("total").asString()).isEqualTo("100.00");
        }
    }

    @Test
    void aMemberSeesThePaymentsOfTheirOwnPlotOnly() throws Exception {
        Given own = given();
        own(demoMember("member@demo.local"), own.plot());
        Given other = given();

        JsonNode account = account(member(), own.plot());
        assertThat(account.get("payments").findValuesAsString("id")).containsExactly(own.payment().toString());
        assertThat(account.get("payments").get(0).has("receiptNumber")).isTrue();
        assertThat(account.get("payments").get(0).has("payerNote")).isFalse();
        assertThat(account.get("payments").get(0).has("createdByName")).isFalse();
        assertThat(account.get("prepayment").asString()).isEqualTo("30.00");

        for (String query : List.of("", "?kind=PAYMENTS")) {
            send(member(), get(ACCOUNT.formatted(other.plot()) + query), null)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"))
                    .andExpect(jsonPath("$.payments").doesNotExist());
        }
    }

    @Test
    void theElectricianAndAnotherCooperativeFindNoAccountAndTheOperatorIsRefused() throws Exception {
        Given given = given();

        for (String bearer : List.of(electrician(), otherChairman(), otherMember())) {
            send(bearer, get(ACCOUNT.formatted(given.plot())), null).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"))
                    .andExpect(jsonPath("$.payments").doesNotExist());
        }
        send(operator(), get(ACCOUNT.formatted(given.plot())), null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }
}
