package app.nadel.treasury.internal;

import app.nadel.audit.ActorNames;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.PaymentRef;
import app.nadel.treasury.internal.AccountBalances.Opening;
import app.nadel.treasury.internal.TreasuryQueries.MovementRow;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import nadel.contract.model.AccountMovement;
import nadel.contract.model.MovementKind;
import nadel.contract.model.MovementSource;
import nadel.contract.model.MovementSourceType;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The contract form of journal lines ({@code AccountMovement}), shared by the journal of an account
 * ({@link MoneyAccounts}) and the lines since the previous cash count ({@link CashCounts}): a member payment line with
 * its plot and receipt number or bank reference ({@link LedgerReadModel#paymentRefs}), a handover line with its deposit
 * slip number, an expense or other income line with its category, and the authors' current names
 * ({@link ActorNames}). Runs in the caller's transaction.
 */
@Component
class JournalLines {

    private final LedgerReadModel ledger;
    private final ActorNames actorNames;

    JournalLines(LedgerReadModel ledger, ActorNames actorNames) {
        this.ledger = ledger;
        this.actorNames = actorNames;
    }

    /** The lines in the given order; {@code opening} decides {@code beforeOpening}. */
    List<AccountMovement> items(List<MovementRow> rows, @Nullable Opening opening) {
        Set<UUID> paymentRows = rows.stream().map(MovementRow::paymentRowId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, PaymentRef> refs = paymentRows.isEmpty() ? Map.of() : ledger.paymentRefs(paymentRows);
        Set<UUID> authors = rows.stream().map(MovementRow::createdBy).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> names = authors.isEmpty() ? Map.of() : actorNames.displayNames(authors);
        List<AccountMovement> items = new ArrayList<>();
        for (MovementRow row : rows) {
            AccountMovement item = new AccountMovement(row.id(), row.movedOn(), MovementKind.fromValue(row.kind().name()),
                    row.amount().toString(), row.bookingMonth().toString(),
                    AccountBalances.beforeOpening(opening, row.movedOn()),
                    new MovementSource(MovementSourceType.fromValue(row.sourceType()), row.sourceId()), row.reversed(),
                    row.createdAt().atOffset(ZoneOffset.UTC));
            PaymentRef ref = row.paymentRowId() == null ? null : refs.get(row.paymentRowId());
            if (ref != null) {
                item.setPlotId(ref.plotId());
                item.setReceiptNumber(ref.receiptNumber());
                item.setDocumentNumber(ref.bankReference());
            } else {
                item.setDocumentNumber(row.documentNumber());
            }
            item.setCategoryId(row.categoryId());
            item.setReversesMovementId(row.reversesMovementId());
            item.setReason(row.reason());
            item.setCreatedByName(row.createdBy() == null ? null : names.get(row.createdBy()));
            items.add(item);
        }
        return items;
    }
}
