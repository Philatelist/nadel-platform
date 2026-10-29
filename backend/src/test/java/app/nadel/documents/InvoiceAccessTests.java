package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Who may do what with invoices (feature 016 §2.9, §2.10, technical considerations 2.8; CLAUDE.md "Security and data"),
 * per endpoint: {@code POST /plots/{id}/invoices} needs {@code INVOICES_ISSUE} (the chairman and the treasurer; a
 * custom role with {@code CHARGES_VIEW} only, a member and the electrician are 403); {@code GET /invoices} and
 * {@code GET /invoices/readiness} need {@code CHARGES_VIEW}; {@code GET /invoices/{id}}, {@code /pdf} and
 * {@code /qr.png} need {@code CHARGES_VIEW}, or the caller owns the plot now and the version is the latest one (an
 * older version, another plot's invoice and an unknown id are the same 404); a caller with neither the permission nor
 * a plot is 403; another cooperative finds nothing (404, an empty list); the platform operator is 403; no token 401.
 * The month: {@code POST /billing-months/{month}/invoices} (preview and build) and
 * {@code GET /billing-months/{month}/invoices/print.pdf} need {@code INVOICES_ISSUE} ({@code CHARGES_VIEW} alone, a
 * member, the electrician and the operator are 403); another cooperative's chairman builds and prints only their own
 * cooperative's invoices.
 */
class InvoiceAccessTests extends DocumentsTestSupport {

    private UUID own;
    private UUID foreign;
    private UUID oldVersion;
    private UUID latest;
    private UUID foreignInvoice;

    @BeforeEach
    void invoices() throws Exception {
        own = plot("9", "Садова");
        own(memberId, own);
        foreign = plot("10", "Садова");
        own(lastPersonOf("MEMBER", "Іваненко", "Ігор", "Олегович"), foreign);
        charge(own, "MEMBERSHIP", "75.00", currentMonth());
        charge(foreign, "MEMBERSHIP", "75.00", currentMonth());
        oldVersion = idOf(issued(own, null));
        pay(own, "CASH", "10.00", null); // a change, so the next request is a new version
        latest = idOf(issuedNewVersion(own, null));
        foreignInvoice = idOf(issued(foreign, null));
    }

    @AfterEach
    void unlinkDemoUsers() {
        // a first sign-in links a demo member to its subject; other tests expect them unlinked
        unlinkByEmail(DEMO_TENANT, "electrician@demo.local");
    }

    private UUID lastPersonOf(String role, String last, String first, String middle) {
        person(role, last, first, middle);
        return lastPerson();
    }

    private static void ok(ResultActions call, String contentType) throws Exception {
        call.andExpect(status().isOk());
        assertThat(call.andReturn().getResponse().getContentType()).startsWith(contentType);
    }

    private static void notFound(ResultActions call) throws Exception {
        call.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private static void forbidden(ResultActions call) throws Exception {
        call.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    private ResultActions list(String bearer) throws Exception {
        return send(bearer, get(INVOICES + "?plotId=" + own), null);
    }

    private ResultActions readiness(String bearer) throws Exception {
        return send(bearer, get(INVOICES + "/readiness"), null);
    }

    private ResultActions preview(String bearer) throws Exception {
        return buildMonth(bearer, currentMonth(), true, null);
    }

    private ResultActions build(String bearer) throws Exception {
        return buildMonth(bearer, currentMonth(), false, null);
    }

    private ResultActions print(String bearer) throws Exception {
        return printMonth(bearer, currentMonth(), "ALL");
    }

    /** The month build (preview and build) and the print set are allowed. */
    private void everyMonthAction(String bearer) throws Exception {
        preview(bearer).andExpect(status().isOk()).andExpect(jsonPath("$.invoiceable").value(2));
        build(bearer).andExpect(status().isOk()).andExpect(jsonPath("$.invoiceable").value(2));
        ok(print(bearer), "application/pdf");
    }

    /** The month build (preview and build) and the print set are refused, and nothing is written. */
    private void noMonthAction(String bearer) throws Exception {
        int before = invoiceCount();
        forbidden(preview(bearer));
        forbidden(build(bearer));
        forbidden(print(bearer));
        assertThat(invoiceCount()).isEqualTo(before);
    }

    private void everyRead(String bearer) throws Exception {
        ok(invoice(bearer, oldVersion), "application/json");
        ok(invoice(bearer, latest), "application/json");
        ok(invoicePdf(bearer, oldVersion), "application/pdf");
        ok(invoiceQr(bearer, latest), "image/png");
        ok(invoice(bearer, foreignInvoice), "application/json");
        list(bearer).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].plotId").value(own.toString()));
        readiness(bearer).andExpect(status().isOk());
    }

    @Test
    void theTreasurerAndTheChairmanIssueAndReadEveryInvoice() throws Exception {
        for (String bearer : new String[] {treasurer, chairman}) {
            // nothing changed since the latest version: 200 with that version
            issue(bearer, own, null).andExpect(status().isOk());
            everyRead(bearer);
            everyMonthAction(bearer);
        }
    }

    @Test
    void aCustomRoleWithChargesViewReadsButCannotIssue() throws Exception {
        String viewer = customRole("CHARGES_VIEW");

        everyRead(viewer);
        forbidden(issue(viewer, own, null));
        noMonthAction(viewer);
        String issuer = customRole("INVOICES_ISSUE");
        issue(issuer, own, null).andExpect(status().isOk());
        everyMonthAction(issuer);
    }

    @Test
    void aMemberReadsOnlyTheLatestVersionOfTheirOwnPlot() throws Exception {
        ok(invoice(member, latest), "application/json");
        ok(invoicePdf(member, latest), "application/pdf");
        ok(invoiceQr(member, latest), "image/png");

        notFound(invoice(member, oldVersion));
        notFound(invoicePdf(member, oldVersion));
        notFound(invoiceQr(member, oldVersion));
        notFound(invoice(member, foreignInvoice));
        notFound(invoicePdf(member, foreignInvoice));
        notFound(invoiceQr(member, foreignInvoice));
        notFound(invoice(member, UUID.randomUUID()));

        forbidden(list(member));
        forbidden(readiness(member));
        forbidden(issue(member, own, null));
        noMonthAction(member);
    }

    @Test
    void aMemberWhoSoldThePlotNoLongerSeesItsInvoice() throws Exception {
        owner().update("update ownership set valid_to = current_date - 1 where plot_id = ? and member_id = ?", own,
                memberId);
        UUID other = plot("11", "Садова");
        own(memberId, other);

        notFound(invoice(member, latest));
    }

    @Test
    void theElectricianWithoutChargesViewOrAPlotIsForbidden() throws Exception {
        String electrician = TestTokens.bearer(DEMO_TENANT, "sub-electrician@demo.local", "electrician@demo.local");
        String withoutPlot = customRole("METERS_VIEW", "READINGS_ENTER");

        for (String bearer : new String[] {electrician, withoutPlot}) {
            forbidden(invoice(bearer, latest));
            forbidden(invoicePdf(bearer, latest));
            forbidden(invoiceQr(bearer, latest));
            forbidden(invoice(bearer, UUID.randomUUID())); // the same answer whatever the id
            forbidden(list(bearer));
            forbidden(readiness(bearer));
            forbidden(issue(bearer, own, null));
            noMonthAction(bearer);
        }
    }

    @Test
    void anotherCooperativeFindsNothingEvenWithEveryPermission() throws Exception {
        String email = "documents-other-" + UUID.randomUUID() + "@example.com";
        UUID otherChairman = newMember(OTHER_TENANT, email);
        giveBaseRole(OTHER_TENANT, otherChairman, "CHAIRMAN");
        String other = TestTokens.bearer(OTHER_TENANT, UUID.randomUUID().toString(), email);

        notFound(invoice(other, latest));
        notFound(invoicePdf(other, latest));
        notFound(invoiceQr(other, latest));
        list(other).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
        issue(other, own, null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        // the other cooperative's own month: a preview writes nothing, and its print set has none of this one's
        int before = invoiceCount();
        int previewStatus = preview(other).andReturn().getResponse().getStatus();
        assertThat(previewStatus).isIn(200, 404, 409);
        var printed = print(other).andReturn().getResponse();
        if (printed.getStatus() == 200) {
            assertThat(text(printed.getContentAsByteArray())).doesNotContain("Документи");
        }
        assertThat(invoiceCount()).isEqualTo(before);
        assertThat(owner().queryForObject("select count(*) from invoice where tenant_id = ?", Integer.class,
                OTHER_TENANT)).isZero();
    }

    @Test
    void thePlatformOperatorIsForbiddenAndNoTokenIsUnauthenticated() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        forbidden(invoice(operator, latest));
        forbidden(invoicePdf(operator, latest));
        forbidden(invoiceQr(operator, latest));
        forbidden(list(operator));
        forbidden(readiness(operator));
        forbidden(issue(operator, own, null));
        noMonthAction(operator);
        invoice(null, latest).andExpect(status().isUnauthorized());
        preview(null).andExpect(status().isUnauthorized());
        build(null).andExpect(status().isUnauthorized());
        print(null).andExpect(status().isUnauthorized());
        invoicePdf(null, latest).andExpect(status().isUnauthorized());
        list(null).andExpect(status().isUnauthorized());
        issue(null, own, null).andExpect(status().isUnauthorized());
    }
}
