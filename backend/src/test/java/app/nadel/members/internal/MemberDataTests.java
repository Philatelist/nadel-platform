package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The e-mail rule of {@link MemberData}: trimmed and lower-case, the form its blind index is computed over. */
class MemberDataTests {

    @Test
    void anEmailIsStoredTrimmedAndLowerCase() {
        MemberData data = valid("  Olena.Petrenko@Example.COM ");

        assertThat(data.email()).isEqualTo("olena.petrenko@example.com")
                .isEqualTo(PhoneIndexValues.normalisedEmail("  Olena.Petrenko@Example.COM "));
    }

    @Test
    void aBlankEmailIsNoEmail() {
        assertThat(valid("   ").email()).isNull();
        assertThat(valid(null).email()).isNull();
    }

    private static MemberData valid(String email) {
        MemberData.Validation result = MemberData.tryFrom("Петренко", "Олена", null, List.of(), email, null,
                DeliveryChannel.PAPER, null, null, null);
        assertThat(result.problems()).isEmpty();
        return result.data();
    }
}
