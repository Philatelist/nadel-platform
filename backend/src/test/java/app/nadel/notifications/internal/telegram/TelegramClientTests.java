package app.nadel.notifications.internal.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import app.nadel.notifications.ClosedPort;
import app.nadel.notifications.FakeBotApi;
import app.nadel.notifications.FakeBotApi.Reply;
import app.nadel.notifications.internal.telegram.TelegramException.Kind;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * How the Bot API client reads Telegram's answers (feature 017 §2.5) against the in-JVM fake Bot API: each refusal
 * becomes the {@link Kind} the callers act on (a token Telegram cannot parse counts as refused, an answer that is not
 * Telegram's envelope as unavailable, a 429 waits only as long as Telegram said), a server that cannot be reached is
 * unavailable, a token with characters a URL could be bent with never leaves the process, and no message carries the
 * token.
 */
class TelegramClientTests {

    private static final String TOKEN = "123456789:AAclientTestsToken0001";

    private final FakeBotApi fake = FakeBotApi.shared();

    @AfterEach
    void resetTheFake() {
        fake.reset();
    }

    @Test
    void aBotWithoutAUsernameIsNotUsable() {
        fake.handle("getMe", (token, body) -> Reply.ok("{\"id\":42,\"is_bot\":true,\"first_name\":\"No name\"}"));

        TelegramException refused = catchThrowableOfType(TelegramException.class,
                () -> client(fake.url()).getMe(TOKEN));

        assertThat(refused.kind()).isEqualTo(Kind.UNAVAILABLE);
        assertThat(refused.getMessage()).doesNotContain(TOKEN);
    }

    @Test
    void eachRefusalBecomesTheKindTheCallersActOn() {
        assertThat(refusal(Reply.error(404, "Not Found")).kind()).isEqualTo(Kind.UNAUTHORIZED);
        assertThat(refusal(Reply.error(403, "Forbidden: bot was blocked by the user")).kind())
                .isEqualTo(Kind.FORBIDDEN);
        assertThat(refusal(Reply.error(400, "Bad Request: chat not found")).kind()).isEqualTo(Kind.REJECTED);
        assertThat(refusal(Reply.error(500, "Internal Server Error")).kind()).isEqualTo(Kind.UNAVAILABLE);
        TelegramException slowDown = refusal(Reply.tooManyRequests(9));
        assertThat(slowDown.kind()).isEqualTo(Kind.TOO_MANY_REQUESTS);
        assertThat(slowDown.retryAfter()).contains(Duration.ofSeconds(9));
    }

    @Test
    void anAnswerThatIsNotTelegramsEnvelopeIsReadByItsStatusOnly() {
        // a proxy's HTML page
        TelegramException proxy = refusal(new Reply(502, "<html><body>Bad Gateway</body></html>"));
        assertThat(proxy.kind()).isEqualTo(Kind.UNAVAILABLE);
        assertThat(proxy.getMessage()).contains("HTTP 502").doesNotContain(TOKEN);
        // JSON, but not an object
        assertThat(refusal(new Reply(400, "[1, 2]")).kind()).isEqualTo(Kind.REJECTED);
        // a 429 without Telegram's parameters gives no time to wait
        TelegramException slowDown = refusal(new Reply(429, "Too Many Requests"));
        assertThat(slowDown.kind()).isEqualTo(Kind.TOO_MANY_REQUESTS);
        assertThat(slowDown.retryAfter()).isEmpty();
        // a 200 that is not ok
        assertThat(refusal(new Reply(200, "{\"ok\":false,\"error_code\":400}")).kind()).isEqualTo(Kind.REJECTED);
    }

    @Test
    void aServerThatCannotBeReachedIsUnavailableAndTheMessageNeverHoldsTheToken() {
        TelegramClient client = client("http://127.0.0.1:" + ClosedPort.next());

        TelegramException refused = catchThrowableOfType(TelegramException.class, () -> client.getMe(TOKEN));

        assertThat(refused.kind()).isEqualTo(Kind.UNAVAILABLE);
        assertThat(refused.getMessage()).startsWith("Telegram getMe failed: ").doesNotContain(TOKEN)
                .doesNotContain("127.0.0.1");
        assertThat(refused.getCause()).isNull();
    }

    @Test
    void aTokenWithOtherCharactersNeverReachesAUrl() {
        int before = fake.calls().size();

        assertThatThrownBy(() -> client(fake.url()).getMe("123:abc/../../sendMessage?x="))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("sendMessage");
        assertThatThrownBy(() -> client(fake.url()).sendMessage(null, 1L, "text", null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(fake.calls()).hasSize(before);
    }

    /** What {@code getMe} throws when Telegram answers with the reply. */
    private TelegramException refusal(Reply reply) {
        fake.failNext("getMe", reply);
        return catchThrowableOfType(TelegramException.class, () -> client(fake.url()).getMe(TOKEN));
    }

    private static TelegramClient client(String url) {
        return new TelegramClient(new TelegramProperties(URI.create(url), Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ZERO, false, null, null), JsonMapper.shared());
    }
}
