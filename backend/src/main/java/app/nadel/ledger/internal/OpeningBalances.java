package app.nadel.ledger.internal;

import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.ledger.ChargesBooked;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.Charge;
import nadel.contract.model.OpeningBalanceInput;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enters an opening balance by hand ({@code POST /plots/{id}/opening-balances}, feature 011 §2.9): the debt a plot had
 * for one charge type before the cooperative started with the platform, as of a date. It is a charge of kind and source
 * {@code OPENING} whose service period and debt age are that date, booked in the month of the date or, when that month
 * is closed, in the earliest open month after it ({@link BillingCalendar}).
 * <p>
 * The rules: the amount is greater than zero ({@code AMOUNT_NOT_POSITIVE}) and fits {@code numeric(14,2)}
 * ({@code INVALID}); the date is not after today in Europe/Kyiv ({@code DATE_IN_FUTURE}); the plot exists in the
 * cooperative (404 {@code PLOT_NOT_FOUND}), is not archived ({@code PLOT_ARCHIVED}) and not common land
 * ({@code PLOT_COMMON_LAND}); the charge type exists (404 {@code NOT_FOUND} on {@code chargeTypeId}) and is not archived
 * ({@code CHARGE_TYPE_ARCHIVED}). The plot is locked {@code FOR SHARE} first (so a deletion of the plot, which asks
 * {@code LedgerPlotGuard}, runs before or after), then the charge type {@code FOR SHARE}, then the month. The optional
 * reason (trimmed; blank means none) is user content: it is stored on the charge and in the audit entry, never logged.
 */
@Service
class OpeningBalances {

    /** {@code numeric(14,2)}: at most 12 digits before the point. */
    private static final int MAX_INTEGER_DIGITS = 12;

    private final PlotCatalog plots;
    private final ChargeTypeCatalog chargeTypes;
    private final BillingMonths billingMonths;
    private final ChargeViews views;
    private final LedgerAuditPublisher audit;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;
    private final Clock clock;

    OpeningBalances(PlotCatalog plots, ChargeTypeCatalog chargeTypes, BillingMonths billingMonths, ChargeViews views,
            LedgerAuditPublisher audit, ApplicationEventPublisher events, EntityManager entityManager, Clock clock) {
        this.plots = plots;
        this.chargeTypes = chargeTypes;
        this.billingMonths = billingMonths;
        this.views = views;
        this.audit = audit;
        this.events = events;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    Charge enter(UUID plotId, OpeningBalanceInput input, AccessProfile actor) {
        Money amount = Money.parse(input.getAmount());
        LocalDate date = input.getDate();
        List<FieldProblem> errors = new ArrayList<>();
        if (amount.signum() <= 0) {
            errors.add(new FieldProblem("amount", ProblemCodes.AMOUNT_NOT_POSITIVE));
        } else if (amount.value().precision() - amount.value().scale() > MAX_INTEGER_DIGITS) {
            errors.add(new FieldProblem("amount", ProblemCodes.INVALID));
        }
        if (date.isAfter(LocalDate.now(clock))) {
            errors.add(new FieldProblem("date", ProblemCodes.DATE_IN_FUTURE));
        }
        if (!errors.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", errors);
        }
        String reason = input.getReason() == null || input.getReason().isBlank() ? null : input.getReason().strip();

        PlotFacts plot = plots.findAndLockShared(plotId).orElseThrow(LedgerAccess::plotNotFound);
        if (plot.archived()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PLOT_ARCHIVED, "The plot is archived.");
        }
        if (plot.commonLand()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PLOT_COMMON_LAND,
                    "Common land has no charges.");
        }
        ChargeTypeFacts type = chargeTypes.findAndLockShared(input.getChargeTypeId())
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, ProblemCodes.NOT_FOUND,
                        "The charge type was not found.",
                        List.of(new FieldProblem("chargeTypeId", ProblemCodes.NOT_FOUND))));
        if (type.archived()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.CHARGE_TYPE_ARCHIVED,
                    "The charge type is archived.",
                    List.of(new FieldProblem("chargeTypeId", ProblemCodes.CHARGE_TYPE_ARCHIVED)));
        }

        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        BillingMonthRecord month = billingMonths.openForBooking(tenantId, date);
        ChargeRecord charge = ChargeRecord.opening(tenantId, plotId, type.id(), month.getId(), amount, date, reason,
                ChargeViews.plainCalculation(amount), actor.memberId(), clock.instant());
        entityManager.persist(charge);
        entityManager.flush();

        List<FieldChange> changes = new ArrayList<>(List.of(
                new FieldChange("chargeType", null, type.auditName()),
                new FieldChange("amount", null, amount.toString()),
                new FieldChange("date", null, date.toString()),
                new FieldChange("billingMonth", null, month.yearMonth().toString())));
        if (reason != null) {
            changes.add(new FieldChange("reason", null, reason));
        }
        audit.publish(actor, "OPENING_BALANCE_ENTERED", plotId, plot.number(), changes);
        events.publishEvent(new ChargesBooked(tenantId, Set.of(plotId)));
        return views.view(charge, null, views.context(List.of(charge), actor));
    }
}
