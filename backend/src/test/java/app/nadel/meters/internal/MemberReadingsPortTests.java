package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.identity.AccessProfile;
import app.nadel.meters.MemberReadings;
import app.nadel.meters.MemberReadings.Accepted;
import app.nadel.meters.MemberReadings.MemberMeter;
import app.nadel.meters.MemberReadings.Problem;
import app.nadel.meters.MemberReadings.ReadingDecisionView;
import app.nadel.meters.MemberReadings.Rejected;
import app.nadel.meters.MemberReadings.SubmitResult;
import app.nadel.meters.MeterResource;
import app.nadel.meters.MeterValue;
import app.nadel.meters.ReadingStatus;
import app.nadel.tenancy.TenantContext;
import java.sql.Date;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The public {@link MemberReadings} port (feature 017 §2.5, the bot): the active meters of a plot the member owns now,
 * a submission that the account's own rules accept or refuse with the account's codes, the board's decision read back,
 * and the event {@code ReadingDecided} that approval and rejection publish with ids only.
 */
class MemberReadingsPortTests extends MeterTestSupport {

    private static final String STREET = "MT вул. Порт";

    @Autowired
    MemberReadings port;

    private UUID plot;
    private UUID meter;
    private RoleMember owner;

    @BeforeEach
    void ownPlotWithMeters() throws Exception {
        plot = newPlot(STREET);
        owner = customRoleMember(); // a member without permissions
        own(owner.id(), plot);
        meter = register(plotMeter(plot, "WATER", "MT-В-1", "2026-01-01", "100"));
        register(plotMeter(plot, "ELECTRICITY", "MT-ЕЛ-1", "2026-01-01", "8000"));
        entered(meter, kyivToday().minusDays(1).toString(), "150");
    }

    private AccessProfile member() {
        return AccessProfile.ownerOnly(DEMO_TENANT, owner.id());
    }

    private <T> T asBot(Supplier<T> work) {
        return TenantContext.runAs(DEMO_TENANT, work);
    }

    @Test
    void theMetersOfAPlotTheMemberOwnsNowAndNoneOfAnyOtherPlot() throws Exception {
        UUID notMine = newPlot(STREET);
        register(plotMeter(notMine, "ELECTRICITY", "MT-ЕЛ-2", "2026-01-01", "1"));

        List<MemberMeter> meters = asBot(() -> port.metersOf(plot, member()));

        assertThat(meters).extracting(MemberMeter::resource, MemberMeter::serial).containsExactly(
                tuple(MeterResource.ELECTRICITY, "MT-ЕЛ-1"),
                tuple(MeterResource.WATER, "MT-В-1"));
        assertThat(asBot(() -> port.metersOf(notMine, member()))).isEmpty();
        assertThat(asBot(() -> port.metersOf(UUID.randomUUID(), member()))).isEmpty();
        assertThat(asBot(() -> port.metersOf(plot, AccessProfile.ownerOnly(DEMO_TENANT, UUID.randomUUID()))))
                .isEmpty();
    }

    @Test
    void anAcceptedSubmissionWaitsForApprovalDatedTodayAndIsAuditedAsTheMembers() {
        SubmitResult result = asBot(() -> port.submit(plot, meter, "180.5", member()));

        assertThat(result).isInstanceOfSatisfying(Accepted.class, accepted -> {
            assertThat(accepted.value()).isEqualTo(MeterValue.parse("180.5"));
            assertThat(accepted.serial()).isEqualTo("MT-В-1");
            assertThat(readingStatus(accepted.readingId())).isEqualTo("PENDING");
            assertThat(ownerJdbc().queryForObject("select taken_on from meter_reading where id = ?",
                    Date.class, accepted.readingId()).toLocalDate()).isEqualTo(kyivToday());
        });
        assertThat(awaitEntries(meter, "READING_SUBMITTED", 1)).singleElement()
                .satisfies(entry -> assertThat(entry.get("actor_member_id")).isEqualTo(owner.id()));
    }

