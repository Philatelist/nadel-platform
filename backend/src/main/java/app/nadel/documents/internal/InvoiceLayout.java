package app.nadel.documents.internal;

import app.nadel.documents.internal.nbu.NbuQrImage;
import app.nadel.documents.internal.pdf.PdfDocument;
import app.nadel.documents.internal.pdf.PdfDocument.PageSize;
import app.nadel.documents.internal.pdf.PdfFonts.Face;
import app.nadel.documents.internal.pdf.Table;
import app.nadel.documents.internal.pdf.Table.Column;
import app.nadel.documents.internal.pdf.Table.Row;
import app.nadel.documents.internal.pdf.TextBlock;
import app.nadel.documents.internal.pdf.TextBlock.Align;
import app.nadel.ledger.InvoiceLedger;
import app.nadel.ledger.InvoiceLedger.LineKind;
import app.nadel.ledger.InvoiceLedger.LineSource;
import app.nadel.ledger.MonthStatus;
import app.nadel.money.Money;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The page of an invoice (feature 016 §2.3), A4, drawn from an {@link InvoiceDocument}: on top the requisites with,
 * beside them, «Рахунок № {number}» with «(версія N)» from the second version on, the day of issue, the billing month
 * and «Станом на» for a month that was open; the plot and its owners with their shares; the table per charge type
 * (start, charged, paid, end, «Разом»); the charges booked in the month with what they were calculated from (the
 * tariff's basis; for a metered charge one range, the reading dates, with the two values, the kWh and the price; the
 * losses and common consumer shares; the month a correction is for); «Оплати за {місяць}», the payment rows booked in
 * the month with the receipt number or the bank reference; «Отримано після {місяць}»; then, kept on one page with the
 * footer, the totals with «Аванс» (when there is one) and «До сплати» and beside them the NBU payment QR code (only
 * when something is due, 0.5 mm modules, the space of the largest code reserved) with «Відскануйте QR-код у
 * застосунку свого банку», the line «Оплатити в застосунку банку» that opens the code's link (a link annotation; the
 * link itself is not printed), the recipient and the purpose; the footer. An invoice too long for one page goes on on
 * the next, whose header repeats the invoice number and the plot. Pure: the same document gives the same bytes (the
 * file's identity is the document's canonical text, its dates the moment it was issued).
 */
final class InvoiceLayout {

    /** The side of a QR module in millimetres (Annex 1 item 18: the code is at least 30 mm). */
    static final float QR_MODULE_MM = 0.5f;

    private static final float TITLE = 13f;
    private static final float BODY = 9f;
    private static final float TABLE = 8f;
    private static final float SMALL = 7.5f;
    /** The column weights of the charges, the payments and «Отримано після»: the amounts line up. */
    private static final float FIRST = 2.2f;
    private static final float DETAIL = 9.2f;
    private static final float AMOUNT = 1.6f;
    /** The gap between two columns side by side. */
    private static final float GAP = 12f;

    private InvoiceLayout() {
    }

    /** The PDF of the invoice. */
    static byte[] render(InvoiceDocument invoice) {
        DocumentTexts texts = DocumentTexts.uk();
        byte[] identity = InvoiceSnapshots.identity(invoice).getBytes(StandardCharsets.UTF_8);
        try (PdfDocument pdf = PdfDocument.create(PageSize.A4, identity, invoice.issuedAt())) {
            draw(pdf, invoice, texts);
            return pdf.toBytes();
        }
    }

    /**
     * Draws the invoice from the cursor on (the print bundle of a month starts each invoice on a new page); a page
     * started inside the invoice begins with {@link #continuation}.
     */
    static void draw(PdfDocument pdf, InvoiceDocument invoice, DocumentTexts texts) {
        pdf.continuation(() -> continuation(pdf, invoice, texts));
        try {
            header(pdf, invoice, texts);
            body(pdf, invoice, texts);
            pdf.keepTogether(() -> {
                closing(pdf, invoice, texts);
                footer(pdf, invoice, texts);
            });
        } finally {
            pdf.continuation(null);
        }
    }

