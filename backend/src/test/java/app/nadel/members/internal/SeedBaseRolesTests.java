package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

/** seed_base_roles: permission matrix, deterministic ids, idempotency. */
class SeedBaseRolesTests extends IntegrationTest {

    static final UUID DEMO = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void matrixMatchesTheSpecification() {
        Map<String, TreeSet<String>> matrix = matrix();

        assertThat(matrix.get("CHAIRMAN")).containsExactlyInAnyOrder("MEMBERS_VIEW", "MEMBERS_EDIT", "ROLES_MANAGE",
                "AUDIT_VIEW", "PLOTS_VIEW", "PLOTS_EDIT", "OWNERSHIP_EDIT", "IMPORT_RUN",
                "SETTINGS_MANAGE", "TARIFFS_VIEW", "TARIFFS_MANAGE", "METERS_VIEW", "METERS_MANAGE",
                "READINGS_ENTER", "READINGS_APPROVE", "CHARGES_VIEW", "CHARGES_RUN", "CHARGES_REVERSE",
                "PERIODS_CLOSE", "RECALCULATION_RUN", "REPORTS_VIEW", "REPORT_TEMPLATES_MANAGE", "PAYMENTS_VIEW",
                "PAYMENTS_ENTER", "PAYMENTS_REVERSE");
        assertThat(matrix.get("TREASURER")).containsExactlyInAnyOrder("MEMBERS_VIEW", "PLOTS_VIEW", "AUDIT_VIEW",
                "TARIFFS_VIEW", "METERS_VIEW", "CHARGES_VIEW", "REPORTS_VIEW", "PAYMENTS_VIEW", "PAYMENTS_ENTER");
        assertThat(matrix.get("MEMBER")).isEmpty();
    }

    @Test
    void idsAreDeterministic() {
        UUID chairman = as(() -> jdbc.queryForObject("select id from role where base_code = 'CHAIRMAN'", UUID.class));
        UUID expected = as(() -> jdbc.queryForObject("select md5(?::text || ':CHAIRMAN')::uuid", UUID.class, DEMO));

        assertThat(chairman).isEqualTo(expected);
    }

    @Test
    void seededBaseRolesAreNotCustomised() {
        assertThat(as(() -> jdbc.queryForList(
                "select name_customized from role where base_code is not null", Boolean.class)))
                .hasSize(3).containsOnly(false);
        assertThat(as(() -> jdbc.queryForList(
                "select description_customized from role where base_code is not null", Boolean.class)))
                .hasSize(3).containsOnly(false);
    }

    /**
     * The condition of the V5 data fix: a base role counts as customised when its stored description differs from the
     * V2 seed text of its code (a cleared description included); custom roles always do.
     */
    @Test
    void migrationRuleFlagsBaseRolesWithAnEditedDescriptionOnly() {
        String rule = "select count(*) from role where id = ? and (base_code is null or description is distinct from "
                + "case base_code when 'CHAIRMAN' then 'Full access' "
                + "when 'TREASURER' then 'Read access to members, plots and audit' "
                + "when 'MEMBER' then 'Own data only' end)";
        UUID treasurer = as(() -> jdbc.queryForObject(
                "select id from role where base_code = 'TREASURER'", UUID.class));
        try {
            assertThat(as(() -> jdbc.queryForObject(rule, Integer.class, treasurer))).as("seed text").isZero();
            as(() -> jdbc.update("update role set description = 'Keeps the cash desk' where id = ?", treasurer));
            assertThat(as(() -> jdbc.queryForObject(rule, Integer.class, treasurer))).as("edited").isOne();
            as(() -> jdbc.update("update role set description = null where id = ?", treasurer));
            assertThat(as(() -> jdbc.queryForObject(rule, Integer.class, treasurer))).as("cleared").isOne();
            // the seed text of another code is an edit for this one
            as(() -> jdbc.update("update role set description = 'Own data only' where id = ?", treasurer));
            assertThat(as(() -> jdbc.queryForObject(rule, Integer.class, treasurer))).as("other code's text").isOne();
        } finally {
            as(() -> jdbc.update("update role set description = 'Read access to members, plots and audit' "
                    + "where id = ?", treasurer));
        }
    }

    @Test
    void secondRunChangesNothingAndKeepsCustomisations() {
        as(() -> jdbc.update("update role set name = 'Chair', name_customized = true, description = 'custom', "
                + "description_customized = true where base_code = 'CHAIRMAN'"));
        as(() -> jdbc.update("delete from role_permission where permission = 'AUDIT_VIEW' "
                + "and role_id = md5(?::text || ':TREASURER')::uuid", DEMO));
        Map<String, TreeSet<String>> before = matrix();

        as(() -> jdbc.queryForList("select seed_base_roles(?)", DEMO));
        as(() -> jdbc.queryForList("select seed_base_roles(?)", DEMO));

        // Seeding only adds missing rows: the deleted permission comes back, customised names stay.
        assertThat(matrix().get("TREASURER")).containsExactlyInAnyOrder("MEMBERS_VIEW", "PLOTS_VIEW", "AUDIT_VIEW",
                "TARIFFS_VIEW", "METERS_VIEW", "CHARGES_VIEW", "REPORTS_VIEW", "PAYMENTS_VIEW", "PAYMENTS_ENTER");
        assertThat(matrix().get("CHAIRMAN")).isEqualTo(before.get("CHAIRMAN"));
        assertThat(as(() -> jdbc.queryForObject("select name from role where base_code = 'CHAIRMAN'", String.class)))
                .isEqualTo("Chair");
        assertThat(as(() -> jdbc.queryForObject(
                "select name_customized from role where base_code = 'CHAIRMAN'", Boolean.class))).isTrue();
        assertThat(as(() -> jdbc.queryForObject(
                "select description_customized from role where base_code = 'CHAIRMAN'", Boolean.class))).isTrue();
        assertThat(as(() -> jdbc.queryForObject(
                "select description from role where base_code = 'CHAIRMAN'", String.class))).isEqualTo("custom");
        assertThat(as(() -> jdbc.queryForObject("select count(*) from role where base_code is not null", Integer.class)))
                .isEqualTo(3);

        // restore the demo state for other tests
        as(() -> jdbc.update("update role set name = 'Chairman', name_customized = false, description = 'Full access', "
                + "description_customized = false where base_code = 'CHAIRMAN'"));
    }

    private Map<String, TreeSet<String>> matrix() {
        List<Map<String, Object>> rows = as(() -> jdbc.queryForList(
                "select r.base_code, rp.permission from role r left join role_permission rp on rp.role_id = r.id "
                        + "where r.base_code is not null"));
        Map<String, TreeSet<String>> result = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            TreeSet<String> set = result.computeIfAbsent((String) row.get("base_code"), k -> new TreeSet<>());
            if (row.get("permission") != null) {
                set.add((String) row.get("permission"));
            }
        }
        return result;
    }

    private <T> T as(Supplier<T> action) {
        Jwt jwt = Jwt.withTokenValue("test")
                .header("alg", "none")
                .subject("test-user")
                .claim("tenant_id", DEMO.toString())
                .claim("email", "test@demo.local")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            return tx.execute(s -> action.get());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
