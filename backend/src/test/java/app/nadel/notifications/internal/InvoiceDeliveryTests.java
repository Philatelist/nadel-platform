package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.notifications.FakeBotApi;
import app.nadel.notifications.FakeBotApi.SentDocument;
import app.nadel.notifications.Mailpit;
import app.nadel.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;

/**
 * Sending the month's invoices (feature 017 §2.3, §2.4) through the cooperative's mailbox (Mailpit) and bot (the
 * in-JVM fake Bot API): the preview, the send with its texts, the PDF and «Оплатити», no second copy of the same
 * invoice, co-owners and owners of two plots, invoices with nothing due, Viber, every failure code with the attempts
 * and pauses (the clock moved by the test), the retry and «Роздруковано», the list and the sweep.
 */
class InvoiceDeliveryTests extends DeliveryTestSupport {

    @Autowired
    BotSupervisor supervisor;

    @Test
    void thePreviewCountsPerChannelAndWritesNothing() throws Exception {
        mailbox(Mailpit.PASSWORD);
        bot();
        UUID onTelegram = member("Телеграм", "TELEGRAM", "tg@delivery.test");
        UUID onEmail = member("Пошта", "EMAIL", "mail@delivery.test");
        UUID onPaper = member("Папір", "PAPER", null);
        link(onTelegram);
        charge(plot("9", onTelegram), "413.26");
        charge(plot("10", onEmail), "100.00");
        charge(plot("11", onPaper), "50.00");
        buildInvoices();

        JsonNode preview = sentMonth(true, false);

        assertThat(preview.path("counts").path("telegram").asInt()).isEqualTo(1);
        assertThat(preview.path("counts").path("email").asInt()).isEqualTo(1);
        assertThat(preview.path("counts").path("paper").asInt()).isEqualTo(1);
        assertThat(preview.path("queued").asInt()).isZero();
        assertThat(deliveries()).isEmpty();
    }