    /**
     * The header of a page the invoice goes on on: «Рахунок № 2026-10/15, ділянка № 15, вул. Вишнева (продовження)».
     */
    static String continuationTitle(InvoiceDocument invoice, DocumentTexts texts) {
        InvoiceDocument.Plot plot = invoice.plot();
        return plot.street() == null || plot.street().isBlank()
                ? texts.text("invoice.continued", Map.of("number", invoice.number(), "plot", plot.number()))
                : texts.text("invoice.continuedStreet", Map.of("number", invoice.number(), "plot", plot.number(),
                        "street", plot.street()));
    }

    private static void continuation(PdfDocument pdf, InvoiceDocument invoice, DocumentTexts texts) {
        pdf.text(new TextBlock(continuationTitle(invoice, texts), Face.SEMIBOLD, BODY, Align.LEFT, 0f));
        pdf.rule();
    }

    private static void header(PdfDocument pdf, InvoiceDocument invoice, DocumentTexts texts) {
        InvoiceDocument.Cooperative c = invoice.cooperative();
        float requisites = pdf.contentWidth() * 0.55f;
        pdf.beside(requisites, GAP, () -> ReceiptLayout.requisites(pdf, new ReceiptContent.Requisites(
                c.officialName(), c.edrpou(), c.address(), c.iban(), c.bankName()), texts), () -> {
                    String title = invoice.version() > 1
                            ? texts.text("invoice.titleVersion", Map.of("number", invoice.number(), "version",
                                    Integer.toString(invoice.version())))
                            : texts.text("invoice.title", Map.of("number", invoice.number()));
                    pdf.text(new TextBlock(title, Face.SEMIBOLD, TITLE, Align.RIGHT, 2f));
                    pdf.text(new TextBlock(texts.text("invoice.month", Map.of("month",
                            DocumentFormat.month(invoice.month()))), Face.REGULAR, BODY, Align.RIGHT, 0.5f));
                    pdf.text(new TextBlock(texts.text("invoice.issuedOn", Map.of("date",
                            DocumentFormat.day(invoice.issuedOn()))), Face.REGULAR, BODY, Align.RIGHT, 0.5f));
                    if (invoice.monthStatus() == MonthStatus.OPEN) {
                        pdf.text(new TextBlock(texts.text("invoice.asOf", Map.of("date",
                                DocumentFormat.day(invoice.asOf()))), Face.REGULAR, BODY, Align.RIGHT, 0.5f));
                    }
                });
        pdf.rule();

        InvoiceDocument.Plot plot = invoice.plot();
        pdf.text(new TextBlock(plot.street() == null || plot.street().isBlank()
                ? texts.text("invoice.plot", Map.of("number", plot.number()))
                : texts.text("invoice.plotStreet", Map.of("number", plot.number(), "street", plot.street())),
                Face.SEMIBOLD, BODY, Align.LEFT, 1f));
        if (!invoice.owners().isEmpty()) {
            List<String> owners = invoice.owners().stream().map(owner -> texts.text("invoice.owner",
                    Map.of("name", owner.name(), "share", owner.share()))).toList();
            pdf.text(new TextBlock(texts.text("invoice.owners", Map.of("owners", String.join(", ", owners))),
                    Face.REGULAR, BODY, Align.LEFT, 1f));
        }
        pdf.space(BODY * 0.4f);
    }

    private static void body(PdfDocument pdf, InvoiceDocument invoice, DocumentTexts texts) {
        pdf.table(types(invoice, texts));
        if (!invoice.lines().isEmpty()) {
            heading(pdf, texts.text("invoice.lines", Map.of("month", DocumentFormat.month(invoice.month()))));
            pdf.table(lines(invoice, texts));
        }
        if (!invoice.payments().isEmpty()) {
            heading(pdf, texts.text("invoice.payments", Map.of("month", DocumentFormat.month(invoice.month()))));
            pdf.table(payments(invoice, texts));
        }
        if (!invoice.receivedAfter().isEmpty()) {
            heading(pdf, receivedAfter(invoice, texts));
            pdf.table(received(invoice, texts));
        }
    }

