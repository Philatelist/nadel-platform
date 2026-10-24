package app.nadel.ledger.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.ledger.internal.ChargeViews.Links;
import app.nadel.ledger.internal.DebtCalculator.Debts;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import nadel.contract.model.Charge;
import nadel.contract.model.ChargeTypeCode;
import nadel.contract.model.ChargeTypeDebt;
import nadel.contract.model.PlotAccount;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The account of a plot ({@code GET /plots/{id}/account}, feature 011 §2.1, feature 012 §2.3): the debt per charge type
 * from every charge of the plot, adjustments included ({@link DebtCalculator}), and the charges, newest first,
 * optionally limited by a date range on {@code accruedOn} and by a kind (neither ever changes the debts). Each charge
 * carries its links within the plot's ledger: its reversal, its adjustments (oldest first) and, for an adjustment, the
 * charge it corrects. One read-only transaction; the plot's charges, the months and
 * the charge types are loaded once each.
 * <p>
 * Who may ask: see {@link LedgerAccess}. A member sees amounts only for the plots they own now; every other plot is the
 * same 404 as an unknown one.
 */
@Service
class AccountQueries {

    private static final Comparator<ChargeRecord> NEWEST_FIRST = Comparator
            .comparing(ChargeRecord::getAccruedOn).thenComparing(ChargeRecord::getCreatedAt)
            .thenComparing(ChargeRecord::getId).reversed();
    private static final Comparator<ChargeRecord> BOOKED_FIRST = Comparator.comparing(ChargeRecord::getCreatedAt)
            .thenComparing(ChargeRecord::getId);

    private final PlotCatalog plots;
    private final ChargeRepository charges;
    private final LedgerAccess access;
    private final ChargeViews views;

    AccountQueries(PlotCatalog plots, ChargeRepository charges, LedgerAccess access, ChargeViews views) {
        this.plots = plots;
        this.charges = charges;
        this.access = access;
        this.views = views;
    }

    /**
     * @throws ApiProblemException 400 when {@code from} is after {@code to}, 403 for the platform operator, 404
     *                             {@code PLOT_NOT_FOUND} when the plot is unknown or not the caller's to see
     */
    @Transactional(readOnly = true)
    PlotAccount account(UUID plotId, @Nullable LocalDate from, @Nullable LocalDate to, @Nullable ChargeKind kind,
            AccessProfile viewer) {
        access.requireCooperativeData(viewer);
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("from", ProblemCodes.INVALID)));
        }
        if (plots.find(plotId).isEmpty() || !access.maySee(viewer, plotId)) {
            throw LedgerAccess.plotNotFound();
        }
        List<ChargeRecord> all = charges.findByPlotId(plotId);
        Debts debts = DebtCalculator.of(all.stream().map(DebtCalculator::line).toList());
        Map<UUID, ChargeRecord> byId = all.stream().collect(Collectors.toMap(ChargeRecord::getId, Function.identity()));
        Map<UUID, UUID> reversedBy = new HashMap<>();
        Map<UUID, List<UUID>> adjustedBy = new HashMap<>();
        all.stream().filter(c -> c.getReversesChargeId() != null)
                .forEach(c -> reversedBy.put(c.getReversesChargeId(), c.getId()));
        all.stream().filter(c -> c.getAdjustsChargeId() != null).sorted(BOOKED_FIRST)
                .forEach(c -> adjustedBy.computeIfAbsent(c.getAdjustsChargeId(), id -> new ArrayList<>()).add(c.getId()));

        List<ChargeRecord> shown = all.stream()
                .filter(c -> from == null || !c.getAccruedOn().isBefore(from))
                .filter(c -> to == null || !c.getAccruedOn().isAfter(to))
                .filter(c -> kind == null || c.getKind() == kind)
                .sorted(NEWEST_FIRST).toList();
        ChargeViews.Context context = views.context(shown, viewer);
        List<Charge> listed = shown.stream().map(c -> views.view(c, new Links(reversedBy.get(c.getId()),
                adjustedBy.getOrDefault(c.getId(), List.of()),
                c.getAdjustsChargeId() == null ? null : byId.get(c.getAdjustsChargeId())), context)).toList();
        return new PlotAccount(plotId, debtsInTypeOrder(debts, context.types()), debts.total().toString(), listed);
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
