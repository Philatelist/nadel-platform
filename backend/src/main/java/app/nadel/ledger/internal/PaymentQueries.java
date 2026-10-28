package app.nadel.ledger.internal;

import app.nadel.audit.ActorNames;
import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import app.nadel.members.MemberDirectory;
import app.nadel.members.MemberRef;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import nadel.contract.model.PaymentDetails;
import nadel.contract.model.PaymentGroupPart;
import nadel.contract.model.PaymentPage;
import nadel.contract.model.PaymentReversalInfo;
import nadel.contract.model.PaymentSource;
import nadel.contract.model.PaymentSummary;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads payments for the API ({@code GET /payments}, {@code GET /payments/{id}}, feature 015 §2.4): the list, newest
 * first, with what each payment pays on charges now, what is left of it and the size of its group (the parts of a split
 * payment), and the card with the allocations and releases, the other parts of its group and the reversal. The payer note is opened only for a viewer who may view members
 * ({@code MEMBERS_VIEW}). The callers check {@code PAYMENTS_VIEW}. Read-only transactions; RLS hides other
 * cooperatives' rows.
 */
@Service
class PaymentQueries {

    // the filters are switched off by their flag, so every parameter is always bound with a value of its type
    private static final String FILTER = """
            from payment p join billing_month m on m.id = p.billing_month_id
            where p.kind = 'PAYMENT'
              and (:anyFrom or p.received_on >= :from)
              and (:anyTo or p.received_on <= :to)
              and (:anySource or p.source = :source)
              and (:anyPlot or p.plot_id = :plotId)
              and (:anyMonth or m.month = :month)
            """;
    private static final String PAGE = "select p.id " + FILTER
            + " order by p.received_on desc, p.created_at desc, p.id desc limit :limit offset :offset";
    private static final String COUNT = "select count(*) " + FILTER;
    private static final LocalDate NO_DATE = LocalDate.of(2000, 1, 1);
    private static final UUID NO_ID = new UUID(0, 0);

    private final EntityManager entityManager;
    private final PaymentRepository payments;
    private final AllocationRepository allocations;
    private final PlotCatalog plots;
    private final MemberDirectory members;
    private final ActorNames actorNames;
    private final PaymentNoteCipher notes;
    private final PaymentViews views;

    PaymentQueries(EntityManager entityManager, PaymentRepository payments, AllocationRepository allocations,
            PlotCatalog plots, MemberDirectory members, ActorNames actorNames, PaymentNoteCipher notes,
            PaymentViews views) {
        this.entityManager = entityManager;
        this.payments = payments;
        this.allocations = allocations;
        this.plots = plots;
        this.members = members;
        this.actorNames = actorNames;
        this.notes = notes;
        this.views = views;
    }