    /** A section heading kept on the page of the first row under it. */
    private static void heading(PdfDocument pdf, String text) {
        TextBlock heading = new TextBlock(text, Face.SEMIBOLD, BODY, Align.LEFT, 1f);
        float rowAndHeader = 2 * (TABLE * 1.3f + 5f);
        if (pdf.measure(() -> pdf.text(heading)) + rowAndHeader > pdf.remaining()) {
            pdf.newPage();
        }
        pdf.text(heading);
    }

    /** The totals and, when something is due, the QR block beside them. */
    private static void closing(PdfDocument pdf, InvoiceDocument invoice, DocumentTexts texts) {
        pdf.space(BODY * 0.3f);
        if (invoice.qr() == null) {
            pdf.table(totals(invoice, texts));
            return;
        }
        float side = NbuQrImage.maxSidePoints(QR_MODULE_MM);
        pdf.beside(pdf.contentWidth() - side - GAP, GAP, () -> {
            pdf.table(totals(invoice, texts));
            qrTexts(pdf, invoice, texts);
        }, () -> qr(pdf, invoice.qr(), side));
    }

    private static void footer(PdfDocument pdf, InvoiceDocument invoice, DocumentTexts texts) {
        pdf.space(BODY * 0.3f);
        pdf.rule();
        pdf.text(new TextBlock(texts.text("invoice.footer.pay"), Face.REGULAR, TABLE, Align.LEFT, 0.5f));
        pdf.text(new TextBlock(texts.text("invoice.footer.chairman", Map.of("names",
                names(invoice.chairmen(), texts))), Face.REGULAR, TABLE, Align.LEFT, 0.5f));
        pdf.text(new TextBlock(texts.text("invoice.footer.treasurer", Map.of("names",
                names(invoice.treasurers(), texts))), Face.REGULAR, TABLE, Align.LEFT, 0.5f));
        pdf.text(new TextBlock(texts.text("invoice.footer.generated", Map.of("time",
                DocumentFormat.time(invoice.issuedAt()))), Face.REGULAR, SMALL, Align.LEFT, 0f));
    }

    private static Table types(InvoiceDocument invoice, DocumentTexts texts) {
        List<Row> rows = new ArrayList<>();
        Money start = Money.ZERO;
        Money charged = Money.ZERO;
        Money paid = Money.ZERO;
        Money end = Money.ZERO;
        for (InvoiceDocument.TypeRow type : invoice.types()) {
            rows.add(Row.of(type.name(), DocumentFormat.money(type.start()), DocumentFormat.money(type.charged()),
                    DocumentFormat.money(type.paid()), DocumentFormat.money(type.end())));
            start = start.plus(type.start());
            charged = charged.plus(type.charged());
            paid = paid.plus(type.paid());
            end = end.plus(type.end());
        }
        rows.add(Row.total(texts.text("invoice.total"), DocumentFormat.money(start), DocumentFormat.money(charged),
                DocumentFormat.money(paid), DocumentFormat.money(end)));
        return new Table(List.of(
                new Column(texts.text("invoice.columns.type"), 4, Align.LEFT),
                new Column(texts.text("invoice.columns.start"), 2.5f, Align.RIGHT),
                new Column(texts.text("invoice.columns.charged"), 2.5f, Align.RIGHT),
                new Column(texts.text("invoice.columns.paid"), 2.5f, Align.RIGHT),
                new Column(texts.text("invoice.columns.end"), 2.5f, Align.RIGHT)), rows, TABLE);
    }

    private static Table lines(InvoiceDocument invoice, DocumentTexts texts) {
        List<Row> rows = invoice.lines().stream().map(line -> Row.of(invoice.typeName(line.chargeTypeId()),
                describe(line, texts), DocumentFormat.money(line.amount()))).toList();
        return new Table(List.of(
                new Column(texts.text("invoice.columns.type"), FIRST, Align.LEFT),
                new Column(texts.text("invoice.columns.detail"), DETAIL, Align.LEFT),
                new Column(texts.text("invoice.columns.amount"), AMOUNT, Align.RIGHT)), rows, TABLE);
    }

