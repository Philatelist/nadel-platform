package app.nadel.tariffs.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.audit.FieldChange;
import org.junit.jupiter.api.Test;

/** The audited difference of two charge type states. */
class ChargeTypeSnapshotTests {

    @Test
    void aNewTypeReportsEveryFilledFieldAndNotTheArchiveFlag() {
        var created = ChargeTypeSnapshot.of(null, "Road repair", true, "Fund of the road", true);

        assertThat(ChargeTypeSnapshot.diff(ChargeTypeSnapshot.NONE, created)).containsExactly(
                new FieldChange("name", null, "Road repair"),
                new FieldChange("description", null, "Fund of the road"),
                new FieldChange("active", null, "true"));
    }

    @Test
    void anEmptyDescriptionIsNotReportedForANewType() {
        assertThat(ChargeTypeSnapshot.diff(ChargeTypeSnapshot.NONE, ChargeTypeSnapshot.of(null, "Road repair", true, null, false)))
                .containsExactly(new FieldChange("name", null, "Road repair"), new FieldChange("active", null, "false"));
    }

    @Test
    void onlyTheChangedFieldsAreReportedInAFixedOrder() {
        var before = ChargeTypeSnapshot.of(null, "Water", true, "Old", false);
        var after = ChargeTypeSnapshot.of(null, "Water supply", true, null, true);

        assertThat(ChargeTypeSnapshot.diff(before, after)).containsExactly(
                new FieldChange("name", "Water", "Water supply"),
                new FieldChange("description", "Old", null),
                new FieldChange("active", "false", "true"));
        assertThat(ChargeTypeSnapshot.diff(before, before)).isEmpty();
    }

    @Test
    void aSeededTypeWithItsStandardNameIsWrittenAsATokenNotAsTheEnglishDefault() {
        assertThat(ChargeTypeSnapshot.auditName("MEMBERSHIP", "Membership fee", false)).isEqualTo("base:MEMBERSHIP");
        assertThat(ChargeTypeSnapshot.auditName("LAND_TAX", "Land tax", false)).isEqualTo("base:LAND_TAX");
    }

    @Test
    void aRenamedSeededTypeAndACustomTypeAreWrittenUnderTheirStoredName() {
        assertThat(ChargeTypeSnapshot.auditName("MEMBERSHIP", "Dues", true)).isEqualTo("Dues");
        assertThat(ChargeTypeSnapshot.auditName(null, "Road repair", true)).isEqualTo("Road repair");
    }

    @Test
    void renamingAStandardNameReportsTheTokenAsTheOldValue() {
        var before = ChargeTypeSnapshot.of("WATER", "Water", false, null, true);
        var after = ChargeTypeSnapshot.of("WATER", "Water supply", true, null, true);

        assertThat(ChargeTypeSnapshot.diff(before, after))
                .containsExactly(new FieldChange("name", "base:WATER", "Water supply"));
    }
}
