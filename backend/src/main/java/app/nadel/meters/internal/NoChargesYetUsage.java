package app.nadel.meters.internal;

import app.nadel.meters.ReadingUsage;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * No charge exists in this feature (charges arrive with feature 011), so no reading is used and every reading can be
 * corrected or deleted. The reading commands ask every {@link ReadingUsage} bean, so the ledger adds its own without
 * replacing this one.
 */
@Component("noChargesYetReadingUsage") // the tariffs module has a bean of the same class name
class NoChargesYetUsage implements ReadingUsage {

    @Override
    public boolean isUsed(UUID tenantId, UUID meterId, LocalDate from, LocalDate to) {
        return false;
    }
}
