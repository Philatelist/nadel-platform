package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * The print set of a month ({@code GET /billing-months/{month}/invoices/print.pdf}, feature 016 §2.5): one A4 page per
 * latest invoice of the month, in street order (the Ukrainian alphabet, plots without a street last) and the natural
 * order of the plot numbers; {@code PAPER} (the default) keeps the plots with at least one current owner who chose
 * paper delivery, {@code ALL} every plot; only the latest version of each plot; the same invoices give the same bytes;
 * a month without such invoices is one page saying so; a month that is not listed is 404.
 */
class InvoicePrintTests extends DocumentsTestSupport {

    private UUID vyshneva2;
    private UUID sadova9;
    private UUID sadova10;
    private UUID noStreet1;

    /**
     * Four plots: Вишнева 2 shared by an e-mail owner and a paper owner, Садова 9 of a paper owner, Садова 10 of the
     * e-mail owner only, and plot 1 without a street of the paper owner; each charged in the current month.
     */
    @BeforeEach
    void plots() {
        UUID paper = memberId; // the default channel is PAPER
        UUID email = memberNamed("Іваненко", "Ігор", "Олегович");
        deliveryChannel(email, "EMAIL");
        String month = currentMonth();
        sadova10 = plot("10", "Садова");
        own(email, sadova10);
        sadova9 = plot("9", "Садова");
        own(paper, sadova9);
        noStreet1 = plot("1", null);
        own(paper, noStreet1);
        vyshneva2 = plot("2", "Вишнева");
        for (UUID owner : List.of(email, paper)) {
            owner().update("insert into ownership (id, tenant_id, plot_id, member_id, share_numerator, "
                    + "share_denominator, valid_from) values (?, ?, ?, ?, 1, 2, date '2020-01-01')",
                    UUID.randomUUID(), tenant, vyshneva2, owner);
        }
        for (UUID plot : List.of(sadova10, sadova9, noStreet1, vyshneva2)) {
            charge(plot, "MEMBERSHIP", "75.00", month);
        }
    }

    private byte[] print(String scope) throws Exception {
        var call = printMonth(treasurer, currentMonth(), scope).andExpect(status().isOk());
        var response = call.andReturn().getResponse();
        assertThat(response.getContentType()).startsWith("application/pdf");
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("invoices_" + currentMonth() + "_"
                + (scope == null ? "paper" : scope.toLowerCase()) + ".pdf");
        return response.getContentAsByteArray();
    }

    /** The text of each page. */
    private static List<String> pages(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            List<String> pages = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(stripper.getText(document).replace(' ', ' '));
            }
            return pages;
        }
    }

    private static String plotLine(String number, String street) {
        return "ділянка № " + number + ", " + street;
    }

    /** Whether the page is the invoice of the plot without a street: «ділянка № N» with nothing after the number. */
    private static boolean plotWithoutStreet(String page, String number) {
        return Pattern.compile("ділянка № " + Pattern.quote(number) + "(?![0-9A-Za-zА-Яа-яІіЇїЄєҐґ,])")
                .matcher(page).find();
    }

    @Test
    void allPrintsEveryLatestInvoiceOnePerPageInStreetAndPlotOrder() throws Exception {
        built(currentMonth(), false);

        List<String> pages = pages(print("ALL"));

        assertThat(pages).hasSize(4);
        assertThat(pages.get(0)).contains(plotLine("2", "Вишнева"));
        assertThat(pages.get(1)).contains(plotLine("9", "Садова"));
        assertThat(pages.get(2)).contains(plotLine("10", "Садова"));
        assertThat(plotWithoutStreet(pages.get(3), "1")).isTrue();
        assertThat(pages.get(3)).doesNotContain(plotLine("1", "Садова"));
        assertThat(pages).allSatisfy(page -> assertThat(page).contains("Рахунок № " + currentMonth() + "/"));
    }

    @Test
    void paperKeepsThePlotsWithAnyCurrentOwnerWhoChosePaperAndIsTheDefault() throws Exception {
        built(currentMonth(), false);

        List<String> paper = pages(print("PAPER"));
        List<String> byDefault = pages(print(null));

        assertThat(paper).hasSize(3);
        assertThat(paper.get(0)).contains(plotLine("2", "Вишнева")); // shared: one owner chose paper
        assertThat(paper.get(1)).contains(plotLine("9", "Садова"));
        assertThat(plotWithoutStreet(paper.get(2), "1")).isTrue();
        assertThat(String.join("\n", paper)).doesNotContain(plotLine("10", "Садова"));
        assertThat(byDefault).isEqualTo(paper);
    }

    @Test
    void onlyTheLatestVersionOfEachPlotIsPrinted() throws Exception {
        built(currentMonth(), false);
        issued(sadova9, currentMonth()); // version 2 on request

        List<String> pages = pages(print("ALL"));

        assertThat(pages).hasSize(4);
        assertThat(pages.get(1)).contains(plotLine("9", "Садова"), "(версія 2)");
        assertThat(String.join("\n", pages).split("ділянка № 9,", -1)).hasSize(2);
    }

    @Test
    void theSameInvoicesGiveTheSameBytes() throws Exception {
        built(currentMonth(), false);

        assertThat(print("ALL")).isEqualTo(print("ALL"));
        assertThat(print("PAPER")).isEqualTo(print("PAPER"));
    }

    @Test
    void aMonthWithoutInvoicesIsOnePageSayingSo() throws Exception {
        List<String> all = pages(print("ALL"));
        assertThat(all).hasSize(1);
        assertThat(all.getFirst()).contains("немає рахунків для друку");

        built(currentMonth(), false);
        owner().update("update member set delivery_channel = 'EMAIL' where tenant_id = ?", tenant);
        List<String> paper = pages(print("PAPER"));
        assertThat(paper).hasSize(1);
        assertThat(paper.getFirst()).contains("немає рахунків для друку", "паперову доставку");
    }

    @Test
    void aMonthThatIsNotListedIsNotFound() throws Exception {
        String month = currentMonth();
        printMonth(treasurer, YearMonth.parse(month).plusMonths(1).toString(), "ALL")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        printMonth(treasurer, YearMonth.parse(month).minusMonths(1).toString(), "ALL")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        printMonth(treasurer, month, "SOME").andExpect(status().isBadRequest());
    }
}
