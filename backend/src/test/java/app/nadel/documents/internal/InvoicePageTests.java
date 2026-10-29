package app.nadel.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.documents.internal.pdf.PdfDocument;
import app.nadel.documents.internal.pdf.PdfDocument.PageSize;
import app.nadel.ledger.InvoiceLedger;
import app.nadel.money.Money;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * The page of an invoice with the longest demo invoices (feature 016 §2.3, §2.5): each one is one A4 page in the
 * print set, the footer on it; an invoice too long for a page goes on on a page whose header repeats its number and
 * plot, the totals, the QR block and the footer together; the QR code's link is a link annotation and never printed;
 * a metered charge shows one range; the payment rows of the month are listed with their receipt number or bank
 * reference; a snapshot stored before the payments were kept still opens.
 */
class InvoicePageTests {

    private static final Instant TIME = Instant.parse("2026-10-29T09:58:20Z");

    /** The print set of the invoices, drawn as {@link PrintBundle} draws it. */
    private static byte[] print(List<InvoiceDocument> invoices) {
        DocumentTexts texts = DocumentTexts.uk();
        try (PdfDocument pdf = PdfDocument.create(PageSize.A4, "print-test".getBytes(StandardCharsets.UTF_8),
                TIME)) {
            boolean first = true;
            for (InvoiceDocument invoice : invoices) {
                PrintBundle.append(pdf, invoice, texts, first);
                first = false;
            }
            return pdf.toBytes();
        }
    }

