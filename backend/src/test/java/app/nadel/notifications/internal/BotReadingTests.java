package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.documents.DocumentFormats;
import app.nadel.meters.ReadingDecided;
import app.nadel.meters.ReadingStatus;
import app.nadel.notifications.FakeBotApi.Sent;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The bot's «Показання» (feature 017 §2.5): the plot (asked only when there are several), the meter, the value; the
 * reading waits for the board's approval dated today, exactly as from the account (the same refusals, in plain words,
 * and the member types again); the board's approval or rejection reaches the member's chat; another member's plot or
 * meter is never reachable.
 */
class BotReadingTests extends BotCommandsTestSupport {

    @Autowired
    ReadingMessages readingMessages;

    @Test
    void aReadingIsSubmittedForApprovalDatedTodayAndAOnePlotMemberIsNotAskedForThePlot() throws Exception {
        startBot();
        Member owner = member("Лисенко");
        UUID plot = plot("9", owner.id());
        UUID meter = meter(plot, "ЕЛ-9/2", "ELECTRICITY");
        entered(meter, today().minusDays(1), "12000");
        long chat = link(owner.id());

        Sent meters = lastReply(chat, "Показання", 1);
        assertThat(meters.text()).isEqualTo("Оберіть лічильник ділянки 9:");
        assertThat(meters.buttons()).containsExactly("⚡ ЕЛ-9/2", "Скасувати");
        Sent prompt = lastReply(chat, "⚡ ЕЛ-9/2", 2);
        assertThat(prompt.text()).isEqualTo("Введіть показання лічильника ЕЛ-9/2 — лише число, наприклад 12345 або "
                + "12345,5.");
        assertThat(prompt.buttons()).containsExactly(MENU_ROW_1, MENU_ROW_2);

        long update = telegram.pushMessage(TOKEN, chat, "12345");
        Sent answer = awaitSent(chat, 3).get(2);

        assertThat(answer.text()).isEqualTo("Показання 12" + NBSP + "345 по лічильнику ЕЛ-9/2 передано на перевірку");
        assertThat(answer.buttons()).containsExactly(MENU_ROW_1, MENU_ROW_2);
        // the update was taken (its offset saved) and the reading submitted once
        awaitTaken(update);
        List<Map<String, Object>> pending = readingsOf(meter).stream()
                .filter(row -> "PENDING".equals(row.get("status"))).toList();
        assertThat(pending).singleElement().satisfies(row -> {
            assertThat(((Date) row.get("taken_on")).toLocalDate()).isEqualTo(today());
            assertThat((BigDecimal) row.get("value")).isEqualByComparingTo("12345");
            assertThat(row.get("source")).isEqualTo("MEMBER");
            assertThat(row.get("entered_by_member_id")).isEqualTo(owner.id());
        });
        // the board sees it for approval
        send(chairman, get("/api/v1/readings").param("status", "PENDING"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reading.id").value(pending.getFirst().get("id").toString()));
    }

    @Test
    void aValueBelowThePreviousIsRefusedAsInTheAccountAndTheMemberTypesAgain() throws Exception {
        startBot();
        Member owner = member("Гончар");
        UUID plot = plot("9", owner.id());
        UUID meter = meter(plot, "ЕЛ-9/2", "ELECTRICITY");
        LocalDate yesterday = today().minusDays(1);
        entered(meter, yesterday, "12000");
        long chat = link(owner.id());
        say(chat, "Показання", 1);
        say(chat, "⚡ ЕЛ-9/2", 2);

        Sent refused = lastReply(chat, "11999", 3);

        assertThat(refused.text()).isEqualTo("Показання менше за попереднє або більше за наступне. Попереднє "
                + "показання: 12" + NBSP + "000 кВт·год, " + DocumentFormats.day(yesterday) + ".\n"
                + "Введіть показання ще раз.");
        // the account refuses the same value with the same code
        send(owner.bearer(), post("/api/v1/plots/" + plot + "/readings"), Map.of("meterId", meter.toString(),
                "value", "11999")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_OUT_OF_ORDER"));

        Sent notANumber = lastReply(chat, "дванадцять тисяч", 4);
        assertThat(notANumber.text()).isEqualTo("Це не схоже на показання. Введіть лише число, наприклад 12345 або "
                + "12345,5.");
        Sent tooPrecise = lastReply(chat, "12345,1234", 5);
        assertThat(tooPrecise.text()).startsWith("Показання — невід’ємне число").endsWith("Введіть показання ще раз.");
        assertThat(readingsOf(meter)).hasSize(1);

        Sent accepted = lastReply(chat, "12345,5", 6);
        assertThat(accepted.text()).isEqualTo("Показання 12" + NBSP + "345,5 по лічильнику ЕЛ-9/2 передано на "
                + "перевірку");
        assertThat(readingsOf(meter)).hasSize(2);
    }

    @Test
    void theMemberIsToldWhenTheBoardApprovesOrRejectsTheReading() throws Exception {
        startBot();
        Member owner = member("Савченко");
        UUID plot = plot("9", owner.id());
        UUID electricity = meter(plot, "ЕЛ-9/2", "ELECTRICITY");
        UUID water = meter(plot, "В-9", "WATER");
        long chat = link(owner.id());
        say(chat, "Показання", 1);
        say(chat, "⚡ ЕЛ-9/2", 2);
        say(chat, "12345", 3);
        UUID approved = (UUID) readingsOf(electricity).getFirst().get("id");

        send(chairman, post("/api/v1/readings/" + approved + "/approve"), null).andExpect(status().isOk());

        assertThat(awaitSent(chat, 4).get(3).text()).isEqualTo("Показання 12" + NBSP + "345 по лічильнику ЕЛ-9/2 "
                + "прийнято");

        assertThat(lastReply(chat, "Показання", 5).buttons()).containsExactly("⚡ ЕЛ-9/2", "💧 В-9", "Скасувати");
        say(chat, "💧 В-9", 6);
        say(chat, "1080", 7);
        UUID rejected = (UUID) readingsOf(water).getFirst().get("id");

        send(chairman, post("/api/v1/readings/" + rejected + "/reject"), Map.of("reason", "Фото нечітке, "
                + "передайте ще раз")).andExpect(status().isOk());

        assertThat(awaitSent(chat, 8).get(7).text()).isEqualTo("Показання 1" + NBSP + "080 по лічильнику В-9 "
                + "відхилено: Фото нечітке, передайте ще раз");
        // a redelivered decision is not told twice
        assertThat(readingMessages.tell(new ReadingDecided(tenant, rejected, plot, owner.id(),
                ReadingStatus.REJECTED))).isFalse();
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                .until(() -> telegram.sent(chat).size() == 8);
    }

    @Test
    void aMemberWithSeveralPlotsChoosesOneAndNeverReachesAnotherMembersPlotOrMeter() throws Exception {
        startBot();
        Member owner = member("Кравець");
        Member neighbour = member("Сусід");
        UUID mine = plot("31", owner.id());
        plot("32", owner.id());
        UUID theirs = plot("33", neighbour.id());
        meter(mine, "ЕЛ-1", "ELECTRICITY");
        UUID theirMeter = meter(theirs, "ЕЛ-2", "ELECTRICITY");
        long chat = link(owner.id());

        Sent question = lastReply(chat, "Показання", 1);
        assertThat(question.text()).isEqualTo("Показання якої ділянки?");
        assertThat(question.buttons()).containsExactly("Ділянка 31", "Ділянка 32", "Скасувати");
        assertThat(lastReply(chat, "Ділянка 33", 2).text()).isEqualTo("Оберіть, будь ласка, кнопкою нижче.");
        assertThat(lastReply(chat, "Ділянка 32", 3).text())
                .isEqualTo("На ділянці 32 немає лічильників, показання яких можна передати.");

        say(chat, "Показання", 4);
        Sent meters = lastReply(chat, "Ділянка 31", 5);
        assertThat(meters.buttons()).containsExactly("⚡ ЕЛ-1", "Скасувати");
        Sent again = lastReply(chat, "⚡ ЕЛ-2", 6);
        assertThat(again.text()).isEqualTo("Оберіть, будь ласка, кнопкою нижче.");
        assertThat(again.buttons()).containsExactly("⚡ ЕЛ-1", "Скасувати");
        say(chat, "12345", 7);
        assertThat(readingsOf(theirMeter)).isEmpty();
    }
}
