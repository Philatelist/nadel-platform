package app.nadel.ledger.internal;

import app.nadel.ledger.internal.BillingCalendar.MonthState;
import app.nadel.ledger.internal.ExpectedCharges.Charge;
import app.nadel.ledger.internal.ExpectedCharges.Expected;
import app.nadel.ledger.internal.ExpectedCharges.NotCharged;
import app.nadel.ledger.internal.NotChargedItemRecord.Reason;
import app.nadel.ledger.internal.NotChargedItemRecord.Status;
import app.nadel.ledger.internal.RecalculationLineRecord.Action;
import app.nadel.money.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import nadel.contract.model.RecalculationChangeReason;
import org.jspecify.annotations.Nullable;

/**
 * What a recalculation books (feature 012, functional spec 2.1): the charges 011 would book today
 * ({@link ExpectedCharges}) against the booked chains ({@link BookedChain}), per {@link ChargeIdentity}. Pure: no
 * Spring, no database, no clock; the preview and the booking both call it, so they never disagree.
 * <p>
 * Per identity:
 * <ul>
 * <li>a chain reversed by hand and not booked again is frozen: nothing (hand reversals stay);</li>
 * <li>a live charge in an open month whose effective amount differs from the expected one:
 * {@code REVERSE_AND_RECHARGE} (the reversal in its month, the new charge in the month 011 would book it in); expected
 * absent (or not charged): {@code REVERSE};</li>
 * <li>a live charge in a closed month: {@code ADJUST} for {@code expected - effective} (or {@code -effective}) in the
 * earliest open month, keeping the original's service period ({@link BillingCalendar#bookingMonth} of the original's
 * debt date);</li>
 * <li>no live charge and an expected charge: {@code NEW}, in its own month if open, else the earliest open month;</li>
 * <li>equal: nothing. A charge that now belongs to another plot or charge type (a meter moved in the tree) goes as a
 * charge that should no longer exist, and the expected one is new.</li>
 * </ul>
 * A losses or common consumer run follows its billing month: open, and any of its lines differs, then every live line
 * of the run is reversed and the run booked again (whole-run lines, also the lines whose amount stays); closed, then
 * the identity rules above (adjustments; a plot new to the run gets a new charge). The only arithmetic is
 * {@code expected - effective}, the difference of two amounts rounded already, so nothing is rounded here.
 * <p>
 * The "not charged" queue: an item of the scope whose identity is charged now (or was) is resolved, one whose identity
 * cannot be charged stays or becomes open with the reason, and one whose identity has nothing to charge any more is
 * obsolete.
 */
final class RecalculationPlanner {

    private RecalculationPlanner() {
    }

    /**
     * What the planner compares.
     *
     * @param booked           every ledger row of the scope (regular charges with their reversals and adjustments)
     * @param expected         what 011 would do today for the identities of the scope
     * @param months           every billing month of the cooperative
     * @param today            today in the cooperative's time zone
     * @param openQueueKeys    the open "not charged" items of the scope
     * @param cause            what caused the recalculation (names a line's reason when the comparison cannot)
     * @param waterChargeTypes the water charge types (their day changes are water connection changes)
     */
    record Input(List<BookedCharge> booked, List<Expected> expected, List<MonthState> months, LocalDate today,
            Set<String> openQueueKeys, RecalculationRecord.Cause cause, Set<UUID> waterChargeTypes) {

        Input {
            booked = List.copyOf(booked);
            expected = List.copyOf(expected);
            months = List.copyOf(months);
            Objects.requireNonNull(today, "today");
            openQueueKeys = Set.copyOf(openQueueKeys);
            Objects.requireNonNull(cause, "cause");
            waterChargeTypes = Set.copyOf(waterChargeTypes);
        }
    }

    /**
     * One line of the plan.
     *
     * @param original    the live charge the line corrects; null for a new charge
     * @param booked      the original's amount ({@code 0.00} for a new charge)
     * @param adjustments the original's adjustments so far
     * @param effective   {@code booked + adjustments}
     * @param expected    the expected amount, {@code 0.00} when the charge should no longer exist
     * @param difference  {@code expected - effective}
     * @param bookedIn    the month the line's reversal (open month), new charge or adjustment goes to
     * @param notCharged  why the expected charge cannot be calculated; null otherwise
     * @param runId       the run of a run line; null otherwise
     * @param recharge    the charge to book ({@code REVERSE_AND_RECHARGE}, {@code NEW}); null otherwise
     */
    record Line(ChargeIdentity identity, Action action, UUID plotId, UUID chargeTypeId, LocalDate serviceFrom,
            LocalDate serviceTo, @Nullable BookedCharge original, Money booked, Money adjustments, Money effective,
            Money expected, Money difference, YearMonth bookedIn, List<RecalculationChangeReason> reasons,
            @Nullable Reason notCharged, @Nullable UUID runId, @Nullable Charge recharge) {

        Line {
            reasons = List.copyOf(reasons);
        }
    }

