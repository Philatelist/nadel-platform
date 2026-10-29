package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * notifications_bot_tenants() (V29): the cooperatives that have a bot token, listed for the poller, which runs as the
 * application role with no cooperative bound.
 */
class BotTenantsFunctionTests extends NotificationsTestSupport {

    @Autowired
    JdbcTemplate app;

    @Test
    void returnsExactlyTheCooperativesWithABotToken() {
        settings(DEMO_TENANT, true);
        settings(OTHER_TENANT, false);

        // the application role, no cooperative bound: the table shows nothing, the function lists the bot
        assertThat(app.queryForObject("select count(*) from delivery_settings", Integer.class)).isZero();
        assertThat(app.queryForList("select * from notifications_bot_tenants()", UUID.class))
                .containsExactly(DEMO_TENANT);

        ownerJdbc().update("update delivery_settings set bot_token_enc = '\\x01'::bytea, bot_token_last4 = '9999' where tenant_id = ?", OTHER_TENANT);
        assertThat(app.queryForList("select * from notifications_bot_tenants()", UUID.class))
                .containsExactlyInAnyOrder(DEMO_TENANT, OTHER_TENANT);
    }

    @Test
    void returnsNothingWhenNoCooperativeHasABot() {
        settings(DEMO_TENANT, false);

        assertThat(app.queryForList("select * from notifications_bot_tenants()", UUID.class)).isEmpty();
    }

    @Test
    void returnsOnlyAUuidColumn() {
        settings(DEMO_TENANT, true);

        app.query("select * from notifications_bot_tenants()", rs -> {
            assertThat(rs.getMetaData().getColumnCount()).isEqualTo(1);
            assertThat(rs.getMetaData().getColumnType(1)).isEqualTo(Types.OTHER);
            assertThat(rs.getMetaData().getColumnTypeName(1)).isEqualTo("uuid");
        });
    }

    @Test
    void isSecurityDefinerWithAFixedSearchPathAndNotCallableByPublic() {
        List<String> flags = ownerJdbc().queryForList("select prosecdef::text || '|' || array_to_string(proconfig, ',') "
                + "|| '|' || coalesce(proacl::text, '') from pg_proc where proname = 'notifications_bot_tenants'",
                String.class);

        assertThat(flags).hasSize(1);
        String[] parts = flags.getFirst().split("\\|");
        assertThat(parts[0]).isEqualTo("true");
        assertThat(parts[1]).isEqualTo("search_path=pg_catalog, public");
        assertThat(parts[2]).contains("nadel_app=X/").doesNotContain("{=X");
        assertThat(ownerJdbc().queryForObject(
                "select has_function_privilege('nadel_app', 'notifications_bot_tenants()', 'EXECUTE')", Boolean.class))
                .isTrue();
    }
}
