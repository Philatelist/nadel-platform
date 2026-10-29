package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The payment rows of the month on an invoice (feature 016 §2.3, «Оплати за {місяць}»), end to end from the ledger
 * ({@code LedgerReadModel#invoiceLedgers}): every payment row booked in the month and created by the invoice, a
 * reversal as its own negative row with the receipt number of the payment it reverses, each with its day and cash
 * receipt number; stored in the snapshot and printed under the charges, the month's figures unchanged.
 */
class InvoicePaymentsTests extends DocumentsTestSupport {

    @Test
    void thePaymentRowsOfTheMonthAreStoredAndPrintedWithTheirReceiptNumbers() throws Exception {
        UUID plot = plot("9", "Садова");
        own(memberId, plot);
        String month = currentMonth();
        charge(plot, "MEMBERSHIP", "75.00", month);
        charge(plot, "ELECTRICITY", "338.26", month);
        JsonNode kept = pay(plot, "CASH", "100.00", "Оплата за жовтень");
        JsonNode reversed = pay(plot, "CASH", "50.00", null);
        reverse(firstPayment(reversed));
        long keptReceipt = kept.get("payments").get(0).get("receiptNumber").asLong();
        long reversedReceipt = reversed.get("payments").get(0).get("receiptNumber").asLong();

        JsonNode issued = issued(plot, month);

        JsonNode stored = json.readTree(owner().queryForObject(
                "select cast(snapshot as text) from invoice where id = ?", String.class, idOf(issued)));
        JsonNode payments = stored.get("payments");
        assertThat(payments).hasSize(3);
        assertThat(payments.get(0).get("amount").asString()).isEqualTo("100.00");
        assertThat(payments.get(0).get("receiptNumber").asString()).isEqualTo(Long.toString(keptReceipt));
        assertThat(payments.get(0).has("reverses")).isFalse();
        assertThat(payments.get(1).get("amount").asString()).isEqualTo("50.00");
        assertThat(payments.get(2).get("amount").asString()).isEqualTo("-50.00");
        assertThat(payments.get(2).get("reverses").asString()).isEqualTo(firstPayment(reversed).toString());
        assertThat(payments.get(2).get("receiptNumber").asString()).isEqualTo(Long.toString(reversedReceipt));
        assertThat(stored.toString()).doesNotContain("Оплата за жовтень");
        assertThat(issued.get("due").asString()).isEqualTo("313.26");

        byte[] pdf = invoicePdf(treasurer, idOf(issued)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray();
        String text = text(pdf).replace(' ', ' ').replaceAll("\\s+", " ");
        String day = today().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        assertThat(text).contains("Оплати за " + monthName(YearMonth.parse(month)),
                day + " квитанція № " + keptReceipt + " 100,00 ₴",
                day + " квитанція № " + reversedReceipt + " 50,00 ₴",
                day + " сторнування платежу (квитанція № " + reversedReceipt + ") -50,00 ₴")
                .doesNotContain("Оплата за жовтень", "qr.bank.gov.ua");
    }

    @Test
    void anInvoiceWithoutPaymentsInItsMonthStoresNoPaymentRows() throws Exception {
        UUID plot = plot("9", "Садова");
        own(memberId, plot);
        String month = currentMonth();
        charge(plot, "MEMBERSHIP", "75.00", month);

        JsonNode issued = issued(plot, month);

        JsonNode stored = json.readTree(owner().queryForObject(
                "select cast(snapshot as text) from invoice where id = ?", String.class, idOf(issued)));
        assertThat(stored.has("payments")).isFalse();
    }

    /** «жовтень 2026». */
    private static String monthName(YearMonth month) {
        String[] names = {"січень", "лютий", "березень", "квітень", "травень", "червень", "липень", "серпень",
            "вересень", "жовтень", "листопад", "грудень"};
        return names[month.getMonthValue() - 1] + " " + month.getYear();
    }
}
