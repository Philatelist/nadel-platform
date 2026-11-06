package app.nadel.imports.internal;

import static app.nadel.imports.internal.ImportWorkbooks.member;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.identity.FakeSignInAccounts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Excel import and the sign-in login (feature 020, owner decision on PR 71: an e-mail change through the import
 * follows the login rules of the member form). The import never changes the login of an existing member: a row with
 * an e-mail matches only the member who has that e-mail (case ignored), and a blank e-mail keeps the stored one, so the
 * e-mail blind index of a matched member, which is the login and the key of the invitation link, cannot change. The
 * member form asks Keycloak only when that index changes ({@code MemberLoginGuard}); so the import, like the form for
 * the same edits, needs no Keycloak call and leaves the access and the open link as they were. The tests prove it with
 * the sign-in service unavailable: any Keycloak call would fail the commit.
 */
class ImportMemberLoginTests extends ImportCommitSupport {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    FakeSignInAccounts accounts;

    private static final String PHONE = "+380671234567";

    /** An imported member who was then invited: linked to a sign-in account, {@code INVITED}, one open link. */
    private record Invited(UUID id, String email, String account, String token) {
    }

    @AfterEach
    void removeAccessRows() {
        accounts.reset();
        // the access and link rows refer to the imported members, which the base class removes after this
        ownerJdbc().update("delete from member_invitation where tenant_id = ? and member_id in "
                + "(select id from member where last_name like ?)", DEMO_TENANT, "Fixt" + tag + "%");
        ownerJdbc().update("delete from member_access where tenant_id = ? and member_id in "
                + "(select id from member where last_name like ?)", DEMO_TENANT, "Fixt" + tag + "%");
    }

    private Invited invited() throws Exception {
        String email = email("invitee");
        String id = preview(workbook(rows(), rows(member("M-1", "Fixt" + tag + "Invitee", "Person", null, PHONE,
                email, null, null, null, null)), rows())).get("id").asString();
        commit(id).andExpect(status().isOk());
        UUID member = memberId(id, "M-1");

        String account = accounts.add(email, DEMO_TENANT, true);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        byte[] tokenHash = sha256(token);
        inTenant(DEMO_TENANT, sql -> {
            sql.update("update member set keycloak_subject = ? where id = ?", account, member);
            sql.update("insert into member_access (member_id, tenant_id, state, invited_at) values (?, ?, 'INVITED', ?)",
                    member, DEMO_TENANT, now);
            return sql.update("""
                    insert into member_invitation (tenant_id, member_id, token_hash, email_bidx, created_at, expires_at,
                        sent_at)
                    select tenant_id, id, ?, email_bidx, ?, ?, ? from member where id = ?
                    """, tokenHash, now, now.plusHours(72), now, member);
        });
        return new Invited(member, email, account, token);
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static byte[] sha256(String token) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    }

    private String state(UUID member) {
        return ownerJdbc().queryForObject("select state from member_access where member_id = ?", String.class, member);
    }

    private int openLinks(UUID member) {
        return ownerJdbc().queryForObject("select count(*) from member_invitation where member_id = ? "
                + "and sent_at is not null and used_at is null and revoked_at is null", Integer.class, member);
    }

    /** The login, the access and the link of the invited member are as they were, and Keycloak was not touched. */
    private void assertLoginUntouched(Invited invited) throws Exception {
        assertThat(state(invited.id())).isEqualTo("INVITED");
        assertThat(openLinks(invited.id())).isEqualTo(1);
        FakeSignInAccounts.Account account = accounts.account(invited.account()).orElseThrow();
        assertThat(account.login()).isEqualTo(invited.email());
        assertThat(account.logouts()).isZero();
        mvc.perform(post("/api/v1/public/invitations/inspect").contentType(MediaType.APPLICATION_JSON)
                        .content(JsonMapper.shared().writeValueAsString(Map.of("token", invited.token()))))
                .andExpect(status().isOk());
    }

    @Test
    void aRowWithTheNameAndPhoneOfAnInvitedMemberAndANewEmailDoesNotMoveTheirLogin() throws Exception {
        Invited invited = invited();
        String newEmail = email("moved");

        accounts.unavailable(true);
        String id = preview(workbook(rows(), rows(member("M-2", "Fixt" + tag + "Invitee", "Person", null, PHONE,
                newEmail, null, null, null, null)), rows())).get("id").asString();
        commit(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.members.created").value(1))
                .andExpect(jsonPath("$.counts.members.updated").value(0));
        accounts.unavailable(false);

        // the row is another member: the invited member keeps the e-mail, which is the login
        assertThat(decryptedContacts(DEMO_TENANT, invited.id()).email()).isEqualTo(invited.email());
        assertLoginUntouched(invited);
        UUID other = memberId(id, "M-2");
        assertThat(decryptedContacts(DEMO_TENANT, other).email()).isEqualTo(newEmail);
        assertThat(ownerJdbc().queryForList("select state from member_access where member_id = ?", String.class,
                other)).isEmpty();
        assertThat(accounts.byLogin(newEmail)).isEmpty();
    }

    @Test
    void aRowWithoutAnEmailUpdatesAnInvitedMemberAndKeepsTheLogin() throws Exception {
        Invited invited = invited();

        accounts.unavailable(true);
        String id = preview(workbook(rows(), rows(member("M-1", "Fixt" + tag + "Invitee", "Person", null, PHONE,
                null, "Garden St 5", null, null, null)), rows())).get("id").asString();
        commit(id).andExpect(status().isOk()).andExpect(jsonPath("$.counts.members.updated").value(1));
        accounts.unavailable(false);

        assertThat(decryptedContacts(DEMO_TENANT, invited.id()).email()).isEqualTo(invited.email());
        assertThat(decryptedContacts(DEMO_TENANT, invited.id()).postalAddress()).isEqualTo("Garden St 5");
        assertLoginUntouched(invited);
    }

    @Test
    void anEmailThatDiffersInLetterCaseOnlyIsTheSameLoginAndChangesNothing() throws Exception {
        Invited invited = invited();

        accounts.unavailable(true);
        JsonNode preview = preview(workbook(rows(), rows(member("M-1", "Fixt" + tag + "Invitee", "Person", null, PHONE,
                invited.email().toUpperCase(Locale.ROOT), null, null, null, null)), rows()));
        assertThat(preview.at("/counts/members/unchanged").asInt()).isEqualTo(1);
        commit(preview.get("id").asString()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_NOTHING_TO_COMMIT"));
        accounts.unavailable(false);

        assertThat(decryptedContacts(DEMO_TENANT, invited.id()).email()).isEqualTo(invited.email());
        assertLoginUntouched(invited);
    }

    private static List<List<?>> rows(List<?>... rows) {
        return List.of(rows);
    }
}
