package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
 * Archiving a member while a change of owner adds that member. The archive holds the member row {@code FOR UPDATE},
 * the change holds it {@code FOR SHARE}, so they run one after another: either the change commits and the archive is
 * refused with {@code MEMBER_OWNS_PLOT}, or the archive commits and the change is refused with
 * {@code MEMBER_ARCHIVED}. Never both succeed, never a deadlock (a 500), and never an archived current owner.
 */
class MemberArchiveConcurrencyTests extends OwnershipTestSupport {

    private static final int ROUNDS = 15;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Test
    void anArchiveAndAChangeThatAddsTheSameMemberNeverEndWithAnArchivedCurrentOwner() throws Exception {
        String chairman = chairman();
        int archivedFirst = 0;
        int changedFirst = 0;

        for (int round = 0; round < ROUNDS; round++) {
            UUID plot = newPlot();
            UUID member = demoMember("Alpha", "Anna");

            List<MockHttpServletResponse> responses = ConcurrentRequests.run(
                    () -> archive(chairman, member),
                    () -> change(chairman, plot, member));
            MockHttpServletResponse archive = responses.get(0);
            MockHttpServletResponse change = responses.get(1);

            if (archive.getStatus() == 200) {
                assertThat(change.getStatus()).as("round %d: archive won", round).isEqualTo(409);
                assertThat(change.getContentAsString()).contains("MEMBER_ARCHIVED");
                assertThat(ownershipRows(plot)).as("round %d", round).isEmpty();
                archivedFirst++;
            } else {
                assertThat(archive.getStatus()).as("round %d: change won", round).isEqualTo(409);
                assertThat(archive.getContentAsString()).contains("MEMBER_OWNS_PLOT");
                assertThat(change.getStatus()).as("round %d", round).isEqualTo(201);
                assertThat(ownershipRows(plot)).as("round %d", round).hasSize(1);
                changedFirst++;
            }
            assertThat(archivedCurrentOwners(plot)).as("round %d: archived current owners", round).isZero();
        }
        assertThat(archivedFirst + changedFirst).isEqualTo(ROUNDS);
    }

    private MockHttpServletResponse archive(String bearer, UUID member) throws Exception {
        return mvc.perform(post("/api/v1/members/" + member + "/archive").header(HttpHeaders.AUTHORIZATION, bearer))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse change(String bearer, UUID plot, UUID member) throws Exception {
        String body = json.writeValueAsString(Map.of("effectiveFrom", "2026-03-01",
                "owners", List.of(Map.of("memberId", member, "share", "1/1"))));
        return mvc.perform(post(PLOTS + "/" + plot + "/ownership-changes")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();
    }

    private int archivedCurrentOwners(UUID plot) {
        return ownerJdbc().queryForObject("select count(*) from ownership o join member m on m.id = o.member_id "
                + "where o.plot_id = ? and o.valid_to is null and m.archived_at is not null", Integer.class, plot);
    }
}
