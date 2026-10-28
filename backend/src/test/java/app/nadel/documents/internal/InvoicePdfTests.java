package app.nadel.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

import app.nadel.documents.internal.nbu.NbuQrPayload;
import app.nadel.ledger.InvoiceLedger;
import app.nadel.ledger.InvoiceLedger.LineKind;
import app.nadel.ledger.InvoiceLedger.LineSource;
import app.nadel.ledger.MonthStatus;
import app.nadel.money.Money;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * The PDF of an invoice (feature 016 §2.1, §2.3, §2.4): the Ukrainian texts are readable (names, «До сплати»,
 * «Ділянка»), «(версія N)» from the second version on, «Станом на» for an open month, the details of the charges, the
 * QR block only when something is due, the QR code decoded from the page rendered at 300 dpi back to the stored link,
 * and the same version always the same bytes.
 */
class InvoicePdfTests {

    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final UUID ELECTRICITY = UUID.randomUUID();
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final Instant ISSUED = Instant.parse("2026-11-05T09:30:00Z");
    private static final String IBAN = "UA850000000000000000000123456";
    private static final String NAME = "Садівниче товариство «Документи»";

    private static Money m(String amount) {
        return Money.parse(amount);
    }

    private static List<InvoiceLedger.Line> lines() {
        InvoiceLedger.Line membership = new InvoiceLedger.Line(UUID.randomUUID(), MEMBERSHIP, LineKind.REGULAR,
                LineSource.TARIFF, m("25.00"), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null,
                List.of(new InvoiceLedger.Part("PER_AREA_SOTKA", "YEARLY", "6.00", "SOTKA", "50.0000", 31,
                        LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))),
                List.of(), null, null, null);
        InvoiceLedger.Line electricity = new InvoiceLedger.Line(UUID.randomUUID(), ELECTRICITY, LineKind.REGULAR,
                LineSource.CONSUMPTION, m("338.30"), LocalDate.of(2026, 9, 6), LocalDate.of(2026, 10, 5), null,
                List.of(new InvoiceLedger.Part("PER_KWH", "MONTHLY", "338.300", "KWH", "1.0000", 30,
                        LocalDate.of(2026, 9, 6), LocalDate.of(2026, 10, 5))),
                List.of(new InvoiceLedger.Reading(UUID.randomUUID(), LocalDate.of(2026, 9, 5), "8205.000"),
                        new InvoiceLedger.Reading(UUID.randomUUID(), LocalDate.of(2026, 10, 5), "8543.300")),
                null, null, null);
        InvoiceLedger.Line adjustment = new InvoiceLedger.Line(UUID.randomUUID(), MEMBERSHIP, LineKind.ADJUSTMENT,
                LineSource.TARIFF, m("49.96"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                YearMonth.of(2026, 9), List.of(), List.of(), null, null, null);
        return List.of(membership, adjustment, electricity);
    }

