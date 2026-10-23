package app.nadel.ledger.internal;

import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.ledger.ChargesBooked;
import app.nadel.plots.PlotCatalog;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ConstraintViolations;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.tariffs.ChargeTypeCatalog;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.Charge;
import nadel.contract.model.ChargeReversal;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reverses a charge by hand ({@code POST /charges/{id}/reverse}, feature 011 §2.1): a new {@code REVERSAL} row with the
 * opposite amount, the same plot, charge type, service period and debt age, the reason and a pointer to the original,
 * booked in the original's billing month. Nothing is changed or deleted.
 * <p>
 * The rules, in order: a reason (trimmed, not empty); the charge exists in the cooperative (404); it is not a reversal
 * itself ({@code REVERSAL_NOT_REVERSIBLE}) nor an adjustment ({@code ADJUSTMENT_NOT_REVERSIBLE}; a later recalculation
 * adjusts the original again, and the database refuses such a reversal too, {@code charge_target_guard}); its month is open ({@code CHARGE_IN_CLOSED_MONTH}; the month is locked
 * {@code FOR SHARE}, so a closing waits); it was not reversed before ({@code CHARGE_ALREADY_REVERSED}). Two reversals of
 * the same charge at the same time meet at the unique index {@code charge_reversed_once_uq}, whose violation is the same
 * 409. The reason is user content: it goes to the ledger and the audit entry, never to a log.
 */
@Service
class Reversals {

    private static final String REVERSED_ONCE_INDEX = "charge_reversed_once_uq";

    private final ChargeRepository charges;
    private final BillingMonthRepository months;
    private final BillingMonths billingMonths;
    private final ChargeTypeCatalog chargeTypes;
    private final PlotCatalog plots;
    private final ChargeViews views;
    private final LedgerAuditPublisher audit;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;
    private final Clock clock;

    Reversals(ChargeRepository charges, BillingMonthRepository months, BillingMonths billingMonths,
            ChargeTypeCatalog chargeTypes, PlotCatalog plots, ChargeViews views, LedgerAuditPublisher audit,
            ApplicationEventPublisher events, EntityManager entityManager, Clock clock) {
        this.charges = charges;
        this.months = months;
        this.billingMonths = billingMonths;
        this.chargeTypes = chargeTypes;
        this.plots = plots;
        this.views = views;
        this.audit = audit;
        this.events = events;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    Charge reverse(UUID chargeId, ChargeReversal input, AccessProfile actor) {
        String reason = input.getReason() == null ? "" : input.getReason().strip();
        if (reason.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("reason", ProblemCodes.REQUIRED)));
        }
        ChargeRecord original = charges.findById(chargeId).orElseThrow(ApiProblemException::notFound);
        if (original.getKind() == ChargeKind.REVERSAL) {
            throw conflict(ProblemCodes.REVERSAL_NOT_REVERSIBLE, "A reversal cannot be reversed.");
        }
        if (original.getKind() == ChargeKind.ADJUSTMENT) {
            throw conflict(ProblemCodes.ADJUSTMENT_NOT_REVERSIBLE,
                    "An adjustment cannot be reversed; a later recalculation adjusts the original charge again.");
        }
        BillingMonthRecord month = months.findByIdForShare(original.getBillingMonthId()).orElseThrow();
        if (month.isClosed()) {
            throw conflict(ProblemCodes.CHARGE_IN_CLOSED_MONTH,
                    "The charge is booked in a closed month; it is corrected by an adjustment.");
        }
        if (charges.existsByReversesChargeId(chargeId)) {
            throw alreadyReversed();
        }
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        BillingMonthRecord booking = billingMonths.openForBooking(tenantId, month.yearMonth().atDay(1));
        ChargeRecord reversal = ChargeRecord.reversalOf(original, booking.getId(), reason,
                ChargeViews.plainCalculation(original.getAmount().negate()), actor.memberId(), clock.instant());
        try {
            entityManager.persist(reversal);
            entityManager.flush();
        } catch (PersistenceException e) {
            if (ConstraintViolations.violates(e, REVERSED_ONCE_INDEX)) {
                throw alreadyReversed();
            }
            throw e;
        }
        String plotNumber = plots.find(original.getPlotId()).orElseThrow().number();
        String chargeType = chargeTypes.find(original.getChargeTypeId()).orElseThrow().auditName();
        audit.publish(actor, LedgerAuditPublisher.Action.CHARGE_REVERSED, original.getPlotId(), plotNumber, List.of(
                new FieldChange("chargeType", null, chargeType),
                new FieldChange("amount", null, reversal.getAmount().toString()),
                new FieldChange("serviceFrom", null, reversal.getServiceFrom().toString()),
                new FieldChange("serviceTo", null, reversal.getServiceTo().toString()),
                new FieldChange("billingMonth", null, booking.yearMonth().toString()),
                new FieldChange("reason", null, reason)));
        events.publishEvent(new ChargesBooked(tenantId, Set.of(original.getPlotId())));
        return views.view(reversal, views.context(List.of(reversal), actor));
    }

    private static ApiProblemException alreadyReversed() {
        return conflict(ProblemCodes.CHARGE_ALREADY_REVERSED, "The charge has already been reversed.");
    }

    private static ApiProblemException conflict(String code, String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, detail);
    }
}
