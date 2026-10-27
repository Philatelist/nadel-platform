package app.nadel.reports.internal;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The language of a report file's texts (sheet names, header block, column headers, codes). Resolved like the
 * language of the import template ({@code imports.internal.TemplateLanguage}): the {@code lang} parameter, otherwise
 * the best match of Ukrainian and English in {@code Accept-Language}, otherwise Ukrainian. The preview has no
 * language: it carries codes and column keys only.
 */
enum ReportLanguage {

    UK("uk"),
    EN("en");

    /** The language when neither the request parameter nor {@code Accept-Language} decides. */
    static final ReportLanguage DEFAULT = UK;

    private static final List<Locale> OFFERED = List.of(Locale.of(UK.code), Locale.of(EN.code));

    private final String code;

    ReportLanguage(String code) {
        this.code = code;
    }

    /** The code of the API and of the message bundle: {@code uk} or {@code en}. */
    String code() {
        return code;
    }

    /**
     * The language of a request: {@code lang} when given ({@code uk-UA} in {@code Accept-Language} is Ukrainian,
     * {@code en-US} is English), otherwise {@link #DEFAULT}. The contract has already refused a {@code lang} other
     * than {@code uk} and {@code en}; a malformed header is no preference.
     */
    static ReportLanguage resolve(@Nullable String lang, @Nullable String acceptLanguage) {
        if (lang != null) {
            return of(lang);
        }
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return DEFAULT;
        }
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguage).stream()
                    .filter(range -> range.getWeight() > 0)
                    .toList();
            Locale best = Locale.lookup(ranges, OFFERED);
            return best == null ? DEFAULT : of(best.getLanguage());
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }

    private static ReportLanguage of(String code) {
        for (ReportLanguage language : values()) {
            if (language.code.equals(code)) {
                return language;
            }
        }
        throw new IllegalArgumentException("Unknown report language");
    }
}
