package app.nadel.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The committed workbooks under {@code src/test/resources/imports/} preview as their names promise. */
class ImportFixturesTests extends ImportCommitSupport {

    private static byte[] resource(String path) throws IOException {
        try (var in = ImportFixtures.class.getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    void theValidWorkbookPreviewsAsThirtyPlotsThirtyFiveMembersAndTheirOwners() throws Exception {
        upload(resource(ImportFixtures.VALID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.problems", hasSize(0)))
                .andExpect(jsonPath("$.problemCount").value(0))
                .andExpect(jsonPath("$.committable").value(true))
                .andExpect(jsonPath("$.counts.plots.created").value(30))
                .andExpect(jsonPath("$.counts.plots.updated").value(0))
                .andExpect(jsonPath("$.counts.plots.unchanged").value(0))
                .andExpect(jsonPath("$.counts.members.created").value(35))
                .andExpect(jsonPath("$.counts.members.updated").value(0))
                .andExpect(jsonPath("$.counts.ownerships.created").value(35));
    }

    @Test
    void theCommittedFilesAreWhatTheGeneratorWouldWrite() throws Exception {
        // the generator's content and the committed bytes agree on what they preview as
        JsonNode committed = preview(resource(ImportFixtures.VALID));
        JsonNode generated = preview(ImportFixtures.valid());
        assertThat(generated.get("counts")).isEqualTo(committed.get("counts"));
        JsonNode committedErrors = preview(resource(ImportFixtures.ERRORS));
        JsonNode generatedErrors = preview(ImportFixtures.errors());
        assertThat(problems(generatedErrors)).isEqualTo(problems(committedErrors));
    }

    @Test
    void theErrorsWorkbookListsEveryTypicalMistakeAndCannotBeCommitted() throws Exception {
        JsonNode preview = preview(resource(ImportFixtures.ERRORS));

        assertThat(preview.get("committable").asBoolean()).isFalse();
        assertThat(problems(preview)).containsExactly(
                "PLOTS/3/cadastral_number/CADASTRAL_INVALID",
                "PLOTS/4/number/DUPLICATE_IN_FILE",
                "PLOTS/5/area_m2/AREA_BOTH_OR_NONE",
                "PLOTS/6/area_m2/AREA_BOTH_OR_NONE",
                "PLOTS/7/kind/INVALID",
                "MEMBERS/3/email/INVALID",
                "MEMBERS/4/email/MEMBER_KEY_MISSING",
                "OWNERSHIP/2/share/SHARES_NOT_ONE",
                "OWNERSHIP/4/plot_number/UNKNOWN_PLOT",
                "OWNERSHIP/5/plot_number/OWNERSHIP_CONFLICT",
                "OWNERSHIP/6/member_external_id/UNKNOWN_MEMBER_KEY");
        assertThat(preview.get("problemCount").asInt()).isEqualTo(11);
    }

    /** {@code SHEET/row/column/code} of every problem, in the order of the preview. */
    private static List<String> problems(JsonNode preview) {
        List<String> problems = new ArrayList<>();
        for (JsonNode problem : preview.get("problems")) {
            problems.add(problem.get("sheet").asString() + "/" + problem.get("row").asInt() + "/"
                    + (problem.has("column") ? problem.get("column").asString() : "-") + "/"
                    + problem.get("code").asString());
        }
        return problems;
    }
}
