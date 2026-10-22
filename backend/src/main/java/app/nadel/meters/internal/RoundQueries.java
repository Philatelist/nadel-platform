package app.nadel.meters.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.meters.MeterResource;
import java.time.LocalDate;
import java.util.List;
import nadel.contract.model.Meter;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reading round: the active meters to read, plot meters in street and number order (electricity before water),
 * then the other meters by name. A plot without an active meter has nothing to read and is never listed. The order and
 * the filters are those of the register ({@link MeterQueries}), so the two screens never disagree.
 */
@Service
class RoundQueries {

    private final MeterQueries meters;
    private final MeterAccess access;

    RoundQueries(MeterQueries meters, MeterAccess access) {
        this.meters = meters;
        this.access = access;
    }

    /**
     * @param noReadingSince when given, only the meters whose last counted reading (or initial reading) is dated before
     *                       that day: the meters not read for a while
     */
    @Transactional(readOnly = true)
    List<Meter> round(@Nullable MeterResource resource, @Nullable String street, @Nullable LocalDate noReadingSince,
            AccessProfile viewer) {
        return meters.list(resource, null, street, noReadingSince, false, access.seesEveryMeter(viewer));
    }
}
