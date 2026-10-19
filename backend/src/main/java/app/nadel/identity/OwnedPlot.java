package app.nadel.identity;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A plot a member owns now, as {@code GET /me} lists it. {@code share} is a fraction in lowest terms such as "1/2".
 */
public record OwnedPlot(UUID plotId, String number, @Nullable String street, String share) {
}
