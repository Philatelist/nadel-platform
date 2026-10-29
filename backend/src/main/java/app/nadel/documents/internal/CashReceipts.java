package app.nadel.documents.internal;

import app.nadel.audit.ActorNames;
import app.nadel.identity.AccessProfile;
import app.nadel.identity.OwnedPlot;
import app.nadel.identity.OwnedPlotsSource;
import app.nadel.identity.Permission;
import app.nadel.ledger.CashReceiptData;
import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.PaymentNotes;
import app.nadel.ledger.PaymentSource;
import app.nadel.members.MemberDirectory;
import app.nadel.members.MemberRef;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import app.nadel.settings.CooperativeSettings;
import app.nadel.settings.Requisites;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import app.nadel.tenancy.TenantDirectory;
import app.nadel.tenancy.TenantSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The printed cash receipt of a payment ({@code GET /payments/{id}/receipt.pdf}, feature 016 §2.8, technical
 * considerations 2.7): reads the payment group through {@link LedgerReadModel#cashReceipt}, names its plots, payer,
 * author and charge types through their modules, and draws it with {@link ReceiptLayout}. Nothing is stored; the same
 * payment printed at the same moment gives the same bytes.
 * <p>
 * Access: {@code PAYMENTS_VIEW}, or a member who owns every plot of the group now. A caller with neither permission
 * nor a plot of their own may print no receipt at all: 403 {@code PERMISSION_DENIED}, answered before the payment is
 * looked up. For a plot owner an unknown payment, one of another cooperative, a reversal row and a group with a plot
 * that is not theirs are the same 404 {@code NOT_FOUND}. A bank payment is 409 {@code RECEIPT_NOT_AVAILABLE} (after
 * the access check, so it says nothing to a member about another plot). The payer note is printed only for a viewer
 * with {@code MEMBERS_VIEW}. The platform operator has no cooperative data: 403.
 */
@Service
class CashReceipts {

    /** A receipt as a file. */
    record ReceiptFile(String fileName, byte[] content) {
    }

    private final LedgerReadModel ledger;
    private final PaymentNotes notes;
    private final PlotCatalog plots;
    private final MemberDirectory members;
    private final ActorNames actorNames;
    private final ChargeTypeCatalog chargeTypes;
    private final CooperativeSettings settings;
    private final TenantDirectory tenants;
    private final OwnedPlotsSource ownedPlots;
    private final Clock clock;

    CashReceipts(LedgerReadModel ledger, PaymentNotes notes, PlotCatalog plots, MemberDirectory members,
            ActorNames actorNames, ChargeTypeCatalog chargeTypes, CooperativeSettings settings,
            TenantDirectory tenants, OwnedPlotsSource ownedPlots, Clock clock) {
        this.ledger = ledger;
        this.notes = notes;
        this.plots = plots;
        this.members = members;
        this.actorNames = actorNames;
        this.chargeTypes = chargeTypes;
        this.settings = settings;
        this.tenants = tenants;
        this.ownedPlots = ownedPlots;
        this.clock = clock;
    }

    /** The receipt of the payment's group as the viewer may see it, printed now. */
    @Transactional(readOnly = true)
    ReceiptFile receipt(UUID paymentId, AccessProfile viewer) {
        if (viewer.platformOperator() || viewer.tenantId() == null) {
            throw denied();
        }
        Set<UUID> owned = null;
        if (!viewer.can(Permission.PAYMENTS_VIEW)) {
            owned = viewer.memberId() == null ? Set.of()
                    : ownedPlots.currentPlotsOf(viewer.memberId()).stream().map(OwnedPlot::plotId)
                            .collect(Collectors.toSet());
            if (owned.isEmpty()) {
                throw denied();
            }
        }
        CashReceiptData data = ledger.cashReceipt(paymentId).orElseThrow(ApiProblemException::notFound);
        if (owned != null && !data.parts().stream().map(CashReceiptData.Part::plotId).allMatch(owned::contains)) {
            throw ApiProblemException.notFound();
        }
        if (!printable(data)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.RECEIPT_NOT_AVAILABLE,
                    "A bank payment has no cash receipt.");
        }
        return file(data, viewer);
    }

    /**
     * The receipt of the payment's group for a caller that checked the access itself ({@code DocumentsFacade}): as a
     * viewer with {@code PAYMENTS_VIEW} but without {@code MEMBERS_VIEW}, so without the payer note. Empty for an
     * unknown payment, one of another cooperative, a reversal row and a bank payment.
     */
    @Transactional(readOnly = true)
    Optional<ReceiptFile> receipt(UUID paymentId, UUID tenantId) {
        AccessProfile system = new AccessProfile(tenantId, null, DocumentsFacadeService.SYSTEM, "",
                Set.of(Permission.PAYMENTS_VIEW), List.of(), false);
        return ledger.cashReceipt(paymentId).filter(CashReceipts::printable).map(data -> file(data, system));
    }

    private static boolean printable(CashReceiptData data) {
        return data.source() == PaymentSource.CASH && data.receiptNumber() != null;
    }

    /** The receipt drawn now. */
    private ReceiptFile file(CashReceiptData data, AccessProfile viewer) {
        Instant now = clock.instant();
        ReceiptContent content = content(data, viewer, DocumentFormat.dayOf(now));
        return new ReceiptFile("receipt_" + content.number() + ".pdf", ReceiptLayout.render(content, now));
    }

    /** Names everything the receipt shows. */
    private ReceiptContent content(CashReceiptData data, AccessProfile viewer, LocalDate today) {
        List<UUID> plotIds = data.parts().stream().map(CashReceiptData.Part::plotId).distinct().toList();
        Map<UUID, PlotFacts> plotFacts = plots.findAll(plotIds);
        List<UUID> order = plots.inStreetOrder(plotIds);
        List<CashReceiptData.Part> parts = data.parts().stream()
                .sorted(Comparator.comparingInt((CashReceiptData.Part part) -> position(order, part.plotId())))
                .toList();
        Map<UUID, ChargeTypeFacts> types = chargeTypes.all().stream()
                .collect(Collectors.toMap(ChargeTypeFacts::id, type -> type));

        List<ReceiptContent.Part> rows = new ArrayList<>();
        List<ReceiptContent.PlacementLine> placements = new ArrayList<>();
        DocumentTexts texts = DocumentTexts.uk();
        for (CashReceiptData.Part part : parts) {
            String number = plotFacts.get(part.plotId()).number();
            LocalDate reversedOn = part.reversedAt() == null ? null : DocumentFormat.dayOf(part.reversedAt());
            rows.add(new ReceiptContent.Part(number, part.amount(), reversedOn));
            if (reversedOn != null) {
                placements.add(new ReceiptContent.PlacementLine(number,
                        texts.text("receipt.reversedPart", Map.of("date", DocumentFormat.day(reversedOn))), null));
                continue;
            }
            for (CashReceiptData.Placement placement : part.placements()) {
                String type = typeName(types.get(placement.chargeTypeId()), texts);
                String label = placement.opening() ? texts.text("receipt.opening", Map.of("type", type))
                        : texts.text("receipt.charge", Map.of("type", type, "period",
                                DocumentFormat.period(placement.serviceFrom(), placement.serviceTo())));
                placements.add(new ReceiptContent.PlacementLine(number, label, placement.amount()));
            }
            if (part.unallocated().signum() > 0) {
                placements.add(new ReceiptContent.PlacementLine(number, texts.text("receipt.unallocated"),
                        part.unallocated()));
            }
        }
        LocalDate reversedOn = data.fullyReversed() ? parts.stream().map(CashReceiptData.Part::reversedAt)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).map(DocumentFormat::dayOf).orElseThrow()
                : null;
        return new ReceiptContent(requisites(), Objects.requireNonNull(data.receiptNumber()), data.receivedOn(),
                payerName(data.payerMemberId()), payerNote(parts, viewer), rows, placements, data.total(), today,
                acceptedBy(data.enteredBy()), reversedOn);
    }

    private ReceiptContent.Requisites requisites() {
        Requisites requisites = settings.requisites();
        TenantSummary tenant = tenants.current().orElseThrow(() -> new IllegalStateException("No cooperative"));
        String name = requisites.officialName() == null || requisites.officialName().isBlank() ? tenant.name()
                : requisites.officialName();
        return new ReceiptContent.Requisites(name, tenant.edrpou(), requisites.legalAddress(), requisites.iban(),
                requisites.bankName());
    }

    private @Nullable String payerName(@Nullable UUID payerMemberId) {
        if (payerMemberId == null) {
            return null;
        }
        MemberRef payer = members.find(List.of(payerMemberId)).get(payerMemberId);
        if (payer == null) {
            return null;
        }
        return payer.middleName() == null || payer.middleName().isBlank() ? payer.shortName()
                : payer.shortName() + " " + payer.middleName();
    }

    /** The payer note of the first part that has one, only for a viewer who may view members. */
    private @Nullable String payerNote(List<CashReceiptData.Part> parts, AccessProfile viewer) {
        if (!viewer.can(Permission.MEMBERS_VIEW)) {
            return null;
        }
        Map<UUID, String> byPayment = notes.notes(parts.stream().map(CashReceiptData.Part::paymentId).toList());
        return parts.stream().map(part -> byPayment.get(part.paymentId())).filter(Objects::nonNull).findFirst()
                .orElse(null);
    }

    private @Nullable String acceptedBy(@Nullable UUID enteredBy) {
        return enteredBy == null ? null : actorNames.displayNames(List.of(enteredBy)).get(enteredBy);
    }

    /** The name of a charge type: its own when renamed or custom, otherwise the Ukrainian default of its code. */
    static String typeName(ChargeTypeFacts type, DocumentTexts texts) {
        return type.code() != null && !type.nameCustomized()
                ? texts.text("chargeTypes.defaultNames." + type.code()) : type.name();
    }

    private static int position(List<UUID> order, UUID plotId) {
        int index = order.indexOf(plotId);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private static ApiProblemException denied() {
        return new ApiProblemException(HttpStatus.FORBIDDEN, ProblemCodes.PERMISSION_DENIED,
                "The caller may print no receipt.");
    }
}
