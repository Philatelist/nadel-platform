package app.nadel.members.internal;

import static app.nadel.members.internal.InvitationRequests.chairman;
import static app.nadel.members.internal.InvitationRequests.demo;
import static app.nadel.members.internal.InvitationRequests.operator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import app.nadel.identity.FakeSignInAccounts;
import app.nadel.notifications.ClosedPort;
import app.nadel.notifications.Mailpit;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Inviting a member into the system and the set-password link (feature 020 §2.1, technical considerations 2.1), with
 * the in-memory Keycloak of the tests ({@link FakeSignInAccounts}) and the real letter through Mailpit: the invitation
 * and its letter, the two-phase send (a letter that fails changes nothing), the refusals, the permissions, the access
 * block of the member card, the public inspect and accept, the sign-in rules of the stored access, and that the link
 * and the password are never written down. {@code MemberInvitationKeycloakTests} runs the main flow against a real
 * Keycloak.
 */
@ExtendWith(OutputCaptureExtension.class)
class MemberInvitationTests extends IntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeSignInAccounts accounts;

    private final Mailpit mailpit = Mailpit.shared();
    private InvitationRequests http;

    @BeforeEach
    void setUp() {
        http = new InvitationRequests(mvc);
        mailpit.clear();
    }

    @AfterEach
    void cleanUp() {
        // the audit entries of this test are written before the next test counts its own
        await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                "select count(*) from event_publication where completion_date is null", Integer.class) == 0);
        ownerJdbc().update("delete from delivery_settings where tenant_id = ?", DEMO_TENANT);
        accounts.reset();
        UUID demoMember = memberIdByEmail(DEMO_TENANT, "member@demo.local");
        ownerJdbc().update("delete from member_access where member_id = ?", demoMember);
        unlinkByEmail(DEMO_TENANT, "chairman@demo.local", "treasurer@demo.local", "member@demo.local");
    }

    private static String freshEmail() {
        return "invitee-" + UUID.randomUUID() + "@example.com";
    }

    private UUID invitee(String email) {
        return newMember(DEMO_TENANT, "Коваль", "Олена", null, email);
    }

    private String state(UUID member) {
        List<String> states = ownerJdbc().queryForList("select state from member_access where member_id = ?",
                String.class, member);
        return states.isEmpty() ? null : states.getFirst();
    }

    private List<Map<String, Object>> invitationRows(UUID member) {
        return ownerJdbc().queryForList("select token_hash, sent_at, used_at, revoked_at from member_invitation "
                + "where member_id = ? order by created_at", member);
    }

    private List<Map<String, Object>> invitedAudit(UUID member) {
        return ownerJdbc().queryForList("select action, actor_subject, entity_label, cast(changes as text) as changes "
                + "from audit_entry where entity_id = ? and action = 'MEMBER_INVITED'", member);
    }

    // ---- invite --------------------------------------------------------------------------------------------------

    @Test
    void theChairmanInvitesAMemberAndTheLetterCarriesTheOneTimeLink() throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        UUID member = invitee(email);

        http.invite(chairman(), member).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVITED"))
                .andExpect(jsonPath("$.invitedAt").exists())
                .andExpect(jsonPath("$.inviteBlockedBy").doesNotExist());

        Mailpit.Letter letter = mailpit.lettersTo(email).getFirst();
        assertThat(letter.from()).isEqualTo("board@coop.example");
        assertThat(letter.subject()).startsWith("Задайте пароль");
        String text = mailpit.text(letter.id());
        assertThat(InvitationRequests.linkIn(text)).startsWith("http://localhost:5173/set-password?token=");
        assertThat(text).contains(email).contains("Посилання діє до");
        String token = InvitationRequests.tokenIn(text);

        List<FakeSignInAccounts.Account> made = accounts.byLogin(email);
        assertThat(made).singleElement().satisfies(account -> {
            assertThat(account.tenantId()).isEqualTo(DEMO_TENANT);
            assertThat(account.enabled()).isTrue();
            assertThat(account.emailVerified()).isFalse();
            assertThat(account.password()).isNull();
        });
        assertThat(subjectOf(DEMO_TENANT, member)).isEqualTo(made.getFirst().id());
        assertThat(state(member)).isEqualTo("INVITED");
        assertThat(invitationRows(member)).singleElement().satisfies(row -> {
            assertThat(row.get("sent_at")).isNotNull();
            assertThat(row.get("used_at")).isNull();
            assertThat(row.get("revoked_at")).isNull();
            assertThat((byte[]) row.get("token_hash")).isEqualTo(InvitationTokens.hash(token));
        });
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(invitedAudit(member))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.get("actor_subject")).isEqualTo("sub-chairman@demo.local");
                    assertThat(entry.get("entity_label")).isEqualTo("Коваль Олена");
                    assertThat((String) entry.get("changes")).contains("\"channels\"").contains("EMAIL")
                            .doesNotContain(token);
                }));
    }

    @Test
    void aLetterThatIsNotSentChangesNothingAndCostsNothing() throws Exception {
        http.mailboxOn("127.0.0.1", ClosedPort.next());
        String email = freshEmail();
        UUID member = invitee(email);

        http.invite(chairman(), member).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("INVITATION_NOT_SENT"))
                .andExpect(jsonPath("$.failureCode").value("CONNECTION_FAILED"));

        assertThat(state(member)).as("still not invited").isIn(null, "NONE");
        http.card(chairman(), member).andExpect(jsonPath("$.access.status").value("NOT_INVITED"));
        assertThat(invitationRows(member)).singleElement().satisfies(row -> {
            assertThat(row.get("sent_at")).isNull();
            assertThat(row.get("revoked_at")).isNotNull();
        });
        Thread.sleep(500); // an audit event, if one were published, would be written within this time
        assertThat(invitedAudit(member)).isEmpty();

        // the mailbox is fixed: the same member is invited normally
        http.mailboxOnMailpit();
        http.invite(chairman(), member).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INVITED"));
        assertThat(accounts.byLogin(email)).hasSize(1);
        assertThat(mailpit.lettersTo(email)).hasSize(1);
    }

    @Test
    void anInvitationIsRefusedWhenItCannotBeSent() throws Exception {
        UUID noEmail = newMember(DEMO_TENANT, "Без", "Пошти", null, null);
        http.invite(chairman(), noEmail).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_EMAIL_MISSING"));

        UUID member = invitee(freshEmail());
        http.invite(chairman(), member).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_CONFIGURED"));

        http.mailboxOnMailpit();
        UUID archived = invitee(freshEmail());
        archiveMember(DEMO_TENANT, archived);
        http.invite(chairman(), archived).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_ARCHIVED"));

        http.invite(chairman(), member).andExpect(status().isOk());
        http.invite(chairman(), member).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_PENDING"));

        // a member linked to an account made by hand before feature 020 already has access
        UUID legacy = invitee(freshEmail());
        inTenant(DEMO_TENANT, sql -> sql.update("update member set keycloak_subject = ? where id = ?",
                UUID.randomUUID().toString(), legacy));
        http.invite(chairman(), legacy).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCESS_ALREADY_ACTIVE"));

        http.invite(chairman(), UUID.randomUUID()).andExpect(status().isNotFound());
        UUID foreign = newMember(OTHER_TENANT, "Інший", "Кооператив", null, freshEmail());
        http.invite(chairman(), foreign).andExpect(status().isNotFound());

        assertThat(mailpit.letters()).hasSize(1);
    }

    @Test
    void anEmailThatSignsInToAnotherCooperativeIsRefusedWithoutALetter() throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        accounts.add(email, OTHER_TENANT, true);
        UUID member = invitee(email);

        http.invite(chairman(), member).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_IN_OTHER_COOPERATIVE"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(OTHER_TENANT.toString()))));

        assertThat(mailpit.letters()).isEmpty();
        assertThat(accounts.byLogin(email)).singleElement()
                .satisfies(account -> assertThat(account.tenantId()).isEqualTo(OTHER_TENANT));
        assertThat(subjectOf(DEMO_TENANT, member)).isNull();
        assertThat(state(member)).isNull();
    }

    @Test
    void anAccountOfTheCooperativeWithTheEmailIsLinkedNotDuplicated() throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        String existing = accounts.add(email, DEMO_TENANT, false);
        UUID member = invitee(email);

        http.invite(chairman(), member).andExpect(status().isOk());

        assertThat(accounts.byLogin(email)).singleElement().satisfies(account -> {
            assertThat(account.id()).isEqualTo(existing);
            assertThat(account.enabled()).as("turned on again").isTrue();
        });
        assertThat(subjectOf(DEMO_TENANT, member)).isEqualTo(existing);
    }

    @Test
    void anAccountLinkedToAnotherMemberIsInUse() throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        String existing = accounts.add(email, DEMO_TENANT, true);
        UUID holder = invitee(freshEmail());
        inTenant(DEMO_TENANT, sql -> sql.update("update member set keycloak_subject = ? where id = ?", existing,
                holder));
        UUID member = invitee(email);

        http.invite(chairman(), member).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SIGN_IN_ACCOUNT_IN_USE"));

        // two accounts answer the login (one by e-mail, one by user name): which one is meant is unclear
        String twice = freshEmail();
        accounts.add(twice, DEMO_TENANT, true);
        accounts.add(twice, DEMO_TENANT, true);
        http.invite(chairman(), invitee(twice)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SIGN_IN_ACCOUNT_IN_USE"));

        // the member is linked to another account that still exists (its access was turned off)
        String relinked = freshEmail();
        UUID linked = invitee(relinked);
        String otherAccount = accounts.add(freshEmail(), DEMO_TENANT, false);
        inTenant(DEMO_TENANT, sql -> {
            sql.update("update member set keycloak_subject = ? where id = ?", otherAccount, linked);
            return sql.update("insert into member_access (member_id, tenant_id, state, disabled_at) "
                    + "values (?, ?, 'DISABLED', now())", linked, DEMO_TENANT);
        });
        http.invite(chairman(), linked).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SIGN_IN_ACCOUNT_IN_USE"));

        // ... unless that account was removed: the member gets the account of the e-mail
        accounts.remove(otherAccount);
        http.invite(chairman(), linked).andExpect(status().isOk());
        assertThat(subjectOf(DEMO_TENANT, linked)).isEqualTo(accounts.byLogin(relinked).getFirst().id());
        assertThat(mailpit.lettersTo(relinked)).hasSize(1);
        assertThat(mailpit.letters()).hasSize(1);
    }

    @Test
    void theSignInServiceDownIsAnsweredAndChangesNothing() throws Exception {
        http.mailboxOnMailpit();
        UUID member = invitee(freshEmail());
        accounts.unavailable(true);

        http.invite(chairman(), member).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SIGN_IN_SERVICE_UNAVAILABLE"));

        assertThat(state(member)).isNull();
        assertThat(invitationRows(member)).isEmpty();
        assertThat(mailpit.letters()).isEmpty();
    }

    @Test
    void onlyAHolderOfTheInvitePermissionInvites() throws Exception {
        http.mailboxOnMailpit();
        UUID member = invitee(freshEmail());

        for (String bearer : List.of(demo("treasurer@demo.local"), demo("member@demo.local"), operator())) {
            http.invite(bearer, member).andExpect(status().isForbidden());
        }
        assertThat(state(member)).isNull();
        assertThat(mailpit.letters()).isEmpty();
        http.invite(chairman(), member).andExpect(status().isOk());
    }

    // ---- the member card -----------------------------------------------------------------------------------------

    @Test
    void theCardShowsTheAccessToViewersOfMembersOnly() throws Exception {
        UUID noEmail = newMember(DEMO_TENANT, "Без", "Пошти", null, null);
        UUID member = invitee(freshEmail());

        http.card(chairman(), noEmail).andExpect(status().isOk())
                .andExpect(jsonPath("$.access.status").value("NOT_INVITED"))
                .andExpect(jsonPath("$.access.inviteBlockedBy").value("EMAIL_MISSING"));
        http.card(chairman(), member)
                .andExpect(jsonPath("$.access.status").value("NOT_INVITED"))
                .andExpect(jsonPath("$.access.inviteBlockedBy").value("MAILBOX_NOT_CONFIGURED"));

        http.mailboxOnMailpit();
        http.card(chairman(), member).andExpect(jsonPath("$.access.inviteBlockedBy").doesNotExist());
        http.invite(chairman(), member).andExpect(status().isOk());
        http.card(demo("treasurer@demo.local"), member).andExpect(status().isOk())
                .andExpect(jsonPath("$.access.status").value("INVITED"))
                .andExpect(jsonPath("$.access.invitedAt").exists())
                .andExpect(jsonPath("$.access.disabledAt").doesNotExist());

        // a member never sees the access block, not even on their own card
        UUID own = memberIdByEmail(DEMO_TENANT, "member@demo.local");
        http.card(demo("member@demo.local"), own).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(own.toString()))
                .andExpect(jsonPath("$.access").doesNotExist());
        // the chairman sees the demo member as having access (linked by the sign-in just now, no row)
        http.card(chairman(), own).andExpect(jsonPath("$.access.status").value("ACTIVE"))
                .andExpect(jsonPath("$.access.inviteBlockedBy").doesNotExist());
    }

    // ---- the public set-password calls ---------------------------------------------------------------------------

    @Test
    void aLinkIsInspectedAndAcceptedOnceAndTheMemberThenSignsIn() throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        UUID member = invitee(email);
        http.invite(chairman(), member).andExpect(status().isOk());
        String token = InvitationRequests.tokenOfLetterTo(email);
        String subject = subjectOf(DEMO_TENANT, member);

        // invited: the account cannot sign in to the application yet, whatever Keycloak says
        http.me(TestTokens.bearer(DEMO_TENANT, subject, email)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_LINKED"));

        http.inspect(token).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        http.accept(token, PASSWORD).andExpect(status().isNoContent());

        FakeSignInAccounts.Account account = accounts.account(subject).orElseThrow();
        assertThat(account.password()).isEqualTo(PASSWORD);
        assertThat(account.emailVerified()).isTrue();
        assertThat(account.enabled()).isTrue();
        assertThat(account.requiredActions()).isEmpty();
        assertThat(state(member)).isEqualTo("ACTIVE");
        assertThat(invitationRows(member)).singleElement().satisfies(row -> assertThat(row.get("used_at")).isNotNull());
        http.me(TestTokens.bearer(DEMO_TENANT, subject, email)).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(member.toString()));
        http.card(chairman(), member).andExpect(jsonPath("$.access.status").value("ACTIVE"));

        // used: the same link is invalid
        http.inspect(token).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        http.accept(token, "another-password-1").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        assertThat(account.password()).isEqualTo(PASSWORD);
    }

    @Test
    void everyLinkThatCannotBeUsedIsTheSameInvalidAnswer() throws Exception {
        http.mailboxOnMailpit();
        http.inspect("no-such-token").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        http.accept("no-such-token", PASSWORD).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));

        // expired
        String expiredEmail = freshEmail();
        UUID expired = invitee(expiredEmail);
        http.invite(chairman(), expired).andExpect(status().isOk());
        String expiredToken = InvitationRequests.tokenOfLetterTo(expiredEmail);
        ownerJdbc().update("update member_invitation set expires_at = now() - interval '1 minute' "
                + "where member_id = ?", expired);
        http.inspect(expiredToken).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        http.accept(expiredToken, PASSWORD).andExpect(status().isNotFound());

        // the member was archived
        String archivedEmail = freshEmail();
        UUID archived = invitee(archivedEmail);
        http.invite(chairman(), archived).andExpect(status().isOk());
        String archivedToken = InvitationRequests.tokenOfLetterTo(archivedEmail);
        archiveMember(DEMO_TENANT, archived);
        http.inspect(archivedToken).andExpect(status().isNotFound());

        // the e-mail changed after the letter
        String changedEmail = freshEmail();
        UUID changed = invitee(changedEmail);
        http.invite(chairman(), changed).andExpect(status().isOk());
        String changedToken = InvitationRequests.tokenOfLetterTo(changedEmail);
        insertContacts(DEMO_TENANT, changed, freshEmail());
        http.inspect(changedToken).andExpect(status().isNotFound());

        // access turned off
        String disabledEmail = freshEmail();
        UUID disabled = invitee(disabledEmail);
        http.invite(chairman(), disabled).andExpect(status().isOk());
        String disabledToken = InvitationRequests.tokenOfLetterTo(disabledEmail);
        ownerJdbc().update("update member_access set state = 'DISABLED', disabled_at = now() where member_id = ?",
                disabled);
        http.accept(disabledToken, PASSWORD).andExpect(status().isNotFound());

        // the account was removed in Keycloak by hand: the link cannot set a password, and nothing changes
        String removedEmail = freshEmail();
        UUID removed = invitee(removedEmail);
        http.invite(chairman(), removed).andExpect(status().isOk());
        String removedToken = InvitationRequests.tokenOfLetterTo(removedEmail);
        accounts.remove(subjectOf(DEMO_TENANT, removed));
        http.accept(removedToken, PASSWORD).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        assertThat(state(removed)).isEqualTo("INVITED");

        // the account was moved to another cooperative by hand: the tenant guard refuses, the link is invalid
        String movedEmail = freshEmail();
        UUID moved = invitee(movedEmail);
        http.invite(chairman(), moved).andExpect(status().isOk());
        String movedToken = InvitationRequests.tokenOfLetterTo(movedEmail);
        accounts.reassign(subjectOf(DEMO_TENANT, moved), OTHER_TENANT);
        http.accept(movedToken, PASSWORD).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        assertThat(accounts.account(subjectOf(DEMO_TENANT, moved)).orElseThrow().password()).isNull();
    }

    @Test
    void thePasswordIsCheckedAtTheBoundary() throws Exception {
        http.accept("any-token", "short").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_SHORT"));
        http.accept("any-token", "x".repeat(129)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        http.inspect("").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("token"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
    }

    @Test
    void theSignInServiceDownKeepsTheLinkUsable() throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        UUID member = invitee(email);
        http.invite(chairman(), member).andExpect(status().isOk());
        String token = InvitationRequests.tokenOfLetterTo(email);

        accounts.unavailable(true);
        http.accept(token, PASSWORD).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SIGN_IN_SERVICE_UNAVAILABLE"));
        accounts.unavailable(false);

        http.accept(token, PASSWORD).andExpect(status().isNoContent());
        assertThat(state(member)).isEqualTo("ACTIVE");
    }

    // ---- sign-in -------------------------------------------------------------------------------------------------

    @Test
    void aMemberWhoseAccessIsOffCannotSignInAndCanBeInvitedAgain() throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        UUID member = invitee(email);
        String account = accounts.add(email, DEMO_TENANT, false);
        inTenant(DEMO_TENANT, sql -> {
            sql.update("update member set keycloak_subject = ? where id = ?", account, member);
            return sql.update("insert into member_access (member_id, tenant_id, state, disabled_at) "
                    + "values (?, ?, 'DISABLED', now())", member, DEMO_TENANT);
        });

        http.me(TestTokens.bearer(DEMO_TENANT, account, email)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_LINKED"));
        http.card(chairman(), member).andExpect(jsonPath("$.access.status").value("DISABLED"))
                .andExpect(jsonPath("$.access.disabledAt").exists());

        http.invite(chairman(), member).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INVITED"));
        assertThat(accounts.account(account).orElseThrow().enabled()).isTrue();
    }

    // ---- secrets -------------------------------------------------------------------------------------------------

    @Test
    void theLinkAndThePasswordAreNeverWrittenDown(CapturedOutput output) throws Exception {
        http.mailboxOnMailpit();
        String email = freshEmail();
        UUID member = invitee(email);
        http.invite(chairman(), member).andExpect(status().isOk());
        String token = InvitationRequests.tokenOfLetterTo(email);
        http.inspect(token).andExpect(status().isOk());
        http.accept(token, PASSWORD).andExpect(status().isNoContent());
        await().atMost(Duration.ofSeconds(10)).until(() -> !invitedAudit(member).isEmpty());

        String hashHex = HexFormat.of().formatHex(InvitationTokens.hash(token));
        assertThat(output.getAll()).doesNotContain(token).doesNotContain(PASSWORD).doesNotContain(email)
                .doesNotContain(hashHex);
        assertThat(ownerJdbc().queryForObject("select count(*) from audit_entry where cast(changes as text) like ? "
                + "or cast(changes as text) like ? or entity_label like ?", Integer.class,
                "%" + token + "%", "%" + PASSWORD + "%", "%" + token + "%")).isZero();
        assertThat(ownerJdbc().queryForObject("select count(*) from event_publication where serialized_event like ? "
                + "or serialized_event like ?", Integer.class, "%" + token + "%", "%" + PASSWORD + "%")).isZero();
        assertThat(ownerJdbc().queryForObject("select count(*) from member_invitation where member_id = ? "
                + "and token_hash = ?", Integer.class, member, token.getBytes())).as("the token is never stored")
                .isZero();
    }
}
