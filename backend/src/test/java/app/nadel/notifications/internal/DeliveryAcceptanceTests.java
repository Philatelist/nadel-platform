package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.notifications.FakeBotApi.Sent;
import app.nadel.notifications.FakeBotApi.SentDocument;
import app.nadel.notifications.Mailpit;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Acceptance of feature 017 as a whole against {@code functional-spec.md} (sections 2.1 to 2.7), one journey per area,
 * driven through the public API, the in-JVM fake Bot API, Mailpit and the bot's own supervisor, on a throw-away
 * cooperative. The slice tests prove the details (every channel rule, every failure code, every bot step); these prove
 * that the pieces work together the way the spec describes: setting up, linking, sending, paying, asking the bot and
 * announcing. Section 2.8 and 2.9 are in {@code DemoNotificationsTests}, the guide checks and the end-to-end specs;
 * 2.10 is the owner's manual check. The mapping is in {@code context/spec/017-delivery-email-telegram/acceptance-coverage.md}.
 *
 * <pre>
 * &#64;layer: integration
 * &#64;spec: 017-delivery-email-telegram
 * &#64;regression
 * </pre>
 */
// @layer: integration
// @spec: 017-delivery-email-telegram
// @regression
class DeliveryAcceptanceTests extends BotCommandsTestSupport {

    private static final String SETTINGS = "/api/v1/settings/delivery";
    private static final String INVALID_LINK = "Посилання недійсне, отримайте нове в кабінеті або в правлінні";
    private static final String WRONG_TOKEN = "bad456789:AAtokenTelegramDoesNotKnow99";

    // ---- 2.1 channel settings

