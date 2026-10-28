package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.PlotDebts;
import app.nadel.tenancy.TenantContext;
import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * An invoice of one plot on request ({@code POST /plots/{id}/invoices}, feature 016 §2.4, §2.6, technical
 * considerations 2.5): every request is a new version with the same number; a payment changes the next version and
 * never the earlier one; the booking-month rule end to end (worked example 5: a correction booked after the closing
 * lands in the next month, the closed month's invoice keeps its numbers, and before any later correction its end debt
 * is the plot's debt as of the month's last day); the requisites check (example 6); the plots that get no invoice; the
 * audit entry {@code INVOICE_REISSUED}.
 */
class InvoiceOnRequestTests extends DocumentsTestSupport {

    @Autowired
    LedgerReadModel ledger;

    @Autowired
    TransactionTemplate tx;

    private UUID ownedPlot(String number) {
        UUID plot = plot(number, "Садова");
        own(memberId, plot);
        return plot;
    }

    private JsonNode snapshot(UUID invoice) throws Exception {
        return body(invoice(treasurer, invoice).andExpect(status().isOk()));
    }

    private static String totals(JsonNode snapshot, String field) {
        return snapshot.get("totals").get(field).asString();
    }

    @Test
    void everyRequestIsANewVersionAndAPaymentChangesOnlyTheNextOne() throws Exception {
        UUID plot = ownedPlot("9");
        String month = currentMonth();
        charge(plot, "MEMBERSHIP", "75.00", month);
        charge(plot, "ELECTRICITY", "338.26", month);

        JsonNode first = issued(plot, null);
        assertThat(first.get("number").asString()).isEqualTo(month + "/9");
        assertThat(first.get("version").asInt()).isEqualTo(1);
        assertThat(first.get("due").asString()).isEqualTo("413.26");
        assertThat(first.get("month").asString()).isEqualTo(month);

        pay(plot, "CASH", "100.00", null);
        JsonNode second = issued(plot, month);
        assertThat(second.get("number").asString()).isEqualTo(month + "/9");
        assertThat(second.get("version").asInt()).isEqualTo(2);
        assertThat(second.get("due").asString()).isEqualTo("313.26");

        // version 1 keeps its numbers
        JsonNode v1 = snapshot(idOf(first));
        assertThat(totals(v1, "due")).isEqualTo("413.26");
        assertThat(v1.get("version").asInt()).isEqualTo(1);
        JsonNode v2 = snapshot(idOf(second));
        assertThat(totals(v2, "due")).isEqualTo("313.26");
        assertThat(v2.get("types").get(0).get("paid").asString()).isEqualTo("75.00");

        // a third request without a change is still a new version
        assertThat(issued(plot, month).get("version").asInt()).isEqualTo(3);

        send(treasurer, get(INVOICES + "?plotId=" + plot), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[0].version").value(3))
                .andExpect(jsonPath("$.items[0].latest").value(true))
                .andExpect(jsonPath("$.items[2].version").value(1))
                .andExpect(jsonPath("$.items[2].latest").value(false));
    }

    @Test
    void example5TheClosedMonthKeepsItsNumbersAndTheCorrectionShowsInTheNextMonth() throws Exception {
        UUID plot = ownedPlot("9");
        String october = previousMonth();
        String november = currentMonth();
        charge(plot, "MEMBERSHIP", "75.00", october);
        UUID electricity = charge(plot, "ELECTRICITY", "338.26", october);
        closeMonth(october);

        JsonNode first = snapshot(idOf(issued(plot, october)));
        assertThat(first.get("monthStatus").asString()).isEqualTo("CLOSED");
        assertThat(totals(first, "endDebt")).isEqualTo("413.26");
        // before any later correction the end debt of a closed month is the plot's debt as of its last day
        PlotDebts debts = TenantContext.runAs(tenant, () -> tx.execute(status -> ledger.debtsAsOf(
                YearMonth.parse(october).atEndOfMonth(), Set.of(), List.of(plot)).get(plot)));
        assertThat(debts.total().toString()).isEqualTo(totals(first, "endDebt"));

        adjustment(electricity, "-20.00", november);

        JsonNode again = snapshot(idOf(issued(plot, october)));
        assertThat(again.get("version").asInt()).isEqualTo(2);
        assertThat(again.get("types")).isEqualTo(first.get("types"));
        assertThat(again.get("lines")).isEqualTo(first.get("lines"));
        assertThat(again.get("totals")).isEqualTo(first.get("totals"));

        UUID nextId = idOf(issued(plot, november));
        JsonNode next = snapshot(nextId);
        JsonNode line = next.get("lines").get(0);
        assertThat(line.get("kind").asString()).isEqualTo("ADJUSTMENT");
        assertThat(line.get("amount").asString()).isEqualTo("-20.00");
        assertThat(line.get("corrects").asString()).isEqualTo(october);
        assertThat(next.get("types").get(0).get("code").asString()).isEqualTo("MEMBERSHIP");
        assertThat(next.get("types").get(0).get("start").asString()).isEqualTo("75.00");
        JsonNode electricityRow = next.get("types").get(1);
        assertThat(electricityRow.get("code").asString()).isEqualTo("ELECTRICITY");
        assertThat(electricityRow.get("start").asString()).isEqualTo("338.26");
        assertThat(electricityRow.get("charged").asString()).isEqualTo("-20.00");
        assertThat(electricityRow.get("end").asString()).isEqualTo("318.26");
        byte[] pdf = invoicePdf(treasurer, nextId).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray();
        assertThat(text(pdf).replace('\u00A0', ' ')).contains("Коригування за " + monthName(october));
    }

    @Test
    void aPaymentReceivedAfterTheMonthLowersTheDueAndRemovesTheQrCode() throws Exception {
        UUID plot = ownedPlot("9");
        String october = previousMonth();
        charge(plot, "MEMBERSHIP", "75.00", october);
        charge(plot, "ELECTRICITY", "338.26", october);
        pay(plot, "CASH", "413.26", null); // received today, booked in the current month

        JsonNode invoice = snapshot(idOf(issued(plot, october)));

        assertThat(totals(invoice, "endDebt")).isEqualTo("413.26");
        assertThat(totals(invoice, "receivedAfter")).isEqualTo("413.26");
        assertThat(totals(invoice, "due")).isEqualTo("0.00");
        assertThat(invoice.has("qr")).isFalse();
        assertThat(invoice.get("receivedAfter")).hasSize(1);
        invoiceQr(treasurer, idOf(invoice)).andExpect(status().isNotFound());
    }

    @Test
    void example6WithoutTheIbanNothingIsBuiltAndTheProblemNamesIt() throws Exception {
        UUID plot = ownedPlot("9");
        owner().update("update cooperative_settings set iban = null where tenant_id = ?", tenant);

        issue(treasurer, plot, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUISITES_MISSING"))
                .andExpect(jsonPath("$.missing[0]").value("IBAN"))
                .andExpect(jsonPath("$.missing.length()").value(1));
        send(treasurer, get(INVOICES + "/readiness"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.missing[0]").value("IBAN"));

        owner().update("update tenant set edrpou = null where id = ?", tenant);
        issue(treasurer, plot, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.missing[0]").value("IBAN"))
                .andExpect(jsonPath("$.missing[1]").value("EDRPOU"));
        assertThat(owner().queryForObject("select count(*) from invoice where tenant_id = ?", Integer.class, tenant))
                .isZero();
    }

    @Test
    void readinessIsEmptyWithTheIbanAndTheEdrpou() throws Exception {
        send(chairman, get(INVOICES + "/readiness"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.missing.length()").value(0));
    }

    @Test
    void commonLandAnArchivedPlotAndAPlotWithoutAnOwnerGetNoInvoice() throws Exception {
        UUID ownerless = plot("10", "Садова");
        UUID archived = ownedPlot("11");
        owner().update("update plot set archived_at = now() where id = ?", archived);
        UUID common = plot("12", "Садова");
        owner().update("update plot set kind = 'COMMON' where id = ?", common);

        for (UUID plot : List.of(ownerless, archived, common)) {
            issue(treasurer, plot, null).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PLOT_NOT_INVOICEABLE"));
        }
        issue(treasurer, UUID.randomUUID(), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
    }

    @Test
    void aMonthAfterTheCurrentOneIsRefused() throws Exception {
        UUID plot = ownedPlot("9");

        issue(treasurer, plot, YearMonth.parse(currentMonth()).plusMonths(1).toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("month"));
        issue(treasurer, plot, "2026-13").andExpect(status().isBadRequest());
    }

    @Test
    void theReissueIsAuditedWithTheNumberTheVersionAndTheDue() throws Exception {
        UUID plot = ownedPlot("9");
        charge(plot, "MEMBERSHIP", "75.00", currentMonth());
        issued(plot, null);

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> owner().queryForObject(
                "select count(*) from audit_entry where tenant_id = ? and action = 'INVOICE_REISSUED'", Integer.class,
                tenant) == 1);
        String changes = owner().queryForObject("select changes::text from audit_entry where tenant_id = ? "
                + "and action = 'INVOICE_REISSUED'", String.class, tenant);
        assertThat(owner().queryForObject("select entity_type || '|' || entity_id || '|' || entity_label "
                + "from audit_entry where tenant_id = ? and action = 'INVOICE_REISSUED'", String.class, tenant))
                .isEqualTo("INVOICE|" + plot + "|9");
        assertThat(changes).contains("\"number\"", currentMonth() + "/9", "\"version\"", "\"due\"", "75.00")
                .doesNotContain("Шевченко");
    }

    /** «за жовтень 2026»: the month a correction is for, as the PDF writes it (the nominative month name). */
    private static String monthName(String october) {
        String[] names = {"січень", "лютий", "березень", "квітень", "травень", "червень", "липень", "серпень",
            "вересень", "жовтень", "листопад", "грудень"};
        YearMonth month = YearMonth.parse(october);
        return names[month.getMonthValue() - 1] + " " + month.getYear();
    }
}
