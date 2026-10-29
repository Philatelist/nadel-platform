package app.nadel.notifications.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;

/**
 * The texts of letters and bot messages, from the UTF-8 bundle {@code notifications/messages_uk.properties}. Members
 * and the board read them in Ukrainian whatever language their screens are in, so there is one bundle: the ADR 0012
 * exception to the English-only rule for generated texts, like the document bundle. A missing key is a programming
 * error.
 */
final class NotificationTexts {

    static final String RESOURCE = "notifications/messages_uk.properties";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");
    private static final NotificationTexts UK = new NotificationTexts(load(RESOURCE));

    private final Properties bundle;

    private NotificationTexts(Properties bundle) {
        this.bundle = bundle;
    }

    /** The Ukrainian texts, read once. */
    static NotificationTexts uk() {
        return UK;
    }

    /** Every key of the bundle. */
    Set<String> keys() {
        return bundle.stringPropertyNames();
    }

    /** The text of the key. */
    String text(String key) {
        String value = bundle.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("No text " + key + " in the notifications bundle");
        }
        return value;
    }

    /** The text of the key with its {@code {name}} placeholders filled in from {@code params}. */
    String text(String key, Map<String, String> params) {
        return PLACEHOLDER.matcher(text(key)).replaceAll(match -> Matcher.quoteReplacement(
                params.getOrDefault(match.group(1), match.group())));
    }

    private static Properties load(String resource) {
        Properties properties = new Properties();
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("The notifications bundle " + resource + " is missing", e);
        }
        return properties;
    }
}
