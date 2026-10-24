package app.nadel.ledger;

import app.nadel.meters.Quantity;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A tariff version behind a row of {@link LedgerReadModel#electricityCharged} (a {@code charge_part} row): the days it
 * counted for, the exact quantity priced at its rate and, for a proportional losses share, the tree node it carries.
 * There is no amount per part: the parts of a charge are added exactly and rounded once (money rules 6.5), so only the
 * charge has an amount.
 *
 * @param tariffVersionId the tariff version
 * @param days            the days of the charge it applied to
 * @param quantity        the exact kWh priced at {@code rate}; negative on a reversal row (the cancelled charge's part)
 * @param rate            the rate per kWh, four decimals
 * @param nodeMeterId     the node (substation or quarter meter chain) of a proportional losses share; null otherwise
 */
public record ChargedPart(UUID tariffVersionId, int days, Quantity quantity, BigDecimal rate,
        @Nullable UUID nodeMeterId) {

    public ChargedPart {
        Objects.requireNonNull(tariffVersionId, "tariffVersionId");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(rate, "rate");
    }
}
