package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.files.TestImages;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * A member submits a reading with a photo of the meter (feature 021 §2.3) through
 * {@code POST /plots/{id}/reading-submissions}: a JPEG or PNG is stored with its metadata removed and the reading says
 * it has a photo; anything else, an unreadable image and a photo over 5 MiB are refused with the codes of the expense
 * receipts and nothing is saved, the reading included; a new photo with the same value and date is a new submission;
 * the audit entry says whether a photo came; the deprecated JSON operation still works.
 */
class ReadingPhotoUploadTests extends ReadingPhotoSupport {

    @Test
    void aCameraJpegIsStoredWithItsMetadataRemovedAndTheReadingSaysItHasAPhoto() throws Exception {
        byte[] camera = TestImages.cameraJpeg(false);

        JsonNode saved = body(submitWithPhoto(member, plot, submission(meter, "2026-10-15", "8210"),
                photo("IMG_0001.jpg", "image/jpeg", camera)).andExpect(status().isCreated()));

        JsonNode reading = saved.get("reading");
        assertThat(reading.get("status").asString()).isEqualTo("PENDING");
        assertThat(reading.get("source").asString()).isEqualTo("MEMBER");
        assertThat(reading.get("hasPhoto").asBoolean()).isTrue();
        UUID id = UUID.fromString(reading.get("id").asString());
        byte[] stored = storedPhoto(id);
        assertThat(stored).isNotNull();
        assertThat(stored.length).isLessThan(camera.length);
        for (String marker : TestImages.MARKERS) {
            assertThat(TestImages.contains(stored, marker)).as(marker).isFalse();
        }
        assertThat(TestImages.contains(stored, "Exif")).as("the orientation is kept").isTrue();
        Map<String, Object> row = ownerJdbc().queryForMap("select tenant_id, content_type, size_bytes, sha256, "
                + "created_by from reading_photo where reading_id = ?", id);
        assertThat(row.get("tenant_id")).isEqualTo(DEMO_TENANT);
        assertThat(row.get("content_type")).isEqualTo("image/jpeg");
        assertThat(row.get("size_bytes")).isEqualTo(stored.length);
        assertThat((byte[]) row.get("sha256")).isEqualTo(MessageDigest.getInstance("SHA-256").digest(stored));
        assertThat(row.get("created_by")).isEqualTo(owner.id());
    }

    @Test
    void aPngIsStoredWithItsMetadataRemoved() throws Exception {
        byte[] png = TestImages.metadataPng();

        UUID id = saved(submitWithPhoto(member, plot, submission(meter, "2026-10-15", "8210"),
                photo("meter.png", "image/png", png)));

        byte[] stored = storedPhoto(id);
        assertThat(stored).isNotNull();
        for (String marker : TestImages.MARKERS) {
            assertThat(TestImages.contains(stored, marker)).as(marker).isFalse();
        }
        assertThat(ownerJdbc().queryForObject("select content_type from reading_photo where reading_id = ?",
                String.class, id)).isEqualTo("image/png");
    }

    @Test
    void theTypeIsTheContentNotTheNameOrTheDeclaredType() throws Exception {
        UUID id = saved(submitWithPhoto(member, plot, submission(meter, "2026-10-15", "8210"),
                photo("scan.pdf", "application/pdf", TestImages.png())));

        assertThat(ownerJdbc().queryForObject("select content_type from reading_photo where reading_id = ?",
                String.class, id)).isEqualTo("image/png");
    }

    @Test
    void aPdfIsRefusedAndNothingIsSaved() throws Exception {
        expectRefused(photo("receipt.pdf", "application/pdf", TestImages.pdf()), 400, "FILE_TYPE_NOT_ALLOWED");
    }

