package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.privacy.PersonalDataUnreadableException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The normal forms of the blind indexes and the JSON form of the sealed phones. */
class PhoneIndexValuesTests {

    @Test
    void anEmailIsTrimmedAndLowerCase() {
        assertThat(PhoneIndexValues.normalisedEmail("  Chairman@Demo.LOCAL ")).isEqualTo("chairman@demo.local");
        assertThat(PhoneIndexValues.normalisedEmail(null)).isNull();
        assertThat(PhoneIndexValues.normalisedEmail("  ")).isNull();
    }

    @Test
    void aPhoneIsIndexedInItsInternationalFormWithItsLastFourDigits() {
        assertThat(PhoneIndexValues.entries(List.of("050 123 45 67", "+380501234567", "380931112233", " ", "12")))
                .containsExactly(new PhoneIndexValues.Entry("+380501234567", "4567"),
                        new PhoneIndexValues.Entry("+380931112233", "2233"));
        // a stored value that is not a valid phone is indexed as stored
        assertThat(PhoneIndexValues.normalisedPhone(" 12-34-56 ")).isEqualTo("12-34-56");
        assertThat(PhoneIndexValues.last4("12-34-56")).isEqualTo("3456");
        assertThat(PhoneIndexValues.last4("+12")).isNull();
        assertThat(PhoneIndexValues.entries(Arrays.asList(null, "+380501234567"))).hasSize(1);
    }

    @Test
    void phonesAreOneJsonArrayAndAnythingElseIsUnreadable() {
        String json = ContactCipher.PhonesJson.write(List.of("+380501234567", "+380931112233"));

        assertThat(json).isEqualTo("[\"+380501234567\",\"+380931112233\"]");
        assertThat(ContactCipher.PhonesJson.read(json)).containsExactly("+380501234567", "+380931112233");
        assertThat(ContactCipher.PhonesJson.read("null")).isEmpty();
        for (String broken : new String[] {"not json", "{\"a\": 1}", "[null]"}) {
            assertThatThrownBy(() -> ContactCipher.PhonesJson.read(broken)).as(broken)
                    .isInstanceOf(PersonalDataUnreadableException.class)
                    .hasMessageNotContaining(broken);
        }
    }
}
