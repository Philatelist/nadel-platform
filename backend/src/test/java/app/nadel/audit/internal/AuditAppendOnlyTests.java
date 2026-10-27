package app.nadel.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

/** The application role can only read and insert audit entries; the database refuses the rest. */
class AuditAppendOnlyTests extends IntegrationTest {

    static final UUID DEMO = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final String INSERT = "insert into audit_entry (id, tenant_id, occurred_at, action, entity_type, entity_id)"
            + " values (?, ?, now(), 'MEMBER_CREATED', 'MEMBER', ?)";

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void insertSucceeds() {
        UUID id = UUID.randomUUID();

        Integer inserted = asDemo(() -> tx.execute(s -> jdbc.update(INSERT, id, DEMO, UUID.randomUUID())));

        assertThat(inserted).isEqualTo(1);
    }

    @Test
    void updateIsDeniedByPrivileges() {
        UUID id = insertEntry();

        assertThatThrownBy(() -> asDemo(() -> tx.execute(s ->
                jdbc.update("update audit_entry set action = 'TAMPERED' where id = ?", id))))
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) rootCause(e)).getSQLState()).isEqualTo("42501"));
    }

    @Test
    void sealedValuesCannotBeRewrittenByTheApplication() {
        // only the schema owner may rewrite the form of stored values (Java migration 24, ADR 0016)
        UUID id = insertEntry();

        assertThatThrownBy(() -> asDemo(() -> tx.execute(s -> jdbc.update(
                "update audit_entry set changes = cast(? as jsonb) where id = ?",
                "[{\"field\": \"email\", \"old\": null, \"new\": {\"enc\": \"AQ==\"}}]", id))))
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) rootCause(e)).getSQLState()).isEqualTo("42501"));
    }

    @Test
    void deleteIsDeniedByPrivileges() {
        UUID id = insertEntry();

        assertThatThrownBy(() -> asDemo(() -> tx.execute(s ->
                jdbc.update("delete from audit_entry where id = ?", id))))
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) rootCause(e)).getSQLState()).isEqualTo("42501"));
    }

    @Test
    void applicationRoleHoldsOnlySelectAndInsert() {
        List<String> privileges = jdbc.queryForList(
                "select privilege_type from information_schema.role_table_grants"
                        + " where grantee = 'nadel_app' and table_name = 'audit_entry' order by privilege_type",
                String.class);

        assertThat(privileges).containsExactly("INSERT", "SELECT");
    }

    private UUID insertEntry() {
        UUID id = UUID.randomUUID();
        asDemo(() -> tx.execute(s -> jdbc.update(INSERT, id, DEMO, UUID.randomUUID())));
        return id;
    }

    private static Throwable rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static <T> T asDemo(Supplier<T> action) {
        Jwt jwt = Jwt.withTokenValue("test")
                .header("alg", "none")
                .subject("test-user")
                .claim("tenant_id", DEMO.toString())
                .claim("email", "test@demo.local")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
