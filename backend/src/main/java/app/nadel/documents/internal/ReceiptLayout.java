package app.nadel.documents.internal;

import app.nadel.documents.internal.pdf.PdfDocument;
import app.nadel.documents.internal.pdf.PdfDocument.PageSize;
import app.nadel.documents.internal.pdf.PdfFonts.Face;
import app.nadel.documents.internal.pdf.Table;
import app.nadel.documents.internal.pdf.Table.Column;
import app.nadel.documents.internal.pdf.Table.Row;
import app.nadel.documents.internal.pdf.TextBlock;
import app.nadel.documents.internal.pdf.TextBlock.Align;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The page of a cash receipt (feature 016 §2.8), A5: the requisites, «Квитанція № N», the date, «СТОРНОВАНО» when the
 * whole payment is reversed, the payer (and the note), the parts per plot with their amounts and reversals, the total
 * in figures and in words, the placement «Розподіл станом на», «Прийняв(ла)» with a signature line. Pure: the same
 * content and time give the same bytes.
 */
final class ReceiptLayout {

    private static final float TITLE = 13f;
    private static final float BODY = 9f;
    private static final float SMALL = 8f;

    private ReceiptLayout() {
    }

    /**
     * The PDF of the receipt.
     *
     * @param content the receipt
     * @param time    the moment it is printed: the creation date written into the file
     */
    static byte[] render(ReceiptContent content, Instant time) {
        DocumentTexts texts = DocumentTexts.uk();
        byte[] identity = content.toString().getBytes(StandardCharsets.UTF_8);
        try (PdfDocument pdf = PdfDocument.create(PageSize.A5, identity, time)) {
            requisites(pdf, content.cooperative(), texts);
            pdf.rule();
            pdf.text(TextBlock.strong(texts.text("receipt.title", Map.of("number", Long.toString(content.number()))),
                    TITLE, Align.CENTER));
            pdf.text(new TextBlock(texts.text("receipt.date", Map.of("date", DocumentFormat.day(content.receivedOn()))),
                    Face.REGULAR, BODY, Align.CENTER, BODY));
            if (content.reversedOn() != null) {
                pdf.mark(texts.text("receipt.reversed", Map.of("date", DocumentFormat.day(content.reversedOn()))),
                        TITLE);
            }
            pdf.text(TextBlock.plain(content.payerName() == null ? texts.text("receipt.payerUnknown")
                    : texts.text("receipt.payer", Map.of("name", content.payerName())), BODY));
            if (content.payerNote() != null) {
                pdf.text(TextBlock.plain(texts.text("receipt.payerNote", Map.of("note", content.payerNote())), BODY));
            }
            pdf.space(BODY * 0.4f);
            pdf.table(parts(content, texts));
            pdf.text(TextBlock.plain(texts.text("receipt.amountInWords",
                    Map.of("words", AmountInWords.of(content.total()))), BODY));
            pdf.space(BODY * 0.4f);
            pdf.text(TextBlock.strong(texts.text("receipt.allocation", Map.of("date",
                    DocumentFormat.day(content.asOf()))), BODY, Align.LEFT));
            pdf.table(placements(content, texts));
            pdf.signature(content.acceptedBy() == null ? texts.text("receipt.acceptedByUnknown")
                    : texts.text("receipt.acceptedBy", Map.of("name", content.acceptedBy())),
                    texts.text("receipt.signature"), BODY);
            return pdf.toBytes();
        }
    }

    /** The requisites block: the name in semibold, then the code, the address and the account when set. */
    static void requisites(PdfDocument pdf, ReceiptContent.Requisites cooperative, DocumentTexts texts) {
        pdf.text(TextBlock.strong(cooperative.name(), 10f, Align.LEFT));
        List<String> lines = new ArrayList<>();
        if (cooperative.edrpou() != null) {
            lines.add(texts.text("requisites.edrpou", Map.of("edrpou", cooperative.edrpou())));
        }
        if (cooperative.address() != null) {
            lines.add(texts.text("requisites.address", Map.of("address", cooperative.address())));
        }
        if (cooperative.iban() != null) {
            lines.add(texts.text("requisites.account", Map.of("iban", cooperative.iban())));
        }
        if (cooperative.bank() != null) {
            lines.add(texts.text("requisites.bank", Map.of("bank", cooperative.bank())));
        }
        for (String line : lines) {
            pdf.text(new TextBlock(line, Face.REGULAR, SMALL, Align.LEFT, 1f));
        }
    }

    private static Table parts(ReceiptContent content, DocumentTexts texts) {
        List<Row> rows = new ArrayList<>();
        for (ReceiptContent.Part part : content.parts()) {
            rows.add(Row.of(texts.text("receipt.plot", Map.of("number", part.plotNumber())),
                    part.reversedOn() == null ? ""
                            : texts.text("receipt.reversed", Map.of("date", DocumentFormat.day(part.reversedOn()))),
                    DocumentFormat.money(part.amount())));
        }
        rows.add(Row.total(texts.text("receipt.total"), "", DocumentFormat.money(content.total())));
        return new Table(List.of(
                new Column(texts.text("receipt.columns.plot"), 3, Align.LEFT),
                new Column(texts.text("receipt.columns.mark"), 4, Align.LEFT),
                new Column(texts.text("receipt.columns.amount"), 3, Align.RIGHT)), rows, BODY);
    }

    private static Table placements(ReceiptContent content, DocumentTexts texts) {
        List<Row> rows = content.placements().stream().map(line -> Row.of(
                texts.text("receipt.plot", Map.of("number", line.plotNumber())), line.label(),
                line.amount() == null ? "" : DocumentFormat.money(line.amount()))).toList();
        return new Table(List.of(
                new Column(texts.text("receipt.columns.plot"), 2, Align.LEFT),
                new Column(texts.text("receipt.columns.charge"), 5, Align.LEFT),
                new Column(texts.text("receipt.columns.amount"), 3, Align.RIGHT)), rows, SMALL);
    }
}
