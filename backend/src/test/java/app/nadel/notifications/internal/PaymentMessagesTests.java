package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.documents.DocumentFormats;
import app.nadel.ledger.PaymentRecorded;
import app.nadel.notifications.FakeBotApi.SentDocument;
import app.nadel.notifications.Mailpit;
import app.nadel.tenancy.TenantContext;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payment and reversal messages (feature 017 §2.4) end to end: a payment saved or reversed through the API publishes
 * the ledger event, {@link PaymentMessages} queues one delivery per owner of the plot who gets messages by Telegram or
 * e-mail, and the dispatcher sends «Отримано … Борг: …» (or «Аванс: …») with the cash receipt for cash, and «Платіж
 * від … сторновано. Борг: …» after a reversal; nothing while «Повідомляти про платежі» is off or for paper owners; a
 * redelivered event sends nothing again; the outbox carries ids only.
 */
class PaymentMessagesTests extends DeliveryTestSupport {

    private static final String NBSP = " ";

    @Autowired
    ApplicationEventPublisher publisher;

    @Test
    void aCashPaymentGoesByTelegramWithTheReceiptAndTheDebt() throws Exception {
        bot();
        UUID owner = member("Телеграм", "TELEGRAM", null);
        long chat = link(owner);
        UUID plot = plot("9", owner);
        charge(plot, "1734.56");

        UUID payment = pay(plot, "CASH", "500.00").getFirst();

        Map<String, Object> row = awaitDeliveries(1).getFirst();
        assertThat(row.get("kind")).isEqualTo("PAYMENT");
        assertThat(row.get("payment_id")).isEqualTo(payment);
        assertThat(row.get("invoice_id")).isNull();
        assertThat(row.get("member_id")).isEqualTo(owner);
        assertThat(row.get("channel")).isEqualTo("TELEGRAM");
        assertThat(row.get("status")).isEqualTo("QUEUED");
        assertThat(row.get("billing_month")).isEqualTo(month().atDay(1));

        assertThat(dispatch("TELEGRAM").sent()).isEqualTo(1);

        SentDocument document = telegram.documents(chat).getFirst();
        assertThat(document.caption()).isEqualTo("Отримано 500,00" + NBSP + "₴ по ділянці 9 (" + today()
                + "). Борг: 1" + NBSP + "234,56" + NBSP + "₴");
        assertThat(document.buttons()).isEmpty();
        assertThat(document.fileName()).startsWith("receipt_").endsWith(".pdf");
        assertThat(pdfText(document.content())).contains("Квитанція");
        assertThat(telegram.sent(chat)).isEmpty();
        assertThat(deliveriesOf(owner)).singleElement().satisfies(sent -> assertThat(sent.get("status"))
                .isEqualTo("SENT"));
    }

    @Test
    void aCashPaymentGoesByEmailWithTheReceiptAttached() throws Exception {
        mailbox(Mailpit.PASSWORD);
        UUID owner = member("Пошта", "EMAIL", "payer@delivery.test");
        UUID plot = plot("10", owner);
        charge(plot, "800.00");

        pay(plot, "CASH", "300.00");
        awaitDeliveries(1);

        assertThat(dispatch("EMAIL").sent()).isEqualTo(1);

        Mailpit.Letter letter = mailpit.lettersTo("payer@delivery.test").getFirst();
        assertThat(letter.subject()).isEqualTo("Платіж по ділянці 10 отримано");
        assertThat(letter.fromName()).isEqualTo("СТ «Розсилка»");
        assertThat(mailpit.text(letter.id())).contains(
                "Отримано 300,00" + NBSP + "₴ по ділянці 10 (" + today() + "). Борг: 500,00" + NBSP + "₴",
                "Квитанція у вкладенні (PDF).", "Правління СТ «Розсилка»");
        assertThat(mailpit.attachments(letter.id())).singleElement()
                .satisfies(name -> assertThat(name).startsWith("receipt_").endsWith(".pdf"));
    }

