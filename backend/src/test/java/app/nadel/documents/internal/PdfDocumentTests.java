package app.nadel.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.documents.internal.pdf.PdfDocument;
import app.nadel.documents.internal.pdf.PdfDocument.PageSize;
import app.nadel.documents.internal.pdf.PdfFonts;
import app.nadel.documents.internal.pdf.PdfFonts.Face;
import app.nadel.documents.internal.pdf.Table;
import app.nadel.documents.internal.pdf.Table.Column;
import app.nadel.documents.internal.pdf.Table.Row;
import app.nadel.documents.internal.pdf.TextBlock;
import app.nadel.documents.internal.pdf.TextBlock.Align;
import app.nadel.money.Money;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * The PDF layer of the documents (feature 016, ADR 0017): the same calls give the same bytes (fixed {@code /ID} from
 * the content and fixed dates), the A4 and A5 page sizes, a glyph in the embedded fonts for every character of the
 * document bundle, text that wraps and tables that go on on a new page with their header, user content without a
 * glyph replaced, and the receipt page itself rendered twice to the same bytes.
 */
class PdfDocumentTests {

    private static final Instant TIME = Instant.parse("2026-11-05T09:30:00Z");
    private static final byte[] IDENTITY = "content of the document".getBytes(StandardCharsets.UTF_8);

