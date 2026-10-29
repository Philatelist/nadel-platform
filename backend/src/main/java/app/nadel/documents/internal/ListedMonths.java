package app.nadel.documents.internal;

import app.nadel.ledger.LedgerReadModel;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.time.Clock;
import java.time.YearMonth;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Which billing months the month endpoints of the documents answer for, as the billing month endpoints of the ledger
 * do (feature 011): the months from the cooperative's first billing month ({@link LedgerReadModel#firstBillingMonth})
 * to the current month in Europe/Kyiv. Runs in the caller's transaction.
 */
@Component
class ListedMonths {

    private final LedgerReadModel ledger;
    private final Clock clock;

    ListedMonths(LedgerReadModel ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    /** The current billing month: today in Europe/Kyiv. */
    YearMonth current() {
        return YearMonth.from(DocumentFormat.dayOf(clock.instant()));
    }

    /**
     * Refuses a month that is not listed: one after the current month is 409 {@code PERIOD_IN_FUTURE} when
     * {@code futureIsConflict} (a build, as the month run and the closing answer) and otherwise 404
     * {@code NOT_FOUND} (a read, as {@code GET /billing-months/{month}}); one before the first month, or any month
     * while the cooperative has none, is 404 {@code NOT_FOUND}.
     */
    void requireListed(YearMonth month, boolean futureIsConflict) {
        if (month.isAfter(current())) {
            if (futureIsConflict) {
                throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PERIOD_IN_FUTURE,
                        "The invoices of a month after the current one cannot be built.");
            }
            throw ApiProblemException.notFound();
        }
        Optional<YearMonth> first = ledger.firstBillingMonth();
        if (first.isEmpty() || month.isBefore(first.get())) {
            throw ApiProblemException.notFound();
        }
    }
}
