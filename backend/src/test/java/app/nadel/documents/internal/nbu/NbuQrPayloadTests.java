package app.nadel.documents.internal.nbu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.documents.internal.nbu.NbuQrPayload.Encoding;
import app.nadel.documents.internal.nbu.NbuQrPayload.Invoice;
import app.nadel.money.Money;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The payload of the NBU payment QR code, format 003 (NBU Board Resolution No. 97, Annex 4; docs/domain/nbu-qr.md):
 * every element against table 2 with a byte dump, the official example 1 re-encoded to its table 4 text, the shortest
 * amount, the size limits and the shortening order (feature 016 example 8, on a long official name and the UTF-8 path).
 * The expected bytes and links were computed independently of this code from the rules of Annex 4.
 */
class NbuQrPayloadTests {

    private static final String NAME = "Садівниче товариство «Надія»";
    private static final String IBAN = "UA213223130000026007233566001";
    private static final String EDRPOU = "12345678";
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final Charset CP1251 = Charset.forName("windows-1251");

    /** The demo invoice of plot 9 for October 2026 (feature 016 example 1). */
    private static final String DEMO_LINK = "https://qr.bank.gov.ua/QkNECjAwMwoyClhDVAoK0eDks-Lt6PflIPLu4uDw6PHy4u4gq83g5LP_"
            + "uwpVQTIxMzIyMzEzMDAwMDAyNjAwNzIzMzU2NjAwMQpVQUg0MTMuMjYKMTIzNDU2NzgKU1VQUC9TVVBQCjIwMjYtMTAvOQrEs-v_7ergI"
            + "DkuIMLt5fHq6CDn4CDm7uLy5e38IDIwMjYuINDg9fPt7uogMjAyNi0xMC85CgpGRUZGCgoK";

    /** Annex 4 table 4 row 2 (example 1), the line breaks of the printed table removed. */
    private static final String EXAMPLE_1_BASE64 = "QkNECjAwMwoyClVDVAoK0s7CIKvDyqvN4PTy7uPg5yDT6vDgv-3ouwpVQTIwMTIzN"
            + "DU2MDAwMDAwMDI2MDMyMzAxMjA0MiAKVUFIMjk5OC4zOQo0MDEyMTQ1MgpTVVBQL1NVUFAKQUExNTY3OC02NzkKP1RpY2tObz2UWUEx"
            + "MjY3lCZBZGRyPZTi8-vo9v8gy_Pj7uLgLCDh8-QuIDkxMSzM6Oro8ujt9mmUCgpGRUZGCjI1MDkyMTEyMDAwMAoyNTAyMjkxMjAwMDAKU"
            + "kZV";

    /** A long official name with a letter Windows-1251 does not have (ż), so the payload is UTF-8. */
    private static final String LONG_UTF8_NAME = "Обслуговуючий кооператив «Садівниче товариство «Żurawie» на землях "
            + "Броварської міської територіальної громади Київської області Броварського району та Баришівської "
            + "селищної громади імені Тараса Шевченка».";

    private static Invoice demo() {
        return new Invoice(NAME, IBAN, Money.parse("413.26"), EDRPOU, "9", OCTOBER, "2026-10/9");
    }

    private static Invoice named(String name) {
        return new Invoice(name, IBAN, Money.parse("413.26"), EDRPOU, "9", OCTOBER, "2026-10/9");
    }

    private static List<String> elements(NbuQrPayload payload) {
        return List.of(payload.text().split("\n", -1));
    }

    private static String hex(byte[] bytes) {
        return HexFormat.ofDelimiter(" ").withUpperCase().formatHex(bytes);
    }

    private static String firstWords(String text, int words) {
        return Arrays.stream(text.split(" ")).limit(words).collect(Collectors.joining(" "));
    }

