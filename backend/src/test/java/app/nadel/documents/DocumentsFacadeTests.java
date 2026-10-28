package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.problem.ApiProblemException;
import app.nadel.tenancy.TenantContext;
import java.io.IOException;
import java.time.Duration;
import java.time.YearMonth;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The public {@link DocumentsFacade} for feature 017 (technical considerations 2.1): called without a request, under
 * {@code TenantContext.runAs} as a job would, without a transaction of its own; ids, links and bytes; the same
 * documents as the endpoints; the system issues an on-request invoice; another cooperative's documents are not found;
 * nothing works without a bound cooperative.
 */
class DocumentsFacadeTests extends DocumentsTestSupport {

    @Autowired
    DocumentsFacade facade;

    private UUID plot;

    @BeforeEach
    void plotWithACharge() {
        plot = plot("9", "Садова");
        own(memberId, plot);
        charge(plot, "MEMBERSHIP", "75.00", currentMonth());
    }

    private <T> T asJob(java.util.function.Supplier<T> work) {
        return TenantContext.runAs(tenant, work);
    }

    private static int pages(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        }
    }

    @Test
    void theLatestInvoiceOfAPlotAndMonthItsPdfAndItsQrLink() throws Exception {
        String month = currentMonth();
        issued(plot, month);
        UUID latest = idOf(issued(plot, month));

        assertThat(asJob(() -> facade.latestInvoice(plot, YearMonth.parse(month)))).contains(latest);
        assertThat(asJob(() -> facade.latestInvoice(plot, YearMonth.parse(month).minusMonths(1)))).isEmpty();
        assertThat(asJob(() -> facade.latestInvoice(UUID.randomUUID(), YearMonth.parse(month)))).isEmpty();

        byte[] endpoint = invoicePdf(treasurer, latest).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray();
        assertThat(asJob(() -> facade.invoicePdf(latest))).hasValueSatisfying(pdf -> assertThat(pdf)
                .isEqualTo(endpoint));
        assertThat(asJob(() -> facade.invoicePdf(UUID.randomUUID()))).isEmpty();

        JsonNode snapshot = body(invoice(treasurer, latest));
        assertThat(asJob(() -> facade.qrLink(latest))).contains(snapshot.get("qr").get("link").asString());
        assertThat(asJob(() -> facade.qrLink(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void anInvoiceWithNothingDueHasNoQrLink() throws Exception {
        pay(plot, "CASH", "75.00", null);
        UUID invoice = idOf(issued(plot, currentMonth()));

        assertThat(asJob(() -> facade.qrLink(invoice))).isEmpty();
    }

    @Test
    void theSystemIssuesTheInvoiceForTodayAsANewVersion() throws Exception {
        issued(plot, currentMonth());

        UUID today = asJob(() -> facade.invoiceForToday(plot));

        assertThat(owner().queryForObject("select version from invoice where id = ?", Integer.class, today))
                .isEqualTo(2);
        assertThat(owner().queryForObject("select issued_by is null from invoice where id = ?", Boolean.class,
                today)).isTrue();
        await().atMost(Duration.ofSeconds(10)).until(() -> owner().queryForObject(
                "select count(*) from audit_entry where tenant_id = ? and action = 'INVOICE_REISSUED' "
                        + "and actor_member_id is null and actor_subject = 'system:documents'", Integer.class,
                tenant) == 1);
        UUID common = plot("90", "Садова");
        owner().update("update plot set kind = 'COMMON' where id = ?", common);
        assertThatThrownBy(() -> asJob(() -> facade.invoiceForToday(common)))
                .isInstanceOf(ApiProblemException.class)
                .extracting(e -> ((ApiProblemException) e).code()).isEqualTo("PLOT_NOT_INVOICEABLE");
    }

    @Test
    void theReceiptOfACashPaymentWithoutThePayerNoteAndNoneForABankPayment() throws Exception {
        UUID cash = firstPayment(pay(plot, "CASH", "50.00", "Передав сусід"));
        UUID bank = firstPayment(pay(plot, "BANK", "25.00", null));

        byte[] receipt = asJob(() -> facade.receiptPdf(cash)).orElseThrow();
        assertThat(new String(receipt, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(text(receipt)).contains("Квитанція").doesNotContain("Передав сусід");
        assertThat(asJob(() -> facade.receiptPdf(bank))).isEmpty();
        assertThat(asJob(() -> facade.receiptPdf(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void thePrintSetOfTheMonth() throws Exception {
        UUID other = plot("10", "Садова");
        own(memberId, other);
        charge(other, "MEMBERSHIP", "75.00", currentMonth());
        built(currentMonth(), false);

        byte[] all = asJob(() -> facade.printPdf(YearMonth.parse(currentMonth()), PrintScope.ALL));
        assertThat(pages(all)).isEqualTo(2);
        byte[] endpoint = printMonth(treasurer, currentMonth(), "ALL").andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsByteArray();
        assertThat(all).isEqualTo(endpoint);
    }

    @Test
    void anotherCooperativesDocumentsAreNotFoundAndNothingWorksWithoutACooperative() throws Exception {
        UUID invoice = idOf(issued(plot, currentMonth()));
        UUID payment = firstPayment(pay(plot, "CASH", "10.00", null));

        assertThat(TenantContext.runAs(OTHER_TENANT, () -> facade.invoicePdf(invoice))).isEmpty();
        assertThat(TenantContext.runAs(OTHER_TENANT, () -> facade.qrLink(invoice))).isEmpty();
        assertThat(TenantContext.runAs(OTHER_TENANT, () -> facade.receiptPdf(payment))).isEmpty();
        assertThat(TenantContext.runAs(OTHER_TENANT,
                () -> facade.latestInvoice(plot, YearMonth.parse(currentMonth())))).isEmpty();

        assertThatThrownBy(() -> facade.invoicePdf(invoice)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> facade.latestInvoice(plot, YearMonth.parse(currentMonth())))
                .isInstanceOf(IllegalStateException.class);
    }
}
