package app.nadel.members.internal;

import static app.nadel.members.internal.InvitationRequests.chairman;
import static app.nadel.members.internal.InvitationRequests.demo;
import static app.nadel.members.internal.InvitationRequests.operator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import app.nadel.identity.AccessProfile;
import app.nadel.identity.FakeSignInAccounts;
import app.nadel.identity.SignInAccounts;
import app.nadel.members.InvitationDelivery;
import app.nadel.members.InvitationDeliveryResult;
import app.nadel.notifications.ClosedPort;
import app.nadel.notifications.Mailpit;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import app.nadel.tenancy.TenantContext;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Resend, disable, the archive and e-mail rules and the last sign-in of feature 020 (§2.1, technical considerations
 * 2.1 and 2.4), with the in-memory Keycloak of the tests ({@link FakeSignInAccounts}) and real letters through Mailpit:
 * a resend replaces the link (and a failed one keeps it), disable and archive turn the account off through
 * {@link MemberAccessListener}, a disable or archive while an invitation letter is on its way wins, restore leaves
 * access off, an e-mail change is checked in Keycloak first and then
 * invites the member again with a letter to the new address, the listener retries only what may pass, and the last
 * sign-in is stored once per interval. {@code MemberAccessKeycloakTests} runs the main flows against a real Keycloak.
 */
