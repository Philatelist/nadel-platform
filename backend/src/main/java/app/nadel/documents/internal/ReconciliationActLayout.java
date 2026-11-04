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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The page of a reconciliation act («Акт звірки взаєморозрахунків», feature 018 §2.10), A4: the requisites; the title
 * and the period; the plot and its current owners; the table of the period with the columns date, document,
 * «Нараховано» and «Сплачено»: the opening balance (a debt in «Нараховано», an overpayment in «Сплачено»), every line,
 * «Обороти за період» and the closing balance placed the same way; the debt and the prepayment at both ends; the
 * sentence by the closing net balance («заборгованість … становить», «переплата … становить», «заборгованість …
 * відсутня»); the signature blocks «Від кооперативу» (the treasurer's name or an empty line) and «Член кооперативу»;
 * the footer. Pure: the same content gives the same bytes (the file's identity is the content, its date the start of
 * the period's last day in Kyiv), so the same plot and period give the same act while the ledger stays the same. Holds
 * personal data (the owners, the treasurer): never log it.
 */
final class ReconciliationActLayout {

    private static final float TITLE = 14f;
    private static final float BODY = 10f;
    private static final float TABLE = 9f;
    private static final float SMALL = 7.5f;

    private ReconciliationActLayout() {
    }

    /**
     * A balance at one end of the period.
     *
     * @param asOf       the day it is counted on (the day before the period, or its last day)
     * @param debt       the debt as of the day
     * @param prepayment the prepayment as of the day
     */
    record Balance(LocalDate asOf, Money debt, Money prepayment) {

        Balance {
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(debt, "debt");
            Objects.requireNonNull(prepayment, "prepayment");
        }

        /** The debt minus the prepayment: above zero a debt, below zero an overpayment. */
        Money net() {
            return debt.minus(prepayment);
        }
    }

    /**
     * One line of the table.
     *
     * @param date        the day of the row
     * @param description what it is, already named («Нарахування: Членський внесок, жовтень 2026»)
     * @param charged     the amount in «Нараховано»; null for a payment row
     * @param paid        the amount in «Сплачено»; null for a charge row
     */
    record Line(LocalDate date, String description, @Nullable Money charged, @Nullable Money paid) {

        Line {
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(description, "description");
        }
    }

    /**
     * What the act shows.
     *
     * @param cooperative   the requisites block
     * @param plotNumber    the plot's number
     * @param street        the plot's street; null or blank when none
     * @param owners        the current owners' full names, in the order of the plot's owners
     * @param from          the first day of the period
     * @param to            the last day of the period
     * @param opening       the balance as of the day before {@code from}
     * @param lines         the rows of the period by date
     * @param charged       the turnover «Нараховано»
     * @param paid          the turnover «Сплачено»
     * @param closing       the balance as of {@code to}
     * @param treasurerName the first active holder of the treasurer base role; null without one
     */
    record Content(ReceiptContent.Requisites cooperative, String plotNumber, @Nullable String street,
            List<String> owners, LocalDate from, LocalDate to, Balance opening, List<Line> lines, Money charged,
            Money paid, Balance closing, @Nullable String treasurerName) {

        Content {
            Objects.requireNonNull(cooperative, "cooperative");
            Objects.requireNonNull(plotNumber, "plotNumber");
            owners = List.copyOf(owners);
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(opening, "opening");
            lines = List.copyOf(lines);
            Objects.requireNonNull(charged, "charged");
            Objects.requireNonNull(paid, "paid");
            Objects.requireNonNull(closing, "closing");
        }
    }

    /** The PDF of the act, dated the start of the period's last day in Kyiv. */
    static byte[] render(Content content) {
        DocumentTexts texts = DocumentTexts.uk();
        byte[] identity = content.toString().getBytes(StandardCharsets.UTF_8);
        Instant time = content.to().atStartOfDay(DocumentFormat.ZONE).toInstant();
        try (PdfDocument pdf = PdfDocument.create(PageSize.A4, identity, time)) {
            draw(pdf, content, texts);
            return pdf.toBytes();
        }
    }

    private static void draw(PdfDocument pdf, Content content, DocumentTexts texts) {
        ReceiptLayout.requisites(pdf, content.cooperative(), texts);
        pdf.rule();
        pdf.text(TextBlock.strong(texts.text("reconciliationAct.title"), TITLE, Align.CENTER));
        pdf.text(new TextBlock(texts.text("reconciliationAct.period", Map.of(
                "from", DocumentFormat.day(content.from()), "to", DocumentFormat.day(content.to()))),
                Face.REGULAR, BODY, Align.CENTER, BODY));

        pdf.text(TextBlock.strong(content.street() == null || content.street().isBlank()
                ? texts.text("reconciliationAct.plot", Map.of("number", content.plotNumber()))
                : texts.text("reconciliationAct.plotStreet", Map.of("number", content.plotNumber(),
                        "street", content.street())), BODY, Align.LEFT));
        pdf.text(TextBlock.plain(content.owners().isEmpty() ? texts.text("reconciliationAct.noOwners")
                : texts.text("reconciliationAct.owners", Map.of("owners", String.join(", ", content.owners()))),
                BODY));
        pdf.space(BODY * 0.6f);

        pdf.table(table(content, texts));
        pdf.text(TextBlock.plain(balance("reconciliationAct.openingBalance", content.opening(), texts), BODY));
        pdf.text(TextBlock.plain(balance("reconciliationAct.closingBalance", content.closing(), texts), BODY));
        pdf.space(BODY * 0.4f);
        pdf.text(TextBlock.strong(result(content.closing(), texts), 12f, Align.LEFT));

        pdf.space(BODY * 1.5f);
        pdf.signature(content.treasurerName() == null ? texts.text("reconciliationAct.cooperativeUnknown")
                : texts.text("reconciliationAct.cooperative", Map.of("name", content.treasurerName())),
                texts.text("reconciliationAct.signature"), BODY);
        pdf.space(BODY);
        pdf.signature(texts.text("reconciliationAct.member"), texts.text("reconciliationAct.signature"), BODY);

        pdf.space(BODY);
        pdf.rule();
        pdf.text(new TextBlock(texts.text("reconciliationAct.footer"), Face.REGULAR, SMALL, Align.LEFT, 1f));
    }

    private static Table table(Content content, DocumentTexts texts) {
        List<Row> rows = new ArrayList<>();
        rows.add(balanceRow(texts.text("reconciliationAct.opening"), content.opening().net(), false));
        for (Line line : content.lines()) {
            rows.add(Row.of(DocumentFormat.day(line.date()), line.description(),
                    line.charged() == null ? "" : DocumentFormat.money(line.charged()),
                    line.paid() == null ? "" : DocumentFormat.money(line.paid())));
        }
        rows.add(Row.total("", texts.text("reconciliationAct.turnover"), DocumentFormat.money(content.charged()),
                DocumentFormat.money(content.paid())));
        rows.add(balanceRow(texts.text("reconciliationAct.closing"), content.closing().net(), true));
        return new Table(List.of(
                new Column(texts.text("reconciliationAct.columns.date"), 2, Align.LEFT),
                new Column(texts.text("reconciliationAct.columns.document"), 6, Align.LEFT),
                new Column(texts.text("reconciliationAct.columns.charged"), 2, Align.RIGHT),
                new Column(texts.text("reconciliationAct.columns.paid"), 2, Align.RIGHT)), rows, TABLE);
    }

    /** A balance row: a debt under «Нараховано», an overpayment under «Сплачено», nothing as 0,00 under the first. */
    private static Row balanceRow(String label, Money net, boolean total) {
        String debit = net.signum() >= 0 ? DocumentFormat.money(net) : "";
        String credit = net.signum() < 0 ? DocumentFormat.money(net.negate()) : "";
        return total ? Row.total("", label, debit, credit) : Row.of("", label, debit, credit);
    }

    /** «Сальдо на початок періоду (станом на 31.08.2026): заборгованість 200,00 ₴, аванс 0,00 ₴». */
    private static String balance(String key, Balance balance, DocumentTexts texts) {
        return texts.text(key, Map.of("date", DocumentFormat.day(balance.asOf()),
                "debt", DocumentFormat.money(balance.debt()),
                "prepayment", DocumentFormat.money(balance.prepayment())));
    }

    /** The closing sentence by the net balance at the end: a debt, an overpayment or none. */
    static String result(Balance closing, DocumentTexts texts) {
        Money net = closing.net();
        String date = DocumentFormat.day(closing.asOf());
        if (net.signum() == 0) {
            return texts.text("reconciliationAct.result.NONE", Map.of("date", date));
        }
        return net.signum() > 0
                ? texts.text("reconciliationAct.result.DEBT", Map.of("date", date, "amount", DocumentFormat.money(net)))
                : texts.text("reconciliationAct.result.OVERPAYMENT", Map.of("date", date,
                        "amount", DocumentFormat.money(net.negate())));
    }
}
