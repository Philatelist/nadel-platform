package app.nadel.reports.internal;

import app.nadel.identity.Permission;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.PlotDebts;
import app.nadel.members.MemberContacts;
import app.nadel.members.MemberDirectory;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.plots.PlotOwners;
import app.nadel.reports.internal.ReportResult.Cell;
import app.nadel.reports.internal.ReportResult.Column;
import app.nadel.reports.internal.ReportResult.Note;
import app.nadel.reports.internal.ReportResult.Row;
import app.nadel.reports.internal.ReportResult.Sheet;
import app.nadel.reports.internal.ReportResult.Total;
import app.nadel.settings.CooperativeSettings;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import nadel.contract.model.ReportCode;
import nadel.contract.model.ReportColumnType;
import nadel.contract.model.ReportNoteCode;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * Debtors ({@code debtors}, functional spec 2.5, feature 013 §3.3, feature 015 §6): the plots whose debt today, after
 * what payments pay on their charges, is over an amount or older than a number of days, with the current owners' names
 * and contacts, so the treasurer can call or write to them. A plot whose debt is fully paid is not listed.
 * It holds personal data: it needs {@code MEMBERS_VIEW} besides {@code REPORTS_VIEW} (the catalog hides it and the
 * endpoints answer 403 without it; {@link #load} checks it again), its audit entry says {@code personalData=true}, and
 * nothing here logs a name, phone or e-mail or puts one in an exception or the audit entry.
 * <p>
 * Filters: {@code minDebt} (default the cooperative's red amount) and {@code olderThanDays} (default its red days),
 * combined with OR as in the red status ({@link #matches}), charge types (the debt and the age count only those) and
 * street. The day is today (Kyiv); the file name carries it.
 * <p>
 * Sheet {@code main}, one row per matching active plot that is not common land, natural order of the number:
 * {@code number}, {@code street}, {@code owners} (the full names of the current owners, the earliest owner first,
 * separated by {@code "; "}), {@code phones} (per owner its phones joined by {@code ", "}, the owners in the same order
 * separated by {@code "; "}, an owner without a phone left out), {@code emails} (the owners' e-mail addresses in the
 * same order separated by {@code "; "}, an owner without one left out), one debt column per charge type
 * ({@link DebtColumns}, family {@code debt}), {@code total}, {@code oldestUnpaid} and {@code daysOverdue} (the oldest
 * charge that still has a debt after payments); the totals {@code all} sum the
 * money columns of the rows listed. A plot without a current owner that matches is listed with empty owner cells: its
 * debt is still owed.
 */
@Component
class DebtorsReport implements ReportDefinition {

    static final String MAIN = "main";

    private final PlotCatalog plots;
    private final PlotOwners owners;
    private final MemberDirectory members;
    private final LedgerReadModel ledger;
    private final List<ReportFilterDefinition> filters;

    DebtorsReport(PlotCatalog plots, PlotOwners owners, MemberDirectory members, LedgerReadModel ledger,
            CooperativeSettings settings) {
        this.plots = plots;
        this.owners = owners;
        this.members = members;
        this.ledger = ledger;
        this.filters = List.of(new MinDebtFilter(settings::redAmount), new OlderThanDaysFilter(settings::redDays),
                new ChargeTypesFilter(), new StreetFilter());
    }

    @Override
    public ReportCode code() {
        return ReportCode.DEBTORS;
    }

    @Override
    public Set<Permission> requiredPermissions() {
        return Set.of(Permission.REPORTS_VIEW, Permission.MEMBERS_VIEW);
    }

    @Override
    public boolean personalData() {
        return true;
    }

    @Override
    public List<ReportFilterDefinition> filters() {
        return filters;
    }

    /** Defence in depth: the runner has already refused a caller without {@code MEMBERS_VIEW} (403). */
    @Override
    @PreAuthorize("hasAuthority('PERM_REPORTS_VIEW') and hasAuthority('PERM_MEMBERS_VIEW')")
    public ReportResult load(ReportFilters filters, ReportContext context) {
        List<PlotFacts> active = plots.findActive(filters.street());
        Map<UUID, PlotDebts> debts = ledger.debtsAsOf(context.today(), new LinkedHashSet<>(filters.chargeTypeIds()),
                active.stream().map(PlotFacts::id).toList());
        List<PlotFacts> matching = active.stream()
                .filter(plot -> matches(debts.get(plot.id()), filters.minDebt(), filters.olderThanDays())).toList();
        Map<UUID, List<UUID>> ownerIds = owners.currentOwnerIds(matching.stream().map(PlotFacts::id).toList());
        Map<UUID, MemberContacts> contacts = members.contacts(ownerIds.values().stream().flatMap(List::stream)
                .distinct().toList());
        List<ChargeTypeFacts> types = DebtColumns.types(filters, context,
                matching.stream().map(plot -> debts.get(plot.id())).toList());

        List<Column> columns = new ArrayList<>();
        columns.add(Column.of("number", ReportColumnType.TEXT));
        columns.add(Column.of("street", ReportColumnType.TEXT));
        columns.add(Column.of("owners", ReportColumnType.TEXT));
        columns.add(Column.of("phones", ReportColumnType.TEXT));
        columns.add(Column.of("emails", ReportColumnType.TEXT));
        columns.addAll(DebtColumns.columns(types, List.of(DebtColumns.Family.DEBT)));
        columns.add(Column.of("total", ReportColumnType.MONEY));
        columns.add(Column.of("oldestUnpaid", ReportColumnType.DATE));
        columns.add(Column.of("daysOverdue", ReportColumnType.INTEGER));

        List<Row> rows = new ArrayList<>();
        for (PlotFacts plot : matching) {
            PlotDebts debt = debts.get(plot.id());
            List<MemberContacts> people = ownerIds.getOrDefault(plot.id(), List.of()).stream().map(contacts::get)
                    .filter(Objects::nonNull).toList();
            List<Cell> cells = new ArrayList<>();
            cells.add(Cell.of(plot.number()));
            cells.add(Cell.of(plot.street()));
            cells.add(joined(people, MemberContacts::fullName));
            cells.add(joined(people, person -> person.phones().isEmpty() ? null : String.join(", ", person.phones())));
            cells.add(joined(people, MemberContacts::email));
            cells.addAll(DebtColumns.cells(types, List.of(DebtColumns.Family.DEBT), debt));
            cells.add(Cell.of(debt.total()));
            cells.add(Cell.of(debt.oldestUnpaid()));
            cells.add(Cell.of(debt.daysOverdue()));
            rows.add(Row.detail(cells));
        }
        return new ReportResult(List.of(new Sheet(MAIN, columns, rows,
                List.of(new Total("all", DebtColumns.totals(columns, rows))))),
                List.of(new Note(ReportNoteCode.OLDEST_UNPAID_IS_OLDEST_CHARGE, Map.of())));
    }

    @Override
    public ReportFilters.Period filenameRange(ReportFilters filters, ReportContext context) {
        return new ReportFilters.Period(context.today(), context.today());
    }

    /**
     * Whether a plot is a debtor: the red rule of the map (money rules 9, {@code plots.internal.PlotStatusPolicy}) with
     * the filter's amount and days in place of the cooperative's thresholds. No debt (zero or less) never matches;
     * otherwise the debt is above {@code minDebt}, or its oldest unpaid charge is more than {@code olderThanDays} days
     * old. With the defaults and every charge type these are exactly the red plots that have an owner, plus the
     * ownerless plots that would be red.
     */
    static boolean matches(@Nullable PlotDebts debt, Money minDebt, int olderThanDays) {
        if (debt == null || debt.total().signum() <= 0) {
            return false;
        }
        boolean aboveAmount = debt.total().minus(minDebt).signum() > 0;
        boolean tooOld = debt.daysOverdue() != null && debt.daysOverdue() > olderThanDays;
        return aboveAmount || tooOld;
    }

    /** The owners' values in the owners' order, separated by {@code "; "}; an owner without a value is left out. */
    private static Cell joined(List<MemberContacts> people, Function<MemberContacts, @Nullable String> value) {
        String text = people.stream().map(value).filter(Objects::nonNull).filter(part -> !part.isBlank())
                .collect(Collectors.joining("; "));
        return Cell.of(text.isEmpty() ? null : text);
    }
}
