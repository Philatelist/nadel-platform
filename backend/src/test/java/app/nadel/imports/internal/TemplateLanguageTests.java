package app.nadel.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

/** Which language a template download gets: the parameter, then Accept-Language, then Ukrainian. */
class TemplateLanguageTests {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "uk-UA | UK",
            "uk | UK",
            "en-US | EN",
            "en | EN",
            "EN-gb | EN",
            "de | UK",
            "fr-FR,de;q=0.8 | UK",
            "*  | UK",
            "en-US,en;q=0.9,uk;q=0.8 | EN",
            "uk;q=0.2, en;q=0.9 | EN",
            "de, uk;q=0.3, en;q=0.2 | UK",
            "de, en;q=0.5 | EN",
            "en;q=0, uk;q=0.5 | UK",
            "en;q=0 | UK",
            "not a language tag at all !!! | UK"})
    void theAcceptLanguageHeaderPicksTheBestMatchOfUkrainianAndEnglish(String header, TemplateLanguage expected) {
        assertThat(TemplateLanguage.resolve(null, header)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void withoutAnyPreferenceTheDefaultIsUkrainian(String header) {
        assertThat(TemplateLanguage.resolve(null, header)).isEqualTo(TemplateLanguage.UK);
        assertThat(TemplateLanguage.DEFAULT).isEqualTo(TemplateLanguage.UK);
    }

    @Test
    void theLangParameterWinsOverAcceptLanguage() {
        assertThat(TemplateLanguage.resolve("en", "uk-UA")).isEqualTo(TemplateLanguage.EN);
        assertThat(TemplateLanguage.resolve("uk", "en-US")).isEqualTo(TemplateLanguage.UK);
        assertThat(TemplateLanguage.resolve("en", null)).isEqualTo(TemplateLanguage.EN);
    }

    @Test
    void fileNamesAreAscii() {
        assertThat(TemplateLanguage.UK.fileName()).isEqualTo("nadel-import-template-uk.xlsx");
        assertThat(TemplateLanguage.EN.fileName()).isEqualTo("nadel-import-template-en.xlsx");
    }
}
