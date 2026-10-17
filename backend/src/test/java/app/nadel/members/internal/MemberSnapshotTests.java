package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.audit.FieldChange;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemberSnapshotTests {

    static final MemberSnapshot BASE = new MemberSnapshot("Ivanenko", "Petro", null, "+380501112233, +380671234567",
            "petro@example.com", null, "PAPER", null, null, null, false);

    @Test
    void identicalSnapshotsHaveNoDifference() {
        assertThat(MemberSnapshot.diff(BASE, BASE)).isEmpty();
    }

    @Test
    void reportsEachChangedFieldWithOldAndNewValue() {
        MemberSnapshot after = new MemberSnapshot("Ivanenko", "Petro", "Ivanovych", "+380501112233, +380671234567",
                "petro@example.com", "Kyiv", "EMAIL", null, null, "note", false);

        assertThat(MemberSnapshot.diff(BASE, after)).containsExactly(
                new FieldChange("middleName", null, "Ivanovych"),
                new FieldChange("postalAddress", null, "Kyiv"),
                new FieldChange("deliveryChannel", "PAPER", "EMAIL"),
                new FieldChange("notes", null, "note"));
    }

    @Test
    void reportsClearedField() {
        MemberSnapshot after = new MemberSnapshot("Ivanenko", "Petro", null, "+380501112233, +380671234567", null,
                null, "PAPER", null, null, null, false);

        assertThat(MemberSnapshot.diff(BASE, after)).containsExactly(
                new FieldChange("email", "petro@example.com", null));
    }

    @Test
    void archivedFlagIsReportedAsFalseToTrue() {
        MemberSnapshot archived = new MemberSnapshot("Ivanenko", "Petro", null, "+380501112233, +380671234567",
                "petro@example.com", null, "PAPER", null, null, null, true);

        assertThat(MemberSnapshot.diff(BASE, archived)).containsExactly(new FieldChange("archived", "false", "true"));
        assertThat(MemberSnapshot.diff(archived, BASE)).containsExactly(new FieldChange("archived", "true", "false"));
    }

    @Test
    void creationReportsEveryFilledFieldWithNullOldValue() {
        List<FieldChange> changes = MemberSnapshot.diff(MemberSnapshot.NONE, BASE);

        assertThat(changes).extracting(FieldChange::field)
                .containsExactly("lastName", "firstName", "phones", "email", "deliveryChannel");
        assertThat(changes).allSatisfy(c -> assertThat(c.oldValue()).isNull());
    }
}
