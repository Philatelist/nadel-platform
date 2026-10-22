package app.nadel.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The one vocabulary file: what it lists is exactly what the readers accept and the Ukrainian template shows. */
class ImportVocabularyTests {

    private final ImportVocabulary vocabulary = ImportVocabulary.get();

    @Test
    void everyColumnOfEverySheetHasAUkrainianLabelThatItsOwnReaderAccepts() {
        assertLabelsAreAccepted(ImportVocabulary.COLUMNS_PLOTS, PlotColumn.values());
        assertLabelsAreAccepted(ImportVocabulary.COLUMNS_MEMBERS, MemberColumn.values());
        assertLabelsAreAccepted(ImportVocabulary.COLUMNS_OWNERSHIP, OwnershipColumn.values());
        assertLabelsAreAccepted(ImportVocabulary.COLUMNS_METERS, MeterColumn.values());
    }

    private void assertLabelsAreAccepted(String sheet, SheetColumn[] columns) {
        Map<String, String> aliases = vocabulary.columnAliases(sheet);
        for (SheetColumn column : columns) {
            String label = vocabulary.columnLabel(sheet, column.key());
            assertThat(label).as(sheet + "." + column.key()).matches(".*[\\u0400-\\u04FF].*");
            assertThat(aliases.get(ImportVocabulary.normaliseHeader(label))).as(label).isEqualTo(column.key());
        }
    }

    @Test
    void everyColumnLineOfTheFileNamesAColumnThatExists() {
        Set<String> known = new HashSet<>();
        Arrays.stream(PlotColumn.values()).forEach(c -> known.add("plots." + c.key()));
        Arrays.stream(MemberColumn.values()).forEach(c -> known.add("members." + c.key()));
        Arrays.stream(OwnershipColumn.values()).forEach(c -> known.add("ownership." + c.key()));
        Arrays.stream(MeterColumn.values()).forEach(c -> known.add("meters." + c.key()));
        Properties file = ImportVocabulary.load("imports/header-aliases.properties");
        for (String name : file.stringPropertyNames()) {
            if (name.startsWith("plots.") || name.startsWith("members.") || name.startsWith("ownership.")
                    || name.startsWith("meters.")) {
                assertThat(known).as(name).contains(name);
            }
        }
        assertThat(file.stringPropertyNames()).containsAll(known);
    }

    @Test
    void sheetNamesAcceptBothLanguagesIgnoringCase() {
        assertThat(PlotsSheet.NAMES).containsExactly("Plots", "Ділянки");
        assertThat(MembersSheet.NAMES).contains("Members", "Члени");
        assertThat(OwnershipSheet.NAMES).contains("Ownership", "Власність");
        assertThat(MetersSheet.NAMES).containsExactly("Meters", "Лічильники");
        assertThat(vocabulary.sheetLabel("plots")).isEqualTo("Ділянки");
        assertThat(vocabulary.sheetLabel("meters")).isEqualTo("Лічильники");
    }

    @Test
    void valueWordsOfBothLanguagesMeanTheCanonicalOnes() {
        assertThat(vocabulary.canonicalValue(ImportVocabulary.KIND, "ДІЛЯНКА")).isEqualTo("PLOT");
        assertThat(vocabulary.canonicalValue(ImportVocabulary.KIND, "  ділянка ")).isEqualTo("PLOT");
        assertThat(vocabulary.canonicalValue(ImportVocabulary.KIND, "Спільна   земля")).isEqualTo("COMMON");
        assertThat(vocabulary.canonicalValue(ImportVocabulary.KIND, "PLOT")).isNull(); // the module reads English
        assertThat(vocabulary.canonicalValue(ImportVocabulary.CHANNEL, "ел. пошта")).isEqualTo("EMAIL");
        assertThat(vocabulary.canonicalValue(ImportVocabulary.CHANNEL, "Телеграм")).isEqualTo("TELEGRAM");
        assertThat(vocabulary.canonicalValue(ImportVocabulary.CHANNEL, "ВАЙБЕР")).isEqualTo("VIBER");
        assertThat(vocabulary.canonicalValue(ImportVocabulary.CHANNEL, "папер")).isEqualTo("PAPER");
        assertThat(vocabulary.canonicalValue(ImportVocabulary.YES_NO, "Так")).isEqualTo("true");
        assertThat(vocabulary.canonicalValue(ImportVocabulary.YES_NO, "NO")).isEqualTo("false");
        assertThat(vocabulary.canonicalValue(ImportVocabulary.YES_NO, "maybe")).isNull();
        assertThat(vocabulary.canonicalValue(ImportVocabulary.RESOURCE, "електроенергія")).isEqualTo("ELECTRICITY");
        assertThat(vocabulary.canonicalValue(ImportVocabulary.RESOURCE, "Електрика")).isEqualTo("ELECTRICITY");
        assertThat(vocabulary.canonicalValue(ImportVocabulary.RESOURCE, " вода ")).isEqualTo("WATER");
        assertThat(vocabulary.canonicalValue(ImportVocabulary.RESOURCE, "WATER")).isNull(); // the module reads English
    }

    @Test
    void everyValueTheTemplateShowsIsAWordTheReaderAccepts() {
        for (String plot : new String[] {"PLOT", "COMMON"}) {
            String label = vocabulary.valueLabel(ImportVocabulary.KIND, plot);
            assertThat(vocabulary.canonicalValue(ImportVocabulary.KIND, label)).isEqualTo(plot);
        }
        for (String resource : new String[] {"ELECTRICITY", "WATER"}) {
            String label = vocabulary.valueLabel(ImportVocabulary.RESOURCE, resource);
            assertThat(vocabulary.canonicalValue(ImportVocabulary.RESOURCE, label)).isEqualTo(resource);
        }
        for (String channel : new String[] {"EMAIL", "TELEGRAM", "VIBER", "PAPER"}) {
            String label = vocabulary.valueLabel(ImportVocabulary.CHANNEL, channel);
            assertThat(vocabulary.canonicalValue(ImportVocabulary.CHANNEL, label)).isEqualTo(channel);
        }
        for (String yesNo : new String[] {"true", "false"}) {
            String label = vocabulary.valueLabel(ImportVocabulary.YES_NO, yesNo);
            assertThat(vocabulary.canonicalValue(ImportVocabulary.YES_NO, label)).isEqualTo(yesNo);
        }
    }

    @Test
    void aMissingLineIsAnError() {
        assertThatThrownBy(() -> vocabulary.valueLabel(ImportVocabulary.KIND, "NOPE"))
                .isInstanceOf(IllegalStateException.class);
    }
}