    @Test
    void aRefusalCarriesTheAccountsCodeAndParametersAndSavesNothing() {
        UUID otherPlotMeter = meterOfAnotherPlot();

        assertThat(problems(asBot(() -> port.submit(plot, meter, "149", member())))).containsExactly(new Problem(
                "READING_OUT_OF_ORDER", Map.of("previousTakenOn", kyivToday().minusDays(1).toString(),
                        "previousValue", "150.000")));
        assertThat(problems(asBot(() -> port.submit(plot, meter, "150.1234", member()))))
                .containsExactly(new Problem("METER_VALUE_INVALID", Map.of()));
        assertThat(problems(asBot(() -> port.submit(plot, meter, "сто", member()))))
                .containsExactly(new Problem("METER_VALUE_INVALID", Map.of()));
        assertThat(problems(asBot(() -> port.submit(plot, otherPlotMeter, "200", member()))))
                .containsExactly(new Problem("NOT_FOUND", Map.of()));
        assertThat(problems(asBot(() -> port.submit(plot, meter, "200", AccessProfile.ownerOnly(DEMO_TENANT,
                UUID.randomUUID()))))).containsExactly(new Problem("PLOT_NOT_FOUND", Map.of()));
        assertThat(readingCount(meter)).isEqualTo(1);
    }

    @Test
    void theDecisionIsReadBackWithTheReasonAsTyped() throws Exception {
        UUID reading = accepted(asBot(() -> port.submit(plot, meter, "180", member())));

        send(chairman(), post(READINGS + "/" + reading + "/reject"), Map.of("reason", "Фото нечітке"))
                .andExpect(status().isOk());

        assertThat(asBot(() -> port.decision(reading))).contains(new ReadingDecisionView(MeterValue.parse("180"),
                "MT-В-1", ReadingStatus.REJECTED, "Фото нечітке"));
        assertThat(asBot(() -> port.decision(UUID.randomUUID()))).isEmpty();
        assertThat(TenantContext.runAs(OTHER_TENANT, () -> port.decision(reading))).isEmpty();
    }

    @Test
    void approvalAndRejectionPublishReadingDecidedWithIdsOnly() throws Exception {
        String table = "outbox_copy_" + UUID.randomUUID().toString().replace("-", "");
        ownerJdbc().execute("create table " + table + " (event_type text, serialized_event text)");
        ownerJdbc().execute("create function " + table + "() returns trigger language plpgsql security definer as $$ "
                + "begin insert into " + table + " values (new.event_type, new.serialized_event); return new; end; $$");
        ownerJdbc().execute("create trigger " + table + " after insert on event_publication for each row "
                + "execute function " + table + "()");
        try {
            UUID approved = accepted(asBot(() -> port.submit(plot, meter, "181", member())));
            send(chairman(), post(READINGS + "/" + approved + "/approve"), null).andExpect(status().isOk());
            UUID electricity = asBot(() -> port.metersOf(plot, member())).getFirst().meterId();
            UUID rejected = accepted(asBot(() -> port.submit(plot, electricity, "8100", member())));
            send(chairman(), post(READINGS + "/" + rejected + "/reject"), Map.of("reason", "Фото нечітке"))
                    .andExpect(status().isOk());

            await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                    "select count(*) from " + table + " where event_type like '%ReadingDecided'", Integer.class) == 2);
            List<String> events = ownerJdbc().queryForList("select serialized_event from " + table
                    + " where event_type like '%ReadingDecided' order by serialized_event like '%APPROVED%' desc",
                    String.class);
            assertThat(events.get(0)).contains(approved.toString(), plot.toString(), owner.id().toString(),
                    DEMO_TENANT.toString(), "APPROVED");
            assertThat(events.get(1)).contains(rejected.toString(), "REJECTED");
            assertThat(events).allSatisfy(event -> assertThat(event)
                    .doesNotContain("Фото", "value", "reason", "MT-"));
        } finally {
            ownerJdbc().execute("drop trigger if exists " + table + " on event_publication");
            ownerJdbc().execute("drop function if exists " + table + "()");
            ownerJdbc().execute("drop table if exists " + table);
        }
    }

    private List<Problem> problems(SubmitResult result) {
        assertThat(result).isInstanceOf(Rejected.class);
        return ((Rejected) result).problems();
    }

    /** The id of the accepted reading. */
    private UUID accepted(SubmitResult result) {
        assertThat(result).isInstanceOf(Accepted.class);
        return ((Accepted) result).readingId();
    }

    private UUID meterOfAnotherPlot() {
        try {
            return register(plotMeter(newPlot(STREET), "WATER", "MT-В-2", "2026-01-01", "1"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
