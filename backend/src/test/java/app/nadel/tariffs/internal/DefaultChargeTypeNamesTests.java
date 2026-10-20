package app.nadel.tariffs.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The default names of the seeded charge types, English and Ukrainian, from the one resource file. */
class DefaultChargeTypeNamesTests {

    @Test
    void everySeededTypeHasAnEnglishAndAUkrainianDefault() {
        assertThat(DefaultChargeTypeNames.of("MEMBERSHIP")).containsExactly("Membership fee", "Членський внесок");
        assertThat(DefaultChargeTypeNames.of("ELECTRICITY")).containsExactly("Electricity", "Електроенергія");
        assertThat(DefaultChargeTypeNames.of("LAND_TAX")).containsExactly("Land tax", "Земельний податок");
        assertThat(DefaultChargeTypeNames.of("WATER")).containsExactly("Water", "Вода");
    }

    @Test
    void aCodeThatIsNotSeededHasNoDefaults() {
        assertThat(DefaultChargeTypeNames.of("ROAD_REPAIR")).isEmpty();
        assertThat(DefaultChargeTypeNames.isDefaultOf("ROAD_REPAIR", "Water")).isFalse();
    }

    @Test
    void aDefaultIsRecognisedIgnoringLetterCaseInBothLanguages() {
        assertThat(DefaultChargeTypeNames.isDefaultOf("WATER", "water")).isTrue();
        assertThat(DefaultChargeTypeNames.isDefaultOf("WATER", "ВОДА")).isTrue();
        assertThat(DefaultChargeTypeNames.isDefaultOf("LAND_TAX", "земельний ПОДАТОК")).isTrue();
    }

    @Test
    void aDefaultOfAnotherTypeIsNotADefaultOfThisOne() {
        assertThat(DefaultChargeTypeNames.isDefaultOf("WATER", "Electricity")).isFalse();
        assertThat(DefaultChargeTypeNames.isDefaultOf("WATER", "Water supply")).isFalse();
    }
}
