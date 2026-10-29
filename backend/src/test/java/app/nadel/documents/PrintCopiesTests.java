package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.documents.internal.PrintBundleHook;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * The paper set of a month with paper recipients (feature 017 §2.2, §2.3; the notifications module's
 * {@link PaperRecipients}): one copy per owner who gets paper by the channel rule (paper chosen, Viber, Telegram without
 * a link and without an e-mail), each named «Примірник для: …» and, with the cooperative's bot, with a small QR code
 * «Отримувати рахунки в Telegram» holding the owner's own 90-day link code; a linked owner's copy has no code, an
 * e-mail owner gets no copy. Without an implementation the set is the feature 016 one.
 */
class PrintCopiesTests extends DocumentsTestSupport {

    private static final String BOT = "coop_print_bot";

    @Autowired
    ApplicationContext context;

    private UUID paper;
    private UUID unlinkedTelegram;
    private UUID viber;
    private UUID linkedPaper;

    /**
     * Садова 2 shared by an e-mail owner, a paper owner and a Telegram owner without a link or an e-mail; Садова 3 of a
     * Viber owner; Садова 4 of a paper owner whose Telegram is linked; Садова 5 of the e-mail owner only. The
     * cooperative has a mailbox and a bot.
     */
    @BeforeEach
    void plots() {
        owner().update("insert into delivery_settings (tenant_id, smtp_host, smtp_port, smtp_from_address, "
                + "bot_token_enc, bot_token_last4, bot_username) values (?, 'smtp.documents.test', 587, "
                + "'board@documents.test', '\\x0102'::bytea, '1234', ?)", tenant, BOT);
        paper = memberId; // the default channel is PAPER
        UUID email = memberNamed("Іваненко", "Ігор", "Олегович");
        deliveryChannel(email, "EMAIL");
        unlinkedTelegram = memberNamed("Петренко", "Петро", "Петрович");
        insertContacts(tenant, unlinkedTelegram, null);
        deliveryChannel(unlinkedTelegram, "TELEGRAM");
        viber = memberNamed("Сидоренко", "Сидір", "Сидорович");
        setContact(tenant, viber, "viber_phone", "+380501234567");
        deliveryChannel(viber, "VIBER");
        linkedPaper = memberNamed("Лінкований", "Лука", "Лукич");
        owner().update("insert into telegram_link (tenant_id, member_id, chat_id_enc, chat_id_bidx) "
                + "values (?, ?, '\\x01'::bytea, '\\x02'::bytea)", tenant, linkedPaper);

        String month = currentMonth();
        UUID shared = plot("2", "Садова");
        for (UUID owner : List.of(email, paper, unlinkedTelegram)) {
            owner().update("insert into ownership (id, tenant_id, plot_id, member_id, share_numerator, "
                    + "share_denominator, valid_from) values (?, ?, ?, ?, 1, 3, date '2020-01-01')",
                    UUID.randomUUID(), tenant, shared, owner);
        }
        UUID viberPlot = plot("3", "Садова");
        own(viber, viberPlot);
        UUID linkedPlot = plot("4", "Садова");
        own(linkedPaper, linkedPlot);
        UUID emailPlot = plot("5", "Садова");
        own(email, emailPlot);
        for (UUID plot : List.of(shared, viberPlot, linkedPlot, emailPlot)) {
            charge(plot, "MEMBERSHIP", "75.00", month);
        }
    }

