package app.nadel.notifications.internal.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The push message is codes only: exactly {@code v}, {@code code}, the code's own parameters of a known shape,
 * {@code url} and {@code lang}. No amount, plot, name or address can be put in it.
 */
class PushPayloadTests {

    @Test
    void theInvoicesMessageIsTheCodeTheMonthTheScreenAndTheLanguage() {
        byte[] payload = PushPayload.of(PushCode.INVOICES_ISSUED, Map.of("month", "2026-10"), "uk");

        assertThat(new String(payload, StandardCharsets.UTF_8)).isEqualTo("{\"v\":1,\"code\":\"INVOICES_ISSUED\","
                + "\"params\":{\"month\":\"2026-10\"},\"url\":\"/account#my-invoices\",\"lang\":\"uk\"}");
        assertThat(new String(PushPayload.of(PushCode.INVOICES_ISSUED, Map.of("month", "2026-01"), "en"),
                StandardCharsets.UTF_8)).endsWith("\"lang\":\"en\"}");
    }

    @Test
    void nothingButTheCodesParametersFitsIn() {
        for (String extra : List.of("amount", "plot", "name", "address")) {
            assertThatThrownBy(() -> PushPayload.of(PushCode.INVOICES_ISSUED,
                    Map.of("month", "2026-10", extra, "1250.50"), "uk"))
                    .as(extra).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> PushPayload.of(PushCode.INVOICES_ISSUED, Map.of(), "uk"))
                .isInstanceOf(IllegalArgumentException.class);
        for (String month : List.of("2026-13", "2026-1", "Жовтень", "1250.50", "2026-10 ділянка 9")) {
            assertThatThrownBy(() -> PushPayload.of(PushCode.INVOICES_ISSUED, Map.of("month", month), "uk"))
                    .as(month).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> PushPayload.of(PushCode.INVOICES_ISSUED, Map.of("month", "2026-10"), "de"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyCodesMessageHasOnlyTheKnownFieldsAndNoDigitsButTheMonth() {
        for (PushCode code : PushCode.values()) {
            Map<String, String> params = code.params().isEmpty() ? Map.of() : Map.of("month", "2026-10");
            JsonNode message = JsonMapper.shared().readTree(PushPayload.of(code, params, "uk"));

            assertThat(message.propertyNames()).containsExactly("v", "code", "params", "url", "lang");
            assertThat(message.get("params").propertyNames()).containsExactlyInAnyOrderElementsOf(code.params());
            assertThat(message.get("url").asString()).startsWith("/").doesNotContain("//");
            // no amount and no plot number: the only digits are the format version and the month
            String text = message.toString().replace("2026-10", "").replace("\"v\":1", "");
            assertThat(text).doesNotContainPattern("\\d");
        }
    }
}