    /** A page with every element of the layer. */
    private static byte[] sample(PageSize size, byte[] identity, Instant time) {
        try (PdfDocument pdf = PdfDocument.create(size, identity, time)) {
            pdf.text(TextBlock.strong("Садівниче товариство «Ялинка»", 10, Align.LEFT));
            pdf.text(TextBlock.plain("Код ЄДРПОУ: 12345678", 8));
            pdf.rule();
            pdf.text(TextBlock.strong("Квитанція № 42", 13, Align.CENTER));
            pdf.mark("СТОРНОВАНО 06.11.2026", 13);
            pdf.table(new Table(List.of(new Column("Ділянка", 3, Align.LEFT), new Column("Сума", 2, Align.RIGHT)),
                    List.of(Row.of("№ 7", "70,00 ₴"), Row.total("Разом", "70,00 ₴")), 9));
            pdf.space(6);
            pdf.signature("Прийняв(ла): Коваленко Олена Петрівна", "(підпис)", 9);
            return pdf.toBytes();
        }
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document).replace(' ', ' ');
        }
    }

    private static int count(String text, String part) {
        Matcher matcher = Pattern.compile(Pattern.quote(part)).matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    // ---- byte stability

    @Test
    void theSameCallsGiveTheSameBytes() {
        byte[] first = sample(PageSize.A5, IDENTITY, TIME);
        byte[] second = sample(PageSize.A5, IDENTITY, TIME);

        assertThat(first).startsWith("%PDF-".getBytes(StandardCharsets.US_ASCII));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void theFileIdentifierIsTheHashOfTheContentAndTheDatesTheGivenTime() throws Exception {
        byte[] expected = Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(IDENTITY), 16);

        try (PDDocument document = Loader.loadPDF(sample(PageSize.A4, IDENTITY, TIME))) {
            COSArray id = document.getDocument().getDocumentID();
            assertThat(id.size()).isEqualTo(2);
            assertThat(((COSString) id.get(0)).getBytes()).isEqualTo(expected);
            assertThat(((COSString) id.get(1)).getBytes()).isEqualTo(expected);
            assertThat(document.getDocumentInformation().getCreationDate().toInstant()).isEqualTo(TIME);
            assertThat(document.getDocumentInformation().getModificationDate().toInstant()).isEqualTo(TIME);
        }
        // another content or another time gives another file
        assertThat(sample(PageSize.A4, "other".getBytes(StandardCharsets.UTF_8), TIME))
                .isNotEqualTo(sample(PageSize.A4, IDENTITY, TIME));
        assertThat(sample(PageSize.A4, IDENTITY, TIME.plusSeconds(1)))
                .isNotEqualTo(sample(PageSize.A4, IDENTITY, TIME));
    }

    // ---- pages

    @Test
    void pagesAreA4OrA5() throws Exception {
        try (PDDocument a4 = Loader.loadPDF(sample(PageSize.A4, IDENTITY, TIME));
                PDDocument a5 = Loader.loadPDF(sample(PageSize.A5, IDENTITY, TIME))) {
            assertThat(a4.getPage(0).getMediaBox().getWidth()).isEqualTo(PDRectangle.A4.getWidth());
            assertThat(a4.getPage(0).getMediaBox().getHeight()).isEqualTo(PDRectangle.A4.getHeight());
            assertThat(a5.getPage(0).getMediaBox().getWidth()).isEqualTo(PDRectangle.A5.getWidth());
            assertThat(a5.getPage(0).getMediaBox().getHeight()).isEqualTo(PDRectangle.A5.getHeight());
        }
        // 210 x 297 mm and 148 x 210 mm
        assertThat(PageSize.A4.width()).isCloseTo(PdfDocument.mm(210), org.assertj.core.data.Offset.offset(0.5f));
        assertThat(PageSize.A5.height()).isCloseTo(PdfDocument.mm(210), org.assertj.core.data.Offset.offset(0.5f));
    }

    @Test
    void theTextIsReadBackInUkrainian() throws Exception {
        String text = text(sample(PageSize.A5, IDENTITY, TIME));

        assertThat(text).contains("Садівниче товариство «Ялинка»", "Код ЄДРПОУ: 12345678", "Квитанція № 42",
                "СТОРНОВАНО 06.11.2026", "№ 7", "70,00 ₴", "Разом", "Прийняв(ла): Коваленко Олена Петрівна",
                "(підпис)");
    }

    // ---- fonts

    @Test
    void everyCharacterOfTheBundleHasAGlyph() {
        TreeSet<String> missing = new TreeSet<>();
        DocumentTexts texts = DocumentTexts.uk();
        for (String key : texts.keys()) {
            texts.text(key).codePoints().filter(codePoint -> !Character.isWhitespace(codePoint))
                    .filter(codePoint -> !PdfFonts.covers(codePoint))
                    .forEach(codePoint -> missing.add(key + ": U+" + Integer.toHexString(codePoint)));
        }

        assertThat(missing).isEmpty();
        // the hryvnia sign, the quotes, the apostrophe, the number sign, the dash and every Ukrainian letter
        "₴«»’'№–—… ".codePoints().forEach(codePoint -> assertThat(PdfFonts.covers(codePoint))
                .as("U+%04X", codePoint).isTrue());
        "АБВГҐДЕЄЖЗИІЇЙКЛМНОПРСТУФХЦЧШЩЬЮЯабвгґдеєжзиіїйклмнопрстуфхцчшщьюя".codePoints()
                .forEach(codePoint -> assertThat(PdfFonts.covers(codePoint)).as("U+%04X", codePoint).isTrue());
    }

    @Test
    void userContentWithoutAGlyphIsReplacedAndLineBreaksBecomeSpaces() throws Exception {
        assertThat(PdfFonts.printable("Іван\nІванович\tОк")).isEqualTo("Іван Іванович Ок");
        assertThat(PdfFonts.printable("сад 🌳 ялинка")).isEqualTo("сад ? ялинка");
        assertThat(PdfFonts.printable("a​b\u0007c")).isEqualTo("abc");
        assertThat(PdfFonts.printable("1 234")).isEqualTo("1 234");
        assertThat(PdfFonts.covers(0x1F333)).isFalse();

        byte[] pdf;
        try (PdfDocument document = PdfDocument.create(PageSize.A5, IDENTITY, TIME)) {
            document.text(TextBlock.plain("Примітка: сад 🌳\nялинка", 9));
            pdf = document.toBytes();
        }
        assertThat(text(pdf)).contains("Примітка: сад ? ялинка");
    }

    // ---- wrapping and overflow

    @Test
    void longTextWrapsAndALongWordIsBrokenBetweenCharacters() throws Exception {
        String sentence = "Садівниче товариство з дуже довгою офіційною назвою ".repeat(8).trim();
        String word = "Ш".repeat(300);
        byte[] pdf;
        try (PdfDocument document = PdfDocument.create(PageSize.A5, IDENTITY, TIME)) {
            document.text(TextBlock.plain(sentence, 9));
            document.text(new TextBlock(word, Face.SEMIBOLD, 9, Align.RIGHT, 0));
            document.text(TextBlock.plain("", 9));
            pdf = document.toBytes();
        }

        String text = text(pdf);
        assertThat(text.lines().filter(line -> line.contains("Садівниче")).count()).isGreaterThan(3);
        assertThat(text.replaceAll("\\s", "")).contains(word);
        assertThat(text.lines().mapToInt(String::length).max().orElseThrow()).isLessThan(300);
    }

    @Test
    void aTableGoesOnOnANewPageWithItsHeader() throws Exception {
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            rows.add(Row.of("Ділянка " + i, "Членський внесок, жовтень 2026 — довгий опис нарахування, що "
                    + "переноситься", DocumentFormat.money(Money.parse(i + ".00"))));
        }
        byte[] pdf;
        int pages;
        try (PdfDocument document = PdfDocument.create(PageSize.A5, IDENTITY, TIME)) {
            document.table(new Table(List.of(new Column("Номер", 2, Align.LEFT), new Column("Опис", 5, Align.LEFT),
                    new Column("Сума", 2, Align.RIGHT)), rows, 9));
            for (int i = 0; i < 40; i++) {
                document.space(30);
            }
            document.mark("СТОРНОВАНО 06.11.2026", 13);
            for (int i = 0; i < 40; i++) {
                document.text(TextBlock.plain("Рядок " + i, 9));
            }
            document.signature("Прийняв(ла): Коваленко Олена Петрівна з дуже довгим ім'ям, що не вміщується в рядок",
                    "(підпис)", 9);
            pages = document.pages();
            pdf = document.toBytes();
        }

        String text = text(pdf);
        assertThat(pages).isGreaterThan(3);
        int headers = count(text, "Номер");
        assertThat(headers).isGreaterThan(1);
        for (int i = 1; i <= 120; i++) {
            assertThat(text).contains("Ділянка " + i + " ");
        }
        assertThat(text).contains("СТОРНОВАНО 06.11.2026", "Рядок 39", "Прийняв(ла): Коваленко", "…");
    }

    @Test
    void invalidBlocksAndTablesAreRefused() {
        assertThatThrownBy(() -> new TextBlock("x", Face.REGULAR, 0, Align.LEFT, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextBlock("x", Face.REGULAR, 9, Align.LEFT, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Column("x", 0, Align.LEFT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Table(List.of(), List.of(), 9)).isInstanceOf(IllegalArgumentException.class);
        Column one = new Column("x", 1, Align.LEFT);
        assertThatThrownBy(() -> new Table(List.of(one), List.of(Row.of("a", "b")), 9))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Table(List.of(one), List.of(), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- the receipt page

    private static ReceiptContent receipt(String payer, String acceptedBy) {
        return new ReceiptContent(
                new ReceiptContent.Requisites("Садівниче товариство «Документи»", "12345678",
                        "с. Тестове, вул. Садова, 1", "UA850000000000000000000123456", "АТ «Тестбанк»"),
                42, LocalDate.of(2026, 11, 5), payer, "Приніс син",
                List.of(new ReceiptContent.Part("7", Money.parse("70.00"), null),
                        new ReceiptContent.Part("15", Money.parse("30.00"), LocalDate.of(2026, 11, 6))),
                List.of(new ReceiptContent.PlacementLine("7", "Членський внесок, жовтень 2026", Money.parse("60.00")),
                        new ReceiptContent.PlacementLine("7", "Не розподілено (передоплата)", Money.parse("10.00")),
                        new ReceiptContent.PlacementLine("15", "Сторновано 06.11.2026", null)),
                Money.parse("100.00"), LocalDate.of(2026, 11, 7), acceptedBy, null);
    }

    @Test
    void theReceiptRenderedTwiceIsTheSameBytes() {
        ReceiptContent content = receipt("Шевченко Тарас Григорович", "Коваленко Олена Петрівна");

        byte[] first = ReceiptLayout.render(content, TIME);

        assertThat(ReceiptLayout.render(content, TIME)).isEqualTo(first);
        assertThat(ReceiptLayout.render(receipt("Шевченко Тарас", "Коваленко Олена Петрівна"), TIME))
                .isNotEqualTo(first);
    }

    @Test
    void theReceiptOnAnA5PageWithoutPayerAuthorOrOptionalRequisites() throws Exception {
        ReceiptContent content = new ReceiptContent(new ReceiptContent.Requisites("СТ «Ялинка»", null, null, null,
                null), 7, LocalDate.of(2026, 11, 5), null, null,
                List.of(new ReceiptContent.Part("9", Money.parse("0.05"), null)),
                List.of(new ReceiptContent.PlacementLine("9", "Не розподілено (передоплата)", Money.parse("0.05"))),
                Money.parse("0.05"), LocalDate.of(2026, 11, 5), null, null);

        byte[] pdf = ReceiptLayout.render(content, TIME);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(document.getPage(0).getMediaBox().getWidth()).isEqualTo(PDRectangle.A5.getWidth());
        }
        String text = text(pdf);
        assertThat(text).contains("СТ «Ялинка»", "Квитанція № 7", "Платник: не вказано",
                "Сума прописом: нуль гривень 05 копійок", "Прийняв(ла):", "Розподіл станом на 05.11.2026");
        assertThat(text).doesNotContain("Код ЄДРПОУ", "IBAN", "Адреса", "Банк", "Примітка платника", "СТОРНОВАНО");
        assertThat(DocumentTexts.uk().text("receipt.payer", Map.of())).isEqualTo("Платник: {name}");
    }
}
