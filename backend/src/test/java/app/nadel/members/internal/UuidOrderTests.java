package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class UuidOrderTests {

    private static final UUID LOW = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID JUST_BELOW_HALF = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
    private static final UUID HALF = UUID.fromString("80000000-0000-0000-0000-000000000000");
    private static final UUID HIGH = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    @Test
    void idsAreOrderedByUnsignedBytesLikePostgresDoes() {
        // UUID.compareTo puts 8..f before 0..7 because it compares the halves as signed numbers
        assertThat(HALF.compareTo(JUST_BELOW_HALF)).isNegative();

        assertThat(Stream.of(HIGH, HALF, LOW, JUST_BELOW_HALF).sorted(UuidOrder.POSTGRES).toList())
                .isEqualTo(List.of(LOW, JUST_BELOW_HALF, HALF, HIGH));
    }

    @Test
    void theSecondHalfDecidesWhenTheFirstHalvesAreEqual() {
        UUID small = UUID.fromString("11111111-1111-1111-0000-000000000000");
        UUID large = UUID.fromString("11111111-1111-1111-8000-000000000000");

        assertThat(UuidOrder.POSTGRES.compare(small, large)).isNegative();
        assertThat(UuidOrder.POSTGRES.compare(large, small)).isPositive();
        assertThat(UuidOrder.POSTGRES.compare(large, large)).isZero();
    }
}
