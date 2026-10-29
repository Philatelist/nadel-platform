package app.nadel.documents.internal;

import app.nadel.documents.internal.pdf.PdfDocument;
import app.nadel.documents.internal.pdf.PdfDocument.PageSize;
import app.nadel.documents.internal.pdf.PdfFonts.Face;
import app.nadel.documents.internal.pdf.Table;
import app.nadel.documents.internal.pdf.Table.Column;
import app.nadel.documents.internal.pdf.Table.Row;
import app.nadel.documents.internal.pdf.TextBlock;
import app.nadel.documents.internal.pdf.TextBlock.Align;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The page of a debt certificate (feature 016 §2.7), A4, drawn from a {@link CertificateDocument}: the requisites;
 * «Довідка № Д-{YYYY}-{N}» «про стан розрахунків за ділянкою»; the day of issue and «Станом на {день}»; «АНУЛЬОВАНО
 * {день}. Причина: …» in a frame when annulled; the plot with its street, its area in m² and сотки and its owners with
 * their shares; the table of the debt per charge type with «Разом»; the prepayment; «Заборгованість відсутня» when the
 * total is 0,00, otherwise «Заборгованість становить {сума}»; the issuer's role and name with a signature line and
 * «М. П.» (the place for the stamp); the footer. Pure: the same certificate in the same state gives the same bytes (the
 * file's identity is the certificate's whole content with the annulment, its dates the moment of issue, or of the
 * annulment for an annulled certificate).
 */
final class CertificateLayout {

    private static final float TITLE = 14f;
    private static final float BODY = 10f;
    private static final float TABLE = 9.5f;
    private static final float SMALL = 7.5f;

    private CertificateLayout() {
    }

    /** The PDF of the certificate. */
    static byte[] render(CertificateDocument certificate) {
        DocumentTexts texts = DocumentTexts.uk();
        byte[] identity = CertificateSnapshots.identity(certificate).getBytes(StandardCharsets.UTF_8);
        Instant time = certificate.annulment() == null ? certificate.issuedAt() : certificate.annulment().at();
        try (PdfDocument pdf = PdfDocument.create(PageSize.A4, identity, time)) {
            draw(pdf, certificate, texts);
            return pdf.toBytes();
        }
    }

    private static void draw(PdfDocument pdf, CertificateDocument certificate, DocumentTexts texts) {
        ReceiptLayout.requisites(pdf, certificate.cooperative(), texts);
        pdf.rule();
        pdf.text(TextBlock.strong(texts.text("certificate.title", Map.of("number", certificate.number())), TITLE,
                Align.CENTER));
        pdf.text(new TextBlock(texts.text("certificate.subtitle"), Face.REGULAR, BODY, Align.CENTER, BODY * 0.6f));
        pdf.text(new TextBlock(texts.text("certificate.issuedOn", Map.of("date",
                DocumentFormat.day(certificate.issuedOn()))), Face.REGULAR, BODY, Align.CENTER, 1f));
        pdf.text(new TextBlock(texts.text("certificate.asOf", Map.of("date", DocumentFormat.day(certificate.asOf()))),
                Face.REGULAR, BODY, Align.CENTER, BODY));
        if (certificate.annulment() != null) {
            pdf.mark(texts.text("certificate.annulled", Map.of(
                    "date", DocumentFormat.day(certificate.annulment().at()),
                    "reason", certificate.annulment().reason())), TITLE);
        }

        CertificateDocument.Plot plot = certificate.plot();
        pdf.text(TextBlock.strong(plot.street() == null || plot.street().isBlank()
                ? texts.text("certificate.plot", Map.of("number", plot.number()))
                : texts.text("certificate.plotStreet", Map.of("number", plot.number(), "street", plot.street())),
                BODY, Align.LEFT));
        pdf.text(TextBlock.plain(texts.text("certificate.area", Map.of(
                "m2", DocumentFormat.decimal(plot.areaM2().toPlainString(), 2),
                "sotky", DocumentFormat.decimal(sotky(plot.areaM2()), 2))), BODY));
        if (certificate.owners().isEmpty()) {
            pdf.text(TextBlock.plain(texts.text("certificate.noOwners"), BODY));
        } else {
            List<String> owners = certificate.owners().stream().map(owner -> texts.text("certificate.owner",
                    Map.of("name", owner.name(), "share", owner.share()))).toList();
            pdf.text(TextBlock.plain(texts.text("certificate.owners", Map.of("owners", String.join(", ", owners))),
                    BODY));
        }
        pdf.space(BODY * 0.6f);

        pdf.table(debts(certificate, texts));
        pdf.text(TextBlock.plain(texts.text("certificate.prepayment", Map.of("amount",
                DocumentFormat.money(certificate.prepayment()))), BODY));
        pdf.space(BODY * 0.4f);
        pdf.text(TextBlock.strong(certificate.inDebt()
                ? texts.text("certificate.debt", Map.of("amount", DocumentFormat.money(certificate.totalDebt())))
                : texts.text("certificate.noDebt"), 12f, Align.LEFT));

        pdf.space(BODY * 1.5f);
        CertificateDocument.Issuer issuer = certificate.issuer();
        pdf.signature(texts.text("certificate.issuer", Map.of("role", issuer.role(), "name", issuer.name())),
                texts.text("certificate.signature"), BODY);
        pdf.space(BODY);
        pdf.text(TextBlock.plain(texts.text("certificate.stamp"), BODY));

        pdf.space(BODY);
        pdf.rule();
        pdf.text(new TextBlock(texts.text("certificate.footer.generated", Map.of("time",
                DocumentFormat.time(certificate.issuedAt()))), Face.REGULAR, SMALL, Align.LEFT, 1f));
    }

    private static Table debts(CertificateDocument certificate, DocumentTexts texts) {
        List<Row> rows = new ArrayList<>();
        for (CertificateDocument.TypeDebt type : certificate.types()) {
            rows.add(Row.of(type.name(), DocumentFormat.money(type.debt())));
        }
        rows.add(Row.total(texts.text("certificate.total"), DocumentFormat.money(certificate.totalDebt())));
        return new Table(List.of(
                new Column(texts.text("certificate.columns.type"), 8, Align.LEFT),
                new Column(texts.text("certificate.columns.debt"), 3, Align.RIGHT)), rows, TABLE);
    }

    /** The area in сотки (100 m² each), exact: the decimal point moved two places, nothing rounded. */
    static String sotky(BigDecimal areaM2) {
        return areaM2.movePointLeft(2).toPlainString();
    }
}
