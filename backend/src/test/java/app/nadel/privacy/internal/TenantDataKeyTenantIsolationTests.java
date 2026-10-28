package app.nadel.privacy.internal;

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

/** tenant_data_key obeys RLS and the application role may only read and insert (plain SQL, no entities). */
class TenantDataKeyTenantIsolationTests extends IntegrationTest {

    static final UUID DEMO = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final String INSERT = "insert into tenant_data_key "
            + "(tenant_id, version, data_key_wrapped, index_key_wrapped, master_key_id) "
            + "values (?, ?, '\\x01'::bytea, '\\x02'::bytea, '\\x0102030405060708'::bytea)";

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    /**
     * The rows above carry a fake master key id. Left behind, they would make every later context on the shared
     * container fail its start-up key-check with PD_MASTER_KEY_MISMATCH, so they are removed as the owner.
     */
    @AfterEach
    void removeFakeKeys() {
        ownerJdbc().update("delete from tenant_data_key where master_key_id = '\\x0102030405060708'::bytea");
    }

    @Test
    void tenantSeesOnlyItsOwnRows() {
        as(DEMO, () -> tx.execute(s -> jdbc.update(INSERT, DEMO, 9)));
        as(OTHER, () -> tx.execute(s -> jdbc.update(INSERT, OTHER, 9)));

        List<UUID> demo = as(DEMO, () -> tx.execute(s ->
                jdbc.queryForList("select distinct tenant_id from tenant_data_key", UUID.class)));
        List<UUID> other = as(OTHER, () -> tx.execute(s ->
                jdbc.queryForList("select distinct tenant_id from tenant_data_key", UUID.class)));
        Integer none = tx.execute(s -> jdbc.queryForObject("select count(*) from tenant_data_key", Integer.class));

        assertThat(demo).containsExactly(DEMO);
        assertThat(other).containsExactly(OTHER);
        assertThat(none).isZero();
    }

    @Test
    void cannotInsertRowForAnotherTenant() {
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(INSERT, OTHER, 1))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void applicationRoleCannotUpdateOrDelete() {
        as(DEMO, () -> tx.execute(s -> jdbc.update(INSERT, DEMO, 8)));

        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                "update tenant_data_key set data_key_wrapped = '\\x03'::bytea where tenant_id = ?", DEMO))))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                "delete from tenant_data_key where tenant_id = ?", DEMO))))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void privilegesAreSelectAndInsertOnly() {
        assertThat(privilege("nadel_app", "SELECT")).isTrue();
        assertThat(privilege("nadel_app", "INSERT")).isTrue();
        assertThat(privilege("nadel_app", "UPDATE")).isFalse();
        assertThat(privilege("nadel_app", "DELETE")).isFalse();
        assertThat(privilege("nadel_app", "TRUNCATE")).isFalse();
    }

    @Test
    void masterKeyIdMustBeEightBytes() {
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(s -> jdbc.update(
                "insert into tenant_data_key (tenant_id, version, data_key_wrapped, index_key_wrapped, master_key_id) "
                        + "values (?, 7, '\\x01'::bytea, '\\x02'::bytea, '\\x0102'::bytea)", DEMO))))
                .isInstanceOf(DataAccessException.class);
    }

    private Boolean privilege(String role, String privilege) {
        return jdbc.queryForObject("select has_table_privilege(?, 'tenant_data_key', ?)", Boolean.class, role, privilege);
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
