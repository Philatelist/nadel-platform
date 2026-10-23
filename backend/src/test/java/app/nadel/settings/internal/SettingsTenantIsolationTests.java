package app.nadel.settings.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

/** Cooperatives can neither see nor change each other's settings (plain SQL as the application role). */
class SettingsTenantIsolationTests extends IntegrationTest {

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seedRows() {
        clean();
        ownerJdbc().update("insert into cooperative_settings (tenant_id, member_plot_status_visibility) values (?, 'ALL_PLOTS')",
                DEMO_TENANT);
        ownerJdbc().update("insert into cooperative_settings (tenant_id, member_plot_status_visibility) values (?, 'OWN_ONLY')",
                OTHER_TENANT);
    }

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        ownerJdbc().update("delete from cooperative_settings where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
    }

    @Test
    void tenantSeesOnlyItsOwnRow() {
        List<UUID> demo = as(DEMO_TENANT, () -> tx.execute(s ->
                jdbc.queryForList("select tenant_id from cooperative_settings", UUID.class)));
        List<UUID> other = as(OTHER_TENANT, () -> tx.execute(s ->
                jdbc.queryForList("select tenant_id from cooperative_settings", UUID.class)));

        assertThat(demo).containsExactly(DEMO_TENANT);
        assertThat(other).containsExactly(OTHER_TENANT);
    }

    @Test
    void withoutTenantNothingIsVisible() {
        Integer count = tx.execute(s -> jdbc.queryForObject("select count(*) from cooperative_settings", Integer.class));

        assertThat(count).isZero();
    }

    @Test
    void cannotInsertARowForAnotherTenant() {
        ownerJdbc().update("delete from cooperative_settings where tenant_id = ?", OTHER_TENANT);

        assertThatThrownBy(() -> as(DEMO_TENANT, () -> tx.execute(s -> jdbc.update(
                "insert into cooperative_settings (tenant_id, member_plot_status_visibility) values (?, 'ALL_PLOTS')",
                OTHER_TENANT)))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void cannotUpsertTheRowOfAnotherTenant() {
        assertThatThrownBy(() -> as(DEMO_TENANT, () -> tx.execute(s -> jdbc.update(
                "insert into cooperative_settings (tenant_id, member_plot_status_visibility) values (?, 'ALL_PLOTS') "
                        + "on conflict (tenant_id) do update set member_plot_status_visibility = 'ALL_PLOTS'",
                OTHER_TENANT)))).isInstanceOf(DataAccessException.class);
        assertThat(otherValue()).isEqualTo("OWN_ONLY");
    }

    @Test
    void cannotUpdateOrDeleteTheRowOfAnotherTenant() {
        Integer updated = as(DEMO_TENANT, () -> tx.execute(s -> jdbc.update(
                "update cooperative_settings set member_plot_status_visibility = 'ALL_PLOTS' where tenant_id = ?",
                OTHER_TENANT)));
        Integer deleted = as(DEMO_TENANT, () -> tx.execute(s -> jdbc.update(
                "delete from cooperative_settings where tenant_id = ?", OTHER_TENANT)));

        assertThat(updated).isZero();
        assertThat(deleted).isZero();
        assertThat(otherValue()).isEqualTo("OWN_ONLY");
    }

    @Test
    void cannotMoveARowToAnotherTenant() {
        assertThatThrownBy(() -> as(DEMO_TENANT, () -> tx.execute(s -> jdbc.update(
                "update cooperative_settings set tenant_id = ? where tenant_id = ?", OTHER_TENANT, DEMO_TENANT))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void anUnknownValueIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> as(DEMO_TENANT, () -> tx.execute(s -> jdbc.update(
                "update cooperative_settings set member_plot_status_visibility = 'EVERYONE'"))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void theLedgerSettingsHaveDefaultsAndTheirChecks() {
        assertThat(ownerJdbc().queryForMap("select losses_mode, losses_percent, common_consumer_default_split, "
                + "red_amount, red_days from cooperative_settings where tenant_id = ?", DEMO_TENANT))
                .containsEntry("losses_mode", "FIXED_PERCENT")
                .containsEntry("losses_percent", new BigDecimal("0.00"))
                .containsEntry("common_consumer_default_split", "CONNECTED_PLOTS")
                .containsEntry("red_amount", new BigDecimal("1000.00"))
                .containsEntry("red_days", 90);
        for (String invalid : List.of("losses_mode = 'SOMETIMES'", "losses_percent = 100.01",
                "losses_percent = -1", "common_consumer_default_split = 'BY_AREA'", "red_amount = -0.01",
                "red_days = -1")) {
            assertThatThrownBy(() -> as(DEMO_TENANT, () -> tx.execute(s -> jdbc.update(
                    "update cooperative_settings set " + invalid))))
                    .as(invalid)
                    .isInstanceOf(DataAccessException.class);
        }
    }

    private String otherValue() {
        return ownerJdbc().queryForObject(
                "select member_plot_status_visibility from cooperative_settings where tenant_id = ?", String.class,
                OTHER_TENANT);
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