    private static Table payments(InvoiceDocument invoice, DocumentTexts texts) {
        List<Row> rows = invoice.payments().stream().map(payment -> Row.of(DocumentFormat.day(payment.receivedOn()),
                reference(payment, texts), DocumentFormat.money(payment.amount()))).toList();
        return new Table(List.of(
                new Column(texts.text("invoice.columns.date"), FIRST, Align.LEFT),
                new Column(texts.text("invoice.columns.reference"), DETAIL, Align.LEFT),
                new Column(texts.text("invoice.columns.amount"), AMOUNT, Align.RIGHT)), rows, TABLE);
    }

    /**
     * What a member matches a payment row with: «квитанція № 7», «банківський платіж, референс ПД-2026-0411», nothing
     * when neither was entered; a reversal says so before it.
     */
    static String reference(InvoiceLedger.MonthPayment payment, DocumentTexts texts) {
        String reference = payment.receiptNumber() != null
                ? texts.text("invoice.payment.receipt", Map.of("number", payment.receiptNumber().toString()))
                : payment.bankReference() != null && !payment.bankReference().isBlank()
                        ? texts.text("invoice.payment.bank", Map.of("reference", payment.bankReference()))
                        : null;
        if (payment.reverses() == null) {
            return reference == null ? "" : reference;
        }
        return reference == null ? texts.text("invoice.payment.reversal")
                : texts.text("invoice.payment.reversalOf", Map.of("reference", reference));
    }

    private static Table received(InvoiceDocument invoice, DocumentTexts texts) {
        List<Row> rows = invoice.receivedAfter().stream().map(payment -> Row.of(
                texts.text("invoice.receivedPayment", Map.of("date", DocumentFormat.day(payment.receivedOn()))),
                DocumentFormat.money(payment.amount()))).toList();
        return new Table(List.of(
                new Column(texts.text("invoice.columns.date"), FIRST + DETAIL, Align.LEFT),
                new Column(texts.text("invoice.columns.amount"), AMOUNT, Align.RIGHT)), rows, TABLE);
    }

    private static Table totals(InvoiceDocument invoice, DocumentTexts texts) {
        InvoiceDocument.Totals totals = invoice.totals();
        List<Row> rows = new ArrayList<>();
        rows.add(Row.of(texts.text("invoice.totals.endDebt"), DocumentFormat.money(totals.endDebt())));
        if (totals.prepayment().signum() > 0) {
            rows.add(Row.of(texts.text("invoice.totals.prepayment"), DocumentFormat.money(totals.prepayment())));
        }
        if (totals.receivedAfter().signum() > 0) {
            rows.add(Row.of(receivedAfter(invoice, texts), DocumentFormat.money(totals.receivedAfter())));
        }
        rows.add(Row.total(texts.text("invoice.totals.due"), DocumentFormat.money(totals.due())));
        return new Table(List.of(new Column(texts.text("invoice.total"), 6, Align.LEFT),
                new Column(texts.text("invoice.columns.amount"), 2.5f, Align.RIGHT)), rows, BODY);
    }

    /** «Відскануйте…», the line that opens the code's link, the recipient and the purpose. */
    private static void qrTexts(PdfDocument pdf, InvoiceDocument invoice, DocumentTexts texts) {
        InvoiceDocument.Qr qr = invoice.qr();
        pdf.text(new TextBlock(texts.text("invoice.qr.scan"), Face.SEMIBOLD, BODY, Align.LEFT, 1f));
        pdf.link(new TextBlock(texts.text("invoice.qr.open"), Face.REGULAR, BODY, Align.LEFT, 2f), qr.link());
        pdf.text(new TextBlock(texts.text("invoice.qr.recipient", Map.of("name",
                invoice.cooperative().officialName())), Face.REGULAR, SMALL, Align.LEFT, 0.5f));
        pdf.text(new TextBlock(texts.text("invoice.qr.purpose", Map.of("purpose", qr.purpose())), Face.REGULAR,
                SMALL, Align.LEFT, 0f));
    }

