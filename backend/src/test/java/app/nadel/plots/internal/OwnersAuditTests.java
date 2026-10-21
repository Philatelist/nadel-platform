package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.members.MemberRef;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The audit value of the owners of a plot: structured parameters, not a display phrase. */
class OwnersAuditTests {

    private static MemberRef member(String last, String first) {
        return new MemberRef(UUID.randomUUID(), last, first, null, false);
    }

    @Test
    void noOwnersIsNull() {
        assertThat(OwnersAudit.of(Stream.empty())).isNull();
    }

    @Test
    void ownersAreAJsonArraySortedByNameIgnoringLetterCase() {
        String value = OwnersAudit.of(Stream.of(
                OwnersAudit.entry(member("beta", "Boris"), Share.parse("1/3")),
                OwnersAudit.entry(member("Alpha", "Anna"), Share.parse("2/3"))));

        assertThat(value).isEqualTo(
                "[{\"member\":\"Alpha Anna\",\"share\":\"2/3\"},{\"member\":\"beta Boris\",\"share\":\"1/3\"}]");
    }

    @Test
    void aPeriodIsWrittenAsIsoDatesAndAnOpenEndIsAbsent() {
        String value = OwnersAudit.of(Stream.of(
                OwnersAudit.entry(member("Alpha", "Anna"), Share.parse("1/1"), LocalDate.of(2020, 1, 1), null),
                OwnersAudit.entry(member("Beta", "Boris"), Share.parse("1/1"), LocalDate.of(2020, 1, 1),
                        LocalDate.of(2022, 12, 31))));

        assertThat(value).isEqualTo("[{\"member\":\"Alpha Anna\",\"share\":\"1/1\",\"from\":\"2020-01-01\"},"
                + "{\"member\":\"Beta Boris\",\"share\":\"1/1\",\"from\":\"2020-01-01\",\"to\":\"2022-12-31\"}]");
    }

    @Test
    void aNameWithQuotesAndCommasStaysOneParameter() {
        String value = OwnersAudit.of(Stream.of(OwnersAudit.entry(member("O\"Neil, Jr.", "Tom"), Share.parse("1/1"))));

        assertThat(value).isEqualTo("[{\"member\":\"O\\\"Neil, Jr. Tom\",\"share\":\"1/1\"}]");
    }
}
