package app.nadel.plots.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Plot writes: create, update, archive, restore and delete: permission, validation, uniqueness, status and audit. */
class PlotsWriteTests extends IntegrationTest {

    private static final String PLOTS = "/api/v1/plots";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private final List<Created> createdThroughApi = new ArrayList<>();
    private final Map<String, UUID> tokenTenants = new HashMap<>();
    private final List<UUID> roles = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID role : roles) {
            inTenant(DEMO_TENANT, sql -> {
                sql.update("delete from member_role where role_id = ?", role);
                sql.update("delete from role_permission where role_id = ?", role);
                return sql.update("delete from role where id = ?", role);
            });
        }
        roles.clear();
        for (Created plot : createdThroughApi) {
            inTenant(plot.tenant, sql -> {
                sql.update("delete from plot_status where plot_id = ?", plot.id);
                return sql.update("delete from plot where id = ?", plot.id);
            });
        }
        createdThroughApi.clear();
    }

    // ---- create

    @Test
    void chairmanCreatesAPlotWithTheInitialStatusAndLocation() throws Exception {
        String number = number();
        Map<String, Object> body = body(number, "PLOT", "600");
        body.put("street", "Garden St");
        body.put("cadastralNumber", "1234567890:12:345:6789");
        body.put("hasBuilding", true);
        body.put("notes", "corner plot");

        Created created = create(chairman(), body);

        created.actions
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Location", endsWith("/api/v1/plots/" + created.id)))
                .andExpect(jsonPath("$.id").value(created.id.toString()))
                .andExpect(jsonPath("$.number").value(number))
                .andExpect(jsonPath("$.street").value("Garden St"))
                .andExpect(jsonPath("$.kind").value("PLOT"))
                .andExpect(jsonPath("$.areaM2").value(600.0))
                .andExpect(jsonPath("$.cadastralNumber").value("1234567890:12:345:6789"))
                .andExpect(jsonPath("$.hasBuilding").value(true))
                .andExpect(jsonPath("$.notes").value("corner plot"))
                .andExpect(jsonPath("$.status").value("GREY"))
                .andExpect(jsonPath("$.archived").value(false))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
        // the cooperative comes from the token, never from the request; the plot starts GREY with no debt
        Map<String, Object> stored = inTenant(DEMO_TENANT, sql -> sql.queryForMap(
                "select p.tenant_id, p.area_m2, s.status, s.debt from plot p join plot_status s on s.plot_id = p.id "
                        + "where p.id = ?", created.id));
        assertThat(stored.get("tenant_id")).isEqualTo(DEMO_TENANT);
        assertThat((BigDecimal) stored.get("area_m2")).isEqualByComparingTo("600");
        assertThat(stored.get("status")).isEqualTo("GREY");
        assertThat((BigDecimal) stored.get("debt")).isEqualByComparingTo("0");
        // the new plot is on the list for the treasurer
        mvc.perform(get(PLOTS + "/" + created.id).header(HttpHeaders.AUTHORIZATION, treasurer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(number));
    }

    @Test
    void optionalFieldsMayBeOmittedAndTheBuildingDefaultsToFalse() throws Exception {
        Created created = create(chairman(), body(number(), "PLOT", "6.50"));

        created.actions
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", not(hasKey("street"))))
                .andExpect(jsonPath("$", not(hasKey("cadastralNumber"))))
                .andExpect(jsonPath("$", not(hasKey("notes"))))
                .andExpect(jsonPath("$.hasBuilding").value(false))
                .andExpect(jsonPath("$.areaM2").value(6.5));
    }

    @Test
    void commonLandMayHaveAreaZero() throws Exception {
        create(chairman(), body(number(), "COMMON", "0"))
                .actions
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("COMMON"))
                .andExpect(jsonPath("$.status").value("GREY"))
                .andExpect(jsonPath("$.areaM2").value(0.0));
    }

    @Test
    void anAreaOfAPlotAtTheLimitsIsAccepted() throws Exception {
        create(chairman(), body(number(), "PLOT", "0.01")).actions.andExpect(status().isCreated());
        create(chairman(), body(number(), "PLOT", "99999999.99")).actions.andExpect(status().isCreated());
    }

    // ---- validation

    @Test
    void anAreaOfZeroIsRefusedForAPlot() throws Exception {
        create(chairman(), body(number(), "PLOT", "0"))
                .actions
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("areaM2"))
                .andExpect(jsonPath("$.errors[0].code").value("AREA_INVALID"));
    }

    @Test
    void aNegativeAreaAndMoreThanTwoDecimalsAreRefused() throws Exception {
        for (String area : List.of("-1", "6.001", "100000000")) {
            create(chairman(), body(number(), "COMMON", area))
                    .actions
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("areaM2"))
                    .andExpect(jsonPath("$.errors[0].code").value("AREA_INVALID"));
        }
    }

    @Test
    void aWrongCadastralFormatIsRefused() throws Exception {
        Map<String, Object> body = body(number(), "PLOT", "600");
        body.put("cadastralNumber", "123:45");

        create(chairman(), body)
                .actions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("cadastralNumber"))
                .andExpect(jsonPath("$.errors[0].code").value("CADASTRAL_INVALID"));
    }

    @Test
    void missingFieldsAreReportedAsRequired() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("number", number());

        create(chairman(), body)
                .actions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'kind')].code").value("REQUIRED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'areaM2')].code").value("REQUIRED"));
    }

    @Test
    void aBlankNumberIsRequired() throws Exception {
        create(chairman(), body("   ", "PLOT", "600"))
                .actions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("number"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
    }

    @Test
    void aTooLongNumberIsRefused() throws Exception {
        create(chairman(), body("N".repeat(21), "PLOT", "600"))
                .actions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("number"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
    }

    @Test
    void validationErrorsOfSeveralKindsComeInOneResponse() throws Exception {
        Map<String, Object> body = body(number(), "PLOT", "0");
        body.put("cadastralNumber", "bad");

        create(chairman(), body)
                .actions
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'areaM2')].code").value("AREA_INVALID"))
                .andExpect(jsonPath("$.errors[?(@.field == 'cadastralNumber')].code").value("CADASTRAL_INVALID"));
    }

    // ---- uniqueness of the number

    @Test
    void aDuplicateNumberIsRefusedIgnoringCaseAndSurroundingSpaces() throws Exception {
        String chairman = chairman();
        String number = "Dup-" + tag();
        create(chairman, body(number, "PLOT", "600")).actions.andExpect(status().isCreated());

        for (String variant : List.of(number, number.toUpperCase(), number.toLowerCase(), "  " + number + " ")) {
            create(chairman, body(variant, "PLOT", "600"))
                    .actions
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("PLOT_NUMBER_TAKEN"))
                    .andExpect(jsonPath("$.errors[0].field").value("number"))
                    .andExpect(jsonPath("$.errors[0].code").value("PLOT_NUMBER_TAKEN"));
        }
    }

    @Test
    void aCyrillicNumberCollidesIgnoringCase() throws Exception {
        // the demo cooperative has plot "12а" with a Cyrillic small a
        create(chairman(), body("12А", "PLOT", "600"))
                .actions
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_NUMBER_TAKEN"));
    }

    @Test
    void theSameNumberInAnotherCooperativeIsAllowed() throws Exception {
        String number = number();
        create(chairman(), body(number, "PLOT", "600")).actions.andExpect(status().isCreated());

        Created other = create(otherChairman(), body(number, "PLOT", "300"));

        other.actions.andExpect(status().isCreated());
        assertThat(other.tenant).isEqualTo(OTHER_TENANT);
        UUID storedTenant = ownerJdbc().queryForObject("select tenant_id from plot where id = ?", UUID.class, other.id);
        assertThat(storedTenant).isEqualTo(OTHER_TENANT);
    }

    @Test
    void anArchivedPlotFreesItsNumber() throws Exception {
        String chairman = chairman();
        String number = number();
        Created first = create(chairman, body(number, "PLOT", "600"));
        first.actions.andExpect(status().isCreated());
        archiveInDatabase(first.id);

        create(chairman, body(number, "PLOT", "600")).actions.andExpect(status().isCreated());
    }

    @Test
    void updatingAPlotToAnotherPlotsNumberIsRefusedButKeepingItsOwnIsFine() throws Exception {
        String chairman = chairman();
        String taken = number();
        create(chairman, body(taken, "PLOT", "600")).actions.andExpect(status().isCreated());
        Created second = create(chairman, body(number(), "PLOT", "600"));
        String secondNumber = second.number;

        send(chairman, put(PLOTS + "/" + second.id), body(taken.toUpperCase(), "PLOT", "600"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLOT_NUMBER_TAKEN"))
                .andExpect(jsonPath("$.errors[0].field").value("number"));
        send(chairman, put(PLOTS + "/" + second.id), body(secondNumber, "PLOT", "700"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.areaM2").value(700.0));
    }

    @Test
    void anArchivedPlotCanBeEditedWithoutCheckingItsNumber() throws Exception {
        String chairman = chairman();
        String number = number();
        Created archived = create(chairman, body(number, "PLOT", "600"));
        archiveInDatabase(archived.id);
        create(chairman, body(number, "PLOT", "500")).actions.andExpect(status().isCreated()); // took the number

        Map<String, Object> edit = body(number, "PLOT", "600");
        edit.put("street", "Changed St");
        send(chairman, put(PLOTS + "/" + archived.id), edit)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.street").value("Changed St"))
                .andExpect(jsonPath("$.archived").value(true));
    }

    // ---- uniqueness of the cadastral number

    @Test
    void aDuplicateCadastralNumberIsRefusedIncludingAgainstAnArchivedPlot() throws Exception {
        String chairman = chairman();
        String cadastral = cadastral();
        Map<String, Object> first = body(number(), "PLOT", "600");
        first.put("cadastralNumber", cadastral);
        Created original = create(chairman, first);
        original.actions.andExpect(status().isCreated());

        Map<String, Object> second = body(number(), "PLOT", "600");
        second.put("cadastralNumber", cadastral);
        create(chairman, second)
                .actions
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CADASTRAL_NUMBER_TAKEN"))
                .andExpect(jsonPath("$.errors[0].field").value("cadastralNumber"))
                .andExpect(jsonPath("$.errors[0].code").value("CADASTRAL_NUMBER_TAKEN"));

        archiveInDatabase(original.id);
        create(chairman, second)
                .actions
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CADASTRAL_NUMBER_TAKEN"));
    }

    @Test
    void theSameCadastralNumberInAnotherCooperativeIsAllowed() throws Exception {
        String cadastral = cadastral();
        Map<String, Object> demo = body(number(), "PLOT", "600");
        demo.put("cadastralNumber", cadastral);
        create(chairman(), demo).actions.andExpect(status().isCreated());

        Map<String, Object> other = body(number(), "PLOT", "600");
        other.put("cadastralNumber", cadastral);
        create(otherChairman(), other).actions.andExpect(status().isCreated());
    }

    @Test
    void updatingToAnotherPlotsCadastralNumberIsRefusedButKeepingItsOwnIsFine() throws Exception {
        String chairman = chairman();
        String cadastral = cadastral();
        Map<String, Object> first = body(number(), "PLOT", "600");
        first.put("cadastralNumber", cadastral);
        create(chairman, first).actions.andExpect(status().isCreated());
        String secondCadastral = cadastral();
        Map<String, Object> secondBody = body(number(), "PLOT", "600");
        secondBody.put("cadastralNumber", secondCadastral);
        Created second = create(chairman, secondBody);

        Map<String, Object> conflicting = body(second.number, "PLOT", "600");
        conflicting.put("cadastralNumber", cadastral);
        send(chairman, put(PLOTS + "/" + second.id), conflicting)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CADASTRAL_NUMBER_TAKEN"))
                .andExpect(jsonPath("$.errors[0].field").value("cadastralNumber"));
        Map<String, Object> own = body(second.number, "PLOT", "650");
        own.put("cadastralNumber", secondCadastral);
        send(chairman, put(PLOTS + "/" + second.id), own).andExpect(status().isOk());
    }

    // ---- concurrency

    @Test
    void twoConcurrentCreationsOfTheSameNumberEndAsOneCreatedAndOneNumberTaken() throws Exception {
        // Both requests may pass the friendly pre-check; the unique index decides and must surface as 409, never 500.
        String chairman = chairman();
        send(chairman, get("/api/v1/me"), null)
                .andExpect(status().isOk()); // first sign-in links the member
        for (int round = 0; round < 5; round++) {
            String number = number();
            List<ResultActions> results = ConcurrentRequests.run(
                    () -> send(chairman, post(PLOTS), body(number, "PLOT", "600")),
                    () -> send(chairman, post(PLOTS), body(number.toLowerCase(), "PLOT", "600")));
            List<Integer> statuses = results.stream().map(r -> r.andReturn().getResponse().getStatus()).sorted()
                    .toList();
            for (ResultActions result : results) {
                if (result.andReturn().getResponse().getStatus() == 201) {
                    track(DEMO_TENANT, result);
                }
            }
            assertThat(statuses).containsExactly(201, 409);
            for (ResultActions result : results) {
                if (result.andReturn().getResponse().getStatus() == 409) {
                    assertThat(result.andReturn().getResponse().getContentAsString()).contains("PLOT_NUMBER_TAKEN");
                }
            }
        }
    }

    @Test
    void twoConcurrentCreationsOfTheSameCadastralNumberEndAsOneCreatedAndOneCadastralTaken() throws Exception {
        String chairman = chairman();
        send(chairman, get("/api/v1/me"), null)
                .andExpect(status().isOk());
        String cadastral = cadastral();
        Map<String, Object> a = body(number(), "PLOT", "600");
        a.put("cadastralNumber", cadastral);
        Map<String, Object> b = body(number(), "PLOT", "600");
        b.put("cadastralNumber", cadastral);

        List<ResultActions> results = ConcurrentRequests.run(
                () -> send(chairman, post(PLOTS), a),
                () -> send(chairman, post(PLOTS), b));

        for (ResultActions result : results) {
            if (result.andReturn().getResponse().getStatus() == 201) {
                track(DEMO_TENANT, result);
            }
        }
        assertThat(results.stream().map(r -> r.andReturn().getResponse().getStatus()).sorted().toList())
                .containsExactly(201, 409);
        for (ResultActions result : results) {
            if (result.andReturn().getResponse().getStatus() == 409) {
                assertThat(result.andReturn().getResponse().getContentAsString()).contains("CADASTRAL_NUMBER_TAKEN");
            }
        }
    }

    // ---- update

    @Test
    void updateReplacesAllFieldsAndClearsOptionalOnesThatAreAbsent() throws Exception {
        String chairman = chairman();
        Map<String, Object> original = body(number(), "PLOT", "600");
        original.put("street", "Garden St");
        original.put("cadastralNumber", cadastral());
        original.put("hasBuilding", true);
        original.put("notes", "note");
        Created created = create(chairman, original);
        created.actions.andExpect(status().isCreated());
        Object createdAt = inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select created_at from plot where id = ?", java.sql.Timestamp.class, created.id));

        String newNumber = number();
        Map<String, Object> replacement = body(newNumber, "COMMON", "0");
        send(chairman, put(PLOTS + "/" + created.id), replacement)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(newNumber))
                .andExpect(jsonPath("$.kind").value("COMMON"))
                .andExpect(jsonPath("$.areaM2").value(0.0))
                .andExpect(jsonPath("$.hasBuilding").value(false))
                .andExpect(jsonPath("$", not(hasKey("street"))))
                .andExpect(jsonPath("$", not(hasKey("cadastralNumber"))))
                .andExpect(jsonPath("$", not(hasKey("notes"))))
                .andExpect(jsonPath("$.status").value("GREY"));

        Map<String, Object> stored = inTenant(DEMO_TENANT, sql -> sql.queryForMap(
                "select street, cadastral_number, notes, created_at, updated_at from plot where id = ?", created.id));
        assertThat(stored.get("street")).isNull();
        assertThat(stored.get("cadastral_number")).isNull();
        assertThat(stored.get("notes")).isNull();
        assertThat(stored.get("created_at")).isEqualTo(createdAt);
        assertThat(((java.sql.Timestamp) stored.get("updated_at")).after((java.sql.Timestamp) createdAt)).isTrue();
    }

    @Test
    void updateOfAnUnknownPlotOrAPlotOfAnotherCooperativeIsNotFound() throws Exception {
        UUID foreign = ownerJdbc().queryForObject("select id from plot where tenant_id = ? limit 1", UUID.class,
                OTHER_TENANT);
        String chairman = chairman();

        for (UUID id : List.of(UUID.randomUUID(), foreign)) {
            send(chairman, put(PLOTS + "/" + id), body(number(), "PLOT", "600"))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Test
    void updateValidatesLikeCreate() throws Exception {
        String chairman = chairman();
        Created created = create(chairman, body(number(), "PLOT", "600"));
        created.actions.andExpect(status().isCreated());

        send(chairman, put(PLOTS + "/" + created.id), body(created.number, "PLOT", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("AREA_INVALID"));
        Map<String, Object> badCadastral = body(created.number, "PLOT", "600");
        badCadastral.put("cadastralNumber", "nope");
        send(chairman, put(PLOTS + "/" + created.id), badCadastral)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("CADASTRAL_INVALID"));
    }

    // ---- permission

    @Test
    void writesAreDeniedWithoutPlotsEdit() throws Exception {
        UUID target = createdByFixture();
        for (String token : List.of(treasurer(), member())) {
            send(token, post(PLOTS), body(number(), "PLOT", "600"))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            send(token, put(PLOTS + "/" + target), body("denied", "PLOT", "600"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        Integer denied = inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select count(*) from plot where number = 'denied'", Integer.class));
        assertThat(denied).isZero();
    }

    @Test
    void writesAreDeniedForThePlatformOperator() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        send(operator, post(PLOTS), body(number(), "PLOT", "600"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        send(operator, put(PLOTS + "/" + UUID.randomUUID()), body(number(), "PLOT", "600"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void writesRequireAToken() throws Exception {
        mvc.perform(post(PLOTS).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body(number(), "PLOT", "600"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(put(PLOTS + "/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body(number(), "PLOT", "600"))))
                .andExpect(status().isUnauthorized());
    }

    // ---- audit

    @Test
    void createAndUpdateAreAuditedWithTheChangedFields() throws Exception {
        String chairman = chairman();
        String number = number();
        Map<String, Object> original = body(number, "PLOT", "600");
        original.put("street", "Garden St");
        Created created = create(chairman, original);
        created.actions.andExpect(status().isCreated());

        List<Map<String, Object>> afterCreate = awaitAudit(created.id, 1);
        assertThat(afterCreate.get(0).get("action")).isEqualTo("PLOT_CREATED");
        assertThat(afterCreate.get(0).get("entity_type")).isEqualTo("PLOT");
        assertThat(afterCreate.get(0).get("entity_label")).isEqualTo(number);
        JsonNode createdChanges = json.readTree((String) afterCreate.get(0).get("changes"));
        assertThat(change(createdChanges, "street").get("new").asString()).isEqualTo("Garden St");
        assertThat(change(createdChanges, "areaM2").get("new").asString()).isEqualTo("600.00");
        assertThat(change(createdChanges, "areaM2").get("old").isNull()).isTrue();

        Map<String, Object> changed = body(number, "PLOT", "650.5");
        changed.put("street", "Orchard St");
        changed.put("hasBuilding", true);
        send(chairman, put(PLOTS + "/" + created.id), changed).andExpect(status().isOk());

        List<Map<String, Object>> entries = awaitAudit(created.id, 2);
        Map<String, Object> update = entries.get(1);
        assertThat(update.get("action")).isEqualTo("PLOT_UPDATED");
        assertThat(update.get("entity_type")).isEqualTo("PLOT");
        assertThat(update.get("entity_label")).isEqualTo(number);
        assertThat(update.get("actor_member_id")).isNotNull();
        JsonNode changes = json.readTree((String) update.get("changes"));
        assertThat(changes).hasSize(3);
        assertThat(change(changes, "street").get("old").asString()).isEqualTo("Garden St");
        assertThat(change(changes, "street").get("new").asString()).isEqualTo("Orchard St");
        assertThat(change(changes, "areaM2").get("old").asString()).isEqualTo("600.00");
        assertThat(change(changes, "areaM2").get("new").asString()).isEqualTo("650.50");
        assertThat(change(changes, "hasBuilding").get("new").asString()).isEqualTo("true");
    }

    @Test
    void updateWithoutChangesWritesNoAuditEntryAndKeepsUpdatedAt() throws Exception {
        String chairman = chairman();
        String number = number();
        Created created = create(chairman, body(number, "PLOT", "600"));
        created.actions.andExpect(status().isCreated());
        awaitAudit(created.id, 1);
        Object updatedBefore = updatedAt(created.id);

        // the same values, written differently: surrounding spaces and an extra zero
        send(chairman, put(PLOTS + "/" + created.id), body(" " + number + " ", "PLOT", "600.00"))
                .andExpect(status().isOk());
        Map<String, Object> changed = body(number, "PLOT", "601");
        send(chairman, put(PLOTS + "/" + created.id), changed).andExpect(status().isOk());

        // the real change is the second entry; the no-op update between them left none
        List<Map<String, Object>> entries = awaitAudit(created.id, 2);
        assertThat(entries).extracting(e -> e.get("action")).containsExactly("PLOT_CREATED", "PLOT_UPDATED");
        JsonNode changes = json.readTree((String) entries.get(1).get("changes"));
        assertThat(changes).hasSize(1);
        assertThat(change(changes, "areaM2").get("new").asString()).isEqualTo("601.00");
        assertThat(updatedAt(created.id)).isNotEqualTo(updatedBefore);
    }

    @Test
    void aNoOpUpdateKeepsUpdatedAt() throws Exception {
        String chairman = chairman();
        Created created = create(chairman, body(number(), "PLOT", "600"));
        created.actions.andExpect(status().isCreated());
        Object updatedBefore = updatedAt(created.id);

        send(chairman, put(PLOTS + "/" + created.id), body(created.number, "PLOT", "600")).andExpect(status().isOk());

        assertThat(updatedAt(created.id)).isEqualTo(updatedBefore);
    }

    // ---- archive, restore, delete

    @Test
    void archiveHidesThePlotFromTheDefaultListAndRestoreBringsItBack() throws Exception {
        String chairman = chairman();
        Created created = create(chairman, body(number(), "PLOT", "600"));
        created.actions.andExpect(status().isCreated());
        assertThat(listed(chairman, created.number, false)).containsExactly(created.id.toString());

        send(chairman, post(PLOTS + "/" + created.id + "/archive"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.id.toString()))
                .andExpect(jsonPath("$.archived").value(true))
                .andExpect(jsonPath("$.archivedAt").exists());
        assertThat(listed(chairman, created.number, false)).isEmpty();
        assertThat(listed(chairman, created.number, true)).containsExactly(created.id.toString());

        send(chairman, post(PLOTS + "/" + created.id + "/restore"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(false))
                .andExpect(jsonPath("$", not(hasKey("archivedAt"))));
        assertThat(listed(chairman, created.number, false)).containsExactly(created.id.toString());
    }

    @Test
    void archivingTwiceAndRestoringAnActivePlotAreNoOpsWithoutAnAuditEntry() throws Exception {
        String chairman = chairman();
        Created created = create(chairman, body(number(), "PLOT", "600"));
        created.actions.andExpect(status().isCreated());

        send(chairman, post(PLOTS + "/" + created.id + "/restore"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(false));
        send(chairman, post(PLOTS + "/" + created.id + "/archive"), null).andExpect(status().isOk());
        Object archivedAt = archivedAtOf(created.id);
        send(chairman, post(PLOTS + "/" + created.id + "/archive"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));

        assertThat(archivedAtOf(created.id)).isEqualTo(archivedAt);
        // created, then one archive; a late second entry would show up as a third one
        awaitAudit(created.id, 2);
        send(chairman, get(PLOTS + "/" + created.id), null).andExpect(status().isOk());
        assertThat(auditEntries(created.id)).extracting(e -> e.get("action"))
                .containsExactly("PLOT_CREATED", "PLOT_ARCHIVED");
    }

    @Test
    void restoreIsRefusedWhenAnotherActivePlotTookTheNumber() throws Exception {
        String chairman = chairman();
        String number = number();
        Created archived = create(chairman, body(number, "PLOT", "600"));
        archived.actions.andExpect(status().isCreated());
        send(chairman, post(PLOTS + "/" + archived.id + "/archive"), null).andExpect(status().isOk());
        create(chairman, body(number.toUpperCase(), "PLOT", "500")).actions.andExpect(status().isCreated());

        send(chairman, post(PLOTS + "/" + archived.id + "/restore"), null)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PLOT_NUMBER_TAKEN"))
                .andExpect(jsonPath("$.errors[0].field").value("number"));

        assertThat(archivedAtOf(archived.id)).isNotNull();
        awaitAudit(archived.id, 2);
        assertThat(auditEntries(archived.id)).extracting(e -> e.get("action"))
                .containsExactly("PLOT_CREATED", "PLOT_ARCHIVED");
    }

    @Test
    void deletingAnActivePlotRemovesItAndItsStatusRow() throws Exception {
        String chairman = chairman();
        Created created = create(chairman, body(number(), "PLOT", "600"));
        created.actions.andExpect(status().isCreated());

        mvcDelete(chairman, created.id).andExpect(status().isNoContent()).andExpect(content().string(""));

        send(chairman, get(PLOTS + "/" + created.id), null).andExpect(status().isNotFound());
        assertThat(listed(chairman, created.number, true)).isEmpty();
        assertThat(rowCount("plot", "id", created.id)).isZero();
        assertThat(rowCount("plot_status", "plot_id", created.id)).isZero();
        // the number is free again
        create(chairman, body(created.number, "PLOT", "600")).actions.andExpect(status().isCreated());
    }

    @Test
    void deletingAnArchivedPlotIsRefusedUntilItIsRestored() throws Exception {
        String chairman = chairman();
        Created created = create(chairman, body(number(), "PLOT", "600"));
        created.actions.andExpect(status().isCreated());
        send(chairman, post(PLOTS + "/" + created.id + "/archive"), null).andExpect(status().isOk());

        mvcDelete(chairman, created.id)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PLOT_ARCHIVED"));
        assertThat(archivedAtOf(created.id)).isNotNull();

        send(chairman, post(PLOTS + "/" + created.id + "/restore"), null).andExpect(status().isOk());
        mvcDelete(chairman, created.id).andExpect(status().isNoContent());
    }

    @Test
    void archiveRestoreAndDeleteAreDeniedWithoutPlotsEdit() throws Exception {
        UUID target = createdByFixture();
        for (String token : List.of(treasurer(), member())) {
            send(token, post(PLOTS + "/" + target + "/archive"), null)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            send(token, post(PLOTS + "/" + target + "/restore"), null)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            mvcDelete(token, target)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        assertThat(archivedAtOf(target)).isNull();
        assertThat(rowCount("plot", "id", target)).isEqualTo(1);
    }

    @Test
    void archiveRestoreAndDeleteAreDeniedForThePlatformOperatorAndNeedAToken() throws Exception {
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();
        UUID id = UUID.randomUUID();

        send(operator, post(PLOTS + "/" + id + "/archive"), null).andExpect(status().isForbidden());
        send(operator, post(PLOTS + "/" + id + "/restore"), null).andExpect(status().isForbidden());
        mvcDelete(operator, id).andExpect(status().isForbidden());
        mvc.perform(post(PLOTS + "/" + id + "/archive")).andExpect(status().isUnauthorized());
        mvc.perform(post(PLOTS + "/" + id + "/restore")).andExpect(status().isUnauthorized());
        mvc.perform(delete(PLOTS + "/" + id)).andExpect(status().isUnauthorized());
    }

    @Test
    void archiveRestoreAndDeleteOfAnUnknownPlotOrAPlotOfAnotherCooperativeAreNotFound() throws Exception {
        UUID foreign = ownerJdbc().queryForObject("select id from plot where tenant_id = ? limit 1", UUID.class,
                OTHER_TENANT);
        String chairman = chairman();

        for (UUID id : List.of(UUID.randomUUID(), foreign)) {
            send(chairman, post(PLOTS + "/" + id + "/archive"), null)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
            send(chairman, post(PLOTS + "/" + id + "/restore"), null)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
            mvcDelete(chairman, id)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        assertThat(ownerJdbc().queryForObject("select count(*) from plot where id = ?", Integer.class, foreign))
                .isEqualTo(1);
    }

    @Test
    void archiveRestoreAndDeleteAreAudited() throws Exception {
        String chairman = chairman();
        String number = number();
        Created created = create(chairman, body(number, "PLOT", "600"));
        created.actions.andExpect(status().isCreated());
        send(chairman, post(PLOTS + "/" + created.id + "/archive"), null).andExpect(status().isOk());
        send(chairman, post(PLOTS + "/" + created.id + "/restore"), null).andExpect(status().isOk());
        mvcDelete(chairman, created.id).andExpect(status().isNoContent());

        List<Map<String, Object>> entries = awaitAudit(created.id, 4);
        assertThat(entries).extracting(e -> e.get("action"))
                .containsExactly("PLOT_CREATED", "PLOT_ARCHIVED", "PLOT_RESTORED", "PLOT_DELETED");
        assertThat(entries).allSatisfy(e -> {
            assertThat(e.get("entity_type")).isEqualTo("PLOT");
            assertThat(e.get("entity_label")).isEqualTo(number);
            assertThat(e.get("actor_member_id")).isNotNull();
        });
        JsonNode archived = json.readTree((String) entries.get(1).get("changes"));
        assertThat(archived).hasSize(1);
        assertThat(change(archived, "archived").get("old").asString()).isEqualTo("false");
        assertThat(change(archived, "archived").get("new").asString()).isEqualTo("true");
        JsonNode restored = json.readTree((String) entries.get(2).get("changes"));
        assertThat(restored).hasSize(1);
        assertThat(change(restored, "archived").get("new").asString()).isEqualTo("false");
        JsonNode deleted = json.readTree((String) entries.get(3).get("changes"));
        assertThat(change(deleted, "number").get("old").asString()).isEqualTo(number);
        assertThat(change(deleted, "number").get("new").isNull()).isTrue();
        assertThat(change(deleted, "areaM2").get("old").asString()).isEqualTo("600.00");
        assertThat(change(deleted, "areaM2").get("new").isNull()).isTrue();
    }

    // ---- notes

    @Test
    void anEditorWithoutPlotsViewCannotWriteNotes() throws Exception {
        String editor = editOnlyToken();
        Map<String, Object> body = body(number(), "PLOT", "600");
        body.put("notes", "secret from an editor");

        Created created = create(editor, body);
        created.actions
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", not(hasKey("notes"))));
        assertThat(notesOf(created.id)).isNull();

        // the chairman adds a note; a later edit by the editor must keep it
        Map<String, Object> withNote = body(created.number, "PLOT", "600");
        withNote.put("notes", "chairman note");
        send(chairman(), put(PLOTS + "/" + created.id), withNote)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("chairman note"));
        Map<String, Object> edited = body(created.number, "PLOT", "700");
        edited.put("notes", "overwritten by editor");
        send(editor, put(PLOTS + "/" + created.id), edited)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", not(hasKey("notes"))));

        assertThat(notesOf(created.id)).isEqualTo("chairman note");
        BigDecimal area = inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select area_m2 from plot where id = ?", BigDecimal.class, created.id));
        assertThat(area).isEqualByComparingTo("700");
    }

    @Test
    void anEditorWithPlotsViewReplacesNotes() throws Exception {
        String chairman = chairman();
        Created created = create(chairman, body(number(), "PLOT", "600"));
        Map<String, Object> body = body(created.number, "PLOT", "600");
        body.put("notes", "new note");

        send(chairman, put(PLOTS + "/" + created.id), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("new note"));
        assertThat(notesOf(created.id)).isEqualTo("new note");
    }

    // ---- helpers

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String number() {
        return "W-" + tag();
    }

    /** A well-formed cadastral number that no other test uses. */
    private static String cadastral() {
        long random = Math.abs(UUID.randomUUID().getMostSignificantBits());
        String digits = String.format("%010d", random % 10_000_000_000L);
        return digits + ":99:" + String.format("%03d", random % 1000) + ":" + String.format("%04d", random % 10_000);
    }

    private String chairman() {
        return bearerWithBaseRole(DEMO_TENANT, "CHAIRMAN");
    }

    private String treasurer() {
        return bearerWithBaseRole(DEMO_TENANT, "TREASURER");
    }

    private String member() {
        return bearerWithBaseRole(DEMO_TENANT, "MEMBER");
    }

    private String otherChairman() {
        return bearerWithBaseRole(OTHER_TENANT, "CHAIRMAN");
    }

    /** A token for a fresh member of the cooperative that holds the given base role. */
    private String bearerWithBaseRole(UUID tenant, String baseCode) {
        String email = "plots-write-" + baseCode.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        UUID member = newMember(tenant, email);
        giveBaseRole(tenant, member, baseCode);
        String bearer = TestTokens.bearer(tenant, UUID.randomUUID().toString(), email);
        tokenTenants.put(bearer, tenant);
        return bearer;
    }

    /** A token of a member whose only permission is PLOTS_EDIT (no PLOTS_VIEW). */
    private String editOnlyToken() {
        String email = "plots-editor-" + UUID.randomUUID() + "@example.com";
        UUID member = newMember(DEMO_TENANT, email);
        UUID role = UUID.randomUUID();
        roles.add(role);
        inTenant(DEMO_TENANT, sql -> {
            sql.update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, DEMO_TENANT,
                    "PlotEditOnly " + role);
            sql.update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, 'PLOTS_EDIT')",
                    DEMO_TENANT, role);
            return sql.update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)",
                    DEMO_TENANT, member, role);
        });
        String bearer = TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email);
        tokenTenants.put(bearer, DEMO_TENANT);
        return bearer;
    }

    private String notesOf(UUID id) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject("select notes from plot where id = ?", String.class, id));
    }

    private static Map<String, Object> body(String number, String kind, String area) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("number", number);
        body.put("kind", kind);
        body.put("areaM2", new BigDecimal(area));
        return body;
    }

    private ResultActions send(String bearer, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private record Created(ResultActions actions, UUID id, String number, UUID tenant) {
    }

    /** POST /plots; removes the plot after the test when it was created. */
    private Created create(String bearer, Map<String, Object> body) throws Exception {
        UUID tenant = tenantOf(bearer);
        ResultActions actions = send(bearer, post(PLOTS), body);
        UUID id = track(tenant, actions);
        return new Created(actions, id, (String) body.get("number"), tenant);
    }

    private UUID track(UUID tenant, ResultActions actions) throws Exception {
        if (actions.andReturn().getResponse().getStatus() != 201) {
            return null;
        }
        UUID id = UUID.fromString(json.readTree(actions.andReturn().getResponse().getContentAsString())
                .get("id").asString());
        createdThroughApi.add(new Created(actions, id, null, tenant));
        return id;
    }

    private UUID tenantOf(String bearer) {
        return tokenTenants.get(bearer);
    }

    /** A plot created straight in the database, removed after the test. */
    private UUID createdByFixture() {
        UUID id = UUID.randomUUID();
        inTenant(DEMO_TENANT, sql -> {
            sql.update("insert into plot (id, tenant_id, number, kind, area_m2) values (?, ?, ?, 'PLOT', 100)", id,
                    DEMO_TENANT, "F-" + tag());
            return sql.update("insert into plot_status (plot_id, tenant_id, status) values (?, ?, 'GREY')", id,
                    DEMO_TENANT);
        });
        createdThroughApi.add(new Created(null, id, null, DEMO_TENANT));
        return id;
    }

    private void archiveInDatabase(UUID id) {
        inTenant(DEMO_TENANT, sql -> sql.update("update plot set archived_at = now() where id = ?", id));
    }

    private ResultActions mvcDelete(String bearer, UUID id) throws Exception {
        return send(bearer, delete(PLOTS + "/" + id), null);
    }

    /** The ids on the first page of the plots list for a search text, with or without archived plots. */
    private List<String> listed(String bearer, String q, boolean includeArchived) throws Exception {
        String content = send(bearer, get(PLOTS).param("q", q).param("includeArchived", String.valueOf(includeArchived)),
                null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> ids = new ArrayList<>();
        json.readTree(content).get("items").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    /** Rows of a plot table for an id; the names are fixed by the tests, never user input. */
    private int rowCount(String table, String column, UUID id) {
        Integer count = inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select count(*) from " + table + " where " + column + " = ?", Integer.class, id));
        return count;
    }

    private Object archivedAtOf(UUID id) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select archived_at from plot where id = ?", java.sql.Timestamp.class, id));
    }

    private Object updatedAt(UUID id) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select updated_at from plot where id = ?", java.sql.Timestamp.class, id));
    }

    /** Waits until the entity has {@code count} audit entries (written asynchronously) and returns them, oldest first. */
    private List<Map<String, Object>> awaitAudit(UUID entityId, int count) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(auditEntries(entityId)).hasSize(count));
        return auditEntries(entityId);
    }

    private List<Map<String, Object>> auditEntries(UUID entityId) {
        return inTenant(DEMO_TENANT, sql -> sql.queryForList(
                "select action, actor_member_id, entity_type, entity_label, cast(changes as text) as changes "
                        + "from audit_entry where entity_id = ? order by occurred_at, action desc", entityId));
    }

    private static JsonNode change(JsonNode changes, String field) {
        for (JsonNode change : changes) {
            if (field.equals(change.get("field").asString())) {
                return change;
            }
        }
        throw new AssertionError("no change for " + field);
    }
}
