package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

/**
 * What a member sees of the documents (feature 016 §2.9, technical considerations 2.8): {@code GET /me/invoices} lists
 * the latest version of each month's invoice of the plots they own now, newest month first, then the plots in natural
 * order; the PDF of a listed invoice downloads; an older version and another plot's invoice are the same 404
 * {@code NOT_FOUND} as an unknown id; the certificates are refused (403); the receipt of a cash payment of their own
 * plot downloads and another plot's is 404. A member without a plot gets an empty list, the platform operator 403, no
 * token 401.
 */
class MemberInvoicesTests extends DocumentsTestSupport {

    private static final String MY_INVOICES = "/api/v1/me/invoices";
    private static final String CERTIFICATES = "/api/v1/certificates";
    private static final String PLOT_CERTIFICATES = "/api/v1/plots/%s/certificates";

    private UUID nine;
    private UUID ten;
    private UUID foreign;

    private UUID nineLastMonthV1;
    private UUID nineLastMonthV2;
    private UUID tenLastMonth;
    private UUID nineThisMonth;
    private UUID foreignInvoice;

    @BeforeEach
    void invoices() throws Exception {
        // created in this order on purpose: the natural order of the numbers is not the order of creation
        ten = plot("10", "Садова");
        nine = plot("9", "Садова");
        own(memberId, ten);
        own(memberId, nine);
        foreign = plot("7", "Садова");
        own(memberNamed("Іваненко", "Ігор", "Олегович"), foreign);
        for (UUID plot : new UUID[] {nine, ten, foreign}) {
            charge(plot, "MEMBERSHIP", "75.00", previousMonth());
            charge(plot, "MEMBERSHIP", "75.00", currentMonth());
        }
        nineLastMonthV1 = idOf(issued(nine, previousMonth()));
        pay(nine, "CASH", "10.00", null); // received after the month: version 2 of last month's invoice
        nineLastMonthV2 = idOf(issuedNewVersion(nine, previousMonth()));
        tenLastMonth = idOf(issued(ten, previousMonth()));
        nineThisMonth = idOf(issued(nine, currentMonth()));
        foreignInvoice = idOf(issued(foreign, currentMonth()));
    }

    private ResultActions mine(String bearer) throws Exception {
        return send(bearer, get(MY_INVOICES), null);
    }

    private static void notFound(ResultActions call) throws Exception {
        call.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private static void forbidden(ResultActions call) throws Exception {
        call.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    private static void isPdf(ResultActions call) throws Exception {
        MockHttpServletResponse response = call.andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getContentType()).startsWith("application/pdf");
        byte[] content = response.getContentAsByteArray();
        assertThat(content.length).isGreaterThan(1000);
        assertThat(new String(content, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void theMemberListsTheLatestVersionOfTheirOwnPlotsNewestMonthFirst() throws Exception {
        mine(member)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].id").value(nineThisMonth.toString()))
                .andExpect(jsonPath("$[0].month").value(currentMonth()))
                .andExpect(jsonPath("$[0].plotNumber").value("9"))
                .andExpect(jsonPath("$[0].number").value(currentMonth() + "/9"))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].due").value("140.00"))
                .andExpect(jsonPath("$[0].latest").value(true))
                .andExpect(jsonPath("$[1].id").value(nineLastMonthV2.toString()))
                .andExpect(jsonPath("$[1].month").value(previousMonth()))
                .andExpect(jsonPath("$[1].version").value(2))
                .andExpect(jsonPath("$[1].latest").value(true))
                .andExpect(jsonPath("$[2].id").value(tenLastMonth.toString()))
                .andExpect(jsonPath("$[2].plotNumber").value("10"))
                .andExpect(jsonPath("$[?(@.id == '" + nineLastMonthV1 + "')]").isEmpty())
                .andExpect(jsonPath("$[?(@.id == '" + foreignInvoice + "')]").isEmpty());
    }

    @Test
    void theMemberDownloadsTheListedInvoiceButNotAnOlderVersionOrAnotherPlots() throws Exception {
        isPdf(invoicePdf(member, nineThisMonth));
        isPdf(invoicePdf(member, nineLastMonthV2));

        notFound(invoicePdf(member, nineLastMonthV1));
        notFound(invoice(member, nineLastMonthV1));
        notFound(invoicePdf(member, foreignInvoice));
        notFound(invoice(member, foreignInvoice));
        // the same answer as for an invoice that does not exist
        notFound(invoicePdf(member, UUID.randomUUID()));
    }

    @Test
    void aPlotTheMemberSoldIsNoLongerListed() throws Exception {
        owner().update("update ownership set valid_to = current_date - 1 where plot_id = ? and member_id = ?", ten,
                memberId);

        mine(member)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.plotId == '" + ten + "')]").isEmpty());
        notFound(invoicePdf(member, tenLastMonth));
    }

    @Test
    void theMemberIsRefusedTheCertificates() throws Exception {
        forbidden(send(member, get(CERTIFICATES), null));
        forbidden(send(member, post(PLOT_CERTIFICATES.formatted(nine)), Map.of()));
        assertThat(owner().queryForObject("select count(*) from certificate where tenant_id = ?", Integer.class,
                tenant)).isZero();
    }

    @Test
    void theMemberDownloadsTheReceiptOfTheirOwnCashPaymentButNotAnotherPlots() throws Exception {
        UUID own = firstPayment(pay(nine, "CASH", "75.00", null));
        UUID other = firstPayment(pay(foreign, "CASH", "75.00", null));

        isPdf(receipt(member, own));
        notFound(receipt(member, other));
        notFound(receipt(member, UUID.randomUUID()));
    }

    @Test
    void aCallerWithoutAPlotGetsAnEmptyListAndTheBoardSeesOnlyTheirOwn() throws Exception {
        String withoutPlot = customRole("METERS_VIEW");
        mine(withoutPlot).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        // the treasurer owns no plot: the list is about one's own plots, whatever the permissions
        mine(treasurer).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void anotherCooperativesMemberSeesNoneOfThem() throws Exception {
        String email = "documents-other-" + UUID.randomUUID() + "@example.com";
        newMember(OTHER_TENANT, email);
        String other = TestTokens.bearer(OTHER_TENANT, UUID.randomUUID().toString(), email);

        mine(other).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void thePlatformOperatorIsForbiddenAndNoTokenIsUnauthenticated() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        forbidden(mine(operator));
        mine(null).andExpect(status().isUnauthorized());
    }
}
