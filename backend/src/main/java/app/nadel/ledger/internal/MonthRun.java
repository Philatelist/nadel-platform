package app.nadel.ledger.internal;

import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.ledger.internal.NotChargedItemRecord.Kind;
import app.nadel.ledger.internal.NotChargedItemRecord.Reason;
import app.nadel.ledger.internal.NotChargedItemRecord.Status;
import app.nadel.ledger.internal.TariffMonthPlanning.AlreadyCharged;
import app.nadel.ledger.internal.TariffMonthPlanning.Conflicting;
import app.nadel.ledger.internal.TariffMonthPlanning.NewCharge;
import app.nadel.ledger.internal.TariffMonthPlanning.Nothing;
import app.nadel.ledger.internal.TariffMonthPlanning.Outcome;
import app.nadel.ledger.internal.TariffMonthPlanning.PlannedCharge;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import app.nadel.tariffs.DaySet;
import app.nadel.tariffs.TariffBook;
import app.nadel.tariffs.TariffBook.ChargeTypeEntry;
import app.nadel.tariffs.TariffBook.Snapshot;
import app.nadel.tariffs.TariffBook.TariffEntry;
import app.nadel.tariffs.TariffResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import nadel.contract.model.ChargeTypeTotal;
import nadel.contract.model.MonthRunResult;
import nadel.contract.model.NotChargedItem;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The month run of recurring tariffs ({@code POST /billing-months/{month}/run?preview=}, feature 011 §2.3 and §2.7):
 * every active plot that is not common land times every active, non-archived charge type with tariffs, as
 * {@link TariffMonthPlanning} decides, and a retry of the open "not charged" items of every other month
 * ({@link NotChargedRetries}). A preview writes nothing; a booking writes the new charges of the month (and their
 * parts) in the month, opens a "not charged" item ({@code TARIFF_MONTH}, {@code CONFLICTING_TARIFFS}) per conflict or
 * refreshes the open one, closes the open items of the month that the run can now charge or that have nothing left to
 * charge, books the charges of the retried items of other months (each in the open month
 * {@link BillingMonths#openForBooking} gives for its own month, with its own service period) and closes those items or
 * keeps them open with the tariffs of this run, sets the month's {@code run_at}, writes the audit entry
 * {@code BILLING_MONTH_RUN} and publishes {@code ChargesBooked}. The answer's {@code plots}, {@code totals},
 * {@code total}, {@code created} and the digest count the charges of the retried items too; {@code notCharged} lists
 * the conflicts of the month itself.
 * <p>
 * The rules, in order: a month after the current one in Europe/Kyiv is 409 {@code PERIOD_IN_FUTURE}; the booking takes
 * the month's advisory lock, the queue lock, every charge type and every tariff {@code FOR SHARE} and then the month
 * {@code FOR SHARE} ({@link TariffChargeWriter}); a closed month (or one that counts as closed) is 409
 * {@code PERIOD_CLOSED}; a {@code digest} that differs from the one of what the run would create now is 409
 * {@code PREVIEW_OUTDATED} and nothing is written. The digest is a SHA-256 over the planned new charges, the retried
 * ones included (source key, amount, service period), so a preview followed by an unchanged booking always matches.
 * <p>
 * A booking also retries every open reading interval item ({@code INTERVAL}, whatever its month;
 * {@link IntervalCharging#retryAll}) right after the month's advisory lock and before the queue lock and the tariffs;
 * those charges are booked like the listener's, in the month of their reading or the earliest open month after it,
 * and are not part of the preview or its digest.
 * <p>
 * Seam for a later slice: {@link #applicableDays} is where the water connection days apply (slice 5).
 */
@Service
class MonthRun {

    private final TariffBook book;
    private final ChargeTypeCatalog chargeTypes;
    private final PlotCatalog plots;
    private final BillingMonths billingMonths;
    private final ChargeRepository charges;
    private final NotChargedItemRepository items;
    private final NotChargedViews itemViews;
    private final TariffChargeWriter writer;
    private final NotChargedRetries retries;
    private final LedgerAuditPublisher audit;
    private final IntervalCharging intervals;
    private final Clock clock;

    MonthRun(TariffBook book, ChargeTypeCatalog chargeTypes, PlotCatalog plots, BillingMonths billingMonths,
            ChargeRepository charges, NotChargedItemRepository items, NotChargedViews itemViews,
            TariffChargeWriter writer, NotChargedRetries retries, LedgerAuditPublisher audit,
            IntervalCharging intervals, Clock clock) {
        this.book = book;
        this.chargeTypes = chargeTypes;
        this.plots = plots;
        this.billingMonths = billingMonths;
        this.charges = charges;
        this.items = items;
        this.itemViews = itemViews;
        this.writer = writer;
        this.retries = retries;
        this.audit = audit;
        this.intervals = intervals;
        this.clock = clock;
    }

    /** A conflict found by the run. */
    record ConflictCase(UUID plotId, UUID chargeTypeId, List<TariffEntry> tariffs) {
    }

    /**
     * What the run would do.
     *
     * @param charges   the new charges of the month, plots in natural order of the number, then charge types in list
     *                  order
     * @param conflicts the conflicts of the month, in the same order
     * @param outcomes  the outcome per month item key, for closing the open items of the month
     * @param retries   the retries of the open items of every other month
     */
    record Plan(List<PlannedCharge> charges, List<ConflictCase> conflicts, Map<String, Outcome> outcomes,
            List<NotChargedRetries.Attempt> retries) {

        /** The new charges of the month, then those of the retried items. */
        List<PlannedCharge> allCharges() {
            List<PlannedCharge> all = new ArrayList<>(charges);
            retries.forEach(attempt -> attempt.charge().ifPresent(all::add));
            return all;
        }

        String digest() {
            String lines = allCharges().stream().sorted(Comparator.comparing(PlannedCharge::sourceKey))
                    .map(c -> c.sourceKey() + "|" + c.amount() + "|" + c.serviceFrom() + "|" + c.serviceTo())
                    .collect(Collectors.joining("\n"));
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(lines.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is always available", e);
            }
        }
    }

    @Transactional
    MonthRunResult run(YearMonth month, boolean preview, @Nullable String digest, AccessProfile actor) {
        if (month.isAfter(YearMonth.now(clock))) {
            throw conflict(ProblemCodes.PERIOD_IN_FUTURE, "A month after the current one cannot be charged.");
        }
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        if (preview) {
            if (!billingMonths.isOpen(month)) {
                throw closed();
            }
            Snapshot snapshot = book.snapshot();
            Plan plan = plan(snapshot, month);
            return result(month, true, plan, 0, snapshot);
        }

        writer.lockMonth(tenantId, month);
        // the open reading intervals of every month first: they lock their meters before the tariffs (lock order)
        intervals.retryAll(tenantId, actor.memberId(), clock.instant());
        writer.lockQueue(tenantId);
        Snapshot snapshot = writer.lockTariffs();
        BillingMonthRecord row = billingMonths.openMonthForRun(tenantId, month).orElseThrow(MonthRun::closed);
        Plan plan = plan(snapshot, month);
        if (digest != null && !digest.equals(plan.digest())) {
            throw conflict(ProblemCodes.PREVIEW_OUTDATED, "The run would now create something else than the preview.");
        }

        Instant now = clock.instant();
        UUID monthId = row.getId();
        writer.book(tenantId, plan.charges(), monthId, actor.memberId(), now);
        queueConflicts(tenantId, month, plan, now);
        closeOpenItems(month, plan, now);
        retries.apply(tenantId, plan.retries(), actor.memberId(), now);
        List<PlannedCharge> all = plan.allCharges();
        Money total = all.stream().map(PlannedCharge::amount).reduce(Money.ZERO, Money::plus);
        audit.publishMonth(actor, LedgerAuditPublisher.Action.BILLING_MONTH_RUN, monthId, month.toString(), List.of(
                new FieldChange("count", null, Integer.toString(all.size())),
                new FieldChange("total", null, total.toString())));
        Set<UUID> plotIds = all.stream().map(PlannedCharge::plotId).collect(Collectors.toSet());
        if (!plotIds.isEmpty()) {
            writer.publishBooked(tenantId, plotIds);
        }
        billingMonths.markRun(month, now);
        return result(month, false, plan, all.size(), snapshot);
    }

    /**
     * The plan of the month: every chargeable plot times every charging type with a tariff, and the retries of the
     * open {@code TARIFF_MONTH} items of every other month.
     */
    Plan plan(Snapshot snapshot, YearMonth month) {
        List<ChargeTypeEntry> types = chargingTypes(snapshot);
        List<PlotFacts> chargeable = plots.findChargeable();
        Map<UUID, PlotFacts> chargeableById = chargeable.stream()
                .collect(Collectors.toMap(PlotFacts::id, plot -> plot));
        LocalDate first = month.atDay(1);
        List<NotChargedItemRecord> otherMonths = items.findByKindAndStatus(Kind.TARIFF_MONTH, Status.OPEN).stream()
                .filter(item -> !first.equals(Objects.requireNonNull(item.getMonth()).atDay(1)))
                .sorted(Comparator.comparing(NotChargedItemRecord::getItemKey)).toList();
        // first: the source keys the run may use, to ask the ledger once which exist (reversed ones included)
        Set<String> keys = new HashSet<>();
        for (PlotFacts plot : chargeable) {
            for (ChargeTypeEntry type : types) {
                keys.addAll(TariffMonthPlanning.sourceKeys(snapshot, type.id(), facts(plot), month));
            }
        }
        keys.addAll(NotChargedRetries.sourceKeys(snapshot, otherMonths, chargeableById));
        Set<String> existing = existingKeys(keys);
        List<PlannedCharge> planned = new ArrayList<>();
        List<ConflictCase> conflicts = new ArrayList<>();
        Map<String, Outcome> outcomes = new HashMap<>();
        for (PlotFacts plot : chargeable) {
            for (ChargeTypeEntry type : types) {
                Outcome outcome = TariffMonthPlanning.plan(snapshot, type.id(), facts(plot), month,
                        applicableDays(type.id(), plot.id(), month), existing::contains);
                outcomes.put(NotChargedItemRecord.tariffMonthKey(plot.id(), type.id(), month), outcome);
                if (outcome instanceof NewCharge newCharge) {
                    planned.add(newCharge.charge());
                } else if (outcome instanceof Conflicting conflicting) {
                    conflicts.add(new ConflictCase(plot.id(), type.id(), conflicting.tariffs()));
                }
            }
        }
        return new Plan(planned, conflicts, outcomes,
                NotChargedRetries.plan(snapshot, otherMonths, chargeableById, existing::contains));
    }

    /**
     * The days the tariffs of the type may charge the plot in the month: every day. Slice 5 (water connections)
     * returns the connected days for the {@code WATER} type here.
     */
    static DaySet applicableDays(UUID chargeTypeId, UUID plotId, YearMonth month) {
        return DaySet.ALL;
    }

    /** The charge types the run charges: active, not archived, with at least one tariff; in list order. */
    private static List<ChargeTypeEntry> chargingTypes(Snapshot snapshot) {
        return snapshot.chargeTypes().stream().filter(ChargeTypeEntry::charges)
                .filter(type -> !snapshot.tariffsOf(type.id()).isEmpty()).toList();
    }

    static TariffResolver.PlotFacts facts(PlotFacts plot) {
        return new TariffResolver.PlotFacts(plot.id(), plot.commonLand(), plot.archived(), plot.areaM2());
    }

    private Set<String> existingKeys(Set<String> keys) {
        List<String> all = new ArrayList<>(keys);
        Set<String> existing = new HashSet<>();
        for (int from = 0; from < all.size(); from += 500) {
            existing.addAll(charges.existingSourceKeys(all.subList(from, Math.min(all.size(), from + 500))));
        }
        return existing;
    }

    /** Opens an item per conflict, or brings the open one up to date with the tariffs of this run. */
    private void queueConflicts(UUID tenantId, YearMonth month, Plan plan, Instant now) {
        Map<String, NotChargedItemRecord> open = openItems(month);
        for (ConflictCase conflict : plan.conflicts()) {
            List<UUID> tariffIds = conflict.tariffs().stream().map(TariffEntry::id).toList();
            NotChargedItemRecord item = open.get(
                    NotChargedItemRecord.tariffMonthKey(conflict.plotId(), conflict.chargeTypeId(), month));
            if (item == null) {
                items.save(NotChargedItemRecord.tariffMonth(tenantId, conflict.plotId(), conflict.chargeTypeId(),
                        month, Reason.CONFLICTING_TARIFFS, tariffIds, now));
            } else {
                item.failedAgain(Reason.CONFLICTING_TARIFFS, tariffIds, now);
            }
        }
        items.flush();
    }

    /**
     * Closes the open month items of the month that are no longer a conflict: charged by this run or before
     * ({@code RESOLVED}), or with nothing left to charge ({@code OBSOLETE}; also when the plot or the type is no longer
     * charged at all).
     */
    private void closeOpenItems(YearMonth month, Plan plan, Instant now) {
        for (NotChargedItemRecord item : openItems(month).values()) {
            Outcome outcome = plan.outcomes().getOrDefault(item.getItemKey(), new Nothing());
            if (outcome instanceof NewCharge || outcome instanceof AlreadyCharged) {
                item.close(Status.RESOLVED, now);
            } else if (outcome instanceof Nothing) {
                item.close(Status.OBSOLETE, now);
            }
        }
        items.flush();
    }

    private Map<String, NotChargedItemRecord> openItems(YearMonth month) {
        return items.findByKindAndStatusAndMonth(Kind.TARIFF_MONTH, Status.OPEN, month.atDay(1)).stream()
                .collect(Collectors.toMap(NotChargedItemRecord::getItemKey, item -> item));
    }

    private MonthRunResult result(YearMonth month, boolean preview, Plan plan, int created, Snapshot snapshot) {
        List<PlannedCharge> all = plan.allCharges();
        Map<UUID, Money> byType = new LinkedHashMap<>();
        for (PlannedCharge charge : all) {
            byType.merge(charge.chargeTypeId(), charge.amount(), Money::plus);
        }
        List<ChargeTypeTotal> totals = chargeTypes.all().stream().filter(type -> byType.containsKey(type.id()))
                .sorted(Comparator.comparingInt(ChargeTypeFacts::position).thenComparing(ChargeTypeFacts::id))
                .map(type -> BillingMonthQueries.total(type, byType.get(type.id()))).toList();
        Money total = byType.values().stream().reduce(Money.ZERO, Money::plus);
        int plotCount = new LinkedHashSet<>(all.stream().map(PlannedCharge::plotId).toList()).size();

        Map<String, NotChargedItemRecord> open = openItems(month);
        NotChargedViews.Context context = itemViews.context(
                plan.conflicts().stream().map(ConflictCase::plotId).collect(Collectors.toSet()), snapshot);
        List<NotChargedItem> notCharged = plan.conflicts().stream()
                .map(c -> itemViews.conflict(c.plotId(), c.chargeTypeId(), month, c.tariffs(),
                        open.get(NotChargedItemRecord.tariffMonthKey(c.plotId(), c.chargeTypeId(), month)), context))
                .toList();
        return new MonthRunResult(month.toString(), preview, plotCount, totals, total.toString(), notCharged, created,
                plan.digest());
    }

    private static ApiProblemException closed() {
        return conflict(ProblemCodes.PERIOD_CLOSED, "The month is closed.");
    }

    private static ApiProblemException conflict(String code, String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, detail);
    }
}
