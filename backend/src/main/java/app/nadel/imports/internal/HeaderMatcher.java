package app.nadel.imports.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;

/**
 * Finds the columns of a sheet from its header row. A header matches a column when the two are equal after
 * normalisation: lower case, everything but letters and digits dropped. The canonical key of the column and the
 * aliases from {@code imports/header-aliases.properties} (Ukrainian names among them) are accepted. Columns may come
 * in any order; unknown headers are ignored; when two headers match the same column the first one wins.
 */
final class HeaderMatcher {

    private static final String ALIASES_RESOURCE = "imports/header-aliases.properties";
    private static final String ALIAS_SEPARATOR = "\\|";

    /** Aliases by sheet name ({@code plots}) and then by normalised header. */
    private static final Map<String, Map<String, String>> ALIASES = loadAliases();

    private HeaderMatcher() {
    }

    /** The header text reduced to lower-case letters and digits. */
    static String normalise(@Nullable String header) {
        if (header == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(header.length());
        header.toLowerCase(Locale.ROOT).codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(out::appendCodePoint);
        return out.toString();
    }

    /**
     * Matches the header cells of a sheet.
     *
     * @param sheet   the sheet key used in the aliases file ({@code plots})
     * @param columns the columns of the sheet
     * @param header  the header row
     * @return the columns found, with the index of their cells
     */
    static <C extends Enum<C> & SheetColumn> Match<C> match(String sheet, Class<C> columns, RawRow header) {
        Map<String, C> byHeader = new HashMap<>();
        Map<String, String> aliases = ALIASES.getOrDefault(sheet, Map.of());
        for (C column : columns.getEnumConstants()) {
            byHeader.put(normalise(column.key()), column);
        }
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            for (C column : columns.getEnumConstants()) {
                if (column.key().equals(alias.getValue())) {
                    byHeader.putIfAbsent(alias.getKey(), column);
                }
            }
        }
        Map<C, Integer> found = new EnumMap<>(columns);
        for (int index = 0; index < header.cells().size(); index++) {
            if (header.cell(index) == null) {
                continue;
            }
            C column = byHeader.get(normalise(headerText(header.cell(index))));
            if (column != null) {
                found.putIfAbsent(column, index);
            }
        }
        List<C> missing = new ArrayList<>();
        for (C column : columns.getEnumConstants()) {
            if (column.required() && !found.containsKey(column)) {
                missing.add(column);
            }
        }
        return new Match<>(found, missing);
    }

    private static String headerText(RawCell cell) {
        return switch (cell) {
            case RawCell.Text text -> text.value();
            case RawCell.Number number -> number.value().toPlainString();
            case RawCell.Bool bool -> String.valueOf(bool.value());
        };
    }

    /**
     * The result of matching.
     *
     * @param indexes the cell index of every column that was found
     * @param missing the required columns that were not found, in column order
     */
    record Match<C extends Enum<C>>(Map<C, Integer> indexes, List<C> missing) {

        /** The cell of the column in a row, or null when the column is absent or the cell is empty. */
        @Nullable RawCell cell(RawRow row, C column) {
            Integer index = indexes.get(column);
            return index == null ? null : row.cell(index);
        }

        boolean has(C column) {
            return indexes.containsKey(column);
        }
    }

    private static Map<String, Map<String, String>> loadAliases() {
        Properties properties = new Properties();
        try (InputStream stream = new ClassPathResource(ALIASES_RESOURCE).getInputStream()) {
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + ALIASES_RESOURCE, e);
        }
        Map<String, Map<String, String>> bySheet = new HashMap<>();
        for (String name : properties.stringPropertyNames()) {
            int dot = name.indexOf('.');
            String sheet = name.substring(0, dot);
            String key = name.substring(dot + 1);
            for (String alias : properties.getProperty(name).split(ALIAS_SEPARATOR)) {
                bySheet.computeIfAbsent(sheet, s -> new HashMap<>()).putIfAbsent(normalise(alias), key);
            }
        }
        return bySheet;
    }
}
