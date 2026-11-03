package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.notifications.Mailpit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * Announcements of the board (feature 017 §2.6, §2.7): the audience of a street is the current owners of its active
 * plots and nobody else; only the chosen channels are used (the member's own channel first, then the other chosen one
 * that reaches them, else paper); the preview counts and lists the paper members and writes nothing; the send keeps the
 * announcement with its deliveries and counts, delivers the text exactly as entered (Telegram without formatting, the
 * letter with its HTML escaped) and audits the audience and counts; the text is never in the audit log, the outbox or
 * the logs; a text over 2000 characters is refused with {@code ANNOUNCEMENT_TOO_LONG}.
 */
@ExtendWith(OutputCaptureExtension.class)
class AnnouncementsTests extends DeliveryTestSupport {

    private static final String STREET = "Вишнева";

    /** Вишнева: prefers Telegram, linked, with an e-mail. */
    private UUID linked;
    private long linkedChat;
    /** Вишнева: prefers e-mail, has one, and is linked too. */
    private UUID mailAndLinked;
    private long mailAndLinkedChat;
    /** Вишнева: prefers e-mail, has one, not linked. */
    private UUID mailOnly;
    /** Вишнева: prefers paper, though linked and with an e-mail. */
    private UUID paper;
    /** Садова: prefers Telegram, linked. */
    private UUID otherStreet;
    private long otherStreetChat;
    /** The owner of an archived plot on Вишнева. */
    private UUID archivedPlotOwner;

    @BeforeEach
    void membersOnTwoStreets() throws Exception {
        mailbox(Mailpit.PASSWORD);
        bot();
        linked = member("Ткаченко", "TELEGRAM", "tkachenko@delivery.test");
        linkedChat = link(linked);
        mailAndLinked = member("Мороз", "EMAIL", "moroz@delivery.test");
        mailAndLinkedChat = link(mailAndLinked);
        mailOnly = member("Лисенко", "EMAIL", "lysenko@delivery.test");
        paper = member("Савченко", "PAPER", "savchenko@delivery.test");
        link(paper);
        otherStreet = member("Кравчук", "TELEGRAM", "kravchuk@delivery.test");
        otherStreetChat = link(otherStreet);
        archivedPlotOwner = member("Олійник", "TELEGRAM", "oliinyk@delivery.test");
        link(archivedPlotOwner);
        plotOn(STREET, "1", linked);
        plotOn(" вишнева ", "2", mailAndLinked, mailOnly);
        plotOn(STREET, "3", paper, linked);
        plotOn("Садова", "4", otherStreet);
        UUID archived = plotOn(STREET, "5", archivedPlotOwner);
        ownerJdbc().update("update plot set archived_at = now() where id = ?", archived);
    }

    @Test
    void aStreetWithTelegramOnlyIsPreviewedWithItsPaperListAndNothingIsWritten() throws Exception {
        JsonNode preview = body(announce(chairman, true, "Збори в неділю", List.of("вишнева"), "TELEGRAM")
                .andExpect(status().isOk()));

        // the linked owner (one delivery for two plots) and the e-mail member who is linked get Telegram
        assertThat(preview.path("counts").path("telegram").asInt()).isEqualTo(2);
        assertThat(preview.path("counts").path("email").asInt()).isZero();
        assertThat(preview.path("counts").path("paper").asInt()).isEqualTo(2);
        assertThat(preview.has("announcementId")).isFalse();
        List<String> names = new ArrayList<>();
        preview.path("paperMembers").forEach(member -> names.add(member.path("name").asString()));
        assertThat(names).containsExactly("Лисенко Тест", "Савченко Тест");
        assertThat(preview.path("paperMembers").get(0).path("memberId").asString()).isEqualTo(mailOnly.toString());

        assertThat(ownerJdbc().queryForObject("select count(*) from announcement where tenant_id = ?", Integer.class,
                tenant)).isZero();
        assertThat(deliveries()).isEmpty();
        assertThat(announcementAudit()).isEmpty();
    }

