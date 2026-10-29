package app.nadel.documents.internal;

import app.nadel.documents.PrintScope;
import app.nadel.documents.internal.pdf.PdfDocument;
import app.nadel.documents.internal.pdf.PdfDocument.PageSize;
import app.nadel.documents.internal.pdf.TextBlock;
import app.nadel.documents.internal.pdf.TextBlock.Align;
import app.nadel.members.DeliveryChannel;
import app.nadel.members.MemberDirectory;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotOwners;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The print set of a month's invoices (feature 016 §2.5, {@code GET /billing-months/{month}/invoices/print.pdf}): one
 * A4 PDF with the latest version of each invoice of the month, each drawn by {@link InvoiceLayout#draw} from the top
 * of a new page, one page per invoice (an invoice that cannot fit goes on on the next page, whose header repeats its
 * number and plot), in street and plot order
 * ({@code PlotCatalog.inStreetOrder}). {@link PrintScope#PAPER} keeps the plots with at least one current owner whose
 * delivery channel is {@code PAPER} ({@code PlotOwners.currentOwnerIds}, {@code MemberDirectory.deliveryChannels});
 * {@link PrintScope#ALL} keeps every plot. A set without invoices is one page saying so.
 * <p>
 * Byte-stable: the file's identity is the month, the scope and the ids of the printed versions in their order (a
 * version never changes), its time the latest {@code issuedAt} among them (the first moment of the month in Kyiv for an
 * empty set), and every invoice is drawn from its stored snapshot. Snapshots are read and opened 50 at a time, the
 * persistence context cleared in between. Nothing is stored; the caller checks the permission ({@code INVOICES_ISSUE}
 * on the endpoint); {@link #printListed} also checks the month ({@link ListedMonths}), {@link #print} (the facade)
 * prints whatever the month has.
 */
@Service
class PrintBundle {

    /** The snapshots read together. */
    static final int CHUNK = 50;

    /** One latest version of the month. */
    private record Entry(UUID id, UUID plotId, Instant issuedAt) {
    }

    private final EntityManager entityManager;
    private final InvoiceRepository invoices;
    private final InvoiceSnapshots snapshots;
    private final PlotCatalog plots;
    private final PlotOwners plotOwners;
    private final MemberDirectory members;
    private final ListedMonths months;

    PrintBundle(EntityManager entityManager, InvoiceRepository invoices, InvoiceSnapshots snapshots, PlotCatalog plots,
            PlotOwners plotOwners, MemberDirectory members, ListedMonths months) {
        this.entityManager = entityManager;
        this.invoices = invoices;
        this.snapshots = snapshots;
        this.plots = plots;
        this.plotOwners = plotOwners;
        this.members = members;
        this.months = months;
    }

    /**
     * The print set of a listed month (the endpoint): a month after the current one, before the first billing month or
     * while the cooperative has none is 404 {@code NOT_FOUND}.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Invoices.File printListed(UUID tenantId, YearMonth month, PrintScope scope) {
        months.requireListed(month, false);
        return print(tenantId, month, scope);
    }

    /** The print set of the cooperative's month, {@code invoices_2026-10_paper.pdf}. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Invoices.File print(UUID tenantId, YearMonth month, PrintScope scope) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(scope, "scope");
        List<Entry> entries = entries(month, scope);
        DocumentTexts texts = DocumentTexts.uk();
        StringBuilder identity = new StringBuilder("print:").append(month).append(':').append(scope.name());
        entries.forEach(entry -> identity.append(':').append(entry.id()));
        Instant time = entries.stream().map(Entry::issuedAt).max(Instant::compareTo)
                .orElseGet(() -> month.atDay(1).atStartOfDay(DocumentFormat.ZONE).toInstant());
        byte[] content;
        try (PdfDocument pdf = PdfDocument.create(PageSize.A4, identity.toString().getBytes(StandardCharsets.UTF_8),
                time)) {
            if (entries.isEmpty()) {
                pdf.text(TextBlock.strong(texts.text("print.empty", Map.of("month", DocumentFormat.month(month))),
                        12f, Align.CENTER));
                pdf.text(TextBlock.plain(texts.text("print.emptyScope." + scope.name()), 9.5f));
            }
            boolean first = true;
            for (int from = 0; from < entries.size(); from += CHUNK) {
                List<Entry> chunk = entries.subList(from, Math.min(entries.size(), from + CHUNK));
                Map<UUID, InvoiceRecord> records = invoices.findAllById(chunk.stream().map(Entry::id).toList())
                        .stream().collect(Collectors.toMap(InvoiceRecord::getId, record -> record));
                for (Entry entry : chunk) {
                    InvoiceRecord record = Objects.requireNonNull(records.get(entry.id()), "invoice");
                    InvoiceDocument document = snapshots.open(tenantId, record.getId(), record.getSnapshot());
                    append(pdf, document, texts, first);
                    first = false;
                }
                entityManager.clear();
            }
            content = pdf.toBytes();
        }
        return new Invoices.File(fileName(month, scope), content);
    }

    /**
     * Draws one invoice of the set: from the top of a new page, the first one on the page the document starts with.
     * An invoice fits on its page ({@link InvoiceLayout}); one that cannot goes on on the next with its continuation
     * header.
     */
    static void append(PdfDocument pdf, InvoiceDocument invoice, DocumentTexts texts, boolean first) {
        if (!first) {
            pdf.newPage();
        }
        InvoiceLayout.draw(pdf, invoice, texts);
    }

    /** {@code invoices_2026-10_paper.pdf} / {@code invoices_2026-10_all.pdf}. */
    static String fileName(YearMonth month, PrintScope scope) {
        return "invoices_" + month + "_" + scope.name().toLowerCase(Locale.ROOT) + ".pdf";
    }

    /** The latest versions of the month in the scope, in street and plot order. */
    private List<Entry> entries(YearMonth month, PrintScope scope) {
        Map<UUID, Entry> byPlot = new HashMap<>();
        for (Object[] row : invoices.latestOfMonth(month.atDay(1))) {
            byPlot.put((UUID) row[1], new Entry((UUID) row[0], (UUID) row[1], (Instant) row[2]));
        }
        if (byPlot.isEmpty()) {
            return List.of();
        }
        Set<UUID> chosen = scope == PrintScope.ALL ? byPlot.keySet() : onPaper(byPlot.keySet());
        if (chosen.isEmpty()) {
            return List.of();
        }
        List<Entry> ordered = new ArrayList<>();
        plots.inStreetOrder(chosen).forEach(plotId -> ordered.add(byPlot.get(plotId)));
        return ordered;
    }

    /** The plots with at least one current owner who receives invoices on paper. */
    private Set<UUID> onPaper(Set<UUID> plotIds) {
        Map<UUID, List<UUID>> owners = plotOwners.currentOwnerIds(plotIds);
        Map<UUID, DeliveryChannel> channels = members.deliveryChannels(owners.values().stream()
                .flatMap(List::stream).distinct().toList());
        return owners.entrySet().stream()
                .filter(entry -> entry.getValue().stream()
                        .anyMatch(owner -> channels.get(owner) == DeliveryChannel.PAPER))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }
}