    /** The QR code at the top right of its column, which is as wide as the largest code. */
    private static void qr(PdfDocument pdf, InvoiceDocument.Qr qr, float reserved) {
        NbuQrImage image = NbuQrImage.of(qr.link());
        float side = image.sidePoints(QR_MODULE_MM);
        pdf.drawing(reserved, (content, left, bottom, width, height) -> image.drawPdf(content,
                left + width - side, bottom + height - side, QR_MODULE_MM));
    }

    /**
     * What a line was calculated from, in words and figures. A regular charge or an adjustment of a metered source
     * (consumption, losses, a common consumer share) shows one range: the reading dates with the two values
     * («показники 10.08.2026 → 03.10.2026: 3 435,900 → 3 705,500 = 269,600 кВт·год × 4,32 ₴»), or, without
     * readings, the service period after what it is («спільний споживач «Свердловина №1», 04.10.2026 → 18.10.2026:
     * 14,114 кВт·год × 4,32 ₴»). Every other line: its kind, its service period and its tariff parts.
     */
    static String describe(InvoiceLedger.Line line, DocumentTexts texts) {
        List<String> parts = new ArrayList<>();
        if (line.kind() == LineKind.OPENING) {
            parts.add(texts.text("invoice.line.opening"));
        } else if (line.kind() == LineKind.REVERSAL) {
            parts.add(line.corrects() == null ? texts.text("invoice.line.reversal")
                    : texts.text("invoice.line.reversalFor", Map.of("month", DocumentFormat.month(line.corrects()))));
        } else if (line.kind() == LineKind.ADJUSTMENT) {
            parts.add(line.corrects() == null ? texts.text("invoice.line.adjustment")
                    : texts.text("invoice.line.adjustmentFor",
                            Map.of("month", DocumentFormat.month(line.corrects()))));
        }
        boolean calculated = line.kind() == LineKind.REGULAR || line.kind() == LineKind.ADJUSTMENT;
        if (calculated && metered(line.source())) {
            parts.add(metered(line, texts));
            return String.join("; ", parts);
        }
        if (line.kind() != LineKind.OPENING) {
            parts.add(DocumentFormat.period(line.serviceFrom(), line.serviceTo()));
        }
        if (calculated) {
            for (InvoiceLedger.Part part : line.parts()) {
                String text = part(part, texts);
                if (text != null) {
                    parts.add(text);
                }
            }
        }
        return String.join("; ", parts);
    }

    private static boolean metered(LineSource source) {
        return source == LineSource.CONSUMPTION || source == LineSource.LOSSES
                || source == LineSource.COMMON_CONSUMER;
    }

    /** A metered line: one range, what was measured over it and the calculation (see {@link #describe}). */
    private static String metered(InvoiceLedger.Line line, DocumentTexts texts) {
        List<String> calculation = new ArrayList<>();
        for (InvoiceLedger.Part part : line.parts()) {
            String text = part(part, texts);
            if (text != null) {
                calculation.add(text);
            }
        }
        String label = switch (line.source()) {
            case LOSSES -> line.lossesPercent() != null && line.consumption() != null
                    ? texts.text("invoice.line.lossesPercent", Map.of("percent",
                            DocumentFormat.decimal(line.lossesPercent(), 2), "consumption",
                            DocumentFormat.decimal(line.consumption(), 3)))
                    : texts.text("invoice.line.lossesRun");
            case COMMON_CONSUMER -> line.consumerName() != null && !line.consumerName().isBlank()
                    ? texts.text("invoice.line.commonConsumer", Map.of("name", line.consumerName()))
                    : null;
            default -> null;
        };
        String joined = String.join(" + ", calculation);
        if (line.readings().size() >= 2) {
            List<InvoiceLedger.Reading> sorted = line.readings().stream()
                    .sorted(Comparator.comparing(InvoiceLedger.Reading::takenOn)).toList();
            InvoiceLedger.Reading first = sorted.getFirst();
            InvoiceLedger.Reading last = sorted.getLast();
            String what = label != null ? label : texts.text("invoice.line.readingValues", Map.of(
                    "from", DocumentFormat.decimal(first.value(), 3), "to", DocumentFormat.decimal(last.value(), 3)));
            String head = texts.text("invoice.line.readings", Map.of("fromDate", DocumentFormat.day(first.takenOn()),
                    "toDate", DocumentFormat.day(last.takenOn()), "what", what));
            return joined.isEmpty() ? head
                    : texts.text("invoice.line.equals", Map.of("text", head, "calculation", joined));
        }
        String range = range(line.serviceFrom(), line.serviceTo(), texts);
        String head = label == null ? range
                : texts.text("invoice.line.labelled", Map.of("label", label, "period", range));
        return joined.isEmpty() ? head
                : texts.text("invoice.line.calculated", Map.of("text", head, "calculation", joined));
    }

