package app.nadel.ledger.internal;

import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.ledger.internal.BillingMonthRepository.MonthStatus;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import nadel.contract.model.BillingMonthDetails;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closes a billing month ({@code POST /billing-months/{month}/close}, feature 011 §2.2). After closing nothing is
 * booked in the month: {@link BillingMonths} sends a charge about its days to the earliest open month after it. A
 * closed month is never reopened (the trigger {@code billing_month_guard} refuses any change of it).
 * <p>
 * The rules, in order: not after the current month in Europe/Kyiv (409 {@code PERIOD_IN_FUTURE}); listed, i.e. not
 * before the first month of the cooperative (404 {@code NOT_FOUND}, also while it has no month); a listed month without
 * a row is opened first; the month is locked {@code FOR UPDATE} (bookings into it hold {@code FOR SHARE}, so a closing
 * waits for them and they wait for it); already closed (409 {@code PERIOD_CLOSED}); every earlier listed month closed
 * (409 {@code PERIOD_CLOSE_ORDER}). The check list never blocks closing. Audit {@code BILLING_MONTH_CLOSED}.
 */
@Service
class MonthClosing {

    private final BillingMonthRepository months;
    private final BillingMonthQueries queries;
    private final LedgerAuditPublisher audit;
    private final Clock clock;

    MonthClosing(BillingMonthRepository months, BillingMonthQueries queries, LedgerAuditPublisher audit, Clock clock) {
        this.months = months;
        this.queries = queries;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    BillingMonthDetails close(YearMonth month, AccessProfile actor) {
        if (month.isAfter(YearMonth.now(clock))) {
            throw conflict(ProblemCodes.PERIOD_IN_FUTURE, "A month after the current one cannot be closed.");
        }
        List<MonthStatus> before = months.statesInOrder();
        if (before.isEmpty() || month.isBefore(YearMonth.from(before.getFirst().month()))) {
            throw ApiProblemException.notFound();
        }
        UUID tenantId = Objects.requireNonNull(actor.tenantId());
        months.insertIfAbsent(tenantId, month.atDay(1));
        String status = months.lockStatusForUpdate(month.atDay(1));
        if (BillingMonthRecord.Status.CLOSED.name().equals(status)) {
            throw conflict(ProblemCodes.PERIOD_CLOSED, "The month is closed.");
        }
        Map<YearMonth, MonthStatus> byMonth = months.statesInOrder().stream()
                .collect(Collectors.toMap(m -> YearMonth.from(m.month()), Function.identity()));
        YearMonth first = byMonth.keySet().stream().min(YearMonth::compareTo).orElseThrow();
        for (YearMonth earlier = first; earlier.isBefore(month); earlier = earlier.plusMonths(1)) {
            MonthStatus row = byMonth.get(earlier);
            if (row == null || row.status() != BillingMonthRecord.Status.CLOSED) {
                throw conflict(ProblemCodes.PERIOD_CLOSE_ORDER, "Close the earlier months first.");
            }
        }
        months.close(month.atDay(1), clock.instant(), actor.memberId());
        UUID monthId = byMonth.get(month).id();
        audit.publishMonth(actor, "BILLING_MONTH_CLOSED", monthId, month.toString(),
                List.of(new FieldChange("status", BillingMonthRecord.Status.OPEN.name(),
                        BillingMonthRecord.Status.CLOSED.name())));
        return queries.details(month);
    }

    private static ApiProblemException conflict(String code, String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, detail);
    }
}
