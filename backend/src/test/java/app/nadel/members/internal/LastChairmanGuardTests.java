package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.nadel.problem.ApiProblemException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

class LastChairmanGuardTests {

    final UUID tenant = UUID.randomUUID();
    final UUID chairman = UUID.randomUUID();
    final UUID other = UUID.randomUUID();
    final MemberRepository members = mock(MemberRepository.class);
    final LastChairmanGuard guard = new LastChairmanGuard(members);

    @Test
    void refusesTheOnlyChairman() {
        when(members.findActiveChairmanIds(tenant)).thenReturn(List.of(chairman));

        assertThatThrownBy(() -> guard.ensureNotLast(tenant, chairman))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("LAST_CHAIRMAN");
                });
    }

    @Test
    void allowsWhenAnotherChairmanRemains() {
        when(members.findActiveChairmanIds(tenant)).thenReturn(List.of(chairman, other));

        assertThatCode(() -> guard.ensureNotLast(tenant, chairman)).doesNotThrowAnyException();
    }

    @Test
    void allowsAMemberWhoIsNotAChairman() {
        when(members.findActiveChairmanIds(tenant)).thenReturn(List.of(chairman));

        assertThatCode(() -> guard.ensureNotLast(tenant, other)).doesNotThrowAnyException();
    }

    @Test
    void takesTheCooperativeLockBeforeCounting() {
        when(members.findActiveChairmanIds(tenant)).thenReturn(List.of(chairman, other));

        guard.ensureNotLast(tenant, chairman);

        InOrder order = inOrder(members);
        order.verify(members).lockChairmen(tenant);
        order.verify(members).findActiveChairmanIds(tenant);
    }
}
