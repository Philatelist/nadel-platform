package app.nadel.ledger.internal;

import app.nadel.ledger.InvoiceLedger;
import app.nadel.ledger.InvoiceLedger.LineKind;
import app.nadel.ledger.InvoiceLedger.LineSource;
import app.nadel.ledger.MonthStatus;
import app.nadel.ledger.internal.MonthMovements.AllocationEntry;
import app.nadel.ledger.internal.MonthMovements.ChargeEntry;
import app.nadel.ledger.internal.MonthMovements.ClosedMonth;
import app.nadel.ledger.internal.MonthMovements.PaymentEntry;
import app.nadel.money.Money;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads what the invoices of plots for a billing month need ({@code LedgerReadModel#invoiceLedgers}, feature 016
 * §2.3) and applies the booking-month rule ({@link MonthMovements}). Three parameterised native queries per call, in
 * the caller's transaction and under RLS: the billing months (month, status, closing time), the plots' charges booked
 * in or before the month (with the billing month and the source of the charge a correction corrects, and the stored
 * calculation as text), and the plots' payments and allocations together ({@code UNION ALL}, with the billing months
 * of each allocation's payment and charge, and each payment's receipt number and bank reference for the payment rows
 * booked in the month). Only rows created at or before {@code asOf} are read. The caller passes a
 * bounded number of plots (a chunk of a month build, or one plot); the plots go 500 per {@code IN} list.
 */
@Component
class InvoiceLedgerQueries {

    private static final int CHUNK = 500;

    private static final String MONTHS = """
            select b.id, b.month, b.status, b.closed_at
            from billing_month b
            """;

    /**
     * The charge a correction corrects is joined twice by its primary key, once per reference column (the reversed one
     * only when the charge adjusts none, as {@code coalesce(adjusts, reverses)}): each join condition is a plain
     * column equality, so it is an index condition on {@code charge_pkey}. A join on
     * {@code coalesce(c.adjusts_charge_id, c.reverses_charge_id)} is not leakproof under RLS, so PostgreSQL checks the
     * cooperative first and reads every charge of the cooperative for it, per chunk or per row (ADR 0017,
     * "Measurement").
     */
    private static final String CHARGES = """
            select c.id, c.plot_id, c.charge_type_id, c.billing_month_id, c.kind, c.source, c.amount,
                   c.service_from, c.service_to, cast(c.calculation as text) as calculation,
                   coalesce(ta.billing_month_id, tr.billing_month_id) as target_month_id,
                   coalesce(ta.source, tr.source) as target_source, c.created_at
            from charge c
            join billing_month b on b.id = c.billing_month_id
            left join charge ta on ta.id = c.adjusts_charge_id
            left join charge tr on tr.id = c.reverses_charge_id and c.adjusts_charge_id is null
            where c.plot_id in (:plotIds)
              and c.created_at <= :asOf
              and b.month <= :month
            order by c.plot_id, c.service_from, c.created_at, c.id
            """;

    private static final String MONEY = """
            select 'P' as row_kind, p.id, p.plot_id, cast(null as uuid) as charge_type_id, p.amount,
                   p.billing_month_id as payment_month_id, cast(null as uuid) as charge_month_id,
                   p.received_on, p.reverses_payment_id, p.created_at, p.receipt_number, p.bank_reference
            from payment p
            where p.plot_id in (:plotIds)
              and p.created_at <= :asOf
            union all
            select 'A' as row_kind, a.id, a.plot_id, c.charge_type_id, a.amount,
                   p.billing_month_id as payment_month_id, c.billing_month_id as charge_month_id,
                   cast(null as date) as received_on, cast(null as uuid) as reverses_payment_id, a.created_at,
                   cast(null as bigint) as receipt_number, cast(null as text) as bank_reference
            from allocation a
            join payment p on p.id = a.payment_id
            join charge c on c.id = a.charge_id
            where a.plot_id in (:plotIds)
              and a.created_at <= :asOf
            """;

    private final EntityManager entityManager;
    private final JsonMapper json;

