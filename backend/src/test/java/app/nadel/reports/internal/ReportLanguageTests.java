package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The language of a report file: {@code lang}, else {@code Accept-Language}, else Ukrainian (as the import template). */
class ReportLanguageTests {

    @Test
    void theParameterDecidesFirst() {
        assertThat(ReportLanguage.resolve("en", "uk-UA")).isEqualTo(ReportLanguage.EN);
        assertThat(ReportLanguage.resolve("uk", "en-US")).isEqualTo(ReportLanguage.UK);
    }

    @Test
    void otherwiseTheBestMatchOfTheHeader() {
        assertThat(ReportLanguage.resolve(null, "en-US,en;q=0.9,uk;q=0.8")).isEqualTo(ReportLanguage.EN);
        assertThat(ReportLanguage.resolve(null, "de-DE,uk;q=0.5,en;q=0.4")).isEqualTo(ReportLanguage.UK);
        assertThat(ReportLanguage.resolve(null, "en;q=0,uk;q=0.1")).isEqualTo(ReportLanguage.UK);
    }

    @Test
    void otherwiseUkrainian() {
        assertThat(ReportLanguage.resolve(null, null)).isEqualTo(ReportLanguage.UK);
        assertThat(ReportLanguage.resolve(null, " ")).isEqualTo(ReportLanguage.UK);
        assertThat(ReportLanguage.resolve(null, "de-DE")).isEqualTo(ReportLanguage.UK);
        assertThat(ReportLanguage.resolve(null, "en;q=x")).isEqualTo(ReportLanguage.UK);
    }

    @Test
    void anUnknownParameterIsAProgrammingError() {
        assertThatThrownBy(() -> ReportLanguage.resolve("de", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
