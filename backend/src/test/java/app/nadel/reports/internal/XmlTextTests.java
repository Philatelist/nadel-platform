package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The one rule for characters XML 1.0 forbids in written values: each becomes a space, valid pairs stay. */
class XmlTextTests {

    @Test
    void textWithoutForbiddenCharactersIsReturnedAsItIs() {
        String text = "СТ «Сад» & <Ко>\t\n\r \uD7FF\uE000\uFFFD\uD83C\uDF3B\uDBFF\uDFFF";

        assertThat(XmlText.clean(text)).isSameAs(text);
        assertThat(XmlText.clean("")).isEmpty();
    }

    @Test
    void everyForbiddenCharacterBecomesASpace() {
        assertThat(XmlText.clean("a\u0000b\u0001c\u0008d\u000Be\u000Cf\u001Fg")).isEqualTo("a b c d e f g");
        assertThat(XmlText.clean("\uFFFE\uFFFF")).isEqualTo("  ");
    }

    @Test
    void unpairedSurrogatesBecomeSpacesAndValidPairsStay() {
        assertThat(XmlText.clean("\uD800")).isEqualTo(" ");
        assertThat(XmlText.clean("\uDC00")).isEqualTo(" ");
        assertThat(XmlText.clean("x\uD800y\uDC00z")).isEqualTo("x y z");
        // reversed order is two unpaired surrogates
        assertThat(XmlText.clean("\uDC00\uD800")).isEqualTo("  ");
        // a high surrogate before a valid pair, and one at the end
        assertThat(XmlText.clean("\uD800\uD83C\uDF3B\uD800")).isEqualTo(" \uD83C\uDF3B ");
        assertThat(XmlText.clean("\u000B\uD83C\uDF3B")).isEqualTo(" \uD83C\uDF3B");
    }
}
