package app.nadel.notifications.internal;

import app.nadel.documents.DocumentsFacade;
import app.nadel.documents.InvoiceSummary;
import app.nadel.members.MemberDirectory;
import app.nadel.members.MemberRef;
import app.nadel.notifications.internal.DeliveryRows.Count;
import app.nadel.notifications.internal.DeliveryRows.Filter;
import app.nadel.notifications.internal.DeliveryRows.Listed;
import app.nadel.notifications.internal.DeliveryRows.State;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import app.nadel.tenancy.TenantContext;
import java.time.Clock;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The delivery screen (feature 017 §2.4): the list with its counts, «Надіслати ще раз» and «Роздруковано». The caller
 * holds {@code INVOICES_ISSUE} (checked on the controller); the cooperative is the caller's (RLS). Single sends and
 * the print mark are not audited (feature 017 §2.7: they are on this screen).
 */
@Service
class DeliveryRegister {

    /** One row with the plot of its invoice and the member's name. */
    record Row(Listed delivery, @Nullable String plotNumber, @Nullable String memberName) {
    }

    /** A page of rows, how many match, and the counts per channel and status. */
    record Page(List<Row> rows, long total, List<Count> summary) {
    }

    private final DeliveryRows rows;
    private final DocumentsFacade documents;
    private final MemberDirectory members;
    private final DeliveryQueue queue;
    private final Clock clock;

    DeliveryRegister(DeliveryRows rows, DocumentsFacade documents, MemberDirectory members, DeliveryQueue queue,
            Clock clock) {
        this.rows = rows;
        this.documents = documents;
        this.members = members;
        this.queue = queue;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    Page list(Filter filter, int page, int size) {
        List<Listed> listed = rows.list(filter, page, size);
        Map<UUID, String> plots = new HashMap<>();
        listed.stream().map(Listed::invoiceId).filter(Objects::nonNull).distinct().forEach(invoice ->
                documents.invoiceSummary(invoice).map(InvoiceSummary::plotNumber)
                        .ifPresent(number -> plots.put(invoice, number)));
        Map<UUID, MemberRef> names = members.find(listed.stream().map(Listed::memberId).distinct().toList());
        List<Row> result = listed.stream().map(row -> new Row(row,
                row.invoiceId() == null ? null : plots.get(row.invoiceId()),
                names.containsKey(row.memberId()) ? names.get(row.memberId()).shortName() : null)).toList();
        return new Page(result, rows.count(filter), rows.summary(filter));
    }

    /**
     * Queues a sent or failed e-mail or Telegram delivery again; 404 for an unknown id, 409
     * {@code DELIVERY_NOT_RETRYABLE} for paper or a row still queued.
     */
    @Transactional
    void retry(UUID id) {
        State state = rows.lock(id).orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                ProblemCodes.NOT_FOUND, "The delivery does not exist."));
        if (state.channel() == Channel.PAPER
                || !(DeliveryRows.FAILED.equals(state.status()) || DeliveryRows.SENT.equals(state.status()))) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.DELIVERY_NOT_RETRYABLE,
                    "Only a sent or failed e-mail or Telegram delivery can be sent again.");
        }
        rows.requeue(id, clock.instant());
        UUID tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("No cooperative is bound"));
        queue.wakeAfterCommit(tenantId, state.channel());
    }

    /** «Роздруковано»: the month's invoice copies to print become printed; how many. */
    @Transactional
    int printed(YearMonth month) {
        return rows.printed(month);
    }
}
