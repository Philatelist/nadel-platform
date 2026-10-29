package app.nadel.documents.internal.nbu;

import app.nadel.documents.internal.DocumentTexts;
import app.nadel.documents.internal.pdf.PdfFonts;
import app.nadel.money.Money;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The data structure of the NBU payment QR code of one invoice: format version 003 of the NBU Board Resolution No. 97
 * of 19.08.2025, Annex 4 (quoted with our value and its source for every element in {@code docs/domain/nbu-qr.md}).
 * Pure: the same invoice always gives the same payload.
 * <p>
 * The 17 elements of Annex 4 table 2 are joined with LF only (item 7), with no line end after the last one, encoded in
 * Windows-1251 (element 3 = {@code 2}, as in every official example) or, only when a character has no Windows-1251
 * form, in UTF-8 ({@code 1}), then in Base64URL without padding behind {@link #START} (items 10.2 and 10.3). The
 * encoded part is at most 475 characters (table 1) and the whole link at most 507 bytes (item 8), so the code fits QR
 * version 17 (item 9).
 * <p>
 * When the payload would be too long, {@link #of} shortens in this order and stops at the first that fits: the full
 * purpose «Ділянка {n}. Внески за {місяць рік}. Рахунок {номер}»; the month as {@code 10.2026}; the purpose without
 * «Внески за …»; then the recipient's name is cut further at a word. The plot number and the invoice number are never
 * cut. With a plot number of at most 20 characters (the {@code plot_number_chk} constraint) and the invoice number
 * {@code YYYY-MM/plot} the short purpose takes at most 200 bytes even in UTF-8 with four-byte characters, which leaves
 * at least 28 bytes of the 356 that 475 Base64 characters carry for the name, so the final
 * {@link IllegalArgumentException} cannot happen with valid inputs.
 *
 * @param text the 17 elements joined with LF
 * @param link {@code https://qr.bank.gov.ua/} and the Base64URL text: what the QR code holds
 * @param recipient element 6 as encoded (cut at a word when longer than 140 characters or when the code was too long)
 * @param purpose element 12 as encoded
 * @param reference element 11 as encoded (the invoice number in ISO 646)
 * @param encoding the encoding of element 3
 */
public record NbuQrPayload(String text, String link, String recipient, String purpose, String reference,
        Encoding encoding) {

    /** Annex 4 table 1 row 1: the start code of the link. */
    public static final String START = "https://qr.bank.gov.ua/";

    /** Annex 4 item 8: the whole link, in bytes. */
    static final int MAX_LINK_BYTES = 507;
    /** Annex 4 table 1 row 2: the Base64URL part, in characters. */
    static final int MAX_ENCODED_CHARS = 475;
    /** Annex 4 table 2 row 6, in characters. */
    static final int MAX_RECIPIENT_CHARS = 140;
    /** Annex 4 table 2 row 11, in bytes. */
    static final int MAX_REFERENCE_BYTES = 35;
    /** Annex 4 table 2 row 12, in characters. */
    static final int MAX_PURPOSE_CHARS = 420;
    /** Annex 4 item 4.8. */
    static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99");

    static final int ELEMENTS = 17;
    static final String SERVICE_MARK = "BCD";
    static final String FORMAT_VERSION = "003";
    /** Item 4.4: a credit transfer or an instant credit transfer, as the payer chooses. */
    static final String FUNCTION = "XCT";
    /** Item 4.10 and Annex 4 example 1 «Оплата комунальних послуг» (owner decision of 28.10.2026). */
    static final String CATEGORY_PURPOSE = "SUPP/SUPP";
    /** Item 4.14: every field locked except the amount (field 8), the value of the item's own example. */
    static final String LOCK_MASK = "FEFF";
    static final String CURRENCY = "UAH";

    private static final char LF = '\n';
    private static final char NBSP = '\u00A0';
    /** Typographic apostrophes and the left quote often typed for one: written as {@code '} (U+0027). */
    private static final String APOSTROPHES = "\u2019\u02BC\u2018";
    private static final String CUT_TRAILERS = " ,;:-\u2013";
    private static final Pattern IBAN = Pattern.compile("^UA[0-9]{27}$");
    private static final Pattern RECIPIENT_CODE = Pattern.compile("^[0-9]{8,10}$");
    /**
     * Elements (1-based) in ISO 646 (column 6 «А» of table 2); 6, 9, 12 and 13 («*») may use the encoding of
     * element 3.
     */
    private static final boolean[] ISO_646 = {false, true, true, true, true, true, false, true, true, false, true, true,
        false, false, true, true, true, true};

    /** Element 3: the code table of the elements that may hold Cyrillic letters (item 4.3). */
    public enum Encoding {
        WINDOWS_1251("2", Charset.forName("windows-1251")),
        UTF_8("1", StandardCharsets.UTF_8);

        private final String code;
        private final Charset charset;

        Encoding(String code, Charset charset) {
            this.code = code;
            this.charset = charset;
        }

        /** The value of element 3. */
        public String code() {
            return code;
        }

        /** The Java charset. */
        public Charset charset() {
            return charset;
        }

        boolean encodes(String text) {
            return charset.newEncoder().canEncode(text);
        }

        static Encoding ofCode(String code) {
            for (Encoding encoding : values()) {
                if (encoding.code.equals(code)) {
                    return encoding;
                }
            }
            throw new IllegalArgumentException("Element 3 must be 1 or 2");
        }
    }

    /**
     * What the QR code of an invoice is made from.
     *
     * @param recipient the cooperative's official name as printed on the invoice (the tenant name when none is set)
     * @param iban the cooperative's IBAN, {@code UA} and 27 digits
     * @param amount the amount due; null leaves element 8 empty (the payer types it)
     * @param recipientCode the ЄДРПОУ, 8 to 10 digits
     * @param plotNumber the plot number as printed on the invoice
     * @param month the billing month of the invoice
     * @param invoiceNumber the invoice number, {@code 2026-10/9}
     */
    public record Invoice(String recipient, String iban, @Nullable Money amount, String recipientCode,
            String plotNumber, YearMonth month, String invoiceNumber) {

        public Invoice {
            Objects.requireNonNull(recipient, "recipient");
            Objects.requireNonNull(iban, "iban");
            Objects.requireNonNull(recipientCode, "recipientCode");
            Objects.requireNonNull(plotNumber, "plotNumber");
            Objects.requireNonNull(month, "month");
            Objects.requireNonNull(invoiceNumber, "invoiceNumber");
        }
    }

    public NbuQrPayload {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(encoding, "encoding");
    }

    /**
     * The payload of the invoice's QR code, shortened as the class describes when needed.
     *
     * @throws IllegalArgumentException for an empty name, plot number or invoice number, an IBAN that is not
     *         {@code UA} and 27 digits, a recipient code that is not 8 to 10 digits, an amount that is not positive or
     *         above 999999999.99, or (not possible with valid inputs) a payload that cannot be made short enough
     */
    public static NbuQrPayload of(Invoice invoice) {
        Objects.requireNonNull(invoice, "invoice");
        String recipient = cutAtWord(clean(invoice.recipient()), MAX_RECIPIENT_CHARS);
        require(!recipient.isEmpty(), "The recipient's name is empty");
        String iban = invoice.iban().strip();
        require(IBAN.matcher(iban).matches(), "The IBAN must be UA and 27 digits");
        String code = invoice.recipientCode().strip();
        require(RECIPIENT_CODE.matcher(code).matches(), "The recipient code must be 8 to 10 digits");
        String amount = invoice.amount() == null ? "" : amount(invoice.amount());
        String plot = clean(invoice.plotNumber());
        require(!plot.isEmpty(), "The plot number is empty");
        String number = clean(invoice.invoiceNumber());
        require(!number.isEmpty(), "The invoice number is empty");
        String reference = reference(number);

        DocumentTexts texts = DocumentTexts.uk();
        YearMonth month = invoice.month();
        List<String> purposes = List.of(
                texts.text("qr.purpose", Map.of("plot", plot, "month", monthName(month), "number", number)),
                texts.text("qr.purpose", Map.of("plot", plot, "month", monthDigits(month), "number", number)),
                texts.text("qr.purposeShort", Map.of("plot", plot, "number", number)));
        for (String purpose : purposes) {
            Optional<NbuQrPayload> payload = fitting(recipient, iban, amount, code, reference, purpose);
            if (payload.isPresent()) {
                return payload.get();
            }
        }
        String purpose = purposes.getLast();
        String previous = recipient;
        for (int max = length(recipient) - 1; max >= 1; max--) {
            String shorter = cutAtWord(recipient, max);
            if (shorter.isEmpty() || shorter.equals(previous)) {
                continue;
            }
            previous = shorter;
            Optional<NbuQrPayload> payload = fitting(shorter, iban, amount, code, reference, purpose);
            if (payload.isPresent()) {
                return payload.get();
            }
        }
        throw new IllegalArgumentException("The QR code of invoice " + number + " cannot be made short enough");
    }

    /**
     * Joins and encodes 17 elements given verbatim, the encoding named by element 3. Checks the structure (17
     * elements, no line break inside one, element 3, the characters of Annex 1 item 4, ISO 646 where table 2 asks for
     * it) and the size limits (475 characters, 507 bytes); the rules of each element are those of {@link #of}. Used to
     * re-encode the official examples.
     *
     * @throws IllegalArgumentException when a check fails
     */
    static NbuQrPayload encode(List<String> elements) {
        NbuQrPayload payload = encoded(elements);
        require(fits(payload), "The QR link is longer than 475 Base64 characters or 507 bytes");
        return payload;
    }

    /** The bytes the Base64URL text encodes. */
    public byte[] bytes() {
        return text.getBytes(encoding.charset());
    }

    /**
     * Element 8 in its shortest form (item 4.8): {@code UAH413.26}, {@code UAH3}, {@code UAH0.50}; the fraction, when
     * there is one, always has two digits.
     */
    static String amount(Money amount) {
        BigDecimal value = Objects.requireNonNull(amount, "amount").value();
        require(value.signum() > 0, "The amount of a QR code must be positive; leave it out instead");
        require(value.compareTo(MAX_AMOUNT) <= 0, "The amount of a QR code is at most 999999999.99");
        String plain = value.toPlainString();
        return CURRENCY + (plain.endsWith(".00") ? plain.substring(0, plain.length() - 3) : plain);
    }

    /**
     * Element 11: the invoice number in ISO 646, Ukrainian letters transliterated by the table of the Cabinet of
     * Ministers Resolution No. 55 of 27.01.2010 (a plot {@code 9а} gives {@code 2026-10/9a}); any other character
     * outside printable ISO 646 becomes {@code -}; at most 35 characters.
     */
    static String reference(String invoiceNumber) {
        String latin = Transliteration.latin(invoiceNumber);
        return latin.length() <= MAX_REFERENCE_BYTES ? latin : latin.substring(0, MAX_REFERENCE_BYTES).strip();
    }

    /**
     * A text as the payload carries it: printable as the PDF prints it ({@link PdfFonts#printable}, so the invoice
     * text and the QR content are the same, Annex 1 item 16), the no-break space and every other space as a plain
     * space, typographic apostrophes as {@code '}, runs of spaces as one, trimmed.
     */
    static String clean(String text) {
        String printable = PdfFonts.printable(Objects.requireNonNull(text, "text"));
        StringBuilder out = new StringBuilder(printable.length());
        printable.codePoints().forEach(codePoint -> {
            int written = codePoint == NBSP || Character.isSpaceChar(codePoint) ? ' '
                    : APOSTROPHES.indexOf(codePoint) >= 0 ? '\'' : codePoint;
            if (written == ' ' && (out.isEmpty() || out.charAt(out.length() - 1) == ' ')) {
                return;
            }
            out.appendCodePoint(written);
        });
        return out.toString().strip();
    }

    /**
     * The text cut to at most {@code max} characters at a word boundary, without trailing spaces or punctuation; a
     * first word longer than {@code max} is cut inside.
     */
    static String cutAtWord(String text, int max) {
        if (length(text) <= max) {
            return text;
        }
        int end = text.offsetByCodePoints(0, max);
        String head = text.substring(0, end);
        if (text.charAt(end) != ' ') {
            int space = head.lastIndexOf(' ');
            if (space > 0) {
                head = head.substring(0, space);
            }
        }
        String trimmed = stripTrailers(head);
        return trimmed.isEmpty() ? head.strip() : trimmed;
    }

    private static Optional<NbuQrPayload> fitting(String recipient, String iban, String amount, String code,
            String reference, String purpose) {
        if (length(purpose) > MAX_PURPOSE_CHARS) {
            return Optional.empty();
        }
        List<String> elements = elements(Encoding.WINDOWS_1251, recipient, iban, amount, code, reference, purpose);
        if (!elements.stream().allMatch(Encoding.WINDOWS_1251::encodes)) {
            elements = elements(Encoding.UTF_8, recipient, iban, amount, code, reference, purpose);
        }
        NbuQrPayload payload = encoded(elements);
        return fits(payload) ? Optional.of(payload) : Optional.empty();
    }

    private static List<String> elements(Encoding encoding, String recipient, String iban, String amount,
            String code, String reference, String purpose) {
        List<String> elements = new ArrayList<>(ELEMENTS);
        elements.add(SERVICE_MARK);     // 1
        elements.add(FORMAT_VERSION);   // 2
        elements.add(encoding.code());  // 3
        elements.add(FUNCTION);         // 4
        elements.add("");               // 5 reserved (Р)
        elements.add(recipient);        // 6
        elements.add(iban);             // 7
        elements.add(amount);           // 8
        elements.add(code);             // 9
        elements.add(CATEGORY_PURPOSE); // 10
        elements.add(reference);        // 11
        elements.add(purpose);          // 12
        elements.add("");               // 13 display
        elements.add(LOCK_MASK);        // 14
        elements.add("");               // 15 valid until
        elements.add("");               // 16 created at
        elements.add("");               // 17 signature (RFU)
        return elements;
    }

    private static NbuQrPayload encoded(List<String> elements) {
        Objects.requireNonNull(elements, "elements");
        require(elements.size() == ELEMENTS, "A format 003 payload has 17 elements");
        Encoding encoding = Encoding.ofCode(elements.get(2));
        for (int i = 0; i < ELEMENTS; i++) {
            String element = Objects.requireNonNull(elements.get(i), "element");
            int number = i + 1;
            element.codePoints().forEach(codePoint -> {
                require(allowed(codePoint), "Element " + number + " has a character Annex 1 item 4 does not allow");
                require(!ISO_646[number] || codePoint <= 0x7E, "Element " + number + " must be in ISO 646");
            });
            require(encoding.encodes(element), "Element " + number + " cannot be written in " + encoding.charset());
        }
        String text = String.join(String.valueOf(LF), elements);
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(encoding.charset()));
        return new NbuQrPayload(text, START + encoded, elements.get(5), elements.get(11), elements.get(10), encoding);
    }

    private static boolean fits(NbuQrPayload payload) {
        int encoded = payload.link().length() - START.length();
        return encoded <= MAX_ENCODED_CHARS
                && payload.link().getBytes(StandardCharsets.US_ASCII).length <= MAX_LINK_BYTES;
    }

    /** Annex 1 item 4: codes 32 to 255 of Windows-1251 except 127, 152 and 160, or their UTF-8 equivalents. */
    private static boolean allowed(int codePoint) {
        return codePoint >= 0x20 && codePoint != 0x7F && codePoint != 0x98 && codePoint != NBSP
                && !Character.isISOControl(codePoint);
    }

    private static String monthName(YearMonth month) {
        return DocumentTexts.uk().text("month.nominative." + month.getMonthValue()) + " " + month.getYear();
    }

    private static String monthDigits(YearMonth month) {
        return String.format(Locale.ROOT, "%02d.%d", month.getMonthValue(), month.getYear());
    }

    private static String stripTrailers(String text) {
        int end = text.length();
        while (end > 0 && CUT_TRAILERS.indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return text.substring(0, end);
    }

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
