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
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds and stores invoices (feature 016 §2.5, §2.6, technical considerations 2.5): the invoice of one plot on request
 * ({@code POST /plots/{id}/invoices}, {@link #forPlot}) and the invoices of a whole billing month
 * ({@code POST /billing-months/{month}/invoices}, {@link #forMonth}).
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
 * <b>On request</b>: the invoice as of now is compared with the latest version of the plot and month by its content
 * hash ({@link InvoiceSnapshots#contentHash}, which leaves out the version, the dates and the QR code), as the month
 * build compares it: the same hash returns the latest version as it is (nothing written, no audit entry); otherwise
 * the next version is stored (version 1 when the month has none) with the audit entry {@code INVOICE_REISSUED}. 400
 * {@code VALIDATION_FAILED} on {@code month} for a month after the current one (Kyiv); 404 {@code PLOT_NOT_FOUND} for
 * an unknown plot or one of another cooperative; 409 {@code REQUISITES_MISSING} with the missing {@code IBAN} /
 * {@code EDRPOU}; 409 {@code PLOT_NOT_INVOICEABLE} for common land, an archived plot or a plot without a current
 * owner. Nothing is stored unless every check passes. The caller holds
 * {@code INVOICES_ISSUE} (checked on the endpoint).
 * <p>
 * <b>Month build</b> (synchronous, in the request; ADR 0017 "Synchronous month build and its limit"): the checks, in
 * this order, for the preview too: a month after the current one 409 {@code PERIOD_IN_FUTURE}, a month before the
 * first billing month 404 {@code NOT_FOUND} ({@link ListedMonths}); the requisites 409 {@code REQUISITES_MISSING}; more
 * than {@value #MONTH_LIMIT} invoiceable plots 422 {@code INVOICE_BUILD_TOO_LARGE}. The invoiceable plots are the
 * chargeable ones ({@code PlotCatalog.findChargeable}: active, not common land) with a current owner, in street and plot
 * order. Then, in chunks of {@value #CHUNK} plots, all inside the one {@code REPEATABLE READ} transaction with one
 * {@code asOf}: the ledgers of the chunk, each plot's invoice, its content hash compared with the latest version of the
 * month; a plot without an invoice of the month and with nothing due is skipped unless {@code includeNothingDue}; a plot
 * whose latest version has the same hash is unchanged; every other plot gets a new version; the persistence context is
 * flushed and cleared after each chunk. One {@code INVOICES_ISSUED} audit entry with the counts. The build takes the
 * month's lock without waiting ({@code pg_try_advisory_xact_lock}): while another build of the month, or an on-request
 * invoice of it, holds the lock it is 409 {@code INVOICE_BUILD_RUNNING} and nothing is built; the on-request invoice
 * (short) waits for the lock instead. The preview takes no lock and writes nothing: it walks the plots exactly as the
 * build does and makes the same decision per plot (built, new version, unchanged, skipped), so its counts are what a
 * build started next does unless the ledger changes in between.
 */
@Service
class InvoiceBuilder {

    static final String LOCK = "documents.invoices:";

    /** The most invoiceable plots one month build takes (the size of the largest supported cooperative). */
    static final int MONTH_LIMIT = 2_000;

    /** The plots read and written together in a month build. */
    static final int CHUNK = 50;

    /**
     * The counts of a month build: {@code invoiceable}, {@code built}, {@code newVersions}, {@code unchanged} and
     * {@code skipped} (what a build did, or what a preview predicts it would do); a preview also has
     * {@code toBuild} ({@code built + newVersions}), {@code nothingDue} (the plots without an invoice of the month
     * with nothing due) and {@code existing} (the plots with an invoice of the month), a build has them 0.
     */
    record MonthBuild(boolean preview, int invoiceable, int toBuild, int nothingDue, int existing, int built,
            int newVersions, int unchanged, int skipped) {
    }

    /** The invoiceable plots of the month in street and plot order, with their facts and current owners. */
    private record Invoiceable(List<UUID> order, Map<UUID, PlotFacts> facts, Map<UUID, List<OwnerShare>> owners) {
    }

    /** The latest version of a plot's invoice of the month. */
    private record Latest(int version, byte[] contentHash) {
    }

    /** What a month build does with one plot. */
    private enum Outcome {
        /** Version 1: the month has no invoice of the plot yet. */
        BUILT,
        /** The next version: the content hash differs from the latest version's. */
        NEW_VERSION,
        /** Nothing written: the latest version has the same content hash. */
        UNCHANGED,
        /** Nothing written: no invoice of the month and nothing due, without {@code includeNothingDue}. */
        SKIPPED
    }

    /** The decision of a month build for one plot, with the invoice it stores when it writes one. */
    private record Decision(Outcome outcome, boolean nothingDue, InvoiceDocument document) {
    }

    /** The counts of a month build or its preview, plot by plot. */
    private static final class Tally {
        private int built;
        private int newVersions;
        private int unchanged;
        private int skipped;
        private int nothingDue;

        void count(Decision decision) {
            switch (decision.outcome()) {
                case BUILT -> built++;
                case NEW_VERSION -> newVersions++;
                case UNCHANGED -> unchanged++;
                case SKIPPED -> skipped++;
            }
            if (decision.nothingDue()) {
                nothingDue++;
            }
        }
    }

    /**
     * The invoice version of an on-request issue: the new version ({@code created}), or the latest one when nothing
     * changed.
     */
    record Issued(UUID id, UUID plotId, String plotNumber, YearMonth month, String number, int version, Money due,
            Instant issuedAt, boolean created) {
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
    private final ListedMonths months;
    private final Clock clock;

    /** Test hook: called with the index of each chunk of a month build after it was written; nothing in production. */
    private volatile IntConsumer afterChunk = chunk -> {
    };

    InvoiceBuilder(EntityManager entityManager, PlatformTransactionManager transactionManager, LedgerReadModel ledger,
            PlotCatalog plots, PlotOwners plotOwners, MemberDirectory members, RoleHolders roleHolders,
            ChargeTypeCatalog chargeTypes, CooperativeSettings settings, TenantDirectory tenants,
            InvoiceSnapshots snapshots, InvoiceRepository invoices, InvoiceAuditPublisher audit, ListedMonths months,
            Clock clock) {
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
        this.months = months;
        this.clock = clock;
    }

    /** Sets the test hook called after each chunk of a month build; {@code null} removes it. */
    void afterChunk(@Nullable IntConsumer hook) {
        this.afterChunk = hook == null ? chunk -> {
        } : hook;
    }

    /**
     * Previews or builds the invoices of the month (see the class comment).
     *
     * @param includeNothingDue also build version 1 for a plot with nothing due
     * @param preview           count only, write nothing and take no lock
     * @param issuer            the caller, who holds {@code INVOICES_ISSUE}
     */
    @Transactional
    MonthBuild forMonth(YearMonth month, boolean includeNothingDue, boolean preview, AccessProfile issuer) {
        Objects.requireNonNull(month, "month");
        UUID tenantId = Objects.requireNonNull(issuer.tenantId(), "tenantId");
        months.requireListed(month, true);
        if (preview) {
            return Objects.requireNonNull(repeatableRead.execute(status -> preview(tenantId, month,
                    includeNothingDue)));
        }
        tryLockMonth(tenantId, month);
        return Objects.requireNonNull(repeatableRead.execute(status -> build(tenantId, month, includeNothingDue,
                issuer)));
    }

    /**
     * The preview inside the {@code REPEATABLE READ} transaction: the same decisions as {@link #build}, counted and
     * nothing written.
     */
    private MonthBuild preview(UUID tenantId, YearMonth month, boolean includeNothingDue) {
        Context context = context();
        Invoiceable invoiceable = invoiceable();
        Instant asOf = asOf();
        Tally tally = new Tally();
        decide(tenantId, month, includeNothingDue, context, invoiceable, asOf, tally::count,
                index -> entityManager.clear());
        return new MonthBuild(true, invoiceable.order().size(), tally.built + tally.newVersions, tally.nothingDue,
                tally.newVersions + tally.unchanged, tally.built, tally.newVersions, tally.unchanged, tally.skipped);
    }

    /** The build inside the {@code REPEATABLE READ} transaction, chunk by chunk. */
    private MonthBuild build(UUID tenantId, YearMonth month, boolean includeNothingDue, AccessProfile issuer) {
        Context context = context();
        Invoiceable invoiceable = invoiceable();
        Instant asOf = asOf();
        Tally tally = new Tally();
        decide(tenantId, month, includeNothingDue, context, invoiceable, asOf, decision -> {
            tally.count(decision);
            if (decision.outcome() == Outcome.BUILT || decision.outcome() == Outcome.NEW_VERSION) {
                InvoiceDocument document = decision.document();
                InvoiceSnapshots.Sealed sealed = snapshots.seal(tenantId, document);
                entityManager.persist(InvoiceRecord.issue(document.id(), tenantId, document, sealed,
                        issuer.memberId()));
            }
        }, index -> {
            entityManager.flush();
            entityManager.clear();
            afterChunk.accept(index);
        });
        audit.issued(issuer, month, tally.built, tally.newVersions, tally.unchanged, tally.skipped, asOf);
        return new MonthBuild(false, invoiceable.order().size(), 0, 0, 0, tally.built, tally.newVersions,
                tally.unchanged, tally.skipped);
    }

    /**
     * Walks the invoiceable plots in chunks of {@value #CHUNK} and decides for each what the month build does: no
     * invoice of the month and nothing due without {@code includeNothingDue} → skipped; no invoice → version 1; the
     * same content hash as the latest version → unchanged; otherwise the next version. {@code afterEachChunk} is
     * called with the chunk's index once its plots are decided.
     */
    private void decide(UUID tenantId, YearMonth month, boolean includeNothingDue, Context context,
            Invoiceable invoiceable, Instant asOf, Consumer<Decision> onPlot, IntConsumer afterEachChunk) {
        LocalDate first = month.atDay(1);
        List<UUID> order = invoiceable.order();
        for (int from = 0, index = 0; from < order.size(); from += CHUNK, index++) {
            List<UUID> chunk = order.subList(from, Math.min(order.size(), from + CHUNK));
            Map<UUID, InvoiceLedger> ledgers = ledger.invoiceLedgers(chunk, month, asOf);
            Map<UUID, Latest> latest = latest(first, chunk);
            Map<UUID, String> names = ownerNames(chunk.stream()
                    .flatMap(plotId -> invoiceable.owners().get(plotId).stream()).toList());
            for (UUID plotId : chunk) {
                Latest previous = latest.get(plotId);
                InvoiceDocument document = document(UUID.randomUUID(), previous == null ? 1 : previous.version() + 1,
                        invoiceable.facts().get(plotId), invoiceable.owners().get(plotId), names,
                        ledgers.get(plotId), context, asOf);
                boolean nothingDue = previous == null && document.totals().due().signum() <= 0;
                Outcome outcome;
                if (nothingDue && !includeNothingDue) {
                    outcome = Outcome.SKIPPED;
                } else if (previous == null) {
                    outcome = Outcome.BUILT;
                } else if (Arrays.equals(previous.contentHash(), snapshots.contentHash(tenantId, document))) {
                    outcome = Outcome.UNCHANGED;
                } else {
                    outcome = Outcome.NEW_VERSION;
                }
                onPlot.accept(new Decision(outcome, nothingDue, document));
            }
            afterEachChunk.accept(index);
        }
    }

    /**
     * The invoiceable plots: the chargeable ones (active, not common land) with a current owner, in street and plot
     * order; refuses more than {@value #MONTH_LIMIT} (422 {@code INVOICE_BUILD_TOO_LARGE}).
     */
    private Invoiceable invoiceable() {
        Map<UUID, PlotFacts> facts = new LinkedHashMap<>();
        plots.findChargeable().forEach(plot -> facts.put(plot.id(), plot));
        Map<UUID, List<OwnerShare>> owners = new HashMap<>(plotOwners.currentOwnerShares(facts.keySet()));
        owners.values().removeIf(List::isEmpty);
        if (owners.size() > MONTH_LIMIT) {
            throw ApiProblemException.invoiceBuildTooLarge(MONTH_LIMIT);
        }
        List<UUID> order = owners.isEmpty() ? List.of() : plots.inStreetOrder(owners.keySet());
        return new Invoiceable(order, facts, owners);
    }

    /** The latest version of the month's invoice of each plot of the chunk that has one. */
    private Map<UUID, Latest> latest(LocalDate month, List<UUID> chunk) {
        Map<UUID, Latest> latest = new HashMap<>();
        for (Object[] row : invoices.latestOfPlots(month, chunk)) {
            latest.put((UUID) row[0], new Latest(((Number) row[1]).intValue(), (byte[]) row[2]));
        }
        return latest;
    }

    /**
     * Issues the invoice of the plot for the month (the current month when null) as of now: a new version when its
     * content hash differs from the latest version's (or the month has none), otherwise the latest version as it is.
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

    /**
     * The build of one plot inside the {@code REPEATABLE READ} transaction: compared with the latest version of the
     * month as the month build compares it; written and audited only when it differs.
     */
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
        Optional<InvoiceRecord> latest = invoices.findFirstByPlotIdAndMonthOrderByVersionDesc(plotId,
                month.atDay(1));
        int version = latest.map(InvoiceRecord::getVersion).orElse(0) + 1;
        InvoiceDocument document = document(UUID.randomUUID(), version, plot, owners, ownerNames(owners), plotLedger,
                context, asOf);
        if (latest.isPresent()
                && Arrays.equals(latest.get().getContentHash(), snapshots.contentHash(tenantId, document))) {
            InvoiceRecord same = latest.get();
            return new Issued(same.getId(), plotId, plot.number(), month, same.getNumber(), same.getVersion(),
                    Money.of(same.getDue()), same.getIssuedAt(), false);
        }
        InvoiceSnapshots.Sealed sealed = snapshots.seal(tenantId, document);
        entityManager.persist(InvoiceRecord.issue(document.id(), tenantId, document, sealed, issuer.memberId()));
        entityManager.flush();
        audit.reissued(issuer, document, asOf);
        return new Issued(document.id(), plotId, plot.number(), month, document.number(), version,
                document.totals().due(), document.issuedAt(), true);
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
     * printed, the number {@code YYYY-MM/plot}, the payment rows booked in the month as the ledger gives them, the QR
     * code when something is due ({@link NbuQrPayload}).
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
                context.chairmen(), context.treasurers(), rows, figures.lines(), plotLedger.payments(),
                figures.receivedAfter(),
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
        // to the microsecond the database stores, so a stored version reads back with the issue time it was answered
        // with (a clock with nanoseconds, as on Linux, would differ); still not before the database time, which has
        // microseconds only
        return (application.isAfter(database) ? application : database).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Takes the build lock of the cooperative's month without waiting and holds it until the caller's transaction
     * ends; while another build of the month (or an on-request invoice of it) holds it: 409
     * {@code INVOICE_BUILD_RUNNING}, nothing built (feature 016 §2.5).
     */
    private void tryLockMonth(UUID tenantId, YearMonth month) {
        Object acquired = entityManager.createNativeQuery(
                "select pg_try_advisory_xact_lock(hashtextextended(:key, 0))")
                .setParameter("key", LOCK + tenantId + ":" + month)
                .getSingleResult();
        if (!Boolean.TRUE.equals(acquired)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.INVOICE_BUILD_RUNNING,
                    "The invoices of this month are being built already.");
        }
    }

    /**
     * Waits for the build lock of the cooperative's month and holds it until the caller's transaction ends (the
     * on-request invoice: it is short, so it waits for a running build instead of being refused).
     */
    private void lockMonth(UUID tenantId, YearMonth month) {
        entityManager.createNativeQuery(
                "select count(*) from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) as l")
                .setParameter("key", LOCK + tenantId + ":" + month)
                .getSingleResult();
    }
}
