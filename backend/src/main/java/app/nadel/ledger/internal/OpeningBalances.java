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
import org.jspecify.annotations.Nullable;
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
        String amountProblem = OpeningBalanceRules.amountProblem(amount);
        if (amountProblem != null) {
            errors.add(new FieldProblem("amount", amountProblem));
        }
        String dateProblem = OpeningBalanceRules.dateProblem(date, LocalDate.now(clock));
        if (dateProblem != null) {
            errors.add(new FieldProblem("date", dateProblem));
        }
        if (!errors.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", errors);
        }
        String reason = OpeningBalanceRules.reason(input.getReason());

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
        Booked booked = book(tenantId, plotId, type, amount, date, reason, null, actor.memberId(),
                billingMonths.openForBooking(tenantId, date));
        entityManager.flush();

        audit.publish(actor, LedgerAuditPublisher.Action.OPENING_BALANCE_ENTERED, plotId, plot.number(),
                booked.auditChanges());
        events.publishEvent(new ChargesBooked(tenantId, Set.of(plotId)));
        return views.view(booked.charge(), null, views.context(List.of(booked.charge()), actor));
    }

    /**
     * A booked opening balance and the audit values that describe it.
     *
     * @param charge       the new charge, persisted but not flushed
     * @param auditChanges the values of its {@code OPENING_BALANCE_ENTERED} entry
     */
    record Booked(ChargeRecord charge, List<FieldChange> auditChanges) {
    }

    /**
     * Books an opening balance whose values passed {@link OpeningBalanceRules} and whose plot and charge type were
     * checked and locked by the caller, in {@code month}: the month {@link BillingMonths#openForBooking} gave for
     * {@code date} (the month of the date or the earliest open month after it), locked by it. The charge is persisted,
     * not flushed, and nothing is published: the caller flushes and audits. Runs in the caller's transaction.
     *
     * @param sourceKey {@code OPENING:{importId}:{row}} for an imported balance, null for one entered by hand
     */
    Booked book(UUID tenantId, UUID plotId, ChargeTypeFacts type, Money amount, LocalDate date,
            @Nullable String reason, @Nullable String sourceKey, @Nullable UUID createdBy, BillingMonthRecord month) {
        ChargeRecord charge = ChargeRecord.opening(tenantId, plotId, type.id(), month.getId(), amount, date, reason,
                sourceKey, ChargeViews.plainCalculation(amount), createdBy, clock.instant());
        entityManager.persist(charge);
        return new Booked(charge, OpeningBalanceRules.auditChanges(type, amount, date, month.yearMonth(), reason));
    }
}
