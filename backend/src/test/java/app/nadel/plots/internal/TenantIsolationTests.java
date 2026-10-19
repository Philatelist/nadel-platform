package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import nadel.contract.model.PlotFeature;
import nadel.contract.model.PlotFeatureCollection;
import nadel.contract.model.PlotStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

/** Cooperatives can neither see nor change each other's data. */
class TenantIsolationTests extends IntegrationTest {

    static final UUID DEMO = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    PlotQueries queries;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tenantSeesOnlyItsOwnPlots() {
        PlotFeatureCollection demo = as(DEMO, () -> queries.map(viewer(DEMO)));
        PlotFeatureCollection other = as(OTHER, () -> queries.map(viewer(OTHER)));

        assertThat(demo.getFeatures()).hasSize(31); // 30 plots + the road
        assertThat(other.getFeatures()).hasSize(2);
        assertThat(demo.getFeatures()).extracting(PlotFeature::getId)
                .doesNotContainAnyElementsOf(other.getFeatures().stream().map(PlotFeature::getId).toList());
    }

    @Test
    void withoutTenantNothingIsVisible() {
        assertThat(queries.map(viewer(DEMO)).getFeatures()).isEmpty();
    }

    @Test
    void ownedDemoPlotsAreGreenTheRestAreGreyAndNoneHasDebt() {
        PlotFeatureCollection demo = as(DEMO, () -> queries.map(viewer(DEMO)));

        assertThat(demo.getFeatures()).allSatisfy(f -> assertThat(f.getProperties().getDebt()).isEqualTo("0.00"));
        assertThat(demo.getFeatures()).filteredOn(f -> f.getProperties().getStatus() == PlotStatus.GREEN)
                .hasSize(25);
        assertThat(demo.getFeatures()).filteredOn(f -> f.getProperties().getStatus() == PlotStatus.GREY)
                .extracting(f -> f.getProperties().getNumber())
                .containsExactlyInAnyOrder("25", "26", "27", "28", "29", "R-1");
        assertThat(feature(demo, "7").getProperties().getStatus()).isEqualTo(PlotStatus.GREEN);
        assertThat(feature(demo, "R-1").getProperties().getKind()).isEqualTo(nadel.contract.model.PlotKind.COMMON);
        assertThat(feature(demo, "12\u0430").getProperties().getKind())
                .isEqualTo(nadel.contract.model.PlotKind.PLOT);
    }

    @Test
    void everyPlotHasAStatusRowWithoutDebtAndOnlyOwnedPlotsAreGreen() {
        List<String> statuses = as(DEMO, () -> tx.execute(status -> jdbc.queryForList(
                "select distinct s.status || ':' || s.debt from plot p left join plot_status s on s.plot_id = p.id",
                String.class)));
        List<String> greenWithoutOwner = as(DEMO, () -> tx.execute(status -> jdbc.queryForList(
                "select p.number from plot p join plot_status s on s.plot_id = p.id where s.status = 'GREEN' "
                        + "and not exists (select 1 from ownership o where o.plot_id = p.id and o.valid_to is null)",
                String.class)));

        assertThat(statuses).containsExactlyInAnyOrder("GREEN:0.00", "GREY:0.00");
        assertThat(greenWithoutOwner).isEmpty();
    }

    @Test
    void demoRegistryHasBuildingsNotesAndCadastralNumbers() {
        record Counts(int buildings, int notes, int cadastral) {
        }
        Counts counts = as(DEMO, () -> tx.execute(status -> jdbc.queryForObject(
                "select count(*) filter (where has_building), count(*) filter (where notes is not null), "
                        + "count(*) filter (where cadastral_number is not null) from plot",
                (rs, row) -> new Counts(rs.getInt(1), rs.getInt(2), rs.getInt(3)))));

        assertThat(counts.buildings()).isBetween(8, 12);
        assertThat(counts.notes()).isEqualTo(2);
        assertThat(counts.cadastral()).isBetween(8, 12);
    }

    @Test
    void cannotInsertRowsForAnotherTenant() {
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(status -> jdbc.update(
                "insert into plot (tenant_id, number, kind, area_m2) values (?, 'X-1', 'PLOT', 100)", OTHER))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void cannotInsertRowsWithNewColumnsForAnotherTenant() {
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(status -> jdbc.update(
                "insert into plot (tenant_id, number, kind, area_m2, has_building, notes, archived_at) "
                        + "values (?, 'X-2', 'PLOT', 100, true, 'planted', now())", OTHER))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void cannotInsertStatusRowsForAnotherTenant() {
        UUID otherPlot = ownerJdbc().queryForObject(
                "select id from plot where tenant_id = ? and number = '1'", UUID.class, OTHER);

        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(status -> jdbc.update(
                "insert into plot_status (plot_id, tenant_id, status) values (?, ?, 'RED')", otherPlot, OTHER))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void cannotUpdateRowsOfAnotherTenant() {
        Integer updated = as(DEMO, () -> tx.execute(status -> jdbc.update(
                "update plot set street = 'tampered' where tenant_id = ?", OTHER)));

        assertThat(updated).isZero();
    }

    @Test
    void cannotUpdateNewColumnsOfAnotherTenant() {
        Integer updated = as(DEMO, () -> tx.execute(status -> jdbc.update(
                "update plot set has_building = true, notes = 'tampered', archived_at = now(), updated_at = now() "
                        + "where tenant_id = ?", OTHER)));

        assertThat(updated).isZero();
        assertThat(ownerJdbc().queryForObject(
                "select count(*) from plot where tenant_id = ? and (has_building or notes is not null "
                        + "or archived_at is not null)", Integer.class, OTHER)).isZero();
    }

    @Test
    void cannotUpdateOrDeleteStatusRowsOfAnotherTenant() {
        Integer updated = as(DEMO, () -> tx.execute(status -> jdbc.update(
                "update plot_status set status = 'RED', debt = 99 where tenant_id = ?", OTHER)));
        Integer deleted = as(DEMO, () -> tx.execute(status -> jdbc.update(
                "delete from plot_status where tenant_id = ?", OTHER)));

        assertThat(updated).isZero();
        assertThat(deleted).isZero();
        // the other cooperative's plot 1 is owned (GREEN), plot 2 is not (GREY); nothing turned RED
        assertThat(ownerJdbc().queryForList(
                "select status from plot_status where tenant_id = ? order by status", String.class, OTHER))
                .containsExactly("GREEN", "GREY");
    }

    @Test
    void statusRowsOfAnotherTenantAreInvisible() {
        Integer visible = as(DEMO, () -> tx.execute(status -> jdbc.queryForObject(
                "select count(*) from plot_status where tenant_id = ?", Integer.class, OTHER)));

        assertThat(visible).isZero();
    }

    private static PlotFeature feature(PlotFeatureCollection collection, String number) {
        return collection.getFeatures().stream()
                .filter(f -> f.getProperties().getNumber().equals(number))
                .findFirst()
                .orElseThrow();
    }

    private static AccessProfile viewer(UUID tenant) {
        return new AccessProfile(tenant, UUID.randomUUID(), "test-user", "Test User",
                Set.of(Permission.PLOTS_VIEW), List.of(), false);
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
