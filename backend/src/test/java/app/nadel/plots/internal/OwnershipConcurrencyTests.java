package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two changes of the owners of one plot at the same moment. The plot row lock makes them run one after another, so
 * the second one sees the first one's result: it either fails the date rule or is a valid later change. In every case
 * the timeline of the plot stays valid and no day has shares that do not add up to one whole.
 */
class OwnershipConcurrencyTests extends OwnershipTestSupport {

    private static final int ROUNDS = 10;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Test
    void ofTwoChangesOnTheSameDateExactlyOneSucceeds() throws Exception {
        String chairman = chairman();
        UUID anna = demoMember("Alpha", "Anna");
        UUID boris = demoMember("Beta", "Boris");
        UUID hanna = demoMember("Gamma", "Hanna");
        int created = 0;

        for (int round = 0; round < ROUNDS; round++) {
            UUID plot = newPlot();
            seedOwner(plot, anna, "1/1", "2020-01-01", null);

            List<MockHttpServletResponse> responses = ConcurrentRequests.run(
                    () -> change(chairman, plot, "2026-03-01", boris),
                    () -> change(chairman, plot, "2026-03-01", hanna));

            List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).sorted().toList();
            assertThat(statuses).as("round %d statuses %s", round, statuses).containsExactly(201, 409);
            for (MockHttpServletResponse response : responses) {
                if (response.getStatus() == 409) {
                    assertThat(response.getContentAsString()).contains("OWNERSHIP_DATE_INVALID");
                }
            }
            assertTimelineIsValid(plot);
            assertThat(currentRows(plot)).as("round %d: one owner remains", round).hasSize(1);
            created++;
        }
        assertThat(created).isEqualTo(ROUNDS);
    }

    @Test
    void changesOnDifferentDatesLeaveAValidTimelineWhicheverRunsFirst() throws Exception {
        String chairman = chairman();
        UUID anna = demoMember("Alpha", "Anna");
        UUID boris = demoMember("Beta", "Boris");
        UUID hanna = demoMember("Gamma", "Hanna");

        for (int round = 0; round < ROUNDS; round++) {
            UUID plot = newPlot();
            seedOwner(plot, anna, "1/1", "2020-01-01", null);

            List<MockHttpServletResponse> responses = ConcurrentRequests.run(
                    () -> change(chairman, plot, "2026-03-01", boris),
                    () -> change(chairman, plot, "2026-04-01", hanna));

            // April first, then March: March is not after April's start (409). March first, then April: both succeed.
            List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).toList();
            assertThat(statuses).as("round %d statuses %s", round, statuses)
                    .isIn(List.of(201, 201), List.of(409, 201));
            assertTimelineIsValid(plot);
            assertThat(currentRows(plot)).as("round %d", round).hasSize(1);
        }
    }

    private MockHttpServletResponse change(String bearer, UUID plot, String date, UUID owner) throws Exception {
        String body = json.writeValueAsString(Map.of("effectiveFrom", date,
                "owners", List.of(Map.of("memberId", owner, "share", "1/1"))));
        return mvc.perform(post(PLOTS + "/" + plot + "/ownership-changes")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();
    }

    private List<Map<String, Object>> currentRows(UUID plot) {
        return ownershipRows(plot).stream().filter(row -> row.get("valid_to") == null).toList();
    }

    /**
     * On every day the owners' shares add up to one whole (checked at each boundary with exact arithmetic in SQL), and
     * the periods of the plot do not overlap.
     */
    private void assertTimelineIsValid(UUID plot) {
        List<Map<String, Object>> rows = ownershipRows(plot);
        List<String> boundaries = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            boundaries.add((String) row.get("valid_from"));
        }
        for (String day : boundaries) {
            BigDecimal sum = ownerJdbc().queryForObject(
                    "select coalesce(sum(share_numerator::numeric / share_denominator), 0) from ownership "
                            + "where plot_id = ? and valid_from <= ?::date and (valid_to is null or valid_to >= ?::date)",
                    BigDecimal.class, plot, day, day);
            assertThat(sum.compareTo(BigDecimal.ONE)).as("shares on %s", day).isZero();
        }
        Integer overlapping = ownerJdbc().queryForObject(
                "select count(*) from ownership a join ownership b on a.plot_id = b.plot_id and a.id < b.id "
                        + "where a.plot_id = ? and daterange(a.valid_from, a.valid_to, '[]') "
                        + "&& daterange(b.valid_from, b.valid_to, '[]')", Integer.class, plot);
        assertThat(overlapping).isZero();
    }
}
