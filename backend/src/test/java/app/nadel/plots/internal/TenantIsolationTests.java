package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
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
    PlotMapQuery mapQuery;

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
        PlotFeatureCollection demo = as(DEMO, mapQuery::currentTenantMap);
        PlotFeatureCollection other = as(OTHER, mapQuery::currentTenantMap);

        assertThat(demo.getFeatures()).hasSize(13); // 12 plots + the road
        assertThat(other.getFeatures()).hasSize(2);
        assertThat(demo.getFeatures()).extracting(PlotFeature::getId)
                .doesNotContainAnyElementsOf(other.getFeatures().stream().map(PlotFeature::getId).toList());
    }

    @Test
    void withoutTenantNothingIsVisible() {
        assertThat(mapQuery.currentTenantMap().getFeatures()).isEmpty();
    }

    @Test
    void statusesComeFromCacheAndCommonLandIsGrey() {
        PlotFeatureCollection demo = as(DEMO, mapQuery::currentTenantMap);

        assertThat(feature(demo, "5").getProperties().getStatus()).isEqualTo(PlotStatus.RED);
        assertThat(feature(demo, "5").getProperties().getDebt()).isEqualTo("4820.00");
        assertThat(feature(demo, "1").getProperties().getStatus()).isEqualTo(PlotStatus.GREEN);
        assertThat(feature(demo, "R-1").getProperties().getStatus()).isEqualTo(PlotStatus.GREY);
    }

    @Test
    void cannotInsertRowsForAnotherTenant() {
        assertThatThrownBy(() -> as(DEMO, () -> tx.execute(status -> jdbc.update(
                "insert into plot (tenant_id, number, kind, area_m2) values (?, 'X-1', 'PLOT', 100)", OTHER))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void cannotUpdateRowsOfAnotherTenant() {
        Integer updated = as(DEMO, () -> tx.execute(status -> jdbc.update(
                "update plot set street = 'tampered' where tenant_id = ?", OTHER)));

        assertThat(updated).isZero();
    }

    private static PlotFeature feature(PlotFeatureCollection collection, String number) {
        return collection.getFeatures().stream()
                .filter(f -> f.getProperties().getNumber().equals(number))
                .findFirst()
                .orElseThrow();
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
