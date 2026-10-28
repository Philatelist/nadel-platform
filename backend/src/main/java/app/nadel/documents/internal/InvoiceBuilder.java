package app.nadel.documents.internal;

import app.nadel.documents.internal.nbu.NbuQrPayload;
import app.nadel.identity.AccessProfile;
import app.nadel.ledger.InvoiceLedger;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.MonthStatus;
import app.nadel.members.MemberDirectory;
import app.nadel.members.MemberRef;
import app.nadel.members.RoleHolders;
import app.nadel.money.Money;
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
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds and stores invoices (feature 016 §2.5, §2.6, technical considerations 2.5). Slice 2: the invoice of one plot
 * on request ({@code POST /plots/{id}/invoices}, {@link #forPlot}); the month build comes with slice 3.
 * <p>
 * <b>Consistency.</b> One build per cooperative and month at a time: the transaction-scoped advisory lock
 * {@code documents.invoices:{tenant}:{YYYY-MM}} is taken first, in an outer transaction, and held until the build has
 * committed. The build itself runs in a new {@code REPEATABLE READ} transaction (through the tenant-aware transaction
 * manager, which sets the cooperative first) that starts only once the lock is held, so its one snapshot of the
 * database already sees whatever an earlier build of the month committed: the next version number is right, and every
 * figure of the build comes from that one snapshot (a payment or a charge committed meanwhile is in none of it).
 * {@code asOf} is read once, after the snapshot began: the later of the application clock and the database clock, so
 * every row committed before the snapshot counts whichever clock wrote its {@code created_at} (they may drift apart by
 * milliseconds); only ledger rows created at or before it count.
 * <p>
 * <b>On request</b> (always a new version, also when nothing changed; audit {@code INVOICE_REISSUED}): 400
 * {@code VALIDATION_FAILED} on {@code month} for a month after the current one (Kyiv); 404 {@code PLOT_NOT_FOUND} for
 * an unknown plot or one of another cooperative; 409 {@code REQUISITES_MISSING} with the missing {@code IBAN} /
 * {@code EDRPOU}; 409 {@code PLOT_NOT_INVOICEABLE} for common land, an archived plot or a plot without a current
 * owner. Nothing is stored unless every check passes. The caller holds
 * {@code INVOICES_ISSUE} (checked on the endpoint).
 */
@Service
class InvoiceBuilder {

    static final String LOCK = "documents.invoices:";

    /** The invoice version just stored. */
    record Issued(UUID id, UUID plotId, String plotNumber, YearMonth month, String number, int version, Money due,
            Instant issuedAt) {
    }

    /**
     * What every invoice of a build shares: the printed requisites, the names of the chairmen and the treasurers, the
     * charge types in their order, the ЄДРПОУ and the IBAN for the QR code.
     */
    record Context(InvoiceDocument.Cooperative cooperative, List<String> chairmen, List<String> treasurers,
            List<ChargeTypeFacts> types) {
    }

    private final EntityManager entityManager;
    private final TransactionTemplate repeatableRead;
    private final LedgerReadModel ledger;
    private final PlotCatalog plots;
    private final PlotOwners plotOwners;
    private final MemberDirectory members;
    private final RoleHolders roleHolders;
    private final ChargeTypeCatalog chargeTypes;
    private final CooperativeSettings settings;
    private final TenantDirectory tenants;
    private final InvoiceSnapshots snapshots;
    private final InvoiceRepository invoices;
    private final InvoiceAuditPublisher audit;
    private final Clock clock;

    InvoiceBuilder(EntityManager entityManager, PlatformTransactionManager transactionManager, LedgerReadModel ledger,
            PlotCatalog plots, PlotOwners plotOwners, MemberDirectory members, RoleHolders roleHolders,
            ChargeTypeCatalog chargeTypes, CooperativeSettings settings, TenantDirectory tenants,
            InvoiceSnapshots snapshots, InvoiceRepository invoices, InvoiceAuditPublisher audit, Clock clock) {
        this.entityManager = entityManager;
        this.repeatableRead = new TransactionTemplate(transactionManager);
        this.repeatableRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.ledger = ledger;
        this.plots = plots;
        this.plotOwners = plotOwners;
        this.members = members;
        this.roleHolders = roleHolders;
        this.chargeTypes = chargeTypes;
        this.settings = settings;
        this.tenants = tenants;
        this.snapshots = snapshots;
        this.invoices = invoices;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Issues the invoice of the plot for the month (the current month when null) as of now, as a new version.
     *
     * @param issuer the caller, who holds {@code INVOICES_ISSUE}
     */
    @Transactional
    Issued forPlot(UUID plotId, @Nullable YearMonth month, AccessProfile issuer) {
        Objects.requireNonNull(plotId, "plotId");
        UUID tenantId = Objects.requireNonNull(issuer.tenantId(), "tenantId");
        YearMonth current = YearMonth.from(DocumentFormat.dayOf(clock.instant()));
        YearMonth target = month == null ? current : month;
        if (target.isAfter(current)) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("month", ProblemCodes.INVALID)));
        }
        lockMonth(tenantId, target);
        return Objects.requireNonNull(repeatableRead.execute(status -> issue(tenantId, plotId, target, issuer)));
    }

    /** The build of one plot inside the {@code REPEATABLE READ} transaction. */
    private Issued issue(UUID tenantId, UUID plotId, YearMonth month, AccessProfile issuer) {
        PlotFacts plot = plots.find(plotId).orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                ProblemCodes.PLOT_NOT_FOUND, "The plot was not found."));
        Context context = context();
        List<OwnerShare> owners = plotOwners.currentOwnerShares(List.of(plotId)).getOrDefault(plotId, List.of());
        if (plot.commonLand() || plot.archived() || owners.isEmpty()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PLOT_NOT_INVOICEABLE,
                    "Common land, an archived plot and a plot without a current owner get no invoice.");
        }
        Instant asOf = asOf();
        InvoiceLedger plotLedger = ledger.invoiceLedgers(List.of(plotId), month, asOf).get(plotId);
        int version = invoices.latestVersion(plotId, month.atDay(1)) + 1;
        InvoiceDocument document = document(UUID.randomUUID(), version, plot, owners, ownerNames(owners), plotLedger,
                context, asOf);
        InvoiceSnapshots.Sealed sealed = snapshots.seal(tenantId, document);
        entityManager.persist(InvoiceRecord.issue(document.id(), tenantId, document, sealed, issuer.memberId()));
        entityManager.flush();
        audit.reissued(issuer, document, asOf);
        return new Issued(document.id(), plotId, plot.number(), month, document.number(), version,
                document.totals().due(), document.issuedAt());
    }

    /**
     * What every invoice of a build shares; refuses a cooperative without the IBAN or the ЄДРПОУ (409
     * {@code REQUISITES_MISSING}).
     */
    Context context() {
        Requisites requisites = settings.requisites();
        TenantSummary tenant = tenants.current().orElseThrow(() -> new IllegalStateException("No cooperative"));
        List<String> missing = RequisitesCheck.missing(requisites, tenant.edrpou());
        if (!missing.isEmpty()) {
            throw ApiProblemException.requisitesMissing(missing);
        }
        String name = requisites.officialName() == null || requisites.officialName().isBlank() ? tenant.name()
                : requisites.officialName();
        return new Context(new InvoiceDocument.Cooperative(name, tenant.edrpou(), requisites.legalAddress(),
                requisites.bankName(), requisites.iban()), names(roleHolders.activeHolders("CHAIRMAN")),
                names(roleHolders.activeHolders("TREASURER")), chargeTypes.all());
    }

    /**
     * The invoice of a plot from its ledger of the month: the figures ({@link InvoiceCalculator}), the type names as
     * printed, the number {@code YYYY-MM/plot}, the QR code when something is due ({@link NbuQrPayload}).
     */
    static InvoiceDocument document(UUID id, int version, PlotFacts plot, List<OwnerShare> owners,
            Map<UUID, String> ownerNames, InvoiceLedger plotLedger, Context context, Instant asOf) {
        InvoiceFigures figures = InvoiceCalculator.of(plotLedger, context.types());
        DocumentTexts texts = DocumentTexts.uk();
        Map<UUID, ChargeTypeFacts> types = context.types().stream()
                .collect(Collectors.toMap(ChargeTypeFacts::id, Function.identity()));
        List<InvoiceDocument.TypeRow> rows = figures.types().stream().map(row -> {
            ChargeTypeFacts type = types.get(row.chargeTypeId());
            return new InvoiceDocument.TypeRow(row.chargeTypeId(), type == null ? null : type.code(),
                    type == null ? "" : CashReceipts.typeName(type, texts), row.start(), row.charged(), row.paid(),
                    row.end());
        }).toList();
        YearMonth month = plotLedger.month();
        String number = month + "/" + plot.number();
        InvoiceDocument.Cooperative cooperative = context.cooperative();
        InvoiceDocument.Qr qr = null;
        if (figures.payable()) {
            NbuQrPayload payload = NbuQrPayload.of(new NbuQrPayload.Invoice(cooperative.officialName(),
                    Objects.requireNonNull(cooperative.iban()), figures.due(),
                    Objects.requireNonNull(cooperative.edrpou()), plot.number(), month, number));
            qr = new InvoiceDocument.Qr(payload.link(), payload.purpose());
        }
        List<InvoiceDocument.Owner> printedOwners = owners.stream().map(owner -> new InvoiceDocument.Owner(
                owner.memberId(), ownerNames.getOrDefault(owner.memberId(), ""), owner.numerator(),
                owner.denominator())).toList();
        MonthStatus status = plotLedger.status() == null ? MonthStatus.OPEN : plotLedger.status();
        return new InvoiceDocument(id, version, number, month, status, asOf, asOf, DocumentFormat.dayOf(asOf),
                cooperative, new InvoiceDocument.Plot(plot.id(), plot.number(), plot.street()), printedOwners,
                context.chairmen(), context.treasurers(), rows, figures.lines(), figures.receivedAfter(),
                new InvoiceDocument.Totals(figures.endDebt(), figures.prepayment(), figures.received(), figures.due()),
                qr);
    }

    /** The full names of the owners. */
    Map<UUID, String> ownerNames(List<OwnerShare> owners) {
        Map<UUID, MemberRef> found = members.find(owners.stream().map(OwnerShare::memberId).toList());
        return found.values().stream().collect(Collectors.toMap(MemberRef::id, InvoiceBuilder::fullName));
    }

    /** «Last First Middle»: how a document names a person. */
    static String fullName(MemberRef member) {
        return member.middleName() == null || member.middleName().isBlank() ? member.shortName()
                : member.shortName() + " " + member.middleName();
    }

    private static List<String> names(List<MemberRef> holders) {
        return holders.stream().map(InvoiceBuilder::fullName).toList();
    }

    /** The moment of the build: the later of the application clock and the database clock, read once. */
    private Instant asOf() {
        Object value = entityManager.createNativeQuery("select clock_timestamp()").getSingleResult();
        Instant database = switch (value) {
            case Instant instant -> instant;
            case java.time.OffsetDateTime offset -> offset.toInstant();
            case java.sql.Timestamp timestamp -> timestamp.toInstant();
            default -> throw new IllegalStateException("Unexpected database time " + value.getClass().getName());
        };
        Instant application = clock.instant();
        return application.isAfter(database) ? application : database;
    }

    /** Waits for the build lock of the cooperative's month and holds it until the caller's transaction ends. */
    private void lockMonth(UUID tenantId, YearMonth month) {
        entityManager.createNativeQuery(
                "select count(*) from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) as l")
                .setParameter("key", LOCK + tenantId + ":" + month)
                .getSingleResult();
    }
}
