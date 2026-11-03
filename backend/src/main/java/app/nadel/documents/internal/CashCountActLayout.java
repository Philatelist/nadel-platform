package app.nadel.documents.internal;

import app.nadel.documents.internal.pdf.PdfDocument;
import app.nadel.documents.internal.pdf.PdfDocument.PageSize;
import app.nadel.documents.internal.pdf.PdfFonts.Face;
import app.nadel.documents.internal.pdf.Table;
import app.nadel.documents.internal.pdf.Table.Column;
import app.nadel.documents.internal.pdf.Table.Row;
import app.nadel.documents.internal.pdf.TextBlock;
import app.nadel.documents.internal.pdf.TextBlock.Align;
import app.nadel.money.Money;
import app.nadel.treasury.CashCountAct;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The page of a cash count act («Акт перерахунку каси», feature 018 §2.6), A4: the requisites; the title; the date and
 * time of the count (Kyiv); the table of the book balance, the counted cash and the difference; the result
 * («збігається», «нестача …», «надлишок …»); the explanation; how the difference was booked (or «Різницю не
 * оформлено.») and its reversal; the signature lines of the cashier (treasurer) and the chairman of the board, each
 * with a name or an empty line; the footer. Pure: the same content and time give the same bytes.
 */
final class CashCountActLayout {

    private static final float TITLE = 14f;
    private static final float BODY = 10f;
    private static final float SMALL = 7.5f;

    private CashCountActLayout() {
    }

    /**
     * What the act shows.
     *
     * @param treasurerName the first active holder of the treasurer base role, null without one
     * @param chairmanName  the first active holder of the chairman base role, null without one
     */
    record Content(ReceiptContent.Requisites cooperative, CashCountAct act, @Nullable String treasurerName,
            @Nullable String chairmanName) {

        Content {
            Objects.requireNonNull(cooperative, "cooperative");
            Objects.requireNonNull(act, "act");
        }
    }

    /**
     * The PDF of the act.
     *
     * @param time the moment it is printed: the creation date written into the file and the footer
     */
    static byte[] render(Content content, Instant time) {
        DocumentTexts texts = DocumentTexts.uk();
        byte[] identity = content.toString().getBytes(StandardCharsets.UTF_8);
        try (PdfDocument pdf = PdfDocument.create(PageSize.A4, identity, time)) {
            draw(pdf, content, texts, time);
            return pdf.toBytes();
        }
    }

    private static void draw(PdfDocument pdf, Content content, DocumentTexts texts, Instant time) {
        CashCountAct act = content.act();
        ReceiptLayout.requisites(pdf, content.cooperative(), texts);
        pdf.rule();
        pdf.text(TextBlock.strong(texts.text("cashCountAct.title"), TITLE, Align.CENTER));
        pdf.text(new TextBlock(texts.text("cashCountAct.countedAt", Map.of("time",
                DocumentFormat.time(act.countedAt()))), Face.REGULAR, BODY, Align.CENTER, BODY));

        pdf.table(new Table(List.of(
                new Column(texts.text("cashCountAct.columns.item"), 8, Align.LEFT),
                new Column(texts.text("cashCountAct.columns.amount"), 3, Align.RIGHT)), List.of(
                Row.of(texts.text("cashCountAct.book"), DocumentFormat.money(act.bookBalance())),
                Row.of(texts.text("cashCountAct.counted"), DocumentFormat.money(act.counted())),
                Row.total(texts.text("cashCountAct.difference"), DocumentFormat.money(act.difference()))), BODY));

        pdf.text(TextBlock.strong(result(act.difference(), texts), 12f, Align.LEFT));
        pdf.text(TextBlock.plain(act.explanation() == null ? texts.text("cashCountAct.noExplanation")
                : texts.text("cashCountAct.explanation", Map.of("text", act.explanation())), BODY));
        if (act.difference().signum() != 0) {
            pdf.space(BODY * 0.4f);
            CashCountAct.Booking booking = act.booking();
            if (booking == null) {
                pdf.text(TextBlock.plain(texts.text("cashCountAct.notBooked"), BODY));
            } else {
                String kind = booking.amount().signum() > 0 ? "SURPLUS" : "SHORTAGE";
                pdf.text(TextBlock.plain(texts.text("cashCountAct.booked." + kind, Map.of(
                        "bookedOn", DocumentFormat.day(booking.bookedAt()),
                        "amount", DocumentFormat.money(abs(booking.amount())),
                        "date", DocumentFormat.day(booking.date()),
                        "month", DocumentFormat.month(booking.bookingMonth()),
                        "reason", booking.reason())), BODY));
                if (booking.reversal() != null) {
                    pdf.text(TextBlock.plain(texts.text("cashCountAct.bookingReversed", Map.of(
                            "date", DocumentFormat.day(booking.reversal().date()),
                            "reason", booking.reversal().reason())), BODY));
                }
            }
        }

        pdf.space(BODY * 1.5f);
        pdf.signature(content.treasurerName() == null ? texts.text("cashCountAct.treasurerUnknown")
                : texts.text("cashCountAct.treasurer", Map.of("name", content.treasurerName())),
                texts.text("cashCountAct.signature"), BODY);
        pdf.space(BODY);
        pdf.signature(content.chairmanName() == null ? texts.text("cashCountAct.chairmanUnknown")
                : texts.text("cashCountAct.chairman", Map.of("name", content.chairmanName())),
                texts.text("cashCountAct.signature"), BODY);

        pdf.space(BODY);
        pdf.rule();
        pdf.text(new TextBlock(texts.text("cashCountAct.footer.generated", Map.of("time", DocumentFormat.time(time))),
                Face.REGULAR, SMALL, Align.LEFT, 1f));
    }

    /** «Результат: збігається», «… нестача 20,00 ₴» or «… надлишок 20,00 ₴» (the size of the difference). */
    static String result(Money difference, DocumentTexts texts) {
        if (difference.signum() == 0) {
            return texts.text("cashCountAct.result.MATCHES");
        }
        String key = difference.signum() > 0 ? "cashCountAct.result.SURPLUS" : "cashCountAct.result.SHORTAGE";
        return texts.text(key, Map.of("amount", DocumentFormat.money(abs(difference))));
    }

    private static Money abs(Money amount) {
        return amount.signum() < 0 ? amount.negate() : amount;
    }
}