    @Test
    void fillsEveryElementOfAnnex4Table2() {
        NbuQrPayload payload = NbuQrPayload.of(demo());

        assertThat(elements(payload)).containsExactly(
                "BCD",                                                   // 1 service mark
                "003",                                                   // 2 format version
                "2",                                                     // 3 encoding: Windows-1251
                "XCT",                                                   // 4 function: credit or instant transfer
                "",                                                      // 5 reserved
                NAME,                                                    // 6 recipient
                IBAN,                                                    // 7 account
                "UAH413.26",                                             // 8 amount
                EDRPOU,                                                  // 9 recipient code
                "SUPP/SUPP",                                             // 10 category / purpose
                "2026-10/9",                                             // 11 reference
                "Ділянка 9. Внески за жовтень 2026. Рахунок 2026-10/9",  // 12 purpose
                "",                                                      // 13 display
                "FEFF",                                                  // 14 lock mask: all but the amount
                "",                                                      // 15 valid until
                "",                                                      // 16 created at
                "");                                                     // 17 signature
        assertThat(payload.encoding()).isEqualTo(Encoding.WINDOWS_1251);
        assertThat(payload.recipient()).isEqualTo(NAME);
        assertThat(payload.purpose()).isEqualTo("Ділянка 9. Внески за жовтень 2026. Рахунок 2026-10/9");
        assertThat(payload.reference()).isEqualTo("2026-10/9");
    }

    @Test
    void writesTheBytesOfEveryElementInWindows1251WithLfOnlyAndNoLfAfterTheLast() {
        byte[] bytes = NbuQrPayload.of(demo()).bytes();

        List<String> lines = List.of(
                "42 43 44 0A",
                "30 30 33 0A",
                "32 0A",
                "58 43 54 0A",
                "0A",
                "D1 E0 E4 B3 E2 ED E8 F7 E5 20 F2 EE E2 E0 F0 E8 F1 F2 E2 EE 20 AB CD E0 E4 B3 FF BB 0A",
                "55 41 32 31 33 32 32 33 31 33 30 30 30 30 30 32 36 30 30 37 32 33 33 35 36 36 30 30 31 0A",
                "55 41 48 34 31 33 2E 32 36 0A",
                "31 32 33 34 35 36 37 38 0A",
                "53 55 50 50 2F 53 55 50 50 0A",
                "32 30 32 36 2D 31 30 2F 39 0A",
                "C4 B3 EB FF ED EA E0 20 39 2E 20 C2 ED E5 F1 EA E8 20 E7 E0 20 E6 EE E2 F2 E5 ED FC 20 32 30 32 36 2E"
                        + " 20 D0 E0 F5 F3 ED EE EA 20 32 30 32 36 2D 31 30 2F 39 0A",
                "0A",
                "46 45 46 46 0A",
                "0A",
                "0A");
        assertThat(hex(bytes)).isEqualTo(String.join(" ", lines));
        assertThat(bytes).hasSize(174).doesNotContain((byte) 0x0D);
        assertThat(bytes[bytes.length - 1]).isEqualTo((byte) 0x0A); // the LF after element 16; element 17 is empty
    }

    @Test
    void linksTheBase64UrlTextWithoutPaddingBehindTheStartCode() {
        NbuQrPayload payload = NbuQrPayload.of(demo());

        assertThat(payload.link()).isEqualTo(DEMO_LINK);
        String encoded = payload.link().substring(NbuQrPayload.START.length());
        assertThat(encoded).matches("[A-Za-z0-9_-]+").doesNotContain("=");
        assertThat(Base64.getUrlDecoder().decode(encoded)).isEqualTo(payload.bytes());
        assertThat(new String(payload.bytes(), CP1251)).isEqualTo(payload.text());
    }

