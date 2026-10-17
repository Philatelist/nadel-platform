package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumberTests {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            0671234567           | +380671234567
            067 123 45 67        | +380671234567
            067-123-45-67        | +380671234567
            (067) 123-45-67      | +380671234567
            067.123.45.67        | +380671234567
            [067]1234567         | +380671234567
            380671234567         | +380671234567
            380 67 123 45 67     | +380671234567
            +380671234567        | +380671234567
            +380 (67) 123-45-67  | +380671234567
            +14155552671         | +14155552671
            +12345678            | +12345678
            +123456789012345     | +123456789012345
            """)
    void normalisesToInternationalFormat(String raw, String expected) {
        assertThat(PhoneNumber.parse(raw)).map(PhoneNumber::value).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "12345", "067123456", "06712345678", "38067123456", "3806712345678", "+1234567",
            "+1234567890123456", "+0671234567", "+", "abc", "067 123 45 6a", "0671234567x", "++380671234567",
            "00380671234567", "+380-67-abc"})
    void rejectsAnythingElse(String raw) {
        assertThat(PhoneNumber.parse(raw)).isEmpty();
    }

    @Test
    void rejectsNull() {
        assertThat(PhoneNumber.parse(null)).isEmpty();
    }
}
