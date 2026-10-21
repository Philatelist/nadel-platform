package app.nadel.tariffs.internal;

import java.io.Serializable;
import java.util.UUID;

/** The primary key of {@link TariffScopePlotRecord}: a plot appears once in the scope of a tariff. */
record ScopePlotKey(UUID tariffId, UUID plotId) implements Serializable {
}