    /** What happens to a run. */
    enum RunAction {
        /** Open month: every live line reversed and the run booked again. */
        REBOOK,
        /** Open month: every live line reversed, nothing to book again. */
        REVERSE,
        /** Closed month: per-line adjustments and new charges. */
        PER_LINE
    }

    record RunOutcome(UUID runId, RunAction action) {
    }

    /**
     * What happens to a "not charged" item.
     *
     * @param identity the identity behind it (the smallest, when several); null for an obsolete item
     * @param status   {@code OPEN} (opened or kept open with the reason), {@code RESOLVED} or {@code OBSOLETE}
     */
    record QueueOutcome(String itemKey, @Nullable ChargeIdentity identity, Status status, @Nullable Reason reason,
            List<UUID> tariffIds) {

        QueueOutcome {
            tariffIds = List.copyOf(tariffIds);
        }
    }

    /** The plan: lines by identity, the runs that change, the queue. */
    record Plan(List<Line> lines, List<RunOutcome> runs, List<QueueOutcome> queue) {

        Plan {
            lines = List.copyOf(lines);
            runs = List.copyOf(runs);
            queue = List.copyOf(queue);
        }

        /** Whether the recalculation books nothing ("No changes"). */
        boolean isEmpty() {
            return lines.isEmpty();
        }

        /** The sum of the differences. */
        Money total() {
            return lines.stream().map(Line::difference).reduce(Money.ZERO, Money::plus);
        }
    }

    /**
     * @throws IllegalArgumentException when two expected entries have one identity
     */
    static Plan plan(Input input) {
        Map<ChargeIdentity, BookedChain> chains = BookedChain.of(input.booked());
        Map<ChargeIdentity, Expected> expected = new TreeMap<>();
        for (Expected entry : input.expected()) {
            if (expected.put(entry.identity(), entry) != null) {
                throw new IllegalArgumentException("Two expected charges of one identity: " + entry.identity());
            }
        }
        Set<ChargeIdentity> identities = new TreeSet<>(chains.keySet());
        identities.addAll(expected.keySet());

        List<Line> lines = new ArrayList<>();
        Map<UUID, List<ChargeIdentity>> runs = new TreeMap<>();
        for (ChargeIdentity identity : identities) {
            if (identity.runId() == null) {
                lines.addAll(decide(identity, chains.get(identity), expected.get(identity), input));
            } else {
                runs.computeIfAbsent(identity.runId(), k -> new ArrayList<>()).add(identity);
            }
        }
        List<RunOutcome> runOutcomes = new ArrayList<>();
        runs.forEach((runId, runIdentities) -> {
            List<Line> perLine = new ArrayList<>();
            for (ChargeIdentity identity : runIdentities) {
                perLine.addAll(decide(identity, chains.get(identity), expected.get(identity), input));
            }
            if (perLine.isEmpty()) {
                return;
            }
            Optional<YearMonth> month = runIdentities.stream().map(chains::get).filter(Objects::nonNull)
                    .map(BookedChain::live).filter(Objects::nonNull).map(BookedCharge::billingMonth).findFirst();
            if (month.isEmpty() || closed(month.get(), input.months())) {
                lines.addAll(perLine);
                runOutcomes.add(new RunOutcome(runId, RunAction.PER_LINE));
                return;
            }
            boolean rebook = false;
            for (ChargeIdentity identity : runIdentities) {
                BookedChain chain = chains.get(identity);
                if (chain != null && chain.frozen()) {
                    continue;
                }
                Expected entry = expected.get(identity);
                Charge charge = entry != null && entry.result() instanceof Charge c ? c : null;
                rebook |= charge != null;
                BookedCharge live = chain == null ? null : chain.live();
                if (live != null) {
                    lines.add(line(identity, charge == null ? Action.REVERSE : Action.REVERSE_AND_RECHARGE, chain,
                            entry, charge == null ? live.billingMonth() : bookingMonth(charge.accruedOn(), input),
                            input));
                } else if (charge != null) {
                    lines.add(newLine(identity, chain, Objects.requireNonNull(entry), charge, input));
                }
            }
            runOutcomes.add(new RunOutcome(runId, rebook ? RunAction.REBOOK : RunAction.REVERSE));
        });
        lines.sort(Comparator.comparing(Line::identity).thenComparing(Line::action));
        return new Plan(lines, runOutcomes, queue(expected, input.openQueueKeys()));
    }

