package app.nadel.plots.internal;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fixtures for tests that change ownership: plots created for the test (with a status row), owners seeded as the
 * database owner, a chairman token, and a cleanup that removes everything the test wrote. The application role cannot
 * delete ownership or audit rows, the owner role can.
 */
abstract class OwnershipTestSupport extends IntegrationTest {

    protected static final String PLOTS = "/api/v1/plots";

    private final List<UUID> createdPlots = new ArrayList<>();
    private final List<UUID> touchedDemoPlots = new ArrayList<>();
    private final List<UUID> roles = new ArrayList<>();

    @AfterEach
    void cleanUpOwnershipFixtures() {
        JdbcTemplate owner = ownerJdbc();
        for (UUID plot : createdPlots) {
            owner.update("delete from ownership where plot_id = ?", plot);
            owner.update("delete from audit_entry where entity_id = ?", plot);
            owner.update("delete from plot_status where plot_id = ?", plot);
            owner.update("delete from plot where id = ?", plot);
        }
        createdPlots.clear();
        for (UUID plot : touchedDemoPlots) {
            owner.update("delete from audit_entry where entity_id = ?", plot);
        }
        touchedDemoPlots.clear();
        restoreDemoPlot("9", "GREEN");
        restoreDemoPlot("25", "GREY");
        for (UUID role : roles) {
            inTenant(DEMO_TENANT, sql -> {
                sql.update("delete from member_role where role_id = ?", role);
                sql.update("delete from role_permission where role_id = ?", role);
                return sql.update("delete from role where id = ?", role);
            });
        }
        roles.clear();
    }

    /**
     * Puts a demo plot back as the demo data left it: the rows the test added are removed, the demo rows (ids
     * {@code dddddddd-...}) are reopened, and the status is the demo one.
     */
    private void restoreDemoPlot(String number, String status) {
        JdbcTemplate owner = ownerJdbc();
        UUID plot = owner.queryForObject("select id from plot where tenant_id = ? and number = ?", UUID.class,
                DEMO_TENANT, number);
        owner.update("delete from ownership where plot_id = ? and id::text not like 'dddddddd-%'", plot);
        if ("9".equals(number)) {
            owner.update("update ownership set valid_to = null, updated_at = created_at where id = ?::uuid "
                    + "and valid_to is not null", "dddddddd-0000-0000-0000-000000000012");
        }
        owner.update("update plot_status set status = ? where plot_id = ?", status, plot);
        owner.update("delete from audit_entry where entity_id = ?", plot);
    }

    protected UUID demoPlot(String number) {
        UUID plot = ownerJdbc().queryForObject("select id from plot where tenant_id = ? and number = ?", UUID.class,
                DEMO_TENANT, number);
        touchedDemoPlots.add(plot);
        return plot;
    }

    /** A new active plot of the demo cooperative with a GREY status row. */
    protected UUID newPlot(String kind) {
        UUID id = UUID.randomUUID();
        String number = "T" + id.toString().substring(0, 8);
        JdbcTemplate owner = ownerJdbc();
        owner.update("insert into plot (id, tenant_id, number, kind, area_m2) values (?, ?, ?, ?, ?)",
                id, DEMO_TENANT, number, kind, "COMMON".equals(kind) ? 0 : 600);
        owner.update("insert into plot_status (plot_id, tenant_id, status, debt) values (?, ?, 'GREY', 0)", id,
                DEMO_TENANT);
        createdPlots.add(id);
        return id;
    }

    protected UUID newPlot() {
        return newPlot("PLOT");
    }

    protected void archivePlot(UUID plot) {
        ownerJdbc().update("update plot set archived_at = now() where id = ?", plot);
    }

    /** Seeds an owner row as the database owner; the plot becomes GREEN when the row is current. */
    protected UUID seedOwner(UUID plot, UUID member, String share, String from, String to) {
        UUID id = UUID.randomUUID();
        String[] fraction = share.split("/");
        ownerJdbc().update("insert into ownership (id, tenant_id, plot_id, member_id, share_numerator, "
                + "share_denominator, valid_from, valid_to, basis) values (?, ?, ?, ?, ?, ?, ?::date, ?::date, ?)",
                id, DEMO_TENANT, plot, member, Integer.parseInt(fraction[0]), Integer.parseInt(fraction[1]), from, to,
                "seeded");
        if (to == null) {
            ownerJdbc().update("update plot_status set status = 'GREEN' where plot_id = ?", plot);
        }
        return id;
    }

    protected UUID demoMember(String last, String first) {
        return newMember(DEMO_TENANT, last, first, null, last.toLowerCase() + "-" + UUID.randomUUID() + "@example.com");
    }

    protected String chairman() {
        return bearerWithBaseRole("CHAIRMAN");
    }

    protected String treasurer() {
        return bearerWithBaseRole("TREASURER");
    }

    protected String member() {
        return bearerWithBaseRole("MEMBER");
    }

    protected String bearerWithBaseRole(String baseCode) {
        String email = "chg-" + baseCode.toLowerCase() + "-" + UUID.randomUUID() + "@demo.local";
        UUID member = newMember(DEMO_TENANT, email);
        giveBaseRole(DEMO_TENANT, member, baseCode);
        return TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);
    }

    /** A token of a fresh member whose only role is a custom role with exactly these permissions. */
    protected String customRoleToken(String... permissions) {
        String email = "chg-custom-" + UUID.randomUUID() + "@example.com";
        UUID member = newMember(DEMO_TENANT, email);
        UUID role = UUID.randomUUID();
        roles.add(role);
        inTenant(DEMO_TENANT, sql -> {
            sql.update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, DEMO_TENANT, "Custom " + role);
            for (String permission : permissions) {
                sql.update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, ?)",
                        DEMO_TENANT, role, permission);
            }
            return sql.update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)",
                    DEMO_TENANT, member, role);
        });
        return TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);
    }

    /** All ownership rows of a plot as the database owner sees them, oldest first. */
    protected List<Map<String, Object>> ownershipRows(UUID plot) {
        return ownerJdbc().queryForList("select member_id, share_numerator || '/' || share_denominator as share, "
                + "valid_from::text as valid_from, valid_to::text as valid_to, tenant_id from ownership "
                + "where plot_id = ? order by valid_from, member_id", plot);
    }

    protected String statusOf(UUID plot) {
        return ownerJdbc().queryForObject("select status from plot_status where plot_id = ?", String.class, plot);
    }
}