    @Test
    void reEncodesTheOfficialExample1OfAnnex4ToItsTable4Text() {
        String quote = Character.toString(0x201D); // ” in the purpose, 94 hex in Windows-1251
        // Annex 4 table 3, as its HEX column and table 4 have it: element 7 ends with a space (20 hex), elements 15 and
        // 16 are 250921120000 and 250229120000 (the text column prints other dates), element 17 is RFU, «Микитинцi»
        // ends with a Latin i (69 hex).
        List<String> example = List.of("BCD", "003", "2", "UCT", "", "ТОВ «ГК«Нафтогаз України»",
                "UA201234560000000260323012042 ", "UAH2998.39", "40121452", "SUPP/SUPP", "AA15678-679",
                "?TickNo=" + quote + "YA1267" + quote + "&Addr=" + quote + "вулиця Лугова, буд. 911,Микитинцi" + quote,
                "", "FEFF", "250921120000", "250229120000", "RFU");

        NbuQrPayload payload = NbuQrPayload.encode(example);

        assertThat(payload.link()).isEqualTo(NbuQrPayload.START + EXAMPLE_1_BASE64);
        assertThat(payload.encoding()).isEqualTo(Encoding.WINDOWS_1251);
        assertThat(payload.bytes()).hasSize(207);
        assertThat(hex(payload.bytes())).startsWith("42 43 44 0A 30 30 33 0A 32 0A 55 43 54 0A 0A D2 CE C2 20 AB C3 CA AB")
                .endsWith("0A 52 46 55");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            413.26       | UAH413.26
            3.00         | UAH3
            0.50         | UAH0.50
            0.01         | UAH0.01
            10.10        | UAH10.10
            63.00        | UAH63
            150.00       | UAH150
            2998.39      | UAH2998.39
            1000000.00   | UAH1000000
            999999999.99 | UAH999999999.99
            """)
    void writesTheAmountInTheShortestFormOfItem48(String amount, String element) {
        assertThat(NbuQrPayload.amount(Money.parse(amount))).isEqualTo(element);
        Invoice invoice = new Invoice(NAME, IBAN, Money.parse(amount), EDRPOU, "9", OCTOBER, "2026-10/9");
        assertThat(elements(NbuQrPayload.of(invoice)).get(7)).isEqualTo(element);
    }

    @ParameterizedTest
    @CsvSource({"0.00", "-1.00", "1000000000.00"})
    void refusesAnAmountThatIsNotPositiveOrAboveTheMaximum(String amount) {
        assertThatThrownBy(() -> NbuQrPayload.amount(Money.parse(amount))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leavesTheAmountEmptyWhenThereIsNone() {
        Invoice invoice = new Invoice(NAME, IBAN, null, EDRPOU, "9", OCTOBER, "2026-10/9");

        assertThat(elements(NbuQrPayload.of(invoice)).get(7)).isEmpty();
    }

    @Test
    void theLongestValidWindows1251InvoiceFitsWithoutShortening() {
        String name = "Обслуговуючий кооператив «Садівниче товариство «Журавлі» на землях Броварської міської "
                + "територіальної громади Київської області Броварського району";
        String plot = "Сектор-Щ/вул.Зелена1"; // 20 characters, the longest plot number
        Invoice invoice = new Invoice(name, IBAN, Money.parse("999999999.99"), "1234567890", plot,
                YearMonth.of(2026, 11), "2026-11/" + plot);

        NbuQrPayload payload = NbuQrPayload.of(invoice);

        assertThat(payload.encoding()).isEqualTo(Encoding.WINDOWS_1251);
        assertThat(payload.recipient()).hasSize(140).isEqualTo(name.substring(0, 140));
        assertThat(payload.purpose()).isEqualTo("Ділянка Сектор-Щ/вул.Зелена1. Внески за листопад 2026. Рахунок 2026-11/"
                + plot);
        assertThat(payload.reference()).isEqualTo("2026-11/Sektor-Shch/vul.Zelena1");
        assertThat(payload.bytes()).hasSize(355);
        assertThat(payload.link().length() - NbuQrPayload.START.length()).isEqualTo(474);
        assertThat(payload.link().getBytes(StandardCharsets.US_ASCII)).hasSize(497);
    }

    @Test
    void cutsANameLongerThan140CharactersAtAWord() {
        String name = LONG_UTF8_NAME.replace("Żurawie", "Журавлі");

        NbuQrPayload payload = NbuQrPayload.of(named(name));

        assertThat(payload.encoding()).isEqualTo(Encoding.WINDOWS_1251);
        assertThat(payload.recipient()).isEqualTo(firstWords(name, 14)).hasSize(140).endsWith("Броварського");
        assertThat(payload.purpose()).isEqualTo("Ділянка 9. Внески за жовтень 2026. Рахунок 2026-10/9");
    }

    @Test
    void writesTheMonthInDigitsWhenTheFullPurposeDoesNotFit() {
        String name = firstWords(LONG_UTF8_NAME, 10);

        NbuQrPayload payload = NbuQrPayload.of(named(name));

        assertThat(payload.encoding()).isEqualTo(Encoding.UTF_8);
        assertThat(elements(payload).get(2)).isEqualTo("1");
        assertThat(payload.recipient()).isEqualTo(name);
        assertThat(payload.purpose()).isEqualTo("Ділянка 9. Внески за 10.2026. Рахунок 2026-10/9");
        assertThat(payload.bytes()).hasSize(350);
        assertThat(new String(payload.bytes(), StandardCharsets.UTF_8)).isEqualTo(payload.text());
    }

    @Test
    void dropsTheChargesPartOfThePurposeNext() {
        String name = firstWords(LONG_UTF8_NAME, 11);

        NbuQrPayload payload = NbuQrPayload.of(named(name));

        assertThat(payload.encoding()).isEqualTo(Encoding.UTF_8);
        assertThat(payload.recipient()).isEqualTo(name);
        assertThat(payload.purpose()).isEqualTo("Ділянка 9. Рахунок 2026-10/9");
    }

    @Test
    void cutsTheNameLastAndAlwaysKeepsThePlotAndTheInvoiceNumber() {
        NbuQrPayload payload = NbuQrPayload.of(named(LONG_UTF8_NAME));

        assertThat(payload.encoding()).isEqualTo(Encoding.UTF_8);
        assertThat(payload.recipient()).isEqualTo(firstWords(LONG_UTF8_NAME, 11)).endsWith("громади");
        assertThat(payload.purpose()).isEqualTo("Ділянка 9. Рахунок 2026-10/9").contains("9", "2026-10/9");
        assertThat(elements(payload).get(10)).isEqualTo("2026-10/9");
        assertThat(payload.bytes()).hasSize(338);
        assertThat(payload.link().length() - NbuQrPayload.START.length()).isEqualTo(451)
                .isLessThanOrEqualTo(NbuQrPayload.MAX_ENCODED_CHARS);
        assertThat(payload.link().getBytes(StandardCharsets.US_ASCII).length)
                .isLessThanOrEqualTo(NbuQrPayload.MAX_LINK_BYTES);
    }

    @Test
    void keepsALongPlotNumberWhenTheUtf8NameIsCut() {
        String plot = "Сектор-Щ/вул.Зелена1";
        Invoice invoice = new Invoice(LONG_UTF8_NAME, IBAN, Money.parse("999999999.99"), "1234567890", plot,
                YearMonth.of(2026, 11), "2026-11/" + plot);

        NbuQrPayload payload = NbuQrPayload.of(invoice);

        assertThat(payload.encoding()).isEqualTo(Encoding.UTF_8);
        assertThat(payload.purpose()).isEqualTo("Ділянка " + plot + ". Рахунок 2026-11/" + plot);
        assertThat(payload.recipient()).isEqualTo(firstWords(LONG_UTF8_NAME, 7)).endsWith("землях");
        assertThat(payload.bytes()).hasSize(356);
        assertThat(payload.link().length() - NbuQrPayload.START.length()).isEqualTo(NbuQrPayload.MAX_ENCODED_CHARS);
    }

    @Test
    void writesSpacesAndApostrophesPlainly() {
        String nbsp = Character.toString(0x00A0);
        String name = "Садівниче" + nbsp + "товариство  «Солов" + Character.toString(0x2019) + "їна\n гай»";
        Invoice invoice = new Invoice(name, IBAN, Money.parse("413.26"), EDRPOU, "Солов" + Character.toString(0x02BC)
                + "їна 3", OCTOBER, "2026-10/Солов" + Character.toString(0x02BC) + "їна 3");

        NbuQrPayload payload = NbuQrPayload.of(invoice);

        assertThat(payload.recipient()).isEqualTo("Садівниче товариство «Солов'їна гай»");
        assertThat(payload.purpose()).isEqualTo("Ділянка Солов'їна 3. Внески за жовтень 2026. Рахунок 2026-10/Солов'їна 3");
        assertThat(payload.reference()).isEqualTo("2026-10/Solovina 3");
        assertThat(payload.encoding()).isEqualTo(Encoding.WINDOWS_1251);
        assertThat(payload.bytes()).doesNotContain((byte) 0xA0, (byte) 0x92);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            2026-10/9            | 2026-10/9
            2026-10/9а           | 2026-10/9a
            2026-10/12-Б         | 2026-10/12-B
            2026-10/12-Щ         | 2026-10/12-Shch
            2026-10/Ялинова 5    | 2026-10/Yalynova 5
            2026-10/Згурівка 1   | 2026-10/Zghurivka 1
            2026-10/Київ-2       | 2026-10/Kyiv-2
            2026-10/Юрій 4       | 2026-10/Yurii 4
            2026-10/Знам'янка 6  | 2026-10/Znamianka 6
            2026-10/Їжакова 7    | 2026-10/Yizhakova 7
            2026-10/Подільськ 8  | 2026-10/Podilsk 8
            2026-10/№5           | 2026-10/-5
            """)
    void writesTheReferenceInIso646WithTheKmu55Table(String number, String reference) {
        assertThat(NbuQrPayload.reference(number)).isEqualTo(reference);
    }