    /** The lines of one identity outside a whole-run rebooking. */
    private static List<Line> decide(ChargeIdentity identity, @Nullable BookedChain chain,
            @Nullable Expected expected, Input input) {
        if (chain != null && chain.frozen()) {
            return List.of();
        }
        BookedCharge live = chain == null ? null : chain.live();
        Charge charge = expected != null && expected.result() instanceof Charge c ? c : null;
        if (live == null) {
            return charge == null ? List.of()
                    : List.of(newLine(identity, chain, Objects.requireNonNull(expected), charge, input));
        }
        if (charge != null && (!live.plotId().equals(expected.plotId())
                || !live.chargeTypeId().equals(expected.chargeTypeId()))) {
            // the charge belongs to another plot or type now: the booked one goes, the expected one is new
            List<Line> lines = new ArrayList<>(decide(identity, chain, null, input));
            lines.add(newLine(identity, null, expected, charge, input));
            return lines;
        }
        Money difference = (charge == null ? Money.ZERO : charge.amount()).minus(Objects.requireNonNull(chain)
                .effective());
        if (difference.signum() == 0) {
            return List.of();
        }
        if (closed(live.billingMonth(), input.months())) {
            return List.of(line(identity, Action.ADJUST, chain, expected, bookingMonth(live.accruedOn(), input),
                    input));
        }
        if (charge == null) {
            return List.of(line(identity, Action.REVERSE, chain, expected, live.billingMonth(), input));
        }
        return List.of(line(identity, Action.REVERSE_AND_RECHARGE, chain, expected,
                bookingMonth(charge.accruedOn(), input), input));
    }

    /** A line about the live charge of {@code chain}. */
    private static Line line(ChargeIdentity identity, Action action, BookedChain chain, @Nullable Expected expected,
            YearMonth bookedIn, Input input) {
        BookedCharge live = Objects.requireNonNull(chain.live());
        Charge charge = expected != null && expected.result() instanceof Charge c ? c : null;
        Money effective = chain.effective();
        Money expectedAmount = charge == null ? Money.ZERO : charge.amount();
        return new Line(identity, action, live.plotId(), live.chargeTypeId(), live.serviceFrom(), live.serviceTo(),
                live, live.amount(), chain.adjustmentsOfLive(), effective, expectedAmount,
                expectedAmount.minus(effective), bookedIn,
                ChangeReasons.of(identity, live, expected, input.cause(),
                        input.waterChargeTypes().contains(live.chargeTypeId())),
                notCharged(expected), identity.runId(), action == Action.REVERSE_AND_RECHARGE ? charge : null);
    }

    /** A new charge; a chain without a live charge (reversed by a recalculation) counts as {@code 0.00}. */
    private static Line newLine(ChargeIdentity identity, @Nullable BookedChain chain, Expected expected,
            Charge charge, Input input) {
        Money effective = chain == null ? Money.ZERO : chain.effective();
        return new Line(identity, Action.NEW, expected.plotId(), expected.chargeTypeId(), charge.serviceFrom(),
                charge.serviceTo(), null, Money.ZERO, Money.ZERO, effective, charge.amount(),
                charge.amount().minus(effective), bookingMonth(charge.accruedOn(), input),
                ChangeReasons.of(identity, null, expected, input.cause(),
                        input.waterChargeTypes().contains(expected.chargeTypeId())),
                null, identity.runId(), charge);
    }

    private static @Nullable Reason notCharged(@Nullable Expected expected) {
        return expected != null && expected.result() instanceof NotCharged notCharged ? notCharged.reason() : null;
    }

    /** The queue items of the expected identities and the open items of the scope. */
    private static List<QueueOutcome> queue(Map<ChargeIdentity, Expected> expected, Set<String> openKeys) {
        Map<String, List<Expected>> byKey = new TreeMap<>();
        for (Expected entry : expected.values()) {
            if (entry.queueKey() != null) {
                byKey.computeIfAbsent(entry.queueKey(), k -> new ArrayList<>()).add(entry);
            }
        }
        List<QueueOutcome> outcomes = new ArrayList<>();
        byKey.forEach((key, entries) -> {
            Optional<Expected> failed = entries.stream().filter(e -> e.result() instanceof NotCharged).findFirst();
            if (failed.isPresent()) {
                NotCharged notCharged = (NotCharged) failed.get().result();
                outcomes.add(new QueueOutcome(key, failed.get().identity(), Status.OPEN, notCharged.reason(),
                        notCharged.tariffIds()));
            } else if (openKeys.contains(key)) {
                outcomes.add(new QueueOutcome(key, entries.getFirst().identity(), Status.RESOLVED, null, List.of()));
            }
        });
        new TreeSet<>(openKeys).stream().filter(key -> !byKey.containsKey(key))
                .forEach(key -> outcomes.add(new QueueOutcome(key, null, Status.OBSOLETE, null, List.of())));
        return outcomes;
    }

    /** The month 011 books a charge of {@code date} in: its own while open, else the earliest open month. */
    private static YearMonth bookingMonth(LocalDate date, Input input) {
        return BillingCalendar.bookingMonth(date, input.months(), input.today());
    }

    /**
     * Whether {@code month} is closed: its row says so, or it has none and lies before the last closed month (months
     * close in order).
     */
    static boolean closed(YearMonth month, List<MonthState> months) {
        Optional<MonthState> row = months.stream().filter(m -> m.month().equals(month)).findFirst();
        if (row.isPresent()) {
            return row.get().closed();
        }
        return months.stream().filter(MonthState::closed).map(MonthState::month).anyMatch(c -> !month.isAfter(c));
    }
}
