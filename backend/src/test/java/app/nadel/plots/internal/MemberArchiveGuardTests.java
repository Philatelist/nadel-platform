package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
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
 * POST /members/{id}/archive with the ownership guard: a current owner is refused and nothing changes, anyone else
 * (no plots, a former owner, an owner whose ownership was ended) is archived, and the last-chairman rule still holds.
 */
class MemberArchiveGuardTests extends OwnershipTestSupport {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Test
    void aCurrentOwnerCannotBeArchivedAndNothingChanges() throws Exception {
        UUID plot = newPlot();
        UUID owner = demoMember("Alpha", "Anna");
        seedOwner(plot, owner, "1/1", "2020-01-01", null);

        archive(chairman(), owner)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_OWNS_PLOT"));

        assertThat(archivedAt(owner)).isNull();
        assertThat(ownershipRows(plot)).singleElement().satisfies(row -> assertThat(row.get("valid_to")).isNull());
        assertThat(statusOf(plot)).isEqualTo("GREEN");
    }

    @Test
    void aCoOwnerIsRefusedToo() throws Exception {
        UUID plot = newPlot();
        UUID anna = demoMember("Alpha", "Anna");
        UUID boris = demoMember("Beta", "Boris");
        seedOwner(plot, anna, "1/2", "2020-01-01", null);
        seedOwner(plot, boris, "1/2", "2020-01-01", null);

        archive(chairman(), boris).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_OWNS_PLOT"));

        assertThat(archivedAt(boris)).isNull();
    }

    @Test
    void anOwnerOfOnePlotAmongSeveralIsRefused() throws Exception {
        UUID sold = newPlot();
        UUID kept = newPlot();
        UUID owner = demoMember("Alpha", "Anna");
        seedOwner(sold, owner, "1/1", "2010-01-01", "2020-12-31");
        seedOwner(kept, owner, "1/1", "2021-01-01", null);

        archive(chairman(), owner).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_OWNS_PLOT"));
    }

    @Test
    void aFormerOwnerCanBeArchived() throws Exception {
        UUID plot = newPlot();
        UUID former = demoMember("Alpha", "Anna");
        UUID current = demoMember("Beta", "Boris");
        seedOwner(plot, former, "1/1", "2010-01-01", "2020-12-31");
        seedOwner(plot, current, "1/1", "2021-01-01", null);

        archive(chairman(), former).andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(true));

        assertThat(archivedAt(former)).isNotNull();
        // the history keeps pointing at the archived member
        assertThat(ownershipRows(plot)).hasSize(2);
    }

    @Test
    void aMemberWhoseOwnershipWasEndedThroughAChangeCanBeArchived() throws Exception {
        UUID plot = newPlot();
        UUID owner = demoMember("Alpha", "Anna");
        seedOwner(plot, owner, "1/1", "2020-01-01", null);
        String chairman = chairman();

        archive(chairman, owner).andExpect(status().isConflict());
        mvc.perform(post(PLOTS + "/" + plot + "/ownership-changes")
                        .header(HttpHeaders.AUTHORIZATION, chairman)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("effectiveFrom", "2026-03-01", "owners", List.of()))))
                .andExpect(status().isCreated());

        archive(chairman, owner).andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(true));
        assertThat(archivedAt(owner)).isNotNull();
    }

    @Test
    void aMemberWithoutPlotsCanBeArchived() throws Exception {
        UUID member = demoMember("Alpha", "Anna");

        archive(chairman(), member).andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(true));

        assertThat(archivedAt(member)).isNotNull();
    }

    @Test
    void archivingAnAlreadyArchivedFormerOwnerIsStillANoOp() throws Exception {
        UUID plot = newPlot();
        UUID former = demoMember("Alpha", "Anna");
        seedOwner(plot, former, "1/1", "2010-01-01", "2020-12-31");
        archiveMember(DEMO_TENANT, former);

        archive(chairman(), former).andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(true));
    }

    @Test
    void theLastChairmanRuleStillHolds() throws Exception {
        // the other cooperative has no chairman of its own: this member is the only one
        String email = "only-chairman-" + UUID.randomUUID() + "@example.com";
        UUID onlyChairman = newMember(OTHER_TENANT, email);
        giveBaseRole(OTHER_TENANT, onlyChairman, "CHAIRMAN");
        String token = TestTokens.bearer(OTHER_TENANT, "sub-" + email, email);

        archive(token, onlyChairman).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_CHAIRMAN"));

        assertThat(inTenant(OTHER_TENANT, sql -> sql.queryForList(
                "select archived_at from member where id = ?", onlyChairman)).get(0).get("archived_at")).isNull();
    }

    private ResultActions archive(String bearer, UUID member) throws Exception {
        return mvc.perform(post("/api/v1/members/" + member + "/archive").header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private Object archivedAt(UUID member) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForList("select archived_at from member where id = ?", member))
                .get(0).get("archived_at");
    }
}
