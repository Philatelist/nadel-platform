package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * The plot side of the ownership guards: a plot with a current owner is not archived, a plot with any ownership row is
 * not deleted and not turned into common land. Every refusal leaves the plot as it was.
 */
class PlotGuardsTests extends OwnershipTestSupport {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    // ---- archive

    @Test
    void aPlotWithACurrentOwnerCannotBeArchived() throws Exception {
        UUID plot = newPlot();
        seedOwner(plot, demoMember("Alpha", "Anna"), "1/1", "2020-01-01", null);

        archive(chairman(), plot).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_OWNERS"));

        assertThat(archivedAt(plot)).isNull();
    }

    @Test
    void aPlotCanBeArchivedOnceItsOwnershipWasEndedByAnEmptyChange() throws Exception {
        UUID plot = newPlot();
        seedOwner(plot, demoMember("Alpha", "Anna"), "1/1", "2020-01-01", null);
        String chairman = chairman();

        archive(chairman, plot).andExpect(status().isConflict());
        mvc.perform(post(PLOTS + "/" + plot + "/ownership-changes")
                        .header(HttpHeaders.AUTHORIZATION, chairman)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("effectiveFrom", "2026-03-01", "owners", List.of()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.current", hasSize(0)));

        archive(chairman, plot).andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(true));
        assertThat(archivedAt(plot)).isNotNull();
    }

    @Test
    void aPlotWithOnlyPastOwnersCanBeArchived() throws Exception {
        UUID plot = newPlot();
        seedOwner(plot, demoMember("Alpha", "Anna"), "1/1", "2010-01-01", "2020-12-31");

        archive(chairman(), plot).andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(true));
    }

    // ---- delete

    @Test
    void aPlotWithACurrentOwnerCannotBeDeleted() throws Exception {
        UUID plot = newPlot();
        seedOwner(plot, demoMember("Alpha", "Anna"), "1/1", "2020-01-01", null);

        remove(chairman(), plot).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_OWNERSHIP_HISTORY"));

        assertThat(plotCount(plot)).isEqualTo(1);
        assertThat(ownershipRows(plot)).hasSize(1);
    }

    @Test
    void aPlotWithOwnershipHistoryCannotBeDeletedEvenWithoutACurrentOwner() throws Exception {
        UUID plot = newPlot();
        seedOwner(plot, demoMember("Alpha", "Anna"), "1/1", "2010-01-01", "2020-12-31");

        remove(chairman(), plot).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_OWNERSHIP_HISTORY"));

        assertThat(plotCount(plot)).isEqualTo(1);
        assertThat(statusRows(plot)).isEqualTo(1);
    }

    @Test
    void aDemoPlotWithHistoryCannotBeDeleted() throws Exception {
        // plot 9 had an owner until 2025-05-31 and has another one now
        UUID plot = demoPlot("9");

        remove(chairman(), plot).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_OWNERSHIP_HISTORY"));
    }

    @Test
    void aPlotThatNeverHadAnOwnerCanBeDeleted() throws Exception {
        UUID plot = newPlot();

        remove(chairman(), plot).andExpect(status().isNoContent());

        assertThat(plotCount(plot)).isZero();
    }

    // ---- kind

    @Test
    void aPlotWithOwnershipHistoryCannotBecomeCommonLand() throws Exception {
        UUID plot = newPlot();
        seedOwner(plot, demoMember("Alpha", "Anna"), "1/1", "2010-01-01", "2020-12-31");

        update(chairman(), plot, "COMMON").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_OWNERSHIP_HISTORY"))
                .andExpect(jsonPath("$.errors[0].field").value("kind"))
                .andExpect(jsonPath("$.errors[0].code").value("PLOT_HAS_OWNERSHIP_HISTORY"));

        assertThat(kindOf(plot)).isEqualTo("PLOT");
    }

    @Test
    void aPlotWithACurrentOwnerCannotBecomeCommonLand() throws Exception {
        UUID plot = newPlot();
        seedOwner(plot, demoMember("Alpha", "Anna"), "1/1", "2020-01-01", null);

        update(chairman(), plot, "COMMON").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_OWNERSHIP_HISTORY"));

        assertThat(kindOf(plot)).isEqualTo("PLOT");
    }

    @Test
    void aPlotWithoutHistoryCanBecomeCommonLandAndBack() throws Exception {
        UUID plot = newPlot();
        String chairman = chairman();

        update(chairman, plot, "COMMON").andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("COMMON"));
        update(chairman, plot, "PLOT").andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("PLOT"));
    }

    @Test
    void aPlotWithHistoryStaysEditableWhenItsKindIsKept() throws Exception {
        UUID plot = newPlot();
        seedOwner(plot, demoMember("Alpha", "Anna"), "1/1", "2020-01-01", null);

        update(chairman(), plot, "PLOT").andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("PLOT"));
    }

    private ResultActions archive(String bearer, UUID plot) throws Exception {
        return mvc.perform(post(PLOTS + "/" + plot + "/archive").header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private ResultActions remove(String bearer, UUID plot) throws Exception {
        return mvc.perform(delete(PLOTS + "/" + plot).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    /** A full replacement that keeps the number and area of a plot made by {@link #newPlot()} and sets the kind. */
    private ResultActions update(String bearer, UUID plot, String kind) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("number", ownerJdbc().queryForObject("select number from plot where id = ?", String.class, plot));
        body.put("kind", kind);
        body.put("areaM2", "COMMON".equals(kind) ? BigDecimal.ZERO : new BigDecimal("600"));
        return mvc.perform(put(PLOTS + "/" + plot).header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private Object archivedAt(UUID plot) {
        return ownerJdbc().queryForList("select archived_at from plot where id = ?", plot).get(0).get("archived_at");
    }

    private String kindOf(UUID plot) {
        return ownerJdbc().queryForObject("select kind from plot where id = ?", String.class, plot);
    }

    private int plotCount(UUID plot) {
        return ownerJdbc().queryForObject("select count(*) from plot where id = ?", Integer.class, plot);
    }

    private int statusRows(UUID plot) {
        return ownerJdbc().queryForObject("select count(*) from plot_status where plot_id = ?", Integer.class, plot);
    }
}
