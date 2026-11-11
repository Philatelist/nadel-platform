package app.nadel.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.DemoLedgerData;
import app.nadel.IntegrationTest;
import app.nadel.tenancy.TenantProvisioning;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link TenantProvisioning} (feature 020): a new cooperative with its seed under RLS as the application role; an
 * existing one left exactly as it is (the name, and the roles: no re-seed that would give back a permission the board
 * removed).
 */
class TenantProvisioningTests extends IntegrationTest {

    @Autowired
    TenantProvisioning provisioning;

    private final UUID tenant = UUID.randomUUID();

    @AfterEach
    void removeCooperative() {
        JdbcTemplate owner = ownerJdbc();
        DemoLedgerData.asLedgerOwner(owner, sql -> {
            sql.update("delete from money_account where tenant_id = ?", tenant);
            sql.update("delete from expense_category where tenant_id = ?", tenant);
        });
        for (String table : List.of("role_permission", "role", "charge_type")) {
            owner.update("delete from " + table + " where tenant_id = ?", tenant);
        }
        owner.update("delete from tenant where id = ?", tenant);
    }

    @Test
    void aNewCooperativeIsCreatedWithItsSeed() {
        assertThat(provisioning.ensure(tenant, "  СТ «Новий»  ")).isTrue();

        assertThat(ownerJdbc().queryForObject("select name from tenant where id = ?", String.class, tenant))
                .isEqualTo("СТ «Новий»");
        assertThat(ownerJdbc().queryForList("select base_code from role where tenant_id = ? order by base_code",
                String.class, tenant)).containsExactly("CHAIRMAN", "MEMBER", "TREASURER");
        assertThat(ownerJdbc().queryForObject("select count(*) from charge_type where tenant_id = ?", Integer.class,
                tenant)).isPositive();
        assertThat(ownerJdbc().queryForObject("select count(*) from money_account where tenant_id = ?", Integer.class,
                tenant)).isEqualTo(2);
    }

    @Test
    void anExistingCooperativeIsLeftAsItIs() {
        provisioning.ensure(tenant, "СТ «Новий»");
        ownerJdbc().update("delete from role_permission where tenant_id = ? and permission = 'CASHBOOK_ENTER' "
                + "and role_id in (select id from role where tenant_id = ? and base_code = 'TREASURER')", tenant, tenant);

        assertThat(provisioning.ensure(tenant, "Інша назва")).isFalse();

        assertThat(ownerJdbc().queryForObject("select name from tenant where id = ?", String.class, tenant))
                .isEqualTo("СТ «Новий»");
        assertThat(ownerJdbc().queryForObject("select count(*) from role_permission rp join role r on r.id = rp.role_id "
                + "where r.tenant_id = ? and r.base_code = 'TREASURER' and rp.permission = 'CASHBOOK_ENTER'",
                Integer.class, tenant)).as("a permission the board removed stays removed").isZero();
    }

    @Test
    void aCooperativeNeedsAName() {
        assertThatThrownBy(() -> provisioning.ensure(tenant, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(ownerJdbc().queryForObject("select count(*) from tenant where id = ?", Integer.class, tenant))
                .isZero();
    }
}