    /**
     * A range of days: «жовтень 2026» for one whole month, the day for one day, otherwise «04.10.2026 → 18.10.2026».
     */
    private static String range(LocalDate from, LocalDate to, DocumentTexts texts) {
        if (from.equals(to) || from.getDayOfMonth() == 1 && YearMonth.from(from).equals(YearMonth.from(to))
                && to.getDayOfMonth() == to.lengthOfMonth()) {
            return DocumentFormat.period(from, to);
        }
        return texts.text("invoice.line.range", Map.of("from", DocumentFormat.day(from), "to",
                DocumentFormat.day(to)));
    }

    /**
     * One part of a calculation: «50,00 ₴ × 6,00 сотки / 12», «75,00 ₴ за ділянку», «338,300 кВт·год × 4,32 ₴»;
     * null when the part has no rate or quantity to show.
     */
    static @Nullable String part(InvoiceLedger.Part part, DocumentTexts texts) {
        if (part.rate() == null || part.unit() == null) {
            return null;
        }
        String rate = DocumentFormat.decimal(part.rate(), 2) + DocumentFormat.NBSP
                + texts.text("money.currency");
        String text = switch (part.unit()) {
            case "SOTKA" -> part.quantity() == null ? null : texts.text("invoice.line.tariffSotka",
                    Map.of("rate", rate, "quantity", DocumentFormat.decimal(part.quantity(), 2)));
            case "PLOT" -> texts.text("invoice.line.tariffPlot", Map.of("rate", rate));
            case "KWH", "M3" -> part.quantity() == null ? null : texts.text("invoice.line.metered", Map.of(
                    "quantity", DocumentFormat.decimal(part.quantity(), 3), "unit",
                    texts.text("invoice.unit." + part.unit()), "rate", rate));
            default -> null;
        };
        if (text == null) {
            return null;
        }
        if ("SOTKA".equals(part.unit()) || "PLOT".equals(part.unit())) {
            String divisor = switch (part.frequency() == null ? "" : part.frequency()) {
                case "YEARLY" -> "12";
                case "QUARTERLY" -> "3";
                default -> null;
            };
            if (divisor != null) {
                text = texts.text("invoice.line.divisor", Map.of("text", text, "divisor", divisor));
            }
            boolean oneOff = "ONE_OFF".equals(part.frequency());
            if (!oneOff && part.days() < part.from().lengthOfMonth()) {
                text = texts.text("invoice.line.days", Map.of("text", text, "days", Integer.toString(part.days())));
            }
        }
        return text;
    }

    /** «Отримано після жовтня 2026». */
    private static String receivedAfter(InvoiceDocument invoice, DocumentTexts texts) {
        return texts.text("invoice.receivedAfter", Map.of("month", monthAfter(invoice.month())));
    }

    /** «жовтня 2026»: the month after «Отримано після». */
    private static String monthAfter(YearMonth month) {
        return DocumentFormat.monthGenitive(month) + " " + month.getYear();
    }

    private static String names(List<String> names, DocumentTexts texts) {
        return names.isEmpty() ? texts.text("invoice.footer.nobody") : String.join(", ", names);
    }
}
