package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.notifications.FakeBotApi.Sent;
import app.nadel.privacy.FieldRef;
import app.nadel.privacy.PersonalDataCipher;
import app.nadel.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;

/**
 * Linking a member's Telegram through the cooperative's bot (feature 017 §2.2) against the in-JVM fake Bot API: the
 * deep link of the account, the greeting with the plot numbers and the menu, one-time and expiring codes, a chat that
 * moves to another member only after «Так, це я», unlinking in the bot, in the account and by blocking the bot, and
 * the chat id that never appears in the clear.
 */
class TelegramLinkingTests extends BotTestSupport {

    private static final String INVALID = "Посилання недійсне, отримайте нове в кабінеті або в правлінні";

    @Autowired
    PersonalDataCipher cipher;

    @Test
    void theAccountLinkLinksTheChatGreetsTheMemberAndShowsTheMenu() throws Exception {
        startBot();
        Owner taras = owner("Тарас", 1);
        long chat = chat();

        myNotifications(taras.bearer()).andExpect(status().isOk())
                .andExpect(jsonPath("$.telegramLinked").value(false))
                .andExpect(jsonPath("$.botAvailable").value(true));
        JsonNode offer = body(createLink(taras.bearer()).andExpect(status().isOk()));
        String deepLink = offer.get("deepLink").asString();
        assertThat(deepLink).matches("https://t\\.me/nadel_test_bot\\?start=[A-Za-z0-9_-]{22}");
        assertThat(OffsetDateTime.parse(offer.get("expiresAt").asString()).toInstant())
                .isBetween(Instant.now().plus(Duration.ofMinutes(29)), Instant.now().plus(Duration.ofMinutes(31)));
        // the bot's @name was not known yet (no bot check): Telegram was asked and the answer kept
        assertThat(telegram.calls("getMe")).hasSize(1);
        read(chairman()).andExpect(jsonPath("$.telegram.botUsername").value("nadel_test_bot"));

        Sent greeting = lastReply(chat, "/start " + deepLink.substring(deepLink.indexOf('=') + 1), 1);

        assertThat(greeting.text()).isEqualTo("Вітаємо, Тарас! Тепер рахунки за ділянку " + taras.plots().getFirst()
                + " приходитимуть сюди.");
        assertThat(greeting.buttons()).containsExactly(MENU_ROW_1, MENU_ROW_2);
        assertThat(linkedMember(chat)).isEqualTo(taras.id());
        myNotifications(taras.bearer()).andExpect(jsonPath("$.telegramLinked").value(true));
        // the board sees the flag on the member card, never the Telegram account
        String card = mvc.perform(get("/api/v1/members/" + taras.id()).header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.telegramLinked").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(card).doesNotContain(Long.toString(chat));
        List<Map<String, Object>> audit = memberAudit(taras.id(), "TELEGRAM_LINKED", 1);
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.get("entity_type")).isEqualTo("MEMBER");
            assertThat(entry.get("entity_label")).isEqualTo("Бот Тарас");
            assertThat((String) entry.get("changes")).doesNotContain(Long.toString(chat));
        });
    }

    @Test
    void aMemberWithSeveralPlotsHearsAllTheirNumbers() throws Exception {
        startBot();
        Owner owner = owner("Олена", 2);
        long chat = chat();

        Sent greeting = lastReply(chat, "/start " + accountCode(owner.id()), 1);

        assertThat(greeting.text()).startsWith("Вітаємо, Олена! Тепер рахунки за ділянки ")
                .contains(owner.plots().get(0)).contains(owner.plots().get(1));
    }

    @Test
    void aCodeWorksOnce() throws Exception {
        startBot();
        Owner owner = owner("Іван", 1);
        String code = accountCode(owner.id());
        long first = chat();
        long second = chat();
        lastReply(first, "/start " + code, 1);

        Sent reply = lastReply(second, "/start " + code, 1);

        assertThat(reply.text()).isEqualTo(INVALID);
        assertThat(reply.buttons()).isEmpty();
        assertThat(linkedMember(second)).isNull();
        assertThat(linkedMember(first)).isEqualTo(owner.id());
        assertThat(linkHistory(owner.id())).containsExactly("ACTIVE");
    }

    @Test
    void anAccountCodeOlderThanThirtyMinutesIsInvalid() throws Exception {
        startBot();
        Owner owner = owner("Петро", 1);
        String code = accountCode(owner.id());
        // issued 31 minutes ago
        ownerJdbc().update("update telegram_link_code set created_at = created_at - interval '31 minutes', "
                + "expires_at = expires_at - interval '31 minutes' where member_id = ?", owner.id());
        long chat = chat();

        assertThat(lastReply(chat, "/start " + code, 1).text()).isEqualTo(INVALID);
        assertThat(linkedMember(chat)).isNull();
    }

    @Test
    void anUnknownOrMalformedCodeIsInvalid() throws Exception {
        startBot();
        long chat = chat();

        assertThat(lastReply(chat, "/start AAAAAAAAAAAAAAAAAAAAAA", 1).text()).isEqualTo(INVALID);
        assertThat(lastReply(chat, "/start not-a-code", 2).text()).isEqualTo(INVALID);
        assertThat(linkedMember(chat)).isNull();
    }

    @Test
    void aChatOfAnotherMemberMovesOnlyAfterTheConfirmation() throws Exception {
        startBot();
        Owner anna = owner("Анна", 1);
        Owner bohdan = owner("Богдан", 1);
        long chat = chat();
        link(chat, anna.id());
        String code = accountCode(bohdan.id());

        Sent question = lastReply(chat, "/start " + code, 2);
        assertThat(question.text())
                .isEqualTo("Цей Telegram уже підключено до іншого члена кооперативу. Це ви, Богдан?");
        assertThat(question.buttons()).containsExactly("Так, це я | Ні");
        assertThat(linkedMember(chat)).isEqualTo(anna.id());

        // «Ні»: nothing changes, and the code is still unused
        assertThat(lastReply(chat, "Ні", 3).text()).isEqualTo("Гаразд, нічого не змінено.");
        assertThat(linkedMember(chat)).isEqualTo(anna.id());

        lastReply(chat, "/start " + code, 4);
        Sent greeting = lastReply(chat, "Так, це я", 5);

        assertThat(greeting.text()).startsWith("Вітаємо, Богдан!");
        assertThat(linkedMember(chat)).isEqualTo(bohdan.id());
        assertThat(linkHistory(anna.id())).containsExactly("MOVED");
        assertThat(linkHistory(bohdan.id())).containsExactly("ACTIVE");
        assertThat(memberAudit(anna.id(), "TELEGRAM_UNLINKED", 1)).singleElement()
                .satisfies(entry -> assertThat((String) entry.get("changes")).contains("MOVED"));
        memberAudit(bohdan.id(), "TELEGRAM_LINKED", 1);
    }

    @Test
    void stopInTheBotUnlinks() throws Exception {
        startBot();
        Owner owner = owner("Марія", 1);
        long chat = chat();
        link(chat, owner.id());

        Sent reply = lastReply(chat, "/stop", 2);

        assertThat(reply.text()).startsWith("Telegram відключено");
        assertThat(reply.removesKeyboard()).isTrue();
        assertThat(linkedMember(chat)).isNull();
        assertThat(linkHistory(owner.id())).containsExactly("UNLINKED");
        myNotifications(owner.bearer()).andExpect(jsonPath("$.telegramLinked").value(false));
        assertThat(memberAudit(owner.id(), "TELEGRAM_UNLINKED", 1)).singleElement()
                .satisfies(entry -> assertThat((String) entry.get("changes")).contains("endReason")
                        .contains("UNLINKED"));
    }

    @Test
    void theUnlinkButtonUnlinks() throws Exception {
        startBot();
        Owner owner = owner("Ганна", 1);
        long chat = chat();
        link(chat, owner.id());

        assertThat(lastReply(chat, "Відключити", 2).text()).startsWith("Telegram відключено");
        assertThat(linkedMember(chat)).isNull();
    }

    @Test
    void unlinkingInTheAccountEndsTheLink() throws Exception {
        startBot();
        Owner owner = owner("Сергій", 1);
        long chat = chat();
        link(chat, owner.id());

        deleteLink(owner.bearer()).andExpect(status().isNoContent());
        deleteLink(owner.bearer()).andExpect(status().isNoContent());

        myNotifications(owner.bearer()).andExpect(jsonPath("$.telegramLinked").value(false));
        assertThat(linkedMember(chat)).isNull();
        assertThat(linkHistory(owner.id())).containsExactly("UNLINKED");
        assertThat(memberAudit(owner.id(), "TELEGRAM_UNLINKED", 1)).hasSize(1);
        // the chat is a stranger now: «Борг» gets the invitation, no data
        assertThat(lastReply(chat, "Борг", 2).text()).startsWith("Це бот кооперативу").contains("Підключити Telegram");
    }

    @Test
    void aMemberWhoBlockedTheBotIsUnlinkedOnTheNextReply() throws Exception {
        startBot();
        Owner owner = owner("Віктор", 1);
        long chat = chat();
        link(chat, owner.id());
        telegram.block(chat);

        long update = telegram.pushMessage(TOKEN, chat, "Борг");
        awaitTaken(update);

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> linkHistory(owner.id()).equals(List.of("BLOCKED")));
        assertThat(linkedMember(chat)).isNull();
    }

    @Test
    void theChatIdIsNeverStoredInTheClear() throws Exception {
        startBot();
        Owner owner = owner("Оксана", 1);
        long chat = chat();
        link(chat, owner.id());
        memberAudit(owner.id(), "TELEGRAM_LINKED", 1);
        byte[] plain = Long.toString(chat).getBytes(StandardCharsets.UTF_8);

        Map<String, Object> row = inTenant(DEMO_TENANT, sql -> sql.queryForMap(
                "select id, chat_id_enc, chat_id_bidx from telegram_link where member_id = ?", owner.id()));
        assertThat(contains((byte[]) row.get("chat_id_enc"), plain)).isFalse();
        assertThat(contains((byte[]) row.get("chat_id_bidx"), plain)).isFalse();
        // it opens with the cooperative's key at the link's field reference
        String opened = TenantContext.runAs(DEMO_TENANT, () -> cipher.decrypt(DEMO_TENANT,
                new FieldRef("telegram_link", "chat_id_enc", (UUID) row.get("id")), (byte[]) row.get("chat_id_enc")));
        assertThat(opened).isEqualTo(Long.toString(chat));
        // nor in the audit log or the outbox
        assertThat(ownerJdbc().queryForObject("select count(*) from audit_entry where cast(changes as text) like ? "
                + "or entity_label like ?", Integer.class, "%" + chat + "%", "%" + chat + "%")).isZero();
        assertThat(ownerJdbc().queryForObject("select count(*) from event_publication where serialized_event like ?",
                Integer.class, "%" + chat + "%")).isZero();
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
