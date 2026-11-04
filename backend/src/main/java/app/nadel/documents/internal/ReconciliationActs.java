package app.nadel.documents.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.OwnedPlot;
import app.nadel.identity.OwnedPlotsSource;
import app.nadel.identity.Permission;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.PaymentSource;
import app.nadel.ledger.PlotDebts;
import app.nadel.ledger.PlotStatement;
import app.nadel.members.MemberDirectory;
import app.nadel.members.MemberRef;
import app.nadel.members.RoleHolders;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.plots.PlotOwners;
import app.nadel.plots.PlotOwners.OwnerShare;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.settings.CooperativeSettings;
import app.nadel.settings.Requisites;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import app.nadel.tenancy.TenantDirectory;
import app.nadel.tenancy.TenantSummary;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reconciliation act of a plot for a period ({@code GET /plots/{id}/reconciliation-act.pdf}, feature 018 §2.10,
 * technical considerations 2.8): reads the statement through {@link LedgerReadModel#plotStatement} (the opening and
 * closing balances are {@code debtsAsOf} on the day before the period and on its last day, as the plot balances report
 * shows them), names the plot, its current owners ({@link PlotOwners}, {@link MemberDirectory}), the charge types
 * (their Ukrainian default names, as on invoices) and the treasurer ({@link RoleHolders}: the first active holder of the
 * treasurer base role, in name order; without one the line stays empty), and draws it with
 * {@link ReconciliationActLayout}. Made on request, never stored, not audited (the 016 downloads rule).
 * <p>
 * The answers, in this order: 403 {@code PERMISSION_DENIED} for the platform operator (no cooperative data); 404
 * {@code PLOT_NOT_FOUND} for an unknown plot, one of another cooperative, and, for a caller without
 * {@code CHARGES_VIEW}, any plot they do not own now (as the plot account); 409 {@code PLOT_COMMON_LAND} for common
 * land (as a debt certificate; an archived plot gets its act, its history stays); 400 {@code VALIDATION_FAILED} with
 * {@code to INVALID} for a last day after today (Kyiv) and {@code from INVALID} for a first day after the last one,
 * before the plot's first ledger day ({@link LedgerReadModel#firstDebtDate}) or for a plot without any ledger row; 409
 * {@code REQUISITES_MISSING} as for invoices. Owners' and the treasurer's names are personal data: never logged.
 */
@Service
class ReconciliationActs {

    /** An act as a file. */
    record ActFile(String fileName, byte[] content) {
    }

    private final LedgerReadModel ledger;
    private final PlotCatalog plots;
    private final PlotOwners plotOwners;
    private final MemberDirectory members;
    private final RoleHolders roleHolders;
    private final ChargeTypeCatalog chargeTypes;
    private final CooperativeSettings settings;
    private final TenantDirectory tenants;
    private final OwnedPlotsSource ownedPlots;
    private final Clock clock;

    ReconciliationActs(LedgerReadModel ledger, PlotCatalog plots, PlotOwners plotOwners, MemberDirectory members,
            RoleHolders roleHolders, ChargeTypeCatalog chargeTypes, CooperativeSettings settings,
            TenantDirectory tenants, OwnedPlotsSource ownedPlots, Clock clock) {
        this.ledger = ledger;
        this.plots = plots;
        this.plotOwners = plotOwners;
        this.members = members;
        this.roleHolders = roleHolders;
        this.chargeTypes = chargeTypes;
        this.settings = settings;
        this.tenants = tenants;
        this.ownedPlots = ownedPlots;
        this.clock = clock;
    }

