package app.nadel.documents.internal;

import app.nadel.documents.internal.nbu.NbuQrImage;
import app.nadel.identity.AccessProfile;
import app.nadel.identity.OwnedPlot;
import app.nadel.identity.OwnedPlotsSource;
import app.nadel.identity.Permission;
import app.nadel.money.Money;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import app.nadel.settings.CooperativeSettings;
import app.nadel.tenancy.TenantDirectory;
import app.nadel.tenancy.TenantSummary;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the issued invoices (feature 016 §2.4, §2.9, technical considerations 2.8): the list for the board, one
 * version as stored, its PDF and its QR image, and whether invoices can be built.
 * <p>
 * <b>Who sees a version</b> ({@link #document}): {@code CHARGES_VIEW}; or a current owner of the invoice's plot, and
 * only the latest version of its plot and month. Anything else about an existing id (another plot, an older version
 * for a member) is the same 404 {@code NOT_FOUND} as an unknown id or another cooperative's invoice (RLS), so nothing
 * leaks. A caller with neither the permission nor a plot of their own may see no invoice at all: 403
 * {@code PERMISSION_DENIED}, answered before the id is looked up. The platform operator has no cooperative data: 403.
 * The PDF and the QR image are made from the stored snapshot on every download and never stored; downloads are not
 * audited.
 */
@Service
class Invoices {

    private static final UUID NONE = new UUID(0L, 0L);

    /** The pixels per module of {@code qr.png}. */
    static final int QR_PX_PER_MODULE = 8;

    private static final String PAGE = """
            select i.id, i.plot_id, i.snapshot -> 'plot' ->> 'number' as plot_number, i.month, i.number,
                   i.version, i.due, i.issued_at,
                   i.version = max(i.version) over (partition by i.plot_id, i.month) as latest
            from invoice i
            where (:allMonths or i.month = :month)
              and (:allPlots or i.plot_id = :plotId)
            order by i.month desc, i.issued_at desc, i.number, i.version desc, i.id
            limit :limit offset :offset
            """;

    private static final String COUNT = """
            select count(*)
            from invoice i
            where (:allMonths or i.month = :month)
              and (:allPlots or i.plot_id = :plotId)
            """;

    /** One row of the list. */
    record Row(UUID id, UUID plotId, String plotNumber, YearMonth month, String number, int version, Money due,
            Instant issuedAt, boolean latest) {
    }

    /** A page of the list. */
    record Page(List<Row> items, int page, int size, long totalItems) {

        int totalPages() {
            return (int) ((totalItems + size - 1) / size);
        }
    }

    /** A rendered file. */
    record File(String fileName, byte[] content) {
    }

    private final EntityManager entityManager;
    private final InvoiceRepository invoices;
    private final InvoiceSnapshots snapshots;
    private final OwnedPlotsSource ownedPlots;
    private final CooperativeSettings settings;
    private final TenantDirectory tenants;

    Invoices(EntityManager entityManager, InvoiceRepository invoices, InvoiceSnapshots snapshots,
            OwnedPlotsSource ownedPlots, CooperativeSettings settings, TenantDirectory tenants) {
        this.entityManager = entityManager;
        this.invoices = invoices;
        this.snapshots = snapshots;
        this.ownedPlots = ownedPlots;
        this.settings = settings;
        this.tenants = tenants;
    }

    /** The page of issued versions; the caller holds {@code CHARGES_VIEW}. */
    @Transactional(readOnly = true)
    Page list(@Nullable YearMonth month, @Nullable UUID plotId, int page, int size) {
        // a filter that is off is bound to a placeholder value (a null of unknown type is not valid SQL there)
        LocalDate first = month == null ? LocalDate.EPOCH : month.atDay(1);
        UUID plot = plotId == null ? NONE : plotId;
        List<?> rows = entityManager.createNativeQuery(PAGE)
                .setParameter("allMonths", month == null)
                .setParameter("month", first)
                .setParameter("allPlots", plotId == null)
                .setParameter("plotId", plot)
                .setParameter("limit", size)
                .setParameter("offset", (long) page * size)
                .getResultList();
        long total = ((Number) entityManager.createNativeQuery(COUNT)
                .setParameter("allMonths", month == null)
                .setParameter("month", first)
                .setParameter("allPlots", plotId == null)
                .setParameter("plotId", plot)
                .getSingleResult()).longValue();
        List<Row> items = rows.stream().map(row -> {
            Object[] v = (Object[]) row;
            LocalDate day = v[3] instanceof java.sql.Date sql ? sql.toLocalDate() : (LocalDate) v[3];
            return new Row((UUID) v[0], (UUID) v[1], Objects.requireNonNullElse((String) v[2], ""),
                    YearMonth.from(day), (String) v[4],
                    ((Number) v[5]).intValue(), Money.of((BigDecimal) v[6]), (Instant) v[7], (Boolean) v[8]);
        }).toList();
        return new Page(items, page, size, total);
    }

    /** One version as the viewer may see it, the names opened. */
    @Transactional(readOnly = true)
    InvoiceDocument document(UUID id, AccessProfile viewer) {
        UUID tenantId = requireCooperative(viewer);
        Set<UUID> owned = null;
        if (!viewer.can(Permission.CHARGES_VIEW)) {
            owned = viewer.memberId() == null ? Set.of()
                    : ownedPlots.currentPlotsOf(viewer.memberId()).stream().map(OwnedPlot::plotId)
                            .collect(Collectors.toSet());
            if (owned.isEmpty()) {
                throw denied();
            }
        }
        InvoiceRecord record = invoices.findById(id).orElseThrow(ApiProblemException::notFound);
        if (owned != null && (!owned.contains(record.getPlotId())
                || invoices.latestVersion(record.getPlotId(), record.getMonth()) != record.getVersion())) {
            throw ApiProblemException.notFound();
        }
        return snapshots.open(tenantId, record.getId(), record.getSnapshot());
    }

    /** The PDF of a version, {@code invoice_2026-10_plot-9_v2.pdf}. */
    @Transactional(readOnly = true)
    File pdf(UUID id, AccessProfile viewer) {
        InvoiceDocument document = document(id, viewer);
        return new File(fileName(document) + ".pdf", InvoiceLayout.render(document));
    }

    /** The QR image of a version; 404 when it has none (nothing due). */
    @Transactional(readOnly = true)
    File qr(UUID id, AccessProfile viewer) {
        InvoiceDocument document = document(id, viewer);
        if (document.qr() == null) {
            throw ApiProblemException.notFound();
        }
        return new File(fileName(document) + "_qr.png", NbuQrImage.of(document.qr().link()).png(QR_PX_PER_MODULE));
    }

    /** The requisites an invoice needs that are not set ({@code IBAN}, {@code EDRPOU}). */
    @Transactional(readOnly = true)
    List<String> missingRequisites() {
        TenantSummary tenant = tenants.current().orElseThrow(() -> new IllegalStateException("No cooperative"));
        return RequisitesCheck.missing(settings.requisites(), tenant.edrpou());
    }

    /** {@code invoice_2026-10_plot-9_v2}: ASCII only, a plot number's other characters as {@code -}. */
    static String fileName(InvoiceDocument document) {
        String plot = document.plot().number().replaceAll("[^0-9A-Za-z]+", "-");
        return "invoice_" + document.month() + "_plot-" + plot + "_v" + document.version();
    }

    private static UUID requireCooperative(AccessProfile viewer) {
        if (viewer.platformOperator() || viewer.tenantId() == null) {
            throw denied();
        }
        return Objects.requireNonNull(viewer.tenantId());
    }

    private static ApiProblemException denied() {
        return new ApiProblemException(HttpStatus.FORBIDDEN, ProblemCodes.PERMISSION_DENIED,
                "The caller may see no invoice.");
    }
}
