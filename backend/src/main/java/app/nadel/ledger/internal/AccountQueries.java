package app.nadel.ledger.internal;

import app.nadel.audit.ActorNames;
import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import app.nadel.ledger.internal.ChargeViews.Links;
import app.nadel.ledger.internal.DebtCalculator.Debts;
import app.nadel.ledger.internal.OpenAmounts.AllocationEntry;
import app.nadel.ledger.internal.OpenAmounts.ChargeEntry;
import app.nadel.ledger.internal.OpenAmounts.PaymentEntry;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.settings.CooperativeSettings;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import nadel.contract.model.AccountPayment;
import nadel.contract.model.AccountPrepaymentAllocation;
import nadel.contract.model.Charge;
import nadel.contract.model.ChargeTypeCode;
import nadel.contract.model.ChargeTypeDebt;
import nadel.contract.model.PaymentAllocation;
import nadel.contract.model.PaymentSource;
import nadel.contract.model.PlotAccount;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The account of a plot ({@code GET /plots/{id}/account}, feature 011 §2.1, feature 012 §2.3, feature 015 §2.9 and
 * §2.10): the debt per charge type after payments and the prepayment, from every charge, allocation and payment of the
 * plot ({@link DebtCalculator}, {@link OpenAmounts}); the charges newest first, each regular charge and opening balance
 * with what is paid and open on it; the payments and their reversals newest first, each payment with its allocations
 * and releases; and the placements of the prepayment ("allocated from prepayment": the allocation rows of origin
 * {@code PREPAYMENT_AUTO} or {@code PREPAYMENT_MANUAL} written together), newest first. The lists are optionally limited
 * by a date range (charges on {@code accruedOn}, payments on {@code receivedOn}, placements on their day in
 * Europe/Kyiv) and by a kind: a charge kind lists only those charges, {@link Filter#paymentsOnly} only the payments and
 * their reversals; neither ever changes the debts or the prepayment. Each charge carries its links within the plot's
 * ledger: its reversal, its adjustments (oldest first) and, for an adjustment, the charge it corrects. One read-only
 * transaction; the plot's charges, allocations and payments, the months and the charge types are loaded once each.
 * <p>
 * Who may ask: see {@link LedgerAccess}. A member sees amounts only for the plots they own now; every other plot is the
 * same 404 as an unknown one. Payments are listed for {@code PAYMENTS_VIEW} and for an owner of the plot today; the
 * payer note only with {@code PAYMENTS_VIEW} and {@code MEMBERS_VIEW}, the author's name only with
 * {@code CHARGES_VIEW}, so a member never sees either.
 */
@Service
class AccountQueries {

    /**
     * What the lists of the account show.
     *
     * @param from         the first day, inclusive; null for no limit
     * @param to           the last day, inclusive; null for no limit
     * @param chargeKind   only charges of this kind (and no payments); null for every kind
     * @param paymentsOnly only the payments and their reversals (no charges, no placements of the prepayment)
     */
    record Filter(@Nullable LocalDate from, @Nullable LocalDate to, @Nullable ChargeKind chargeKind,
            boolean paymentsOnly) {

        Filter {
            if (chargeKind != null && paymentsOnly) {
                throw new IllegalArgumentException("A charge kind and payments only exclude each other");
            }
        }

        boolean listsCharges() {
            return !paymentsOnly;
        }

        boolean listsPayments() {
            return chargeKind == null;
        }

        boolean listsPlacements() {
            return chargeKind == null && !paymentsOnly;
        }

        boolean within(LocalDate day) {
            return (from == null || !day.isBefore(from)) && (to == null || !day.isAfter(to));
        }
    }

    private static final Comparator<ChargeRecord> NEWEST_FIRST = Comparator
            .comparing(ChargeRecord::getAccruedOn).thenComparing(ChargeRecord::getCreatedAt)
            .thenComparing(ChargeRecord::getId).reversed();
    private static final Comparator<ChargeRecord> BOOKED_FIRST = Comparator.comparing(ChargeRecord::getCreatedAt)
            .thenComparing(ChargeRecord::getId);
    private static final Comparator<PaymentRecord> PAYMENTS_NEWEST_FIRST = Comparator
            .comparing(PaymentRecord::getReceivedOn).thenComparing(PaymentRecord::getCreatedAt)
            .thenComparing(PaymentRecord::getId).reversed();
    /** Allocations and releases of one payment as the card lists them ({@link PaymentQueries#HISTORY_ORDER}). */
    private static final Comparator<AllocationRecord> ROWS_OLDEST_FIRST = PaymentQueries.HISTORY_ORDER;

    private final PlotCatalog plots;
    private final ChargeRepository charges;
    private final PaymentRepository payments;
    private final AllocationRepository allocations;
    private final LedgerAccess access;
    private final ChargeViews views;
    private final ActorNames actorNames;
    private final PaymentNoteCipher notes;
    private final CooperativeSettings settings;

    AccountQueries(PlotCatalog plots, ChargeRepository charges, PaymentRepository payments,
            AllocationRepository allocations, LedgerAccess access, ChargeViews views, ActorNames actorNames,
            PaymentNoteCipher notes, CooperativeSettings settings) {
        this.plots = plots;
        this.charges = charges;
        this.payments = payments;
        this.allocations = allocations;
        this.access = access;
        this.views = views;
        this.actorNames = actorNames;
        this.notes = notes;
        this.settings = settings;
    }

    /**
     * @throws ApiProblemException 400 when {@code from} is after {@code to}, 403 for the platform operator, 404
     *                             {@code PLOT_NOT_FOUND} when the plot is unknown or not the caller's to see
     */
    @Transactional(readOnly = true)
    PlotAccount account(UUID plotId, Filter filter, AccessProfile viewer) {
        access.requireCooperativeData(viewer);
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("from", ProblemCodes.INVALID)));
        }
        if (plots.find(plotId).isEmpty() || !access.maySee(viewer, plotId)) {
            throw LedgerAccess.plotNotFound();
        }
        List<ChargeRecord> all = charges.findByPlotId(plotId);
        List<PaymentRecord> paymentRows = payments.findByPlotIdIn(List.of(plotId));
        List<AllocationRecord> allocationRows = allocations.findByPlotIdIn(List.of(plotId));
        List<ChargeEntry> chargeEntries = all.stream().sorted(BOOKED_FIRST).map(OpenAmounts::entry).toList();
        List<AllocationEntry> allocationEntries = allocationRows.stream().map(OpenAmounts::entry).toList();
        List<PaymentEntry> paymentEntries = paymentRows.stream().map(OpenAmounts::entry).toList();
        Debts debts = DebtCalculator.of(chargeEntries, allocationEntries, paymentEntries);
        Map<UUID, ChargeState> chargeStates = new HashMap<>();
        PlotLedgerState ledger = OpenAmounts.of(chargeEntries, allocationEntries, paymentEntries, Map.of());
        ledger.charges().forEach(state -> chargeStates.put(state.id(), state));

        Map<UUID, ChargeRecord> byId = all.stream().collect(Collectors.toMap(ChargeRecord::getId, Function.identity()));
        Map<UUID, UUID> reversedBy = new HashMap<>();
        Map<UUID, List<UUID>> adjustedBy = new HashMap<>();
        all.stream().filter(c -> c.getReversesChargeId() != null)
                .forEach(c -> reversedBy.put(c.getReversesChargeId(), c.getId()));
        all.stream().filter(c -> c.getAdjustsChargeId() != null).sorted(BOOKED_FIRST)
                .forEach(c -> adjustedBy.computeIfAbsent(c.getAdjustsChargeId(), id -> new ArrayList<>()).add(c.getId()));

        List<ChargeRecord> shown = !filter.listsCharges() ? List.of() : all.stream()
                .filter(c -> filter.within(c.getAccruedOn()))
                .filter(c -> filter.chargeKind() == null || c.getKind() == filter.chargeKind())
                .sorted(NEWEST_FIRST).toList();
        ChargeViews.Context context = views.context(shown, viewer);
        List<Charge> listed = shown.stream().map(c -> {
            Charge dto = views.view(c, new Links(reversedBy.get(c.getId()),
                    adjustedBy.getOrDefault(c.getId(), List.of()),
                    c.getAdjustsChargeId() == null ? null : byId.get(c.getAdjustsChargeId())), context);
            ChargeState state = chargeStates.get(c.getId());
            if (state != null) {
                dto.setPaid(state.allocated().toString());
                dto.setOpen(state.open().toString());
            }
            return dto;
        }).toList();

        List<AccountPayment> listedPayments = List.of();
        List<AccountPrepaymentAllocation> placements = List.of();
        if (access.maySeePayments(viewer, plotId)) {
            PaymentViews.Context paymentContext = new PaymentViews.Context(byId, context.months(), context.types());
            listedPayments = filter.listsPayments()
                    ? payments(paymentRows, allocationRows, filter, paymentContext, viewer)
                    : List.of();
            placements = filter.listsPlacements() ? placements(allocationRows, filter, paymentContext) : List.of();
        }
        return new PlotAccount(plotId, debtsInTypeOrder(debts, context.types()), debts.total().toString(),
                debts.prepayment().toString(),
                LedgerAccess.prepaymentAllocatable(viewer, settings::autoAllocatePrepayment, ledger), listed,
                listedPayments, placements);
    }

    /** The payments and reversals within the filter, newest first, as the viewer may see them. */
    private List<AccountPayment> payments(List<PaymentRecord> rows, List<AllocationRecord> allocationRows,
            Filter filter, PaymentViews.Context context, AccessProfile viewer) {
        List<PaymentRecord> shown = rows.stream().filter(p -> filter.within(p.getReceivedOn()))
                .sorted(PAYMENTS_NEWEST_FIRST).toList();
        if (shown.isEmpty()) {
            return List.of();
        }
        Map<UUID, UUID> reversedBy = new HashMap<>();
        rows.stream().filter(p -> p.getReversesPaymentId() != null)
                .forEach(p -> reversedBy.put(p.getReversesPaymentId(), p.getId()));
        Map<UUID, List<AllocationRecord>> ofPayment = allocationRows.stream().sorted(ROWS_OLDEST_FIRST)
                .collect(Collectors.groupingBy(AllocationRecord::getPaymentId, LinkedHashMap::new,
                        Collectors.toList()));
        Map<UUID, String> names = Map.of();
        if (viewer.can(Permission.CHARGES_VIEW)) {
            List<UUID> authors = shown.stream().map(PaymentRecord::getCreatedBy).filter(Objects::nonNull).distinct()
                    .toList();
            names = authors.isEmpty() ? Map.of() : actorNames.displayNames(authors);
        }
        boolean seesNotes = viewer.can(Permission.PAYMENTS_VIEW) && viewer.can(Permission.MEMBERS_VIEW);
        UUID tenantId = Objects.requireNonNull(viewer.tenantId());
        List<AccountPayment> listed = new ArrayList<>();
        for (PaymentRecord payment : shown) {
            boolean isPayment = payment.getKind() == PaymentKind.PAYMENT;
            UUID reversal = reversedBy.get(payment.getId());
            List<PaymentAllocation> lines = !isPayment ? List.of()
                    : ofPayment.getOrDefault(payment.getId(), List.of()).stream()
                            .map(row -> PaymentViews.allocation(row, context)).toList();
            AccountPayment dto = new AccountPayment(payment.getId(),
                    nadel.contract.model.PaymentKind.fromValue(payment.getKind().name()), payment.getGroupId(),
                    PaymentSource.fromValue(payment.getSource().name()), payment.getAmount().toString(),
                    payment.getReceivedOn(), PaymentViews.month(context, payment.getBillingMonthId()),
                    isPayment && reversal != null, lines, payment.getCreatedAt().atOffset(ZoneOffset.UTC));
            dto.setReceiptNumber(payment.getReceiptNumber());
            dto.setBankReference(payment.getBankReference());
            dto.setReversedBy(reversal);
            dto.setReverses(payment.getReversesPaymentId());
            dto.setReason(payment.getReason());
            if (seesNotes && isPayment) {
                dto.setPayerNote(notes.open(tenantId, payment.getId(), payment.getPayerNoteEnc()));
            }
            if (payment.getCreatedBy() != null) {
                dto.setCreatedByName(names.get(payment.getCreatedBy()));
            }
            listed.add(dto);
        }
        return listed;
    }

    /** One placement of the prepayment: the rows of one origin written by one actor at one time. */
    private record PlacementKey(Instant createdAt, AllocationOrigin origin, @Nullable UUID createdBy) {
    }

    /**
     * The placements of the prepayment within the filter, newest first; the lines of each in the order of their
     * charges (accrued oldest first).
     */
    private static List<AccountPrepaymentAllocation> placements(List<AllocationRecord> rows, Filter filter,
            PaymentViews.Context context) {
        Map<PlacementKey, List<AllocationRecord>> grouped = rows.stream()
                .filter(row -> row.getOrigin() == AllocationOrigin.PREPAYMENT_AUTO
                        || row.getOrigin() == AllocationOrigin.PREPAYMENT_MANUAL)
                .filter(row -> filter.within(placedOn(row.getCreatedAt())))
                .collect(Collectors.groupingBy(row -> new PlacementKey(row.getCreatedAt(),
                        Objects.requireNonNull(row.getOrigin()), row.getCreatedBy())));
        Comparator<AllocationRecord> byCharge = Comparator
                .comparing((AllocationRecord row) -> context.charges().get(row.getChargeId()).getAccruedOn())
                .thenComparing(row -> context.charges().get(row.getChargeId()).getCreatedAt())
                .thenComparing(row -> row.getId().toString());
        return grouped.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<PlacementKey, List<AllocationRecord>> e) -> e.getKey()
                        .createdAt()).thenComparing(e -> e.getKey().origin()).reversed())
                .map(entry -> {
                    List<AllocationRecord> lines = entry.getValue().stream().sorted(byCharge).toList();
                    Money amount = lines.stream().map(AllocationRecord::getAmount).reduce(Money.ZERO, Money::plus);
                    PlacementKey key = entry.getKey();
                    return new AccountPrepaymentAllocation(placedOn(key.createdAt()),
                            key.createdAt().atOffset(ZoneOffset.UTC),
                            nadel.contract.model.AllocationOrigin.fromValue(key.origin().name()),
                            key.createdBy() == null, amount.toString(),
                            lines.stream().map(row -> PaymentViews.allocation(row, context)).toList());
                }).toList();
    }

    /** The day a placement was written, in Europe/Kyiv. */
    private static LocalDate placedOn(Instant createdAt) {
        return createdAt.atZone(PaymentRules.ZONE).toLocalDate();
    }

    /** The debts in the order of the charge types (position, then id). */
    private static List<ChargeTypeDebt> debtsInTypeOrder(Debts debts, Map<UUID, ChargeTypeFacts> types) {
        List<ChargeTypeFacts> ordered = new ArrayList<>(types.values().stream()
                .filter(type -> debts.byType().containsKey(type.id())).toList());
        ordered.sort(Comparator.comparingInt(ChargeTypeFacts::position).thenComparing(ChargeTypeFacts::id));
        return ordered.stream().map(type -> debt(type, debts.byType().get(type.id()))).toList();
    }

    private static ChargeTypeDebt debt(ChargeTypeFacts type, Money amount) {
        ChargeTypeDebt debt = new ChargeTypeDebt(type.id(), type.name(), type.nameCustomized(), amount.toString());
        if (type.code() != null) {
            debt.setChargeTypeCode(ChargeTypeCode.fromValue(type.code()));
        }
        return debt;
    }
}
