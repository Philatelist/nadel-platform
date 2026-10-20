package app.nadel.imports.internal;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** The language of the downloadable import template. */
enum TemplateLanguage {

    UK("uk"),
    EN("en");

    /** The language of a template when neither the request parameter nor {@code Accept-Language} decides. */
    static final TemplateLanguage DEFAULT = UK;

    private static final List<Locale> OFFERED = List.of(Locale.of(UK.code), Locale.of(EN.code));

    private final String code;

    TemplateLanguage(String code) {
        this.code = code;
    }

    /** The code used in the API and in the file name: {@code uk} or {@code en}. */
    String code() {
        return code;
    }

    /** The ASCII file name of the template in this language. */
    String fileName() {
        return "nadel-import-template-" + code + ".xlsx";
    }

    /**
     * The language of a request: the {@code lang} parameter when given, otherwise the best match of Ukrainian and
     * English in the {@code Accept-Language} header ({@code uk-UA} is Ukrainian, {@code en-US} is English), otherwise
     * {@link #DEFAULT}. The contract has already refused a {@code lang} other than {@code uk} and {@code en}.
     */
    static TemplateLanguage resolve(@Nullable String lang, @Nullable String acceptLanguage) {
        if (lang != null) {
            return of(lang);
        }
        return fromAcceptLanguage(acceptLanguage);
    }

    private static TemplateLanguage of(String code) {
        for (TemplateLanguage language : values()) {
            if (language.code.equals(code)) {
                return language;
            }
        }
        throw new IllegalArgumentException("Unknown template language");
    }

    private static TemplateLanguage fromAcceptLanguage(@Nullable String header) {
        if (header == null || header.isBlank()) {
            return DEFAULT;
        }
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(header).stream()
                    .filter(range -> range.getWeight() > 0)
                    .toList();
            Locale best = Locale.lookup(ranges, OFFERED);
            return best == null ? DEFAULT : of(best.getLanguage());
        } catch (IllegalArgumentException e) {
            return DEFAULT; // a malformed header is no preference
        }
    }
}
