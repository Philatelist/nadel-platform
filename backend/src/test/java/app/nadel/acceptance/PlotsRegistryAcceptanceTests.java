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
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance tests of the whole feature "Plots Registry" (functional-spec.md of 006) through the public HTTP API with
 * the demo data: the journey of one plot across three kinds of viewer, the demo cooperative as the spec describes it,
 * and the refusals a person without edit rights meets. Per-case rules stay in {@code PlotsReadTests} and
 * {@code PlotsWriteTests}. Criteria are named as {@code §2.x #n}.
 * <pre>
 * @layer: integration
 * @spec: 006-plots-registry
 * @regression
 * </pre>
 */
@Tag("regression")
class PlotsRegistryAcceptanceTests extends IntegrationTest {

    private static final String PLOTS = "/api/v1/plots";
    private static final String RESTRICTED_NOTE = "visible to plot viewers only";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void removeCreatedPlots() {
        for (UUID id : created) {
            inTenant(DEMO_TENANT, sql -> {
                sql.update("delete from plot_status where plot_id = ?", id);
                return sql.update("delete from plot where id = ?", id);
            });
        }
        created.clear();
    }

    /**
     * §2.1 #1, §2.2 #2, §2.2 #4, §2.3 #1-#4, §2.3 #6, §2.5 #2: the chairman creates a plot, the treasurer reads every
     * field, an ordinary member reads number, street, kind and status only (list, card, map), archive hides it,
     * restore brings it back, delete removes it for good, and each action has its own audit entry.
     */
    @Test
    void aPlotIsCreatedReadByThreeKindsOfViewerArchivedRestoredDeletedAndAudited() throws Exception {
        String chairman = tokenWithBaseRole("CHAIRMAN");
        String treasurer = tokenWithBaseRole("TREASURER");
        String member = tokenWithBaseRole("MEMBER");
        String number = "A-" + UUID.randomUUID().toString().substring(0, 8);
        String cadastral = "9999999999:88:777:6666";

        // create: 6 sotkas typed as 600 m2, with every optional field
        Map<String, Object> input = input(number, "PLOT", "600");
        input.put("street", "Вишнева");
        input.put("cadastralNumber", cadastral);
        input.put("hasBuilding", true);
        input.put("notes", RESTRICTED_NOTE);
        String body = send(chairman, post(PLOTS), input).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("GREY"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).get("id").asString());
        created.add(id);

        // the treasurer finds it by number and by cadastral number and reads every field
        JsonNode listedByTreasurer = list(treasurer, "q=" + number).get("items").get(0);
        assertThat(listedByTreasurer.get("id").asString()).isEqualTo(id.toString());
        assertThat(listedByTreasurer.get("areaM2").decimalValue()).isEqualByComparingTo("600");
        assertThat(listedByTreasurer.get("cadastralNumber").asString()).isEqualTo(cadastral);
        assertThat(listedByTreasurer.get("hasBuilding").asBoolean()).isTrue();
        assertThat(list(treasurer, "q=" + cadastral).get("totalItems").asInt()).isEqualTo(1);
        send(treasurer, get(PLOTS + "/" + id), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(number))
                .andExpect(jsonPath("$.street").value("Вишнева"))
                .andExpect(jsonPath("$.areaM2").value(600.0))
                .andExpect(jsonPath("$.cadastralNumber").value(cadastral))
                .andExpect(jsonPath("$.hasBuilding").value(true))
                .andExpect(jsonPath("$.notes").value(RESTRICTED_NOTE))
                .andExpect(jsonPath("$.status").value("GREY"));

        // the member sees the plot with number, street, kind and status, and no other field, in list and card
        JsonNode listedByMember = list(member, "q=" + number).get("items").get(0);
        assertThat(fieldNames(listedByMember)).containsExactlyInAnyOrder("id", "number", "street", "kind", "status",
                "archived");
        assertThat(listedByMember.get("street").asString()).isEqualTo("Вишнева");
        assertThat(list(member, "q=" + cadastral).get("totalItems").asInt()).isZero();
        String memberCard = send(member, get(PLOTS + "/" + id), null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(fieldNames(json.readTree(memberCard))).doesNotContain("areaM2", "cadastralNumber", "hasBuilding",
                "notes", "createdAt", "updatedAt");
        assertThat(memberCard).doesNotContain(RESTRICTED_NOTE).doesNotContain(cadastral);

        // the hidden map follows the same visibility (a new plot has no polygon, so a drawn demo plot is used)
        assertThat(mapFeature(member, "3").get("properties").has("areaM2")).isFalse();
        assertThat(mapFeature(member, "3").get("properties").has("debt")).isFalse();
        assertThat(mapFeature(treasurer, "3").get("properties").has("areaM2")).isTrue();
        assertThat(mapFeature(treasurer, "3").get("properties").has("debt")).isTrue();
        assertThat(mapFeature(treasurer, number)).isNull();

        // someone without edit rights is refused every change; the plot stays as it was
        Map<String, Object> changed = input(number, "PLOT", "600");
        changed.put("street", "Hacked");
        for (String denied : List.of(treasurer, member)) {
            send(denied, put(PLOTS + "/" + id), changed).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            send(denied, post(PLOTS + "/" + id + "/archive"), null).andExpect(status().isForbidden());
            send(denied, post(PLOTS + "/" + id + "/restore"), null).andExpect(status().isForbidden());
            send(denied, delete(PLOTS + "/" + id), null).andExpect(status().isForbidden());
        }
        assertThat(streetOf(id)).isEqualTo("Вишнева");

        // edit the street (§2.5 #1: the old and the new street reach the audit log)
        changed.put("street", "Яблунева");
        changed.put("cadastralNumber", cadastral);
        changed.put("hasBuilding", true);
        changed.put("notes", RESTRICTED_NOTE);
        send(chairman, put(PLOTS + "/" + id), changed).andExpect(status().isOk())
                .andExpect(jsonPath("$.street").value("Яблунева"));

        // archive: gone from the default list, there with includeArchived and marked archived; the member never sees it
        send(chairman, post(PLOTS + "/" + id + "/archive"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));
        assertThat(list(treasurer, "q=" + number).get("totalItems").asInt()).isZero();
        JsonNode archived = list(treasurer, "q=" + number + "&includeArchived=true").get("items").get(0);
        assertThat(archived.get("archived").asBoolean()).isTrue();
        assertThat(list(member, "q=" + number + "&includeArchived=true").get("totalItems").asInt()).isZero();
        send(member, get(PLOTS + "/" + id), null).andExpect(status().isNotFound());

        // an archived plot cannot be deleted; the refusal says to restore it first
        send(chairman, delete(PLOTS + "/" + id), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_ARCHIVED"));
        assertThat(list(treasurer, "q=" + number + "&includeArchived=true").get("totalItems").asInt()).isEqualTo(1);

        // restore: back in the default list
        send(chairman, post(PLOTS + "/" + id + "/restore"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(false));
        assertThat(list(treasurer, "q=" + number).get("totalItems").asInt()).isEqualTo(1);

        // delete: gone for good, archived view included
        send(chairman, delete(PLOTS + "/" + id), null).andExpect(status().isNoContent());
        assertThat(list(treasurer, "q=" + number + "&includeArchived=true").get("totalItems").asInt()).isZero();
        send(treasurer, get(PLOTS + "/" + id), null).andExpect(status().isNotFound());

        // audit: five entries, each its own action, readable through the audit API filtered to plots by the treasurer
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(auditActions(treasurer, id)).containsExactly("PLOT_CREATED", "PLOT_UPDATED", "PLOT_ARCHIVED",
                        "PLOT_RESTORED", "PLOT_DELETED"));
        JsonNode update = auditEntries(treasurer, id).get(1);
        assertThat(update.get("entityLabel").asString()).isEqualTo(number);
        JsonNode streetChange = null;
        for (JsonNode change : update.get("changes")) {
            if ("street".equals(change.get("field").asString())) streetChange = change;
        }
        assertThat(streetChange).isNotNull();
        assertThat(streetChange.get("oldValue").asString()).isEqualTo("Вишнева");
        assertThat(streetChange.get("newValue").asString()).isEqualTo("Яблунева");
    }

    /**
     * §2.1 #2, #3, #4, #5, §2.4 #1 (server side of the form): the refusals come with a code per field and nothing is
     * saved; a number freed by archiving can be used again.
     */
    @Test
    void everyRefusedSaveNamesItsFieldAndSavesNothingWhileAnArchivedNumberIsFree() throws Exception {
        String chairman = tokenWithBaseRole("CHAIRMAN");

        // empty number and empty area: "required" under each field
        send(chairman, post(PLOTS), Map.of("kind", "PLOT")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='number')].code").value("REQUIRED"))
                .andExpect(jsonPath("$.errors[?(@.field=='areaM2')].code").value("REQUIRED"));
        // an existing active number, with surrounding spaces and in another letter case
        send(chairman, post(PLOTS), input(" 12 ", "PLOT", "600")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_NUMBER_TAKEN"))
                .andExpect(jsonPath("$.errors[0].field").value("number"));
        send(chairman, post(PLOTS), input("r-1", "COMMON", "0")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_NUMBER_TAKEN"));
        // wrong cadastral format; a number used by another plot of the cooperative
        Map<String, Object> wrongFormat = input("B-" + tag(), "PLOT", "600");
        wrongFormat.put("cadastralNumber", "123:45");
        send(chairman, post(PLOTS), wrongFormat).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("cadastralNumber"))
                .andExpect(jsonPath("$.errors[0].code").value("CADASTRAL_INVALID"));
        Map<String, Object> taken = input("B-" + tag(), "PLOT", "600");
        taken.put("cadastralNumber", "4810136300:02:001:0001"); // demo plot 1
        send(chairman, post(PLOTS), taken).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CADASTRAL_NUMBER_TAKEN"))
                .andExpect(jsonPath("$.errors[0].field").value("cadastralNumber"));
        // area 0 for a plot is refused, for common land it is accepted
        String zeroPlot = "B-" + tag();
        send(chairman, post(PLOTS), input(zeroPlot, "PLOT", "0")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("areaM2"))
                .andExpect(jsonPath("$.errors[0].code").value("AREA_INVALID"));
        assertThat(countByNumber(zeroPlot)).isZero();
        String zeroCommon = "C-" + tag();
        UUID common = remember(send(chairman, post(PLOTS), input(zeroCommon, "COMMON", "0"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("GREY")));
        assertThat(common).isNotNull();

        // an archived plot frees its number; a new plot takes it; then restoring the old one is refused
        // (a plot created here, because a demo plot has an owner and could not be archived)
        String reused = "R-" + tag();
        UUID original = remember(send(chairman, post(PLOTS), input(reused, "PLOT", "600"))
                .andExpect(status().isCreated()));
        send(chairman, post(PLOTS + "/" + original + "/archive"), null).andExpect(status().isOk());
        UUID replacement = remember(send(chairman, post(PLOTS), input(reused, "PLOT", "600"))
                .andExpect(status().isCreated()));
        assertThat(replacement).isNotEqualTo(original);
        send(chairman, post(PLOTS + "/" + original + "/restore"), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_NUMBER_TAKEN"));
    }

    /** §2.1 #6: 600 m2 is stored as 6 sotkas and 6.5 sotkas as 650 m2 (the server keeps m2 and never rounds). */
    @Test
    void anAreaTypedInSquareMetresOrSotkasIsStoredAsSquareMetresWithoutRounding() throws Exception {
        String chairman = tokenWithBaseRole("CHAIRMAN");

        UUID six = remember(send(chairman, post(PLOTS), input("S-" + tag(), "PLOT", "600"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.areaM2").value(600.0)));
        UUID sixAndHalf = remember(send(chairman, post(PLOTS), input("S-" + tag(), "PLOT", "650"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.areaM2").value(650.0)));
        assertThat(areaOf(six)).isEqualByComparingTo("600");
        assertThat(areaOf(sixAndHalf)).isEqualByComparingTo("650");
        // more than two decimals of m2 (for example 6.505 sotkas) is refused rather than rounded
        send(chairman, post(PLOTS), input("S-" + tag(), "PLOT", "650.123")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("AREA_INVALID"));
    }

    /**
     * §2.2 #3, #5, §2.6 #1: the demo cooperative has about 30 plots on two streets and one common-land road, the owned
     * ones green (feature 007) and the rest grey, some with cadastral numbers and buildings; the list pages of 20 continue in the same natural order; the kind
     * filter lists only the road.
     */
    @Test
    void theDemoCooperativeHasThirtyPlotsOnTwoStreetsAndARoadListedInPages() throws Exception {
        String treasurer = tokenWithBaseRole("TREASURER");

        List<JsonNode> all = new ArrayList<>();
        list(treasurer, "size=100").get("items").forEach(all::add);
        long plots = all.stream().filter(p -> "PLOT".equals(p.get("kind").asString())).count();
        List<JsonNode> common = all.stream().filter(p -> "COMMON".equals(p.get("kind").asString())).toList();
        Set<String> streets = new LinkedHashSet<>();
        all.stream().filter(p -> "PLOT".equals(p.get("kind").asString()))
                .forEach(p -> streets.add(p.get("street").asString()));
        assertThat(plots).isBetween(28L, 32L);
        assertThat(streets).hasSize(2);
        assertThat(common).hasSize(1);
        assertThat(common.get(0).get("number").asString()).isEqualTo("R-1");
        assertThat(all).allSatisfy(p -> assertThat(p.get("status").asString()).isIn("GREEN", "GREY"));
        assertThat(common.get(0).get("status").asString()).isEqualTo("GREY");
        assertThat(all.stream().filter(p -> p.has("cadastralNumber")).count()).isBetween(5L, 15L);
        assertThat(all.stream().filter(p -> p.get("hasBuilding").asBoolean()).count()).isPositive();

        // paging: the second page continues the first without gaps or repeats
        List<String> everyNumber = all.stream().map(p -> p.get("number").asString()).toList();
        List<String> firstPage = numbers(list(treasurer, "page=0"));
        List<String> secondPage = numbers(list(treasurer, "page=1"));
        assertThat(firstPage).hasSize(20);
        List<String> joined = new ArrayList<>(firstPage);
        joined.addAll(secondPage);
        assertThat(joined).isEqualTo(everyNumber);
        // natural order, not text order
        assertThat(everyNumber.indexOf("2")).isLessThan(everyNumber.indexOf("10"));
        assertThat(everyNumber.indexOf("12")).isLessThan(everyNumber.indexOf("12а"));

        // the search for "12" finds the plots whose number, street or cadastral number contains it, 12 before 12а
        List<String> found = numbers(list(treasurer, "q=12&size=100"));
        assertThat(found).contains("12", "12а");
        assertThat(found.indexOf("12")).isLessThan(found.indexOf("12а"));
        // the filter by kind lists only the road; nothing found is an empty page, not an error
        assertThat(numbers(list(treasurer, "kind=COMMON"))).containsExactly("R-1");
        assertThat(list(treasurer, "q=no-such-plot-" + tag()).get("items")).isEmpty();
    }

    /** §2.2 #4 on the demo plots: the demo member sees every plot number and street but nothing of area or cadastral. */
    @Test
    void anOrdinaryMemberSeesEveryDemoPlotAsNumberStreetKindAndStatusOnly() throws Exception {
        String member = tokenWithBaseRole("MEMBER");

        JsonNode page = list(member, "size=100");
        assertThat(page.get("totalItems").asInt()).isEqualTo(31);
        for (JsonNode plot : page.get("items")) {
            assertThat(fieldNames(plot)).doesNotContain("areaM2", "cadastralNumber", "hasBuilding", "notes");
            assertThat(plot.get("status").asString()).isIn("GREEN", "GREY");
            assertThat(fieldNames(plot)).doesNotContain("owners");
        }
    }

    // ---- helpers

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static Map<String, Object> input(String number, String kind, String areaM2) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("number", number);
        input.put("kind", kind);
        input.put("areaM2", new BigDecimal(areaM2));
        return input;
    }

    private String tokenWithBaseRole(String baseCode) {
        String email = "plots-acceptance-" + baseCode.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        UUID member = newMember(DEMO_TENANT, email);
        giveBaseRole(DEMO_TENANT, member, baseCode);
        return TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);
    }

    private ResultActions send(String bearer, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private UUID remember(ResultActions created201) throws Exception {
        UUID id = UUID.fromString(json.readTree(created201.andReturn().getResponse().getContentAsString())
                .get("id").asString());
        created.add(id);
        return id;
    }

    private JsonNode list(String bearer, String query) throws Exception {
        String content = mvc.perform(get(PLOTS + (query.isEmpty() ? "" : "?" + query))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(content);
    }

    private static List<String> numbers(JsonNode page) {
        List<String> numbers = new ArrayList<>();
        page.get("items").forEach(item -> numbers.add(item.get("number").asString()));
        return numbers;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    /** The map feature of a plot number, or null when the plot is not on the map. */
    private JsonNode mapFeature(String bearer, String number) throws Exception {
        String content = mvc.perform(get(PLOTS + "/map").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (JsonNode feature : json.readTree(content).get("features")) {
            if (number.equals(feature.get("properties").get("number").asString())) return feature;
        }
        return null;
    }

    private JsonNode auditEntries(String bearer, UUID plot) throws Exception {
        String content = mvc.perform(get("/api/v1/audit").param("entityType", "PLOT")
                        .param("entityId", plot.toString()).param("size", "50")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // newest first in the API; the journey reads oldest first
        List<JsonNode> items = new ArrayList<>();
        json.readTree(content).get("items").forEach(items::add);
        java.util.Collections.reverse(items);
        return json.valueToTree(items);
    }

    private List<String> auditActions(String bearer, UUID plot) throws Exception {
        List<String> actions = new ArrayList<>();
        auditEntries(bearer, plot).forEach(e -> actions.add(e.get("action").asString()));
        return actions;
    }

    private String streetOf(UUID id) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject("select street from plot where id = ?", String.class,
                id));
    }

    private BigDecimal areaOf(UUID id) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject("select area_m2 from plot where id = ?",
                BigDecimal.class, id));
    }

    private int countByNumber(String number) {
        Integer count = inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select count(*) from plot where number = ?", Integer.class, number));
        return count;
    }
}
