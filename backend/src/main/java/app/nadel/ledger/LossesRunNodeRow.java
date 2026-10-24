package app.nadel.ledger;

import app.nadel.meters.MeterKind;
import app.nadel.meters.Quantity;
import app.nadel.money.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A node of a proportional losses run as it was booked ({@link LedgerReadModel#lossesRunsInRange}, money rules 6.6),
 * with its run. The numbers are those stored with the run ({@code losses_run_node}), so a run booked under another
 * losses mode than today's still shows what it booked. Ids, codes, the meter's name as the run showed it (history,
 * user content), quantities and {@code Money}; no personal data.
 *
 * @param runId        the run
 * @param runFrom      the first day of the run
 * @param runTo        the last day of the run
 * @param runStatus    booked or reversed
 * @param reversedAt   when the whole run was reversed; null while booked
 * @param billingMonth the billing month of the run's charges
 * @param runTotal     the amount the run charged in all, as booked
 * @param nodeMeterId  the node: its chain of meters, by the first meter
 * @param nodeKind     substation or quarter meter
 * @param nodeName     the name of the node's meter as the run showed it; null when it had none
 * @param position     the place of the node in the run (substations first, then quarter meters)
 * @param outcome      what the run did with the node
 * @param consumption  the node's consumption over the run's days, three decimals as stored for display; null when
 *                     unknown
 * @param children     the sum of its children's consumption, as stored; null when unknown
 * @param losses       consumption minus children, as stored (negative for {@link LossesRunNodeOutcome#NEGATIVE}); null
 *                     when unknown
 * @param percent      the losses in percent of the consumption, two decimals as stored; null when unknown or the
 *                     consumption is zero
 * @param calculated   whether a value it was read from was interpolated
 * @param amount       the amount charged for the node's losses (zero unless charged)
 * @param plotsShared  the plots the node's losses were shared among (those that could not be priced included)
 * @param plotsCharged the distinct plots with a regular losses charge of the run for this node (a plot added to the run
 *                     later by a recalculation included)
 */
public record LossesRunNodeRow(UUID runId, LocalDate runFrom, LocalDate runTo, RunStatus runStatus,
        @Nullable Instant reversedAt, YearMonth billingMonth, Money runTotal, UUID nodeMeterId, MeterKind nodeKind,
        @Nullable String nodeName, int position, LossesRunNodeOutcome outcome, @Nullable Quantity consumption,
        @Nullable Quantity children, @Nullable Quantity losses, @Nullable BigDecimal percent, boolean calculated,
        Money amount, int plotsShared, int plotsCharged) {

    public LossesRunNodeRow {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(runFrom, "runFrom");
        Objects.requireNonNull(runTo, "runTo");
        Objects.requireNonNull(runStatus, "runStatus");
        Objects.requireNonNull(billingMonth, "billingMonth");
        Objects.requireNonNull(runTotal, "runTotal");
        Objects.requireNonNull(nodeMeterId, "nodeMeterId");
        Objects.requireNonNull(nodeKind, "nodeKind");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(amount, "amount");
    }
}