    InvoiceLedgerQueries(EntityManager entityManager, JsonMapper json) {
        this.entityManager = entityManager;
        this.json = json;
    }

    /** The invoice ledgers of the plots; every distinct id is a key, in the order of first appearance. */
    @Transactional(propagation = Propagation.MANDATORY)
    Map<UUID, InvoiceLedger> find(Collection<UUID> plotIds, YearMonth month, Instant asOf) {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(asOf, "asOf");
        List<UUID> ids = plotIds.stream().distinct().toList();
        Map<UUID, InvoiceLedger> result = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        Map<UUID, YearMonth> monthById = new HashMap<>();
        List<ClosedMonth> closed = new ArrayList<>();
        MonthStatus status = null;
        Instant closedAt = null;
        for (Object row : entityManager.createNativeQuery(MONTHS).getResultList()) {
            Object[] v = (Object[]) row;
            YearMonth ym = YearMonth.from(Objects.requireNonNull(date(v[1])));
            monthById.put((UUID) v[0], ym);
            Instant closing = (Instant) v[3];
            boolean closedByAsOf = "CLOSED".equals(String.valueOf(v[2])) && closing != null
                    && !closing.isAfter(asOf);
            if (closedByAsOf) {
                closed.add(new ClosedMonth(ym, closing));
            }
            if (ym.equals(month)) {
                status = closedByAsOf ? MonthStatus.CLOSED : MonthStatus.OPEN;
                closedAt = closedByAsOf ? closing : null;
            }
        }

        Map<UUID, Rows> byPlot = new LinkedHashMap<>();
        ids.forEach(id -> byPlot.put(id, new Rows()));
        for (int from = 0; from < ids.size(); from += CHUNK) {
            List<UUID> chunk = ids.subList(from, Math.min(ids.size(), from + CHUNK));
            for (Object row : entityManager.createNativeQuery(CHARGES)
                    .setParameter("plotIds", chunk)
                    .setParameter("asOf", asOf)
                    .setParameter("month", month.atDay(1))
                    .getResultList()) {
                Object[] v = (Object[]) row;
                Rows rows = byPlot.get((UUID) v[1]);
                YearMonth bookedIn = monthById.get((UUID) v[3]);
                Money amount = money(v[6]);
                Instant createdAt = (Instant) v[12];
                rows.charges.add(new ChargeEntry((UUID) v[2], bookedIn, amount, createdAt));
                if (bookedIn.equals(month)) {
                    rows.lines.add(line(v, monthById, month));
                }
            }
            for (Object row : entityManager.createNativeQuery(MONEY)
                    .setParameter("plotIds", chunk)
                    .setParameter("asOf", asOf)
                    .getResultList()) {
                Object[] v = (Object[]) row;
                Rows rows = byPlot.get((UUID) v[2]);
                Money amount = money(v[4]);
                YearMonth paymentMonth = monthById.get((UUID) v[5]);
                Instant createdAt = (Instant) v[9];
                if ("P".equals(String.valueOf(v[0]))) {
                    rows.payments.add(new PaymentEntry((UUID) v[1], (UUID) v[8], paymentMonth,
                            Objects.requireNonNull(date(v[7])), amount, createdAt));
                    Long receipt = v[10] == null ? null : ((Number) v[10]).longValue();
                    rows.references.put((UUID) v[1], new Reference(receipt, (String) v[11]));
                } else {
                    rows.allocations.add(new AllocationEntry((UUID) v[3], paymentMonth, monthById.get((UUID) v[6]),
                            amount, createdAt));
                }
            }
        }
        MonthStatus monthStatus = status;
        Instant monthClosedAt = closedAt;
        byPlot.forEach((plotId, rows) -> {
            MonthMovements.Result figures = MonthMovements.of(month, asOf, closed, rows.charges, rows.allocations,
                    rows.payments);
            result.put(plotId, new InvoiceLedger(plotId, month, monthStatus, monthClosedAt, figures.types(),
                    rows.lines, figures.prepaymentAtEnd(), figures.receivedAfter(), monthPayments(month, asOf, rows)));
        });
        return result;
    }

