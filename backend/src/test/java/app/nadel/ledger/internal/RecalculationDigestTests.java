package app.nadel.ledger.internal;

import static app.nadel.ledger.internal.RecalculationFixtures.ELECTRICITY;
import static app.nadel.ledger.internal.RecalculationFixtures.MEMBERSHIP;
import static app.nadel.ledger.internal.RecalculationFixtures.METER;
import static app.nadel.ledger.internal.RecalculationFixtures.OCT;
import static app.nadel.ledger.internal.RecalculationFixtures.PLOT;
import static app.nadel.ledger.internal.RecalculationFixtures.PLOT_600;
import static app.nadel.ledger.internal.RecalculationFixtures.SEP;
import static app.nadel.ledger.internal.RecalculationFixtures.SEPTEMBER_CLOSED;
import static app.nadel.ledger.internal.RecalculationFixtures.TODAY;
import static app.nadel.ledger.internal.RecalculationFixtures.expectedElectricity;
import static app.nadel.ledger.internal.RecalculationFixtures.expectedMembership;
import static app.nadel.ledger.internal.RecalculationFixtures.interval;
import static app.nadel.ledger.internal.RecalculationFixtures.membership;
import static app.nadel.ledger.internal.RecalculationFixtures.perKwh;
import static app.nadel.ledger.internal.RecalculationFixtures.perSotka;
import static app.nadel.ledger.internal.RecalculationFixtures.plan;
import static app.nadel.ledger.internal.RecalculationFixtures.point;
import static app.nadel.ledger.internal.RecalculationFixtures.snapshot;
import static app.nadel.ledger.internal.RecalculationFixtures.tariff;
import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.ledger.internal.ExpectedCharges.Expected;
import app.nadel.ledger.internal.NotChargedItemRecord.Status;
import app.nadel.ledger.internal.RecalculationDigest.Cause;
import app.nadel.ledger.internal.RecalculationDigest.Scope;
import app.nadel.ledger.internal.RecalculationFixtures.Ledger;
import app.nadel.ledger.internal.RecalculationPlanner.Plan;
import app.nadel.tariffs.TariffBook.Snapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The digest of a recalculation (feature 012, technical considerations 2.2): the same for a preview and its booking
 * whatever the order of the input and whatever ids the change generates (a new version, a new reading), different as
 * soon as an amount, the cause, the scope or the queue differs.
 */
class RecalculationDigestTests {

    private static final Scope SCOPE = new Scope(List.of(SEP, OCT), List.of(MEMBERSHIP), List.of(PLOT));
    private static final Cause CAUSE = new Cause("TARIFF_VERSION_ADD", Map.of("tariffId", "t", "validFrom",
            "2026-09-16", "rate", "60.0000"));

    private final Ledger ledger = new Ledger();
    private final UUID tariffId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();

    /** September and October booked at 50.0000; a version from 16.09 added with the id {@code newVersionId}. */
    private Plan backDated(UUID newVersionId, String rate, boolean reversed) {
        Snapshot after = snapshot(membership(tariffId, perSotka(versionId, "2026-01-01", "50.0000"),
                perSotka(newVersionId, "2026-09-16", rate)));
        List<BookedCharge> rows = new ArrayList<>(ledger.rows());
        List<Expected> expected = expectedMembership(after, PLOT_600, SEP, OCT);
        if (reversed) {
            Collections.reverse(rows);
            expected = new ArrayList<>(expected);
            Collections.reverse(expected);
        }
        return plan(rows, expected, SEPTEMBER_CLOSED, TODAY, RecalculationRecord.Cause.TARIFF_VERSION);
    }

    private void bookSeptemberAndOctober() {
        Snapshot before = snapshot(membership(tariffId, perSotka(versionId, "2026-01-01", "50.0000")));
        ledger.membership(before, PLOT_600, SEP, SEP);
        ledger.membership(before, PLOT_600, OCT, OCT);
    }

    @Test
    void aPreviewAndItsBookingHaveOneDigestAlthoughTheChangeGetsNewIds() {
        bookSeptemberAndOctober();
        // the preview rolls the new version back; the booking creates it again with another id
        Plan preview = backDated(UUID.randomUUID(), "60.0000", false);
        Plan booking = backDated(UUID.randomUUID(), "60.0000", false);

        String digest = RecalculationDigest.of(CAUSE, SCOPE, preview);
        assertThat(digest).hasSize(64).isEqualTo(RecalculationDigest.of(CAUSE, SCOPE, booking));
    }