    @Test
    void cutsTheReferenceTo35Characters() {
        String reference = NbuQrPayload.reference("2026-10/" + "щ".repeat(20));

        assertThat(reference).hasSize(35).startsWith("2026-10/shchshch").matches("[ -~]+");
    }

    @Test
    void refusesInvalidRequisites() {
        Money due = Money.parse("413.26");
        assertThatThrownBy(() -> NbuQrPayload.of(new Invoice(" ", IBAN, due, EDRPOU, "9", OCTOBER, "2026-10/9")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NbuQrPayload.of(new Invoice(NAME, "UA21 3223 1300 0002 6007 2335 6600 1", due, EDRPOU,
                "9", OCTOBER, "2026-10/9"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NbuQrPayload.of(new Invoice(NAME, "DE89370400440532013000", due, EDRPOU, "9",
                OCTOBER, "2026-10/9"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NbuQrPayload.of(new Invoice(NAME, IBAN, due, "1234567", "9", OCTOBER, "2026-10/9")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NbuQrPayload.of(new Invoice(NAME, IBAN, due, "1234567A", "9", OCTOBER, "2026-10/9")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NbuQrPayload.of(new Invoice(NAME, IBAN, due, EDRPOU, "", OCTOBER, "2026-10/")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NbuQrPayload.of(new Invoice(NAME, IBAN, due, EDRPOU, "9", OCTOBER, " ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encodeChecksTheStructureAndTheSize() {
        List<String> valid = elements(NbuQrPayload.of(demo()));
        assertThat(NbuQrPayload.encode(valid).link()).isEqualTo(DEMO_LINK);

        assertThatThrownBy(() -> NbuQrPayload.encode(valid.subList(0, 16)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NbuQrPayload.encode(with(valid, 12, "Ділянка 9.\nРахунок")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NbuQrPayload.encode(with(valid, 3, "3")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NbuQrPayload.encode(with(valid, 7, "UA2132231300000260072335660ЖЖ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NbuQrPayload.encode(with(valid, 6, "Ż")))
                .isInstanceOf(IllegalArgumentException.class); // not in Windows-1251 while element 3 says 2
        assertThatThrownBy(() -> NbuQrPayload.encode(with(valid, 12, "x".repeat(300))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(NbuQrPayload.encode(with(with(valid, 3, "1"), 6, "Ż")).encoding()).isEqualTo(Encoding.UTF_8);
    }

    private static List<String> with(List<String> elements, int number, String value) {
        List<String> changed = new ArrayList<>(elements);
        changed.set(number - 1, value);
        return changed;
    }
}
