package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The printed cash receipt end to end ({@code GET /payments/{id}/receipt.pdf}, feature 016 §2.8): a single cash
 * payment with its number, the amount in figures and words, the charge it was placed on and who accepted it; a payment
 * split over plots 7 and 15 as one receipt with both parts; a reversed payment with «СТОРНОВАНО» and the day; a bank
 * payment refused with 409; the payer note only for a viewer who may view members. The texts are read back from the
 * PDF with {@code PDFTextStripper}, which also proves the embedded fonts map back to Unicode.
 */
class CashReceiptTests extends DocumentsTestSupport {

    private static final String MONTH = YearMonth.now(ZoneId.of("Europe/Kyiv")).minusMonths(1).toString();

    private byte[] pdf(String bearer, UUID payment) throws Exception {
        return receipt(bearer, payment).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    /** The text of the PDF with no-break spaces as plain ones. */
    private String receiptText(String bearer, UUID payment) throws Exception {
        return text(pdf(bearer, payment)).replace(' ', ' ');
    }

    @Test
    void aCashPaymentOf100PrintsTheNumberTheWordsTheChargeAndTheTreasurer() throws Exception {
        UUID plot = plot("9", "Садова");
        own(memberId, plot);
        charge(plot, "MEMBERSHIP", "100.00", MONTH);
        JsonNode saved = pay(plot, "CASH", "100.00", null);
        UUID payment = firstPayment(saved);
        long number = saved.get("payments").get(0).get("receiptNumber").asLong();

        byte[] bytes = receipt(treasurer, payment).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"receipt_" + number
                        + ".pdf\""))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String text = text(bytes).replace(' ', ' ');

        assertThat(text).contains("Садівниче товариство «Документи»", "Код ЄДРПОУ: 12345678",
                "IBAN: UA850000000000000000000123456", "Квитанція № " + number,
                "Дата: " + DocumentsTestSupport.today().format(java.time.format.DateTimeFormatter
                        .ofPattern("dd.MM.yyyy")),
                "Платник: " + MEMBER_NAME, "№ 9", "100,00 ₴", "Разом",
                "Сума прописом: сто гривень 00 копійок", "Розподіл станом на",
                "Членський внесок, " + monthName(MONTH), "Прийняв(ла): " + TREASURER_NAME, "(підпис)");
        assertThat(text).doesNotContain("СТОРНОВАНО", "Не розподілено");
    }

    @Test
    void aPaymentSplitOverPlots7And15IsOneReceiptWithBothParts() throws Exception {
        UUID seven = plot("7", "Вишнева");
        UUID fifteen = plot("15", "Вишнева");
        own(memberId, seven);
        own(memberId, fifteen);
        charge(seven, "MEMBERSHIP", "60.00", MONTH);
        JsonNode saved = paySplit("100.00", seven, "70.00", fifteen, "30.00");
        UUID fromSecondPart = UUID.fromString(saved.get("payments").get(1).get("id").asString());

        String text = receiptText(treasurer, fromSecondPart);

        assertThat(text).contains("№ 7", "70,00 ₴", "№ 15", "30,00 ₴", "100,00 ₴",
                "сто гривень 00 копійок", "Членський внесок, " + monthName(MONTH), "60,00 ₴",
                "Не розподілено (передоплата)", "10,00 ₴");
        assertThat(text.indexOf("№ 7")).isLessThan(text.indexOf("№ 15")); // street, then plot order
        // any part of the group gives the same receipt
        assertThat(receiptText(treasurer, firstPayment(saved))).isEqualTo(text);
    }

    @Test
    void aReversedPaymentCarriesTheMarkAndTheDay() throws Exception {
        UUID plot = plot("3", "Садова");
        charge(plot, "MEMBERSHIP", "40.00", MONTH);
        UUID payment = firstPayment(pay(plot, "CASH", "40.00", null));
        reverse(payment);

        String text = receiptText(treasurer, payment);

        String day = DocumentsTestSupport.today().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        assertThat(text).contains("СТОРНОВАНО " + day, "Сторновано " + day, "40,00 ₴",
                "сорок гривень 00 копійок");
        assertThat(text).doesNotContain("Членський внесок");
    }

    @Test
    void aBankPaymentHasNoReceipt() throws Exception {
        UUID plot = plot("4", "Садова");
        UUID payment = firstPayment(pay(plot, "BANK", "25.00", null));

        receipt(treasurer, payment).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_NOT_AVAILABLE"));
    }

    @Test
    void thePayerNoteIsPrintedOnlyForAViewerWhoMayViewMembers() throws Exception {
        UUID plot = plot("5", "Садова");
        UUID payment = firstPayment(pay(plot, "CASH", "15.00", "Приніс син"));
        String paymentsOnly = customRole("PAYMENTS_VIEW");

        assertThat(receiptText(treasurer, payment)).contains("Примітка платника: «Приніс син»");
        String withoutMembers = receiptText(paymentsOnly, payment);
        assertThat(withoutMembers).contains("Платник: " + MEMBER_NAME, "15,00 ₴").doesNotContain("Приніс син");
    }

    private static String monthName(String month) {
        String[] names = {"січень", "лютий", "березень", "квітень", "травень", "червень", "липень", "серпень",
            "вересень", "жовтень", "листопад", "грудень"};
        YearMonth ym = YearMonth.parse(month);
        return names[ym.getMonthValue() - 1] + " " + ym.getYear();
    }
}
