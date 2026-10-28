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
import app.nadel.ledger.MonthStatus;
import app.nadel.money.Money;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The page of an invoice (feature 016 §2.3), A4, drawn from an {@link InvoiceDocument}: the requisites; «Рахунок №
 * {number}» with «(версія N)» from the second version on; the day of issue, the billing month and «Станом на» for a
 * month that was open; the plot and its owners with their shares; the table per charge type (start, charged, paid,
 * end, «Разом»); the charges booked in the month with what they were calculated from (the tariff's basis, the reading
 * interval with its two values, the kWh and the price, the losses and common consumer shares, the month a correction
 * is for); «Отримано після {місяць}»; the totals with «Аванс» (when there is one) and «До сплати»; the NBU payment
 * QR code (only when something is due, 0.5 mm modules) with «Відскануйте QR-код у застосунку свого банку», the link,
 * the recipient and the purpose; the footer. Pure: the same document gives the same bytes (the file's identity is the
 * document's canonical text, its dates the moment it was issued).
 */
final class InvoiceLayout {

    /** The side of a QR module in millimetres (Annex 1 item 18: the code is at least 30 mm). */
    static final float QR_MODULE_MM = 0.5f;

    private static final float TITLE = 14f;
    private static final float BODY = 9.5f;
    private static final float TABLE = 8.5f;
    private static final float SMALL = 7.5f;

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

    /** Draws the invoice from the cursor on (the print bundle of a month puts one invoice per page). */
    static void draw(PdfDocument pdf, InvoiceDocument invoice, DocumentTexts texts) {
        InvoiceDocument.Cooperative c = invoice.cooperative();
        ReceiptLayout.requisites(pdf, new ReceiptContent.Requisites(c.officialName(), c.edrpou(), c.address(),
                c.iban(), c.bankName()), texts);
        pdf.rule();
        String title = invoice.version() > 1
                ? texts.text("invoice.titleVersion", Map.of("number", invoice.number(), "version",
                        Integer.toString(invoice.version())))
                : texts.text("invoice.title", Map.of("number", invoice.number()));
        pdf.text(TextBlock.strong(title, TITLE, Align.CENTER));
        pdf.text(new TextBlock(texts.text("invoice.issuedOn", Map.of("date", DocumentFormat.day(invoice.issuedOn()))),
                Face.REGULAR, BODY, Align.CENTER, 1f));
        pdf.text(new TextBlock(texts.text("invoice.month", Map.of("month", DocumentFormat.month(invoice.month()))),
                Face.REGULAR, BODY, Align.CENTER, 1f));
        if (invoice.monthStatus() == MonthStatus.OPEN) {
            pdf.text(new TextBlock(texts.text("invoice.asOf", Map.of("date", DocumentFormat.day(invoice.asOf()))),
                    Face.REGULAR, BODY, Align.CENTER, 1f));
        }
        pdf.space(BODY * 0.6f);

        InvoiceDocument.Plot plot = invoice.plot();
        pdf.text(TextBlock.strong(plot.street() == null || plot.street().isBlank()
                ? texts.text("invoice.plot", Map.of("number", plot.number()))
                : texts.text("invoice.plotStreet", Map.of("number", plot.number(), "street", plot.street())),
                BODY, Align.LEFT));
        if (!invoice.owners().isEmpty()) {
            List<String> owners = invoice.owners().stream().map(owner -> texts.text("invoice.owner",
                    Map.of("name", owner.name(), "share", owner.share()))).toList();
            pdf.text(TextBlock.plain(texts.text("invoice.owners", Map.of("owners", String.join(", ", owners))),
                    BODY));
        }
        pdf.space(BODY * 0.4f);

        pdf.table(types(invoice, texts));
        if (!invoice.lines().isEmpty()) {
            pdf.text(TextBlock.strong(texts.text("invoice.lines", Map.of("month",
                    DocumentFormat.month(invoice.month()))), BODY, Align.LEFT));
            pdf.table(lines(invoice, texts));
        }
        String after = texts.text("invoice.receivedAfter", Map.of("month", monthAfter(invoice.month())));
        if (!invoice.receivedAfter().isEmpty()) {
            pdf.text(TextBlock.strong(after, BODY, Align.LEFT));
            pdf.table(received(invoice, texts));
        }
        pdf.table(totals(invoice, texts, after));

        if (invoice.qr() != null) {
            qr(pdf, invoice, texts);
        }

        pdf.space(BODY * 0.6f);
        pdf.rule();
        pdf.text(TextBlock.plain(texts.text("invoice.footer.pay"), BODY));
        pdf.text(TextBlock.plain(texts.text("invoice.footer.chairman", Map.of("names",
                names(invoice.chairmen(), texts))), BODY));
        pdf.text(TextBlock.plain(texts.text("invoice.footer.treasurer", Map.of("names",
                names(invoice.treasurers(), texts))), BODY));
        pdf.text(new TextBlock(texts.text("invoice.footer.generated", Map.of("time",
                DocumentFormat.time(invoice.issuedAt()))), Face.REGULAR, SMALL, Align.LEFT, 1f));
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
                new Column(texts.text("invoice.columns.type"), 3, Align.LEFT),
                new Column(texts.text("invoice.columns.detail"), 8, Align.LEFT),
                new Column(texts.text("invoice.columns.amount"), 2.5f, Align.RIGHT)), rows, TABLE);
    }

    private static Table received(InvoiceDocument invoice, DocumentTexts texts) {
        List<Row> rows = invoice.receivedAfter().stream().map(payment -> Row.of(
                texts.text("invoice.receivedPayment", Map.of("date", DocumentFormat.day(payment.receivedOn()))),
                DocumentFormat.money(payment.amount()))).toList();
        return new Table(List.of(
                new Column(texts.text("invoice.columns.date"), 8, Align.LEFT),
                new Column(texts.text("invoice.columns.amount"), 2.5f, Align.RIGHT)), rows, TABLE);
    }

    private static Table totals(InvoiceDocument invoice, DocumentTexts texts, String after) {
        InvoiceDocument.Totals totals = invoice.totals();
        List<Row> rows = new ArrayList<>();
        rows.add(Row.of(texts.text("invoice.totals.endDebt"), DocumentFormat.money(totals.endDebt())));
        if (totals.prepayment().signum() > 0) {
            rows.add(Row.of(texts.text("invoice.totals.prepayment"), DocumentFormat.money(totals.prepayment())));
        }
        if (totals.receivedAfter().signum() > 0) {
            rows.add(Row.of(after, DocumentFormat.money(totals.receivedAfter())));
        }
        rows.add(Row.total(texts.text("invoice.totals.due"), DocumentFormat.money(totals.due())));
        return new Table(List.of(new Column(texts.text("invoice.total"), 8, Align.LEFT),
                new Column(texts.text("invoice.columns.amount"), 2.5f, Align.RIGHT)), rows, BODY);
    }

    private static void qr(PdfDocument pdf, InvoiceDocument invoice, DocumentTexts texts) {
        InvoiceDocument.Qr qr = invoice.qr();
        NbuQrImage image = NbuQrImage.of(qr.link());
        float side = image.sidePoints(QR_MODULE_MM);
        pdf.space(BODY * 0.6f);
        pdf.drawing(side, (content, left, bottom, width, height) -> image.drawPdf(content, left, bottom,
                QR_MODULE_MM));
        pdf.text(TextBlock.strong(texts.text("invoice.qr.scan"), BODY, Align.LEFT));
        pdf.text(new TextBlock(qr.link(), Face.REGULAR, SMALL, Align.LEFT, SMALL * 0.4f));
        pdf.text(TextBlock.plain(texts.text("invoice.qr.recipient", Map.of("name",
                invoice.cooperative().officialName())), SMALL));
        pdf.text(TextBlock.plain(texts.text("invoice.qr.purpose", Map.of("purpose", qr.purpose())), SMALL));
    }

    /** What a line was calculated from, in words and figures. */
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
        if (line.kind() != LineKind.OPENING) {
            parts.add(DocumentFormat.period(line.serviceFrom(), line.serviceTo()));
        }
        if (line.kind() == LineKind.REGULAR || line.kind() == LineKind.ADJUSTMENT) {
            parts.addAll(calculation(line, texts));
        }
        return String.join("; ", parts);
    }

    private static List<String> calculation(InvoiceLedger.Line line, DocumentTexts texts) {
        List<String> out = new ArrayList<>();
        switch (line.source()) {
            case CONSUMPTION -> readings(line, texts).ifPresent(out::add);
            case LOSSES -> out.add(line.lossesPercent() != null && line.consumption() != null
                    ? texts.text("invoice.line.lossesPercent", Map.of("percent",
                            DocumentFormat.decimal(line.lossesPercent(), 2), "consumption",
                            DocumentFormat.decimal(line.consumption(), 3)))
                    : texts.text("invoice.line.lossesRun"));
            case COMMON_CONSUMER -> {
                if (line.consumerName() != null && !line.consumerName().isBlank()) {
                    out.add(texts.text("invoice.line.commonConsumer", Map.of("name", line.consumerName())));
                }
            }
            default -> {
            }
        }
        for (InvoiceLedger.Part part : line.parts()) {
            String text = part(part, texts);
            if (text != null) {
                out.add(text);
            }
        }
        return out;
    }

    private static Optional<String> readings(InvoiceLedger.Line line, DocumentTexts texts) {
        if (line.readings().size() < 2) {
            return Optional.empty();
        }
        List<InvoiceLedger.Reading> sorted = line.readings().stream()
                .sorted(Comparator.comparing(InvoiceLedger.Reading::takenOn)).toList();
        InvoiceLedger.Reading first = sorted.getFirst();
        InvoiceLedger.Reading last = sorted.getLast();
        return Optional.of(texts.text("invoice.line.readings", Map.of(
                "from", DocumentFormat.decimal(first.value(), 3), "to", DocumentFormat.decimal(last.value(), 3),
                "fromDate", DocumentFormat.day(first.takenOn()), "toDate", DocumentFormat.day(last.takenOn()))));
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

    /** «жовтня 2026»: the month after «Отримано після». */
    private static String monthAfter(YearMonth month) {
        return DocumentFormat.monthGenitive(month) + " " + month.getYear();
    }

    private static String names(List<String> names, DocumentTexts texts) {
        return names.isEmpty() ? texts.text("invoice.footer.nobody") : String.join(", ", names);
    }
}
