package app.nadel.tariffs.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;

/**
 * The default names of the four seeded charge types in both languages, from the one resource file
 * {@code tariffs/charge-type-default-names.properties}. The English names are what {@code seed_charge_types} stores;
 * the Ukrainian ones mirror {@code chargeTypes.defaultNames} of the web catalog. A seeded type that was never renamed
 * is shown under its default name in the user's language, so a new name must not equal either default of such a type.
 */
final class DefaultChargeTypeNames {

    private static final String RESOURCE = "tariffs/charge-type-default-names.properties";
    private static final Map<String, List<String>> BY_CODE = load();

    private DefaultChargeTypeNames() {
    }

    /** The English and Ukrainian default names of a seeded charge type; empty for a code that is not seeded. */
    static List<String> of(String code) {
        return BY_CODE.getOrDefault(code, List.of());
    }

    /** Whether the name equals, ignoring letter case, a default name of the seeded type with this code. */
    static boolean isDefaultOf(String code, String name) {
        return of(code).stream().anyMatch(defaultName -> defaultName.equalsIgnoreCase(name));
    }

    private static Map<String, List<String>> load() {
        Properties properties = new Properties();
        try (InputStream stream = new ClassPathResource(RESOURCE).getInputStream()) {
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + RESOURCE, e);
        }
        return properties.stringPropertyNames().stream()
                .map(key -> key.substring(0, key.indexOf('.')))
                .distinct()
                .collect(Collectors.toUnmodifiableMap(code -> code,
                        code -> List.of(properties.getProperty(code + ".en"), properties.getProperty(code + ".uk"))));
    }
}
