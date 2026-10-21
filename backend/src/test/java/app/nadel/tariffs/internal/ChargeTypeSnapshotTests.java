package app.nadel.tariffs.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.audit.FieldChange;
import org.junit.jupiter.api.Test;

/** The audited difference of two charge type states. */
class ChargeTypeSnapshotTests {

    @Test
    void aNewTypeReportsEveryFilledFieldAndNotTheArchiveFlag() {
        var created = ChargeTypeSnapshot.of("Road repair", "Fund of the road", true);

        assertThat(ChargeTypeSnapshot.diff(ChargeTypeSnapshot.NONE, created)).containsExactly(
                new FieldChange("name", null, "Road repair"),
                new FieldChange("description", null, "Fund of the road"),
                new FieldChange("active", null, "true"));
    }

    @Test
    void anEmptyDescriptionIsNotReportedForANewType() {
        assertThat(ChargeTypeSnapshot.diff(ChargeTypeSnapshot.NONE, ChargeTypeSnapshot.of("Road repair", null, false)))
                .containsExactly(new FieldChange("name", null, "Road repair"), new FieldChange("active", null, "false"));
    }

    @Test
    void onlyTheChangedFieldsAreReportedInAFixedOrder() {
        var before = ChargeTypeSnapshot.of("Water", "Old", false);
        var after = ChargeTypeSnapshot.of("Water supply", null, true);

        assertThat(ChargeTypeSnapshot.diff(before, after)).containsExactly(
                new FieldChange("name", "Water", "Water supply"),
                new FieldChange("description", "Old", null),
                new FieldChange("active", "false", "true"));
        assertThat(ChargeTypeSnapshot.diff(before, before)).isEmpty();
    }
}