    /**
     * The payment rows booked in the month ({@link MonthMovements#bookedIn}) with their receipt number or bank
     * reference; a reversal has neither of its own, so it carries those of the payment it reverses (a payment of the
     * same plot, created before it, so read with it).
     */
    private static List<InvoiceLedger.MonthPayment> monthPayments(YearMonth month, Instant asOf, Rows rows) {
        return MonthMovements.bookedIn(month, asOf, rows.payments).stream().map(payment -> {
            Reference reference = rows.references.get(payment.reverses() == null ? payment.id() : payment.reverses());
            return new InvoiceLedger.MonthPayment(payment.id(), payment.reverses(), payment.receivedOn(),
                    payment.amount(), reference == null ? null : reference.receiptNumber(),
                    reference == null ? null : reference.bankReference());
        }).toList();
    }

    /** A charge booked in the month with what its stored calculation says. */
    private InvoiceLedger.Line line(Object[] v, Map<UUID, YearMonth> monthById, YearMonth month) {
        LineKind kind = LineKind.valueOf((String) v[4]);
        String ownSource = (String) v[5];
        String source = v[11] != null ? (String) v[11] : ownSource;
        YearMonth targetMonth = v[10] == null ? null : monthById.get((UUID) v[10]);
        YearMonth corrects = targetMonth != null && targetMonth.isBefore(month) ? targetMonth : null;
        List<InvoiceLedger.Part> parts = new ArrayList<>();
        List<InvoiceLedger.Reading> readings = new ArrayList<>();
        String lossesPercent = null;
        String consumption = null;
        String consumerName = null;
        String calculation = (String) v[9];
        if (calculation != null) {
            JsonNode node = json.readTree(calculation);
            for (JsonNode part : node.path("parts")) {
                parts.add(new InvoiceLedger.Part(text(part, "basis"), text(part, "frequency"),
                        text(part, "quantity"), text(part, "unit"), text(part, "rate"), part.path("days").asInt(0),
                        LocalDate.parse(part.path("from").asString()), LocalDate.parse(part.path("to").asString())));
            }
            for (JsonNode reading : node.path("readings")) {
                readings.add(new InvoiceLedger.Reading(UUID.fromString(reading.path("meterId").asString()),
                        LocalDate.parse(reading.path("takenOn").asString()), reading.path("value").asString()));
            }
            lossesPercent = text(node, "lossesPercent");
            consumption = text(node, "consumption");
            consumerName = text(node, "consumerName");
        }
        return new InvoiceLedger.Line((UUID) v[0], (UUID) v[2], kind, lineSource(source), money(v[6]),
                Objects.requireNonNull(date(v[7])), Objects.requireNonNull(date(v[8])), corrects, parts, readings,
                lossesPercent, consumption, consumerName);
    }

    /**
     * The source of a line: the charge's own, a correction's that of the charge it corrects. A correction whose target
     * is not known keeps a tariff source (it has no calculation to describe).
     */
    private static LineSource lineSource(String source) {
        return switch (source) {
            case "CONSUMPTION" -> LineSource.CONSUMPTION;
            case "LOSSES" -> LineSource.LOSSES;
            case "COMMON_CONSUMER" -> LineSource.COMMON_CONSUMER;
            case "OPENING" -> LineSource.OPENING;
            default -> LineSource.TARIFF;
        };
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static @Nullable LocalDate date(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof java.sql.Date sql ? sql.toLocalDate() : (LocalDate) value;
    }

    private static Money money(Object value) {
        return Money.of((BigDecimal) value);
    }

    /** The receipt number and the bank reference of a payment row. */
    private record Reference(@Nullable Long receiptNumber, @Nullable String bankReference) {
    }

    /** The rows of one plot as they come from the queries. */
    private static final class Rows {
        private final Map<UUID, Reference> references = new HashMap<>();
        private final List<ChargeEntry> charges = new ArrayList<>();
        private final List<AllocationEntry> allocations = new ArrayList<>();
        private final List<PaymentEntry> payments = new ArrayList<>();
        private final List<InvoiceLedger.Line> lines = new ArrayList<>();
    }
}