@ExtendWith(OutputCaptureExtension.class)
class MemberAccessTests extends IntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeSignInAccounts accounts;

    @Autowired
    MemberAccessListener listener;

    @Autowired
    MemberInvitations memberInvitations;

    @Autowired
    InvitationDelivery realDelivery;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    MemberCrypto memberCrypto;

    @Autowired
    MemberAccessState accessState;

    @Autowired
    MemberInvitationRows invitationRows;

    @Autowired
    SignInAccounts signInAccounts;

    @Autowired
    MemberAuditPublisher memberAudit;

    @Autowired
    MemberAccessRevocation revocation;

    @Autowired
    LastChairmanGuard lastChairmanGuard;

    @Autowired
    Clock clock;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Value("${nadel.web.url}")
    String webUrl;

    @Value("${nadel.invitations.ttl}")
    Duration invitationTtl;

    private final Mailpit mailpit = Mailpit.shared();
    private InvitationRequests http;

    @BeforeEach
    void setUp() {
        http = new InvitationRequests(mvc);
        mailpit.clear();
    }

    @AfterEach
    void cleanUp() {
        // the listener work and the audit entries of this test end before the next test counts its own
        await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                "select count(*) from event_publication where completion_date is null", Integer.class) == 0);
        ownerJdbc().update("delete from delivery_settings where tenant_id = ?", DEMO_TENANT);
        accounts.reset();
        unlinkByEmail(DEMO_TENANT, "chairman@demo.local", "treasurer@demo.local", "member@demo.local");
    }

    private static String freshEmail() {
        return "access-" + UUID.randomUUID() + "@example.com";
    }

    private UUID invitee(String email) {
        return newMember(DEMO_TENANT, "Коваль", "Олена", null, email);
    }

    private String state(UUID member) {
        List<String> states = ownerJdbc().queryForList("select state from member_access where member_id = ?",
                String.class, member);
        return states.isEmpty() ? null : states.getFirst();
    }

    private int audited(UUID member, String action) {
        Integer count = ownerJdbc().queryForObject("select count(*) from audit_entry where entity_id = ? "
                + "and action = ?", Integer.class, member, action);
        return count == null ? 0 : count;
    }

    private String auditChanges(UUID member, String action) {
        return ownerJdbc().queryForObject("select cast(changes as text) from audit_entry where entity_id = ? "
                + "and action = ?", String.class, member, action);
    }

    private int openLinks(UUID member) {
        Integer count = ownerJdbc().queryForObject("select count(*) from member_invitation where member_id = ? "
                + "and sent_at is not null and used_at is null and revoked_at is null", Integer.class, member);
        return count == null ? 0 : count;
    }

    /** Invites the member through the API and answers the token of the letter. */
    private String invite(UUID member, String email) throws Exception {
        http.invite(chairman(), member).andExpect(status().isOk());
        return InvitationRequests.tokenOfLetterTo(email);
    }

    /** Invites the member and sets the password from the letter: the member has access. */
    private String activate(UUID member, String email) throws Exception {
        String token = invite(member, email);
        http.accept(token, PASSWORD).andExpect(status().isNoContent());
        assertThat(state(member)).isEqualTo("ACTIVE");
        return subjectOf(DEMO_TENANT, member);
    }

    /** A member linked to an account made by hand before feature 020: a subject and no access row. */
    private String legacy(UUID member, String email) {
        String account = accounts.add(email, DEMO_TENANT, true);
        inTenant(DEMO_TENANT, sql -> sql.update("update member set keycloak_subject = ? where id = ?", account,
                member));
        return account;
    }

    // ---- resend --------------------------------------------------------------------------------------------------

    @Test
    void aResendMakesTheOldLinkInvalidAndSendsANewLetter() throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        UUID member = invitee(email);
        String first = invite(member, email);

        http.resend(chairman(), member).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVITED"))
                .andExpect(jsonPath("$.invitedAt").exists());

        assertThat(mailpit.lettersTo(email)).hasSize(2);
        String second = InvitationRequests.tokenOfLetterTo(email);
        assertThat(second).isNotEqualTo(first);
        http.inspect(first).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        http.inspect(second).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        assertThat(openLinks(member)).isEqualTo(1);
        assertThat(accounts.byLogin(email)).as("the same account").hasSize(1);
        await().atMost(Duration.ofSeconds(10)).until(() -> audited(member, "MEMBER_INVITE_RESENT") == 1);
        assertThat(auditChanges(member, "MEMBER_INVITE_RESENT")).contains("\"channels\"").contains("EMAIL")
                .doesNotContain(second).doesNotContain(first);
        assertThat(audited(member, "MEMBER_INVITED")).isEqualTo(1);

        http.accept(second, PASSWORD).andExpect(status().isNoContent());
        assertThat(state(member)).isEqualTo("ACTIVE");
    }

    @Test
    void aResendThatIsNotSentKeepsThePreviousLink() throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        UUID member = invitee(email);
        String first = invite(member, email);

        http.mailboxOn("127.0.0.1", ClosedPort.next());
        http.resend(chairman(), member).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("INVITATION_NOT_SENT"))
                .andExpect(jsonPath("$.failureCode").value("CONNECTION_FAILED"));

        assertThat(state(member)).isEqualTo("INVITED");
        http.inspect(first).andExpect(status().isOk());
        assertThat(openLinks(member)).isEqualTo(1);
        Thread.sleep(500); // an audit event, if one were published, would be written within this time
        assertThat(audited(member, "MEMBER_INVITE_RESENT")).isZero();
        http.accept(first, PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void onlyAnInvitedMemberGetsTheInvitationAgain() throws Exception {
        http.mailboxOnMailpit();
        UUID never = invitee(freshEmail());
        http.resend(chairman(), never).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_INVITED"));

        String email = freshEmail();
        UUID active = invitee(email);
        activate(active, email);
        http.resend(chairman(), active).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_INVITED"));

        String archivedEmail = freshEmail();
        UUID archived = invitee(archivedEmail);
        invite(archived, archivedEmail);
        archiveMember(DEMO_TENANT, archived);
        http.resend(chairman(), archived).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_ARCHIVED"));

        http.resend(chairman(), UUID.randomUUID()).andExpect(status().isNotFound());
        assertThat(mailpit.letters()).hasSize(2); // the two invitations only
    }

    // ---- disable -------------------------------------------------------------------------------------------------

    @Test
    void disableTurnsAccessOffOnceAndTheAccountOff() throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        UUID member = invitee(email);
        String subject = activate(member, email);
        http.me(TestTokens.bearer(DEMO_TENANT, subject, email)).andExpect(status().isOk());

        http.disable(chairman(), member).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.disabledAt").exists());

        assertThat(state(member)).isEqualTo("DISABLED");
        http.me(TestTokens.bearer(DEMO_TENANT, subject, email)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_LINKED"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            FakeSignInAccounts.Account account = accounts.account(subject).orElseThrow();
            assertThat(account.enabled()).isFalse();
            assertThat(account.logouts()).isEqualTo(1);
        });
        await().atMost(Duration.ofSeconds(10)).until(() -> audited(member, "MEMBER_ACCESS_DISABLED") == 1);
        http.card(chairman(), member).andExpect(jsonPath("$.access.status").value("DISABLED"))
                .andExpect(jsonPath("$.lastName").value("Коваль"));

        // already off: a no-op without a second audit entry
        http.disable(chairman(), member).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
        Thread.sleep(500);
        assertThat(audited(member, "MEMBER_ACCESS_DISABLED")).isEqualTo(1);
    }

    @Test
    void disablingAnInvitationKillsItsLink() throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        UUID member = invitee(email);
        String token = invite(member, email);

        http.disable(chairman(), member).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));

        assertThat(openLinks(member)).isZero();
        http.inspect(token).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        String subject = subjectOf(DEMO_TENANT, member);
        await().atMost(Duration.ofSeconds(10)).until(() -> !accounts.account(subject).orElseThrow().enabled());

        // the board invites again: a new link, the account off until the member uses it
        http.invite(chairman(), member).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INVITED"));
        assertThat(accounts.account(subject).orElseThrow().enabled()).isFalse();
        http.accept(InvitationRequests.tokenOfLetterTo(email), PASSWORD).andExpect(status().isNoContent());
        assertThat(accounts.account(subject).orElseThrow().enabled()).isTrue();
    }

    @Test
    void aMemberLinkedBeforeInvitationsIsDisabledToo() throws Exception {
        String email = freshEmail();
        UUID member = invitee(email);
        String account = legacy(member, email);
        http.card(chairman(), member).andExpect(jsonPath("$.access.status").value("ACTIVE"));

        http.disable(chairman(), member).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));

        assertThat(state(member)).isEqualTo("DISABLED");
        await().atMost(Duration.ofSeconds(10)).until(() -> !accounts.account(account).orElseThrow().enabled());
        http.me(TestTokens.bearer(DEMO_TENANT, account, email)).andExpect(status().isForbidden());
    }

    @Test
    void aMemberWhoWasNeverInvitedHasNoAccessToTurnOff() throws Exception {
        UUID member = invitee(freshEmail());

        http.disable(chairman(), member).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCESS_NOT_GRANTED"));
        http.disable(chairman(), UUID.randomUUID()).andExpect(status().isNotFound());
        UUID foreign = newMember(OTHER_TENANT, "Інший", "Кооператив", null, freshEmail());
        http.disable(chairman(), foreign).andExpect(status().isNotFound());

        assertThat(state(member)).isNull();
    }

    @Test
    void onlyAHolderOfTheInvitePermissionResendsOrDisables() throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        UUID member = invitee(email);
        invite(member, email);

        for (String bearer : List.of(demo("treasurer@demo.local"), demo("member@demo.local"), operator())) {
            http.resend(bearer, member).andExpect(status().isForbidden());
            http.disable(bearer, member).andExpect(status().isForbidden());
        }
        assertThat(state(member)).isEqualTo("INVITED");
        assertThat(mailpit.lettersTo(email)).hasSize(1);

        http.resend(chairman(), member).andExpect(status().isOk());
        http.disable(chairman(), member).andExpect(status().isOk());
    }

    // ---- archive and restore -------------------------------------------------------------------------------------

    @Test
    void archiveTurnsAccessOffAndRestoreLeavesItOff() throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        UUID member = invitee(email);
        String subject = activate(member, email);

        http.archive(chairman(), member).andExpect(status().isOk());

        assertThat(state(member)).isEqualTo("DISABLED");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            FakeSignInAccounts.Account account = accounts.account(subject).orElseThrow();
            assertThat(account.enabled()).isFalse();
            assertThat(account.logouts()).isEqualTo(1);
        });

        http.restore(chairman(), member).andExpect(status().isOk())
                .andExpect(jsonPath("$.access.status").value("DISABLED"));
        assertThat(state(member)).isEqualTo("DISABLED");
        Thread.sleep(500); // a listener, if one were triggered, would have turned the account on within this time
        assertThat(accounts.account(subject).orElseThrow().enabled()).isFalse();
        http.me(TestTokens.bearer(DEMO_TENANT, subject, email)).andExpect(status().isForbidden());
        assertThat(audited(member, "MEMBER_ACCESS_DISABLED")).as("the archive is the audit entry").isZero();
    }

    @Test
    void archivingAnInvitedMemberKillsTheLinkAndANeverInvitedOneGetsNoRow() throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        UUID invited = invitee(email);
        String token = invite(invited, email);

        http.archive(chairman(), invited).andExpect(status().isOk());

        assertThat(state(invited)).isEqualTo("DISABLED");
        assertThat(openLinks(invited)).isZero();
        http.restore(chairman(), invited).andExpect(status().isOk());
        http.inspect(token).andExpect(status().isNotFound());

        UUID never = invitee(freshEmail());
        http.archive(chairman(), never).andExpect(status().isOk());
        assertThat(state(never)).isNull();
    }

    // ---- e-mail change -------------------------------------------------------------------------------------------

    @Test
    void anEmailChangeMovesTheLoginAndSendsANewLinkToTheNewAddress() throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        UUID member = invitee(email);
        String oldToken = invite(member, email);
        String subject = subjectOf(DEMO_TENANT, member);
        String newEmail = freshEmail();

        http.update(chairman(), member, "Коваль", "Олена", newEmail).andExpect(status().isOk())
                .andExpect(jsonPath("$.access.status").value("INVITED"));

        http.inspect(oldToken).andExpect(status().isNotFound());
        await().atMost(Duration.ofSeconds(10)).until(() -> mailpit.lettersTo(newEmail).size() == 1);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            FakeSignInAccounts.Account account = accounts.account(subject).orElseThrow();
            assertThat(account.login()).isEqualTo(newEmail);
            assertThat(account.emailVerified()).isFalse();
        });
        assertThat(mailpit.lettersTo(email)).as("nothing more to the old address").hasSize(1);
        String newToken = InvitationRequests.tokenOfLetterTo(newEmail);
        http.inspect(newToken).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(newEmail));
        http.accept(newToken, PASSWORD).andExpect(status().isNoContent());

        assertThat(state(member)).isEqualTo("ACTIVE");
        assertThat(accounts.account(subject).orElseThrow().emailVerified()).isTrue();
        http.me(TestTokens.bearer(DEMO_TENANT, subject, newEmail)).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(member.toString()));
        assertThat(audited(member, "MEMBER_INVITE_RESENT")).as("the change is audited as MEMBER_UPDATED").isZero();
    }

    @Test
    void anEmailChangeOfAMemberWithAccessInvitesThemAgain() throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        UUID member = invitee(email);
        String subject = activate(member, email);
        String newEmail = freshEmail();

        http.update(chairman(), member, "Коваль", "Олена", newEmail).andExpect(status().isOk());

        assertThat(state(member)).isEqualTo("INVITED");
        http.me(TestTokens.bearer(DEMO_TENANT, subject, email)).andExpect(status().isForbidden());
        await().atMost(Duration.ofSeconds(10)).until(() -> mailpit.lettersTo(newEmail).size() == 1);
        FakeSignInAccounts.Account account = accounts.account(subject).orElseThrow();
        assertThat(account.login()).isEqualTo(newEmail);
        assertThat(account.emailVerified()).isFalse();
        assertThat(account.enabled()).as("off until the new link is used").isFalse();
        assertThat(account.password()).as("the old password is removed").isNull();
        assertThat(account.credentialRemovals()).isEqualTo(1);
        assertThat(account.logouts()).isEqualTo(1);

        http.accept(InvitationRequests.tokenOfLetterTo(newEmail), PASSWORD).andExpect(status().isNoContent());
        assertThat(accounts.account(subject).orElseThrow().enabled()).isTrue();
        assertThat(state(member)).isEqualTo("ACTIVE");
    }

    @Test
    void withoutAMailboxTheMemberStaysInvitedWithoutALink() throws Exception {
        String email = freshEmail();
        UUID member = invitee(email);
        String account = legacy(member, email);
        String newEmail = freshEmail();

        http.update(chairman(), member, "Коваль", "Олена", newEmail).andExpect(status().isOk())
                .andExpect(jsonPath("$.access.status").value("INVITED"))
                .andExpect(jsonPath("$.access.inviteBlockedBy").value("MAILBOX_NOT_CONFIGURED"));

        await().atMost(Duration.ofSeconds(10)).until(() -> newEmail.equals(
                accounts.account(account).orElseThrow().login()));
        assertThat(mailpit.letters()).isEmpty();
        assertThat(ownerJdbc().queryForObject("select count(*) from member_invitation where member_id = ?",
                Integer.class, member)).isZero();
        http.me(TestTokens.bearer(DEMO_TENANT, account, newEmail)).andExpect(status().isForbidden());
    }

    @Test
    void aNewEmailThatCannotBeTheLoginIsRefused() throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        UUID member = invitee(email);
        invite(member, email);

        String elsewhere = freshEmail();
        accounts.add(elsewhere, OTHER_TENANT, true);
        http.update(chairman(), member, "Коваль", "Олена", elsewhere).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_IN_OTHER_COOPERATIVE"))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(OTHER_TENANT.toString()))));

        String taken = freshEmail();
        accounts.add(taken, DEMO_TENANT, true);
        http.update(chairman(), member, "Коваль", "Олена", taken).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SIGN_IN_ACCOUNT_IN_USE"));

        assertThat(memberEmail(DEMO_TENANT, member)).isEqualTo(email);
        assertThat(state(member)).isEqualTo("INVITED");
        assertThat(openLinks(member)).isEqualTo(1);
    }

    @Test
    void theSignInServiceIsAskedOnlyForTheLoginOfAMemberWithAccess() throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        UUID member = invitee(email);
        invite(member, email);
        UUID plain = invitee(freshEmail());
        accounts.unavailable(true);

        http.update(chairman(), member, "Коваль", "Олена", freshEmail()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SIGN_IN_SERVICE_UNAVAILABLE"));
        assertThat(memberEmail(DEMO_TENANT, member)).isEqualTo(email);

        // the same e-mail, or a member never invited: no question to Keycloak
        http.update(chairman(), member, "Коваленко", "Олена", email).andExpect(status().isOk());
        http.update(chairman(), plain, "Петренко", "Ігор", freshEmail()).andExpect(status().isOk());
        assertThat(state(member)).isEqualTo("INVITED");
        assertThat(state(plain)).isNull();
    }

    // ---- a change while the letter is on its way ----------------------------------------------------------------

    /**
     * {@link MemberInvitations} wired like the application's, except that its letters go through the real delivery
     * and then run {@code meanwhile}: what the board does while a letter is on its way (no lock is held then).
     */
    private MemberInvitations racing(Runnable meanwhile) {
        InvitationDelivery racingDelivery = new InvitationDelivery() {
            @Override
            public boolean emailReady(UUID tenantId) {
                return realDelivery.emailReady(tenantId);
            }

            @Override
            public InvitationDeliveryResult deliver(UUID tenantId, UUID memberId, String email, URI link,
                    Instant expiresAt) {
                InvitationDeliveryResult result = realDelivery.deliver(tenantId, memberId, email, link, expiresAt);
                meanwhile.run();
                return result;
            }
        };
        InvitationDeliveries racingDeliveries = new InvitationDeliveries(
                new StaticListableBeanFactory(Map.of("racingDelivery", racingDelivery))
                        .getBeanProvider(InvitationDelivery.class));
        return new MemberInvitations(memberRepository, memberCrypto, accessState, invitationRows, racingDeliveries,
                signInAccounts, memberAudit, revocation, lastChairmanGuard, clock, transactionManager, webUrl,
                invitationTtl);
    }

    private AccessProfile chairmanProfile() {
        return new AccessProfile(DEMO_TENANT, memberIdByEmail(DEMO_TENANT, "chairman@demo.local"),
                "sub-chairman@demo.local", "Голова", Set.of(), List.of(), false);
    }

    @Test
    void aDeliveryThatBreaksRevokesTheLinkAndChangesNothing() throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        UUID member = invitee(email);
        AccessProfile chairman = chairmanProfile();
        MemberInvitations racing = racing(() -> {
            throw new IllegalStateException("the delivery broke");
        });

        assertThatThrownBy(() -> TenantContext.runAs(DEMO_TENANT, () -> racing.invite(member, chairman)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("the delivery broke");

        assertThat(state(member)).as("the member was never invited").isEqualTo("NONE");
        assertThat(openLinks(member)).as("the link made for the letter is revoked").isZero();
        Thread.sleep(500); // an audit event, if one were published, would be written within this time
        assertThat(audited(member, "MEMBER_INVITED")).isZero();
    }

    @Test
    void aLinkPrinterThatBreaksRevokesTheLinkAndChangesNothing() {
        UUID member = invitee(freshEmail());
        AccessProfile chairman = chairmanProfile();

        assertThatThrownBy(() -> TenantContext.runAs(DEMO_TENANT, () -> memberInvitations.issueForCommand(member,
                chairman, false, (link, expiresAt) -> {
                    throw new IllegalStateException("the printer broke");
                }))).isInstanceOf(IllegalStateException.class).hasMessageContaining("the printer broke");

        assertThat(state(member)).isEqualTo("NONE");
        assertThat(ownerJdbc().queryForObject("select count(*) from member_invitation where member_id = ? "
                + "and revoked_at is not null and sent_at is null", Integer.class, member)).isEqualTo(1);
    }

    @Test
    void aDisableWhileTheResentLetterIsOnItsWayWins() throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        UUID member = invitee(email);
        String first = invite(member, email);
        String subject = subjectOf(DEMO_TENANT, member);
        AccessProfile chairman = chairmanProfile();
        MemberInvitations racing = racing(() -> memberInvitations.disable(member, chairman));

        assertThatThrownBy(() -> TenantContext.runAs(DEMO_TENANT, () -> racing.resend(member, chairman)))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> assertThat(e.code())
                        .isEqualTo(ProblemCodes.NOT_INVITED));

        assertThat(mailpit.lettersTo(email)).as("the resent letter did go out").hasSize(2);
        String second = InvitationRequests.tokenOfLetterTo(email);
        assertThat(state(member)).isEqualTo("DISABLED");
        assertThat(openLinks(member)).isZero();
        http.inspect(second).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        http.inspect(first).andExpect(status().isNotFound());
        http.accept(second, PASSWORD).andExpect(status().isNotFound());
        await().atMost(Duration.ofSeconds(10)).until(() -> !accounts.account(subject).orElseThrow().enabled());
        await().atMost(Duration.ofSeconds(10)).until(() -> audited(member, "MEMBER_ACCESS_DISABLED") == 1);
        Thread.sleep(500); // an audit event, if one were published, would be written within this time
        assertThat(audited(member, "MEMBER_INVITE_RESENT")).isZero();
        assertThat(state(member)).isEqualTo("DISABLED");
    }

    @Test
    void anArchiveWhileTheInvitationLetterIsOnItsWayWins() throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        UUID member = invitee(email);
        AccessProfile chairman = chairmanProfile();
        MemberInvitations racing = racing(() -> archiveMember(DEMO_TENANT, member));

        assertThatThrownBy(() -> TenantContext.runAs(DEMO_TENANT, () -> racing.invite(member, chairman)))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> assertThat(e.code())
                        .isEqualTo(ProblemCodes.MEMBER_ARCHIVED));

        String token = InvitationRequests.tokenOfLetterTo(email);
        assertThat(state(member)).isNotEqualTo("INVITED");
        assertThat(openLinks(member)).isZero();
        http.inspect(token).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        Thread.sleep(500); // an audit event, if one were published, would be written within this time
        assertThat(audited(member, "MEMBER_INVITED")).isZero();
    }

    // ---- the listener --------------------------------------------------------------------------------------------

    @Test
    void theListenerRetriesWhatMayPassAndDropsWhatWillNot() throws Exception {
        String email = freshEmail();
        UUID member = invitee(email);
        String account = legacy(member, email);
        inTenant(DEMO_TENANT, sql -> sql.update("insert into member_access (member_id, tenant_id, state, disabled_at) "
                + "values (?, ?, 'DISABLED', now())", member, DEMO_TENANT));

        // Keycloak down: thrown, so the outbox resubmits the publication
        accounts.unavailable(true);
        assertThatThrownBy(() -> listener.revoked(new MemberAccessRevoked(DEMO_TENANT, member)))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> assertThat(e.code())
                        .isEqualTo(ProblemCodes.SIGN_IN_SERVICE_UNAVAILABLE));
        accounts.unavailable(false);
        assertThat(accounts.account(account).orElseThrow().enabled()).isTrue();

        // the account moved to another cooperative by hand: refused by the tenant guard, nothing to retry
        accounts.reassign(account, OTHER_TENANT);
        listener.revoked(new MemberAccessRevoked(DEMO_TENANT, member));
        assertThat(accounts.account(account).orElseThrow().enabled()).isTrue();

        // the account was removed by hand: nothing to turn off
        accounts.remove(account);
        listener.revoked(new MemberAccessRevoked(DEMO_TENANT, member));

        // the access is on again by now (a newer invitation was accepted): the late event changes nothing
        String other = freshEmail();
        UUID active = invitee(other);
        String activeAccount = legacy(active, other);
        listener.revoked(new MemberAccessRevoked(DEMO_TENANT, active));
        assertThat(accounts.account(activeAccount).orElseThrow().enabled()).isTrue();
    }

    @Test
    void theNewLoginLinkIsRetriedUntilItIsSentAndNeverTwice() throws Exception {
        http.mailboxOn("127.0.0.1", ClosedPort.next());
        String email = freshEmail();
        UUID member = invitee(email);
        String account = legacy(member, email);
        inTenant(DEMO_TENANT, sql -> sql.update("insert into member_access (member_id, tenant_id, state, invited_at) "
                + "values (?, ?, 'INVITED', now())", member, DEMO_TENANT));
        MemberLoginChanged event = new MemberLoginChanged(DEMO_TENANT, member);

        // the letter is not sent: thrown for a retry, no open link
        assertThatThrownBy(() -> listener.loginChanged(event))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> assertThat(e.code())
                        .isEqualTo(ProblemCodes.INVITATION_NOT_SENT));
        assertThat(openLinks(member)).isZero();

        // the retry sends it; a redelivered event sends nothing more
        http.mailboxOnMailpit();
        listener.loginChanged(event);
        listener.loginChanged(event);
        assertThat(mailpit.lettersTo(email)).hasSize(1);
        assertThat(openLinks(member)).isEqualTo(1);
        assertThat(accounts.account(account).orElseThrow().emailVerified()).isFalse();

        // a member no longer invited is left alone
        String otherEmail = freshEmail();
        UUID other = invitee(otherEmail);
        legacy(other, otherEmail);
        listener.loginChanged(new MemberLoginChanged(DEMO_TENANT, other));
        assertThat(mailpit.lettersTo(otherEmail)).isEmpty();

        // the account moved to another cooperative by hand: refused by the tenant guard, nothing sent, nothing retried
        String movedEmail = freshEmail();
        UUID moved = invitee(movedEmail);
        String movedAccount = legacy(moved, movedEmail);
        inTenant(DEMO_TENANT, sql -> sql.update("insert into member_access (member_id, tenant_id, state, invited_at) "
                + "values (?, ?, 'INVITED', now())", moved, DEMO_TENANT));
        accounts.reassign(movedAccount, OTHER_TENANT);
        listener.loginChanged(new MemberLoginChanged(DEMO_TENANT, moved));
        assertThat(mailpit.lettersTo(movedEmail)).isEmpty();
        assertThat(openLinks(moved)).isZero();
    }

    // ---- last sign-in --------------------------------------------------------------------------------------------

    @Test
    void theLastSignInIsStoredOncePerInterval() throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        UUID member = invitee(email);
        String subject = activate(member, email);
        String bearer = TestTokens.bearer(DEMO_TENANT, subject, email);
        http.card(chairman(), member).andExpect(jsonPath("$.access.lastSignInAt").doesNotExist());

        http.me(bearer).andExpect(status().isOk());
        Timestamp first = lastSignIn(member);
        assertThat(first).isNotNull();
        http.card(chairman(), member).andExpect(jsonPath("$.access.lastSignInAt").exists());

        http.me(bearer).andExpect(status().isOk());
        assertThat(lastSignIn(member)).as("within the interval nothing is written").isEqualTo(first);

        ownerJdbc().update("update member_access set last_sign_in_at = now() - interval '2 hours' "
                + "where member_id = ?", member);
        Timestamp old = lastSignIn(member);
        http.me(bearer).andExpect(status().isOk());
        assertThat(lastSignIn(member)).isAfter(old);
    }

    @Test
    void aLastSignInThatCannotBeStoredNeverStopsTheSignIn(CapturedOutput output) throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        UUID member = invitee(email);
        String subject = activate(member, email);
        String bearer = TestTokens.bearer(DEMO_TENANT, subject, email);
        ownerJdbc().execute("create function test_refuse_sign_in() returns trigger language plpgsql as "
                + "$$ begin raise exception 'refused by the test'; end $$");
        ownerJdbc().execute("create trigger test_refuse_sign_in before update on member_access "
                + "for each row execute function test_refuse_sign_in()");
        try {
            http.me(bearer).andExpect(status().isOk());
        } finally {
            ownerJdbc().execute("drop trigger test_refuse_sign_in on member_access");
            ownerJdbc().execute("drop function test_refuse_sign_in()");
        }

        assertThat(lastSignIn(member)).isNull();
        assertThat(output.getAll()).contains("not stored").doesNotContain(email);
    }

    @Test
    void aMemberLinkedBeforeInvitationsGetsNoRowAtSignIn() throws Exception {
        String email = freshEmail();
        UUID member = invitee(email);
        String account = legacy(member, email);

        http.me(TestTokens.bearer(DEMO_TENANT, account, email)).andExpect(status().isOk());

        assertThat(state(member)).as("no member_access row").isNull();
    }

    private Timestamp lastSignIn(UUID member) {
        return ownerJdbc().queryForObject("select last_sign_in_at from member_access where member_id = ?",
                Timestamp.class, member);
    }
}