    @Test
    void eachPaperRecipientGetsACopyWithTheirOwnLinkCode() throws Exception {
        built(currentMonth(), false);

        byte[] pdf = printMonth(treasurer, currentMonth(), "PAPER").andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsByteArray();
        List<String> pages = pages(pdf);

        // Садова 2: the paper owner and the unlinked Telegram owner (the co-owners in their order); Садова 3: Viber;
        // Садова 4: the linked owner
        assertThat(pages).hasSize(4);
        assertThat(pages.subList(0, 2)).allSatisfy(page -> assertThat(page).contains("ділянка № 2",
                "Отримувати рахунки в Telegram"));
        assertThat(pages.get(0) + pages.get(1)).contains("Примірник для: Шевченко Тарас",
                "Примірник для: Петренко Петро");
        assertThat(pages.get(2)).contains("ділянка № 3", "Примірник для: Сидоренко Сидір",
                "Отримувати рахунки в Telegram");
        assertThat(pages.get(3)).contains("ділянка № 4", "Примірник для: Лінкований Лука")
                .doesNotContain("Отримувати рахунки в Telegram");
        assertThat(String.join("\n", pages)).doesNotContain("Примірник для: Іваненко").doesNotContain("ділянка № 5");

        Map<String, UUID> holders = Map.of("Шевченко Тарас", paper, "Петренко Петро", unlinkedTelegram,
                "Сидоренко Сидір", viber);
        List<String> links = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            String link = linkQr(pdf, page).orElseThrow();
            assertThat(link).matches("https://t\\.me/" + BOT + "\\?start=[A-Za-z0-9_-]{22}");
            links.add(link);
            String code = link.substring(link.indexOf("start=") + 6);
            Map<String, Object> stored = owner().queryForMap("select member_id, purpose, used_at, expires_at - "
                    + "created_at as validity from telegram_link_code where code_hash = ?", sha256(code));
            String text = pages.get(page);
            UUID holder = holders.entrySet().stream().filter(e -> text.contains("Примірник для: " + e.getKey()))
                    .map(Map.Entry::getValue).findFirst().orElseThrow();
            assertThat(stored.get("member_id")).isEqualTo(holder);
            assertThat(stored.get("purpose")).isEqualTo("PAPER");
            assertThat(stored.get("used_at")).isNull();
            assertThat(stored.get("validity").toString()).contains("90 days");
        }
        assertThat(links).doesNotHaveDuplicates();
        assertThat(linkQr(pdf, 3)).isEmpty();
        assertThat(owner().queryForObject("select count(*) from telegram_link_code where tenant_id = ?",
                Integer.class, tenant)).isEqualTo(3);
    }

    @Test
    void withoutPaperRecipientsTheSetIsOneCopyPerPlotWithAPaperOwner() throws Exception {
        built(currentMonth(), false);

        List<String> pages = pages(PrintBundleHook.printWithoutRecipients(context, tenant,
                YearMonth.parse(currentMonth())));

        // feature 016: the plots with a current owner who chose paper (Садова 2 and Садова 4), no names, no codes
        assertThat(pages).hasSize(2);
        assertThat(pages.get(0)).contains("ділянка № 2");
        assertThat(pages.get(1)).contains("ділянка № 4");
        assertThat(String.join("\n", pages)).doesNotContain("Примірник для").doesNotContain("Telegram");
        assertThat(owner().queryForObject("select count(*) from telegram_link_code where tenant_id = ?",
                Integer.class, tenant)).isZero();
    }

    @Test
    void allPrintsOneCopyPerPlotWithoutNamesOrCodes() throws Exception {
        built(currentMonth(), false);

        List<String> pages = pages(printMonth(treasurer, currentMonth(), "ALL").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());

        assertThat(pages).hasSize(4);
        assertThat(String.join("\n", pages)).doesNotContain("Примірник для");
        assertThat(owner().queryForObject("select count(*) from telegram_link_code where tenant_id = ?",
                Integer.class, tenant)).isZero();
    }

    /** The text of each page, no-break spaces as spaces. */
    private static List<String> pages(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            List<String> pages = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(stripper.getText(document).replace(' ', ' '));
            }
            return pages;
        }
    }

    /** The Telegram link of the page's small QR code, decoded from the rendered page; empty without one. */
    private static Optional<String> linkQr(byte[] pdf, int page) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            BufferedImage image = new PDFRenderer(document).renderImageWithDPI(page, 300, ImageType.GRAY);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
            Result[] found;
            try {
                found = new QRCodeMultiReader().decodeMultiple(bitmap, Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE));
            } catch (NotFoundException e) {
                return Optional.empty();
            }
            for (Result result : found) {
                if (result.getText().startsWith("https://t.me/")) {
                    return Optional.of(result.getText());
                }
            }
            return Optional.empty();
        }
    }

    private static byte[] sha256(String code) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.US_ASCII));
    }
}
