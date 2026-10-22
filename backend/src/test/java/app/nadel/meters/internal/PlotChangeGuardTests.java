package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * The plot guard of the meters module ({@code plots.PlotChangeGuard}, ADR 0011): a plot with a meter, active or removed,
 * is neither deleted nor turned into common land, and a plot with an active meter is not archived, by hand or by the
 * Excel import. Every refusal leaves the plot as it was; a plot without a meter is not affected.
 */
class PlotChangeGuardTests extends MeterTestSupport {

    private static final String PLOTS = "/api/v1/plots";

    @Autowired
    PlotImport plotImport;

    @Autowired
    TransactionTemplate tx;

    // ---- delete

    @Test
    void aPlotWithAnActiveMeterIsNotDeleted() throws Exception {
        UUID plot = newPlot("MT Садова");
        register(plotMeter(plot, "ELECTRICITY", "MT-1", "2026-06-01", "0"));

        send(chairman(), delete(PLOTS + "/" + plot), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_METER"));

        assertThat(plotExists(plot)).isTrue();
    }

    @Test
    void aPlotWithOnlyARemovedMeterIsNotDeletedEither() throws Exception {
        UUID plot = newPlot("MT Садова");
        UUID meter = register(plotMeter(plot, "WATER", "MT-1", "2026-06-01", "0"));
        remove(meter);

        send(chairman(), delete(PLOTS + "/" + plot), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_METER"));

        assertThat(plotExists(plot)).isTrue();
    }

    @Test
    void aPlotWithoutAMeterIsDeleted() throws Exception {
        UUID plot = newPlot("MT Садова");
        register(plotMeter(newPlot("MT Садова"), "ELECTRICITY", "MT-1", "2026-06-01", "0")); // another plot's meter

        send(chairman(), delete(PLOTS + "/" + plot), null).andExpect(status().isNoContent());

        assertThat(plotExists(plot)).isFalse();
    }

    // ---- archive

    @Test
    void aPlotWithAnActiveMeterIsArchivedOnlyOnceTheMeterIsRemoved() throws Exception {
        UUID plot = newPlot("MT Садова");
        UUID meter = register(plotMeter(plot, "ELECTRICITY", "MT-1", "2026-06-01", "0"));

        send(chairman(), post(PLOTS + "/" + plot + "/archive"), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_METER"));
        assertThat(archived(plot)).isFalse();

        remove(meter);

        send(chairman(), post(PLOTS + "/" + plot + "/archive"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));
        assertThat(archived(plot)).isTrue();
    }

    @Test
    void anActiveWaterMeterBlocksTheArchiveToo() throws Exception {
        UUID plot = newPlot("MT Садова");
        register(plotMeter(plot, "WATER", "MT-1", "2026-06-01", "0"));

        send(chairman(), post(PLOTS + "/" + plot + "/archive"), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_METER"));
    }

    // ---- plot to common land

    @Test
    void aPlotWithAMeterDoesNotBecomeCommonLand() throws Exception {
        UUID active = newPlot("MT Садова");
        register(plotMeter(active, "ELECTRICITY", "MT-1", "2026-06-01", "0"));
        UUID removed = newPlot("MT Садова");
        remove(register(plotMeter(removed, "ELECTRICITY", "MT-2", "2026-06-01", "0")));

        for (UUID plot : new UUID[] {active, removed}) {
            update(plot, "COMMON", "MT Садова").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PLOT_HAS_METER"))
                    .andExpect(jsonPath("$.errors[0].field").value("kind"))
                    .andExpect(jsonPath("$.errors[0].code").value("PLOT_HAS_METER"));
            assertThat(kind(plot)).isEqualTo("PLOT");
        }
    }

    @Test
    void otherEditsOfAPlotWithAMeterAreFineAndAPlotWithoutOneMayBecomeCommonLand() throws Exception {
        UUID withMeter = newPlot("MT Садова");
        register(plotMeter(withMeter, "ELECTRICITY", "MT-1", "2026-06-01", "0"));
        UUID withoutMeter = newPlot("MT Садова");

        update(withMeter, "PLOT", "MT Вишнева").andExpect(status().isOk())
                .andExpect(jsonPath("$.street").value("MT Вишнева"));
        update(withoutMeter, "COMMON", "MT Садова").andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("COMMON"));
    }

    // ---- the Excel import

    @Test
    void theImportDoesNotTurnAPlotWithAMeterIntoCommonLand() throws Exception {
        UUID active = newPlot("MT Садова");
        register(plotMeter(active, "ELECTRICITY", "MT-1", "2026-06-01", "0"));
        UUID removed = newPlot("MT Садова");
        remove(register(plotMeter(removed, "ELECTRICITY", "MT-2", "2026-06-01", "0")));
        UUID free = newPlot("MT Садова");

        List<PlotImportItem> items = plan(
                new PlotImportRow(2, plotNumber(active), null, "COMMON", null, null, null, null),
                new PlotImportRow(3, plotNumber(removed), null, "common", null, null, null, null),
                new PlotImportRow(4, plotNumber(free), null, "COMMON", null, null, null, null),
                new PlotImportRow(5, plotNumber(active), "MT Вишнева", "PLOT", null, null, null, null));

        assertThat(items.get(0).action()).isNull();
        assertThat(items.get(0).problems()).containsExactly(new FieldProblem("kind", "PLOT_HAS_METER"));
        assertThat(items.get(1).problems()).containsExactly(new FieldProblem("kind", "PLOT_HAS_METER"));
        assertThat(items.get(2).action()).isEqualTo(PlotImportAction.UPDATE);
        assertThat(items.get(2).changedFields()).containsExactly("kind");
        // a later row of the same number is a duplicate in the file, not a meter problem
        assertThat(items.get(3).problems()).extracting(FieldProblem::code).containsExactly("DUPLICATE_IN_FILE");
    }

    @Test
    void theImportMayChangeOtherFieldsOfAPlotWithAMeter() throws Exception {
        UUID plot = newPlot("MT Садова");
        register(plotMeter(plot, "ELECTRICITY", "MT-1", "2026-06-01", "0"));

        PlotImportItem item = plan(new PlotImportRow(2, plotNumber(plot), "MT Вишнева", "PLOT", null, null, null,
                null)).getFirst();

        assertThat(item.problems()).isEmpty();
        assertThat(item.action()).isEqualTo(PlotImportAction.UPDATE);
        assertThat(item.changedFields()).containsExactly("street");
    }

    // ---- helpers

    private List<PlotImportItem> plan(PlotImportRow... rows) {
        PlotImportPlan plan = TenantContext.runAs(DEMO_TENANT,
                () -> tx.execute(status -> plotImport.plan(DEMO_TENANT, UUID.randomUUID(), List.of(rows))));
        return plan.items();
    }

    private void remove(UUID meter) throws Exception {
        send(chairman(), post(METERS + "/" + meter + "/remove"), Map.of("removedOn", "2026-07-01",
                "finalReading", "1")).andExpect(status().isOk());
    }

    private ResultActions update(UUID plot, String kind, String street) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("number", plotNumber(plot));
        body.put("street", street);
        body.put("kind", kind);
        body.put("areaM2", "COMMON".equals(kind) ? 0 : 600);
        return send(chairman(), put(PLOTS + "/" + plot), body);
    }

    private boolean plotExists(UUID plot) {
        return ownerJdbc().queryForObject("select count(*) from plot where id = ?", Integer.class, plot) == 1;
    }

    private boolean archived(UUID plot) {
        return ownerJdbc().queryForObject("select archived_at is not null from plot where id = ?", Boolean.class,
                plot);
    }

    private String kind(UUID plot) {
        return ownerJdbc().queryForObject("select kind from plot where id = ?", String.class, plot);
    }
}