    @Test
    void theOrderOfTheInputAndOfTheScopeDoesNotMatter() {
        bookSeptemberAndOctober();
        UUID newVersion = UUID.randomUUID();
        Scope reordered = new Scope(List.of(OCT, SEP), List.of(MEMBERSHIP), List.of(PLOT));
        Cause sameCause = new Cause("TARIFF_VERSION_ADD", Map.of("rate", "60.0000", "validFrom", "2026-09-16",
                "tariffId", "t"));

        assertThat(RecalculationDigest.of(sameCause, reordered, backDated(newVersion, "60.0000", true)))
                .isEqualTo(RecalculationDigest.of(CAUSE, SCOPE, backDated(newVersion, "60.0000", false)));
    }

    @Test
    void anotherAmountCauseOrScopeIsAnotherDigest() {
        bookSeptemberAndOctober();
        UUID newVersion = UUID.randomUUID();
        String digest = RecalculationDigest.of(CAUSE, SCOPE, backDated(newVersion, "60.0000", false));

        assertThat(RecalculationDigest.of(CAUSE, SCOPE, backDated(newVersion, "61.0000", false))).isNotEqualTo(digest);
        assertThat(RecalculationDigest.of(Cause.manual(), SCOPE, backDated(newVersion, "60.0000", false)))
                .isNotEqualTo(digest);
        assertThat(RecalculationDigest.of(CAUSE, new Scope(List.of(SEP), List.of(MEMBERSHIP), List.of(PLOT)),
                backDated(newVersion, "60.0000", false))).isNotEqualTo(digest);
        Plan plan = backDated(newVersion, "60.0000", false);
        assertThat(RecalculationDigest.of(CAUSE, SCOPE, new Plan(plan.lines(), List.of(
                new RecalculationPlanner.RunOutcome(UUID.randomUUID(), RecalculationPlanner.RunAction.REBOOK)),
                plan.queue()))).isNotEqualTo(digest);
    }

    @Test
    void theQueueGoesInByIdentityNotByTheKeyOfANewReading() {
        UUID electricityTariff = UUID.randomUUID();
        Snapshot october = snapshot(tariff(electricityTariff, ELECTRICITY, perKwh(UUID.randomUUID(), "2026-10-01",
                "4.3200")));
        // a back-dated reading entered inside a queued interval: the preview and the booking give it other ids
        Plan preview = plan(List.of(), expectedElectricity(october, interval(point("2026-09-20", "8140", "R1"),
                point("2026-10-05", "8205", "new-reading-" + UUID.randomUUID())), null), SEPTEMBER_CLOSED, TODAY,
                RecalculationRecord.Cause.READING, Set.of(NotChargedItemRecord.intervalKey(METER, "old")));
        Plan booking = plan(List.of(), expectedElectricity(october, interval(point("2026-09-20", "8140", "R1"),
                point("2026-10-05", "8205", "new-reading-" + UUID.randomUUID())), null), SEPTEMBER_CLOSED, TODAY,
                RecalculationRecord.Cause.READING, Set.of(NotChargedItemRecord.intervalKey(METER, "old")));
        assertThat(preview.queue()).extracting(RecalculationPlanner.QueueOutcome::status)
                .containsExactly(Status.OPEN, Status.OBSOLETE);
        assertThat(preview.queue().getFirst().itemKey()).isNotEqualTo(booking.queue().getFirst().itemKey());

        Scope scope = new Scope(List.of(SEP, OCT), List.of(ELECTRICITY), List.of(PLOT));
        assertThat(RecalculationDigest.of(Cause.manual(), scope, preview))
                .isEqualTo(RecalculationDigest.of(Cause.manual(), scope, booking));
        Plan noQueue = plan(List.of(), List.of(), SEPTEMBER_CLOSED, TODAY, RecalculationRecord.Cause.READING);
        assertThat(RecalculationDigest.of(Cause.manual(), scope, noQueue))
                .isNotEqualTo(RecalculationDigest.of(Cause.manual(), scope, preview));
    }
}
