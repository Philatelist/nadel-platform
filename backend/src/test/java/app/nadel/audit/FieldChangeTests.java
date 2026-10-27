package app.nadel.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class FieldChangeTests {

    private static final UUID ENTRY = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @Test
    void aChangeWithoutTheFlagHoldsNoPersonalData() {
        assertThat(new FieldChange("middleName", null, "Ivanovych").sealed()).isFalse();
    }

    @Test
    void aChangeSerializedBeforeTheFlagExistedReadsAsNotSealed() {
        // a publication stored by the previous release, still pending in event_publication
        FieldChange read = JsonMapper.builder().build().readValue(
                "{\"field\": \"areaM2\", \"oldValue\": \"600.00\", \"newValue\": null}", FieldChange.class);

        assertThat(read).isEqualTo(new FieldChange("areaM2", "600.00", null, false));
    }

    @Test
    void aSealedChangeSurvivesTheOutboxRoundTrip() {
        JsonMapper json = JsonMapper.builder().build();
        FieldChange sealed = new FieldChange("email", "b2xk", "bmV3", true);

        assertThat(json.readValue(json.writeValueAsString(sealed), FieldChange.class)).isEqualTo(sealed);
    }

    @Test
    void theValuesOfAFieldLiveAtItsSnakeCasePathInTheEntry() {
        assertThat(FieldChange.oldValueRef(ENTRY, "postalAddress").column()).isEqualTo("changes.postal_address.old");
        assertThat(FieldChange.newValueRef(ENTRY, "telegramUsername").column())
                .isEqualTo("changes.telegram_username.new");
        assertThat(FieldChange.newValueRef(ENTRY, "email").table()).isEqualTo("audit_entry");
        assertThat(FieldChange.newValueRef(ENTRY, "email").rowId()).isEqualTo(ENTRY);
        assertThat(FieldChange.oldValueRef(ENTRY, "phones")).isNotEqualTo(FieldChange.newValueRef(ENTRY, "phones"));
    }

    @Test
    void aFieldNameWithDigitsHasNoPlace() {
        assertThatThrownBy(() -> FieldChange.oldValueRef(ENTRY, "areaM2"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
