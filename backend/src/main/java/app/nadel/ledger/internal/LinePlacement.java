package app.nadel.ledger.internal;

import app.nadel.ledger.internal.AllocationPlanner.Line;
import app.nadel.ledger.internal.AllocationPlanner.Violation;
import app.nadel.money.Money;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * The lines of one plot for an amount (a payment, a part of a split, a reallocated payment or a prepayment being
 * allocated) and what follows from them (feature 015 §2.2, §2.4): the proposal or the caller's lines, their problems
 * ({@link AllocationPlanner#validate}) and, for valid lines while automatic allocation is on, the automatic placement
 * of the remainder ({@link AllocationPlanner#completeWithRemainder}). Pure value; a preview shows the problems
 * (200), a save refuses them ({@link #requireValid}: 400 with the codes of the API).
 *
 * @param plotId     the plot
 * @param amount     the amount the lines place
 * @param lines      the proposal's lines or the caller's lines as sent (perhaps with problems)
 * @param violations the problems of the caller's lines; empty for the proposal
 * @param automatic  the automatic placement of the remainder; empty while there is a problem or it is switched off
 */
record LinePlacement(UUID plotId, Money amount, List<Line> lines, List<Violation> violations, List<Line> automatic) {

    LinePlacement {
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(amount, "amount");
        lines = List.copyOf(lines);
        violations = List.copyOf(violations);
        automatic = List.copyOf(automatic);
        if (!violations.isEmpty() && !automatic.isEmpty()) {
            throw new IllegalArgumentException("Lines with problems are never completed automatically");
        }
    }

    /**
     * The proposal of {@code amount} ({@code requested} null) or the caller's lines with their problems, without the
     * automatic placement ({@link #completed} adds it).
     */
    static LinePlacement of(UUID plotId, Money amount, @Nullable List<Line> requested,
            Collection<ChargeState> charges) {
        if (requested == null) {
            return new LinePlacement(plotId, amount, AllocationPlanner.propose(amount, charges).lines(), List.of(),
                    List.of());
        }
        return new LinePlacement(plotId, amount, requested, AllocationPlanner.validate(amount, requested, charges),
                List.of());
    }

    /** Whether the lines have no problem. */
    boolean valid() {
        return violations.isEmpty();
    }

    /**
     * This placement with the remainder placed automatically on the charges still open after the lines (only when the
     * lines are valid and {@code autoAllocate} is on; otherwise unchanged).
     */
    LinePlacement completed(boolean autoAllocate, Collection<ChargeState> charges) {
        if (!autoAllocate || !valid()) {
            return this;
        }
        return new LinePlacement(plotId, amount, lines, violations,
                AllocationPlanner.completeWithRemainder(lines, amount, charges));
    }

    /** The lines (above zero) and the automatic lines together: everything placed. */
    Money allocated() {
        Money total = Money.ZERO;
        for (Line line : lines) {
            if (line.amount().signum() > 0) {
                total = total.plus(line.amount());
            }
        }
        for (Line line : automatic) {
            total = total.plus(line.amount());
        }
        return total;
    }

    /** What the automatic lines place. */
    Money automaticTotal() {
        return automatic.stream().map(Line::amount).reduce(Money.ZERO, Money::plus);
    }

    /**
     * Refuses lines with problems, as a save answers them: 400 {@code VALIDATION_FAILED} for a charge that is not open
     * on the plot ({@code NOT_FOUND}), named twice ({@code DUPLICATE}) or a line not above zero
     * ({@code AMOUNT_NOT_POSITIVE}); otherwise 400 {@code ALLOCATION_EXCEEDS_PAYMENT} when the lines together exceed
     * the amount, then 400 {@code ALLOCATION_EXCEEDS_CHARGE} for the lines that exceed what is open on their charge.
     * The field names start with {@code prefix} ({@code ""} for one plot, {@code split.parts[i].} for a part of a
     * split).
     */
    void requireValid(String prefix) {
        if (violations.isEmpty()) {
            return;
        }
        List<FieldProblem> fields = new ArrayList<>();
        List<FieldProblem> exceedsCharge = new ArrayList<>();
        boolean exceedsPayment = false;
        for (Violation violation : violations) {
            Integer i = violation.line();
            switch (violation.problem()) {
                case ALLOCATION_EXCEEDS_PAYMENT -> exceedsPayment = true;
                case ALLOCATION_EXCEEDS_CHARGE -> exceedsCharge.add(new FieldProblem(
                        prefix + "lines[" + i + "].amount", ProblemCodes.ALLOCATION_EXCEEDS_CHARGE));
                case NOT_FOUND -> fields.add(new FieldProblem(prefix + "lines[" + i + "].chargeId",
                        ProblemCodes.NOT_FOUND));
                case DUPLICATE -> fields.add(new FieldProblem(prefix + "lines[" + i + "].chargeId",
                        ProblemCodes.DUPLICATE));
                case AMOUNT_NOT_POSITIVE -> fields.add(new FieldProblem(prefix + "lines[" + i + "].amount",
                        ProblemCodes.AMOUNT_NOT_POSITIVE));
            }
        }
        if (!fields.isEmpty()) {
            throw PaymentService.invalid(fields);
        }
        if (exceedsPayment) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.ALLOCATION_EXCEEDS_PAYMENT,
                    "More is allocated than the payment amount.",
                    List.of(new FieldProblem(prefix + "lines", ProblemCodes.ALLOCATION_EXCEEDS_PAYMENT)));
        }
        throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.ALLOCATION_EXCEEDS_CHARGE,
                "An amount exceeds what is open on the charge.", exceedsCharge);
    }
}
