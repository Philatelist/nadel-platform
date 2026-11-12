package app.nadel.notifications.internal.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import app.nadel.privacy.PersonalDataCipher;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The push tables are never touched without a bound cooperative (the device store and the throttle refuse before any
 * query or key use), nor when there is nothing to do: no members to look up, a code that is not throttled, no members
 * to claim. A code that is not throttled claims each member once, in the order given.
 */
class PushStorageGuardTests {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PersonalDataCipher cipher = mock(PersonalDataCipher.class);
    private final PushSubscriptions subscriptions = new PushSubscriptions(jdbc, cipher);
    private final PushThrottle throttle = new PushThrottle(jdbc);
    private final UUID anna = UUID.randomUUID();
    private final UUID petro = UUID.randomUUID();

    @Test
    void withoutACooperativeNoDeviceIsReadOrSaved() {
        assertThatThrownBy(() -> subscriptions.target(UUID.randomUUID())).isInstanceOf(IllegalStateException.class)
                .hasMessage("No cooperative");
        assertThatThrownBy(() -> subscriptions.save(anna, "https://fcm.googleapis.com/fcm/send/a", new byte[65],
                new byte[16], null, "uk", new byte[8])).isInstanceOf(IllegalStateException.class)
                .hasMessage("No cooperative");

        verifyNoInteractions(jdbc, cipher);
    }

    @Test
    void noMembersHaveNoDevicesWithoutAQuery() {
        assertThat(subscriptions.devicesOf(List.of())).isEmpty();

        verifyNoInteractions(jdbc);
    }

    @Test
    void aCodeThatIsNotThrottledClaimsEveryMemberOnceWithoutAQuery() {
        assertThat(throttle.claim(PushCode.PAYMENT_RECORDED, List.of(anna, petro, anna), Instant.now()))
                .containsExactly(anna, petro);
        assertThat(throttle.claim(PushCode.READINGS_TO_APPROVE, List.of(), Instant.now())).isEmpty();

        verifyNoInteractions(jdbc);
    }

    @Test
    void withoutACooperativeNothingIsClaimed() {
        assertThatThrownBy(() -> throttle.claim(PushCode.READINGS_TO_APPROVE, List.of(anna), Instant.now()))
                .isInstanceOf(IllegalStateException.class).hasMessage("No cooperative");

        verifyNoInteractions(jdbc);
    }
}
