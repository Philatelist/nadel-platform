package app.nadel.ledger.internal;

import app.nadel.audit.ActorNames;
import app.nadel.ledger.internal.ChargeRepository.MonthTypeTotal;
import app.nadel.meters.MeterNetwork;
import app.nadel.meters.MeterNetwork.StaleMeter;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import nadel.contract.model.BillingMonthChecklist;
import nadel.contract.model.BillingMonthDetails;
import nadel.contract.model.BillingMonthStatus;
import nadel.contract.model.BillingMonthSummary;
import nadel.contract.model.ChargeTypeCode;
import nadel.contract.model.ChargeTypeTotal;
import nadel.contract.model.MeterResource;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The billing months of the cooperative ({@code GET /billing-months}, {@code GET /billing-months/{month}}, feature 011
 * §2.2). The listed months run from the first month row (the month of the first booking) to the current month in
 * Europe/Kyiv, or to the last row when one is later; a month without a row is listed {@code OPEN} with no totals and is
 * not created by a read. Totals are the sums of the charges booked in the month per charge type, reversals negative,
 * added exactly in the database ({@code numeric(14,2)}), nothing rounded; {@code received} is the sum of the payments
 * booked in the month, reversals of payments negative (feature 015 §6: a payment received in a closed month is booked
 * in, and counts in, the earliest open month).
 * <p>
 * The check list of one month: the month run is done ({@code run_at}), the open "not charged" items of the month
 * ({@link #notChargedCount}) and the stale plot meters
 * ({@link MeterNetwork#staleMeters}, more than {@value #STALE_DAYS} days without a reading as of the month's last day
 * or today, whichever is earlier).
 */
@Service
class BillingMonthQueries {

    /** A plot meter without a reading for more than this many days is on the check list (feature 010). */
    static final int STALE_DAYS = 60;

    private final BillingMonthRepository months;
    private final ChargeRepository charges;
    private final PaymentRepository payments;
    private final ChargeTypeCatalog chargeTypes;
    private final MeterNetwork meterNetwork;
    private final PlotCatalog plots;
    private final ActorNames actorNames;
    private final NotChargedItemRepository items;
    private final Clock clock;

    BillingMonthQueries(BillingMonthRepository months, ChargeRepository charges, PaymentRepository payments,
            ChargeTypeCatalog chargeTypes, MeterNetwork meterNetwork, PlotCatalog plots, ActorNames actorNames,
            NotChargedItemRepository items, Clock clock) {
        this.months = months;
        this.charges = charges;
        this.payments = payments;
        this.chargeTypes = chargeTypes;
        this.meterNetwork = meterNetwork;
        this.plots = plots;
        this.actorNames = actorNames;
        this.items = items;
        this.clock = clock;
    }

    /** Every listed month, newest first; empty while the cooperative has no month. */
    @Transactional(readOnly = true)
    List<BillingMonthSummary> list() {
        List<BillingMonthRecord> rows = months.findAllInOrder();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<YearMonth, BillingMonthRecord> byMonth = rows.stream()
                .collect(Collectors.toMap(BillingMonthRecord::yearMonth, Function.identity()));
        Map<UUID, List<MonthTypeTotal>> totals = charges.totalsPerMonthAndType().stream()
                .collect(Collectors.groupingBy(MonthTypeTotal::billingMonthId));
        Map<UUID, Money> received = new HashMap<>();
        payments.receivedPerMonth().forEach(r -> received.put(r.billingMonthId(), Money.of(r.amount())));
        List<ChargeTypeFacts> types = typesInOrder();
        List<BillingMonthSummary> listed = new ArrayList<>();
        YearMonth first = rows.getFirst().yearMonth();
        for (YearMonth month = last(rows); !month.isBefore(first); month = month.minusMonths(1)) {
            BillingMonthRecord row = byMonth.get(month);
            List<MonthTypeTotal> ofMonth = row == null ? List.of() : totals.getOrDefault(row.getId(), List.of());
            Money ofMonthReceived = row == null ? Money.ZERO : received.getOrDefault(row.getId(), Money.ZERO);
            listed.add(summary(month, row, ofMonth, ofMonthReceived, types));
        }
        return listed;
    }

    /**
     * One listed month with its check list.
     *
     * @throws ApiProblemException 404 {@code NOT_FOUND} when the month is not listed
     */
    @Transactional(readOnly = true)
    BillingMonthDetails details(YearMonth month) {
        List<BillingMonthRecord> rows = months.findAllInOrder();
        if (rows.isEmpty() || month.isBefore(rows.getFirst().yearMonth()) || month.isAfter(last(rows))) {
            throw ApiProblemException.notFound();
        }
        Optional<BillingMonthRecord> row = rows.stream().filter(m -> m.yearMonth().equals(month)).findFirst();
        List<MonthTypeTotal> ofMonth = row.map(m -> charges.totalsOfMonth(m.getId())).orElse(List.of());
        Money received = row.map(m -> Money.of(payments.receivedInMonth(m.getId()))).orElse(Money.ZERO);
        BillingMonthSummary summary = summary(month, row.orElse(null), ofMonth, received, typesInOrder());
        boolean runDone = row.map(BillingMonthRecord::isRunDone).orElse(false);

        LocalDate today = LocalDate.now(clock);
        LocalDate asOf = month.atEndOfMonth().isBefore(today) ? month.atEndOfMonth() : today;
        BillingMonthChecklist checklist = new BillingMonthChecklist(runDone, notChargedCount(month),
                staleMeters(asOf));
        BillingMonthDetails details = new BillingMonthDetails(summary.getMonth(), summary.getStatus(), runDone,
                summary.getTotals(), summary.getTotal(), summary.getReceived(), checklist);
        details.setClosedAt(summary.getClosedAt());
        UUID closedBy = row.map(BillingMonthRecord::getClosedBy).orElse(null);
        if (closedBy != null) {
            details.setClosedByName(actorNames.displayNames(List.of(closedBy)).get(closedBy));
        }
        return details;
    }

    /**
     * The open "not charged" items of the month: those about the month itself (the month run) and those without a
     * month whose days end in it.
     */
    int notChargedCount(YearMonth month) {
        return Math.toIntExact(items.countOpenOfMonth(month.atDay(1), month.atEndOfMonth()));
    }

    /** The current month, or the last row when a month after it exists (a late booking opened it). */
    private YearMonth last(List<BillingMonthRecord> rows) {
        YearMonth current = YearMonth.now(clock);
        YearMonth lastRow = rows.getLast().yearMonth();
        return lastRow.isAfter(current) ? lastRow : current;
    }

    private List<ChargeTypeFacts> typesInOrder() {
        List<ChargeTypeFacts> types = new ArrayList<>(chargeTypes.all());
        types.sort(Comparator.comparingInt(ChargeTypeFacts::position).thenComparing(ChargeTypeFacts::id));
        return types;
    }

    private static BillingMonthSummary summary(YearMonth month, @Nullable BillingMonthRecord row,
            List<MonthTypeTotal> totals, Money received, List<ChargeTypeFacts> types) {
        Map<UUID, Money> byType = new HashMap<>();
        totals.forEach(t -> byType.put(t.chargeTypeId(), Money.of(t.amount())));
        List<ChargeTypeTotal> listed = types.stream().filter(type -> byType.containsKey(type.id()))
                .map(type -> total(type, byType.get(type.id()))).toList();
        Money total = byType.values().stream().reduce(Money.ZERO, Money::plus);
        boolean closed = row != null && row.isClosed();
        BillingMonthSummary summary = new BillingMonthSummary(month.toString(),
                closed ? BillingMonthStatus.CLOSED : BillingMonthStatus.OPEN, row != null && row.isRunDone(), listed,
                total.toString(), received.toString());
        if (row != null && row.getClosedAt() != null) {
            summary.setClosedAt(row.getClosedAt().atOffset(ZoneOffset.UTC));
        }
        return summary;
    }

    static ChargeTypeTotal total(ChargeTypeFacts type, Money amount) {
        ChargeTypeTotal total = new ChargeTypeTotal(type.id(), type.name(), type.nameCustomized(), amount.toString());
        if (type.code() != null) {
            total.setChargeTypeCode(ChargeTypeCode.fromValue(type.code()));
        }
        return total;
    }

    /** The stale plot meters with their plot numbers, in the natural order of the numbers, electricity first. */
    private List<nadel.contract.model.StaleMeter> staleMeters(LocalDate asOf) {
        List<StaleMeter> stale = meterNetwork.staleMeters(asOf, STALE_DAYS);
        if (stale.isEmpty()) {
            return List.of();
        }
        List<UUID> plotIds = stale.stream().map(StaleMeter::plotId).distinct().toList();
        Map<UUID, PlotFacts> facts = plots.findAll(plotIds);
        Map<UUID, Integer> rank = new HashMap<>();
        List<UUID> ordered = plots.inNaturalOrder(plotIds);
        for (int i = 0; i < ordered.size(); i++) {
            rank.put(ordered.get(i), i);
        }
        return stale.stream()
                .filter(meter -> facts.containsKey(meter.plotId()))
                .sorted(Comparator.comparing((StaleMeter meter) -> rank.getOrDefault(meter.plotId(), Integer.MAX_VALUE))
                        .thenComparing(StaleMeter::resource).thenComparing(StaleMeter::meterId))
                .map(meter -> new nadel.contract.model.StaleMeter(meter.meterId(), meter.plotId(),
                        facts.get(meter.plotId()).number(), MeterResource.fromValue(meter.resource().name()),
                        meter.lastReadingOn()))
                .toList();
    }
}
