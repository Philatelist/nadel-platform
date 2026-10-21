package app.nadel.tariffs.internal;

import app.nadel.tariffs.TariffVersionUsage;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * No charge exists in this feature (charges arrive with feature 011), so no version is used and every version can be
 * corrected or deleted. {@code TariffCommands} asks every {@link TariffVersionUsage} bean, so the ledger adds its own
 * without replacing this one.
 */
@Component
class NoChargesYetUsage implements TariffVersionUsage {

    @Override
    public boolean isUsed(UUID tenantId, UUID versionId) {
        return false;
    }
}
