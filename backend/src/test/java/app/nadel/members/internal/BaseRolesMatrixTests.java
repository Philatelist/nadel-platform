package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
import app.nadel.identity.Permission;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The base-role matrix in Java ({@link BaseRoles}) must equal what {@code seed_base_roles} writes for a brand new
 * cooperative. Fails loudly when a migration or the Java definition changes without the other.
 */
class BaseRolesMatrixTests extends IntegrationTest {

    @Test
    void javaDefinitionEqualsTheSeededMatrixOfAFreshCooperative() {
        JdbcTemplate owner = ownerJdbc();
        UUID tenant = UUID.randomUUID();
        owner.update("insert into tenant (id, name) values (?, 'Matrix Test Cooperative')", tenant);
        try {
            owner.queryForList("select seed_base_roles(?)", tenant);

            Map<String, String> seededNames = new HashMap<>();
            owner.queryForList("select base_code, name from role where tenant_id = ?", tenant)
                    .forEach(row -> seededNames.put((String) row.get("base_code"), (String) row.get("name")));
            Map<String, String> javaNames = new HashMap<>();
            BaseRoles.ALL.forEach(d -> javaNames.put(d.code(), d.name()));
            assertThat(seededNames)
                    .as("base-role names of seed_base_roles (database) vs BaseRoles (Java)")
                    .isEqualTo(javaNames);

            Map<String, EnumSet<Permission>> seeded = new HashMap<>();
            List<Map<String, Object>> rows = owner.queryForList(
                    "select r.base_code, rp.permission from role r left join role_permission rp on rp.role_id = r.id "
                            + "where r.tenant_id = ?", tenant);
            for (Map<String, Object> row : rows) {
                EnumSet<Permission> set = seeded.computeIfAbsent((String) row.get("base_code"),
                        k -> EnumSet.noneOf(Permission.class));
                if (row.get("permission") != null) {
                    set.add(Permission.valueOf((String) row.get("permission")));
                }
            }

            Map<String, EnumSet<Permission>> java = new HashMap<>();
            BaseRoles.ALL.forEach(d -> java.put(d.code(), EnumSet.copyOf(
                    d.permissions().isEmpty() ? EnumSet.noneOf(Permission.class) : d.permissions())));
            assertThat(seeded)
                    .as("base-role matrix of seed_base_roles (database) vs BaseRoles (Java)")
                    .isEqualTo(java);
        } finally {
            owner.update("delete from role_permission where tenant_id = ?", tenant);
            owner.update("delete from role where tenant_id = ?", tenant);
            owner.update("delete from tenant where id = ?", tenant);
        }
    }

    @Test
    void defaultNameReturnsTheSeedNameOfABaseRole() {
        assertThat(BaseRoles.defaultName("TREASURER")).isEqualTo("Treasurer");
        assertThatThrownBy(() -> BaseRoles.defaultName("SECRETARY")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chairmanHasEveryPermissionAndMemberNone() {
        assertThat(BaseRoles.ALL.get(0).permissions()).containsExactlyInAnyOrder(Permission.values());
        assertThat(BaseRoles.ALL).extracting(BaseRoles.Definition::code)
                .containsExactly("CHAIRMAN", "TREASURER", "MEMBER");
        assertThat(BaseRoles.ALL.get(2).permissions()).isEmpty();
    }
}
