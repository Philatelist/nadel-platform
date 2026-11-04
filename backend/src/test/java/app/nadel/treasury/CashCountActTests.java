package app.nadel.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The act of a cash count ({@code GET /cash-counts/{id}/act.pdf}, feature 018 §2.6, technical considerations 2.8): the
 * cooperative's name, the date and time of the count in Kyiv, the book balance, the counted balance, the difference
 * with its result and explanation, how it was booked (and reversed), and the signature lines of the cashier
 * (treasurer) and the chairman with their names, or an empty line when the cooperative has nobody in the role. The
 * texts are read back from the PDF with {@code PDFTextStripper}.
 */
class CashCountActTests extends TreasuryApiSupport {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    String treasurer;
    UUID treasurerId;
    UUID cash;
    LocalDate opened;

    @BeforeEach
    void cashBook() {
        treasurer = person(tenantA, "TREASURER");
        treasurerId = lastPerson;
        cash = account(tenantA, "CASH");
        opened = today().minusMonths(1).withDayOfMonth(1);
    }

    private String name(UUID member) {
        return ownerJdbc().queryForObject("select last_name || ' ' || first_name from member where id = ?",
                String.class, member);
    }

    private byte[] pdf(String bearer, UUID count) throws Exception {
        return countAct(bearer, count).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    /** The text of the PDF with no-break spaces as plain ones. */
    private static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document).replace('\u00A0', ' ');
        }
    }

    @Test
    void theActOfAShortageShowsTheBalancesTheDifferenceTheBookingAndBothSigners() throws Exception {
        String chairman = person(tenantA, "CHAIRMAN");
        UUID chairmanId = lastPerson;
        setOpening(chairman, cash, "200.00", opened).andExpect(status().isOk());
        LocalDate tenth = opened.plusDays(9);
        Instant countedAt = kyiv(tenth, 14);
        UUID count = saveCount(treasurer, count(countedAt, "180.00", "Помилка при видачі решти"));

        byte[] bytes = countAct(treasurer, count).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"cash_count_act_" + tenth + ".pdf\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String text = text(bytes);

        assertThat(text).contains("Treasury test cooperative", "Акт перерахунку каси",
                "Дата і час перерахунку: " + tenth.format(DAY) + " 14:30",
                "Залишок готівки за касовою книгою", "200,00 ₴",
                "Фактична наявність готівки (перераховано)", "180,00 ₴",
                "Різниця (фактично мінус за книгою)", "-20,00 ₴",
                "Результат: нестача 20,00 ₴", "Пояснення: Помилка при видачі решти", "Різницю не оформлено.",
                "Касир (скарбник): " + name(treasurerId), "Голова правління: " + name(chairmanId), "(підпис)");

        bookDifference(chairman, count, "Нестача за актом").andExpect(status().isOk());
        String booked = text(pdf(treasurer, count));

        assertThat(booked).contains("Різницю оформлено " + today().format(DAY) + ": нестача 20,00 ₴, датою "
                + tenth.format(DAY), "Причина: Нестача за актом").doesNotContain("Різницю не оформлено.");
    }

    @Test
    void theActOfAMatchingCountSaysItMatchesAndLeavesTheChairmansLineEmptyWithoutAChairman() throws Exception {
        String approver = customRole(tenantA, "CASHBOOK_APPROVE");
        setOpening(approver, cash, "200.00", opened).andExpect(status().isOk());
        UUID count = saveCount(treasurer, count(kyiv(opened.plusDays(3), 9), "200.00", null));

        String text = text(pdf(treasurer, count));

        assertThat(text).contains("Результат: збігається", "Пояснення: не надано", "0,00 ₴",
                "Касир (скарбник): " + name(treasurerId), "Голова правління:")
                .doesNotContain("Різницю не оформлено.", "Різницю оформлено", "Голова правління: Скарбник");
    }
}
