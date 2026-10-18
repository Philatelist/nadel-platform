package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Plot numbers sort naturally and are unique per cooperative ignoring case; both are decided by the database. */
class NaturalSortKeyTests extends IntegrationTest {

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    @BeforeEach
    void createCooperatives() {
        JdbcTemplate owner = ownerJdbc();
        owner.update("insert into tenant (id, name) values (?, 'Sort test A'), (?, 'Sort test B')", tenantA, tenantB);
    }

    @AfterEach
    void removeCooperatives() {
        JdbcTemplate owner = ownerJdbc();
        owner.update("delete from plot where tenant_id in (?, ?)", tenantA, tenantB);
        owner.update("delete from tenant where id in (?, ?)", tenantA, tenantB);
    }

    @Test
    void numbersAreOrderedNaturally() {
        for (String number : List.of("10", "2", "12а", "12", "R-1", "1a", "13")) {
            addPlot(tenantA, number);
        }

        List<String> ordered = inTenant(tenantA, sql -> sql.queryForList(
                "select number from plot order by number_sort_key, number, id", String.class));

        assertThat(ordered).containsExactly("1a", "2", "10", "12", "12а", "13", "R-1");
    }

    @Test
    void sortKeyPadsDigitRunsAndLowerCasesTheRest() {
        String key = ownerJdbc().queryForObject("select natural_sort_key('A-12b7')", String.class);

        assertThat(key).isEqualTo("a-" + "0".repeat(18) + "12" + "b" + "0".repeat(19) + "7");
    }

    @Test
    void sameNumberWithAnotherCaseCollidesInOneCooperative() {
        addPlot(tenantA, "12а"); // Cyrillic small a

        assertThatThrownBy(() -> addPlot(tenantA, "12А")) // Cyrillic capital A
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("plot_tenant_number_uq");
    }

    @Test
    void sameNumberIsAllowedInAnotherCooperative() {
        addPlot(tenantA, "12");

        addPlot(tenantB, "12");

        assertThat(countPlots(tenantB, "12")).isEqualTo(1);
    }

    @Test
    void archivedPlotFreesItsNumber() {
        addPlot(tenantA, "7");
        inTenant(tenantA, sql -> sql.update("update plot set archived_at = now() where number = '7'"));

        addPlot(tenantA, "7");

        assertThat(countPlots(tenantA, "7")).isEqualTo(2);
    }

    @Test
    void numberIsTrimmedAndAtMostTwentyCharacters() {
        assertThatThrownBy(() -> addPlot(tenantA, " 5"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("plot_number_chk");
        assertThatThrownBy(() -> addPlot(tenantA, "1".repeat(21)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("plot_number_chk");
    }

    private void addPlot(UUID tenant, String number) {
        inTenant(tenant, sql -> sql.update(
                "insert into plot (tenant_id, number, kind, area_m2) values (?, ?, 'PLOT', 600)", tenant, number));
    }

    private int countPlots(UUID tenant, String number) {
        return inTenant(tenant, sql -> sql.queryForObject(
                "select count(*) from plot where number = ?", Integer.class, number));
    }
}
