package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import app.nadel.notifications.FakeBotApi.Sent;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The bot treats every message as untrusted input (feature 017 §2.5): anything but a command or a menu button gets
 * the help text, a text over 64 characters is refused, a chat sending more than 20 messages a minute gets one warning
 * and then silence, an unlinked chat gets only the invitation and no data, and no text reaches a log.
 */
@ExtendWith(OutputCaptureExtension.class)
class BotInputTests extends BotTestSupport {

    @Test
    void anyOtherTextGetsTheHelpAndTheMenu(CapturedOutput output) throws Exception {
        startBot();
        Owner owner = owner("Лариса", 1);
        long chat = chat();
        link(chat, owner.id());

        Sent help = lastReply(chat, "секретне повідомлення про щось", 2);

        assertThat(help.text()).startsWith(helpStart()).contains("«Борг»").contains("«Показання»");
        assertThat(help.buttons()).containsExactly(MENU_ROW_1, MENU_ROW_2);
        assertThat(lastReply(chat, "/help", 3).text()).isEqualTo(help.text());
        assertThat(lastReply(chat, "/start", 4).text()).isEqualTo(help.text());
        assertThat(output.getAll()).doesNotContain("секретне повідомлення").doesNotContain(Long.toString(chat));
    }

    @Test
    void theMenuActionsAnswerTheLinkedMemberAndKeepTheMenu() throws Exception {
        startBot();
        Owner owner = owner("Роман", 1);
        String number = owner.plots().getFirst();
        long chat = chat();
        link(chat, owner.id());

        Sent debt = lastReply(chat, "Борг", 2);
        assertThat(debt.text()).isEqualTo("Ділянка " + number + "\nБоргу немає");
        assertThat(debt.buttons()).containsExactly(MENU_ROW_1, MENU_ROW_2);
        Sent readings = lastReply(chat, "Показання", 3);
        assertThat(readings.text()).isEqualTo("На ділянці " + number + " немає лічильників, показання яких можна "
                + "передати.");
        assertThat(readings.buttons()).containsExactly(MENU_ROW_1, MENU_ROW_2);
    }

    @Test
    void aTextOverSixtyFourCharactersIsRefused() throws Exception {
        startBot();
        Owner owner = owner("Тетяна", 1);
        long chat = chat();
        link(chat, owner.id());

        assertThat(lastReply(chat, "x".repeat(65), 2).text())
                .isEqualTo("Повідомлення задовге: бот читає не більше 64 символів.");
        assertThat(lastReply(chat, "x".repeat(64), 3).text()).startsWith(helpStart());
    }

    @Test
    void aChatSendingTooManyMessagesIsWarnedOnceThenIgnored() throws Exception {
        startBot();
        long chat = chat();
        long last = 0;
        for (int i = 0; i < 25; i++) {
            last = telegram.pushMessage(TOKEN, chat, "повідомлення " + i);
        }
        awaitTaken(last);
        await().atMost(Duration.ofSeconds(10)).until(() -> telegram.sent(chat).size() >= 21);
        // the last update was taken; its (empty) answer had time to go out
        Thread.sleep(300);

        List<Sent> replies = telegram.sent(chat);
        assertThat(replies).hasSize(21);
        assertThat(replies.subList(0, 20)).allSatisfy(reply -> assertThat(reply.text()).startsWith(helpStart()));
        assertThat(replies.get(20).text()).isEqualTo("Забагато повідомлень, спробуйте за хвилину");
    }

    @Test
    void anUnlinkedChatGetsOnlyTheInvitation() throws Exception {
        startBot();
        long chat = chat();

        for (String text : List.of("Борг", "Рахунок", "Показання", "/help", "/start", "Відключити", "/stop")) {
            int before = telegram.sent(chat).size();
            Sent reply = lastReply(chat, text, before + 1);
            assertThat(reply.text()).startsWith(helpStart())
                    .contains("«Підключити Telegram»").doesNotContain("₴");
            assertThat(reply.removesKeyboard()).isTrue();
        }
    }

    @Test
    void aMessageWithoutTextGetsTheHelpAndAGroupGetsNothing() throws Exception {
        startBot();
        long chat = chat();
        long group = -chat();

        long fromGroup = telegram.pushGroupMessage(TOKEN, group, "Борг");
        long sticker = telegram.pushMessage(TOKEN, chat, null);
        awaitTaken(Math.max(fromGroup, sticker));
        await().atMost(Duration.ofSeconds(10)).until(() -> telegram.sent(chat).size() == 1);

        assertThat(telegram.sent(chat).getFirst().text()).startsWith(helpStart());
        assertThat(telegram.sent(group)).isEmpty();
    }

    private String helpStart() {
        String name = inTenant(DEMO_TENANT, sql -> sql.queryForObject("select name from tenant", String.class));
        return "Це бот кооперативу " + name + ".";
    }
}