    /** An invoice of plot 9 for October 2026 with the given version, month status and due amount. */
    static InvoiceDocument invoice(int version, MonthStatus status, String due, String prepayment) {
        String number = "2026-10/9";
        InvoiceDocument.Qr qr = null;
        if (m(due).signum() > 0) {
            NbuQrPayload payload = NbuQrPayload.of(new NbuQrPayload.Invoice(NAME, IBAN, m(due), "12345678", "9",
                    OCTOBER, number));
            qr = new InvoiceDocument.Qr(payload.link(), payload.purpose());
        }
        return new InvoiceDocument(UUID.fromString("7f2c3b6e-1d0a-4a5b-9c8d-0123456789ab"), version, number, OCTOBER,
                status, ISSUED, ISSUED, LocalDate.of(2026, 11, 5),
                new InvoiceDocument.Cooperative(NAME, "12345678", "с. Тестове, вул. Садова, 1", "АТ «Тестбанк»",
                        IBAN),
                new InvoiceDocument.Plot(UUID.randomUUID(), "9", "Садова"),
                List.of(new InvoiceDocument.Owner(UUID.randomUUID(), "Шевченко Тарас Григорович", 1, 2),
                        new InvoiceDocument.Owner(UUID.randomUUID(), "Шевченко Олена Іванівна", 1, 2)),
                List.of("Бондаренко Іван Іванович"), List.of("Коваленко Олена Петрівна"),
                List.of(new InvoiceDocument.TypeRow(MEMBERSHIP, "MEMBERSHIP", "Членський внесок", m("0.00"),
                                m("74.96"), m("0.00"), m("74.96")),
                        new InvoiceDocument.TypeRow(ELECTRICITY, "ELECTRICITY", "Електроенергія", m("0.00"),
                                m("338.30"), m("0.00"), m("338.30"))),
                lines(), List.of(), new InvoiceDocument.Totals(m("413.26"), m(prepayment), m("0.00"), m(due)), qr);
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            // no-break spaces as spaces, and the lines a long cell wraps into joined again
            return new PDFTextStripper().getText(document).replace('\u00A0', ' ').replaceAll("\\s+", " ");
        }
    }

    private static String decodePage(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            BufferedImage page = new PDFRenderer(document).renderImageWithDPI(0, 300, ImageType.GRAY);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(page)));
            return new QRCodeReader().decode(bitmap, Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE)).getText();
        }
    }

    @Test
    void theUkrainianTextIsReadable() throws Exception {
        String text = text(InvoiceLayout.render(invoice(1, MonthStatus.CLOSED, "413.26", "0.00")));

        assertThat(text).contains(NAME, "Код ЄДРПОУ: 12345678", "IBAN: " + IBAN, "Рахунок № 2026-10/9",
                "Дата видачі: 05.11.2026", "Розрахунковий місяць: жовтень 2026", "ділянка № 9, Садова",
                "Шевченко Тарас Григорович (1/2)", "Шевченко Олена Іванівна (1/2)", "Членський внесок",
                "Електроенергія", "Борг на початок місяця", "Борг на кінець місяця", "До сплати", "413,26 ₴",
                "Відскануйте QR-код у застосунку свого банку", "https://qr.bank.gov.ua/",
                "Призначення платежу: Ділянка 9. Внески за жовтень 2026. Рахунок 2026-10/9",
                "Сплачуйте за реквізитами або QR-кодом; у призначенні вказуйте номер ділянки",
                "Голова: Бондаренко Іван Іванович", "Казначей: Коваленко Олена Петрівна",
                "Сформовано в системі Наділ 05.11.2026 11:30");
        assertThat(text).doesNotContain("?", "(версія", "Станом на", "Аванс");
    }

    @Test
    void theChargesOfTheMonthAreDetailed() throws Exception {
        String text = text(InvoiceLayout.render(invoice(1, MonthStatus.CLOSED, "413.26", "0.00")));

        assertThat(text).contains("Нарахування за жовтень 2026", "50,00 ₴ × 6,00 сотки / 12",
                "показники 8 205,000 – 8 543,300 (05.09.2026 – 05.10.2026)", "338,300 кВт·год × 1,00 ₴",
                "Коригування за вересень 2026");
    }

    @Test
    void aLineIsDescribedFromItsStoredCalculation() {
        DocumentTexts texts = DocumentTexts.uk();
        List<InvoiceLedger.Line> lines = lines();

        assertThat(InvoiceLayout.describe(lines.get(0), texts).replace('\u00A0', ' '))
                .isEqualTo("жовтень 2026; 50,00 ₴ × 6,00 сотки / 12");
        assertThat(InvoiceLayout.describe(lines.get(1), texts))
                .isEqualTo("Коригування за вересень 2026; вересень 2026");
        assertThat(InvoiceLayout.describe(lines.get(2), texts).replace('\u00A0', ' ')).isEqualTo(
                "06.09.2026 – 05.10.2026; показники 8 205,000 – 8 543,300 (05.09.2026 – 05.10.2026); "
                        + "338,300 кВт·год × 1,00 ₴");
        InvoiceLedger.Part plot = new InvoiceLedger.Part("PER_PLOT", "MONTHLY", "1", "PLOT", "75.0000", 16,
                LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 31));
        assertThat(InvoiceLayout.part(plot, texts).replace('\u00A0', ' ')).isEqualTo("75,00 ₴ за ділянку, 16 дн.");
        InvoiceLedger.Part oneOff = new InvoiceLedger.Part("FIXED_ONE_TIME", "ONE_OFF", "1", "PLOT", "500.0000", 1,
                LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 15));
        assertThat(InvoiceLayout.part(oneOff, texts).replace('\u00A0', ' ')).isEqualTo("500,00 ₴ за ділянку");
    }

    private static InvoiceLedger.Line line(LineKind kind, LineSource source, YearMonth corrects,
            List<InvoiceLedger.Reading> readings, String lossesPercent, String consumption, String consumerName) {
        return new InvoiceLedger.Line(UUID.randomUUID(), ELECTRICITY, kind, source, m("12.50"),
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), corrects, List.of(), readings, lossesPercent,
                consumption, consumerName);
    }

    @Test
    void anOpeningBalanceAndAReversalAreNamedWithoutACalculation() {
        DocumentTexts texts = DocumentTexts.uk();

        assertThat(InvoiceLayout.describe(line(LineKind.OPENING, LineSource.OPENING, null, List.of(), null, null,
                null), texts)).isEqualTo("Вхідний залишок");
        assertThat(InvoiceLayout.describe(line(LineKind.REVERSAL, LineSource.TARIFF, null, List.of(), null, null,
                null), texts)).isEqualTo("Сторнування; жовтень 2026");
        assertThat(InvoiceLayout.describe(line(LineKind.REVERSAL, LineSource.LOSSES, YearMonth.of(2026, 9),
                List.of(), "5.00", "338.300", null), texts)).isEqualTo("Сторнування за вересень 2026; жовтень 2026");
        assertThat(InvoiceLayout.describe(line(LineKind.ADJUSTMENT, LineSource.TARIFF, null, List.of(), null, null,
                null), texts)).isEqualTo("Коригування; жовтень 2026");
    }

    @Test
    void lossesAndACommonConsumerShareSayWhatTheyWereTakenOf() {
        DocumentTexts texts = DocumentTexts.uk();

        assertThat(InvoiceLayout.describe(line(LineKind.REGULAR, LineSource.LOSSES, null, List.of(), "5.00",
                "338.300", null), texts).replace(' ', ' '))
                .isEqualTo("жовтень 2026; втрати 5,00 % від 338,300 кВт·год");
        assertThat(InvoiceLayout.describe(line(LineKind.REGULAR, LineSource.LOSSES, null, List.of(), null, null,
                null), texts)).isEqualTo("жовтень 2026; втрати в мережі");
        assertThat(InvoiceLayout.describe(line(LineKind.ADJUSTMENT, LineSource.COMMON_CONSUMER,
                YearMonth.of(2026, 9), List.of(), null, null, "Насосна станція"), texts))
                .isEqualTo("Коригування за вересень 2026; жовтень 2026; спільний споживач «Насосна станція»");
        assertThat(InvoiceLayout.describe(line(LineKind.REGULAR, LineSource.COMMON_CONSUMER, null, List.of(), null,
                null, " "), texts)).isEqualTo("жовтень 2026");
    }

    @Test
    void aConsumptionLineWithoutAnIntervalShowsNoReadings() {
        InvoiceLedger.Line single = line(LineKind.REGULAR, LineSource.CONSUMPTION, null,
                List.of(new InvoiceLedger.Reading(UUID.randomUUID(), LocalDate.of(2026, 10, 5), "8543.300")), null,
                null, null);

        assertThat(InvoiceLayout.describe(single, DocumentTexts.uk())).isEqualTo("жовтень 2026");
    }

    @Test
    void aPartWithoutARateOrAKnownUnitIsLeftOutAndAQuarterlyTariffIsDividedByThree() {
        DocumentTexts texts = DocumentTexts.uk();
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 31);

        assertThat(InvoiceLayout.part(new InvoiceLedger.Part("PER_PLOT", "MONTHLY", "1", "PLOT", null, 31, from, to),
                texts)).isNull();
        assertThat(InvoiceLayout.part(new InvoiceLedger.Part(null, null, "2", "LITRE", "3.0000", 31, from, to),
                texts)).isNull();
        assertThat(InvoiceLayout.part(new InvoiceLedger.Part("PER_AREA_SOTKA", "YEARLY", null, "SOTKA", "50.0000",
                31, from, to), texts)).isNull();
        assertThat(InvoiceLayout.part(new InvoiceLedger.Part("PER_PLOT", "QUARTERLY", "1", "PLOT", "90.0000", 31,
                from, to), texts).replace(' ', ' ')).isEqualTo("90,00 ₴ за ділянку / 3");
        InvoiceLedger.Line withoutRate = new InvoiceLedger.Line(UUID.randomUUID(), MEMBERSHIP, LineKind.REGULAR,
                LineSource.TARIFF, m("10.00"), from, to, null,
                List.of(new InvoiceLedger.Part("PER_PLOT", "MONTHLY", "1", "PLOT", null, 31, from, to)), List.of(),
                null, null, null);
        assertThat(InvoiceLayout.describe(withoutRate, texts)).isEqualTo("жовтень 2026");
    }

    @Test
    void theSecondVersionSaysSoAndAnOpenMonthIsAsOfTheDay() throws Exception {
        String text = text(InvoiceLayout.render(invoice(2, MonthStatus.OPEN, "413.26", "0.00")));

        assertThat(text).contains("Рахунок № 2026-10/9 (версія 2)", "Станом на 05.11.2026");
    }

    @Test
    void theQrCodeOnThePageDecodesToTheStoredLink() throws Exception {
        InvoiceDocument invoice = invoice(1, MonthStatus.CLOSED, "413.26", "0.00");

        assertThat(decodePage(InvoiceLayout.render(invoice))).isEqualTo(invoice.qr().link());
    }

    @Test
    void nothingDueMeansNoQrBlockButThePrepaymentIsShown() throws Exception {
        byte[] pdf = InvoiceLayout.render(invoice(1, MonthStatus.CLOSED, "0.00", "100.00"));

        assertThat(text(pdf)).contains("До сплати", "0,00 ₴", "Аванс", "100,00 ₴")
                .doesNotContain("Відскануйте", "qr.bank.gov.ua");
        assertThatThrownBy(() -> decodePage(pdf)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theSameVersionRendersToTheSameBytes() {
        InvoiceDocument invoice = invoice(1, MonthStatus.CLOSED, "413.26", "0.00");

        assertThat(InvoiceLayout.render(invoice)).isEqualTo(InvoiceLayout.render(invoice));
        assertThat(InvoiceLayout.render(invoice(2, MonthStatus.CLOSED, "413.26", "0.00")))
                .isNotEqualTo(InvoiceLayout.render(invoice));
    }

    @Test
    void theFileIsAnA4Page() throws Exception {
        try (PDDocument document = Loader.loadPDF(InvoiceLayout.render(invoice(1, MonthStatus.CLOSED, "413.26",
                "0.00")))) {
            assertThat(document.getPage(0).getMediaBox().getWidth()).isCloseTo(595.28f, offset(0.5f));
            assertThat(document.getPage(0).getMediaBox().getHeight()).isCloseTo(841.89f, offset(0.5f));
        }
    }
}
