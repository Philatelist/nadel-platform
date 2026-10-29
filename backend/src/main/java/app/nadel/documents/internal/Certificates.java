package app.nadel.documents.internal;

import app.nadel.audit.ActorNames;
import app.nadel.identity.AccessProfile;
import app.nadel.identity.RoleRef;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.PlotDebts;
import app.nadel.members.MemberDirectory;
import app.nadel.members.MemberRef;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Debt certificates (feature 016 §2.7, technical considerations 2.7): issue, register, PDF and annulment. The caller
 * holds the permission of the endpoint ({@code CERTIFICATES_ISSUE} to issue and annul, {@code CHARGES_VIEW} for the
 * register and the PDF; checked on the controller), so a member never gets here.
 * <p>
 * <b>Issue</b> ({@link #issue}), one read committed transaction: 400 {@code VALIDATION_FAILED} on {@code asOf} for a day
 * after today (Kyiv); 404 {@code PLOT_NOT_FOUND} for an unknown plot or one of another cooperative; 409
 * {@code PLOT_COMMON_LAND} / {@code PLOT_ARCHIVED} (the plot balances report lists neither, and the figures are those of
 * that report); 409 {@code REQUISITES_MISSING} as for invoices. Then the figures
 * ({@code LedgerReadModel.debtsAsOf(asOf, every type, [plot])}: the debt per charge type, its total and the prepayment),
 * the area, the current owners with their shares and names, the issuer's name and role, the names sealed. The number is
 * taken last ({@link DocumentCounter}, the year of the issue in Kyiv), after every read and check: only the insert of
 * the row and its audit event ({@code CERTIFICATE_ISSUED}) follow, so the counter row is locked as briefly as possible
 * and a failure anywhere rolls the step back (no gap). A plot without a current owner gets a certificate without
 * owners. The total stored in {@code certificate.total_debt} is never below zero (the column's check; a total below
 * zero would print «Заборгованість відсутня» anyway).
 * <p>
 * <b>Annul</b> ({@link #annul}): the reason is required (stripped; 400 {@code REQUIRED}); one {@code UPDATE … WHERE
 * annulled_at IS NULL}: a concurrent second annulment waits for the row lock, then matches nothing; 0 rows is 409
 * {@code CERTIFICATE_ALREADY_ANNULLED} for an existing certificate, 404 {@code NOT_FOUND} otherwise. Audit
 * {@code CERTIFICATE_ANNULLED}.
 */
@Service
class Certificates {

    /** The longest annulment reason (the contract and the column's check). */
    static final int REASON_MAX = 500;

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("issuedAt"), Sort.Order.desc("year"),
            Sort.Order.desc("seq"), Sort.Order.asc("id"));

    /** A certificate as the register lists it. */
    record Row(UUID id, String number, UUID plotId, String plotNumber, LocalDate asOf, Money totalDebt,
            @Nullable UUID issuedBy, @Nullable String issuedByName, Instant issuedAt, @Nullable Instant annulledAt,
            @Nullable String annulledByName, @Nullable String annulReason) {

        boolean annulled() {
            return annulledAt != null;
        }
    }

    /** A page of the register. */
    record Page(List<Row> items, int page, int size, long totalItems) {

        int totalPages() {
            return (int) ((totalItems + size - 1) / size);
        }
    }

    /** A rendered file. */
    record File(String fileName, byte[] content) {
    }

    private final EntityManager entityManager;
    private final CertificateRepository certificates;
    private final CertificateSnapshots snapshots;
    private final DocumentCounter counter;
    private final CertificateAuditPublisher audit;
    private final LedgerReadModel ledger;
    private final PlotCatalog plots;
    private final PlotOwners plotOwners;
    private final MemberDirectory members;
    private final ActorNames actorNames;
    private final ChargeTypeCatalog chargeTypes;
    private final CooperativeSettings settings;
    private final TenantDirectory tenants;
    private final Clock clock;

    Certificates(EntityManager entityManager, CertificateRepository certificates, CertificateSnapshots snapshots,
            DocumentCounter counter, CertificateAuditPublisher audit, LedgerReadModel ledger, PlotCatalog plots,
            PlotOwners plotOwners, MemberDirectory members, ActorNames actorNames, ChargeTypeCatalog chargeTypes,
            CooperativeSettings settings, TenantDirectory tenants, Clock clock) {
        this.entityManager = entityManager;
        this.certificates = certificates;
        this.snapshots = snapshots;
        this.counter = counter;
        this.audit = audit;
        this.ledger = ledger;
        this.plots = plots;
        this.plotOwners = plotOwners;
        this.members = members;
        this.actorNames = actorNames;
        this.chargeTypes = chargeTypes;
        this.settings = settings;
        this.tenants = tenants;
        this.clock = clock;
    }

    /**
     * Issues the certificate of the plot as of the day (today in Kyiv when null).
     *
     * @param issuer the caller, who holds {@code CERTIFICATES_ISSUE}
     */
    @Transactional
    Row issue(UUID plotId, @Nullable LocalDate asOf, AccessProfile issuer) {
        Objects.requireNonNull(plotId, "plotId");
        UUID tenantId = Objects.requireNonNull(issuer.tenantId(), "tenantId");
        // stored with microseconds, so a PDF drawn now and one drawn later from the row are the same
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LocalDate today = DocumentFormat.dayOf(now);
        LocalDate day = asOf == null ? today : asOf;
        if (day.isAfter(today)) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("asOf", ProblemCodes.INVALID)));
        }
        PlotFacts plot = plots.find(plotId).orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                ProblemCodes.PLOT_NOT_FOUND, "The plot was not found."));
        if (plot.commonLand()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PLOT_COMMON_LAND,
                    "Common land gets no debt certificate.");
        }
        if (plot.archived()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PLOT_ARCHIVED,
                    "An archived plot gets no debt certificate.");
        }
        ReceiptContent.Requisites requisites = requisites();
        List<OwnerShare> owners = plotOwners.currentOwnerShares(List.of(plotId)).getOrDefault(plotId, List.of());
        Set<UUID> people = new LinkedHashSet<>();
        owners.forEach(owner -> people.add(owner.memberId()));
        if (issuer.memberId() != null) {
            people.add(issuer.memberId());
        }
        Map<UUID, MemberRef> names = members.find(people);
        PlotDebts debts = ledger.debtsAsOf(day, Set.of(), List.of(plotId)).getOrDefault(plotId,
                PlotDebts.none(plotId));
        DocumentTexts texts = DocumentTexts.uk();

        UUID id = UUID.randomUUID();
        List<CertificateDocument.Owner> printedOwners = owners.stream().map(owner -> new CertificateDocument.Owner(
                owner.memberId(), Optional.ofNullable(names.get(owner.memberId())).map(InvoiceBuilder::fullName)
                        .orElse(""), owner.numerator(), owner.denominator())).toList();
        MemberRef issuerMember = issuer.memberId() == null ? null : names.get(issuer.memberId());
        CertificateDocument.Issuer printedIssuer = new CertificateDocument.Issuer(issuer.memberId(),
                issuerMember == null ? issuer.displayName() : InvoiceBuilder.fullName(issuerMember),
                issuerRole(issuer.roles(), texts));
        // the number is not known yet: the persons are sealed first, the counter steps last
        CertificateDocument draft = new CertificateDocument(id, "", 0, 0, day, now, requisites,
                new CertificateDocument.Plot(plot.id(), plot.number(), plot.street(), plot.areaM2()), printedOwners,
                typeDebts(debts, chargeTypes.all(), texts), debts.total(), debts.prepayment(), printedIssuer, null);
        String sealedPersons = snapshots.sealPersons(tenantId, draft);

        int year = now.atZone(DocumentFormat.ZONE).getYear();
        long seq = counter.next(tenantId, DocumentCounter.Kind.CERTIFICATE, year);
        CertificateDocument document = new CertificateDocument(id, CertificateDocument.number(year, seq), year,
                Math.toIntExact(seq), day, now, requisites, draft.plot(), printedOwners, draft.types(),
                draft.totalDebt(), draft.prepayment(), printedIssuer, null);
        Money stored = document.inDebt() ? document.totalDebt() : Money.ZERO;
        entityManager.persist(CertificateRecord.issue(tenantId, document,
                CertificateSnapshots.snapshot(document, sealedPersons), stored.value()));
        entityManager.flush();
        audit.issued(issuer, document);
        return new Row(id, document.number(), plotId, plot.number(), day, stored, issuer.memberId(),
                printedIssuer.name(), now, null, null, null);
    }

    /** A page of the register, newest issue first; {@code annulled} null means both states. */
    @Transactional(readOnly = true)
    Page list(@Nullable Boolean annulled, int page, int size) {
        PageRequest request = PageRequest.of(page, size, NEWEST_FIRST);
        org.springframework.data.domain.Page<CertificateRecord> found = annulled == null
                ? certificates.findAll(request)
                : annulled ? certificates.findByAnnulledAtIsNotNull(request)
                        : certificates.findByAnnulledAtIsNull(request);
        Set<UUID> people = found.getContent().stream()
                .flatMap(record -> Stream.of(record.getIssuedBy(), record.getAnnulledBy()))
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> names = people.isEmpty() ? Map.of() : actorNames.displayNames(people);
        List<Row> items = found.getContent().stream().map(record -> row(record, names)).toList();
        return new Page(items, page, size, found.getTotalElements());
    }

    /** The PDF of the certificate in its current state, {@code certificate_D-2026-1.pdf}. */
    @Transactional(readOnly = true)
    File pdf(UUID id, AccessProfile viewer) {
        UUID tenantId = Objects.requireNonNull(viewer.tenantId(), "tenantId");
        CertificateRecord record = certificates.findById(id).orElseThrow(ApiProblemException::notFound);
        CertificateDocument document = snapshots.open(tenantId, record.getId(), record.getSnapshot(),
                record.annulment());
        return new File(fileName(document.number()), CertificateLayout.render(document));
    }

    /**
     * Annuls the certificate with the reason, once.
     *
     * @param actor the caller, who holds {@code CERTIFICATES_ISSUE}
     */
    @Transactional
    Row annul(UUID id, @Nullable String reason, AccessProfile actor) {
        String text = reason == null ? "" : reason.strip();
        if (text.isEmpty() || text.length() > REASON_MAX) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("reason",
                            text.isEmpty() ? ProblemCodes.REQUIRED : ProblemCodes.TOO_LONG)));
        }
        UUID by = Objects.requireNonNull(actor.memberId(), "memberId");
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int changed = entityManager.createNativeQuery("update certificate set annulled_at = :at, annulled_by = :by, "
                + "annul_reason = :reason where id = :id and annulled_at is null")
                .setParameter("at", now)
                .setParameter("by", by)
                .setParameter("reason", text)
                .setParameter("id", id)
                .executeUpdate();
        if (changed == 0) {
            if (certificates.existsById(id)) {
                throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.CERTIFICATE_ALREADY_ANNULLED,
                        "The certificate is already annulled.");
            }
            throw ApiProblemException.notFound();
        }
        // not loaded before the update in this transaction: read fresh, with the annulment
        CertificateRecord record = certificates.findById(id).orElseThrow(ApiProblemException::notFound);
        audit.annulled(actor, record.getId(), record.getNumber(), text, now);
        Set<UUID> people = Stream.of(record.getIssuedBy(), record.getAnnulledBy()).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return row(record, actorNames.displayNames(people));
    }

    // ---- parts

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

    /**
     * The debt per charge type the plot has a counted charge of, in the cooperative's charge type order (a type the
     * catalog does not know goes last, by id), with the printed name.
     */
    static List<CertificateDocument.TypeDebt> typeDebts(PlotDebts debts, List<ChargeTypeFacts> types,
            DocumentTexts texts) {
        List<CertificateDocument.TypeDebt> rows = new ArrayList<>();
        Set<UUID> left = new LinkedHashSet<>(debts.byChargeType().keySet());
        for (ChargeTypeFacts type : types) {
            Money debt = debts.byChargeType().get(type.id());
            if (debt != null) {
                rows.add(new CertificateDocument.TypeDebt(type.id(), type.code(), CashReceipts.typeName(type, texts),
                        debt));
                left.remove(type.id());
            }
        }
        for (UUID unknown : left) {
            rows.add(new CertificateDocument.TypeDebt(unknown, null, "", debts.byChargeType().get(unknown)));
        }
        return rows;
    }

    /**
     * The role printed after the issuer's name: the chairman's («Голова») when the issuer holds the chairman's role,
     * else the treasurer's, else the names of the custom roles (sorted, joined by ", "), else the member's. A base role
     * the cooperative renamed prints its stored name.
     */
    static String issuerRole(List<RoleRef> roles, DocumentTexts texts) {
        for (String base : List.of("CHAIRMAN", "TREASURER")) {
            Optional<RoleRef> held = roles.stream().filter(role -> base.equals(role.baseCode())).findFirst();
            if (held.isPresent()) {
                return roleName(held.get(), texts);
            }
        }
        List<String> custom = roles.stream().filter(role -> role.baseCode() == null).map(RoleRef::name).sorted()
                .toList();
        if (!custom.isEmpty()) {
            return String.join(", ", custom);
        }
        return roles.stream().filter(role -> "MEMBER".equals(role.baseCode())).findFirst()
                .map(role -> roleName(role, texts)).orElse(texts.text("certificate.role.MEMBER"));
    }

    private static String roleName(RoleRef role, DocumentTexts texts) {
        return role.nameCustomized() || role.baseCode() == null ? role.name()
                : texts.text("certificate.role." + role.baseCode());
    }

    /** {@code certificate_D-2026-1}: ASCII only, «Д» as {@code D}. */
    static String fileName(String number) {
        return "certificate_" + number.replace("Д", "D").replaceAll("[^0-9A-Za-z-]+", "-") + ".pdf";
    }

    private static Row row(CertificateRecord record, Map<UUID, String> names) {
        return new Row(record.getId(), record.getNumber(), record.getPlotId(), record.plotNumber(),
                record.getAsOf(), Money.of(record.getTotalDebt()), record.getIssuedBy(),
                record.getIssuedBy() == null ? null : names.get(record.getIssuedBy()), record.getIssuedAt(),
                record.getAnnulledAt(), record.getAnnulledBy() == null ? null : names.get(record.getAnnulledBy()),
                record.getAnnulReason());
    }
}