    @Test
    void theChairmanSetsUpTheMailboxAndTheBotAndSecretsNeverComeBack() throws Exception {
        // a cooperative that never set it up: both channels «не налаштовано»
        send(chairman, get(SETTINGS), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.email.configured").value(false))
                .andExpect(jsonPath("$.telegram.configured").value(false));

        mailbox(Mailpit.PASSWORD);
        bot();

        // reopened: the password is only «збережено», the token only its last 4 characters
        String reopened = send(chairman, get(SETTINGS), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.email.configured").value(true))
                .andExpect(jsonPath("$.email.passwordSet").value(true))
                .andExpect(jsonPath("$.telegram.configured").value(true))
                .andExpect(jsonPath("$.telegram.tokenLast4").value("0001"))
                .andReturn().getResponse().getContentAsString();
        assertThat(reopened).doesNotContain(Mailpit.PASSWORD).doesNotContain(TOKEN);

        // the test letter arrives at the chairman's own e-mail
        send(chairman, post(SETTINGS + "/test-email"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
        assertThat(mailpit.letters()).singleElement()
                .satisfies(letter -> assertThat(letter.to()).singleElement().asString().startsWith("board-"));

        // the bot check answers the @name; a token Telegram refuses is «Токен недійсний»
        send(chairman, post(SETTINGS + "/test-telegram"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.botUsername").value(BOT));
        send(chairman, put(SETTINGS), Map.of("telegram", Map.of("botToken", WRONG_TOKEN))).andExpect(status().isOk());
        send(chairman, post(SETTINGS + "/test-telegram"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));

        // a wrong password: the server's refusal in plain words, nothing else changes
        mailbox("wrong-password");
        send(chairman, post(SETTINGS + "/test-email"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.failureCode").value("SMTP_AUTH_FAILED"))
                .andExpect(jsonPath("$.serverReply").value(org.hamcrest.Matchers.startsWith("535")));
        assertThat(mailpit.letters()).hasSize(1);

        // the change history names the changed secrets and never their values
        await().atMost(Duration.ofSeconds(10)).until(() -> auditOfSettings().size() >= 4);
        for (Map<String, Object> entry : auditOfSettings()) {
            String changes = (String) entry.get("changes");
            assertThat(changes).doesNotContain(Mailpit.PASSWORD).doesNotContain("wrong-password")
                    .doesNotContain(TOKEN).doesNotContain(WRONG_TOKEN);
        }
        assertThat(auditOfSettings().toString()).contains("password").contains("botToken");
    }

    @Test
    void aTreasurerWithoutTheRightToManageSettingsCannotSeeOrSaveThem() throws Exception {
        send(treasurer, get(SETTINGS), null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        send(treasurer, put(SETTINGS), Map.of("telegram", Map.of("botToken", TOKEN))).andExpect(status().isForbidden());
        send(treasurer, post(SETTINGS + "/test-email"), null).andExpect(status().isForbidden());
        send(treasurer, post(SETTINGS + "/test-telegram"), null).andExpect(status().isForbidden());
        // nothing was saved by the refused call
        send(chairman, get(SETTINGS), null).andExpect(jsonPath("$.telegram.configured").value(false));
    }

    // ---- 2.2 linking, 2.3 the member's channel

    @Test
    void aMemberLinksTelegramFromTheAccountChoosesItAndUnlinksInTheBot() throws Exception {
        Member member = member("Лисенко");
        UUID plot = plot("9", member.id());
        charge(plot, "100.00");
        buildInvoices();

        // a cooperative without a bot: «Підключити Telegram» is not offered, only paper (no mailbox either)
        send(member.bearer(), get("/api/v1/me/notifications"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.botAvailable").value(false))
                .andExpect(jsonPath("$.available[0]").value("PAPER"))
                .andExpect(jsonPath("$.available.length()").value(1));

        startBot();
        send(member.bearer(), get("/api/v1/me/notifications"), null).andExpect(jsonPath("$.botAvailable").value(true))
                .andExpect(jsonPath("$.telegramLinked").value(false));
        JsonNode offer = body(send(member.bearer(), post("/api/v1/me/telegram-link"), null)
                .andExpect(status().isOk()));
        String deepLink = offer.get("deepLink").asString();
        String code = deepLink.substring(deepLink.indexOf('=') + 1);
        long chat = ThreadLocalChat.next();

        // the bot greets by name with the plot numbers and shows the menu; the account shows «підключено»
        Sent greeting = lastReply(chat, "/start " + code, 1);
        assertThat(greeting.text()).isEqualTo("Вітаємо, Тест! Тепер рахунки за ділянку 9 приходитимуть сюди.");
        assertThat(greeting.buttons()).containsExactly(MENU_ROW_1, MENU_ROW_2);
        send(member.bearer(), get("/api/v1/me/notifications"), null)
                .andExpect(jsonPath("$.telegramLinked").value(true));

        // a used link is refused and links nothing
        long stranger = ThreadLocalChat.next();
        assertThat(lastReply(stranger, "/start " + code, 1).text()).isEqualTo(INVALID_LINK);
        assertThat(linked(member.id())).isTrue();

        // the member chooses Telegram; the board sees the new channel and only «підключено»
        send(member.bearer(), put("/api/v1/me/notifications"), Map.of("channel", "TELEGRAM"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.channel").value("TELEGRAM"));
        String card = send(chairman, get("/api/v1/members/" + member.id()), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryChannel").value("TELEGRAM"))
                .andExpect(jsonPath("$.telegramLinked").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(card).doesNotContain(Long.toString(chat));

        // «Відключити» in the bot: the account shows «не підключено» and the next invoice takes the fallback (paper)
        say(chat, "Відключити", 2);
        send(member.bearer(), get("/api/v1/me/notifications"), null)
                .andExpect(jsonPath("$.telegramLinked").value(false));
        JsonNode sent = sentMonth(false, false);
        assertThat(sent.path("counts").path("telegram").asInt()).isZero();
        assertThat(sent.path("counts").path("paper").asInt()).isEqualTo(1);
        assertThat(deliveriesOf(member.id())).singleElement().satisfies(row -> {
            assertThat(row.get("channel")).isEqualTo("PAPER");
            assertThat(row.get("status")).isEqualTo("TO_PRINT");
        });

        // 2.7: the history has both events for this member and no Telegram details
        for (String action : List.of("TELEGRAM_LINKED", "TELEGRAM_UNLINKED")) {
            await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                    "select count(*) from audit_entry where tenant_id = ? and entity_id = ? and action = ?",
                    Integer.class, tenant, member.id(), action) == 1);
        }
        assertThat(ownerJdbc().queryForList("select cast(changes as text) from audit_entry where tenant_id = ? "
                + "and entity_id = ? and action like 'TELEGRAM_%'", String.class, tenant, member.id()).toString())
                .doesNotContain(Long.toString(chat)).doesNotContain(code);
    }

    // ---- 2.3 and 2.4 sending the month's invoices

    @Test
    void theTreasurerPreviewsSendsAndSeesSentFailedAndToPrintAndASecondSendAddsNothing() throws Exception {
        mailbox(Mailpit.PASSWORD);
        bot();
        UUID telegramOwner = member("Телеграм", "TELEGRAM", "tg@delivery.test");
        UUID paperOwner = member("Папір", "PAPER", null);
        UUID several = member("Багатоділянковий", "TELEGRAM", null);
        UUID mailer = member("Пошта", "EMAIL", "mail@delivery.test");
        UUID viber = member("Вайбер", "VIBER", null);
        UUID blocked = member("Заблокував", "TELEGRAM", null);
        link(telegramOwner);
        long severalChat = link(several);
        long blockedChat = link(blocked);
        charge(plot("9", telegramOwner, paperOwner), "413.26");
        charge(plot("10", several), "100.00");
        charge(plot("11", several), "200.00");
        charge(plot("12", mailer), "100.00");
        charge(plot("13", viber), "100.00");
        charge(plot("14", blocked), "100.00");
        buildInvoices();

        // the preview counts per channel first and sends nothing
        JsonNode preview = sentMonth(true, false);
        assertThat(preview.path("counts").path("telegram").asInt()).isEqualTo(4);
        assertThat(preview.path("counts").path("email").asInt()).isEqualTo(1);
        assertThat(preview.path("counts").path("paper").asInt()).isEqualTo(2);
        assertThat(deliveries()).isEmpty();

        // confirmed: one message per owner and plot, each through the owner's own channel
        JsonNode sent = sentMonth(false, false);
        assertThat(sent.path("counts")).isEqualTo(preview.path("counts"));
        assertThat(deliveries()).hasSize(7);
        assertThat(deliveriesOf(several)).hasSize(2);
        assertThat(deliveriesOf(paperOwner)).singleElement().satisfies(row -> assertThat(row.get("status"))
                .isEqualTo("TO_PRINT"));
        assertThat(deliveriesOf(viber)).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("TO_PRINT");
            assertThat(row.get("note")).isEqualTo("VIBER_UNSUPPORTED");
        });

        telegram.block(blockedChat);
        dispatch("TELEGRAM");
        dispatch("EMAIL");

        // the Telegram message of the 9th plot carries the PDF and the «Оплатити» button; the owner of two plots gets two
        assertThat(telegram.documents(severalChat)).hasSize(2).allSatisfy(document -> {
            assertThat(document.caption()).contains("ділянка 1").contains(": до сплати");
            assertThat(document.buttons()).singleElement().satisfies(button -> assertThat(button.text())
                    .isEqualTo("Оплатити"));
            assertThat(document.fileName()).endsWith(".pdf");
        });
        assertThat(mailpit.lettersTo("mail@delivery.test")).singleElement()
                .satisfies(letter -> assertThat(mailpit.attachments(letter.id())).hasSize(1));

        // the member who blocked the bot: «помилка: Бот заблоковано», and the link ends
        assertThat(deliveriesOf(blocked)).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("failure_code")).isEqualTo("TELEGRAM_BLOCKED");
        });
        assertThat(linked(blocked)).isFalse();

        // the delivery screen: sent, failed and to print per channel, filtered by status
        JsonNode screen = body(listDeliveries(treasurer, "month=" + month() + "&size=50").andExpect(status().isOk()));
        assertThat(screen.path("totalItems").asLong()).isEqualTo(7);
        assertThat(screen.path("summary").toString()).contains("\"SENT\"", "\"FAILED\"", "\"TO_PRINT\"");
        listDeliveries(treasurer, "month=" + month() + "&status=FAILED").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].failureCode").value("TELEGRAM_BLOCKED"));

        // the second send adds nothing for anyone who got the invoice or has a row on the same channel; the only new
        // row is the paper copy of the member whose Telegram link ended (the fallback channel now)
        JsonNode again = sentMonth(false, false);
        assertThat(again.path("queued").asInt()).isZero();
        assertThat(deliveries()).hasSize(8);
        assertThat(deliveriesOf(blocked)).extracting(row -> row.get("channel")).containsExactlyInAnyOrder("TELEGRAM",
                "PAPER");
        assertThat(deliveriesOf(several)).hasSize(2);
        assertThat(deliveriesOf(telegramOwner)).hasSize(1);
        assertThat(deliveriesOf(mailer)).hasSize(1);
        assertThat(telegram.documents(severalChat)).hasSize(2);
        assertThat(mailpit.lettersTo("mail@delivery.test")).hasSize(1);

        // «Роздруковано»: the paper rows stop being «до друку»
        printDone(treasurer).andExpect(status().isOk()).andExpect(jsonPath("$.printed").value(3));
        assertThat(deliveries()).extracting(row -> row.get("status")).doesNotContain("TO_PRINT");

        // 2.7: the history shows the invoices sent with the month
        assertThat(sentAudit(1)).isNotEmpty();
    }

    @Test
    void aMailboxThatRefusesLettersFailsAfterThreeAttemptsAndARetryDeliversOnceFixed() throws Exception {
        mailbox(Mailpit.PASSWORD);
        mailpit.refuseRecipients(true);
        UUID mailer = member("Пошта", "EMAIL", "mail@delivery.test");
        charge(plot("12", mailer), "100.00");
        buildInvoices();
        sentMonth(false, false);

        dispatch("EMAIL");
        clock.advance(Duration.ofMinutes(1));
        dispatch("EMAIL");
        clock.advance(Duration.ofMinutes(5));
        dispatch("EMAIL");

        Map<String, Object> failed = deliveriesOf(mailer).getFirst();
        assertThat(failed).containsEntry("status", "FAILED").containsEntry("attempts", 3)
                .containsEntry("failure_code", "SMTP_REJECTED");

        mailpit.refuseRecipients(false);
        retry(treasurer, (UUID) failed.get("id")).andExpect(status().isNoContent());
        dispatch("EMAIL");

        assertThat(deliveriesOf(mailer).getFirst()).containsEntry("status", "SENT");
        assertThat(mailpit.lettersTo("mail@delivery.test")).hasSize(1);
    }

    @Test
    void onlyAUserWhoMayIssueInvoicesSendsThemOrOpensTheDeliveryScreen() throws Exception {
        String plain = person("MEMBER", "Член", "Простий", "plain-" + UUID.randomUUID() + "@delivery.test");
        UUID owner = member("Папір", "PAPER", null);
        charge(plot("9", owner), "50.00");
        buildInvoices();

        for (boolean preview : new boolean[] {true, false}) {
            sendMonth(plain, preview, null).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        listDeliveries(plain, null).andExpect(status().isForbidden());
        printDone(plain).andExpect(status().isForbidden());
        assertThat(deliveries()).isEmpty();
    }

    // ---- 2.4 payment messages

    @Test
    void aCashPaymentReachesTheLinkedOwnerWithTheReceiptAReversalAndTheSwitchOffSendNothingMore() throws Exception {
        bot();
        UUID owner = member("Телеграм", "TELEGRAM", null);
        UUID paperOwner = member("Папір", "PAPER", null);
        long chat = link(owner);
        UUID plot = plot("9", owner, paperOwner);
        charge(plot, "1734.56");

        pay(plot, owner, "500.00");
        awaitRows(1);
        dispatch("TELEGRAM");

        SentDocument receipt = telegram.documents(chat).getFirst();
        assertThat(receipt.caption()).startsWith("Отримано 500,00" + NBSP + "₴ по ділянці 9 (")
                .contains("). Борг: 1" + NBSP + "234,56" + NBSP + "₴");
        assertThat(receipt.fileName()).startsWith("receipt_").endsWith(".pdf");
        // a paper owner gets no payment message
        assertThat(deliveriesOf(paperOwner)).isEmpty();

        // the reversal: «Платіж від … на … сторновано» with the new debt
        UUID payment = ownerJdbc().queryForObject("select id from payment where plot_id = ? and kind = 'PAYMENT'",
                UUID.class, plot);
        send(chairman, post("/api/v1/payments/" + payment + "/reverse"), Map.of("reason", "Помилкова ділянка"))
                .andExpect(status().isCreated());
        awaitRows(2);
        clock.advance(Duration.ofMinutes(1)); // the clock stopped at the first dispatch; the reversal is newer
        dispatch("TELEGRAM");
        assertThat(telegram.sent(chat).getLast().text()).startsWith("Платіж від ")
                .contains(" на 500,00" + NBSP + "₴ сторновано. Борг: 1" + NBSP + "734,56" + NBSP + "₴");

        // the switch «Повідомляти про платежі» off: the next payment sends no message
        send(chairman, put(SETTINGS), Map.of("notifyPayments", false)).andExpect(status().isOk());
        pay(plot, owner, "100.00");
        await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                "select count(*) from event_publication where completion_date is null and serialized_event like ?",
                Integer.class, "%" + tenant + "%") == 0);
        assertThat(deliveriesOf(owner)).hasSize(2);
    }

    // ---- 2.5 the bot

    @Test
    void theLinkedMemberAsksDebtAndInvoiceSubmitsAReadingAndHearsTheBoardsDecision() throws Exception {
        startBot();
        Member owner = member("Шевченко");
        UUID plot = plot("9", owner.id());
        charge(plot, "MEMBERSHIP", "1200.00");
        charge(plot, "ELECTRICITY", "213.26");
        UUID meter = meter(plot, "ЕЛ-9/2", "ELECTRICITY");
        entered(meter, today().minusDays(1), "12000");
        long chat = link(owner.id());

        // «Борг»: per charge type, the total, as in the account
        Sent debt = lastReply(chat, "Борг", 1);
        assertThat(debt.text()).contains("Членський внесок: 1" + NBSP + "200,00" + NBSP + "₴")
                .contains("Електроенергія: 213,26" + NBSP + "₴")
                .contains("Усього боргу: 1" + NBSP + "413,26" + NBSP + "₴");
        assertThat(account(owner, plot).get("total").asString()).isEqualTo("1413.26");

        // «Рахунок» twice the same day: the same invoice
        telegram.pushMessage(TOKEN, chat, "Рахунок");
        SentDocument first = awaitDocuments(chat, 1).getFirst();
        telegram.pushMessage(TOKEN, chat, "Рахунок");
        SentDocument second = awaitDocuments(chat, 2).get(1);
        assertThat(second.fileName()).isEqualTo(first.fileName());
        assertThat(second.caption()).isEqualTo(first.caption());

        // «Показання»: submitted for the board's approval, then accepted
        say(chat, "Показання", 2);
        say(chat, "⚡ ЕЛ-9/2", 3);
        // below the previous reading: the same refusal as in the account, and the member types again
        Sent refused = lastReply(chat, "11999", 4);
        assertThat(refused.text()).contains("Введіть показання ще раз.");
        Sent accepted = lastReply(chat, "12345", 5);
        assertThat(accepted.text()).isEqualTo("Показання 12" + NBSP + "345 по лічильнику ЕЛ-9/2 передано на перевірку");
        UUID reading = readingsOf(meter).stream().filter(row -> "PENDING".equals(row.get("status")))
                .map(row -> (UUID) row.get("id")).findFirst().orElseThrow();
        send(chairman, get("/api/v1/readings").param("status", "PENDING"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reading.id").value(reading.toString()));
        send(chairman, post("/api/v1/readings/" + reading + "/approve"), null).andExpect(status().isOk());
        assertThat(awaitSent(chat, 6).get(5).text()).isEqualTo("Показання 12" + NBSP + "345 по лічильнику ЕЛ-9/2 "
                + "прийнято");

        // any other text: the help text
        assertThat(lastReply(chat, "що це?", 7).text()).startsWith("Це бот кооперативу ").contains("«Борг»");
    }

    @Test
    void anUnlinkedChatGetsTheInvitationAndNoAmounts() throws Exception {
        startBot();
        Member owner = member("Коваль");
        charge(plot("9", owner.id()), "MEMBERSHIP", "999.00");
        long chat = ThreadLocalChat.next();

        Sent reply = lastReply(chat, "Борг", 1);

        assertThat(reply.text()).startsWith("Це бот кооперативу ").contains("«Підключити Telegram»")
                .doesNotContain("₴").doesNotContain("999");
    }

    // ---- 2.6 and 2.7 announcements

    @Test
    void theChairmanPreviewsAndSendsAnAnnouncementToOneStreetAndOnlyTheRightToSendMayDoIt() throws Exception {
        bot();
        UUID linked = member("Ткаченко", "TELEGRAM", "tkachenko@delivery.test");
        long linkedChat = link(linked);
        UUID withoutTelegram = member("Лисенко", "EMAIL", "lysenko@delivery.test");
        UUID otherStreet = member("Кравчук", "TELEGRAM", "kravchuk@delivery.test");
        long otherChat = link(otherStreet);
        plotOn("Вишнева", "1", linked);
        plotOn("Вишнева", "2", withoutTelegram);
        plotOn("Садова", "3", otherStreet);
        Map<String, Object> request = Map.of("text", "Збори в неділю", "streets", List.of("Вишнева"),
                "channels", List.of("TELEGRAM"));

        // the preview: the count by Telegram and the street's members without it
        JsonNode preview = body(send(chairman, post("/api/v1/announcements?preview=true"), request)
                .andExpect(status().isOk()));
        assertThat(preview.path("counts").path("telegram").asInt()).isEqualTo(1);
        assertThat(preview.path("paperMembers")).hasSize(1);
        assertThat(preview.path("paperMembers").get(0).path("memberId").asString())
                .isEqualTo(withoutTelegram.toString());
        assertThat(deliveries()).isEmpty();

        // a treasurer without the right «Надсилати оголошення» cannot send
        send(treasurer, post("/api/v1/announcements?preview=false"), request).andExpect(status().isForbidden());
        send(treasurer, get("/api/v1/announcements"), null).andExpect(status().isForbidden());

        // too long: refused
        send(chairman, post("/api/v1/announcements?preview=false"),
                Map.of("text", "я".repeat(2001), "streets", List.of(), "channels", List.of("TELEGRAM")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ANNOUNCEMENT_TOO_LONG"));

        // sent: the street's linked member gets the text, the other street nothing, the list shows the counts
        JsonNode sent = body(send(chairman, post("/api/v1/announcements?preview=false"), request)
                .andExpect(status().isOk()));
        dispatch("TELEGRAM");
        assertThat(telegram.sent(linkedChat)).singleElement().satisfies(message -> assertThat(message.text())
                .isEqualTo("Збори в неділю"));
        assertThat(telegram.sent(otherChat)).isEmpty();
        JsonNode list = body(send(chairman, get("/api/v1/announcements"), null).andExpect(status().isOk()));
        assertThat(list.path("totalItems").asLong()).isEqualTo(1);
        assertThat(list.path("items").get(0).path("id").asString()).isEqualTo(sent.path("announcementId").asString());
        assertThat(list.path("items").get(0).path("counts").path("sent").asInt()).isEqualTo(1);
        assertThat(list.path("items").get(0).path("counts").path("paper").asInt()).isEqualTo(1);
    }

    // ---- helpers

    private List<Map<String, Object>> auditOfSettings() {
        return ownerJdbc().queryForList("select action, cast(changes as text) as changes from audit_entry "
                + "where tenant_id = ? and entity_type = 'DELIVERY_SETTINGS' and action = 'DELIVERY_SETTINGS_CHANGED' "
                + "order by occurred_at", tenant);
    }

    /** Waits until the cooperative has {@code count} payment-message rows. */
    private void awaitRows(int count) {
        await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                "select count(*) from delivery where tenant_id = ? and kind in ('PAYMENT', 'REVERSAL')", Integer.class, tenant)
                >= count);
    }

    /** A chat id no other test uses. */
    private static final class ThreadLocalChat {

        static long next() {
            return java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
        }
    }
}
