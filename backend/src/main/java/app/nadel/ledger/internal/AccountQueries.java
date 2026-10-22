package app.nadel.ledger.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.ledger.internal.DebtCalculator.Debts;
import app.nadel.ledger.internal.DebtCalculator.Line;
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
import nadel.contract.model.Charge;
import nadel.contract.model.ChargeTypeCode;
import nadel.contract.model.ChargeTypeDebt;
import nadel.contract.model.PlotAccount;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The account of a plot ({@code GET /plots/{id}/account}, feature 011 §2.1): the debt per charge type from every charge
 * of the plot ({@link DebtCalculator}) and the charges, newest first, optionally limited by a date range on
 * {@code accruedOn} (the range never changes the debts). One read-only transaction; the plot's charges, the months and
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
    PlotAccount account(UUID plotId, @Nullable LocalDate from, @Nullable LocalDate to, AccessProfile viewer) {
        access.requireCooperativeData(viewer);
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("from", ProblemCodes.INVALID)));
        }
        if (plots.find(plotId).isEmpty() || !access.maySee(viewer, plotId)) {
            throw LedgerAccess.plotNotFound();
        }
        List<ChargeRecord> all = charges.findByPlotId(plotId);
        Debts debts = DebtCalculator.of(all.stream().map(c -> new Line(c.getId(), c.getChargeTypeId(), c.getAmount(),
                c.getAccruedOn(), c.getReversesChargeId())).toList());
        Map<UUID, UUID> reversedBy = new HashMap<>();
        all.stream().filter(c -> c.getReversesChargeId() != null)
                .forEach(c -> reversedBy.put(c.getReversesChargeId(), c.getId()));

        List<ChargeRecord> shown = all.stream()
                .filter(c -> from == null || !c.getAccruedOn().isBefore(from))
                .filter(c -> to == null || !c.getAccruedOn().isAfter(to))
                .sorted(NEWEST_FIRST).toList();
        ChargeViews.Context context = views.context(shown, viewer);
        List<Charge> listed = shown.stream().map(c -> views.view(c, reversedBy.get(c.getId()), context)).toList();
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
