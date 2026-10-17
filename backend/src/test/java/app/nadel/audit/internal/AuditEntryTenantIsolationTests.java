package app.nadel.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
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

/** The audit log obeys RLS (plain SQL, no entities). */
class AuditEntryTenantIsolationTests extends IntegrationTest {

    static final UUID DEMO = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
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
    void tenantSeesOnlyItsOwnEntries() {
        UUID demoEntry = UUID.randomUUID();
        UUID otherEntry = UUID.randomUUID();
        as(DEMO, () -> tx.execute(s -> jdbc.update(INSERT, demoEntry, DEMO, UUID.randomUUID())));
        as(OTHER, () -> tx.execute(s -> jdbc.update(INSERT, otherEntry, OTHER, UUID.randomUUID())));

        List<UUID> demoSees = as(DEMO, () -> tx.execute(s -> jdbc.queryForList("select id from audit_entry", UUID.class)));
        List<UUID> otherSees = as(OTHER, () -> tx.execute(s -> jdbc.queryForList("select id from audit_entry", UUID.class)));

        assertThat(demoSees).contains(demoEntry).doesNotContain(otherEntry);
        assertThat(otherSees).contains(otherEntry).doesNotContain(demoEntry);
    }

    @Test
    void withoutTenantNothingIsVisible() {
        as(DEMO, () -> tx.execute(s -> jdbc.update(INSERT, UUID.randomUUID(), DEMO, UUID.randomUUID())));

        Integer count = tx.execute(s -> jdbc.queryForObject("select count(*) from audit_entry", Integer.class));

        assertThat(count).isZero();
    }

    @Test
    void cannotInsertEntryForAnotherTenant() {
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s ->
                jdbc.update(INSERT, UUID.randomUUID(), OTHER, UUID.randomUUID()))))
                .isInstanceOf(DataAccessException.class);
    }

    private static <T> T as(UUID tenant, Supplier<T> action) {
        Jwt jwt = Jwt.withTokenValue("test")
                .header("alg", "none")
                .subject("test-user")
                .claim("tenant_id", tenant.toString())
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
