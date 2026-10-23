package app.nadel.ledger.internal;

import app.nadel.ledger.internal.ExpectedCharges.Expected;
import app.nadel.ledger.internal.NotChargedItemRecord.Kind;
import app.nadel.ledger.internal.NotChargedItemRecord.Status;
import app.nadel.ledger.internal.ReadingChargePlanning.AlreadyCharged;
import app.nadel.ledger.internal.ReadingChargePlanning.Failed;
import app.nadel.ledger.internal.ReadingChargePlanning.MeterInterval;
import app.nadel.ledger.internal.ReadingChargePlanning.NewCharges;
import app.nadel.ledger.internal.ReadingChargePlanning.Nothing;
import app.nadel.ledger.internal.ReadingChargePlanning.Outcome;
import app.nadel.ledger.internal.ReadingChargePlanning.PlannedReadingCharge;
import app.nadel.ledger.internal.ReadingChargeCalculator.PricedPart;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterNetwork;
import app.nadel.meters.MeterNetwork.MeterPoints;
import app.nadel.meters.MeterNetwork.Point;
import app.nadel.meters.MeterResource;
import app.nadel.plots.PlotCatalog;
import app.nadel.settings.CooperativeSettings;
import app.nadel.settings.LossesMode;
import app.nadel.tariffs.TariffBook.ChargeTypeEntry;
import app.nadel.tariffs.TariffBook.Snapshot;
import app.nadel.tariffs.TariffResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Charges the intervals of plot meters (feature 011 §2.4) and keeps their "not charged" items ({@code INTERVAL}) in
 * step: for the listener of reading changes ({@link ReadingCharges}), the retry of one item and the month run, which all
 * decide with {@link ReadingChargePlanning}. Everything runs inside the caller's transaction (bound to the cooperative).
 * <p>
 * Locks, in this order: the meter {@code FOR SHARE} with its points ({@link MeterNetwork#lockSharedPoints}; a reading
 * write holds it {@code FOR UPDATE}, so it waits for the charge and then sees it through {@code LedgerReadingUsage}),
 * a transaction advisory lock per meter (two ledger transactions about the same meter run one after another, so a key
 * is checked and written once and an item is opened once), the charge types and every tariff {@code FOR SHARE}
 * ({@link TariffChargeWriter#lockTariffs}: a correction of a version waits and then sees the charge), and the billing
 * month {@code FOR SHARE} ({@link BillingMonths#openForBooking}). The month run takes the meters of its retries before
 * its tariffs, so the order holds there too.
 * <p>
 * A charge is booked in the month of the reading, or the earliest open month after it when that month is closed,
 * keeping its own service period and its debt date (the reading date). With it go its parts (version, days, the exact
 * quantity as a fraction, rate) and one {@code meter_value_use} row per point it read (the two points of the interval),
 * which {@code LedgerReadingUsage} answers from. Business failures never throw: they open or refresh the item
 * {@code INTERVAL:{meter}:{toKey}}; an item whose interval no longer exists (the readings around it changed) becomes
 * {@code OBSOLETE}. A water meter's interval is charged only for the days its plot is connected to water
 * ({@link WaterDays}); an interval without a connected day charges and queues nothing.
 * <p>
 * A recalculation (feature 012) uses the same planning: {@link #expected} returns what the listener would charge today
 * for the intervals of a locked meter ({@link #lockPoints}) in a window, and {@link #bookRecalculated} and
 * {@link #writePartsAndUse} write its new charges and the parts and points of its adjustments.
 */
@Component
class IntervalCharging {

    private final MeterNetwork network;
    private final TariffChargeWriter writer;
    private final PlotCatalog plots;
    private final CooperativeSettings settings;
    private final BillingMonths billingMonths;
    private final ChargeRepository charges;
    private final NotChargedItemRepository items;
    private final WaterDays waterDays;
    private final EntityManager entityManager;

    IntervalCharging(MeterNetwork network, TariffChargeWriter writer, PlotCatalog plots,
            CooperativeSettings settings, BillingMonths billingMonths, ChargeRepository charges,
            NotChargedItemRepository items, WaterDays waterDays, EntityManager entityManager) {
        this.network = network;
        this.writer = writer;
        this.plots = plots;
        this.settings = settings;
        this.billingMonths = billingMonths;
        this.charges = charges;
        this.items = items;
        this.waterDays = waterDays;
        this.entityManager = entityManager;
    }

    /** What the charging of one meter needs, read under the locks. */
    private record Context(MeterPoints meter, UUID plotId, ChargeTypeEntry type, Snapshot snapshot,
            TariffResolver.PlotFacts plot, @Nullable BigDecimal lossesPercent) {
    }

    /**
     * Charges the intervals of the meter that contain {@code on} (the interval ending there and the one starting
     * there; the one spanning it when no point is left on that date) and are not charged yet, and closes the open
     * items of the meter whose interval no longer exists. A meter that is gone or is not a plot meter changes nothing.
     *
     * Publishes {@code ChargesBooked} when it booked.
     *
     * @return the plots whose ledger changed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Set<UUID> chargeAround(UUID tenantId, UUID meterId, LocalDate on, Instant now) {
        Optional<MeterPoints> locked = lockMeter(tenantId, meterId);
        if (locked.isEmpty()) {
            return Set.of();
        }
        List<MeterInterval> intervals = intervals(locked.get());
        Set<UUID> booked = new HashSet<>();
        Optional<Context> context = context(locked.get(), lockTariffs());
        if (context.isPresent()) {
            for (MeterInterval interval : intervals) {
                if (!interval.from().on().isAfter(on) && !interval.to().on().isBefore(on)
                        && process(tenantId, context.get(), interval, null, now) == Processed.BOOKED) {
                    booked.add(context.get().plotId());
                }
            }
        }
        closeStaleItems(meterId, intervals, now);
        if (!booked.isEmpty()) {
            writer.publishBooked(tenantId, booked);
        }
        return booked;
    }

    /** How one interval ended. */
    enum Processed {
        BOOKED, ALREADY_CHARGED, NOTHING, FAILED
    }

    /**
     * Retries one open {@code INTERVAL} item: its interval (the one that ends at the item's point) is charged as the
     * listener would. A point that no longer exists makes the item {@code OBSOLETE}.
     *
     * @return the item's status afterwards; {@code RESOLVED} also when it was no longer open
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Status retry(UUID tenantId, NotChargedItemRecord item, @Nullable UUID actor, Instant now) {
        UUID meterId = Objects.requireNonNull(item.getMeterId());
        Optional<MeterPoints> locked = lockMeter(tenantId, meterId);
        entityManager.refresh(item, LockModeType.PESSIMISTIC_WRITE);
        if (item.getStatus() != Status.OPEN) {
            return Status.RESOLVED;
        }
        Snapshot snapshot = lockTariffs();
        Retried retried = retryLocked(tenantId, item, locked, snapshot, actor, now);
        items.flush();
        if (retried.booked() && item.getPlotId() != null) {
            writer.publishBooked(tenantId, Set.of(item.getPlotId()));
        }
        return retried.status();
    }

    /**
     * Retries every open {@code INTERVAL} item of the cooperative, whatever its month (the month run). The meters are
     * locked first, in id order, then the tariffs once. Publishes {@code ChargesBooked} for the plots charged.
     *
     * @return the number of items resolved by a new charge
     */
    @Transactional(propagation = Propagation.MANDATORY)
    int retryAll(UUID tenantId, @Nullable UUID actor, Instant now) {
        List<NotChargedItemRecord> open = items.findByKindAndStatus(Kind.INTERVAL, Status.OPEN);
        if (open.isEmpty()) {
            return 0;
        }
        Map<UUID, Optional<MeterPoints>> meters = new TreeMap<>();
        for (UUID meterId : new TreeSet<>(open.stream().map(NotChargedItemRecord::getMeterId)
                .filter(Objects::nonNull).toList())) {
            meters.put(meterId, lockMeter(tenantId, meterId));
        }
        Snapshot snapshot = lockTariffs();
        Set<UUID> booked = new HashSet<>();
        int created = 0;
        for (NotChargedItemRecord item : open) {
            entityManager.refresh(item, LockModeType.PESSIMISTIC_WRITE);
            if (item.getStatus() != Status.OPEN || item.getMeterId() == null) {
                continue;
            }
            Retried retried = retryLocked(tenantId, item, meters.get(item.getMeterId()), snapshot, actor, now);
            if (retried.booked() && item.getPlotId() != null) {
                booked.add(item.getPlotId());
                created++;
            }
        }
        items.flush();
        if (!booked.isEmpty()) {
            writer.publishBooked(tenantId, booked);
        }
        return created;
    }

    /** The status of a retried item and whether the retry booked a charge. */
    private record Retried(Status status, boolean booked) {
    }

    private Retried retryLocked(UUID tenantId, NotChargedItemRecord item, Optional<MeterPoints> locked,
            Snapshot snapshot, @Nullable UUID actor, Instant now) {
        Optional<MeterInterval> interval = locked.flatMap(points -> intervals(points).stream()
                .filter(i -> i.to().key().equals(item.toKey())).findFirst());
        Optional<Context> context = locked.flatMap(points -> context(points, snapshot));
        if (interval.isEmpty() || context.isEmpty()) {
            item.close(Status.OBSOLETE, now);
            return new Retried(Status.OBSOLETE, false);
        }
        Processed processed = process(tenantId, context.get(), interval.get(), actor, now);
        return switch (processed) {
            case BOOKED -> new Retried(Status.RESOLVED, true);
            case ALREADY_CHARGED -> new Retried(Status.RESOLVED, false);
            case NOTHING -> new Retried(Status.OBSOLETE, false);
            case FAILED -> new Retried(Status.OPEN, false);
        };
    }

    /**
     * Plans one interval and does what the plan says: books the charges, closes the interval's open item
     * ({@code RESOLVED} when charged, now or before; {@code OBSOLETE} when there is nothing to charge) or opens or
     * refreshes it with the reason of the failure.
     */
    private Processed process(UUID tenantId, Context context, MeterInterval interval, @Nullable UUID actor,
            Instant now) {
        Outcome outcome = plan(context, interval, key -> !charges.existingSourceKeys(List.of(key)).isEmpty());
        String itemKey = NotChargedItemRecord.intervalKey(interval.meterId(), interval.to().key());
        Optional<NotChargedItemRecord> open = items.findOpen(itemKey);
        Processed processed;
        switch (outcome) {
            case NewCharges newCharges -> {
                book(tenantId, newCharges, actor, now);
                open.ifPresent(item -> item.close(Status.RESOLVED, now));
                processed = Processed.BOOKED;
            }
            case AlreadyCharged already -> {
                open.ifPresent(item -> item.close(Status.RESOLVED, now));
                processed = Processed.ALREADY_CHARGED;
            }
            case Nothing nothing -> {
                open.ifPresent(item -> item.close(Status.OBSOLETE, now));
                processed = Processed.NOTHING;
            }
            case Failed failed -> {
                if (open.isPresent()) {
                    open.get().intervalFailedAgain(failed.reason(), failed.tariffIds(), interval.from().key(),
                            interval.firstDay(), now);
                } else {
                    items.save(NotChargedItemRecord.interval(tenantId, context.plotId(), context.type().id(),
                            interval.meterId(), interval.from().key(), interval.to().key(), interval.firstDay(),
                            interval.to().on(), failed.reason(), failed.tariffIds(), now));
                }
                processed = Processed.FAILED;
            }
        }
        items.flush();
        return processed;
    }

    /**
     * What {@link ReadingChargePlanning} decides for one interval of the meter: the tariffs of the snapshot, the days the
     * plot may be charged ({@link WaterDays}) and the losses percentage of the context.
     *
     * @param charged whether a charge with the source key exists ({@code key -> false} for a recalculation)
     */
    private Outcome plan(Context context, MeterInterval interval, Predicate<String> charged) {
        MeterResource resource = context.meter().resource();
        return ReadingChargePlanning.plan(context.snapshot(), context.type().id(), context.plot(), resource, interval,
                waterDays.forInterval(resource, context.plotId(), interval.firstDay(), interval.to().on()),
                context.lossesPercent(), charged);
    }

    /** Writes the charges of an interval, their parts and the points they read, in the booking month. */
    private void book(UUID tenantId, NewCharges planned, @Nullable UUID actor, Instant now) {
        List<PlannedReadingCharge> toBook = new ArrayList<>();
        toBook.add(planned.consumption());
        if (planned.losses() != null) {
            toBook.add(planned.losses());
        }
        BillingMonthRecord month = billingMonths.openForBooking(tenantId, planned.consumption().accruedOn());
        for (PlannedReadingCharge charge : toBook) {
            write(tenantId, charge, month.getId(), charge.sourceKey(), null, actor, now);
        }
    }

    // ---- feature 012: what a recalculation needs

    /**
     * The days a recalculation covers: the intervals whose closing reading lies in them, both ends included (the
     * months of a manual recalculation, from the first day of the first one to the last day of the last one).
     */
    record Window(LocalDate from, LocalDate to) {

        Window {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (to.isBefore(from)) {
                throw new IllegalArgumentException("A window does not end before it starts");
            }
        }

        /** Whether the interval belongs to the window: the reading that closes it is dated in it. */
        boolean contains(MeterInterval interval) {
            return contains(interval.to().on());
        }

        /** Whether the day lies in the window. */
        boolean contains(LocalDate on) {
            return !on.isBefore(from) && !on.isAfter(to);
        }
    }

    /**
     * Locks the meter {@code FOR SHARE} with its points ({@link MeterNetwork#lockSharedPoints}), then its advisory lock,
     * as the listener does: a recalculation locks the meters of its plots in id order, after the charging lock and
     * before the queue lock. Empty for a meter that is gone or is not a plot meter.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<MeterPoints> lockPoints(UUID tenantId, UUID meterId) {
        return lockMeter(tenantId, meterId);
    }

    /**
     * What 011 would charge today for the intervals of the locked meter that lie in the window (feature 012): each
     * interval planned exactly as the listener plans it ({@link ReadingChargePlanning}, the same tariffs, water days
     * and losses percentage), with a "charged" predicate that ignores the ledger, as {@link ExpectedCharges#ofInterval}
     * entries (a consumption charge and its fixed losses charge; both not charged when the interval fails; nothing for
     * an interval with nothing to charge, an inactive charge type included). Empty for a meter that is not a plot
     * meter, whose plot is gone, or whose charge type does not exist. Reads the plot and the water connections.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<Expected> expected(MeterPoints points, Snapshot snapshot, Window window) {
        if (points.kind() != MeterKind.PLOT || points.plotId() == null) {
            return List.of();
        }
        Optional<Context> context = context(points, snapshot);
        if (context.isEmpty()) {
            return List.of();
        }
        List<Expected> expected = new ArrayList<>();
        for (MeterInterval interval : intervals(points)) {
            if (window.contains(interval)) {
                expected.addAll(ExpectedCharges.ofInterval(plan(context.get(), interval, key -> false), interval,
                        context.get().plotId(), context.get().type().id()));
            }
        }
        return expected;
    }

    /**
     * Writes a charge of an interval that the recalculation {@code recalculationId} books (feature 012): the planned
     * charge with {@code sourceKey} (its base key, or {@code base#r{recalculation}} when a charge has the base key),
     * its parts and the two points it reads, in the billing month. Flushes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    ChargeRecord bookRecalculated(UUID tenantId, PlannedReadingCharge charge, UUID billingMonthId, String sourceKey,
            UUID recalculationId, @Nullable UUID createdBy, Instant now) {
        return write(tenantId, charge, billingMonthId, sourceKey, recalculationId, createdBy, now);
    }

    /**
     * Writes the parts of the planned charge and the two points it reads for {@code chargeId}: an adjustment of an
     * interval charge by a recalculation (feature 012), whose parts are those of the expected charge. Flushes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void writePartsAndUse(UUID tenantId, UUID chargeId, PlannedReadingCharge charge) {
        List<UUID> versionIds = charge.versionIds();
        for (int p = 0; p < charge.parts().size(); p++) {
            PricedPart part = charge.parts().get(p);
            entityManager.persist(ChargePartRecord.of(tenantId, chargeId, versionIds.get(p), part.days(),
                    new BigDecimal(part.quantity().numerator()), new BigDecimal(part.quantity().denominator()),
                    part.terms().rate().value()));
        }
        for (Point point : List.of(charge.interval().from(), charge.interval().to())) {
            entityManager.createNativeQuery("insert into meter_value_use (tenant_id, meter_id, used_on, charge_id) "
                    + "values (:tenant, :meter, :usedOn, :charge)")
                    .setParameter("tenant", tenantId)
                    .setParameter("meter", charge.interval().meterId())
                    .setParameter("usedOn", point.on())
                    .setParameter("charge", chargeId)
                    .executeUpdate();
        }
        entityManager.flush();
    }

    /** Writes one charge of an interval with its parts and the points it reads. */
    private ChargeRecord write(UUID tenantId, PlannedReadingCharge charge, UUID billingMonthId, String sourceKey,
            @Nullable UUID recalculationId, @Nullable UUID createdBy, Instant now) {
        ChargeRecord record = ChargeRecord.reading(tenantId, charge.plotId(), charge.chargeTypeId(), billingMonthId,
                charge.source(), charge.tariff().id(), charge.interval().meterId(), charge.amount(),
                charge.serviceFrom(), charge.serviceTo(), charge.accruedOn(), sourceKey, charge.calculation(),
                recalculationId, createdBy, now);
        entityManager.persist(record);
        entityManager.flush();
        writePartsAndUse(tenantId, record.getId(), charge);
        return record;
    }

    /** Closes the open items of the meter whose interval (from point and to point) is not an interval any more. */
    private void closeStaleItems(UUID meterId, List<MeterInterval> intervals, Instant now) {
        Set<String> current = intervals.stream().map(i -> i.from().key() + "|" + i.to().key())
                .collect(Collectors.toSet());
        for (NotChargedItemRecord item : items.findByKindAndStatusAndMeterId(Kind.INTERVAL, Status.OPEN, meterId)) {
            if (!current.contains(item.fromKey() + "|" + item.toKey())) {
                item.close(Status.OBSOLETE, now);
            }
        }
        items.flush();
    }

    /** The meter {@code FOR SHARE} with its points, then the meter's advisory lock; empty for a meter that is gone. */
    private Optional<MeterPoints> lockMeter(UUID tenantId, UUID meterId) {
        Optional<MeterPoints> points = network.lockSharedPoints(meterId);
        entityManager.createNativeQuery(
                "select count(*) from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) as l")
                .setParameter("key", "ledger.meter-charges:" + tenantId + ":" + meterId)
                .getSingleResult();
        return points.filter(p -> p.kind() == MeterKind.PLOT && p.plotId() != null);
    }

    /** Every charge type and every tariff, locked {@code FOR SHARE} (the same order as the month run). */
    private Snapshot lockTariffs() {
        return writer.lockTariffs();
    }

    private Optional<Context> context(MeterPoints meter, Snapshot snapshot) {
        UUID plotId = Objects.requireNonNull(meter.plotId());
        String code = meter.resource() == MeterResource.WATER ? "WATER" : "ELECTRICITY";
        Optional<ChargeTypeEntry> type = snapshot.chargeTypes().stream().filter(t -> code.equals(t.code()))
                .findFirst();
        return type.flatMap(t -> plots.find(plotId).map(plot -> new Context(meter, plotId, t, snapshot,
                MonthRun.facts(plot), meter.resource() == MeterResource.ELECTRICITY
                        && settings.lossesMode() == LossesMode.FIXED_PERCENT ? settings.lossesPercent() : null)));
    }

    /** The intervals of the meter between neighbouring points, oldest first. */
    static List<MeterInterval> intervals(MeterPoints meter) {
        List<MeterInterval> intervals = new ArrayList<>();
        for (int i = 1; i < meter.points().size(); i++) {
            intervals.add(new MeterInterval(meter.meterId(), meter.points().get(i - 1), meter.points().get(i)));
        }
        return intervals;
    }
}
