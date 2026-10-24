package app.nadel.reports.internal;

import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.time.LocalDate;
import java.util.List;

/**
 * What a run of a report knows besides its filters.
 *
 * @param today       the day in Europe/Kyiv the report is made on
 * @param rowLimit    the row cap plus one: a facade that can return many rows is asked for at most this many, so the
 *                    runner sees that a sheet is over the cap without loading all of it
 * @param chargeTypes every charge type of the cooperative, archived ones included, in their configured order
 */
record ReportContext(LocalDate today, int rowLimit, List<ChargeTypeFacts> chargeTypes) {

    ReportContext {
        chargeTypes = List.copyOf(chargeTypes);
    }
}
