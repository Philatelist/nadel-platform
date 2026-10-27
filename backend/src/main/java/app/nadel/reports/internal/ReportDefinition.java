package app.nadel.reports.internal;

import app.nadel.identity.Permission;
import java.util.List;
import java.util.Set;
import nadel.contract.model.ReportCode;

/**
 * A built-in report: its code, who may run it, its filters and how it loads its sheets. One Spring bean per report;
 * {@link ReportCatalog} collects them. A definition only reads, through the public read facades of the modules that
 * own the data, inside the runner's transaction, and adds no money rule: every amount is a {@code Money} from a facade
 * or a sum of such, never re-rounded. No definition depends on another.
 */
interface ReportDefinition {

    ReportCode code();

    /**
     * The permissions the caller needs, all of them: {@code REPORTS_VIEW}, and for a report with names and contacts
     * also {@code MEMBERS_VIEW}. A report the caller may not run is not listed and is 403.
     */
    default Set<Permission> requiredPermissions() {
        return Set.of(Permission.REPORTS_VIEW);
    }

    /** True when the report holds names and contacts of people (only {@code debtors}). */
    default boolean personalData() {
        return false;
    }

    /** The filters in the order the form shows them, the period first. */
    List<ReportFilterDefinition> filters();

    /**
     * Loads every row of every sheet. A facade that can return many rows is asked for at most
     * {@link ReportContext#rowLimit()} rows, so the runner can refuse a sheet over the cap.
     */
    ReportResult load(ReportFilters filters, ReportContext context);

    /**
     * The days of the file name {@code <code>_<from>_<to>.xlsx}; by default the period of the filters. A report made for
     * one day (plot balances: the as-of day; debtors: today) gives that day twice.
     */
    default ReportFilters.Period filenameRange(ReportFilters filters, ReportContext context) {
        return filters.period();
    }
}