    @Test
    void aBankPaymentHasNoAttachmentAndShowsThePrepaymentWhenNothingIsOwed() throws Exception {
        mailbox(Mailpit.PASSWORD);
        bot();
        UUID onTelegram = member("Телеграм", "TELEGRAM", null);
        UUID onEmail = member("Пошта", "EMAIL", "co-owner@delivery.test");
        long chat = link(onTelegram);
        UUID plot = plot("11", onTelegram, onEmail);
        charge(plot, "100.00");

        pay(plot, "BANK", "600.00");
        awaitDeliveries(2);
        dispatch("TELEGRAM");
        dispatch("EMAIL");

        String expected = "Отримано 600,00" + NBSP + "₴ по ділянці 11 (" + today() + "). Аванс: 500,00" + NBSP
                + "₴";
        assertThat(telegram.sent(chat)).singleElement().satisfies(sent -> {
            assertThat(sent.text()).isEqualTo(expected);
            assertThat(sent.buttons()).isEmpty();
        });
        assertThat(telegram.documents(chat)).isEmpty();
        Mailpit.Letter letter = mailpit.lettersTo("co-owner@delivery.test").getFirst();
        assertThat(mailpit.text(letter.id())).contains(expected).doesNotContain("вкладенні");
        assertThat(mailpit.attachments(letter.id())).isEmpty();
    }

    @Test
    void aReversalSaysWhichPaymentWasReversedAndTheNewDebt() throws Exception {
        bot();
        UUID owner = member("Телеграм", "TELEGRAM", null);
        long chat = link(owner);
        UUID plot = plot("9", owner);
        charge(plot, "1734.56");
        UUID payment = pay(plot, "CASH", "500.00").getFirst();
        awaitDeliveries(1);
        dispatch("TELEGRAM");

        send(chairman, post("/api/v1/payments/" + payment + "/reverse"), Map.of("reason", "Помилкова ділянка"))
                .andExpect(status().isCreated());

        Map<String, Object> reversal = awaitDeliveries(2).get(1);
        assertThat(reversal.get("kind")).isEqualTo("REVERSAL");
        assertThat(reversal.get("payment_id")).isEqualTo(payment);
        // the test's clock stopped at the first dispatch; the reversal was queued by the real clock after it
        clock.advance(Duration.ofMinutes(1));
        assertThat(dispatch("TELEGRAM").sent()).isEqualTo(1);
        assertThat(telegram.sent(chat)).singleElement().satisfies(sent -> assertThat(sent.text()).isEqualTo(
                "Платіж від " + today() + " на 500,00" + NBSP + "₴ сторновано. Борг: 1" + NBSP + "734,56" + NBSP
                        + "₴"));
        assertThat(telegram.documents(chat)).hasSize(1);
    }

    @Test
    void nothingIsSentWhileTheSwitchIsOff() throws Exception {
        mailbox(Mailpit.PASSWORD);
        bot();
        UUID onTelegram = member("Телеграм", "TELEGRAM", null);
        UUID onEmail = member("Пошта", "EMAIL", "off@delivery.test");
        link(onTelegram);
        UUID plot = plot("9", onTelegram, onEmail);
        charge(plot, "100.00");
        send(chairman, put("/api/v1/settings/delivery"), Map.of("notifyPayments", false)).andExpect(status().isOk());

        UUID payment = pay(plot, "CASH", "50.00").getFirst();
        awaitListeners();
        send(chairman, post("/api/v1/payments/" + payment + "/reverse"), Map.of("reason", "Помилка"))
                .andExpect(status().isCreated());
        awaitListeners();

        assertThat(deliveries()).isEmpty();
    }

    @Test
    void aPaperOwnerGetsNoPaymentMessage() throws Exception {
        bot();
        UUID onTelegram = member("Телеграм", "TELEGRAM", null);
        UUID onPaper = member("Папір", "PAPER", null);
        link(onTelegram);
        UUID plot = plot("9", onTelegram, onPaper);
        charge(plot, "100.00");

        pay(plot, "CASH", "50.00");
        awaitDeliveries(1);
        awaitListeners();

        assertThat(deliveries()).singleElement().satisfies(row -> {
            assertThat(row.get("member_id")).isEqualTo(onTelegram);
            assertThat(row.get("channel")).isEqualTo("TELEGRAM");
        });
        assertThat(deliveriesOf(onPaper)).isEmpty();
    }