    @Test
    void sendingQueuesAndDeliversByTelegramAndEmailWithThePdfAndThePaymentButton() throws Exception {
        mailbox(Mailpit.PASSWORD);
        bot();
        UUID onTelegram = member("Телеграм", "TELEGRAM", "tg@delivery.test");
        UUID onEmail = member("Пошта", "EMAIL", "mail@delivery.test");
        UUID onPaper = member("Папір", "PAPER", null);
        long chat = link(onTelegram);
        charge(plot("9", onTelegram), "413.26");
        charge(plot("10", onEmail), "1234.56");
        charge(plot("11", onPaper), "50.00");
        buildInvoices();

        JsonNode sent = sentMonth(false, false);

        assertThat(sent.path("counts").path("telegram").asInt()).isEqualTo(1);
        assertThat(sent.path("counts").path("email").asInt()).isEqualTo(1);
        assertThat(sent.path("counts").path("paper").asInt()).isEqualTo(1);
        assertThat(sent.path("queued").asInt()).isEqualTo(2);
        assertThat(deliveries()).extracting(row -> row.get("status"))
                .containsExactlyInAnyOrder("QUEUED", "QUEUED", "TO_PRINT");

        assertThat(dispatch("TELEGRAM").sent()).isEqualTo(1);
        assertThat(dispatch("EMAIL").sent()).isEqualTo(1);

        assertThat(deliveriesOf(onTelegram)).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("SENT");
            assertThat(row.get("attempts")).isEqualTo(1);
            assertThat(row.get("sent_at")).isNotNull();
        });
        assertThat(deliveriesOf(onPaper)).singleElement()
                .satisfies(row -> assertThat(row.get("status")).isEqualTo("TO_PRINT"));
        String month = app.nadel.documents.DocumentFormats.month(month());
        SentDocument document = telegram.documents(chat).getFirst();
        assertThat(document.caption()).isEqualTo("Рахунок за " + month + ", ділянка 9: до сплати 413,26\u00A0₴");
        assertThat(document.buttons()).singleElement().satisfies(button -> {
            assertThat(button.text()).isEqualTo("Оплатити");
            assertThat(button.url()).startsWith("https://qr.bank.gov.ua/");
        });
        assertThat(document.fileName()).endsWith(".pdf");
        assertThat(pdfText(document.content())).contains("ділянка № 9");

        Mailpit.Letter letter = mailpit.lettersTo("mail@delivery.test").getFirst();
        assertThat(letter.from()).isEqualTo("board@rozsylka.example");
        assertThat(letter.fromName()).isEqualTo("СТ «Розсилка»");
        assertThat(letter.subject()).isEqualTo("Рахунок за " + month + ", ділянка 10");
        assertThat(mailpit.text(letter.id())).contains(
                "Рахунок за " + month + ", ділянка 10: до сплати 1\u00A0234,56\u00A0₴",
                "Оплатити в застосунку банку: https://qr.bank.gov.ua/", "Правління СТ «Розсилка»");
        assertThat(mailpit.html(letter.id())).contains("<a href=\"https://qr.bank.gov.ua/", ">Оплатити</a>");
        assertThat(mailpit.attachments(letter.id())).singleElement()
                .satisfies(name -> assertThat(name).startsWith("invoice_").endsWith(".pdf"));
        assertThat(mailpit.letters()).hasSize(1);

        assertThat(sentAudit(1)).singleElement().satisfies(entry -> {
            assertThat(entry.get("entity_type")).isEqualTo("BILLING_MONTH");
            assertThat(entry.get("entity_label")).isEqualTo(month().toString());
            assertThat((String) entry.get("changes")).contains("\"telegram\"", "\"email\"", "\"paper\"")
                    .doesNotContain("@delivery.test").doesNotContain(Long.toString(chat));
        });
    }

    @Test
    void sendingTheMonthAgainAddsNothing() throws Exception {
        mailbox(Mailpit.PASSWORD);
        UUID onEmail = member("Пошта", "EMAIL", "mail@delivery.test");
        charge(plot("10", onEmail), "100.00");
        buildInvoices();
        sentMonth(false, false);
        dispatch("EMAIL");

        JsonNode again = sentMonth(false, false);
        JsonNode preview = sentMonth(true, false);

        assertThat(again.path("counts").path("email").asInt()).isZero();
        assertThat(again.path("queued").asInt()).isZero();
        assertThat(preview.path("counts").path("email").asInt()).isZero();
        assertThat(deliveries()).hasSize(1);
        dispatch("EMAIL");
        assertThat(mailpit.letters()).hasSize(1);
        assertThat(sentAudit(1)).hasSize(1);
    }

    @Test
    void coOwnersGetThePlotsInvoiceEachThroughTheirOwnChannel() throws Exception {
        bot();
        UUID onTelegram = member("Телеграм", "TELEGRAM", null);
        UUID onPaper = member("Папір", "PAPER", null);
        long chat = link(onTelegram);
        charge(plot("9", onTelegram, onPaper), "200.00");
        buildInvoices();

        JsonNode sent = sentMonth(false, false);
        dispatch("TELEGRAM");

        assertThat(sent.path("counts").path("telegram").asInt()).isEqualTo(1);
        assertThat(sent.path("counts").path("paper").asInt()).isEqualTo(1);
        assertThat(telegram.documents(chat)).hasSize(1);
        assertThat(deliveriesOf(onPaper)).singleElement()
                .satisfies(row -> assertThat(row.get("status")).isEqualTo("TO_PRINT"));
    }

    @Test
    void anOwnerOfTwoPlotsGetsTwoMessagesEachWithItsOwnPdfAtOneASecond() throws Exception {
        bot();
        UUID owner = member("Двоплотний", "TELEGRAM", null);
        long chat = link(owner);
        charge(plot("9", owner), "10.00");
        charge(plot("10", owner), "20.00");
        buildInvoices();

        sentMonth(false, false);
        dispatch("TELEGRAM");

        List<SentDocument> documents = telegram.documents(chat);
        assertThat(documents).hasSize(2);
        assertThat(documents).extracting(SentDocument::caption).anySatisfy(c -> assertThat(c).contains("ділянка 9:"))
                .anySatisfy(c -> assertThat(c).contains("ділянка 10:"));
        assertThat(pdfText(documents.get(0).content())).isNotEqualTo(pdfText(documents.get(1).content()));
        assertThat(pauses).contains(Duration.ofSeconds(1));
    }

    @Test
    void invoicesWithNothingDueGoOnlyWithTheOptionAndSayThankYou() throws Exception {
        bot();
        UUID owner = member("Чистий", "TELEGRAM", null);
        long chat = link(owner);
        plot("9", owner); // nothing charged: nothing due
        buildInvoices();

        JsonNode without = sentMonth(false, false);
        assertThat(without.path("counts").path("telegram").asInt()).isZero();
        assertThat(deliveries()).isEmpty();

        JsonNode with = sentMonth(false, true);
        dispatch("TELEGRAM");

        assertThat(with.path("counts").path("telegram").asInt()).isEqualTo(1);
        SentDocument document = telegram.documents(chat).getFirst();
        assertThat(document.caption()).endsWith("Боргу немає, дякуємо!");
        assertThat(document.buttons()).isEmpty();
    }

    @Test
    void aViberMemberIsListedToPrintWithTheNote() throws Exception {
        UUID viber = member("Вайбер", "VIBER", null);
        charge(plot("9", viber), "10.00");
        buildInvoices();

        JsonNode sent = sentMonth(false, false);

        assertThat(sent.path("counts").path("paper").asInt()).isEqualTo(1);
        listDeliveries(treasurer, "month=" + month()).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].channel").value("PAPER"))
                .andExpect(jsonPath("$.items[0].status").value("TO_PRINT"))
                .andExpect(jsonPath("$.items[0].note").value("VIBER_UNSUPPORTED"))
                .andExpect(jsonPath("$.items[0].plotNumber").value("9"))
                .andExpect(jsonPath("$.items[0].memberName").value("Вайбер Тест"));
    }

    @Test
    void aWrongMailboxPasswordFailsAtOnce() throws Exception {
        mailbox("wrong-password");
        UUID onEmail = member("Пошта", "EMAIL", "mail@delivery.test");
        charge(plot("10", onEmail), "100.00");
        buildInvoices();
        sentMonth(false, false);

        DeliveryDispatcher.Run run = dispatch("EMAIL");

        assertThat(run.failed()).isEqualTo(1);
        assertThat(deliveries()).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("failure_code")).isEqualTo("SMTP_AUTH_FAILED");
            assertThat(row.get("attempts")).isEqualTo(1);
        });
    }

    @Test
    void anUnreachableServerIsTriedThreeTimesNowAfterOneAndAfterFiveMinutes() throws Exception {
        mailbox("127.0.0.1", closedPort(), Mailpit.PASSWORD);
        UUID onEmail = member("Пошта", "EMAIL", "mail@delivery.test");
        charge(plot("10", onEmail), "100.00");
        buildInvoices();
        sentMonth(false, false);

        assertThat(dispatch("EMAIL").retried()).isEqualTo(1);
        Instant start = clock.instant();
        assertThat(state()).containsEntry("status", "QUEUED").containsEntry("attempts", 1)
                .containsEntry("failure_code", "CONNECTION_FAILED");
        assertThat(nextAttempt()).isEqualTo(start.plus(Duration.ofMinutes(1)));

        clock.advance(Duration.ofSeconds(30));
        assertThat(dispatch("EMAIL").retried()).isZero(); // not due yet
        assertThat(state()).containsEntry("attempts", 1);

        clock.advance(Duration.ofSeconds(30));
        assertThat(dispatch("EMAIL").retried()).isEqualTo(1);
        assertThat(state()).containsEntry("status", "QUEUED").containsEntry("attempts", 2);
        assertThat(nextAttempt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));

        clock.advance(Duration.ofMinutes(5));
        assertThat(dispatch("EMAIL").failed()).isEqualTo(1);
        assertThat(state()).containsEntry("status", "FAILED").containsEntry("attempts", 3)
                .containsEntry("failure_code", "CONNECTION_FAILED");
    }

    @Test
    void aMessageThatCannotBePreparedBacksOffAndFailsAfterThreeAttemptsWithoutRescheduling() throws Exception {
        mailbox(Mailpit.PASSWORD);
        UUID onEmail = member("Пошта", "EMAIL", "mail@delivery.test");
        charge(plot("10", onEmail), "100.00");
        buildInvoices();
        sentMonth(false, false);
        // the invoice's PDF is missing: preparing the message throws
        InvoiceMessages broken = new InvoiceMessages(null, null) {
            @Override
            java.util.Optional<DeliveryMessage> of(UUID invoiceId) {
                throw new java.util.NoSuchElementException("No value present");
            }
        };
        List<Instant> wakes = new java.util.ArrayList<>();
        DeliveryQueue recording = new DeliveryQueue(null, clock) {
            @Override
            void schedule(UUID tenantId, Channel channel, Instant at) {
                wakes.add(at);
            }
        };

        DeliveryDispatcher.Run first = dispatcher("broken", broken, recording).dispatch(tenant, Channel.EMAIL);
        Instant start = clock.instant();

        assertThat(first.retried()).isEqualTo(1);
        assertThat(state()).containsEntry("status", "QUEUED").containsEntry("attempts", 1)
                .containsEntry("failure_code", "CONNECTION_FAILED");
        assertThat(nextAttempt()).isEqualTo(start.plus(Duration.ofMinutes(1)));
        // the lane wakes when the row is due again, not at once
        assertThat(wakes).containsExactly(start.plus(Duration.ofMinutes(1)));

        clock.advance(Duration.ofMinutes(1));
        assertThat(dispatcher("broken", broken, recording).dispatch(tenant, Channel.EMAIL).retried()).isEqualTo(1);
        assertThat(state()).containsEntry("status", "QUEUED").containsEntry("attempts", 2);
        assertThat(nextAttempt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));

        clock.advance(Duration.ofMinutes(5));
        wakes.clear();
        assertThat(dispatcher("broken", broken, recording).dispatch(tenant, Channel.EMAIL).failed()).isEqualTo(1);
        assertThat(state()).containsEntry("status", "FAILED").containsEntry("attempts", 3)
                .containsEntry("failure_code", "CONNECTION_FAILED");
        assertThat(wakes).isEmpty();
        assertThat(mailpit.letters()).isEmpty();
    }

    @Test
    void aMailboxThatRefusesLettersFailsAfterThreeAttemptsAndARetrySendsItOnceFixed() throws Exception {
        mailbox(Mailpit.PASSWORD);
        mailpit.refuseRecipients(true);
        UUID onEmail = member("Пошта", "EMAIL", "mail@delivery.test");
        charge(plot("10", onEmail), "100.00");
        buildInvoices();
        sentMonth(false, false);

        dispatch("EMAIL");
        clock.advance(Duration.ofMinutes(1));
        dispatch("EMAIL");
        clock.advance(Duration.ofMinutes(5));
        dispatch("EMAIL");

        assertThat(state()).containsEntry("status", "FAILED").containsEntry("attempts", 3)
                .containsEntry("failure_code", "SMTP_REJECTED");
        listDeliveries(treasurer, "status=FAILED").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].failureCode").value("SMTP_REJECTED"));

        mailpit.refuseRecipients(false);
        retry(treasurer, (UUID) state().get("id")).andExpect(status().isNoContent());
        assertThat(state()).containsEntry("status", "QUEUED").containsEntry("attempts", 0)
                .containsEntry("resends", 1).containsEntry("failure_code", null);
        dispatch("EMAIL");

        assertThat(state()).containsEntry("status", "SENT").containsEntry("attempts", 1);
        assertThat(mailpit.lettersTo("mail@delivery.test")).hasSize(1);
    }

    @Test
    void aMemberWhoBlockedTheBotFailsAsBlockedAndLosesTheLink() throws Exception {
        bot();
        UUID owner = member("Заблокував", "TELEGRAM", null);
        long chat = link(owner);
        charge(plot("9", owner), "10.00");
        buildInvoices();
        sentMonth(false, false);
        telegram.block(chat);

        dispatch("TELEGRAM");

        assertThat(state()).containsEntry("status", "FAILED").containsEntry("failure_code", "TELEGRAM_BLOCKED")
                .containsEntry("attempts", 1);
        assertThat(linked(owner)).isFalse();
        assertThat(ownerJdbc().queryForObject("select end_reason from telegram_link where member_id = ?",
                String.class, owner)).isEqualTo("BLOCKED");
    }

    @Test
    void telegramAskingToWaitMovesTheMessageWithoutCountingAnAttempt() throws Exception {
        bot();
        UUID owner = member("Чекає", "TELEGRAM", null);
        long chat = link(owner);
        charge(plot("9", owner), "10.00");
        buildInvoices();
        sentMonth(false, false);
        telegram.failNext("sendDocument", FakeBotApi.Reply.tooManyRequests(7));

        DeliveryDispatcher.Run waited = dispatch("TELEGRAM");

        assertThat(waited.sent()).isZero();
        assertThat(state()).containsEntry("status", "QUEUED").containsEntry("attempts", 0);
        assertThat(nextAttempt()).isEqualTo(clock.instant().plus(Duration.ofSeconds(7)));
        assertThat(dispatch("TELEGRAM").sent()).isZero();

        clock.advance(Duration.ofSeconds(7));
        assertThat(dispatch("TELEGRAM").sent()).isEqualTo(1);
        assertThat(telegram.documents(chat)).hasSize(1);
    }

    @Test
    void noAddressAndNoMailboxFailAtOnceWithTheirCodes() throws Exception {
        mailbox(Mailpit.PASSWORD);
        bot();
        UUID onEmail = member("Пошта", "EMAIL", "mail@delivery.test");
        UUID unlinked = member("Відключився", "TELEGRAM", null);
        link(unlinked);
        charge(plot("10", onEmail), "100.00");
        charge(plot("11", unlinked), "100.00");
        buildInvoices();
        sentMonth(false, false);
        // after the send: the Telegram member unlinks, the board removes the mailbox
        TenantContext.runAs(tenant, () -> telegramLinks.unlinkMember(unlinked, TelegramLinks.EndReason.UNLINKED,
                app.nadel.identity.AccessProfile.ownerOnly(tenant, unlinked)));
        send(chairman, put("/api/v1/settings/delivery"), Map.of("email", Map.of())).andExpect(status().isOk());

        dispatch("TELEGRAM");
        dispatch("EMAIL");

        assertThat(deliveriesOf(unlinked)).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("failure_code")).isEqualTo("NO_CONTACT");
        });
        assertThat(deliveriesOf(onEmail)).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("failure_code")).isEqualTo("CHANNEL_NOT_CONFIGURED");
        });
    }

    @Test
    void telegramRefusingTheTokenOrTheChatFailsAtOnceAndAnOutageIsTriedAgain() throws Exception {
        bot();
        UUID refusedToken = member("Токен", "TELEGRAM", null);
        UUID refusedChat = member("Чат", "TELEGRAM", null);
        UUID outage = member("Збій", "TELEGRAM", null);
        long tokenChat = link(refusedToken);
        long badChat = link(refusedChat);
        long outageChat = link(outage);
        charge(plot("9", refusedToken), "10.00");
        charge(plot("10", refusedChat), "10.00");
        charge(plot("11", outage), "10.00");
        buildInvoices();
        sentMonth(false, false);
        telegram.handle("sendDocument", (token, body) -> {
            if (body.contains("\r\n\r\n" + tokenChat + "\r\n")) {
                return FakeBotApi.Reply.error(401, "Unauthorized");
            }
            if (body.contains("\r\n\r\n" + badChat + "\r\n")) {
                return FakeBotApi.Reply.error(400, "Bad Request: chat not found");
            }
            return FakeBotApi.Reply.error(502, "Bad Gateway");
        });

        DeliveryDispatcher.Run run = dispatch("TELEGRAM");

        assertThat(run.sent()).isZero();
        assertThat(run.failed()).isEqualTo(2);
        assertThat(run.retried()).isEqualTo(1);
        assertThat(deliveriesOf(refusedToken)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("status", "FAILED").containsEntry("failure_code", "CHANNEL_NOT_CONFIGURED")
                .containsEntry("attempts", 1));
        assertThat(deliveriesOf(refusedChat)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("status", "FAILED").containsEntry("failure_code", "NO_CONTACT")
                .containsEntry("attempts", 1));
        assertThat(deliveriesOf(outage)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("status", "QUEUED").containsEntry("failure_code", "CONNECTION_FAILED")
                .containsEntry("attempts", 1));
        // only a member who blocked the bot loses the link
        assertThat(List.of(linked(refusedToken), linked(refusedChat), linked(outage))).containsOnly(true);
        assertThat(telegram.documents(outageChat)).isEmpty();
    }

    @Test
    void anAddressRemovedAndABotRemovedAfterTheSendFailAtOnce() throws Exception {
        mailbox(Mailpit.PASSWORD);
        bot();
        UUID onEmail = member("Пошта", "EMAIL", "mail@delivery.test");
        UUID onTelegram = member("Телеграм", "TELEGRAM", null);
        link(onTelegram);
        charge(plot("10", onEmail), "100.00");
        charge(plot("11", onTelegram), "100.00");
        buildInvoices();
        sentMonth(false, false);
        // after the send: the member switches to paper and erases the e-mail, the cooperative's bot token is gone
        ownerJdbc().update("update member set delivery_channel = 'PAPER' where id = ?", onEmail);
        insertContacts(tenant, onEmail, null);
        ownerJdbc().update("update delivery_settings set bot_token_enc = null, bot_token_last4 = null "
                + "where tenant_id = ?", tenant);

        dispatch("EMAIL");
        dispatch("TELEGRAM");

        assertThat(deliveriesOf(onEmail)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("status", "FAILED").containsEntry("failure_code", "NO_CONTACT"));
        assertThat(deliveriesOf(onTelegram)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("status", "FAILED").containsEntry("failure_code", "CHANNEL_NOT_CONFIGURED"));
        assertThat(mailpit.letters()).isEmpty();
        assertThat(telegram.documents()).isEmpty();
    }

    @Test
    void anInvoiceWhoseMessageIsGoneFailsAtOnceWithoutContact() throws Exception {
        mailbox(Mailpit.PASSWORD);
        UUID onEmail = member("Пошта", "EMAIL", "mail@delivery.test");
        charge(plot("10", onEmail), "100.00");
        buildInvoices();
        sentMonth(false, false);
        InvoiceMessages gone = new InvoiceMessages(null, null) {
            @Override
            Optional<DeliveryMessage> of(UUID invoiceId) {
                return Optional.empty();
            }
        };

        DeliveryDispatcher.Run run = dispatcher("gone", gone, queue).dispatch(tenant, Channel.EMAIL);

        assertThat(run.failed()).isEqualTo(1);
        assertThat(state()).containsEntry("status", "FAILED").containsEntry("failure_code", "NO_CONTACT")
                .containsEntry("attempts", 1);
        assertThat(mailpit.letters()).isEmpty();
    }

    @Test
    void aRunPastItsTimeLimitHandsTheLaneToANewRunAtOnce() throws Exception {
        mailbox(Mailpit.PASSWORD);
        UUID onEmail = member("Пошта", "EMAIL", "mail@delivery.test");
        charge(plot("10", onEmail), "100.00");
        buildInvoices();
        sentMonth(false, false);
        List<Instant> wakes = new ArrayList<>();
        DeliveryQueue recording = new DeliveryQueue(null, clock) {
            @Override
            void schedule(UUID tenantId, Channel channel, Instant at) {
                wakes.add(at);
            }
        };
        clock.start();
        RateLimiters limiters = new RateLimiters(clock, clock::advance, Duration.ofSeconds(1), 20);
        DeliveryProperties noTime = new DeliveryProperties(null, null, null, Duration.ZERO, null, null, null, null);
        DeliveryDispatcher dispatcher = new DeliveryDispatcher("limited", rows, lanes, recording, invoiceMessages,
                paymentMessages, announcementMessages, settingsService, memberDirectory, telegramLinks, mailChannel,
                telegramChannel, limiters, clock, noTime, jdbc, transactionManager);

        DeliveryDispatcher.Run run = dispatcher.dispatch(tenant, Channel.EMAIL);

        assertThat(run.busy()).isFalse();
        assertThat(run.sent()).isZero();
        assertThat(state()).containsEntry("status", "QUEUED").containsEntry("attempts", 0);
        assertThat(wakes).containsExactly(clock.instant());
        assertThat(mailpit.letters()).isEmpty();
        // the lane is free again: the next run sends it
        assertThat(dispatch("EMAIL").sent()).isEqualTo(1);
    }

    @Test
    void aRunThatLosesItsLaneStopsBeforeTheNextMessageAndLeavesItQueued() throws Exception {
        mailbox(Mailpit.PASSWORD);
        UUID first = member("Перша", "EMAIL", "first@delivery.test");
        UUID second = member("Друга", "EMAIL", "second@delivery.test");
        charge(plot("10", first), "100.00");
        charge(plot("11", second), "100.00");
        buildInvoices();
        sentMonth(false, false);
        List<Instant> wakes = new ArrayList<>();
        DeliveryQueue recording = new DeliveryQueue(null, clock) {
            @Override
            void schedule(UUID tenantId, Channel channel, Instant at) {
                wakes.add(at);
            }
        };
        // the lease holds for the run's start and the first message, then another instance has the lane
        java.util.concurrent.atomic.AtomicInteger claims = new java.util.concurrent.atomic.AtomicInteger();
        DeliveryLanes losing = new DeliveryLanes(jdbc) {
            @Override
            boolean claim(UUID tenantId, Channel channel, String owner, Duration lease) {
                return claims.incrementAndGet() <= 2 && super.claim(tenantId, channel, owner, lease);
            }
        };
        clock.start();
        DeliveryDispatcher dispatcher = new DeliveryDispatcher("losing", rows, losing, recording, invoiceMessages,
                paymentMessages, announcementMessages, settingsService, memberDirectory, telegramLinks, mailChannel,
                telegramChannel, new RateLimiters(clock, clock::advance, Duration.ofSeconds(1), 20), clock,
                DeliveryProperties.defaults(), jdbc, transactionManager);

        DeliveryDispatcher.Run run = dispatcher.dispatch(tenant, Channel.EMAIL);

        assertThat(run.sent()).isEqualTo(1);
        assertThat(deliveries()).extracting(row -> row.get("status")).containsExactlyInAnyOrder("SENT", "QUEUED");
        assertThat(deliveries()).filteredOn(row -> "QUEUED".equals(row.get("status")))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("attempts", 0));
        assertThat(mailpit.letters()).hasSize(1);
        // the lane is woken for what is left, which is due already
        assertThat(wakes).singleElement().satisfies(at -> assertThat(at).isBeforeOrEqualTo(clock.instant()));
    }

    @Test
    void aSweepThatCannotListTheLanesWakesNothingAndDoesNotFail() throws Exception {
        mailbox(Mailpit.PASSWORD);
        UUID onEmail = member("Пошта", "EMAIL", "mail@delivery.test");
        charge(plot("10", onEmail), "100.00");
        buildInvoices();
        sentMonth(false, false);
        List<Instant> wakes = new ArrayList<>();
        DeliveryQueue recording = new DeliveryQueue(null, clock) {
            @Override
            void schedule(UUID tenantId, Channel channel, Instant at) {
                wakes.add(at);
            }
        };
        JdbcTemplate unreachable = new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:"
                + closedPort() + "/nadel"));
        DeliveryDispatcher dispatcher = new DeliveryDispatcher("sweeper", rows, lanes, recording, invoiceMessages,
                paymentMessages, announcementMessages, settingsService, memberDirectory, telegramLinks, mailChannel,
                telegramChannel, new RateLimiters(clock, clock::advance, Duration.ofSeconds(1), 20), clock,
                DeliveryProperties.defaults(), unreachable, transactionManager);

        assertThatCode(dispatcher::sweep).doesNotThrowAnyException();

        assertThat(wakes).isEmpty();
        assertThat(state()).containsEntry("status", "QUEUED");
    }

    @Test
    void oneInvoiceGoesToItsOwnersAlsoWithNothingDue() throws Exception {
        bot();
        UUID owner = member("Один", "TELEGRAM", null);
        UUID other = member("Інший", "PAPER", null);
        long chat = link(owner);
        UUID plot = plot("9", owner); // nothing charged: nothing due
        charge(plot("10", other), "10.00");
        buildInvoices();

        sendOne(treasurer, invoiceOf(plot)).andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.telegram").value(1))
                .andExpect(jsonPath("$.counts.paper").value(0))
                .andExpect(jsonPath("$.queued").value(1));
        sendOne(treasurer, invoiceOf(plot)).andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.telegram").value(0));
        sendOne(treasurer, UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        dispatch("TELEGRAM");

        assertThat(telegram.documents(chat)).hasSize(1);
        assertThat(deliveries()).hasSize(1);
        assertThat((String) sentAudit(1).getFirst().get("changes")).contains("\"number\"");
    }

    @Test
    void onlyASentOrFailedEmailOrTelegramDeliveryIsSentAgain() throws Exception {
        mailbox(Mailpit.PASSWORD);
        UUID onEmail = member("Пошта", "EMAIL", "mail@delivery.test");
        UUID onPaper = member("Папір", "PAPER", null);
        charge(plot("10", onEmail), "100.00");
        charge(plot("11", onPaper), "100.00");
        buildInvoices();
        sentMonth(false, false);
        UUID email = (UUID) deliveriesOf(onEmail).getFirst().get("id");
        UUID paper = (UUID) deliveriesOf(onPaper).getFirst().get("id");

        retry(treasurer, email).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_RETRYABLE")); // still queued
        retry(treasurer, paper).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_RETRYABLE"));
        retry(treasurer, UUID.randomUUID()).andExpect(status().isNotFound());

        dispatch("EMAIL");
        retry(treasurer, email).andExpect(status().isNoContent());
        clock.advance(Duration.ofMinutes(1)); // the retry is due at the real time of the request
        dispatch("EMAIL");

        assertThat(mailpit.lettersTo("mail@delivery.test")).hasSize(2);
        assertThat(deliveriesOf(onEmail).getFirst()).containsEntry("status", "SENT").containsEntry("resends", 1);
    }

    @Test
    void printedMarksTheMonthsPaperCopies() throws Exception {
        UUID onPaper = member("Папір", "PAPER", null);
        UUID viber = member("Вайбер", "VIBER", null);
        charge(plot("9", onPaper, viber), "100.00");
        buildInvoices();
        sentMonth(false, false);

        printDone(treasurer).andExpect(status().isOk()).andExpect(jsonPath("$.printed").value(2));
        printDone(treasurer).andExpect(status().isOk()).andExpect(jsonPath("$.printed").value(0));

        assertThat(deliveries()).extracting(row -> row.get("status")).containsOnly("PRINTED");
        // paper is printed by the board, never sent by a lane
        assertThatThrownBy(() -> dispatch("PAPER")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theListShowsTheRowsAndTheCountsPerChannelAndStatus() throws Exception {
        mailbox(Mailpit.PASSWORD);
        UUID onEmail = member("Пошта", "EMAIL", "mail@delivery.test");
        UUID onPaper = member("Папір", "PAPER", null);
        charge(plot("10", onEmail), "100.00");
        charge(plot("11", onPaper), "100.00");
        buildInvoices();
        sentMonth(false, false);
        dispatch("EMAIL");

        JsonNode page = body(listDeliveries(treasurer, "month=" + month() + "&channel=EMAIL&size=10")
                .andExpect(status().isOk()));

        assertThat(page.path("totalItems").asLong()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("status").asString()).isEqualTo("SENT");
        assertThat(page.path("items").get(0).path("kind").asString()).isEqualTo("INVOICE");
        assertThat(page.path("items").get(0).path("month").asString()).isEqualTo(month().toString());
        assertThat(page.path("items").get(0).path("sentAt").isMissingNode()).isFalse();
        assertThat(page.toString()).doesNotContain("mail@delivery.test");
        assertThat(page.path("summary")).hasSize(2); // the channel filter does not narrow the counts
        assertThat(page.path("summary").toString()).contains("\"EMAIL\"", "\"SENT\"", "\"PAPER\"", "\"TO_PRINT\"");
    }

    @Test
    void theSweepWakesTheLanesWithDueRowsAndABusyLaneWaits() throws Exception {
        mailbox(Mailpit.PASSWORD);
        UUID onEmail = member("Пошта", "EMAIL", "mail@delivery.test");
        charge(plot("10", onEmail), "100.00");
        buildInvoices();
        sentMonth(false, false);

        List<String> due = jdbc.queryForList("select tenant_id::text || ':' || channel from "
                + "notifications_due_lanes(now() + interval '1 minute')", String.class);
        assertThat(due).contains(tenant + ":EMAIL");
        // the job the send created is gone (as after a lost wake-up); the sweep creates one
        ownerJdbc().update("delete from jobrunr_jobs where jobasjson like ?", "%" + tenant + "%");
        dispatcher("sweeper").sweep();
        assertThat(jobsOf(tenant)).isEqualTo(1);
        // the schedule's minute sweep does the same after the job is lost again
        ownerJdbc().update("delete from jobrunr_jobs where jobasjson like ?", "%" + tenant + "%");
        new BotSchedule(supervisor, dispatcher("sweeper")).sweepDeliveries();
        assertThat(jobsOf(tenant)).isEqualTo(1);

        // a lane held by another instance waits
        TenantContext.runAs(tenant, () -> new org.springframework.transaction.support.TransactionTemplate(
                transactionManager).execute(s -> lanes.claim(tenant, Channel.EMAIL, "other-instance",
                        Duration.ofMinutes(1))));
        assertThat(dispatch("EMAIL").busy()).isTrue();
        assertThat(state()).containsEntry("status", "QUEUED");
    }

    @Test
    void theAuditActionIsAValueOfTheContract() {
        assertThat(nadel.contract.model.AuditAction.fromValue(DeliveryPlanner.ACTION))
                .isEqualTo(nadel.contract.model.AuditAction.INVOICES_SENT);
        assertThat(nadel.contract.model.AuditEntityType.fromValue(DeliveryPlanner.ENTITY_TYPE))
                .isEqualTo(nadel.contract.model.AuditEntityType.BILLING_MONTH);
    }

    private Map<String, Object> state() {
        return deliveries().getFirst();
    }

    private Instant nextAttempt() {
        Object next = state().get("next_attempt_at");
        return next instanceof OffsetDateTime time ? time.toInstant() : ((java.sql.Timestamp) next).toInstant();
    }

    private static int jobsOf(UUID tenant) {
        Integer count = ownerJdbc().queryForObject("select count(*) from jobrunr_jobs where jobasjson like ?",
                Integer.class, "%" + tenant + "%");
        return count == null ? 0 : count;
    }

    private static String pdfText(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document).replace('\u00A0', ' ');
        }
    }
}
