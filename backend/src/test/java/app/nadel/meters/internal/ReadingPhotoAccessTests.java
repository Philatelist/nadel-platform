package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import app.nadel.files.TestImages;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;

/**
 * Who may open the photo of a reading ({@code GET /readings/{id}/photo}, feature 021 §2.3): a current owner of the plot,
 * a user who enters readings and a user who approves them; anyone else and every user of another cooperative are
 * refused as if it did not exist, the platform operator gets 403. The photo is served inline, never cached and never
 * sniffed. A rejected, superseded or approved reading keeps its photo, and the history and the approvals list say which
 * readings have one.
 */
class ReadingPhotoAccessTests extends ReadingPhotoSupport {

    private UUID reading;
    private byte[] stored;

    @BeforeEach
    void submitWithAPhoto() throws Exception {
        reading = saved(submitWithPhoto(member, plot, submission(meter, "2026-10-15", "8210"),
                photo("meter.jpg", "image/jpeg", TestImages.cameraJpeg(false))));
        stored = storedPhoto(reading);
    }

    @Test
    void theOwnerTheElectricianTheApproverAndTheChairmanOpenThePhoto() throws Exception {
        String electrician = customRoleToken("READINGS_ENTER");
        String approver = customRoleToken("READINGS_APPROVE");

        for (String bearer : new String[] {member, electrician, approver, chairman()}) {
            download(bearer, reading).andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, stored.length))
                    .andExpect(content().bytes(stored));
        }
    }

    @Test
    void anotherMemberAndAnotherCooperativeAreRefusedAsIfThePhotoDidNotExist() throws Exception {
        RoleMember neighbour = customRoleMember("METERS_VIEW"); // sees every meter, but neither enters nor approves
        String stranger = TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), customRoleMember().email());

        for (String bearer : new String[] {stranger, TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(),
                neighbour.email()), demo("treasurer@demo.local"), otherChairman()}) {
            download(bearer, reading).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        download(member, UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void thePlatformOperatorIsForbiddenAndAnAnonymousCallerUnauthorized() throws Exception {
        download(operator(), reading).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mvc.perform(get(READINGS + "/" + reading + "/photo")).andExpect(status().isUnauthorized());
    }

    @Test
    void aReadingWithoutAPhotoHasNoneToOpen() throws Exception {
        UUID counted = entered(meter, "2026-10-20", "8230");

        download(chairman(), counted).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(historyRow(chairman(), counted).get("hasPhoto").asBoolean()).isFalse();
    }

    @Test
    void theApprovalsListSaysWhichReadingsHaveAPhoto() throws Exception {
        UUID other = newPlot(STREET);
        own(owner.id(), other);
        UUID otherMeter = register(plotMeter(other, "ELECTRICITY", "MT-ФОТО-3", "2026-06-01", "0"));
        UUID withoutPhoto = saved(submitWithPhoto(member, other, submission(otherMeter, "2026-10-15", "10"), null));

        assertThat(pendingItem(reading).get("reading").get("hasPhoto").asBoolean()).isTrue();
        assertThat(pendingItem(withoutPhoto).get("reading").get("hasPhoto").asBoolean()).isFalse();
        assertThat(historyRow(member, reading).get("hasPhoto").asBoolean()).isTrue();
    }

    @Test
    void aRejectedReadingKeepsItsPhoto() throws Exception {
        send(chairman(), post(READINGS + "/" + reading + "/reject"), Map.of("reason", "Цифри не видно"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.hasPhoto").value(true));

        JsonNode row = historyRow(chairman(), reading);
        assertThat(row.get("status").asString()).isEqualTo("REJECTED");
        assertThat(row.get("hasPhoto").asBoolean()).isTrue();
        download(chairman(), reading).andExpect(status().isOk()).andExpect(content().bytes(stored));
        download(member, reading).andExpect(status().isOk());
    }

    @Test
    void aSupersededReadingKeepsItsPhoto() throws Exception {
        UUID second = saved(submitWithPhoto(member, plot, submission(meter, "2026-10-15", "8212"),
                photo("meter.png", "image/png", TestImages.png())));

        assertThat(readingStatus(reading)).isEqualTo("SUPERSEDED");
        assertThat(historyRow(member, reading).get("hasPhoto").asBoolean()).isTrue();
        download(member, reading).andExpect(status().isOk()).andExpect(content().bytes(stored));
        download(member, second).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"));
        assertThat(pendingIds()).contains(second).doesNotContain(reading);
    }

    @Test
    void anApprovedReadingKeepsItsPhoto() throws Exception {
        send(chairman(), post(READINGS + "/" + reading + "/approve"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.reading.status").value("APPROVED"))
                .andExpect(jsonPath("$.reading.hasPhoto").value(true));

        assertThat(historyRow(chairman(), reading).get("hasPhoto").asBoolean()).isTrue();
        download(member, reading).andExpect(status().isOk()).andExpect(content().bytes(stored));
    }

    @Test
    void aFormerOwnerNoLongerOpensThePhoto() throws Exception {
        ownerJdbc().update("update ownership set valid_to = date '2026-10-31' where plot_id = ? and member_id = ?",
                plot, owner.id());

        download(member, reading).andExpect(status().isNotFound());
        download(chairman(), reading).andExpect(status().isOk());
    }
}
