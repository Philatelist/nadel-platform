package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * Who may send and read announcements (feature 017 §2.6, ADR 0009): only a holder of {@code ANNOUNCEMENTS_SEND} (the
 * chairman by default); the treasurer, a member and the platform operator get 403 and nothing is written; another
 * cooperative neither lists nor opens this one's announcement (404).
 */
class AnnouncementsAccessTests extends DeliveryTestSupport {

    private static final Map<String, Object> BODY = Map.of("text", "Збори в неділю", "streets", List.of(),
            "channels", List.of("TELEGRAM"));

    private UUID announcement;

    @BeforeEach
    void oneAnnouncement() throws Exception {
        bot();
        UUID owner = member("Ткаченко", "TELEGRAM", "tkachenko@delivery.test");
        link(owner);
        plot("1", owner);
        JsonNode sent = body(send(chairman, post("/api/v1/announcements?preview=false"), BODY)
                .andExpect(status().isOk()));
        announcement = UUID.fromString(sent.path("announcementId").asString());
    }

    @Test
    void theChairmanPreviewsSendsListsAndOpens() throws Exception {
        send(chairman, post("/api/v1/announcements?preview=true"), BODY).andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.telegram").value(1));
        send(chairman, get("/api/v1/announcements"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1));
        send(chairman, get("/api/v1/announcements/" + announcement), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Збори в неділю"));
    }

    @Test
    void theTreasurerAMemberAndTheOperatorAreRefused() throws Exception {
        String member = person("MEMBER", "Член", "Простий", "plain-" + UUID.randomUUID() + "@delivery.test");
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        for (String bearer : new String[] {treasurer, member, operator}) {
            denied(send(bearer, post("/api/v1/announcements?preview=false"), BODY));
            denied(send(bearer, post("/api/v1/announcements?preview=true"), BODY));
            denied(send(bearer, get("/api/v1/announcements"), null));
            denied(send(bearer, get("/api/v1/announcements/" + announcement), null));
        }
        assertThat(ownerJdbc().queryForObject("select count(*) from announcement where tenant_id = ?", Integer.class,
                tenant)).isEqualTo(1);
        // the owner by Telegram, the chairman and the treasurer on paper
        assertThat(deliveries()).hasSize(3);
    }

    @Test
    void anotherCooperativeNeitherListsNorOpensThisOnesAnnouncement() throws Exception {
        String email = "other-chairman-" + UUID.randomUUID() + "@other.local";
        UUID other = newMember(OTHER_TENANT, email);
        giveBaseRole(OTHER_TENANT, other, "CHAIRMAN");
        String otherChairman = TestTokens.bearer(OTHER_TENANT, "sub-" + email, email);

        send(otherChairman, get("/api/v1/announcements"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + announcement + "')]").isEmpty());
        send(otherChairman, get("/api/v1/announcements/" + announcement), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private static void denied(ResultActions call) throws Exception {
        call.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }
}
