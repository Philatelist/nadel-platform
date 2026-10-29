package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.notifications.FakeBotApi;
import app.nadel.notifications.FakeBotApi.Reply;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * «Перевірити бота» (feature 017 §2.1) against the in-JVM fake Bot API: a valid token answers the bot's @name, which is
 * kept; a token Telegram refuses is {@code TOKEN_INVALID}; no answer is {@code TELEGRAM_UNAVAILABLE}; the token never
 * reaches a log or a response.
 */
@ExtendWith(OutputCaptureExtension.class)
class TestTelegramTests extends DeliverySettingsTestSupport {

    private static final String TOKEN = "123456789:AAgoodTokenForTheFakeBotApi1234";
    private static final String BAD_TOKEN = "bad456789:AAtokenTelegramDoesNotKnow99";

    private final FakeBotApi telegram = FakeBotApi.shared();

    @Test
    void aValidTokenAnswersTheBotsName(CapturedOutput output) throws Exception {
        telegram.username("coop_nadiia_bot");
        save(chairman(), Map.of("telegram", Map.of("botToken", TOKEN))).andExpect(status().isOk());

        String body = testTelegram(chairman()).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.botUsername").value("coop_nadiia_bot"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(telegram.calls("getMe")).singleElement()
                .satisfies(call -> assertThat(call.tokenLast4()).isEqualTo("1234"));
        // the @name is kept for the settings page (and the bot's links)
        read(chairman()).andExpect(jsonPath("$.telegram.botUsername").value("coop_nadiia_bot"))
                .andExpect(jsonPath("$.telegram.tokenLast4").value("1234"));
        assertThat(body).doesNotContain(TOKEN);
        assertThat(output.getAll()).doesNotContain(TOKEN).doesNotContain("AAgoodTokenForTheFakeBotApi");
    }

    @Test
    void aTokenTelegramRefusesIsInvalid(CapturedOutput output) throws Exception {
        save(chairman(), Map.of("telegram", Map.of("botToken", BAD_TOKEN))).andExpect(status().isOk());

        testTelegram(chairman()).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"))
                .andExpect(jsonPath("$.botUsername").doesNotExist());

        read(chairman()).andExpect(jsonPath("$.telegram.botUsername").doesNotExist());
        assertThat(output.getAll()).doesNotContain(BAD_TOKEN);
    }

    @Test
    void noAnswerFromTelegramIsUnavailable(CapturedOutput output) throws Exception {
        save(chairman(), Map.of("telegram", Map.of("botToken", TOKEN))).andExpect(status().isOk());
        telegram.failNext("getMe", Reply.error(502, "Bad Gateway"));

        testTelegram(chairman()).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value("TELEGRAM_UNAVAILABLE"));
        assertThat(output.getAll()).contains("Telegram getMe failed: HTTP 502").doesNotContain(TOKEN);
    }

    @Test
    void withoutATokenThereIsNothingToCheck() throws Exception {
        testTelegram(chairman()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_CONFIGURED"));
        assertThat(telegram.calls()).isEmpty();
    }
}