    /** The act of the plot for the period as the viewer may see it. */
    @Transactional(readOnly = true)
    ActFile act(UUID plotId, LocalDate from, LocalDate to, AccessProfile viewer) {
        if (viewer.platformOperator() || viewer.tenantId() == null) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, ProblemCodes.PERMISSION_DENIED,
                    "The platform operator has no access to cooperative data.");
        }
        if (!viewer.can(Permission.CHARGES_VIEW) && !ownsNow(viewer, plotId)) {
            throw plotNotFound();
        }
        return act(plotId, from, to);
    }

    /**
     * The act of the plot for the period for a caller that checked the access itself ({@code DocumentsFacade}): the
     * same answers as the endpoint after its access check.
     */
    @Transactional(readOnly = true)
    ActFile act(UUID plotId, LocalDate from, LocalDate to) {
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        PlotFacts plot = plots.find(plotId).orElseThrow(ReconciliationActs::plotNotFound);
        if (plot.commonLand()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PLOT_COMMON_LAND,
                    "Common land gets no reconciliation act.");
        }
        validatePeriod(plotId, from, to);
        ReceiptContent.Requisites requisites = requisites();
        PlotStatement statement = ledger.plotStatement(plotId, from, to);
        ReconciliationActLayout.Content content = new ReconciliationActLayout.Content(requisites, plot.number(),
                plot.street(), owners(plotId), from, to, balance(from.minusDays(1), statement.opening()),
                lines(statement), statement.charged(), statement.paid(), balance(to, statement.closing()),
                treasurer());
        return new ActFile(fileName(plot.number(), from, to), ReconciliationActLayout.render(content));
    }

    /** {@code first ≤ from ≤ to ≤ today} (Kyiv), else 400 with the field at fault. */
    private void validatePeriod(UUID plotId, LocalDate from, LocalDate to) {
        LocalDate today = DocumentFormat.dayOf(clock.instant());
        List<FieldProblem> problems = new ArrayList<>(2);
        Optional<LocalDate> first = ledger.firstDebtDate(plotId);
        if (from.isAfter(to) || first.isEmpty() || from.isBefore(first.get())) {
            problems.add(new FieldProblem("from", ProblemCodes.INVALID));
        }
        if (to.isAfter(today)) {
            problems.add(new FieldProblem("to", ProblemCodes.INVALID));
        }
        if (!problems.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", problems);
        }
    }

    /** The lines of the statement, named: the charge type and the service period, or the payment and its number. */
    private List<ReconciliationActLayout.Line> lines(PlotStatement statement) {
        DocumentTexts texts = DocumentTexts.uk();
        Map<UUID, ChargeTypeFacts> types = chargeTypes.all().stream()
                .collect(Collectors.toMap(ChargeTypeFacts::id, Function.identity()));
        return statement.lines().stream().map(line -> line.payment()
                ? new ReconciliationActLayout.Line(line.date(), paymentText(line, texts), null, line.paid())
                : new ReconciliationActLayout.Line(line.date(), chargeText(line, types, texts), line.charged(), null))
                .toList();
    }

    static String chargeText(PlotStatement.Line line, Map<UUID, ChargeTypeFacts> types, DocumentTexts texts) {
        ChargeTypeFacts type = line.chargeTypeId() == null ? null : types.get(line.chargeTypeId());
        String name = type == null ? "" : CashReceipts.typeName(type, texts);
        String period = line.serviceFrom() == null || line.serviceTo() == null ? ""
                : DocumentFormat.period(line.serviceFrom(), line.serviceTo());
        return texts.text("reconciliationAct.line." + line.kind().name(), Map.of("type", name, "period", period));
    }

    static String paymentText(PlotStatement.Line line, DocumentTexts texts) {
        PaymentSource source = Objects.requireNonNull(line.paymentSource(), "paymentSource");
        StringBuilder text = new StringBuilder(texts.text("reconciliationAct.line." + line.kind().name() + "."
                + source.name()));
        if (line.receiptNumber() != null) {
            text.append(texts.text("reconciliationAct.line.receipt", Map.of("number",
                    Long.toString(line.receiptNumber()))));
        }
        if (line.bankReference() != null && !line.bankReference().isBlank()) {
            text.append(texts.text("reconciliationAct.line.bankReference", Map.of("reference",
                    line.bankReference())));
        }
        return text.toString();
    }

    private static ReconciliationActLayout.Balance balance(LocalDate asOf, PlotDebts debts) {
        return new ReconciliationActLayout.Balance(asOf, debts.total(), debts.prepayment());
    }

    /** The current owners' full names, in the order of the plot's owners. */
    private List<String> owners(UUID plotId) {
        List<OwnerShare> owners = plotOwners.currentOwnerShares(List.of(plotId)).getOrDefault(plotId, List.of());
        Map<UUID, MemberRef> names = members.find(owners.stream().map(OwnerShare::memberId).toList());
        return owners.stream().map(owner -> names.get(owner.memberId())).filter(Objects::nonNull)
                .map(InvoiceBuilder::fullName).toList();
    }

    private @Nullable String treasurer() {
        List<MemberRef> holders = roleHolders.activeHolders("TREASURER");
        return holders.isEmpty() ? null : InvoiceBuilder.fullName(holders.getFirst());
    }

    /** The requisites printed on top; refuses a cooperative without the IBAN or the ЄДРПОУ, as an invoice does. */
    private ReceiptContent.Requisites requisites() {
        Requisites requisites = settings.requisites();
        TenantSummary tenant = tenants.current().orElseThrow(() -> new IllegalStateException("No cooperative"));
        List<String> missing = RequisitesCheck.missing(requisites, tenant.edrpou());
        if (!missing.isEmpty()) {
            throw ApiProblemException.requisitesMissing(missing);
        }
        String name = requisites.officialName() == null || requisites.officialName().isBlank() ? tenant.name()
                : requisites.officialName();
        return new ReceiptContent.Requisites(name, tenant.edrpou(), requisites.legalAddress(), requisites.iban(),
                requisites.bankName());
    }

    /** Whether the viewer is a member who owns the plot today (any share). */
    private boolean ownsNow(AccessProfile viewer, UUID plotId) {
        UUID memberId = viewer.memberId();
        return memberId != null
                && ownedPlots.currentPlotsOf(memberId).stream().map(OwnedPlot::plotId).anyMatch(plotId::equals);
    }

    /** {@code reconciliation_act_plot-9_2026-09-01_2026-10-31.pdf}: ASCII only. */
    static String fileName(String plotNumber, LocalDate from, LocalDate to) {
        return "reconciliation_act_plot-" + plotNumber.replaceAll("[^0-9A-Za-z]+", "-") + "_" + from + "_" + to
                + ".pdf";
    }

    private static ApiProblemException plotNotFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, ProblemCodes.PLOT_NOT_FOUND, "The plot was not found.");
    }
}