    @Test
    void aHeicTheAppDidNotConvertIsRefusedAndNothingIsSaved() throws Exception {
        // an ISO BMFF box "ftyp" with the brand "heic", as an iPhone writes it
        byte[] heic = TestImages.concat(new byte[] {0, 0, 0, 0x18}, "ftypheic".getBytes(StandardCharsets.US_ASCII),
                new byte[] {0, 0, 0, 0}, "mif1heic".getBytes(StandardCharsets.US_ASCII), new byte[64]);

        expectRefused(photo("IMG_0001.HEIC", "image/heic", heic), 400, "FILE_TYPE_NOT_ALLOWED");
    }

    @Test
    void anUnreadableImageIsRefusedAndNothingIsSaved() throws Exception {
        expectRefused(photo("broken.png", "image/png", Arrays.copyOf(TestImages.png(), 40)), 400,
                "FILE_TYPE_NOT_ALLOWED");
    }

    @Test
    void aPhotoOverFiveMegabytesIsRefusedAndNothingIsSaved() throws Exception {
        byte[] tooLarge = Arrays.copyOf(TestImages.jpeg(false), 5 * 1024 * 1024 + 1);

        expectRefused(photo("big.jpg", "image/jpeg", tooLarge), 413, "FILE_TOO_LARGE");
    }

    @Test
    void aRefusedPhotoNeverReplacesThePendingReading() throws Exception {
        UUID pending = saved(submitWithPhoto(member, plot, submission(meter, "2026-10-15", "8210"), null));

        submitWithPhoto(member, plot, submission(meter, "2026-10-15", "8212"),
                photo("receipt.pdf", "application/pdf", TestImages.pdf())).andExpect(status().isBadRequest());

        assertThat(readingStatus(pending)).isEqualTo("PENDING");
        assertThat(readingCount(meter)).isEqualTo(2);
    }

    @Test
    void aSubmissionWithoutAPhotoHasNone() throws Exception {
        JsonNode saved = body(submitWithPhoto(member, plot, submission(meter, "2026-10-15", "8210"), null)
                .andExpect(status().isCreated()));

        assertThat(saved.get("reading").get("hasPhoto").asBoolean()).isFalse();
        assertThat(photoCount()).isZero();
    }

    @Test
    void aNewPhotoWithTheSameValueAndDateIsAChange() throws Exception {
        UUID first = saved(submitWithPhoto(member, plot, submission(meter, "2026-10-15", "8210"), null));

        JsonNode second = body(submitWithPhoto(member, plot, submission(meter, "2026-10-15", "8210"),
                photo("meter.jpg", "image/jpeg", TestImages.jpeg(false))).andExpect(status().isCreated()));

        UUID secondId = UUID.fromString(second.get("reading").get("id").asString());
        assertThat(secondId).isNotEqualTo(first);
        assertThat(second.get("supersededReadingIds").get(0).asString()).isEqualTo(first.toString());
        assertThat(second.get("reading").get("hasPhoto").asBoolean()).isTrue();
        assertThat(readingStatus(first)).isEqualTo("SUPERSEDED");
        // the same value again without a photo changes nothing: the pending reading keeps its photo
        JsonNode again = body(submitWithPhoto(member, plot, submission(meter, "2026-10-15", "8210"), null)
                .andExpect(status().isCreated()));
        assertThat(again.get("reading").get("id").asString()).isEqualTo(secondId.toString());
        assertThat(again.get("reading").get("hasPhoto").asBoolean()).isTrue();
        // a second photo again: a new submission replaces the pending one
        UUID third = saved(submitWithPhoto(member, plot, submission(meter, "2026-10-15", "8210"),
                photo("meter.png", "image/png", TestImages.png())));
        assertThat(readingStatus(secondId)).isEqualTo("SUPERSEDED");
        assertThat(readingStatus(third)).isEqualTo("PENDING");
        assertThat(photoCount()).isEqualTo(2);
    }

