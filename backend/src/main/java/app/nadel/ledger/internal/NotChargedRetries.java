package app.nadel.ledger.internal;

import app.nadel.ledger.internal.NotChargedItemRecord.Kind;
import app.nadel.ledger.internal.NotChargedItemRecord.Reason;
import app.nadel.ledger.internal.NotChargedItemRecord.Status;
import app.nadel.ledger.internal.TariffMonthPlanning.AlreadyCharged;
import app.nadel.ledger.internal.TariffMonthPlanning.Conflicting;
import app.nadel.ledger.internal.TariffMonthPlanning.NewCharge;
import app.nadel.ledger.internal.TariffMonthPlanning.Nothing;
import app.nadel.ledger.internal.TariffMonthPlanning.Outcome;
import app.nadel.ledger.internal.TariffMonthPlanning.PlannedCharge;
import app.nadel.ledger.internal.WaterDays.ApplicableDays;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.tariffs.TariffBook.Snapshot;
import app.nadel.tariffs.TariffBook.TariffEntry;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The retry of open "not charged" items, shared by "Повторити" ({@link NotChargedQueue#retry}, one item) and the month
 * run ({@link MonthRun}, the open items of every other month): {@link #plan} says what a retry of each item would do
 * now, {@link #apply} books the new charges and closes or refreshes the items. Both run inside the caller's
 * transaction, which holds the queue lock ({@link TariffChargeWriter#lockQueue}) and the charge types and tariffs
 * {@code FOR SHARE} ({@link TariffChargeWriter#lockTariffs}) when it writes.
 * <p>
 * A {@code TARIFF_MONTH} item is planned with {@link TariffMonthPlanning} for its plot, charge type and month: a new
 * charge closes it {@code RESOLVED} (also when the charge exists already), nothing left to charge (the plot left the
 * scopes or is no longer chargeable, the amount is {@code 0.00}, the type is no longer charged) {@code OBSOLETE}, a
 * conflict that remains keeps it open with the tariffs of this attempt. A new charge is booked through
 * {@link BillingMonths#openForBooking}: in the item's month, or, when that month is closed, in the earliest open month
 * after it, keeping its own service period and {@code accrued_on}. Items of other kinds are left out of the plan here:
 * {@code INTERVAL} items join with the reading charges (slice 4) as another case of {@link #plan}.
 */
@Component
class NotChargedRetries {

    private final NotChargedItemRepository items;
    private final BillingMonths billingMonths;
    private final TariffChargeWriter writer;

    NotChargedRetries(NotChargedItemRepository items, BillingMonths billingMonths, TariffChargeWriter writer) {
        this.items = items;
        this.billingMonths = billingMonths;
        this.writer = writer;
    }

    /** One open item and what a retry would do with it now. */
    record Attempt(NotChargedItemRecord item, Outcome outcome) {

        /** The status the item gets: {@code RESOLVED}, {@code OBSOLETE} or, while the cause remains, {@code OPEN}. */
        Status status() {
            return switch (outcome) {
                case NewCharge _ -> Status.RESOLVED;
                case AlreadyCharged _ -> Status.RESOLVED;
                case Conflicting _ -> Status.OPEN;
                case Nothing _ -> Status.OBSOLETE;
            };
        }

        /** The charge the retry books, if any. */
        Optional<PlannedCharge> charge() {
            return outcome instanceof NewCharge newCharge ? Optional.of(newCharge.charge()) : Optional.empty();
        }
    }

    /** Whether a retry of the item is planned here (the kinds the slices so far charge). */
    static boolean retried(NotChargedItemRecord item) {
        return item.getKind() == Kind.TARIFF_MONTH;
    }

    /**
     * The source keys the retries of the items may look for, to ask the ledger for all of them in one query.
     *
     * @param plots the chargeable plots by id; an item of another plot has nothing left to charge
     */
    static List<String> sourceKeys(Snapshot snapshot, Collection<NotChargedItemRecord> open,
            Map<UUID, PlotFacts> plots) {
        List<String> keys = new ArrayList<>();
        for (NotChargedItemRecord item : open) {
            if (retried(item)) {
                PlotFacts plot = plots.get(item.getPlotId());
                if (plot != null) {
                    keys.addAll(TariffMonthPlanning.sourceKeys(snapshot, chargeType(item), MonthRun.facts(plot),
                            month(item)));
                }
            }
        }
        return keys;
    }

    /**
     * What a retry of each item would do now. Pure: no writes. Items of a kind not retried here are left out.
     *
     * @param plots      the plots by id ({@link MonthRun}: the chargeable plots; a plot missing here, or archived or
     *                   common land, has nothing left to charge)
     * @param applicable the days the tariffs of a charge type may charge a plot in a month ({@link WaterDays})
     * @param charged    whether the source key counts as charged ({@link ChargeKeys}: a charge stands or was
     *                   reversed by hand)
     */
    static List<Attempt> plan(Snapshot snapshot, Collection<NotChargedItemRecord> open, Map<UUID, PlotFacts> plots,
            ApplicableDays applicable, Predicate<String> charged) {
        List<Attempt> attempts = new ArrayList<>();
        for (NotChargedItemRecord item : open) {
            if (!retried(item)) {
                continue; // INTERVAL items: slice 4
            }
            PlotFacts plot = plots.get(item.getPlotId());
            UUID chargeTypeId = chargeType(item);
            YearMonth month = month(item);
            Outcome outcome = plot == null ? new Nothing()
                    : TariffMonthPlanning.plan(snapshot, chargeTypeId, MonthRun.facts(plot), month,
                            applicable.of(chargeTypeId, plot.id(), month), charged);
            attempts.add(new Attempt(item, outcome));
        }
        return attempts;
    }

    /**
     * Books the new charges of the attempts and closes or refreshes their items. Each charge goes into the open month
     * {@link BillingMonths#openForBooking} gives for its own month (the months asked in calendar order, each locked
     * {@code FOR SHARE}). Does not publish {@code ChargesBooked}: the caller does, once for all its plots.
     *
     * @return the charges booked
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<PlannedCharge> apply(UUID tenantId, List<Attempt> attempts, @Nullable UUID createdBy, Instant now) {
        Map<YearMonth, List<PlannedCharge>> byMonth = new TreeMap<>();
        for (Attempt attempt : attempts) {
            attempt.charge().ifPresent(charge -> byMonth.computeIfAbsent(charge.month(), m -> new ArrayList<>())
                    .add(charge));
        }
        List<PlannedCharge> booked = new ArrayList<>();
        for (Map.Entry<YearMonth, List<PlannedCharge>> entry : byMonth.entrySet()) {
            BillingMonthRecord booking = billingMonths.openForBooking(tenantId, entry.getKey().atDay(1));
            writer.book(tenantId, entry.getValue(), booking.getId(), createdBy, now);
            booked.addAll(entry.getValue());
        }
        for (Attempt attempt : attempts) {
            NotChargedItemRecord item = attempt.item();
            Status status = attempt.status();
            if (attempt.outcome() instanceof Conflicting conflicting) {
                item.failedAgain(Reason.CONFLICTING_TARIFFS,
                        conflicting.tariffs().stream().map(TariffEntry::id).toList(), now);
            } else {
                item.close(status, now);
            }
        }
        items.flush();
        return booked;
    }

    private static UUID chargeType(NotChargedItemRecord item) {
        return Objects.requireNonNull(item.getChargeTypeId());
    }

    private static YearMonth month(NotChargedItemRecord item) {
        return Objects.requireNonNull(item.getMonth());
    }
}
