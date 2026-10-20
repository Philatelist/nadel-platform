package app.nadel.imports.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;

/**
 * The words of the import, from the one resource file {@code imports/header-aliases.properties}: sheet names, column
 * headers and value words (plot kinds, delivery channels, yes and no) in Ukrainian and English. The readers accept every
 * word listed there; the template writer shows the FIRST word of a line as the Ukrainian label, so the two cannot
 * drift apart. English canonical names (column keys, the sheet names of {@link PlotsSheet#NAME} and its siblings) are
 * accepted without being listed.
 * <p>
 * Lines are {@code <group>.<key> = label | alias | ...}. Groups: {@code plots}, {@code members}, {@code ownership}
 * (columns), {@code sheet} (sheet names) and {@code value} (the key is {@code <kind>.<CANONICAL>}).
 */
final class ImportVocabulary {

    static final String COLUMNS_PLOTS = "plots";
    static final String COLUMNS_MEMBERS = "members";
    static final String COLUMNS_OWNERSHIP = "ownership";
    static final String KIND = "kind";
    static final String CHANNEL = "channel";
    static final String YES_NO = "yesno";

    private static final String RESOURCE = "imports/header-aliases.properties";
    private static final String SEPARATOR = "\\|";
    private static final String SHEET_GROUP = "sheet";
    private static final String VALUE_GROUP = "value";
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private static final ImportVocabulary INSTANCE = new ImportVocabulary(load(RESOURCE));

    /** Words of every line by its property name, in file order; the first word is the Ukrainian label. */
    private final Map<String, List<String>> words;

    private ImportVocabulary(Properties properties) {
        Map<String, List<String>> parsed = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            List<String> list = new ArrayList<>();
            for (String word : properties.getProperty(name).split(SEPARATOR)) {
                if (!word.isBlank()) {
                    list.add(word.strip());
                }
            }
            parsed.put(name, List.copyOf(list));
        }
        this.words = Map.copyOf(parsed);
    }

    static ImportVocabulary get() {
        return INSTANCE;
    }

    /** Lower case, letters and digits only: the form in which headers are compared. */
    static String normaliseHeader(@Nullable String header) {
        if (header == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(header.length());
        header.toLowerCase(Locale.ROOT).codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(out::appendCodePoint);
        return out.toString();
    }

    /** Lower case, surrounding spaces dropped and runs of spaces collapsed: the form in which value words are compared. */
    static String normaliseWord(String text) {
        return SPACES.matcher(text.strip().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }

    /** The aliases of the columns of a sheet: normalised header to canonical column key. */
    Map<String, String> columnAliases(String sheet) {
        Map<String, String> aliases = new HashMap<>();
        String prefix = sheet + ".";
        words.forEach((name, list) -> {
            if (name.startsWith(prefix)) {
                String key = name.substring(prefix.length());
                for (String word : list) {
                    aliases.putIfAbsent(normaliseHeader(word), key);
                }
            }
        });
        return aliases;
    }

    /** The Ukrainian header of a column: the first word of its line. */
    String columnLabel(String sheet, String column) {
        return label(sheet + "." + column);
    }

    /** The names a sheet may have in a file: the English name and every listed alias. Compared ignoring case. */
    List<String> sheetNames(String sheetKey, String englishName) {
        List<String> names = new ArrayList<>();
        names.add(englishName);
        names.addAll(words.getOrDefault(SHEET_GROUP + "." + sheetKey, List.of()));
        return List.copyOf(names);
    }

    /** The Ukrainian name of a sheet: the first word of its line. */
    String sheetLabel(String sheetKey) {
        return label(SHEET_GROUP + "." + sheetKey);
    }

    /**
     * The canonical value a word stands for ({@code PLOT} for {@code ДІЛЯНКА}), or null when the word is not listed.
     *
     * @param kind one of {@link #KIND}, {@link #CHANNEL} and {@link #YES_NO}
     */
    @Nullable String canonicalValue(String kind, String word) {
        String wanted = normaliseWord(word);
        String prefix = VALUE_GROUP + "." + kind + ".";
        for (Map.Entry<String, List<String>> entry : words.entrySet()) {
            if (entry.getKey().startsWith(prefix)
                    && entry.getValue().stream().anyMatch(candidate -> normaliseWord(candidate).equals(wanted))) {
                return entry.getKey().substring(prefix.length());
            }
        }
        return null;
    }

    /** The Ukrainian word of a value: the first word of its line. */
    String valueLabel(String kind, String canonical) {
        return label(VALUE_GROUP + "." + kind + "." + canonical);
    }

    private String label(String name) {
        List<String> list = words.get(name);
        if (list == null || list.isEmpty()) {
            throw new IllegalStateException("No vocabulary line " + name + " in " + RESOURCE);
        }
        return list.getFirst();
    }

    /** Reads a UTF-8 properties resource of the classpath. */
    static Properties load(String resource) {
        Properties properties = new Properties();
        try (InputStream stream = new ClassPathResource(resource).getInputStream()) {
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + resource, e);
        }
        return properties;
    }
}