    @Test
    void aRedeliveredEventCreatesNoSecondRowAndSendsOnce() throws Exception {
        bot();
        UUID owner = member("Телеграм", "TELEGRAM", null);
        long chat = link(owner);
        UUID plot = plot("9", owner);
        charge(plot, "100.00");
        List<UUID> payments = pay(plot, "CASH", "50.00");
        awaitDeliveries(1);
        assertThat(dispatch("TELEGRAM").sent()).isEqualTo(1);

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        TenantContext.runAs(tenant, () -> transaction.execute(status -> {
            publisher.publishEvent(new PaymentRecorded(tenant, payments));
            return null;
        }));
        awaitListeners();
        int again = paymentMessages.plan(tenant, payments, DeliveryRows.PAYMENT);

        assertThat(again).isZero();
        assertThat(deliveries()).hasSize(1);
        clock.advance(Duration.ofMinutes(1));
        assertThat(dispatch("TELEGRAM").sent()).isZero();
        assertThat(telegram.documents(chat)).hasSize(1);
    }

    @Test
    void theOutboxCarriesIdsOnly() throws Exception {
        String table = "outbox_copy_" + UUID.randomUUID().toString().replace("-", "");
        ownerJdbc().execute("create table " + table + " (event_type text, serialized_event text)");
        ownerJdbc().execute("create function " + table + "() returns trigger language plpgsql security definer as $$ "
                + "begin insert into " + table + " values (new.event_type, new.serialized_event); return new; end; $$");
        ownerJdbc().execute("create trigger " + table + " after insert on event_publication for each row "
                + "execute function " + table + "()");
        try {
            bot();
            UUID owner = member("Шевчук", "TELEGRAM", "shevchuk@delivery.test");
            link(owner);
            UUID plot = plot("9", owner);
            charge(plot, "1734.56");
            UUID payment = pay(plot, "CASH", "512.34").getFirst();
            awaitDeliveries(1);
            send(chairman, post("/api/v1/payments/" + payment + "/reverse"), Map.of("reason", "Помилкова ділянка"))
                    .andExpect(status().isCreated());
            awaitDeliveries(2);

            List<String> events = ownerJdbc().queryForList("select serialized_event from " + table
                    + " where event_type like '%PaymentRecorded' or event_type like '%PaymentReversed'", String.class);
            assertThat(events).hasSize(2).allSatisfy(event -> assertThat(event)
                    .contains(tenant.toString(), payment.toString())
                    .doesNotContain("512", "Шевчук", "shevchuk", "Отримано", "сторновано", "Помилкова", "Тест"));
        } finally {
            ownerJdbc().execute("drop trigger if exists " + table + " on event_publication");
            ownerJdbc().execute("drop function if exists " + table + "()");
            ownerJdbc().execute("drop table if exists " + table);
        }
    }

    // ---- helpers

    /** Saves a payment of the plot received today as the treasurer; the ids of its payments. */
    private List<UUID> pay(UUID plot, String source, String amount) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plotId", plot.toString());
        body.put("source", source);
        body.put("amount", amount);
        body.put("receivedOn", LocalDate.now(ZoneId.of("Europe/Kyiv")).toString());
        List<UUID> ids = new ArrayList<>();
        body(send(treasurer, post("/api/v1/payments?preview=false"), body).andExpect(status().isCreated()))
                .get("payments").forEach(saved -> ids.add(UUID.fromString(saved.get("id").asString())));
        return ids;
    }

    /** Waits until the cooperative has the deliveries; them with kind, payment and month, oldest first. */
    private List<Map<String, Object>> awaitDeliveries(int count) {
        await().atMost(Duration.ofSeconds(10)).until(() -> paymentDeliveries().size() >= count);
        return paymentDeliveries();
    }

    private List<Map<String, Object>> paymentDeliveries() {
        return ownerJdbc().queryForList("select id, kind, invoice_id, payment_id, billing_month, member_id, channel, "
                + "status from delivery where tenant_id = ? order by created_at, id", tenant).stream()
                .map(row -> {
                    Map<String, Object> copy = new LinkedHashMap<>(row);
                    Object month = row.get("billing_month");
                    copy.put("billing_month", month instanceof java.sql.Date date ? date.toLocalDate() : month);
                    return copy;
                }).toList();
    }

    /** Waits until every listener of the cooperative's events ran (completed publications are deleted). */
    private void awaitListeners() {
        await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                "select count(*) from event_publication where completion_date is null and serialized_event like ?",
                Integer.class, "%" + tenant + "%") == 0);
    }

    private static String today() {
        return DocumentFormats.day(LocalDate.now(ZoneId.of("Europe/Kyiv")));
    }

    private static String pdfText(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }
}
