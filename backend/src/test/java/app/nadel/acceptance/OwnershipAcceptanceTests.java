package app.nadel.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance tests of the whole feature "Ownership" (functional-spec.md of 007) through the public HTTP API: one
 * journey per section 2.1-2.8, driven as the demo people would drive it (chairman, treasurer, the demo member). The
 * plots and members a journey changes are created by the journey; the demo data is only read, except the cooperative
 * setting, which is deleted again ("never saved" is the default). Per-case rules stay in the {@code Ownership*Tests}
 * of the plots module and in {@code SettingsTests}. Criteria are named as {@code §2.x #n} in the order of the spec.
 * <pre>
 * @layer: integration
 * @spec: 007-ownership
 * @regression
 * </pre>
 */
@Tag("regression")
class OwnershipAcceptanceTests extends IntegrationTest {

    private static final String PLOTS = "/api/v1/plots";
    private static final String SETTINGS = "/api/v1/settings";
    private static final UUID DEMO_MEMBER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
    private static final String DEMO_MEMBER_EMAIL = "member@demo.local";
    private static final String NO_ACCESS = "PERMISSION_DENIED";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private final List<UUID> createdPlots = new ArrayList<>();
    private final List<UUID> touchedMembers = new ArrayList<>();
    private final List<UUID> createdRoles = new ArrayList<>();
    private final List<UUID> createdApiRoles = new ArrayList<>();
    private String chairman;
    private String treasurer;

    @BeforeEach
    void signInThePeople() {
        chairman = tokenWithBaseRole("CHAIRMAN");
        treasurer = tokenWithBaseRole("TREASURER");
    }

    @AfterEach
    void removeWhatTheJourneysCreated() {
        JdbcTemplate owner = ownerJdbc();
        for (UUID plot : createdPlots) {
            owner.update("delete from ownership where plot_id = ?", plot);
            owner.update("delete from audit_entry where entity_id = ?", plot);
            owner.update("delete from plot_status where plot_id = ?", plot);
            owner.update("delete from plot where id = ?", plot);
        }
        createdPlots.clear();
        for (UUID member : touchedMembers) {
            owner.update("delete from audit_entry where entity_id = ?", member);
        }
        touchedMembers.clear();
        for (UUID role : createdApiRoles) {
            owner.update("delete from member_role where role_id = ?", role);
            owner.update("delete from role_permission where role_id = ?", role);
            owner.update("delete from audit_entry where entity_id = ?", role);
            owner.update("delete from role where id = ?", role);
        }
        createdApiRoles.clear();
        for (UUID role : createdRoles) {
            owner.update("delete from member_role where role_id = ?", role);
            owner.update("delete from role_permission where role_id = ?", role);
            owner.update("delete from role where id = ?", role);
        }
        createdRoles.clear();
        // the demo cooperative goes back to "never saved" (own plots only); the first sign-in linked the demo member
        owner.update("delete from cooperative_settings where tenant_id = ?", DEMO_TENANT);
        owner.update("update member set keycloak_subject = null where id = ?", DEMO_MEMBER);
    }

    // ---- §2.1 Owners and shares

    /**
     * §2.1 #1-#6 and the rules above them: shares are exact fractions adding up to one whole, three owners at 1/3,
     * "2/4" is shown "1/2", the same member twice is refused on the second row, a member may own several plots, the
     * first owner turns a grey plot green and common land never has owners.
     */
    @Test
    void sharesMustAddUpToOneAreShownInLowestTermsAndGiveAPlotItsGreenStatus() throws Exception {
        UUID anna = member("Anna");
        UUID boris = member("Boris");
        UUID hanna = member("Hanna");

        // #1 less or more than one whole: refused with SHARES_NOT_ONE, nothing changes
        UUID plot = plot(chairman);
        change(chairman, plot, "2026-02-01", owners(anna, "1/2", boris, "1/3")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHARES_NOT_ONE"))
                .andExpect(jsonPath("$.errors[0].field").value("owners"))
                .andExpect(jsonPath("$.errors[0].code").value("SHARES_NOT_ONE"));
        change(chairman, plot, "2026-02-01", owners(anna, "1/2", boris, "1/2", hanna, "1/3"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHARES_NOT_ONE"));
        // "0.33 + 0.33 + 0.34" is not a valid way to enter shares
        change(chairman, plot, "2026-02-01", owners(anna, "0.33", boris, "0.33", hanna, "0.34"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("SHARE_INVALID"));
        assertThat(current(treasurer, plot)).isEmpty();
        assertThat(statusOnCard(treasurer, plot)).isEqualTo("GREY");

        // #2 three owners at 1/3 each are one whole; the card shows each owner with 1/3
        change(chairman, plot, "2026-02-01", owners(anna, "1/3", boris, "1/3", hanna, "1/3"))
                .andExpect(status().isCreated());
        assertThat(shares(current(treasurer, plot))).containsExactly("1/3", "1/3", "1/3");
        assertThat(statusOnCard(treasurer, plot)).isEqualTo("GREEN");

        // #3 "2/4" is saved and shown as "1/2"
        UUID halves = plot(chairman);
        change(chairman, halves, "2026-02-01", owners(anna, "2/4", boris, "3/6")).andExpect(status().isCreated());
        assertThat(shares(current(treasurer, halves))).containsExactly("1/2", "1/2");

        // #4 the same member twice: refused with a message on the second row
        UUID twice = plot(chairman);
        change(chairman, twice, "2026-02-01", owners(anna, "1/2", anna, "1/2")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_OWNER"))
                .andExpect(jsonPath("$.errors[0].field").value("owners[1].memberId"))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE_OWNER"));
        assertThat(current(treasurer, twice)).isEmpty();

        // #5 a plot without owner is grey in the list and on the card; its first owner turns it green in both
        UUID grey = plot(chairman);
        String number = numberOf(grey);
        assertThat(statusInList(treasurer, number)).isEqualTo("GREY");
        assertThat(statusOnCard(treasurer, grey)).isEqualTo("GREY");
        change(chairman, grey, "2026-02-01", owners(anna, "1/1")).andExpect(status().isCreated());
        assertThat(statusInList(treasurer, number)).isEqualTo("GREEN");
        assertThat(statusOnCard(treasurer, grey)).isEqualTo("GREEN");

        // a member may own several plots at once, each with its own share
        JsonNode annasPlots = body(get("/api/v1/members/" + anna + "/ownerships"), chairman);
        assertThat(shares(annasPlots.get("current"), "share")).containsExactlyInAnyOrder("1/3", "1/2", "1/1");

        // #6 common land never has owners: a change sent anyway is refused (the demo road and a created one)
        UUID road = plot(chairman, "COMMON", "0");
        card(treasurer, road).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("GREY"));
        change(chairman, road, "2026-02-01", owners(anna, "1/1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_COMMON_LAND"));
        UUID demoRoad = demoPlot("R-1");
        change(chairman, demoRoad, "2026-02-01", owners(anna, "1/1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_COMMON_LAND"));
        assertThat(current(treasurer, road)).isEmpty();
        assertThat(current(treasurer, demoRoad)).isEmpty();
    }

    // ---- §2.2 Change of owner

    /**
     * §2.2 #1, #2, #6: a sale of a plot to two co-owners on 01.03.2026 closes the previous owner on 28.02.2026, the
     * period ends the day before on 1 January and in a leap year, and the change is one audit entry naming the date
     * and the new owners with shares.
     */
    @Test
    void aSaleClosesThePreviousOwnerTheDayBeforeAndIsOneAuditEntryPerChange() throws Exception {
        UUID anna = member("Anna");
        UUID boris = member("Boris");
        UUID hanna = member("Hanna");

        // #1 a plot owned by one member since 01.06.2025 is sold to two members at 1/2 each on 01.03.2026
        UUID plot = plot(chairman);
        change(chairman, plot, "2025-06-01", owners(anna, "1/1")).andExpect(status().isCreated());
        change(chairman, plot, "2026-03-01", owners(boris, "1/2", hanna, "1/2"), "Sale contract")
                .andExpect(status().isCreated());
        JsonNode card = body(get(PLOTS + "/" + plot + "/ownerships"), treasurer);
        assertThat(names(card.get("current"))).containsExactlyInAnyOrder(name(boris), name(hanna));
        assertThat(card.get("current")).allSatisfy(o -> {
            assertThat(o.get("validFrom").asString()).isEqualTo("2026-03-01");
            assertThat(o.get("share").asString()).isEqualTo("1/2");
            assertThat(o.has("validTo")).isFalse();
        });
        assertThat(card.get("history")).hasSize(1);
        assertThat(card.get("history").get(0).get("memberName").asString()).isEqualTo(name(anna));
        assertThat(card.get("history").get(0).get("validFrom").asString()).isEqualTo("2025-06-01");
        assertThat(card.get("history").get(0).get("validTo").asString()).isEqualTo("2026-02-28");

        // #2 the day before 1 January is 31 December; the day before 1 March 2024 is 29 February
        String[][] dates = {{"2026-01-01", "2025-12-31"}, {"2024-03-01", "2024-02-29"}, {"2026-03-01", "2026-02-28"}};
        for (String[] pair : dates) {
            UUID other = plot(chairman);
            change(chairman, other, "2020-01-01", owners(anna, "1/1")).andExpect(status().isCreated());
            change(chairman, other, pair[0], owners(boris, "1/1")).andExpect(status().isCreated());
            JsonNode after = body(get(PLOTS + "/" + other + "/ownerships"), treasurer);
            assertThat(after.get("current").get(0).get("validFrom").asString()).isEqualTo(pair[0]);
            assertThat(after.get("history").get(0).get("validTo").asString()).isEqualTo(pair[1]);
        }

        // #6 two changes of the plot are two entries, filtered to ownership; the newest names the date and owners
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(auditItems(treasurer, "OWNERSHIP", plot)).hasSize(2));
        JsonNode newest = auditItems(treasurer, "OWNERSHIP", plot).get(0);
        assertThat(newest.get("action").asString()).isEqualTo("OWNERSHIP_CHANGED");
        assertThat(newest.get("entityLabel").asString()).isEqualTo(numberOf(plot));
        assertThat(change(newest, "effectiveFrom").get("newValue").asString()).isEqualTo("2026-03-01");
        assertThat(ownerTexts(change(newest, "owners").get("newValue").asString()))
                .containsExactlyInAnyOrder(name(boris) + " 1/2", name(hanna) + " 1/2");
        assertThat(ownerTexts(change(newest, "owners").get("oldValue").asString())).containsExactly(name(anna) + " 1/1");
    }

    /**
     * §2.2 #3, #5, #7: a future date and a date not later than the current owner's start are refused on the date,
     * removing every owner leaves a grey plot with history, and without permission to edit ownership a change is
     * refused with "You don't have access".
     */
    @Test
    void impossibleDatesAreRefusedAnEmptyOwnerListEndsOwnershipAndEditingNeedsItsOwnPermission() throws Exception {
        UUID anna = member("Anna");
        UUID boris = member("Boris");
        UUID plot = plot(chairman);
        change(chairman, plot, "2025-06-01", owners(anna, "1/1")).andExpect(status().isCreated());

        // #3 the future, the start day itself and a day before it: refused with a message under the date
        String tomorrow = LocalDate.now(ZoneId.of("Europe/Kyiv")).plusDays(1).toString();
        for (String date : List.of(tomorrow, "2025-06-01", "2025-05-01")) {
            change(chairman, plot, date, owners(boris, "1/1")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("OWNERSHIP_DATE_INVALID"))
                    .andExpect(jsonPath("$.errors[0].field").value("effectiveFrom"))
                    .andExpect(jsonPath("$.errors[0].code").value("OWNERSHIP_DATE_INVALID"));
        }
        JsonNode unchanged = body(get(PLOTS + "/" + plot + "/ownerships"), treasurer);
        assertThat(names(unchanged.get("current"))).containsExactly(name(anna));
        assertThat(unchanged.get("history")).isEmpty();

        // #7 a role that may view and edit plots but not edit ownership, the treasurer and a member are refused
        String plotsOnly = customRoleToken("PLOTS_VIEW", "PLOTS_EDIT", "MEMBERS_VIEW");
        for (String denied : List.of(plotsOnly, treasurer, tokenWithBaseRole("MEMBER"))) {
            change(denied, plot, "2026-03-01", owners(boris, "1/1")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(NO_ACCESS));
        }
        UUID annaRow = UUID.fromString(unchanged.get("current").get(0).get("id").asString());
        for (String denied : List.of(plotsOnly, treasurer)) {
            correct(denied, annaRow, anna, "1/1", "2025-06-01", null, "x", "Typo", List.of())
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(NO_ACCESS));
        }
        assertThat(names(body(get(PLOTS + "/" + plot + "/ownerships"), treasurer).get("current")))
                .containsExactly(name(anna));

        // #5 every owner row removed: grey "no owner" from that date, the former owner is in the history
        change(chairman, plot, "2026-03-01", List.of()).andExpect(status().isCreated());
        assertThat(statusOnCard(treasurer, plot)).isEqualTo("GREY");
        assertThat(statusInList(treasurer, numberOf(plot))).isEqualTo("GREY");
        JsonNode ended = body(get(PLOTS + "/" + plot + "/ownerships"), treasurer);
        assertThat(ended.get("current")).isEmpty();
        assertThat(ended.get("history")).hasSize(1);
        assertThat(ended.get("history").get(0).get("memberName").asString()).isEqualTo(name(anna));
        assertThat(ended.get("history").get(0).get("validTo").asString()).isEqualTo("2026-02-28");
    }

    // ---- §2.3 Correction

    /**
     * §2.3 #1-#4: 1/2 + 1/2 corrected to 2/3 + 1/3 in one step with the reason in the audit log; a correction that
     * breaks the sum is refused, so is one without a reason, and so is a start date that overlaps the previous owner.
     */
    @Test
    void aCorrectionNeedsAReasonKeepsTheSumAndTheTimelineAndKeepsTheOldValuesInTheAuditLog() throws Exception {
        UUID anna = member("Anna");
        UUID boris = member("Boris");
        UUID plot = plot(chairman);
        change(chairman, plot, "2020-01-01", owners(anna, "1/2", boris, "1/2")).andExpect(status().isCreated());
        JsonNode before = body(get(PLOTS + "/" + plot + "/ownerships"), treasurer);
        UUID annaRow = rowOf(before.get("current"), name(anna));
        UUID borisRow = rowOf(before.get("current"), name(boris));

        // #2 only one co-owner's share changed: the total is no longer one whole
        correct(chairman, annaRow, anna, "2/3", "2020-01-01", null, "x", "Wrong shares in the contract copy",
                List.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHARES_NOT_ONE"));
        // #3 no reason, or only spaces: "required" on the reason field, nothing changes
        for (String reason : new String[] {null, "   "}) {
            correct(chairman, annaRow, anna, "2/3", "2020-01-01", null, "x", reason,
                    List.of(coOwner(borisRow, "1/3"))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("reason"))
                    .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        }
        assertThat(shares(current(treasurer, plot))).containsExactly("1/2", "1/2");

        // #1 both shares corrected in the same step, with the reason
        String reason = "Wrong shares in the contract copy";
        correct(chairman, annaRow, anna, "2/3", "2020-01-01", null, "x", reason, List.of(coOwner(borisRow, "1/3")))
                .andExpect(status().isOk());
        JsonNode card = body(get(PLOTS + "/" + plot + "/ownerships"), treasurer);
        assertThat(sharesByName(card.get("current"))).containsEntry(name(anna), "2/3").containsEntry(name(boris), "1/3");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(auditItems(treasurer, "OWNERSHIP", plot)).hasSize(2)); // the change and the correction
        JsonNode entry = auditItems(treasurer, "OWNERSHIP", plot).get(0);
        assertThat(entry.get("action").asString()).isEqualTo("OWNERSHIP_CORRECTED");
        assertThat(ownerTexts(change(entry, "owners").get("oldValue").asString()))
                .containsExactlyInAnyOrder(name(anna) + " 1/2", name(boris) + " 1/2");
        assertThat(ownerTexts(change(entry, "owners").get("newValue").asString()))
                .containsExactlyInAnyOrder(name(anna) + " 2/3", name(boris) + " 1/3");
        assertThat(change(entry, "reason").get("newValue").asString()).isEqualTo(reason);

        // #4 the start date of the second owner moved before the end of the previous owner: refused on the date
        UUID sold = plot(chairman);
        change(chairman, sold, "2020-01-01", owners(anna, "1/1")).andExpect(status().isCreated());
        change(chairman, sold, "2023-01-01", owners(boris, "1/1")).andExpect(status().isCreated());
        UUID borisNow = rowOf(body(get(PLOTS + "/" + sold + "/ownerships"), treasurer).get("current"), name(boris));
        correct(chairman, borisNow, boris, "1/1", "2022-06-01", null, "x", "Wrong date", List.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("validFrom"))
                .andExpect(jsonPath("$.errors[0].code").value("OWNERSHIP_OVERLAP"));
        JsonNode stays = body(get(PLOTS + "/" + sold + "/ownerships"), treasurer);
        assertThat(stays.get("current").get(0).get("validFrom").asString()).isEqualTo("2023-01-01");
        assertThat(stays.get("history").get(0).get("validTo").asString()).isEqualTo("2022-12-31");
    }

    // ---- §2.4 Guards on members and plots

    /**
     * §2.4 #1-#3: a member who owns a plot and a plot with an owner cannot be archived (the demo plot 12 and its
     * owner, and created ones), a plot with history cannot be deleted (demo plot 9), and once the owner is changed to
     * nobody both can be archived while the plot still cannot be deleted.
     */
    @Test
    void anOwningMemberAndAnOwnedPlotCannotBeArchivedAndAPlotWithHistoryCannotBeDeleted() throws Exception {
        // the demo plot 12 and its owner: both refusals leave the demo data as it was
        UUID plot12 = demoPlot("12");
        UUID owner12 = UUID.fromString(body(get(PLOTS + "/" + plot12 + "/ownerships"), chairman)
                .get("current").get(0).get("memberId").asString());
        send(chairman, post("/api/v1/members/" + owner12 + "/archive"), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_OWNS_PLOT"));
        send(chairman, post(PLOTS + "/" + plot12 + "/archive"), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_OWNERS"));
        assertThat(archivedMember(owner12)).isFalse();
        assertThat(card(treasurer, plot12).andReturn().getResponse().getContentAsString()).contains("\"archived\":false");
        // #2 the demo plot 9 had an owner in the past
        send(chairman, delete(PLOTS + "/" + demoPlot("9")), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_OWNERSHIP_HISTORY"));

        // the same on created data, then the way out: change the owner to nobody
        UUID anna = member("Anna");
        UUID plot = plot(chairman);
        change(chairman, plot, "2025-01-01", owners(anna, "1/1")).andExpect(status().isCreated());
        send(chairman, post("/api/v1/members/" + anna + "/archive"), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_OWNS_PLOT"));
        send(chairman, post(PLOTS + "/" + plot + "/archive"), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_OWNERS"));
        send(chairman, delete(PLOTS + "/" + plot), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_OWNERSHIP_HISTORY"));
        touchedMembers.add(anna);

        change(chairman, plot, "2025-06-01", List.of()).andExpect(status().isCreated());
        send(chairman, post("/api/v1/members/" + anna + "/archive"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));
        send(chairman, delete(PLOTS + "/" + plot), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_HAS_OWNERSHIP_HISTORY"));
        send(chairman, post(PLOTS + "/" + plot + "/archive"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));
        // an archived member cannot be chosen as an owner of another plot
        UUID another = plot(chairman);
        change(chairman, another, "2026-01-01", owners(anna, "1/1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_ARCHIVED"));
    }

    // ---- §2.5 Where owners are shown, §2.6 What a member sees

    /**
     * §2.5 #1-#3: the treasurer reads the owners in the list and finds plots by an owner's last name, reads plot 9
     * with its history and cannot edit; the chairman reads a member's plots (owned now with the share, owned before
     * with the period).
     */
    @Test
    void theTreasurerReadsOwnersInTheListAndOnTheCardAndTheChairmanReadsAMembersPlots() throws Exception {
        // #1 each owned plot shows its owners' names; plots with two owners show both; the last name finds the plots
        JsonNode all = body(get(PLOTS + "?size=100"), treasurer);
        assertThat(ownerNames(all, "3")).hasSize(2);
        assertThat(ownerNames(all, "7")).containsExactly("Бондаренко Андрій", "Мороз Галина");
        assertThat(ownerNames(all, "1")).hasSize(1);
        assertThat(numbersOf(body(get(PLOTS + "?q=Мороз"), treasurer))).containsExactly("7");
        assertThat(numbersOf(body(get(PLOTS + "?q=Кравченко"), treasurer))).containsExactly("11");

        // #2 plot 9: the current owner, the previous owner with the period, and nothing to edit
        UUID plot9 = demoPlot("9");
        JsonNode nine = body(get(PLOTS + "/" + plot9 + "/ownerships"), treasurer);
        assertThat(nine.get("current")).hasSize(1);
        assertThat(nine.get("current").get(0).get("memberName").asString()).isEqualTo("Литвиненко Світлана");
        assertThat(nine.get("history")).hasSize(1);
        assertThat(nine.get("history").get(0).get("memberName").asString()).isEqualTo("Гончаренко Микола");
        assertThat(nine.get("history").get(0).get("validFrom").asString()).isEqualTo("2015-06-01");
        assertThat(nine.get("history").get(0).get("validTo").asString()).isEqualTo("2025-05-31");
        UUID currentRow = UUID.fromString(nine.get("current").get(0).get("id").asString());
        UUID someone = member("Anna");
        change(treasurer, plot9, "2026-03-01", owners(someone, "1/1")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(NO_ACCESS));
        correct(treasurer, currentRow, someone, "1/1", "2025-06-01", null, "x", "Typo", List.of())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(NO_ACCESS));
        assertThat(body(get(PLOTS + "/" + plot9 + "/ownerships"), treasurer).get("history")).hasSize(1);

        // #3 the member card of a member who owns one plot now and owned another one in the past
        UUID owner = member("Anna");
        UUID boris = member("Boris");
        UUID soldPlot = plot(chairman);
        UUID heldPlot = plot(chairman);
        change(chairman, soldPlot, "2019-01-01", owners(owner, "1/1")).andExpect(status().isCreated());
        change(chairman, soldPlot, "2022-05-01", owners(boris, "1/1")).andExpect(status().isCreated());
        change(chairman, heldPlot, "2022-05-01", owners(owner, "1/2", boris, "1/2")).andExpect(status().isCreated());
        JsonNode card = body(get("/api/v1/members/" + owner + "/ownerships"), chairman);
        assertThat(card.get("current")).hasSize(1);
        assertThat(card.get("current").get(0).get("plotNumber").asString()).isEqualTo(numberOf(heldPlot));
        assertThat(card.get("current").get(0).get("share").asString()).isEqualTo("1/2");
        assertThat(card.get("past")).hasSize(1);
        assertThat(card.get("past").get(0).get("plotNumber").asString()).isEqualTo(numberOf(soldPlot));
        assertThat(card.get("past").get(0).get("validFrom").asString()).isEqualTo("2019-01-01");
        assertThat(card.get("past").get(0).get("validTo").asString()).isEqualTo("2022-04-30");
    }

    /**
     * §2.5 #4, §2.6 #1-#3: the demo member sees plot 7 (1/2 with a named co-owner) and the area and green status on
     * its card, only number, street and kind on plot 9, and a member who sold a plot sees it like any other plot;
     * a member who owns nothing has no plots.
     */
    @Test
    void aMemberSeesTheirOwnPlotsWithCoOwnersAndOnlyTheBareFactsOfEveryOtherPlot() throws Exception {
        String demoMember = demoMemberToken();
        UUID plot7 = demoPlot("7");
        UUID plot9 = demoPlot("9");

        // §2.5 #4 "My account": the plots with the share, and the co-owner of plot 7 by name
        JsonNode me = body(get("/api/v1/me"), demoMember);
        assertThat(me.get("ownedPlots")).hasSize(2);
        assertThat(me.get("ownedPlots").get(0).get("number").asString()).isEqualTo("7");
        assertThat(me.get("ownedPlots").get(0).get("share").asString()).isEqualTo("1/2");
        assertThat(me.get("ownedPlots").get(1).get("number").asString()).isEqualTo("15");
        assertThat(me.get("ownedPlots").get(1).get("share").asString()).isEqualTo("1/1");
        JsonNode seven = body(get(PLOTS + "/" + plot7 + "/ownerships"), demoMember);
        assertThat(names(seven.get("current"))).containsExactly("Бондаренко Андрій", "Мороз Галина");
        assertThat(shares(seven.get("current"))).containsExactly("1/2", "1/2");

        // §2.6 #1 the card of plot 7: area, green status, both co-owners; the board's notes stay hidden
        card(demoMember, plot7).andExpect(status().isOk())
                .andExpect(jsonPath("$.areaM2").value(800.0))
                .andExpect(jsonPath("$.status").value("GREEN"))
                .andExpect(jsonPath("$.cadastralNumber").exists())
                .andExpect(jsonPath("$.notes").doesNotExist());

        // §2.6 #2 plot 9 is someone else's: number, street and kind, no owners, no area, no history
        String limited = card(demoMember, plot9).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(fieldNames(json.readTree(limited))).doesNotContain("areaM2", "cadastralNumber", "hasBuilding",
                "notes", "status");
        // the card carries no owners at all (the generated model serialises an empty list; names never appear)
        assertThat(json.readTree(limited).path("owners")).isEmpty();
        assertThat(limited).doesNotContain("Литвиненко").doesNotContain("Гончаренко").contains("вул. Садова");
        assertThat(limited).contains("\"number\":\"9\"").contains("вул. Садова");
        send(demoMember, get(PLOTS + "/" + plot9 + "/ownerships"), null).andExpect(status().isNotFound());
        // the list says no more than the card
        JsonNode listed = body(get(PLOTS + "?q=9&size=100"), demoMember);
        for (JsonNode plot : listed.get("items")) {
            if ("9".equals(plot.get("number").asString())) {
                assertThat(fieldNames(plot)).doesNotContain("areaM2", "owners", "cadastralNumber");
            }
        }

        // §2.6 #3 a member who sold their plot sees it like any other plot they do not own; a member who owns nothing
        UUID seller = member("Seller");
        UUID buyer = member("Buyer");
        UUID sold = plot(chairman);
        change(chairman, sold, "2020-01-01", owners(seller, "1/1")).andExpect(status().isCreated());
        String sellerBefore = tokenOf(seller);
        card(sellerBefore, sold).andExpect(status().isOk()).andExpect(jsonPath("$.areaM2").value(400.0));
        change(chairman, sold, "2024-01-01", owners(buyer, "1/1")).andExpect(status().isCreated());
        String limitedAfter = card(sellerBefore, sold).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(fieldNames(json.readTree(limitedAfter))).doesNotContain("areaM2", "status", "notes");
        assertThat(json.readTree(limitedAfter).path("owners")).isEmpty();
        send(sellerBefore, get(PLOTS + "/" + sold + "/ownerships"), null).andExpect(status().isNotFound());
        assertThat(body(get("/api/v1/me"), sellerBefore).get("ownedPlots")).isEmpty();
        // people allowed to view plots always see every plot's owners and history
        assertThat(body(get(PLOTS + "/" + sold + "/ownerships"), treasurer).get("history")).hasSize(1);
    }

    // ---- §2.7 Cooperative setting

    /**
     * §2.7 #1-#4: under the default "own plots only" the demo member gets the status only of own plots and the
     * status filter matches only those; "all plots" shows every status but no area or owners of other plots; the
     * treasurer sees every status under both.
     */
    @Test
    void theColourSettingDecidesWhichStatusesAMemberSeesAndNeverWhichAreasOrOwners() throws Exception {
        String demoMember = demoMemberToken();
        ownerJdbc().update("delete from cooperative_settings where tenant_id = ?", DEMO_TENANT);

        // the default, saved or not, is "own plots only"
        assertThat(body(get(SETTINGS), demoMember).get("memberPlotStatusVisibility").asString()).isEqualTo("OWN_ONLY");

        // #1 own plots show their status, every other plot shows nothing (the UI draws the neutral mark)
        JsonNode own = body(get(PLOTS + "?size=100"), demoMember);
        assertThat(own.get("items")).hasSize(31);
        assertThat(statusNumbers(own)).containsExactly("7", "15");
        // #2 the status filter matches own plots only
        assertThat(numbersOf(body(get(PLOTS + "?status=GREEN&size=100"), demoMember))).containsExactly("7", "15");
        assertThat(numbersOf(body(get(PLOTS + "?status=GREY&size=100"), demoMember))).isEmpty();
        // #4 the treasurer always sees every status
        assertThat(body(get(PLOTS + "?size=100"), treasurer).get("items")).allSatisfy(p ->
                assertThat(p.has("status")).isTrue());

        // #3 the chairman switches to "all plots"
        send(chairman, put(SETTINGS), Map.of("memberPlotStatusVisibility", "ALL_PLOTS")).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberPlotStatusVisibility").value("ALL_PLOTS"));
        JsonNode everything = body(get(PLOTS + "?size=100"), demoMember);
        assertThat(everything.get("items")).allSatisfy(p -> assertThat(p.has("status")).isTrue());
        assertThat(statusOf(everything, "9")).isEqualTo("GREEN");
        assertThat(statusOf(everything, "25")).isEqualTo("GREY");
        for (JsonNode plot : everything.get("items")) {
            assertThat(plot.has("owners")).isFalse();
            if (!List.of("7", "15").contains(plot.get("number").asString())) {
                assertThat(fieldNames(plot)).doesNotContain("areaM2", "cadastralNumber", "hasBuilding");
            }
        }
        assertThat(body(get(PLOTS + "?size=100"), treasurer).get("items")).allSatisfy(p ->
                assertThat(p.has("status")).isTrue());

        // #5 the audit log has an entry with the old and the new value
        UUID tenantEntity = DEMO_TENANT;
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode newest = auditItems(chairman, "SETTINGS", tenantEntity).get(0);
            assertThat(newest.get("action").asString()).isEqualTo("SETTINGS_UPDATED");
            assertThat(change(newest, "memberPlotStatusVisibility").get("oldValue").asString()).isEqualTo("OWN_ONLY");
            assertThat(change(newest, "memberPlotStatusVisibility").get("newValue").asString()).isEqualTo("ALL_PLOTS");
        });
        ownerJdbc().update("delete from audit_entry where entity_type = 'SETTINGS' and entity_id = ?", DEMO_TENANT);
    }

    /**
     * §2.7 #6, #7: the treasurer and a member can read but not change the setting, and the roles page can add the new
     * permission to a role, which then lets its holder change the setting.
     */
    @Test
    void onlyTheChairmanOrASettingsManagerChangesTheSettingAndThePermissionIsAssignableToRoles() throws Exception {
        // #6 refused for the treasurer, a plain member and the demo member; nothing is saved
        for (String denied : List.of(treasurer, tokenWithBaseRole("MEMBER"), demoMemberToken())) {
            send(denied, put(SETTINGS), Map.of("memberPlotStatusVisibility", "ALL_PLOTS"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(NO_ACCESS));
        }
        assertThat(ownerJdbc().queryForObject("select count(*) from cooperative_settings where tenant_id = ?",
                Integer.class, DEMO_TENANT)).isZero();
        send(chairman, get("/api/v1/me"), null).andExpect(jsonPath("$.permissions[?(@ == 'SETTINGS_MANAGE')]").exists());
        send(treasurer, get("/api/v1/me"), null)
                .andExpect(jsonPath("$.permissions[?(@ == 'SETTINGS_MANAGE')]").doesNotExist());

        // #7 the roles page offers "Manage cooperative settings"; a custom role can hold it
        send(chairman, get("/api/v1/permissions"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'SETTINGS_MANAGE')].name").value("Manage cooperative settings"));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "Settings keeper " + UUID.randomUUID().toString().substring(0, 8));
        input.put("permissions", List.of("SETTINGS_MANAGE"));
        String created = send(chairman, post("/api/v1/roles"), input).andExpect(status().isCreated())
                .andExpect(jsonPath("$.permissions[0]").value("SETTINGS_MANAGE")).andReturn().getResponse()
                .getContentAsString();
        UUID role = UUID.fromString(json.readTree(created).get("id").asString());
        createdApiRoles.add(role);
        // and a person holding that role may change the setting
        String keeper = tokenWithRole(role);
        send(keeper, put(SETTINGS), Map.of("memberPlotStatusVisibility", "ALL_PLOTS")).andExpect(status().isOk());
    }

    // ---- §2.8 Demo data

    /**
     * §2.8 #1: most demo plots are green with owners, a few grey without, plots with two owners show both names,
     * plot 9 shows a sale with the previous owner in its history, and the demo member co-owns plot 7 (1/2) and owns
     * another plot alone.
     */
    @Test
    void theDemoCooperativeHasOwnersCoOwnedPlotsASaleInTheHistoryAndAFewPlotsWithoutAnOwner() throws Exception {
        JsonNode all = body(get(PLOTS + "?size=100"), treasurer);
        long green = 0;
        long grey = 0;
        for (JsonNode plot : all.get("items")) {
            boolean owned = plot.has("owners");
            String status = plot.get("status").asString();
            green += "GREEN".equals(status) ? 1 : 0;
            grey += "GREY".equals(status) ? 1 : 0;
            assertThat(owned).as("plot %s: owners iff green", plot.get("number").asString())
                    .isEqualTo("GREEN".equals(status));
        }
        assertThat(green).isGreaterThan(grey).isGreaterThanOrEqualTo(20);
        assertThat(grey).isBetween(3L, 10L);
        assertThat(statusOf(all, "25")).isEqualTo("GREY");
        assertThat(statusOf(all, "R-1")).isEqualTo("GREY");

        // co-owned plots: 1/2 + 1/2 (plots 3 and 7) and 2/3 + 1/3 (plot 11), both names in the list
        Set<String> twoOwnerPlots = new LinkedHashSet<>();
        for (JsonNode plot : all.get("items")) {
            if (plot.has("owners") && plot.get("owners").size() == 2) twoOwnerPlots.add(plot.get("number").asString());
        }
        assertThat(twoOwnerPlots).contains("3", "7", "11");
        assertThat(shares(ownersOf(all, "3"), "share")).containsExactly("1/2", "1/2");
        assertThat(shares(ownersOf(all, "11"), "share")).containsExactlyInAnyOrder("2/3", "1/3");

        // plot 9 was sold: the previous owner's period ends the day before the new owner's starts
        JsonNode nine = body(get(PLOTS + "/" + demoPlot("9") + "/ownerships"), treasurer);
        LocalDate soldOn = LocalDate.parse(nine.get("current").get(0).get("validFrom").asString());
        LocalDate previousEnd = LocalDate.parse(nine.get("history").get(0).get("validTo").asString());
        assertThat(previousEnd).isEqualTo(soldOn.minusDays(1));
        assertThat(soldOn).isBefore(LocalDate.now(ZoneId.of("Europe/Kyiv")));

        // the demo member: plot 7 at 1/2 and another plot alone
        JsonNode me = body(get("/api/v1/me"), demoMemberToken());
        assertThat(shares(me.get("ownedPlots"), "share")).containsExactly("1/2", "1/1");
    }

    // ---- helpers

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private final Map<UUID, String> memberNames = new LinkedHashMap<>();

    /** A new active member with a unique last name; its name as the owner lists print it is {@link #name}. */
    private UUID member(String firstName) {
        String last = "Acc" + tag();
        UUID id = newMember(DEMO_TENANT, last, firstName, null, last.toLowerCase() + "@example.com");
        memberNames.put(id, last + " " + firstName);
        return id;
    }

    private String name(UUID member) {
        return memberNames.get(member);
    }

    private String tokenWithBaseRole(String baseCode) {
        String email = "own-acceptance-" + baseCode.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        UUID member = newMember(DEMO_TENANT, email);
        giveBaseRole(DEMO_TENANT, member, baseCode);
        return TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);
    }

    /** A token of an existing member who holds the MEMBER role. */
    private String tokenOf(UUID member) {
        String email = memberEmail(DEMO_TENANT, member);
        giveBaseRole(DEMO_TENANT, member, "MEMBER");
        return TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);
    }

    /** The demo member (member@demo.local) as Keycloak would sign them in; owns plot 7 (1/2) and plot 15. */
    private String demoMemberToken() {
        return TestTokens.bearer(DEMO_TENANT, "sub-" + DEMO_MEMBER_EMAIL, DEMO_MEMBER_EMAIL);
    }

    /** A token of a fresh member whose only role is a custom role with exactly these permissions. */
    private String customRoleToken(String... permissions) {
        String email = "own-acceptance-custom-" + UUID.randomUUID() + "@example.com";
        UUID member = newMember(DEMO_TENANT, email);
        UUID role = UUID.randomUUID();
        createdRoles.add(role);
        inTenant(DEMO_TENANT, sql -> {
            sql.update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, DEMO_TENANT, "Custom " + role);
            for (String permission : permissions) {
                sql.update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, ?)",
                        DEMO_TENANT, role, permission);
            }
            return sql.update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)",
                    DEMO_TENANT, member, role);
        });
        return TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);
    }

    /** A token of a fresh member who holds the given (already existing) role. */
    private String tokenWithRole(UUID role) {
        String email = "own-acceptance-role-" + UUID.randomUUID() + "@example.com";
        UUID member = newMember(DEMO_TENANT, email);
        inTenant(DEMO_TENANT, sql -> sql.update(
                "insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)", DEMO_TENANT, member, role));
        return TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);
    }

    private UUID plot(String bearer) throws Exception {
        return plot(bearer, "PLOT", "400");
    }

    private UUID plot(String bearer, String kind, String area) throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("number", "OA-" + tag());
        input.put("kind", kind);
        input.put("areaM2", new java.math.BigDecimal(area));
        String content = send(bearer, post(PLOTS), input).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        UUID id = UUID.fromString(json.readTree(content).get("id").asString());
        createdPlots.add(id);
        return id;
    }

    /** A demo plot by number (read-only use). */
    private UUID demoPlot(String number) {
        return ownerJdbc().queryForObject("select id from plot where tenant_id = ? and number = ?", UUID.class,
                DEMO_TENANT, number);
    }

    private String numberOf(UUID plot) {
        return ownerJdbc().queryForObject("select number from plot where id = ?", String.class, plot);
    }

    private boolean archivedMember(UUID member) {
        return ownerJdbc().queryForObject("select archived_at is not null from member where id = ?", Boolean.class,
                member);
    }

    /** Owner rows for a request: member, share, member, share, ... */
    private static List<Map<String, Object>> owners(Object... memberAndShare) {
        List<Map<String, Object>> owners = new ArrayList<>();
        for (int i = 0; i < memberAndShare.length; i += 2) {
            Map<String, Object> owner = new LinkedHashMap<>();
            owner.put("memberId", memberAndShare[i]);
            owner.put("share", memberAndShare[i + 1]);
            owners.add(owner);
        }
        return owners;
    }

    private ResultActions change(String bearer, UUID plot, String from, List<Map<String, Object>> owners)
            throws Exception {
        return change(bearer, plot, from, owners, null);
    }

    private ResultActions change(String bearer, UUID plot, String from, List<Map<String, Object>> owners,
            String basis) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("effectiveFrom", from);
        body.put("owners", owners);
        if (basis != null) body.put("basis", basis);
        return send(bearer, post(PLOTS + "/" + plot + "/ownership-changes"), body);
    }

    private static Map<String, Object> coOwner(UUID ownershipId, String share) {
        Map<String, Object> coOwner = new LinkedHashMap<>();
        coOwner.put("ownershipId", ownershipId);
        coOwner.put("share", share);
        return coOwner;
    }

    private ResultActions correct(String bearer, UUID ownership, UUID member, String share, String from, String to,
            String basis, String reason, List<Map<String, Object>> coOwners) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("memberId", member);
        body.put("share", share);
        body.put("validFrom", from);
        if (to != null) body.put("validTo", to);
        if (basis != null) body.put("basis", basis);
        if (reason != null) body.put("reason", reason);
        body.put("coOwnerShares", coOwners);
        return send(bearer, post("/api/v1/ownerships/" + ownership + "/corrections"), body);
    }

    private ResultActions send(String bearer, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private ResultActions card(String bearer, UUID plot) throws Exception {
        return send(bearer, get(PLOTS + "/" + plot), null);
    }

    /** GET as the bearer, which must answer 200; the parsed JSON. */
    private JsonNode body(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        return json.readTree(send(bearer, request, null).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private JsonNode current(String bearer, UUID plot) throws Exception {
        return body(get(PLOTS + "/" + plot + "/ownerships"), bearer).get("current");
    }

    private String statusOnCard(String bearer, UUID plot) throws Exception {
        return body(get(PLOTS + "/" + plot), bearer).get("status").asString();
    }

    private String statusInList(String bearer, String number) throws Exception {
        JsonNode items = body(get(URI.create(PLOTS + "?q=" + number)), bearer).get("items");
        for (JsonNode item : items) {
            if (number.equals(item.get("number").asString())) return item.get("status").asString();
        }
        throw new AssertionError("plot " + number + " is not listed");
    }

    /** Audit entries of one entity as the API lists them (newest first). */
    private JsonNode auditItems(String bearer, String entityType, UUID entity) throws Exception {
        return body(get(URI.create("/api/v1/audit?entityType=" + entityType + "&entityId=" + entity + "&size=50")),
                bearer).get("items");
    }

    /** The owners of an audit value (a JSON array of {member, share, ...}) as "member share" texts. */
    private List<String> ownerTexts(String ownersValue) {
        List<String> texts = new ArrayList<>();
        for (JsonNode owner : json.readTree(ownersValue)) {
            texts.add(owner.get("member").asString() + " " + owner.get("share").asString());
        }
        return texts;
    }

    private static JsonNode change(JsonNode auditEntry, String field) {
        for (JsonNode change : auditEntry.get("changes")) {
            if (field.equals(change.get("field").asString())) return change;
        }
        throw new AssertionError("no change for " + field + " in " + auditEntry);
    }

    private static List<String> names(JsonNode owners) {
        List<String> names = new ArrayList<>();
        owners.forEach(o -> names.add(o.get("memberName").asString()));
        return names;
    }

    private static List<String> shares(JsonNode owners) {
        return shares(owners, "share");
    }

    private static List<String> shares(JsonNode rows, String field) {
        List<String> shares = new ArrayList<>();
        rows.forEach(o -> shares.add(o.get(field).asString()));
        return shares;
    }

    private static Map<String, String> sharesByName(JsonNode owners) {
        Map<String, String> byName = new LinkedHashMap<>();
        owners.forEach(o -> byName.put(o.get("memberName").asString(), o.get("share").asString()));
        return byName;
    }

    private static UUID rowOf(JsonNode owners, String memberName) {
        for (JsonNode owner : owners) {
            if (memberName.equals(owner.get("memberName").asString())) return UUID.fromString(owner.get("id").asString());
        }
        throw new AssertionError("no owner record of " + memberName);
    }

    private static List<String> numbersOf(JsonNode page) {
        List<String> numbers = new ArrayList<>();
        page.get("items").forEach(item -> numbers.add(item.get("number").asString()));
        return numbers;
    }

    /** The numbers of the plots in a list page that carry a status, in list order. */
    private static List<String> statusNumbers(JsonNode page) {
        List<String> numbers = new ArrayList<>();
        page.get("items").forEach(item -> {
            if (item.has("status")) numbers.add(item.get("number").asString());
        });
        return numbers;
    }

    private static String statusOf(JsonNode page, String number) {
        for (JsonNode item : page.get("items")) {
            if (number.equals(item.get("number").asString())) return item.get("status").asString();
        }
        throw new AssertionError("plot " + number + " is not listed");
    }

    private static JsonNode ownersOf(JsonNode page, String number) {
        for (JsonNode item : page.get("items")) {
            if (number.equals(item.get("number").asString())) return item.get("owners");
        }
        throw new AssertionError("plot " + number + " is not listed");
    }

    private static List<String> ownerNames(JsonNode page, String number) {
        List<String> names = new ArrayList<>();
        ownersOf(page, number).forEach(o -> names.add(o.get("name").asString()));
        return names;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }
}
