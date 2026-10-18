package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class CadastralNumberTests {

    @Test
    void acceptsTheFourGroupFormat() {
        assertThat(CadastralNumber.parse("4810136300:02:001:0001")).map(CadastralNumber::value)
                .contains("4810136300:02:001:0001");
    }

    @Test
    void ignoresSurroundingWhitespace() {
        assertThat(CadastralNumber.parse("  1234567890:12:345:6789 ")).map(CadastralNumber::value)
                .contains("1234567890:12:345:6789");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "123:45",
            "1234567890:12:345",
            "1234567890:12:345:6789:1",
            "123456789:12:345:6789",
            "12345678901:12:345:6789",
            "1234567890:1:345:6789",
            "1234567890:12:34:6789",
            "1234567890:12:345:678",
            "1234567890-12-345-6789",
            "1234567890:12:345:67a9",
            "1234567890: 12:345:6789",
            "١٢٣٤٥٦٧٨٩٠:12:345:6789"
    })
    void rejectsAnythingElse(String raw) {
        assertThat(CadastralNumber.parse(raw)).isEmpty();
    }
}
