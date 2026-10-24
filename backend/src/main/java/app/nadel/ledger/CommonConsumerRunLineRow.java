package app.nadel.ledger;

import app.nadel.meters.Quantity;
import app.nadel.money.Money;
import app.nadel.settings.CommonConsumerSplit;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A common consumer of a common consumers run as it was booked ({@link LedgerReadModel#commonConsumerRunsInRange},
 * money rules 6.7), with its run. The numbers are those stored with the run ({@code common_consumer_run_line}). Ids,
 * codes, the meter's name as the run showed it (history, user content), quantities and {@code Money}; no personal data.
 *
 * @param runId           the run
 * @param runFrom         the first day of the run
 * @param runTo           the last day of the run
 * @param runStatus       booked or reversed
 * @param reversedAt      when the whole run was reversed; null while booked
 * @param billingMonth    the billing month of the run's charges
 * @param runTotal        the amount the run charged in all, as booked
 * @param consumerMeterId the consumer: its chain of meters, by the first meter
 * @param consumerName    the name of the consumer's meter as the run showed it; null when it had none
 * @param position        the place of the consumer in the run
 * @param split           how its consumption was split, as booked
 * @param outcome         what the run did with the consumer
 * @param consumption     its consumption over the run's days, three decimals as stored for display; null when unknown
 * @param calculated      whether a value it was read from was interpolated
 * @param plotDays        the sum of the days of the plots that shared it
 * @param amount          the amount charged for it (zero unless charged)
 * @param plotsShared     the plots it was split among (those that could not be priced included)
 * @param plotsCharged    the distinct plots with a regular charge of the run for this consumer (a plot added to the run
 *                        later by a recalculation included)
 */
public record CommonConsumerRunLineRow(UUID runId, LocalDate runFrom, LocalDate runTo, RunStatus runStatus,
        @Nullable Instant reversedAt, YearMonth billingMonth, Money runTotal, UUID consumerMeterId,
        @Nullable String consumerName, int position, CommonConsumerSplit split, CommonConsumerRunLineOutcome outcome,
        @Nullable Quantity consumption, boolean calculated, long plotDays, Money amount, int plotsShared,
        int plotsCharged) {

    public CommonConsumerRunLineRow {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(runFrom, "runFrom");
        Objects.requireNonNull(runTo, "runTo");
        Objects.requireNonNull(runStatus, "runStatus");
        Objects.requireNonNull(billingMonth, "billingMonth");
        Objects.requireNonNull(runTotal, "runTotal");
        Objects.requireNonNull(consumerMeterId, "consumerMeterId");
        Objects.requireNonNull(split, "split");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(amount, "amount");
    }
}
