package app.nadel.meters.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * What the photo tests share (feature 021 §2.3): a plot owned by a member without permissions, its electricity meter
 * with one counted reading, the multipart submission ({@code POST /plots/{id}/reading-submissions}, the {@code reading}
 * part sent without a content type as the generated web client sends it) and the download.
 */
abstract class ReadingPhotoSupport extends MeterTestSupport {

    static final String STREET = "MT вул. Фото";

    UUID plot;
    UUID meter;
    RoleMember owner;
    String member;

    @BeforeEach
    void ownPlotWithAMeter() throws Exception {
        plot = newPlot(STREET);
        owner = customRoleMember(); // a member without permissions
        own(owner.id(), plot);
        member = TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), owner.email());
        meter = register(plotMeter(plot, "ELECTRICITY", "MT-ФОТО-1", "2026-06-01", "8000"));
        entered(meter, "2026-10-05", "8205");
    }

    static Map<String, Object> submission(UUID meter, String takenOn, String value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("meterId", meter.toString());
        if (takenOn != null) {
            body.put("takenOn", takenOn);
        }
        body.put("value", value);
        return body;
    }

    static MockMultipartFile photo(String name, String contentType, byte[] content) {
        return new MockMultipartFile("photo", name, contentType, content);
    }

    /** Submits through the multipart operation, with the photo when one is given. */
    ResultActions submitWithPhoto(String bearer, UUID plotId, Map<String, Object> reading, MockMultipartFile photo)
            throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/plots/" + plotId + "/reading-submissions");
        request.part(new MockPart("reading", json.writeValueAsBytes(reading)));
        if (photo != null) {
            request.file(photo);
        }
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer));
    }

    /** The id of the reading a submission saved (201 expected). */
    UUID saved(ResultActions submission) throws Exception {
        return UUID.fromString(body(submission.andExpect(status().isCreated())).get("reading").get("id").asString());
    }

    ResultActions download(String bearer, UUID reading) throws Exception {
        return mvc.perform(get(READINGS + "/" + reading + "/photo").header(HttpHeaders.AUTHORIZATION, bearer));
    }

    /** The stored photo of the reading, read as the database owner; null when it has none. */
    byte[] storedPhoto(UUID reading) {
        List<byte[]> rows = ownerJdbc().query("select content from reading_photo where reading_id = ?",
                (rs, row) -> rs.getBytes(1), reading);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    int photoCount() {
        return ownerJdbc().queryForObject("select count(*) from reading_photo where reading_id in "
                + "(select id from meter_reading where meter_id = ?)", Integer.class, meter);
    }

    /** The history row of the reading as the viewer sees it. */
    JsonNode historyRow(String bearer, UUID reading) throws Exception {
        JsonNode history = body(send(bearer, get(METERS + "/" + meter + "/readings"), null)
                .andExpect(status().isOk()));
        for (JsonNode row : history.get("readings")) {
            if (row.get("id").asString().equals(reading.toString())) {
                return row;
            }
        }
        throw new AssertionError("The reading is not in the history: " + reading);
    }

    /** The approvals list item of the reading as the chairman sees it. */
    JsonNode pendingItem(UUID reading) throws Exception {
        JsonNode list = body(send(chairman(), get(READINGS).param("status", "PENDING"), null)
                .andExpect(status().isOk()));
        for (JsonNode item : list) {
            if (item.get("reading").get("id").asString().equals(reading.toString())) {
                return item;
            }
        }
        throw new AssertionError("The reading is not in the approvals list: " + reading);
    }

    List<UUID> pendingIds() throws Exception {
        List<UUID> ids = new ArrayList<>();
        body(send(chairman(), get(READINGS).param("status", "PENDING"), null).andExpect(status().isOk()))
                .forEach(item -> ids.add(UUID.fromString(item.get("reading").get("id").asString())));
        return ids;
    }
}