    /** A page of the payments matching the filters (each optional), newest first. */
    @Transactional(readOnly = true)
    PaymentPage list(@Nullable LocalDate from, @Nullable LocalDate to, @Nullable PaymentSource source,
            @Nullable UUID plotId, @Nullable String month, int page, int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("from", ProblemCodes.INVALID)));
        }
        LocalDate monthStart = month == null ? null : YearMonth.parse(month).atDay(1);
        long total = ((Number) bind(entityManager.createNativeQuery(COUNT), from, to, source, plotId, monthStart)
                .getSingleResult()).longValue();
        List<?> rows = bind(entityManager.createNativeQuery(PAGE), from, to, source, plotId, monthStart)
                .setParameter("limit", size)
                .setParameter("offset", (long) page * size)
                .getResultList();
        List<UUID> ids = rows.stream().map(UUID.class::cast).toList();
        List<PaymentSummary> items = summaries(ids);
        return new PaymentPage(items, page, size, total, (int) ((total + size - 1) / size));
    }

    private static jakarta.persistence.Query bind(jakarta.persistence.Query query, @Nullable LocalDate from,
            @Nullable LocalDate to, @Nullable PaymentSource source, @Nullable UUID plotId,
            @Nullable LocalDate monthStart) {
        return query.setParameter("anyFrom", from == null).setParameter("from", from == null ? NO_DATE : from)
                .setParameter("anyTo", to == null).setParameter("to", to == null ? NO_DATE : to)
                .setParameter("anySource", source == null)
                .setParameter("source", source == null ? "" : source.getValue())
                .setParameter("anyPlot", plotId == null).setParameter("plotId", plotId == null ? NO_ID : plotId)
                .setParameter("anyMonth", monthStart == null)
                .setParameter("month", monthStart == null ? NO_DATE : monthStart);
    }

    private List<PaymentSummary> summaries(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, PaymentRecord> byId = payments.findAllById(ids).stream()
                .collect(Collectors.toMap(PaymentRecord::getId, Function.identity()));
        Map<UUID, Money> allocated = netByPayment(allocations.findByPaymentIdIn(ids));
        Set<UUID> reversed = payments.findByReversesPaymentIdIn(ids).stream()
                .map(PaymentRecord::getReversesPaymentId).collect(Collectors.toSet());
        Map<UUID, PlotFacts> plotFacts = plots.findAll(byId.values().stream().map(PaymentRecord::getPlotId)
                .collect(Collectors.toSet()));
        Map<UUID, MemberRef> payers = payers(byId.values());
        Map<UUID, Integer> groupSizes = new HashMap<>();
        payments.countByGroup(PaymentKind.PAYMENT, byId.values().stream().map(PaymentRecord::getGroupId)
                .collect(Collectors.toSet()))
                .forEach(row -> groupSizes.put((UUID) row[0], ((Number) row[1]).intValue()));
        PaymentViews.Context context = views.context(List.of());
        List<PaymentSummary> items = new ArrayList<>();
        for (UUID id : ids) {
            PaymentRecord payment = byId.get(id);
            boolean isReversed = reversed.contains(id);
            Money net = allocated.getOrDefault(id, Money.ZERO);
            PaymentSummary item = new PaymentSummary(id, payment.getGroupId(), payment.getReceivedOn(),
                    PaymentViews.month(context, payment.getBillingMonthId()), payment.getPlotId(),
                    plotFacts.get(payment.getPlotId()).number(), PaymentSource.fromValue(payment.getSource().name()),
                    payment.getAmount().toString(), net.toString(), left(payment, net, isReversed).toString(),
                    isReversed);
            item.setGroupSize(groupSizes.getOrDefault(payment.getGroupId(), 1));
            item.setReceiptNumber(payment.getReceiptNumber());
            item.setBankReference(payment.getBankReference());
            item.setPayer(PaymentViews.payer(payment.getPayerMemberId(), payers));
            items.add(item);
        }
        return items;
    }

    /**
     * The payment with its allocations and releases (oldest first), the other parts of its group, its reversal, the
     * author's current name, whether the viewer may reverse it ({@code PAYMENTS_REVERSE}) or reallocate it
     * ({@code PAYMENTS_ENTER}) now (both only while it is not reversed) and, for a viewer with {@code MEMBERS_VIEW},
     * the payer note. An unknown payment, one of
     * another cooperative and the id of a reversal row are 404 {@code NOT_FOUND}.
     */
    @Transactional(readOnly = true)
    PaymentDetails details(UUID id, AccessProfile viewer) {
        PaymentRecord payment = payments.findById(id).filter(p -> p.getKind() == PaymentKind.PAYMENT)
                .orElseThrow(ApiProblemException::notFound);
        UUID tenantId = Objects.requireNonNull(viewer.tenantId());
        List<AllocationRecord> rows = allocations.findByPaymentId(id).stream()
                .sorted(Comparator.comparing(AllocationRecord::getCreatedAt)
                        .thenComparing(row -> row.getAmount().signum() < 0)
                        .thenComparing(row -> row.getId().toString()))
                .toList();
        List<PaymentRecord> group = payments.findByGroupId(payment.getGroupId());
        Set<UUID> reversedInGroup = group.stream().map(PaymentRecord::getReversesPaymentId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        PaymentRecord reversal = group.stream().filter(p -> id.equals(p.getReversesPaymentId())).findFirst()
                .orElseGet(() -> payments.findByReversesPaymentId(id).orElse(null));
        List<PaymentRecord> parts = group.stream()
                .filter(p -> p.getKind() == PaymentKind.PAYMENT && !p.getId().equals(id))
                .sorted(Comparator.comparing(PaymentRecord::getCreatedAt).thenComparing(p -> p.getId().toString()))
                .toList();
        Set<UUID> plotIds = new HashSet<>();
        plotIds.add(payment.getPlotId());
        parts.forEach(part -> plotIds.add(part.getPlotId()));
        Map<UUID, PlotFacts> plotFacts = plots.findAll(plotIds);
        List<UUID> authors = Stream.of(payment.getCreatedBy(), reversal == null ? null : reversal.getCreatedBy())
                .filter(Objects::nonNull).distinct().toList();
        Map<UUID, String> names = authors.isEmpty() ? Map.of() : actorNames.displayNames(authors);
        PaymentViews.Context context = views.context(rows.stream().map(AllocationRecord::getChargeId).distinct()
                .toList());

        boolean isReversed = reversal != null;
        Money net = rows.stream().map(AllocationRecord::getAmount).reduce(Money.ZERO, Money::plus);
        PaymentDetails dto = new PaymentDetails(id, payment.getGroupId(), payment.getPlotId(),
                plotFacts.get(payment.getPlotId()).number(), PaymentSource.fromValue(payment.getSource().name()),
                payment.getAmount().toString(), payment.getReceivedOn(),
                PaymentViews.month(context, payment.getBillingMonthId()), net.toString(),
                left(payment, net, isReversed).toString(), isReversed,
                rows.stream().map(row -> PaymentViews.allocation(row, context)).toList(),
                parts.stream().map(part -> new PaymentGroupPart(part.getId(), part.getPlotId(),
                        plotFacts.get(part.getPlotId()).number(), part.getAmount().toString(),
                        reversedInGroup.contains(part.getId()))).toList(),
                payment.getCreatedAt().atOffset(ZoneOffset.UTC),
                !isReversed && viewer.can(Permission.PAYMENTS_REVERSE),
                !isReversed && viewer.can(Permission.PAYMENTS_ENTER));
        dto.setReceiptNumber(payment.getReceiptNumber());
        dto.setBankReference(payment.getBankReference());
        dto.setPayer(PaymentViews.payer(payment.getPayerMemberId(), payers(List.of(payment))));
        if (viewer.can(Permission.MEMBERS_VIEW)) {
            dto.setPayerNote(notes.open(tenantId, id, payment.getPayerNoteEnc()));
        }
        if (payment.getCreatedBy() != null) {
            dto.setCreatedByName(names.get(payment.getCreatedBy()));
        }
        if (reversal != null) {
            PaymentReversalInfo info = new PaymentReversalInfo(reversal.getId(),
                    Objects.requireNonNull(reversal.getReason()),
                    PaymentViews.month(context, reversal.getBillingMonthId()),
                    reversal.getCreatedAt().atOffset(ZoneOffset.UTC));
            if (reversal.getCreatedBy() != null) {
                info.setCreatedByName(names.get(reversal.getCreatedBy()));
            }
            dto.setReversedBy(info);
        }
        return dto;
    }

    /** What is left of a payment on no charge: amount minus allocated; {@code 0.00} once reversed. */
    private static Money left(PaymentRecord payment, Money allocated, boolean reversed) {
        return reversed ? Money.ZERO : payment.getAmount().minus(allocated);
    }

    private static Map<UUID, Money> netByPayment(Collection<AllocationRecord> rows) {
        Map<UUID, Money> net = new HashMap<>();
        rows.forEach(row -> net.merge(row.getPaymentId(), row.getAmount(), Money::plus));
        return net;
    }

    private Map<UUID, MemberRef> payers(Collection<PaymentRecord> rows) {
        Set<UUID> ids = rows.stream().map(PaymentRecord::getPayerMemberId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : members.find(ids);
    }
}
