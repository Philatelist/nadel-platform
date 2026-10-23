package app.nadel.ledger.internal;

import app.nadel.tariffs.TariffVersionUsage;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger's answer to the tariffs port {@link TariffVersionUsage}: a version is used when a charge that is not
 * reversed has a {@code charge_part} priced by it (money rules section 4). After a reversal the version is free again;
 * its correction then changes no booked amount.
 * <p>
 * Race-free by the port's lock contract: the correction holds its tariff {@code FOR UPDATE} while it asks, and every
 * path that writes a charge from a version (the month run, a retry) holds the tariff {@code FOR SHARE}
 * ({@code TariffBook.lockShared}) until it commits, so the correction waits and then sees the charge. Runs in the
 * caller's transaction, under RLS.
 */
@Component
class LedgerTariffVersionUsage implements TariffVersionUsage {

    private final ChargePartRepository parts;

    LedgerTariffVersionUsage(ChargePartRepository parts) {
        this.parts = parts;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean isUsed(UUID tenantId, UUID versionId) {
        return parts.usedByUnreversedCharge(versionId);
    }
}
