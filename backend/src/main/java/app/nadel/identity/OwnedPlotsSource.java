package app.nadel.identity;

import java.util.List;
import java.util.UUID;

/**
 * SPI implemented by the module that owns plots. {@code identity} calls it to fill {@code GET /me} with the plots of
 * the signed-in member; this keeps the dependency one-way ({@code plots} depends on {@code identity}, never the
 * opposite). The caller runs inside the request of the member's cooperative.
 */
public interface OwnedPlotsSource {

    /**
     * The plots the member owns now (a record without an end date), in natural order of the plot number. Empty when
     * the member owns nothing.
     */
    List<OwnedPlot> currentPlotsOf(UUID memberId);
}
