package app.nadel.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.problem.ProblemCodes;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** The names of a {@link FieldRef} never contain the separator of the associated data; the failure is a 500 code. */
class FieldRefAndProblemTests {

    @Test
    void namesAreLowerCaseLettersUnderscoresAndDots() {
        UUID row = UUID.randomUUID();

        assertThat(new FieldRef("audit_entry", "changes.email.old", row).column()).isEqualTo("changes.email.old");
        for (String bad : new String[] {"member|x", "Member", "member1", "", " member", "член"}) {
            assertThatThrownBy(() -> new FieldRef(bad, "email_enc", row))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("The table name of a field must match [a-z_.]+");
            assertThatThrownBy(() -> new FieldRef("member", bad, row))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("The column name of a field must match [a-z_.]+");
        }
        assertThatThrownBy(() -> new FieldRef(null, "email_enc", row)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FieldRef("member", "email_enc", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void unreadableDataIsA500WithItsCodeAndNoValue() {
        PersonalDataUnreadableException e = new PersonalDataUnreadableException();

        assertThat(e.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(e.code()).isEqualTo(ProblemCodes.PERSONAL_DATA_UNREADABLE).isEqualTo("PERSONAL_DATA_UNREADABLE");
        assertThat(e.getMessage()).isEqualTo("Personal data could not be read.");
        assertThat(e.errors()).isEmpty();
    }
}