    @Test
    void theStreetsLinkedMembersGetTheTextInTelegramAndTheAnnouncementIsListedWithItsCounts() throws Exception {
        String text = "Шановні власники!\n<b>Збори</b> & вибори — у неділю о 10:00 *біля* _правління_.";
        JsonNode sent = body(announce(chairman, false, text, List.of(STREET), "TELEGRAM").andExpect(status().isOk()));
        UUID id = UUID.fromString(sent.path("announcementId").asString());
        assertThat(sent.path("counts").path("telegram").asInt()).isEqualTo(2);
        assertThat(sent.path("counts").path("paper").asInt()).isEqualTo(2);

        assertThat(deliveries()).hasSize(4);
        assertThat(deliveriesOf(mailOnly)).singleElement().satisfies(row -> {
            assertThat(row.get("channel")).isEqualTo("PAPER");
            assertThat(row.get("status")).isEqualTo("TO_PRINT");
        });
        assertThat(deliveriesOf(otherStreet)).isEmpty();
        assertThat(deliveriesOf(archivedPlotOwner)).isEmpty();

        assertThat(dispatch("TELEGRAM").sent()).isEqualTo(2);
        assertThat(telegram.sent(linkedChat)).singleElement().satisfies(message -> assertThat(message.text())
                .isEqualTo(text));
        assertThat(telegram.sent(mailAndLinkedChat)).singleElement().satisfies(message -> assertThat(message.text())
                .isEqualTo(text));
        assertThat(telegram.sent(otherStreetChat)).isEmpty();
        assertThat(telegram.calls("sendMessage")).allSatisfy(call -> assertThat(call.body())
                .doesNotContain("parse_mode"));
        assertThat(mailpit.letters()).isEmpty();

        JsonNode list = body(send(chairman, get("/api/v1/announcements"), null).andExpect(status().isOk()));
        assertThat(list.path("totalItems").asLong()).isEqualTo(1);
        JsonNode item = list.path("items").get(0);
        assertThat(item.path("id").asString()).isEqualTo(id.toString());
        assertThat(item.path("authorName").asString()).isEqualTo("Бондаренко Іван");
        assertThat(item.path("streets").get(0).asString()).isEqualTo(STREET);
        assertThat(item.path("channels").get(0).asString()).isEqualTo("TELEGRAM");
        assertThat(item.path("counts").path("sent").asInt()).isEqualTo(2);
        assertThat(item.path("counts").path("failed").asInt()).isZero();
        assertThat(item.path("counts").path("queued").asInt()).isZero();
        assertThat(item.path("counts").path("paper").asInt()).isEqualTo(2);
        assertThat(item.has("text")).isFalse();

        send(chairman, get("/api/v1/announcements/" + id), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(text))
                .andExpect(jsonPath("$.deliveries.totalItems").value(4))
                .andExpect(jsonPath("$.deliveries.items[0].kind").value("ANNOUNCEMENT"))
                .andExpect(jsonPath("$.deliveries.items[0].announcementId").value(id.toString()));
        listDeliveries(chairman, "kind=ANNOUNCEMENT").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(4));
        listDeliveries(chairman, "kind=INVOICE").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void anAnnouncementToEveryMemberByEmailGoesThroughTheMailboxWithItsHtmlEscaped() throws Exception {
        UUID archivedMember = member("Архівний", "EMAIL", "archived@delivery.test");
        ownerJdbc().update("update member set archived_at = now() where id = ?", archivedMember);
        String text = "Відключення води 3 листопада.\n<script>alert('x')</script> & «Правління»";

        JsonNode sent = body(announce(chairman, false, text, List.of(), "EMAIL").andExpect(status().isOk()));

        // every active member with an e-mail gets it by e-mail, whatever messenger they prefer, except paper
        for (UUID member : List.of(linked, mailAndLinked, mailOnly, otherStreet, archivedPlotOwner)) {
            assertThat(deliveriesOf(member)).singleElement().satisfies(row -> assertThat(row.get("channel"))
                    .isEqualTo("EMAIL"));
        }
        assertThat(deliveriesOf(paper)).singleElement().satisfies(row -> assertThat(row.get("channel"))
                .isEqualTo("PAPER"));
        assertThat(deliveriesOf(archivedMember)).isEmpty();
        // the chairman and the treasurer are members too; they kept the default paper
        assertThat(sent.path("counts").path("email").asInt()).isEqualTo(5);
        assertThat(sent.path("counts").path("telegram").asInt()).isZero();
        assertThat(sent.path("counts").path("paper").asInt()).isEqualTo(3);

        assertThat(dispatch("EMAIL").sent()).isEqualTo(5);
        assertThat(telegram.sent()).isEmpty();
        Mailpit.Letter letter = mailpit.lettersTo("lysenko@delivery.test").getFirst();
        assertThat(letter.subject()).isEqualTo("Оголошення правління СТ «Розсилка»");
        assertThat(mailpit.text(letter.id())).contains(text, "Правління СТ «Розсилка»");
        assertThat(mailpit.html(letter.id()))
                .contains("Відключення води 3 листопада.<br>")
                .contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp;")
                .doesNotContain("<script>");
        assertThat(mailpit.attachments(letter.id())).isEmpty();
    }

    @Test
    void bothChannelsFollowTheMembersOwnChannelFirst() throws Exception {
        JsonNode preview = body(announce(chairman, true, "Нагадування", List.of(STREET, "САДОВА"), "EMAIL",
                "TELEGRAM").andExpect(status().isOk()));

        // Telegram: the linked owner and the Садова owner; e-mail: both e-mail members; paper: the paper member
        assertThat(preview.path("counts").path("telegram").asInt()).isEqualTo(2);
        assertThat(preview.path("counts").path("email").asInt()).isEqualTo(2);
        assertThat(preview.path("counts").path("paper").asInt()).isEqualTo(1);
        assertThat(preview.path("paperMembers").get(0).path("memberId").asString()).isEqualTo(paper.toString());
    }

