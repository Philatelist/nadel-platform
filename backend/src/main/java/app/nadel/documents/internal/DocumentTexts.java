package app.nadel.documents.internal;

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
 * The texts of the documents, from the UTF-8 bundle {@code documents/messages_uk.properties}. Documents are official
 * papers and always Ukrainian, whatever language the user's screens are in (feature 016 §1), so there is one bundle;
 * it is the ADR 0012 exception to the English-only rule for generated files, like the report bundles. The words of
 * amounts in words ({@link AmountInWords}) live here too. A missing key is a programming error. Public inside the
 * module only for the NBU QR code ({@code internal.nbu}: the month names and the purpose of payment).
 */
public final class DocumentTexts {

    static final String RESOURCE = "documents/messages_uk.properties";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");
    private static final DocumentTexts UK = new DocumentTexts(load(RESOURCE));

    private final Properties bundle;

    private DocumentTexts(Properties bundle) {
        this.bundle = bundle;
    }

    /** The Ukrainian texts, read once. */
    public static DocumentTexts uk() {
        return UK;
    }

    /** Every key of the bundle. */
    Set<String> keys() {
        return bundle.stringPropertyNames();
    }

    /** The text of the key. */
    public String text(String key) {
        String value = bundle.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("No text " + key + " in the document bundle");
        }
        return value;
    }

    /** The text of the key with its {@code {name}} placeholders filled in from {@code params}. */
    public String text(String key, Map<String, String> params) {
        return PLACEHOLDER.matcher(text(key)).replaceAll(match -> Matcher.quoteReplacement(
                params.getOrDefault(match.group(1), match.group())));
    }

    private static Properties load(String resource) {
        Properties properties = new Properties();
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("The document bundle " + resource + " is missing", e);
        }
        return properties;
    }
}