    /** The text of each page, no-break spaces as spaces and the lines of a wrapped cell joined again. */
    private static List<String> pages(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            List<String> pages = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(stripper.getText(document).replace(' ', ' ').replaceAll("\\s+", " "));
            }
            return pages;
        }
    }

    /** Saves the PDF for a look when {@code -Dnadel.pdf.out=<dir>} is set. */
    private static void keep(String name, byte[] pdf) throws IOException {
        String dir = System.getProperty("nadel.pdf.out");
        if (dir != null) {
            Files.write(Path.of(dir, name), pdf);
        }
    }

    @Test
    void theLongestDemoInvoicesAreOnePageEachInThePrintSetWithTheirFooter() throws Exception {
        byte[] pdf = print(List.of(DemoInvoices.plot15(), DemoInvoices.plot2(), DemoInvoices.plot15()));
        keep("print-demo.pdf", pdf);

        List<String> pages = pages(pdf);

        assertThat(pages).hasSize(3);
        assertThat(pages.get(0)).contains("Рахунок № 2026-10/15", "ділянка № 15, вул. Вишнева");
        assertThat(pages.get(1)).contains("Рахунок № 2026-10/2", "ділянка № 2, вул. Садова");
        assertThat(pages.get(2)).contains("Рахунок № 2026-10/15");
        assertThat(pages).allSatisfy(page -> assertThat(page).contains("До сплати", "Оплатити в застосунку банку",
                "Казначей: Коваленко Олена Петрівна", "Сформовано в системі Наділ 29.10.2026 11:58")
                .doesNotContain("(продовження)"));
    }

    @Test
    void anInvoiceTooLongForAPageGoesOnOnAPageThatRepeatsItsNumberAndPlot() throws Exception {
        InvoiceDocument invoice = DemoInvoices.repeated(DemoInvoices.plot15(), 12);
        byte[] pdf = print(List.of(invoice, DemoInvoices.plot2()));
        keep("print-long.pdf", pdf);

        List<String> pages = pages(pdf);

        assertThat(pages).hasSizeGreaterThanOrEqualTo(3);
        List<String> long15 = pages.subList(0, pages.size() - 1);
        assertThat(long15.getFirst()).contains("Рахунок № 2026-10/15", "Платник: ділянка № 15")
                .doesNotContain("(продовження)", "Сформовано в системі Наділ");
        for (String next : long15.subList(1, long15.size())) {
            assertThat(next.strip()).startsWith("Рахунок № 2026-10/15, ділянка № 15, вул. Вишнева (продовження)");
        }
        // the totals, the QR block and the footer are on the last page of the invoice, together
        assertThat(long15.getLast()).contains("До сплати", "Відскануйте QR-код у застосунку свого банку",
                "Оплатити в застосунку банку", "Сплачуйте за реквізитами", "Казначей:",
                "Сформовано в системі Наділ");
        assertThat(String.join(" ", long15.subList(0, long15.size() - 1))).doesNotContain("До сплати",
                "Відскануйте", "Казначей:");
        // the next invoice starts on its own page, without the header of the one before
        assertThat(pages.getLast()).contains("Рахунок № 2026-10/2").doesNotContain("(продовження)");
    }

    @Test
    void theQrLinkIsALinkAnnotationAndIsNotPrinted() throws Exception {
        InvoiceDocument invoice = DemoInvoices.plot15();
        byte[] pdf = InvoiceLayout.render(invoice);

        assertThat(pages(pdf)).singleElement().satisfies(page -> assertThat(page)
                .contains("Оплатити в застосунку банку").doesNotContain("qr.bank.gov.ua", "QkNE"));
        try (PDDocument document = Loader.loadPDF(pdf)) {
            List<String> uris = new ArrayList<>();
            for (PDAnnotation annotation : document.getPage(0).getAnnotations()) {
                if (annotation instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI uri) {
                    uris.add(uri.getURI());
                }
            }
            assertThat(uris).containsExactly(invoice.qr().link());
        }
    }

    @Test
    void nothingDueMeansNoLinkAnnotation() throws Exception {
        InvoiceDocument invoice = InvoicePdfTests.invoice(1, app.nadel.ledger.MonthStatus.CLOSED, "0.00", "100.00");
        try (PDDocument document = Loader.loadPDF(InvoiceLayout.render(invoice))) {
            assertThat(document.getPage(0).getAnnotations()).isEmpty();
        }
    }

    @Test
    void aMeteredChargeShowsOneRangeTheReadingDates() throws Exception {
        String page = pages(InvoiceLayout.render(DemoInvoices.plot15())).getFirst();

        assertThat(page).contains(
                "показники 10.08.2026 → 03.10.2026: 3 435,900 → 3 705,500 = 269,600 кВт·год × 4,32 ₴",
                "показники 03.10.2026 → 18.10.2026: 3 705,500 → 3 780,000 = 74,500 кВт·год × 4,32 ₴",
                "спільний споживач «Свердловина №1», 04.10.2026 → 18.10.2026: 14,114 кВт·год × 4,32 ₴",
                "жовтень 2026; 5,00 ₴ × 6,00 сотки")
                .doesNotContain("11.08.2026", "(10.08.2026");
        String plot2 = pages(InvoiceLayout.render(DemoInvoices.plot2())).getFirst();
        assertThat(plot2).contains("втрати в мережі, 04.10.2026 → 18.10.2026: 6,810 кВт·год × 4,32 ₴",
                "Сторнування; жовтень 2026", "Коригування за вересень 2026; вересень 2026");
    }

    @Test
    void fixedPercentageLossesWithTheirIntervalShowTheReadingDates() {
        InvoiceLedger.Line losses = new InvoiceLedger.Line(UUID.randomUUID(), UUID.randomUUID(),
                InvoiceLedger.LineKind.REGULAR, InvoiceLedger.LineSource.LOSSES, Money.parse("16.92"),
                LocalDate.of(2026, 9, 6), LocalDate.of(2026, 10, 5), null,
                List.of(new InvoiceLedger.Part("PER_KWH", "MONTHLY", "16.915", "KWH", "1.0000", 30,
                        LocalDate.of(2026, 9, 6), LocalDate.of(2026, 10, 5))),
                List.of(new InvoiceLedger.Reading(UUID.randomUUID(), LocalDate.of(2026, 9, 5), "8205.000"),
                        new InvoiceLedger.Reading(UUID.randomUUID(), LocalDate.of(2026, 10, 5), "8543.300")),
                "5.00", "338.300", null);

        assertThat(InvoiceLayout.describe(losses, DocumentTexts.uk()).replace(' ', ' ')).isEqualTo(
                "показники 05.09.2026 → 05.10.2026: втрати 5,00 % від 338,300 кВт·год = 16,915 кВт·год × 1,00 ₴");
    }

    @Test
    void thePaymentsOfTheMonthAreListedWithTheirReceiptOrBankReference() throws Exception {
        String page = pages(InvoiceLayout.render(DemoInvoices.plot15())).getFirst();

        assertThat(page).contains("Оплати за жовтень 2026", "17.10.2026 квитанція № 7 1 000,00 ₴");
        DocumentTexts texts = DocumentTexts.uk();
        UUID paid = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 10, 18);
        assertThat(InvoiceLayout.reference(new InvoiceLedger.MonthPayment(paid, null, day, Money.parse("10.00"), 8L,
                null), texts)).isEqualTo("квитанція № 8");
        assertThat(InvoiceLayout.reference(new InvoiceLedger.MonthPayment(paid, null, day, Money.parse("10.00"), null,
                "ПД-2026-0411"), texts)).isEqualTo("банківський платіж, референс ПД-2026-0411");
        assertThat(InvoiceLayout.reference(new InvoiceLedger.MonthPayment(paid, null, day, Money.parse("10.00"), null,
                null), texts)).isEmpty();
        assertThat(InvoiceLayout.reference(new InvoiceLedger.MonthPayment(UUID.randomUUID(), paid, day,
                Money.parse("-10.00"), 8L, null), texts)).isEqualTo("сторнування платежу (квитанція № 8)");
        assertThat(InvoiceLayout.reference(new InvoiceLedger.MonthPayment(UUID.randomUUID(), paid, day,
                Money.parse("-10.00"), null, null), texts)).isEqualTo("сторнування платежу");
    }

    @Test
    void aPaymentAndItsReversalAreTwoRowsTheReversalNegative() throws Exception {
        InvoiceDocument base = DemoInvoices.plot15();
        UUID paid = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 10, 18);
        InvoiceDocument invoice = new InvoiceDocument(base.id(), base.version(), base.number(), base.month(),
                base.monthStatus(), base.asOf(), base.issuedAt(), base.issuedOn(), base.cooperative(), base.plot(),
                base.owners(), base.chairmen(), base.treasurers(), base.types(), base.lines(),
                List.of(new InvoiceLedger.MonthPayment(paid, null, day, Money.parse("1000.00"), null, "ПД-2026-0411"),
                        new InvoiceLedger.MonthPayment(UUID.randomUUID(), paid, day, Money.parse("-1000.00"), null,
                                "ПД-2026-0411")),
                base.receivedAfter(), base.totals(), base.qr());

        String page = pages(InvoiceLayout.render(invoice)).getFirst();

        assertThat(page).contains("18.10.2026 банківський платіж, референс ПД-2026-0411 1 000,00 ₴",
                "18.10.2026 сторнування платежу (банківський платіж, референс ПД-2026-0411) -1 000,00 ₴");
    }

    @Test
    void aSnapshotStoredBeforeThePaymentsWereKeptStillOpens() throws Exception {
        Map<String, Object> body = DemoInvoices.body("invoice-2026-10-15.json");
        body.remove("payments");

        InvoiceDocument old = InvoiceSnapshots.read(DemoInvoices.PLOT_15, body, DemoInvoices.persons(
                "aaaaaaaa-0000-0000-0000-000000000003", "Мельниченко-Задорожна Оксана Володимирівна"));

        assertThat(old.payments()).isEmpty();
        assertThat(InvoiceSnapshots.body(old)).doesNotContainKey("payments");
        assertThat(pages(InvoiceLayout.render(old))).singleElement()
                .satisfies(page -> assertThat(page).contains("До сплати").doesNotContain("Оплати за"));
        // the payments read back as stored
        InvoiceDocument current = DemoInvoices.plot15();
        assertThat(current.payments()).singleElement().satisfies(payment -> {
            assertThat(payment.receiptNumber()).isEqualTo(7L);
            assertThat(payment.amount()).isEqualTo(Money.parse("1000.00"));
            assertThat(payment.reverses()).isNull();
        });
        assertThat(InvoiceSnapshots.read(current.id(), InvoiceSnapshots.body(current), DemoInvoices.persons(
                "aaaaaaaa-0000-0000-0000-000000000003", "Мельниченко-Задорожна Оксана Володимирівна")))
                .isEqualTo(current);
    }
}