    @Test
    void aTextOverTwoThousandCharactersIsRefusedAndNothingIsWritten() throws Exception {
        announce(chairman, false, "я".repeat(2001), List.of(), "TELEGRAM")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANNOUNCEMENT_TOO_LONG"))
                .andExpect(jsonPath("$.errors[0].field").value("text"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        announce(chairman, true, "я".repeat(2001), List.of(), "TELEGRAM")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANNOUNCEMENT_TOO_LONG"));
        // characters, not UTF-16 units: 2000 sunflowers are 4000 units and fit; the spaces around do not count
        announce(chairman, true, "🌻".repeat(2000), List.of(), "TELEGRAM").andExpect(status().isOk());
        announce(chairman, true, "  " + "я".repeat(2000) + "\n", List.of(), "TELEGRAM").andExpect(status().isOk());
        announce(chairman, false, "  \n ", List.of(), "TELEGRAM")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("text"));
        announce(chairman, false, "Текст", List.of(), new String[0])
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        announce(chairman, false, "Текст", List.of(" "), "TELEGRAM")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("streets"));

        assertThat(ownerJdbc().queryForObject("select count(*) from announcement where tenant_id = ?", Integer.class,
                tenant)).isZero();
        assertThat(deliveries()).isEmpty();
    }

    @Test
    void theTextIsNeverInTheAuditLogTheOutboxOrTheLogs(CapturedOutput output) throws Exception {
        String marker = "Таємниця-" + UUID.randomUUID();
        String table = "outbox_copy_" + UUID.randomUUID().toString().replace("-", "");
        ownerJdbc().execute("create table " + table + " (event_type text, serialized_event text)");
        ownerJdbc().execute("create function " + table + "() returns trigger language plpgsql security definer as $$ "
                + "begin insert into " + table + " values (new.event_type, new.serialized_event); return new; end; $$");
        ownerJdbc().execute("create trigger " + table + " after insert on event_publication for each row "
                + "execute function " + table + "()");
        try {
            JsonNode sent = body(announce(chairman, false, "Оголошення: " + marker, List.of(STREET), "TELEGRAM",
                    "EMAIL").andExpect(status().isOk()));
            dispatch("TELEGRAM");
            dispatch("EMAIL");
            assertThat(telegram.sent(linkedChat)).singleElement().satisfies(message -> assertThat(message.text())
                    .isEqualTo("Оголошення: " + marker));

            List<Map<String, Object>> audit = announcementAudit();
            assertThat(audit).singleElement().satisfies(entry -> {
                assertThat(entry.get("entity_type")).isEqualTo("ANNOUNCEMENT");
                assertThat(entry.get("entity_id")).isEqualTo(UUID.fromString(sent.path("announcementId").asString()));
                assertThat(entry.get("entity_label")).isEqualTo("announcement");
                assertThat((String) entry.get("changes"))
                        .contains("audience", "STREETS", "streets", STREET, "channels", "telegram", "email", "paper")
                        .doesNotContain(marker, "Оголошення:");
            });
            List<String> events = ownerJdbc().queryForList("select serialized_event from " + table, String.class);
            assertThat(events).isNotEmpty().allSatisfy(event -> assertThat(event).doesNotContain(marker));
            assertThat(output.getAll()).doesNotContain(marker);
        } finally {
            ownerJdbc().execute("drop trigger if exists " + table + " on event_publication");
            ownerJdbc().execute("drop function if exists " + table + "()");
            ownerJdbc().execute("drop table if exists " + table);
        }
    }

    @Test
    void aFailedAnnouncementDeliveryCanBeSentAgain() throws Exception {
        body(announce(chairman, false, "Повідомлення", List.of(STREET), "TELEGRAM").andExpect(status().isOk()));
        telegram.block(linkedChat);
        dispatch("TELEGRAM");
        UUID failed = (UUID) deliveriesOf(linked).getFirst().get("id");
        assertThat(deliveriesOf(linked).getFirst()).containsEntry("status", "FAILED")
                .containsEntry("failure_code", "TELEGRAM_BLOCKED");

        JsonNode list = body(send(chairman, get("/api/v1/announcements"), null).andExpect(status().isOk()));
        assertThat(list.path("items").get(0).path("counts").path("failed").asInt()).isEqualTo(1);
        assertThat(list.path("items").get(0).path("counts").path("sent").asInt()).isEqualTo(1);
        retry(chairman, failed).andExpect(status().isNoContent());
        assertThat(deliveriesOf(linked).getFirst()).containsEntry("status", "QUEUED");
    }

    // ---- helpers

    private ResultActions announce(String bearer, boolean preview, String text, List<String> streets,
            String... channels) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        body.put("streets", streets);
        body.put("channels", List.of(channels));
        return send(bearer, post("/api/v1/announcements?preview=" + preview), body);
    }

    /** The audit entries ANNOUNCEMENT_SENT of the cooperative, after the outbox delivered what was published. */
    private List<Map<String, Object>> announcementAudit() {
        await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                "select count(*) from event_publication where completion_date is null and serialized_event like ?",
                Integer.class, "%" + tenant + "%") == 0);
        return ownerJdbc().queryForList("select entity_type, entity_id, entity_label, cast(changes as text) as changes "
                + "from audit_entry where tenant_id = ? and action = 'ANNOUNCEMENT_SENT'", tenant);
    }
}
