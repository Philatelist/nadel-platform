package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import app.nadel.tenancy.TenantProvisioning;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * What the first-chairman work prints once the chairman exists (feature 020, technical considerations 2.1), with its
 * collaborators as stand-ins: an existing chairman who is not invited yet is invited through
 * {@link MemberInvitations#issueForCommand}. A cooperative with a mailbox gets the letter and nothing is printed but
 * that it went; a printer called a second time is refused, so a link is never printed twice.
 */
class FirstChairmanOutcomeTests {

    private static final String EMAIL = "chairman@coop.example";
    private static final URI LINK = URI.create("http://localhost:5173/set-password?token=outcomeTOKEN_1");

    private static final jakarta.validation.ValidatorFactory VALIDATION = Validation.buildDefaultValidatorFactory();

    private final TenantProvisioning provisioning = mock(TenantProvisioning.class);
    private final MemberRepository members = mock(MemberRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final RoleGrantReader grants = mock(RoleGrantReader.class);
    private final MemberCrypto crypto = mock(MemberCrypto.class);
    private final MemberCommands memberCommands = mock(MemberCommands.class);
    private final MemberRoleCommands roleCommands = mock(MemberRoleCommands.class);
    private final MemberAccessState access = mock(MemberAccessState.class);
    private final MemberInvitations invitations = mock(MemberInvitations.class);
    private final Validator validator = VALIDATION.getValidator();
    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(printed, true, StandardCharsets.UTF_8);
    private final UUID memberId = UUID.randomUUID();
    private FirstChairman work;

    @AfterAll
    static void closeValidation() {
        VALIDATION.close();
    }

    @BeforeEach
    void anExistingChairmanWhoIsNotInvited() {
        Member chairman = mock(Member.class);
        when(chairman.getId()).thenReturn(memberId);
        when(chairman.getKeycloakSubject()).thenReturn(null);
        when(members.findActiveByEmailBidx(any(), any())).thenReturn(Optional.of(chairman));
        when(members.findById(memberId)).thenReturn(Optional.of(chairman));
        when(grants.read(chairman)).thenReturn(List.of(new RoleGrant(UUID.randomUUID(), "Голова",
                PermissionUnion.CHAIRMAN, false, Set.of())));
        when(access.find(memberId)).thenReturn(Optional.empty());
        work = new FirstChairman(provisioning, members, roles, grants, crypto, memberCommands, roleCommands, access,
                invitations, validator, mock(PlatformTransactionManager.class));
    }

    private FirstChairman.Result run() {
        return work.run(new FirstChairman.Arguments("СТ «Лист»", EMAIL, "Іваненко", "Петро", false), out);
    }

    private String printed() {
        return printed.toString(StandardCharsets.UTF_8);
    }

    @Test
    void withAMailboxTheLetterIsSentAndNoLinkIsPrinted() {
        // the invitation goes by letter: the printer is never called
        when(invitations.issueForCommand(eq(memberId), any(), eq(false), any())).thenReturn(null);

        FirstChairman.Result result = run();

        assertThat(result.outcome()).isEqualTo(FirstChairman.Outcome.LETTER_SENT);
        assertThat(result.memberId()).isEqualTo(memberId);
        assertThat(result.chairmanCreated()).isFalse();
        assertThat(printed()).contains("Chairman exists: " + memberId)
                .contains("The invitation letter was sent to the chairman's e-mail.")
                .doesNotContain("set-password").doesNotContain(EMAIL);
        verifyNoInteractions(memberCommands, roleCommands);
    }

    @Test
    void aLinkIsNeverPrintedTwice() {
        doAnswer(call -> {
            MemberInvitations.LinkPrinter printer = call.getArgument(3);
            Instant expires = Instant.parse("2026-11-09T10:15:00Z");
            printer.print(LINK, expires);
            printer.print(LINK, expires);
            return null;
        }).when(invitations).issueForCommand(eq(memberId), any(), anyBoolean(), any());

        assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class)
                .hasMessage("A link is printed once");

        String text = printed();
        assertThat(text.indexOf(LINK.toString())).isNotNegative();
        assertThat(text.indexOf(LINK.toString())).as("printed exactly once")
                .isEqualTo(text.lastIndexOf(LINK.toString()));
        assertThat(text).contains("valid until 2026-11-09 10:15 UTC");
    }
}
