package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.files.TestImages;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * The photo rule (feature 021 §2.3): while the cooperative's setting {@code readingPhotoRequired} is on, a member's
 * reading without a photo is refused with 400 {@code READING_PHOTO_REQUIRED} on {@code photo} from both web operations
 * and nothing is saved; with a photo it is saved. The board's and the electrician's entries never need a photo. With
 * the setting off a member's reading without a photo is saved.
 */
class ReadingPhotoRuleTests extends ReadingPhotoSupport {

    private boolean hadSettingsRow;

    @BeforeEach
    void rememberSettings() {
        hadSettingsRow = ownerJdbc().queryForObject("select count(*) from cooperative_settings where tenant_id = ?",
                Integer.class, DEMO_TENANT) > 0;
    }

    @AfterEach
    void restoreSettings() {
        if (hadSettingsRow) {
            ownerJdbc().update("update cooperative_settings set reading_photo_required = false where tenant_id = ?",
                    DEMO_TENANT);
        } else {
            ownerJdbc().update("delete from cooperative_settings where tenant_id = ?", DEMO_TENANT);
        }
    }

    @Test
    void whileTheRuleIsOnAMembersReadingWithoutAPhotoIsRefusedAndNothingIsSaved() throws Exception {
        requirePhotos(true);

        expectPhotoRequired(submitWithPhoto(member, plot, submission(meter, "2026-10-15", "8210"), null));
        expectPhotoRequired(send(member, post("/api/v1/plots/" + plot + "/readings"),
                submission(meter, "2026-10-15", "8210")));

        assertThat(readingCount(meter)).as("only the counted reading of the fixture").isEqualTo(1);
        assertThat(photoCount()).isZero();
        // with a photo the same reading is saved; its audit entry is the only one, so the refusals wrote none
        JsonNode saved = body(submitWithPhoto(member, plot, submission(meter, "2026-10-15", "8210"),
                photo("meter.jpg", "image/jpeg", TestImages.jpeg(false))).andExpect(status().isCreated()));
        assertThat(saved.get("reading").get("status").asString()).isEqualTo("PENDING");
        assertThat(saved.get("reading").get("hasPhoto").asBoolean()).isTrue();
        assertThat(changes(awaitEntries(meter, "READING_SUBMITTED", 1).getFirst()))
                .contains(new Change("photo", null, "true"));
        // the same value again without a photo is refused too, not answered as a repeat
        expectPhotoRequired(send(member, post("/api/v1/plots/" + plot + "/readings"),
                submission(meter, "2026-10-15", "8210")));
        assertThat(readingCount(meter)).isEqualTo(2);
    }

    @Test
    void theOwnerRuleComesBeforeThePhotoRule() throws Exception {
        requirePhotos(true);

        submitWithPhoto(chairman(), plot, submission(meter, "2026-10-15", "8210"), null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        submitWithPhoto(operator(), plot, submission(meter, "2026-10-15", "8210"), null)
                .andExpect(status().isForbidden());
    }

    @Test
    void theBoardAndTheElectricianEnterReadingsWithoutAPhotoWhileTheRuleIsOn() throws Exception {
        requirePhotos(true);
        String electrician = customRoleToken("READINGS_ENTER");

        JsonNode byElectrician = enterRows(electrician, List.of(row(meter, "2026-10-15", "8210"))).get(0);
        JsonNode byChairman = enterRows(chairman(), List.of(row(meter, "2026-10-20", "8215"))).get(0);

        for (JsonNode result : List.of(byElectrician, byChairman)) {
            assertThat(result.get("outcome").asString()).isEqualTo("SAVED");
            assertThat(result.get("reading").get("status").asString()).isEqualTo("APPROVED");
            assertThat(result.get("reading").get("hasPhoto").asBoolean()).isFalse();
        }
        assertThat(readingCount(meter)).isEqualTo(3);
    }

    @Test
    void withTheRuleOffAMembersReadingWithoutAPhotoIsSaved() throws Exception {
        // never saved: the default is off
        saved(submitWithPhoto(member, plot, submission(meter, "2026-10-15", "8210"), null));

        requirePhotos(true);
        expectPhotoRequired(submitWithPhoto(member, plot, submission(meter, "2026-10-16", "8211"), null));
        requirePhotos(false);

        JsonNode again = body(send(member, post("/api/v1/plots/" + plot + "/readings"),
                submission(meter, "2026-10-16", "8211")).andExpect(status().isCreated()));
        assertThat(again.get("reading").get("hasPhoto").asBoolean()).isFalse();
        assertThat(readingCount(meter)).isEqualTo(3);
    }

    // ---- helpers

    private void requirePhotos(boolean required) throws Exception {
        send(chairman(), put("/api/v1/settings"), Map.of("readingPhotoRequired", required))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readingPhotoRequired").value(required));
    }

    private static void expectPhotoRequired(ResultActions answer) throws Exception {
        answer.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("READING_PHOTO_REQUIRED"))
                .andExpect(jsonPath("$.errors[0].field").value("photo"))
                .andExpect(jsonPath("$.errors[0].code").value("READING_PHOTO_REQUIRED"));
    }
}
