package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.files.TestImages;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * The board and the electrician enter one reading with a photo of the meter (feature 021 §2.3) through
 * {@code POST /reading-entries}: it counts at once with its photo, stored without metadata; {@code READINGS_ENTER} is
 * required; the photo checks of a member's submission apply (413/400, nothing saved), and so do the refusals of a single
 * entry; a photo with the same value and date replaces the counted reading; the audit entry says {@code photo}
 * {@code true}, never the file.
 */
class ReadingEntryPhotoTests extends ReadingPhotoSupport {

    private static final String ENTRIES = "/api/v1/reading-entries";

    @Test
    void theBoardEntersAReadingWithAPhotoThatCountsAtOnce() throws Exception {
        byte[] camera = TestImages.cameraJpeg(false);

        JsonNode saved = body(enterWithPhoto(chairman(), row(meter, "2026-10-15", "8210"),
                photo("IMG_0002.jpg", "image/jpeg", camera)).andExpect(status().isCreated()));

        JsonNode reading = saved.get("reading");
        assertThat(reading.get("status").asString()).isEqualTo("APPROVED");
        assertThat(reading.get("source").asString()).isEqualTo("ENTERED");
        assertThat(reading.get("value").asString()).isEqualTo("8210.000");
        assertThat(reading.get("hasPhoto").asBoolean()).isTrue();
        assertThat(saved.get("supersededReadingIds")).isEmpty();
        UUID id = UUID.fromString(reading.get("id").asString());
        byte[] stored = storedPhoto(id);
        assertThat(stored).isNotNull();
        for (String marker : TestImages.MARKERS) {
            assertThat(TestImages.contains(stored, marker)).as(marker).isFalse();
        }
        assertThat(ownerJdbc().queryForObject("select content_type from reading_photo where reading_id = ?",
                String.class, id)).isEqualTo("image/jpeg");
        // the owner of the plot sees the photo with the reading and can open it
        assertThat(historyRow(member, id).get("hasPhoto").asBoolean()).isTrue();
        download(member, id).andExpect(status().isOk());

        List<Map<String, Object>> entries = awaitEntries(meter, "READING_ENTERED", 2);
        assertThat(changes(entries.get(1))).containsExactly(new Change("takenOn", null, "2026-10-15"),
                new Change("value", null, "8210.000"), new Change("photo", null, "true"));
        assertThat((String) entries.get(1).get("changes")).doesNotContain(TestImages.CAMERA, "IMG", ".jpg");
    }

    @Test
    void anElectricianEntersAReadingWithAPhoto() throws Exception {
        String electrician = customRoleToken("READINGS_ENTER");

        UUID id = saved(enterWithPhoto(electrician, row(meter, "2026-10-15", "8210"),
                photo("meter.png", "image/png", TestImages.png())));

        assertThat(readingStatus(id)).isEqualTo("APPROVED");
        assertThat(storedPhoto(id)).isNotNull();
    }

    @Test
    void withoutReadingsEnterTheEntryIsRefusedAndNothingIsSaved() throws Exception {
        for (String bearer : List.of(member, customRoleToken("READINGS_APPROVE"), demo("treasurer@demo.local"),
                operator())) {
            enterWithPhoto(bearer, row(meter, "2026-10-15", "8210"), jpeg())
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        mvc.perform(multipart(ENTRIES)).andExpect(status().isUnauthorized());

        assertThat(readingCount(meter)).isEqualTo(1);
        assertThat(photoCount()).isZero();
    }

    @Test
    void aPhotoWithTheSameValueAndDateReplacesTheCountedReading() throws Exception {
        UUID counted = ownerJdbc().queryForObject("select id from meter_reading where meter_id = ? "
                + "and taken_on = date '2026-10-05' and status = 'APPROVED'", UUID.class, meter);

        JsonNode saved = body(enterWithPhoto(chairman(), row(meter, "2026-10-05", "8205"), jpeg())
                .andExpect(status().isCreated()));

        UUID id = UUID.fromString(saved.get("reading").get("id").asString());
        assertThat(id).isNotEqualTo(counted);
        assertThat(saved.get("supersededReadingIds").get(0).asString()).isEqualTo(counted.toString());
        assertThat(readingStatus(counted)).isEqualTo("SUPERSEDED");
        assertThat(readingStatus(id)).isEqualTo("APPROVED");
        assertThat(changes(awaitEntries(meter, "READING_REPLACED", 1).getFirst())).containsExactly(
                new Change("takenOn", "2026-10-05", "2026-10-05"), new Change("value", "8205.000", "8205.000"),
                new Change("photo", "false", "true"));
    }

    @Test
    void aPhotoThatIsNotAReadableJpegOrPngOrTooLargeIsRefusedAndNothingIsSaved() throws Exception {
        expectRefused(photo("receipt.pdf", "application/pdf", TestImages.pdf()), 400, "FILE_TYPE_NOT_ALLOWED");
        expectRefused(photo("broken.png", "image/png", Arrays.copyOf(TestImages.png(), 40)), 400,
                "FILE_TYPE_NOT_ALLOWED");
        expectRefused(photo("big.jpg", "image/jpeg", Arrays.copyOf(TestImages.jpeg(false), 5 * 1024 * 1024 + 1)),
                413, "FILE_TOO_LARGE");
        // the photo is required here: without it the request is malformed
        enterWithPhoto(chairman(), row(meter, "2026-10-15", "8210"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        assertThat(readingCount(meter)).isEqualTo(1);
        assertThat(photoCount()).isZero();
    }

    @Test
    void theRefusalsOfASingleEntryApplyAndNothingIsSaved() throws Exception {
        enterWithPhoto(chairman(), row(meter, "2026-10-15", "8100"), jpeg())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_OUT_OF_ORDER"))
                .andExpect(jsonPath("$.errors[0].field").value("value"));
        enterWithPhoto(chairman(), row(meter, kyivToday().plusDays(1).toString(), "8300"), jpeg())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_DATE_IN_FUTURE"))
                .andExpect(jsonPath("$.errors[0].field").value("takenOn"));
        enterWithPhoto(chairman(), row(meter, "2026-05-01", "7000"), jpeg())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_DATE_OUTSIDE_METER"));
        enterWithPhoto(chairman(), row(UUID.randomUUID(), "2026-10-15", "8210"), jpeg())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].field").value("meterId"));
        enterWithPhoto(otherChairman(), row(meter, "2026-10-15", "8210"), jpeg())
                .andExpect(status().isNotFound());
        enterWithPhoto(chairman(), row(meter, "2026-10-15", "8.1234"), jpeg())
                .andExpect(status().isBadRequest());

        assertThat(readingCount(meter)).isEqualTo(1);
        assertThat(photoCount()).isZero();
    }

    // ---- helpers

    private static MockMultipartFile jpeg() {
        return photo("meter.jpg", "image/jpeg", TestImages.jpeg(false));
    }

    /** Enters through the multipart operation, the {@code reading} part without a content type as the web sends it. */
    private ResultActions enterWithPhoto(String bearer, Map<String, Object> reading, MockMultipartFile photo)
            throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(ENTRIES);
        request.part(new MockPart("reading", json.writeValueAsBytes(reading)));
        if (photo != null) {
            request.file(photo);
        }
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private void expectRefused(MockMultipartFile file, int status, String code) throws Exception {
        enterWithPhoto(chairman(), row(meter, "2026-10-15", "8210"), file)
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.errors[0].field").value("photo"))
                .andExpect(jsonPath("$.errors[0].code").value(code));
    }
}
