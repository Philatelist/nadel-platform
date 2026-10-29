package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * Who may print a cash receipt ({@code GET /payments/{id}/receipt.pdf}, feature 016 §2.9, §2.10; CLAUDE.md "Security
 * and data"): {@code PAYMENTS_VIEW} (the treasurer, the chairman, a custom role with it), or a member who owns every
 * plot of the payment's group now. A member's foreign payment, a split group with a plot that is not theirs, an
 * unknown id, a reversal row and another cooperative's payment are the same 404 {@code NOT_FOUND}; a caller with
 * neither the permission nor a plot of their own (the demo electrician) and the platform operator are 403; no token is
 * 401. A member never learns that a foreign payment is a bank one (404, not 409).
 */
class ReceiptAccessTests extends DocumentsTestSupport {

    @AfterEach
    void unlinkDemoUsers() {
        // a first sign-in links a demo member to its subject; other tests expect them unlinked
        unlinkByEmail(DEMO_TENANT, "electrician@demo.local");
    }

    private static void ok(ResultActions call) throws Exception {
        call.andExpect(status().isOk());
        assertThat(call.andReturn().getResponse().getContentType()).isEqualTo("application/pdf");
    }

    private static void notFound(ResultActions call) throws Exception {
        call.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private static void forbidden(ResultActions call) throws Exception {
        call.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    private UUID cash(UUID plot) throws Exception {
        return firstPayment(pay(plot, "CASH", "50.00", null));
    }

    @Test
    void theTreasurerTheChairmanAndACustomRoleWithPaymentsViewPrintAnyReceipt() throws Exception {
        UUID payment = cash(plot("1", "Садова"));
        String paymentsView = customRole("PAYMENTS_VIEW");

        ok(receipt(treasurer, payment));
        ok(receipt(chairman, payment));
        ok(receipt(paymentsView, payment));
    }

    @Test
    void aMemberPrintsTheReceiptOfTheirOwnPlotOnly() throws Exception {
        UUID own = plot("2", "Садова");
        own(memberId, own);
        UUID foreign = plot("3", "Садова");
        UUID ownPayment = cash(own);
        UUID foreignPayment = cash(foreign);
        UUID foreignBank = firstPayment(pay(foreign, "BANK", "10.00", null));

        ok(receipt(member, ownPayment));
        notFound(receipt(member, foreignPayment));
        notFound(receipt(member, foreignBank)); // not 409: nothing about another plot leaks
        notFound(receipt(member, UUID.randomUUID()));
    }

    @Test
    void aMemberWhoOwnsOnlyOnePlotOfASplitGroupIsAnsweredNotFound() throws Exception {
        UUID seven = plot("7", "Вишнева");
        UUID fifteen = plot("15", "Вишнева");
        own(memberId, seven);
        own(memberId, fifteen);
        JsonNode saved = paySplit("100.00", seven, "70.00", fifteen, "30.00");
        UUID payment = firstPayment(saved);
        ok(receipt(member, payment));
        // the plot 15 changes hands: the member now owns plot 7 only
        owner().update("update ownership set valid_to = current_date - 1 where plot_id = ? and member_id = ?", fifteen,
                memberId);

        notFound(receipt(member, payment));
        ok(receipt(treasurer, payment));
    }

    @Test
    void aReversalRowAndAnUnknownIdAreNotFound() throws Exception {
        UUID payment = cash(plot("4", "Садова"));
        reverse(payment);
        UUID reversal = owner().queryForObject("select id from payment where reverses_payment_id = ?", UUID.class,
                payment);

        notFound(receipt(treasurer, reversal));
        notFound(receipt(treasurer, UUID.randomUUID()));
        ok(receipt(treasurer, payment));
    }

    @Test
    void theElectricianWithoutPaymentsViewOrAPlotIsForbidden() throws Exception {
        UUID payment = cash(plot("5", "Садова"));
        String electrician = TestTokens.bearer(DEMO_TENANT, "sub-electrician@demo.local", "electrician@demo.local");
        String withoutPlot = customRole("METERS_VIEW", "READINGS_ENTER");

        forbidden(receipt(electrician, payment));
        forbidden(receipt(withoutPlot, payment));
        forbidden(receipt(electrician, UUID.randomUUID())); // the same answer whatever the id
    }

    @Test
    void anotherCooperativeFindsNothingEvenWithEveryPermission() throws Exception {
        UUID payment = cash(plot("6", "Садова"));
        String email = "documents-other-" + UUID.randomUUID() + "@example.com";
        UUID otherChairman = newMember(OTHER_TENANT, email);
        giveBaseRole(OTHER_TENANT, otherChairman, "CHAIRMAN");

        notFound(receipt(TestTokens.bearer(OTHER_TENANT, UUID.randomUUID().toString(), email), payment));
    }

    @Test
    void thePlatformOperatorIsForbiddenAndNoTokenIsUnauthenticated() throws Exception {
        UUID payment = cash(plot("8", "Садова"));
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        forbidden(receipt(operator, payment));
        receipt(null, payment).andExpect(status().isUnauthorized());
    }
}
