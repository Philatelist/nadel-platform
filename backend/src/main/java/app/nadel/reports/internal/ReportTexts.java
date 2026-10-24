package app.nadel.reports.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;

/**
 * The texts of the report files in one language, from the UTF-8 bundles {@code reports/messages_uk.properties} and
 * {@code reports/messages_en.properties} (generated files need text, so the server holds these catalogs: the ADR 0012
 * exception to the English-only rule). Every key is the path of the same text in the web catalogs
 * ({@code packages/shared/src/i18n}), for example {@code reports.charges-by-month.columns.effective} or
 * {@code billing.status.OPEN}, so the screen and the file say the same. A missing key is a programming error.
 */
final class ReportTexts {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM.yyyy");
    private static final Map<ReportLanguage, ReportTexts> LOADED = new EnumMap<>(ReportLanguage.class);

    private final ReportLanguage language;
    private final Properties bundle;

    private ReportTexts(ReportLanguage language, Properties bundle) {
        this.language = language;
        this.bundle = bundle;
    }

    /** The texts of the language, read once. */
    static synchronized ReportTexts of(ReportLanguage language) {
        return LOADED.computeIfAbsent(language, lang -> new ReportTexts(lang, load(resource(lang))));
    }

    static String resource(ReportLanguage language) {
        return "reports/messages_" + language.code() + ".properties";
    }

    ReportLanguage language() {
        return language;
    }

    /** Every key of the bundle. */
    Set<String> keys() {
        return bundle.stringPropertyNames();
    }

    /** The text of the key. */
    String text(String key) {
        String value = bundle.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("No text " + key + " in the " + language.code() + " report bundle");
        }
        return value;
    }

    /** The text of the key with its {@code {name}} placeholders filled in from {@code params}. */
    String text(String key, Map<String, String> params) {
        return PLACEHOLDER.matcher(text(key)).replaceAll(match -> Matcher.quoteReplacement(
                params.getOrDefault(match.group(1), match.group())));
    }

    /** A day as people read it in both languages: {@code 31.01.2026}. */
    static String day(LocalDate day) {
        return DAY.format(day);
    }

    /** A month as people read it in both languages: {@code 01.2026}. */
    static String month(YearMonth month) {
        return MONTH.format(month);
    }

    private static Properties load(String resource) {
        Properties properties = new Properties();
        try (InputStream stream = new ClassPathResource(resource).getInputStream()) {
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + resource, e);
        }
        return properties;
    }
}
