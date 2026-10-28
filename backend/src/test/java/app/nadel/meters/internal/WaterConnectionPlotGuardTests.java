package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.plots.PlotImport;
import app.nadel.plots.PlotImportAction;
import app.nadel.plots.PlotImportItem;
import app.nadel.plots.PlotImportPlan;
import app.nadel.plots.PlotImportRow;
import app.nadel.problem.FieldProblem;
import app.nadel.tenancy.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The water connection guard of the meters module ({@code plots.PlotChangeGuard}, feature 014 §4.1): a plot with a
 * lasting water connection is neither archived nor turned into common land, by hand or by the Excel import
 * ({@code PLOT_HAS_WATER_CONNECTION}); a plot whose connection has ended, or that never had one, is not affected; a plot
 * that also has an active meter is refused with {@code PLOT_HAS_METER}, the meter guard being asked first.
 */
class WaterConnectionPlotGuardTests extends MeterTestSupport {

    private static final String PLOTS = "/api/v1/plots";

    @Autowired
    PlotImport plotImport;

    @Autowired
    TransactionTemplate tx;

    // ---- archive

    @Test
    void aPlotWithALastingConnectionIsNotArchived() throws Exception {
        UUID plot = newPlot("MT Садова");
        connect(plot, "2020-05-01");

        archive(plot).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_WATER_CONNECTION"))
                .andExpect(jsonPath("$.errors").isEmpty());

        assertThat(archived(plot)).isFalse();
    }

    @Test
    void aPlotWhoseConnectionHasEndedIsArchived() throws Exception {
        UUID plot = newPlot("MT Садова");
        end(plot, connect(plot, "2020-05-01"), "2026-01-31");

        archive(plot).andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(true));

        assertThat(archived(plot)).isTrue();
    }

    @Test
    void aPlotWithoutAConnectionIsArchivedAsBefore() throws Exception {
        UUID plot = newPlot("MT Садова");
        connect(newPlot("MT Садова"), "2020-05-01"); // another plot's connection

        archive(plot).andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(true));

        assertThat(archived(plot)).isTrue();
    }

    @Test
    void aPlotWithAnActiveMeterAndAConnectionIsRefusedForTheMeterFirst() throws Exception {
        UUID plot = newPlot("MT Садова");
        register(plotMeter(plot, "WATER", "MT-1", "2026-06-01", "0"));
        connect(plot, "2020-05-01");

        archive(plot).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLOT_HAS_METER"));
        update(plot, "COMMON").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_METER"))
                .andExpect(jsonPath("$.errors[0].code").value("PLOT_HAS_METER"));

        assertThat(archived(plot)).isFalse();
        assertThat(kind(plot)).isEqualTo("PLOT");
    }

    // ---- plot to common land

    @Test
    void aPlotWithALastingConnectionDoesNotBecomeCommonLand() throws Exception {
        UUID plot = newPlot("MT Садова");
        connect(plot, "2020-05-01");

        update(plot, "COMMON").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_WATER_CONNECTION"))
                .andExpect(jsonPath("$.errors[0].field").value("kind"))
                .andExpect(jsonPath("$.errors[0].code").value("PLOT_HAS_WATER_CONNECTION"));

        assertThat(kind(plot)).isEqualTo("PLOT");
    }

    @Test
    void aPlotWhoseConnectionHasEndedMayBecomeCommonLand() throws Exception {
        UUID plot = newPlot("MT Садова");
        end(plot, connect(plot, "2020-05-01"), "2026-01-31");

        update(plot, "COMMON").andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("COMMON"));
    }

    // ---- the Excel import

    @Test
    void theImportDoesNotTurnAPlotWithALastingConnectionIntoCommonLand() throws Exception {
        UUID connected = newPlot("MT Садова");
        connect(connected, "2020-05-01");
        UUID ended = newPlot("MT Садова");
        end(ended, connect(ended, "2020-05-01"), "2026-01-31");
        UUID metered = newPlot("MT Садова");
        register(plotMeter(metered, "WATER", "MT-1", "2026-06-01", "0"));
        connect(metered, "2020-05-01");

        List<PlotImportItem> items = plan(
                new PlotImportRow(2, plotNumber(connected), null, "COMMON", null, null, null, null),
                new PlotImportRow(3, plotNumber(ended), null, "COMMON", null, null, null, null),
                new PlotImportRow(4, plotNumber(metered), null, "COMMON", null, null, null, null));

        assertThat(items.get(0).action()).isNull();
        assertThat(items.get(0).problems()).containsExactly(new FieldProblem("kind", "PLOT_HAS_WATER_CONNECTION"));
        assertThat(items.get(1).action()).isEqualTo(PlotImportAction.UPDATE);
        assertThat(items.get(1).changedFields()).containsExactly("kind");
        assertThat(items.get(2).problems()).containsExactly(new FieldProblem("kind", "PLOT_HAS_METER"));
    }

    // ---- helpers

    /** Connects the plot to the cooperative's water supply; returns the connection id. */
    private String connect(UUID plot, String from) throws Exception {
        return body(send(chairman(), post(PLOTS + "/" + plot + "/water-connections"), Map.of("from", from))
                .andExpect(status().isCreated())).get("current").get("id").asString();
    }

    private void end(UUID plot, String connectionId, String until) throws Exception {
        send(chairman(), post(PLOTS + "/" + plot + "/water-connections/" + connectionId + "/end"),
                Map.of("until", until)).andExpect(status().isOk());
    }

    private ResultActions archive(UUID plot) throws Exception {
        return send(chairman(), post(PLOTS + "/" + plot + "/archive"), null);
    }

    private ResultActions update(UUID plot, String kind) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("number", plotNumber(plot));
        body.put("street", "MT Садова");
        body.put("kind", kind);
        body.put("areaM2", "COMMON".equals(kind) ? 0 : 600);
        return send(chairman(), put(PLOTS + "/" + plot), body);
    }

    private List<PlotImportItem> plan(PlotImportRow... rows) {
        PlotImportPlan plan = TenantContext.runAs(DEMO_TENANT,
                () -> tx.execute(status -> plotImport.plan(DEMO_TENANT, UUID.randomUUID(), List.of(rows))));
        return plan.items();
    }

    private boolean archived(UUID plot) {
        return ownerJdbc().queryForObject("select archived_at is not null from plot where id = ?", Boolean.class,
                plot);
    }

    private String kind(UUID plot) {
        return ownerJdbc().queryForObject("select kind from plot where id = ?", String.class, plot);
    }
}
