package app.nadel.imports.internal;

import static app.nadel.imports.internal.ImportWorkbooks.METER_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.METER_HEADER_UK;
import static app.nadel.imports.internal.ImportWorkbooks.PLOT_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.member;
import static app.nadel.imports.internal.ImportWorkbooks.meter;
import static app.nadel.imports.internal.ImportWorkbooks.owner;
import static app.nadel.imports.internal.ImportWorkbooks.plot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import app.nadel.audit.AuditBatch;
import app.nadel.audit.AuditEvent;
import app.nadel.plots.PlotImport;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * POST /imports/{id}/commit: everything the preview announced is saved in one transaction or nothing is; a changed
 * cooperative, an expired preview, a repeated commit and a failure half-way are refused or rolled back whole.
 */
@RecordApplicationEvents
class ImportCommitTests extends ImportCommitSupport {

    private static final LocalDate SINCE = LocalDate.of(2020, 3, 1);

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(java.time.ZoneId.of("Europe/Kyiv"));
        }
    }

    @Autowired
    MutableClock clock;

    @Autowired
    ApplicationEvents applicationEvents;

    @Autowired
    PlatformTransactionManager transactions;

    @MockitoSpyBean
    PlotImport plotImport;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private static List<List<?>> rows(List<?>... rows) {
        return List.of(rows);
    }

    /** Three plots (two of them owned, by co-owners), four members. */
    private byte[] threePlots() {
        return workbook(
                rows(newPlot("a"), newPlot("b"), newPlot("c")),
                rows(newMember("M-1", "One"), newMember("M-2", "Two"), newMember("M-3", "Three"),
                        newMember("M-4", "Four")),
                rows(owner(plotNo("a"), "M-1", "1/2", "01.03.2020"), owner(plotNo("a"), "M-2", "1/2", "01.03.2020"),
                        owner(plotNo("b"), "M-3", "2/3", SINCE), owner(plotNo("b"), "M-4", "1/3", SINCE)));
    }

    // ---- what a commit saves

    @Test
    void aCommitSavesEverythingAndReturnsThePreviewCounts() throws Exception {
        JsonNode preview = preview(threePlots());
        String id = preview.get("id").asString();

        commit(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.counts.plots.created").value(3))
                .andExpect(jsonPath("$.counts.plots.updated").value(0))
                .andExpect(jsonPath("$.counts.plots.unchanged").value(0))
                .andExpect(jsonPath("$.counts.members.created").value(4))
                .andExpect(jsonPath("$.counts.ownerships.created").value(4))
                .andExpect(jsonPath("$.counts.ownerships.unchanged").value(0));

        assertThat(preview.get("counts").get("plots").get("created").asInt()).isEqualTo(3);
        assertThat(plotsLike("CMT-" + tag)).isEqualTo(3);
        assertThat(membersLike("Fixt" + tag)).isEqualTo(4);
        assertThat(ownershipOfPlotsLike("CMT-" + tag)).isEqualTo(4);
        // owned plots are GREEN, the free one stays GREY
        assertThat(statusOfPlot(plotNo("a"))).isEqualTo("GREEN");
        assertThat(statusOfPlot(plotNo("b"))).isEqualTo("GREEN");
        assertThat(statusOfPlot(plotNo("c"))).isEqualTo("GREY");
        // the ids are those of the plan: derived from the import id and the key
        assertThat(ownerJdbc().queryForObject("select count(*) from plot where id = ?", Integer.class,
                plotId(id, plotNo("a")))).isEqualTo(1);
        assertThat(ownerJdbc().queryForObject("select email from member where id = ?", String.class,
                memberId(id, "M-3"))).isEqualTo(email("three"));
        List<Map<String, Object>> shares = ownerJdbc().queryForList(
                "select m.email, o.share_numerator, o.share_denominator, o.valid_from, o.valid_to "
                        + "from ownership o join member m on m.id = o.member_id where o.plot_id = ? order by o.share_numerator desc",
                plotId(id, plotNo("b")));
        assertThat(shares).hasSize(2);
        assertThat(shares.get(0)).containsEntry("share_numerator", 2).containsEntry("share_denominator", 3);
        assertThat(shares.get(0).get("valid_from").toString()).isEqualTo("2020-03-01");
        assertThat(shares.get(0).get("valid_to")).isNull();

        Map<String, Object> run = runRow(id);
        assertThat(run).containsEntry("status", "COMMITTED");
        assertThat(run.get("plan")).isNull();
        assertThat(run.get("plan_digest")).isNull();
        assertThat(run.get("committed_at")).isNotNull();
    }

    @Test
    void theImportAndEveryRecordAreAudited() throws Exception {
        String id = preview(threePlots()).get("id").asString();

        commit(id).andExpect(status().isOk());

        awaitAudit("IMPORT_COMMITTED", "plots.xlsx", 1);
        awaitAudit("PLOT_CREATED", "CMT-" + tag, 3);
        awaitAudit("MEMBER_CREATED", "Fixt" + tag, 4);
        awaitAudit("OWNERSHIP_CHANGED", "CMT-" + tag, 2);
        Map<String, Object> summary = ownerJdbc().queryForMap("select actor_subject, entity_type, entity_id, "
                + "cast(changes as text) as changes from audit_entry where action = 'IMPORT_COMMITTED' "
                + "and entity_id = ?::uuid", id);
        assertThat(summary).containsEntry("entity_type", "IMPORT").containsEntry("actor_subject",
                "sub-chairman@demo.local");
        JsonNode changes = JSON.readTree((String) summary.get("changes"));
        assertThat(field(changes, "plotsCreated")).isEqualTo("3");
        assertThat(field(changes, "membersCreated")).isEqualTo("4");
        assertThat(field(changes, "ownershipsCreated")).isEqualTo("4");
        assertThat(field(changes, "plotsUpdated")).isEqualTo("0");
        String owners = ownerJdbc().queryForObject("select cast(changes as text) from audit_entry "
                + "where action = 'OWNERSHIP_CHANGED' and entity_label = ?", String.class, plotNo("a"));
        JsonNode ownersChange = JSON.readTree(owners);
        assertThat(field(ownersChange, "effectiveFrom")).isEqualTo("2020-03-01");
        assertThat(field(ownersChange, "owners")).isEqualTo("[{\"member\":\"Fixt" + tag
                + "One Person\",\"share\":\"1/2\"},{\"member\":\"Fixt" + tag + "Two Person\",\"share\":\"1/2\"}]");
    }

    @Test
    void theRecordsAreAuditedAsOneBatchAndOnlyTheSummaryIsASingleEvent() throws Exception {
        String id = preview(threePlots()).get("id").asString();

        commit(id).andExpect(status().isOk());

        List<AuditEvent> singles = applicationEvents.stream(AuditEvent.class).toList();
        assertThat(singles).extracting(AuditEvent::action).containsExactly("IMPORT_COMMITTED");
        List<AuditBatch> batches = applicationEvents.stream(AuditBatch.class).toList();
        assertThat(batches).hasSize(1);
        // 3 plots + 4 members + 2 owned plots
        assertThat(batches.getFirst().entries()).hasSize(9)
                .extracting(AuditEvent::action).containsOnly("PLOT_CREATED", "MEMBER_CREATED", "OWNERSHIP_CHANGED");
        assertThat(batches.getFirst().tenantId()).isEqualTo(DEMO_TENANT);
    }

    private static String field(JsonNode changes, String name) {
        for (JsonNode change : changes) {
            if (name.equals(change.get("field").asString())) {
                return change.get("new").asString();
            }
        }
        return null;
    }

    @Test
    void theCommittedValidWorkbookShowsEveryPlotWithItsOwners() throws Exception {
        byte[] file = ImportFixtures.class.getResourceAsStream(ImportFixtures.VALID).readAllBytes();
        String id = preview(file).get("id").asString();

        commit(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.plots.created").value(30))
                .andExpect(jsonPath("$.counts.members.created").value(35))
                .andExpect(jsonPath("$.counts.ownerships.created").value(35));

        assertThat(plotsLike("IMP-")).isEqualTo(30);
        assertThat(membersLike("Fixt")).isEqualTo(35);
        assertThat(ownershipOfPlotsLike("IMP-")).isEqualTo(35);
        assertThat(ownerJdbc().queryForObject("select count(*) from plot_status s join plot p on p.id = s.plot_id "
                + "where p.number like 'IMP-%' and s.status = 'GREEN'", Integer.class)).isEqualTo(30);
        // 6 sotky are 600 square metres
        assertThat(ownerJdbc().queryForObject("select area_m2 from plot where number = 'IMP-05'",
                java.math.BigDecimal.class)).isEqualByComparingTo("600");

        // uploading the same file again: nothing is new, nothing would change
        preview(file);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(IMPORTS)
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "valid.xlsx", XLSX, file))
                        .header(org.springframework.http.HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.counts.plots.unchanged").value(30))
                .andExpect(jsonPath("$.counts.members.unchanged").value(35))
                .andExpect(jsonPath("$.counts.ownerships.unchanged").value(35))
                .andExpect(jsonPath("$.committable").value(false));
    }

    @Test
    void aSecondUploadOfTheSameFileHasNothingToCommitAndChangesNothing() throws Exception {
        commit(preview(threePlots()).get("id").asString()).andExpect(status().isOk());
        awaitAudit("IMPORT_COMMITTED", "plots.xlsx", 1);
        JsonNode again = preview(threePlots());
        assertThat(again.get("committable").asBoolean()).isFalse();

        commit(again.get("id").asString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_NOTHING_TO_COMMIT"));

        assertThat(plotsLike("CMT-" + tag)).isEqualTo(3);
        assertThat(membersLike("Fixt" + tag)).isEqualTo(4);
        assertThat(ownershipOfPlotsLike("CMT-" + tag)).isEqualTo(4);
        assertThat(runRow(again.get("id").asString())).containsEntry("status", "PREVIEW");
        assertThat(auditLike("IMPORT_COMMITTED", "plots.xlsx")).isEqualTo(1);
    }

    @Test
    void anUpdateChangesThePlotAndTheMemberFoundByEmail() throws Exception {
        String first = preview(workbook(
                rows(plot(plotNo("a"), "Garden St", "PLOT", 600, null, null, null, "Keep me")),
                rows(newMember("M-1", "One")), rows())).get("id").asString();
        commit(first).andExpect(status().isOk());
        UUID member = memberId(first, "M-1");

        String second = preview(workbook(
                rows(plot(plotNo("a"), "Orchard St", null, null, null, null, null, null)),
                rows(member("X-9", "Fixt" + tag + "One", "Person", null, "+380501112233", email("one"), null, null,
                        null, null)),
                rows())).get("id").asString();
        commit(second)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.plots.updated").value(1))
                .andExpect(jsonPath("$.counts.plots.created").value(0))
                .andExpect(jsonPath("$.counts.members.updated").value(1))
                .andExpect(jsonPath("$.counts.members.created").value(0));

        Map<String, Object> plot = ownerJdbc().queryForMap("select street, notes from plot where number = ?",
                plotNo("a"));
        assertThat(plot).containsEntry("street", "Orchard St").containsEntry("notes", "Keep me");
        assertThat(membersLike("Fixt" + tag)).isEqualTo(1);
        assertThat(ownerJdbc().queryForObject("select array_to_string(phones, ',') from member where id = ?",
                String.class, member)).isEqualTo("+380501112233");
        awaitAudit("PLOT_UPDATED", plotNo("a"), 1);
        awaitAudit("MEMBER_UPDATED", "Fixt" + tag, 1);
    }

    @Test
    void firstOwnersOnAnExistingFreePlotMakeItGreen() throws Exception {
        UUID plot = ownerJdbc().queryForObject("select id from plot where tenant_id = ? and number = ?", UUID.class,
                DEMO_TENANT, FREE_DEMO_PLOT);
        assertThat(ownerJdbc().queryForObject("select status from plot_status where plot_id = ?", String.class, plot))
                .isEqualTo("GREY");
        String id = preview(workbook(rows(), rows(newMember("M-1", "One"), newMember("M-2", "Two")),
                rows(owner(FREE_DEMO_PLOT, "M-1", "2/3", SINCE), owner(FREE_DEMO_PLOT, "M-2", "1/3", SINCE))))
                .get("id").asString();

        commit(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.plots.created").value(0))
                .andExpect(jsonPath("$.counts.ownerships.created").value(2));

        assertThat(ownerJdbc().queryForObject("select status from plot_status where plot_id = ?", String.class, plot))
                .isEqualTo("GREEN");
        assertThat(ownerJdbc().queryForObject("select count(*) from ownership where plot_id = ? "
                + "and valid_to is null", Integer.class, plot)).isEqualTo(2);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(ownerJdbc().queryForObject(
                "select count(*) from audit_entry where action = 'OWNERSHIP_CHANGED' and entity_id = ?",
                Integer.class, plot)).isEqualTo(1));
    }

    // ---- meters

    @Test
    void aCommitRegistersTheMetersOfTheFileAuditsThemAndAReimportIsUnchanged() throws Exception {
        UUID stored = storedPlot("s");
        byte[] file = workbook(rows(newPlot("a")), rows(), rows(), rows(
                meter(plotNo("a"), "ELECTRICITY", serial("1"), SINCE, new BigDecimal("1250.5")),
                meter(plotNo("s"), "WATER", serial("2"), "01.03.2020", 7)));
        String id = preview(file).get("id").asString();

        commit(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.plots.created").value(1))
                .andExpect(jsonPath("$.counts.meters.created").value(2))
                .andExpect(jsonPath("$.counts.meters.updated").value(0))
                .andExpect(jsonPath("$.counts.meters.unchanged").value(0));

        // the ids are those of the plan: derived from the import id, the plot and the resource
        Map<String, Object> electricity = ownerJdbc().queryForMap("select kind, resource, serial, plot_id, "
                + "installed_on, initial_value, removed_on, name from meter where id = ?",
                meterId(id, plotNo("a"), "ELECTRICITY"));
        assertThat(electricity).containsEntry("kind", "PLOT").containsEntry("resource", "ELECTRICITY")
                .containsEntry("serial", serial("1")).containsEntry("plot_id", plotId(id, plotNo("a")))
                .containsEntry("removed_on", null).containsEntry("name", null);
        assertThat(electricity.get("installed_on").toString()).isEqualTo("2020-03-01");
        assertThat((BigDecimal) electricity.get("initial_value")).isEqualByComparingTo("1250.5");
        assertThat(ownerJdbc().queryForObject("select plot_id from meter where id = ?", UUID.class,
                meterId(id, plotNo("s"), "WATER"))).isEqualTo(stored);

        awaitAudit("METER_REGISTERED", "IMT-" + tag, 2);
        awaitAudit("IMPORT_COMMITTED", "plots.xlsx", 1);
        JsonNode registered = JSON.readTree(ownerJdbc().queryForObject("select cast(changes as text) from audit_entry "
                + "where action = 'METER_REGISTERED' and entity_id = ?", String.class,
                meterId(id, plotNo("a"), "ELECTRICITY")));
        assertThat(field(registered, "plot")).isEqualTo(plotNo("a"));
        assertThat(field(registered, "resource")).isEqualTo("ELECTRICITY");
        assertThat(field(registered, "initialReading")).isEqualTo("1250.500");
        assertThat(field(registered, "installedOn")).isEqualTo("2020-03-01");
        JsonNode summary = JSON.readTree(ownerJdbc().queryForObject("select cast(changes as text) from audit_entry "
                + "where action = 'IMPORT_COMMITTED' and entity_id = ?::uuid", String.class, id));
        assertThat(field(summary, "metersCreated")).isEqualTo("2");
        assertThat(field(summary, "metersUnchanged")).isEqualTo("0");
        assertThat(applicationEvents.stream(AuditBatch.class).flatMap(batch -> batch.entries().stream())
                .map(AuditEvent::action)).contains("METER_REGISTERED", "PLOT_CREATED");

        // the same file again: the plot and both meters are already there
        upload(file)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.counts.plots.unchanged").value(1))
                .andExpect(jsonPath("$.counts.meters.unchanged").value(2))
                .andExpect(jsonPath("$.counts.meters.created").value(0))
                .andExpect(jsonPath("$.problemCount").value(0))
                .andExpect(jsonPath("$.committable").value(false));
        assertThat(metersLike("IMT-" + tag)).isEqualTo(2);
    }

    @Test
    void theMetersSheetInUkrainianAndInEnglishEndsUpInTheRegister() throws Exception {
        byte[] ukrainian = ImportWorkbooks.builder()
                .sheet("Ділянки", List.of("Номер ділянки", "Тип ділянки", "Площа, м²"),
                        List.of(plotNo("uk"), "ДІЛЯНКА", 600))
                .sheet("Лічильники", METER_HEADER_UK,
                        meter(plotNo("uk"), "електроенергія", serial("uk"), "01.03.2020", "1250,5"))
                .build();
        byte[] english = ImportWorkbooks.builder()
                .sheet("Plots", PLOT_HEADER, newPlot("en"))
                .sheet("Meters", METER_HEADER, meter(plotNo("en"), "WATER", serial("en"), SINCE, 12))
                .build();

        for (byte[] file : List.of(ukrainian, english)) {
            JsonNode preview = preview(file);
            assertThat(preview.get("problemCount").asInt()).isZero();
            assertThat(preview.get("counts").get("meters").get("created").asInt()).isEqualTo(1);
            commit(preview.get("id").asString()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.counts.meters.created").value(1));
        }

        JsonNode register = body(mvc.perform(get("/api/v1/meters").header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(status().isOk()).andReturn());
        Map<String, JsonNode> bySerial = new java.util.HashMap<>();
        register.forEach(item -> bySerial.put(item.get("serial").asString(), item));
        assertThat(bySerial.get(serial("uk")).get("plot").get("number").asString()).isEqualTo(plotNo("uk"));
        assertThat(bySerial.get(serial("uk")).get("resource").asString()).isEqualTo("ELECTRICITY");
        assertThat(bySerial.get(serial("uk")).get("initialReading").asString()).isEqualTo("1250.500");
        assertThat(bySerial.get(serial("uk")).get("active").asBoolean()).isTrue();
        assertThat(bySerial.get(serial("en")).get("plot").get("number").asString()).isEqualTo(plotNo("en"));
        assertThat(bySerial.get(serial("en")).get("resource").asString()).isEqualTo("WATER");
        assertThat(bySerial.get(serial("en")).get("installedOn").asString()).isEqualTo("2020-03-01");

        // both files again: nothing new
        for (byte[] file : List.of(ukrainian, english)) {
            JsonNode again = preview(file);
            assertThat(again.get("counts").get("meters").get("unchanged").asInt()).isEqualTo(1);
            assertThat(again.get("committable").asBoolean()).isFalse();
        }
    }

    @Test
    void aMeterRegisteredAfterThePreviewMakesTheCommitStale() throws Exception {
        UUID plot = storedPlot("s");
        String id = preview(workbook(rows(), rows(), rows(),
                rows(meter(plotNo("s"), "ELECTRICITY", serial("file"), SINCE, 1)))).get("id").asString();
        storedMeter(plot, "ELECTRICITY", serial("meanwhile"), null);

        commit(id)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_STALE"));

        assertThat(runRow(id)).containsEntry("status", "STALE");
        assertThat(metersLike(serial("file"))).isZero();
        assertThat(metersLike("IMT-" + tag)).isEqualTo(1);
    }

    @Test
    void aMeterRemovedAfterThePreviewOfAnUnchangedRowMakesTheCommitStale() throws Exception {
        UUID plot = storedPlot("s");
        UUID installed = storedMeter(plot, "ELECTRICITY", serial("kept"), null);
        String id = preview(workbook(rows(newPlot("a")), rows(), rows(),
                rows(meter(plotNo("s"), "ELECTRICITY", serial("kept"), SINCE, 1)))).get("id").asString();
        ownerJdbc().update("update meter set removed_on = date '2021-01-01', final_value = 300 where id = ?",
                installed);

        commit(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IMPORT_STALE"));

        assertThat(plotsLike(plotNo("a"))).isZero();
        assertThat(metersLike("IMT-" + tag)).isEqualTo(1);
    }

    // ---- refusals

    @Test
    void aPlotCreatedAfterThePreviewMakesTheCommitStale() throws Exception {
        String id = preview(threePlots()).get("id").asString();
        ownerJdbc().update("insert into plot (id, tenant_id, number, kind, area_m2) values (?, ?, ?, 'PLOT', 500)",
                UUID.randomUUID(), DEMO_TENANT, plotNo("a"));

        commit(id)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_STALE"));

        Map<String, Object> run = runRow(id);
        assertThat(run).containsEntry("status", "STALE");
        assertThat(run.get("plan")).isNull();
        assertThat(run.get("plan_digest")).isNull();
        // nothing of the file was saved: only the plot somebody else created is there
        assertThat(plotsLike("CMT-" + tag)).isEqualTo(1);
        assertThat(membersLike("Fixt" + tag)).isZero();
        assertThat(ownershipOfPlotsLike("CMT-" + tag)).isZero();
        // a stale run stays refused
        commit(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IMPORT_STALE"));
    }

    @Test
    void aMemberArchivedAfterThePreviewMakesTheCommitStale() throws Exception {
        UUID existing = newMember(DEMO_TENANT, email("existing"));
        String id = preview(workbook(rows(newPlot("a")),
                rows(member("E-1", "Test", "Member", email("existing"))),
                rows(owner(plotNo("a"), "E-1", "1", SINCE)))).get("id").asString();
        archiveMember(DEMO_TENANT, existing);

        commit(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IMPORT_STALE"));

        assertThat(plotsLike("CMT-" + tag)).isZero();
        assertThat(runRow(id)).containsEntry("status", "STALE");
    }

    @Test
    void aPreviewOlderThanThirtyMinutesIsRefusedAndItsPlanIsDropped() throws Exception {
        String id = preview(threePlots()).get("id").asString();
        assertThat(runRow(id).get("plan")).isNotNull();
        clock.advance(Duration.ofMinutes(31));

        commit(id)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("IMPORT_EXPIRED"));

        Map<String, Object> run = runRow(id);
        assertThat(run).containsEntry("status", "PREVIEW");
        assertThat(run.get("plan")).isNull();
        assertThat(run.get("plan_digest")).isNull();
        assertThat(plotsLike("CMT-" + tag)).isZero();
        assertThat(membersLike("Fixt" + tag)).isZero();
    }

    @Test
    void aPreviewJustInsideThirtyMinutesCanStillBeCommitted() throws Exception {
        String id = preview(threePlots()).get("id").asString();
        clock.advance(Duration.ofMinutes(29));

        commit(id).andExpect(status().isOk());
    }

    @Test
    void anImportCannotBeCommittedTwice() throws Exception {
        String id = preview(threePlots()).get("id").asString();
        commit(id).andExpect(status().isOk());

        commit(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IMPORT_ALREADY_COMMITTED"));

        assertThat(plotsLike("CMT-" + tag)).isEqualTo(3);
        assertThat(ownershipOfPlotsLike("CMT-" + tag)).isEqualTo(4);
        awaitAudit("IMPORT_COMMITTED", "plots.xlsx", 1);
    }

    @Test
    void twoCommitsAtTheSameTimeSaveOnce() throws Exception {
        String id = preview(threePlots()).get("id").asString();
        String chairman = chairman();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> attempt = () -> commit(chairman, id).andReturn().getResponse().getStatus();
            Future<Integer> one = pool.submit(attempt);
            Future<Integer> two = pool.submit(attempt);
            assertThat(List.of(one.get(), two.get())).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(plotsLike("CMT-" + tag)).isEqualTo(3);
        assertThat(ownershipOfPlotsLike("CMT-" + tag)).isEqualTo(4);
    }

    @Test
    void aPreviewWithProblemsCannotBeCommitted() throws Exception {
        JsonNode preview = preview(ImportFixtures.errors());
        assertThat(preview.get("committable").asBoolean()).isFalse();

        commit(preview.get("id").asString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_HAS_ERRORS"));
    }

    @Test
    void anUnknownImportIsNotFound() throws Exception {
        commit(UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---- failure half-way

    @Test
    void aFailureAfterTheMembersWereSavedRollsEverythingBack() throws Exception {
        String id = preview(threePlots()).get("id").asString();
        stubPlotImport(true);

        commit(id).andExpect(status().isInternalServerError());

        assertThat(membersLike("Fixt" + tag)).as("members").isZero();
        assertThat(plotsLike("CMT-" + tag)).as("plots").isZero();
        assertThat(ownershipOfPlotsLike("CMT-" + tag)).as("ownership").isZero();
        Map<String, Object> run = runRow(id);
        assertThat(run).containsEntry("status", "PREVIEW");
        assertThat(run.get("plan")).as("the preview can be tried again").isNotNull();
        // no audit entry is ever written for the rolled-back records
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6)).untilAsserted(() -> {
            assertThat(auditLike("MEMBER_CREATED", "Fixt" + tag)).isZero();
            assertThat(auditLike("IMPORT_COMMITTED", "plots.xlsx")).isZero();
        });

        // once the failure is gone the same preview commits
        stubPlotImport(false);
        commit(id).andExpect(status().isOk());
        assertThat(plotsLike("CMT-" + tag)).isEqualTo(3);
    }

    /**
     * Makes {@code PlotImport.apply} fail, or run for real again. The bean is a transactional proxy around the spy, so
     * stubbing it needs the transaction its {@code MANDATORY} advice asks for.
     */
    private void stubPlotImport(boolean fail) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            if (fail) {
                doThrow(new IllegalStateException("simulated failure")).when(plotImport)
                        .apply(any(), any(), any(), any(), any(), any());
            } else {
                doCallRealMethod().when(plotImport).apply(any(), any(), any(), any(), any(), any());
            }
        });
    }

    // ---- who may commit

    @Test
    void onlyThoseWithImportRunMayCommit() throws Exception {
        String id = UUID.randomUUID().toString();
        for (String email : List.of("treasurer@demo.local", "member@demo.local", "electrician@demo.local")) {
            commit(demo(email), id).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();
        commit(operator, id).andExpect(status().isForbidden());
    }

    @Test
    void editingPermissionsDoNotAllowCommitting() throws Exception {
        String email = "import-edit-" + UUID.randomUUID() + "@example.com";
        UUID member = newMember(DEMO_TENANT, email);
        UUID role = UUID.randomUUID();
        inTenant(DEMO_TENANT, sql -> {
            sql.update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, DEMO_TENANT, "Edit " + role);
            for (String permission : List.of("PLOTS_VIEW", "PLOTS_EDIT", "MEMBERS_VIEW", "MEMBERS_EDIT",
                    "OWNERSHIP_EDIT")) {
                sql.update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, ?)",
                        DEMO_TENANT, role, permission);
            }
            return sql.update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)",
                    DEMO_TENANT, member, role);
        });
        try {
            String id = preview(threePlots()).get("id").asString();
            commit(TestTokens.bearer(DEMO_TENANT, UUID.randomUUID().toString(), email), id)
                    .andExpect(status().isForbidden());
            assertThat(plotsLike("CMT-" + tag)).isZero();
        } finally {
            inTenant(DEMO_TENANT, sql -> {
                sql.update("delete from member_role where role_id = ?", role);
                sql.update("delete from role_permission where role_id = ?", role);
                return sql.update("delete from role where id = ?", role);
            });
        }
    }

    @Test
    void anonymousCallersGet401() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(IMPORTS + "/" + UUID.randomUUID() + "/commit"))
                .andExpect(status().isUnauthorized());
    }

    // ---- other cooperatives

    @Test
    void anotherCooperativesImportIsNotFoundAndStaysAsItWas() throws Exception {
        UUID otherRun = UUID.randomUUID();
        ownerJdbc().update("insert into import_run (id, tenant_id, file_name, created_by_subject, created_by_name, "
                + "created_at, expires_at, status, plan, plan_digest) values (?, ?, 'other.xlsx', 's', 'n', now(), "
                + "now() + interval '30 minutes', 'PREVIEW', '{\"version\": 1}'::jsonb, 'abc')", otherRun,
                OTHER_TENANT);

        commit(otherRun.toString()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));

        Map<String, Object> run = runRow(otherRun.toString());
        assertThat(run).containsEntry("status", "PREVIEW");
        assertThat(run.get("plan")).isNotNull();
    }

    @Test
    void committingInOneCooperativeNeverChangesAnother() throws Exception {
        Map<String, Integer> before = otherCooperativeCounts();
        String id = preview(threePlots()).get("id").asString();

        commit(id).andExpect(status().isOk());

        assertThat(otherCooperativeCounts()).isEqualTo(before);
        assertThat(ownerJdbc().queryForObject("select count(*) from plot where tenant_id = ? and number like ?",
                Integer.class, OTHER_TENANT, "CMT-%")).isZero();
        awaitAudit("IMPORT_COMMITTED", "plots.xlsx", 1);
        assertThat(ownerJdbc().queryForObject("select count(*) from audit_entry where tenant_id = ? "
                + "and entity_type = 'IMPORT'", Integer.class, OTHER_TENANT)).isZero();
    }

    private Map<String, Integer> otherCooperativeCounts() {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String table : List.of("plot", "member", "ownership", "plot_status", "audit_entry", "import_run")) {
            counts.put(table, ownerJdbc().queryForObject("select count(*) from " + table + " where tenant_id = ?",
                    Integer.class, OTHER_TENANT));
        }
        return counts;
    }
}