    @Test
    void theAuditEntrySaysWhetherAPhotoCameButNeverHoldsTheFile() throws Exception {
        saved(submitWithPhoto(member, plot, submission(meter, "2026-10-15", "8210"),
                photo("meter.jpg", "image/jpeg", TestImages.cameraJpeg(false))));
        saved(submitWithPhoto(member, plot, submission(meter, "2026-10-16", "8211"), null));
        saved(submitWithPhoto(member, plot, submission(meter, "2026-10-16", "8211"),
                photo("meter.png", "image/png", TestImages.png())));

        List<Map<String, Object>> entries = awaitEntries(meter, "READING_SUBMITTED", 3);
        assertThat(changes(entries.get(0))).containsExactly(new Change("takenOn", null, "2026-10-15"),
                new Change("value", null, "8210.000"), new Change("photo", null, "true"));
        assertThat(changes(entries.get(1))).containsExactly(new Change("takenOn", null, "2026-10-16"),
                new Change("value", null, "8211.000"), new Change("photo", null, "false"));
        assertThat(changes(entries.get(2))).containsExactly(new Change("takenOn", null, "2026-10-16"),
                new Change("value", "8211.000", "8211.000"), new Change("photo", "false", "true"));
        assertThat(entries).allSatisfy(entry -> assertThat((String) entry.get("changes"))
                .doesNotContain(TestImages.CAMERA, "IMG", "meter.jpg", "meter.png"));
    }

    @Test
    void theRulesAndAnswersOfTheSubmissionStayTheSame() throws Exception {
        UUID other = newPlot(STREET);
        UUID otherMeter = register(plotMeter(other, "ELECTRICITY", "MT-ФОТО-2", "2026-06-01", "0"));

        submitWithPhoto(member, other, submission(otherMeter, "2026-10-15", "10"), jpeg())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        submitWithPhoto(member, plot, submission(otherMeter, "2026-10-15", "10"), jpeg())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errors[0].field").value("meterId"));
        submitWithPhoto(chairman(), plot, submission(meter, "2026-10-15", "8210"), jpeg())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PLOT_NOT_FOUND"));
        submitWithPhoto(otherChairman(), plot, submission(meter, "2026-10-15", "8210"), jpeg())
                .andExpect(status().isNotFound());
        submitWithPhoto(operator(), plot, submission(meter, "2026-10-15", "8210"), jpeg())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        submitWithPhoto(member, plot, submission(meter, "2026-10-15", "8100"), jpeg())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("READING_OUT_OF_ORDER"));
        submitWithPhoto(member, plot, submission(meter, "2026-10-15", "8.1234"), jpeg())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("METER_VALUE_INVALID"));
        mvc.perform(multipart("/api/v1/plots/" + plot + "/reading-submissions")).andExpect(status().isUnauthorized());

        assertThat(readingCount(meter)).isEqualTo(1);
        assertThat(readingCount(otherMeter)).isZero();
        assertThat(photoCount()).isZero();
    }

    @Test
    void theDeprecatedJsonOperationStillSubmitsAReadingWithoutAPhoto() throws Exception {
        JsonNode saved = body(send(member, post("/api/v1/plots/" + plot + "/readings"),
                submission(meter, "2026-10-15", "8210")).andExpect(status().isCreated()));

        assertThat(saved.get("reading").get("status").asString()).isEqualTo("PENDING");
        assertThat(saved.get("reading").get("hasPhoto").asBoolean()).isFalse();
        assertThat(photoCount()).isZero();
        assertThat(changes(awaitEntries(meter, "READING_SUBMITTED", 1).getFirst()))
                .contains(new Change("photo", null, "false"));
    }

    // ---- helpers

    private static MockMultipartFile jpeg() {
        return photo("meter.jpg", "image/jpeg", TestImages.jpeg(false));
    }

    private void expectRefused(MockMultipartFile file, int status, String code)
            throws Exception {
        ResultActions answer = submitWithPhoto(member, plot, submission(meter, "2026-10-15", "8210"), file);

        answer.andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.errors[0].field").value("photo"))
                .andExpect(jsonPath("$.errors[0].code").value(code));
        assertThat(readingCount(meter)).as("no reading is saved").isEqualTo(1);
        assertThat(photoCount()).isZero();
        assertThat(entries(meter, "READING_SUBMITTED")).isEmpty();
    }
}
