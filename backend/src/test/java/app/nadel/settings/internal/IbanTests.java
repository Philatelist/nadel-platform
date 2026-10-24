package app.nadel.settings.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The Ukrainian IBAN: normalisation, format and the mod-97 check (functional spec 2.13). */
class IbanTests {

    private static final String VALID = "UA213223130000026007233566001";

    @ParameterizedTest
    @ValueSource(strings = {VALID, "UA903052992990004149123456789", "UA263220010000026201234567890"})
    void aValidUkrainianIbanIsAccepted(String iban) {
        assertThat(Iban.parse(iban)).hasValueSatisfying(parsed -> assertThat(parsed.value()).isEqualTo(iban));
        assertThat(Iban.isValid(iban)).isTrue();
    }

    @Test
    void spacesAndLowerCaseAreNormalised() {
        assertThat(Iban.parse("ua21 3223 1300 0002 6007 2335 6600 1")).map(Iban::value).hasValue(VALID);
        // a copy from a bank statement: a tab and no-break spaces
        assertThat(Iban.parse(" Ua21\t3223 1300 0002 6007 2335 6600 1 ")).map(Iban::value).hasValue(VALID);
        assertThat(Iban.normalise("ua21 3223")).isEqualTo("UA213223");
        assertThat(Iban.parse(VALID).orElseThrow()).hasToString(VALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "UA223223130000026007233566001", // check digits 22 instead of 21
        "UA213223130000026007233566002", // last digit changed
        "UA213223130000026007233565601", // two digits swapped
    })
    void aWrongChecksumIsRejected(String iban) {
        assertThat(Iban.parse(iban)).isEmpty();
        assertThat(Iban.isValid(iban)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "UA21322313000002600723356600", // 28 characters
        "UA2132231300000260072335660011", // 30 characters
        "UA21", "UA", ""
    })
    void aWrongLengthIsRejected(String iban) {
        assertThat(Iban.parse(iban)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "DE89370400440532013000", // a valid German IBAN
        "GB82WEST12345698765432", // a valid British IBAN
        "PL61109010140000071219812874", // a valid Polish IBAN
        "XX213223130000026007233566001", // the Ukrainian digits with another country
    })
    void anIbanOfAnotherCountryIsRejected(String iban) {
        assertThat(Iban.parse(iban)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "UA21322313000002600723356600A", // a letter in the account
        "UA21-3223-1300-0002-6007-2335-6600-1", // dashes are not spaces
        "UA21322313000002600723356600١", // an Arabic-Indic digit one
    })
    void otherCharactersAreRejected(String iban) {
        assertThat(Iban.parse(iban)).isEmpty();
    }

    @Test
    void anInvalidValueCannotBeConstructed() {
        assertThatThrownBy(() -> new Iban("UA223223130000026007233566001"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Iban("ua213223130000026007233566001"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
