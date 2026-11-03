package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The member account's «Сповіщення» (feature 017 §2.2, §2.3): the channels a member may choose (paper always, e-mail
 * with an e-mail of their own and the cooperative's mailbox, Telegram with the cooperative's bot), the choice itself,
 * which only ever changes the signed-in member, the Telegram link offer, and the Telegram flag on the member card.
 */
class MeNotificationsTests extends BotTestSupport {

    @Test
    void withoutMailboxOrBotOnlyPaperIsAvailable() throws Exception {
        Owner owner = owner("Зоя", 0);

        myNotifications(owner.bearer()).andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("PAPER"))
                .andExpect(jsonPath("$.available", contains("PAPER")))
                .andExpect(jsonPath("$.telegramLinked").value(false))
                .andExpect(jsonPath("$.botAvailable").value(false));
    }

    @Test
    void theBotOffersTelegramAndTheMailboxOffersEmailToAMemberWithAnEmail() throws Exception {
        Owner owner = owner("Юрій", 0);
        save(chairman(), Map.of("telegram", Map.of("botToken", TOKEN))).andExpect(status().isOk());

        myNotifications(owner.bearer()).andExpect(jsonPath("$.available", contains("TELEGRAM", "PAPER")))
                .andExpect(jsonPath("$.botAvailable").value(true));

        save(chairman(), Map.of("email", mailbox("smtp.coop.example", 587, null, null))).andExpect(status().isOk());

        myNotifications(owner.bearer()).andExpect(jsonPath("$.available", contains("EMAIL", "TELEGRAM", "PAPER")));
    }

    @Test
    void aMemberWithoutAnEmailIsNotOfferedEmail() throws Exception {
        Owner owner = owner("Ліна", 0);
        save(chairman(), Map.of("email", mailbox("smtp.coop.example", 587, null, null))).andExpect(status().isOk());
        String bearer = owner.bearer();
        myNotifications(bearer).andExpect(status().isOk()); // signs in (links the subject) while the e-mail is there
        insertContacts(DEMO_TENANT, owner.id(), null);

        myNotifications(bearer).andExpect(jsonPath("$.available", contains("PAPER")));
        choose(bearer, "EMAIL").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("channel"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
    }

    @Test
    void theMemberChoosesAnAvailableChannelAndTheBoardSeesIt() throws Exception {
        Owner owner = owner("Дмитро", 0);
        save(chairman(), Map.of("email", mailbox("smtp.coop.example", 587, null, null),
                "telegram", Map.of("botToken", TOKEN))).andExpect(status().isOk());

        choose(owner.bearer(), "EMAIL").andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("EMAIL"));
        choose(owner.bearer(), "TELEGRAM").andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("TELEGRAM"));

        card(chairman(), owner.id()).andExpect(jsonPath("$.deliveryChannel").value("TELEGRAM"))
                .andExpect(jsonPath("$.telegramUsername").doesNotExist());
        List<Map<String, Object>> audit = memberAudit(owner.id(), "MEMBER_UPDATED", 2);
        assertThat(audit).extracting(entry -> (String) entry.get("changes"))
                .allSatisfy(changes -> assertThat(changes).contains("deliveryChannel"));
        // the same channel again is no change
        choose(owner.bearer(), "TELEGRAM").andExpect(status().isOk());
        assertThat(memberAudit(owner.id(), "MEMBER_UPDATED", 2)).hasSize(2);
    }

    @Test
    void aChannelThatIsNotAvailableIsRefused() throws Exception {
        Owner owner = owner("Ярослав", 0);

        for (String channel : List.of("EMAIL", "TELEGRAM", "VIBER")) {
            choose(owner.bearer(), channel).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("channel"));
        }
        myNotifications(owner.bearer()).andExpect(jsonPath("$.channel").value("PAPER"));
    }

    @Test
    void aMemberChangesOnlyTheirOwnChannel() throws Exception {
        Owner one = owner("Перший", 0);
        Owner other = owner("Другий", 0);
        save(chairman(), Map.of("telegram", Map.of("botToken", TOKEN))).andExpect(status().isOk());

        // the body names no member: whatever is sent, the signed-in member is the one changed
        mvc.perform(put("/api/v1/me/notifications").header(HttpHeaders.AUTHORIZATION, one.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"TELEGRAM\",\"memberId\":\"" + other.id() + "\"}"))
                .andExpect(status().isOk());

        myNotifications(one.bearer()).andExpect(jsonPath("$.channel").value("TELEGRAM"));
        myNotifications(other.bearer()).andExpect(jsonPath("$.channel").value("PAPER"));
    }

    @Test
    void thePlatformOperatorHasNoChoices() throws Exception {
        myNotifications(operator()).andExpect(status().isForbidden());
        choose(operator(), "PAPER").andExpect(status().isForbidden());
        createLink(operator()).andExpect(status().isForbidden());
        deleteLink(operator()).andExpect(status().isForbidden());
    }

    @Test
    void aLinkNeedsABot() throws Exception {
        Owner owner = owner("Ніна", 0);

        createLink(owner.bearer()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TELEGRAM_NOT_CONFIGURED"));
    }

    @Test
    void aLinkNeedsABotTelegramKnows() throws Exception {
        Owner owner = owner("Вадим", 0);
        save(chairman(), Map.of("telegram", Map.of("botToken", "bad654321:AAtokenTelegramRefusesInTheTests")))
                .andExpect(status().isOk());

        createLink(owner.bearer()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TELEGRAM_NOT_CONFIGURED"));
        Integer codes = inTenant(DEMO_TENANT, sql -> sql.queryForObject("select count(*) from telegram_link_code",
                Integer.class));
        assertThat(codes).isZero();
    }

    @Test
    void theMemberCardShowsOnlyWhetherTelegramIsLinked() throws Exception {
        Owner owner = owner("Остап", 0);
        card(chairman(), owner.id()).andExpect(jsonPath("$.telegramLinked").value(false));
        card(owner.bearer(), owner.id()).andExpect(jsonPath("$.telegramLinked").value(false));

        link(DEMO_TENANT, owner.id(), 7);

        card(chairman(), owner.id()).andExpect(jsonPath("$.telegramLinked").value(true));
        card(owner.bearer(), owner.id()).andExpect(jsonPath("$.telegramLinked").value(true));
    }

    private ResultActions choose(String bearer, String channel) throws Exception {
        return mvc.perform(put("/api/v1/me/notifications").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"" + channel + "\"}"));
    }

    private ResultActions card(String bearer, UUID member) throws Exception {
        return mvc.perform(get("/api/v1/members/" + member).header(HttpHeaders.AUTHORIZATION, bearer));
    }
}
