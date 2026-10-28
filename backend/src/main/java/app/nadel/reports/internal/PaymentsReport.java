package app.nadel.reports.internal;

import app.nadel.identity.CurrentUser;
import app.nadel.identity.Permission;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.PaymentFilter;
import app.nadel.ledger.PaymentNotes;
import app.nadel.ledger.PaymentRow;
import app.nadel.ledger.PaymentSource;
import app.nadel.members.MemberDirectory;
import app.nadel.members.MemberRef;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.reports.internal.ReportResult.Cell;
import app.nadel.reports.internal.ReportResult.Column;
import app.nadel.reports.internal.ReportResult.Row;
import app.nadel.reports.internal.ReportResult.Sheet;
import app.nadel.reports.internal.ReportResult.Total;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import nadel.contract.model.ReportCode;
import nadel.contract.model.ReportColumnType;
import nadel.contract.model.ReportPeriodBasis;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * Payments ({@code payments}, functional spec 2.11, feature 015 §6): every payment of a range of billing months, or of
 * a range of days received, one row per payment, with totals per source. It needs {@code PAYMENTS_VIEW} besides
 * {@code REPORTS_VIEW} (the catalog hides it and the endpoints answer 403 without it; {@link #load} checks it again).
 * It names payers, so it holds personal data (its audit entry says {@code personalData=true}); the payer note, read
 * through {@code ledger.PaymentNotes}, is a column only for a caller with {@code MEMBERS_VIEW}. Nothing here logs a
 * name or a note.
 * <p>
 * Filters: billing months (default the previous month), the days received (optional; given, they choose the payments
 * instead of the months), source (default both) and street.
 * <p>
 * Sheet {@code main}, one row per payment ({@code LedgerReadModel.payments}: by the day received, then the time booked;
 * a reversed payment is listed once and marked): {@code receivedOn}, {@code billingMonth}, {@code source},
 * {@code document} (the receipt number of a cash payment, the bank document number of a bank payment as entered),
 * {@code number} and {@code street} of the plot, {@code payer} (the member's short name, "Last First"; empty when no
 * payer was named), {@code payerNote} (only with {@code MEMBERS_VIEW}), {@code amount}, {@code allocated} (what it pays
 * on charges now), {@code prepayment} (what is left of it) and {@code reversed}. Totals: one line per source asked
 * ({@code CASH}, {@code BANK}) and {@code all}, each the sum of the money columns of the payments of that source that
 * are not reversed: a reversed payment is listed but brought no money.
 */
@Component
class PaymentsReport implements ReportDefinition {

    static final String MAIN = "main";

    /** The catalog of the {@code source} column's codes. */
    static final String SOURCES = "reports.filters.options.source";

    private static final List<ReportFilterDefinition> FILTERS = List.of(
            MonthRangeFilter.previousMonth(ReportPeriodBasis.BILLING_MONTHS), new PaymentDaysFilter(),
            new SourceFilter(), new StreetFilter());

    private final LedgerReadModel ledger;
    private final PaymentNotes notes;
    private final PlotCatalog plots;
    private final MemberDirectory members;
    private final CurrentUser currentUser;

    PaymentsReport(LedgerReadModel ledger, PaymentNotes notes, PlotCatalog plots, MemberDirectory members,
            CurrentUser currentUser) {
        this.ledger = ledger;
        this.notes = notes;
        this.plots = plots;
        this.members = members;
        this.currentUser = currentUser;
    }

    @Override
    public ReportCode code() {
        return ReportCode.PAYMENTS;
    }

    @Override
    public Set<Permission> requiredPermissions() {
        return Set.of(Permission.REPORTS_VIEW, Permission.PAYMENTS_VIEW);
    }

    @Override
    public boolean personalData() {
        return true;
    }

    @Override
    public List<ReportFilterDefinition> filters() {
        return FILTERS;
    }

    /** Defence in depth: the runner has already refused a caller without {@code PAYMENTS_VIEW} (403). */
    @Override
    @PreAuthorize("hasAuthority('PERM_REPORTS_VIEW') and hasAuthority('PERM_PAYMENTS_VIEW')")
    public ReportResult load(ReportFilters filters, ReportContext context) {
        return new ReportResult(List.of(sheet(filters, context, true, currentUser.can(Permission.MEMBERS_VIEW))),
                List.of());
    }

    /**
     * The sheet {@code main} for the filters: the report's, and the template row set {@code payments}
     * ({@link TemplateValues}), which never names the payer.
     *
     * @param withPayer     whether the {@code payer} column is there
     * @param withPayerNote whether the {@code payerNote} column is there (only for a caller with {@code MEMBERS_VIEW})
     */
    Sheet sheet(ReportFilters filters, ReportContext context, boolean withPayer, boolean withPayerNote) {
        PaymentFilter filter = (filters.hasDays() ? PaymentFilter.days(filters.from(), filters.to())
                : PaymentFilter.months(filters.fromMonth(), filters.toMonth()))
                .withSource(filters.source())
                .withPlots(filters.street() == null ? null : Set.copyOf(plots.idsOnStreet(filters.street())));
        List<PaymentRow> payments = ledger.payments(filter, context.rowLimit());
        Map<UUID, PlotFacts> plotFacts = plots.findAll(payments.stream().map(PaymentRow::plotId).distinct()
                .toList());
        Map<UUID, MemberRef> payers = withPayer ? members.find(payments.stream().map(PaymentRow::payerMemberId)
                .filter(Objects::nonNull).distinct().toList()) : Map.of();
        Map<UUID, String> payerNotes = withPayerNote
                ? notes.notes(payments.stream().map(PaymentRow::paymentId).toList()) : Map.of();

        List<Column> columns = new ArrayList<>();
        columns.add(Column.of("receivedOn", ReportColumnType.DATE));
        columns.add(Column.of("billingMonth", ReportColumnType.MONTH));
        columns.add(Column.codes("source", SOURCES));
        columns.add(Column.of("document", ReportColumnType.TEXT));
        columns.add(Column.of("number", ReportColumnType.TEXT));
        columns.add(Column.of("street", ReportColumnType.TEXT));
        if (withPayer) {
            columns.add(Column.of("payer", ReportColumnType.TEXT));
        }
        if (withPayerNote) {
            columns.add(Column.of("payerNote", ReportColumnType.TEXT));
        }
        columns.add(Column.of("amount", ReportColumnType.MONEY));
        columns.add(Column.of("allocated", ReportColumnType.MONEY));
        columns.add(Column.of("prepayment", ReportColumnType.MONEY));
        columns.add(Column.of("reversed", ReportColumnType.BOOLEAN));

        List<Row> rows = new ArrayList<>();
        for (PaymentRow payment : payments) {
            PlotFacts plot = plotFacts.get(payment.plotId());
            List<Cell> cells = new ArrayList<>();
            cells.add(Cell.of(payment.receivedOn()));
            cells.add(Cell.of(payment.billingMonth()));
            cells.add(Cell.of(payment.source().name()));
            cells.add(Cell.of(document(payment)));
            cells.add(Cell.of(plot == null ? null : plot.number()));
            cells.add(Cell.of(plot == null ? null : plot.street()));
            if (withPayer) {
                MemberRef payer = payment.payerMemberId() == null ? null : payers.get(payment.payerMemberId());
                cells.add(Cell.of(payer == null ? null : payer.shortName()));
            }
            if (withPayerNote) {
                cells.add(Cell.of(payerNotes.get(payment.paymentId())));
            }
            cells.add(Cell.of(payment.amount()));
            cells.add(Cell.of(payment.allocated()));
            cells.add(Cell.of(payment.remaining()));
            cells.add(Cell.of(payment.reversed()));
            rows.add(Row.detail(cells));
        }

        List<Total> totals = new ArrayList<>();
        List<PaymentSource> sources = filters.source() == null ? List.of(PaymentSource.values())
                : List.of(filters.source());
        for (PaymentSource source : sources) {
            totals.add(new Total(source.name(), DebtColumns.totals(columns, counted(payments, rows, source))));
        }
        totals.add(new Total("all", DebtColumns.totals(columns, counted(payments, rows, null))));
        return new Sheet(MAIN, columns, rows, totals);
    }

    /** The receipt number of a cash payment, the bank document number of a bank payment (null when none). */
    private static @Nullable String document(PaymentRow payment) {
        return payment.receiptNumber() != null ? payment.receiptNumber().toString() : payment.bankReference();
    }

    /** The rows of the payments that are not reversed, of the source (null: every source). */
    private static List<Row> counted(List<PaymentRow> payments, List<Row> rows, @Nullable PaymentSource source) {
        List<Row> counted = new ArrayList<>();
        for (int index = 0; index < payments.size(); index++) {
            PaymentRow payment = payments.get(index);
            if (!payment.reversed() && (source == null || payment.source() == source)) {
                counted.add(rows.get(index));
            }
        }
        return counted;
    }
}
