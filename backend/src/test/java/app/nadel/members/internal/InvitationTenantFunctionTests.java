package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * members_invitation_tenant(bytea) (V36): the cooperative of an invitation by its token hash, called by the public
 * accept endpoint as the application role with no cooperative bound.
 */
class InvitationTenantFunctionTests extends IntegrationTest {

    static final UUID DEMO = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID DEMO_MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
    static final UUID OTHER_MEMBER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

    @Autowired
    JdbcTemplate app;

    @BeforeEach
    void createInvitations() {
        clean();
        insert(DEMO, DEMO_MEMBER, "demo-token");
        insert(OTHER, OTHER_MEMBER, "other-token");
    }

    @AfterEach
    void clean() {
        ownerJdbc().update("delete from member_invitation where member_id in (?, ?)", DEMO_MEMBER, OTHER_MEMBER);
    }

    @Test
    void returnsTheCooperativeOfTheTokenHashWithoutABoundCooperative() {
        // the application role, nothing bound: the table shows nothing, the function finds the cooperative
        assertThat(app.queryForObject("select count(*) from member_invitation", Integer.class)).isZero();
        assertThat(app.queryForObject("select members_invitation_tenant(sha256(?::bytea))", UUID.class,
                "demo-token".getBytes())).isEqualTo(DEMO);
        assertThat(app.queryForObject("select members_invitation_tenant(sha256(?::bytea))", UUID.class,
                "other-token".getBytes())).isEqualTo(OTHER);
    }

    @Test
    void returnsNullForAnUnknownHash() {
        assertThat(app.queryForObject("select members_invitation_tenant(sha256(?::bytea))", UUID.class,
                "no-such-token".getBytes())).isNull();
    }

    @Test
    void returnsOnlyAUuid() {
        app.query("select members_invitation_tenant(sha256('demo-token'::bytea))", rs -> {
            assertThat(rs.getMetaData().getColumnCount()).isEqualTo(1);
            assertThat(rs.getMetaData().getColumnTypeName(1)).isEqualTo("uuid");
        });
    }

    @Test
    void isSecurityDefinerStableWithAFixedSearchPath() {
        List<String> flags = ownerJdbc().queryForList("select prosecdef::text || '|' || provolatile || '|' "
                + "|| array_to_string(proconfig, ',') from pg_proc where proname = 'members_invitation_tenant'",
                String.class);

        assertThat(flags).hasSize(1);
        String[] parts = flags.getFirst().split("\\|");
        assertThat(parts[0]).isEqualTo("true");
        assertThat(parts[1]).isEqualTo("s");
        assertThat(parts[2]).isEqualTo("search_path=pg_catalog, public, pg_temp");
    }

    @Test
    void applicationRoleCanExecuteAndPublicCannot() {
        assertThat(ownerJdbc().queryForObject(
                "select has_function_privilege('nadel_app', 'members_invitation_tenant(bytea)', 'EXECUTE')",
                Boolean.class)).isTrue();
        assertThat(ownerJdbc().queryForObject(
                "select proacl::text from pg_proc where proname = 'members_invitation_tenant'", String.class))
                .contains("nadel_app=X/").doesNotContain("{=X").doesNotContain(",=X");
    }

    private void insert(UUID tenant, UUID member, String token) {
        ownerJdbc().update("insert into member_invitation (tenant_id, member_id, token_hash, email_bidx, expires_at, "
                + "sent_at) values (?, ?, sha256(?::bytea), sha256('e'::bytea), now() + interval '1 day', now())",
                tenant, member, token.getBytes());
    }
}
