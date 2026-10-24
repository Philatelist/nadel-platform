package app.nadel.ledger.internal;

import app.nadel.audit.ActorNames;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import nadel.contract.model.ChargeTypeCode;
import nadel.contract.model.ChargeTypeTotal;
import nadel.contract.model.Recalculation;
import nadel.contract.model.RecalculationAction;
import nadel.contract.model.RecalculationCauseKind;
import nadel.contract.model.RecalculationCauseType;
import nadel.contract.model.RecalculationChangeReason;
import nadel.contract.model.RecalculationLine;
import nadel.contract.model.RecalculationPage;
import nadel.contract.model.RecalculationScope;
import nadel.contract.model.RecalculationSummary;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The booked recalculations ({@code GET /recalculations} and {@code GET /recalculations/{id}}, feature 012 §2.7): the
 * list newest first and one record with its lines, as stored. Read-only; the plots, charge types, months, created
 * charges and author names are loaded once per call. The controller requires {@code CHARGES_VIEW}; RLS keeps other
 * cooperatives' records out, so an unknown id and one of another cooperative are the same 404.
 */
@Service
class RecalculationQueries {

    private final RecalculationRepository recalculations;
    private final RecalculationLineRepository lines;
    private final ChargeRepository charges;
    private final BillingMonthRepository months;
    private final PlotCatalog plots;
    private final ChargeTypeCatalog chargeTypes;
    private final ActorNames actorNames;

    RecalculationQueries(RecalculationRepository recalculations, RecalculationLineRepository lines,
            ChargeRepository charges, BillingMonthRepository months, PlotCatalog plots, ChargeTypeCatalog chargeTypes,
            ActorNames actorNames) {
        this.recalculations = recalculations;
        this.lines = lines;
        this.charges = charges;
        this.months = months;
        this.plots = plots;
        this.chargeTypes = chargeTypes;
        this.actorNames = actorNames;
    }

    @Transactional(readOnly = true)
    RecalculationPage list(int page, int size) {
        Page<RecalculationRecord> found = recalculations.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(page, size));
        Map<UUID, String> names = names(found.getContent());
        List<RecalculationSummary> items = found.getContent().stream().map(record -> {
            RecalculationSummary summary = new RecalculationSummary(record.getId(), cause(record), record.getReason(),
                    record.getTotal().toString(), record.getLineCount(), record.getPlotCount(),
                    record.getCreatedAt().atOffset(ZoneOffset.UTC));
            summary.setCauseType(causeType(record));
            summary.setCreatedByName(record.getCreatedBy() == null ? null : names.get(record.getCreatedBy()));
            return summary;
        }).toList();
        return new RecalculationPage(items, found.getNumber(), found.getSize(), found.getTotalElements(),
                found.getTotalPages());
    }

    /** @throws ApiProblemException 404 {@code NOT_FOUND} for an unknown recalculation or one of another cooperative */
    @Transactional(readOnly = true)
    Recalculation details(UUID id) {
        RecalculationRecord record = recalculations.findById(id).orElseThrow(ApiProblemException::notFound);
        List<RecalculationLineRecord> stored = lines.findByRecalculationIdOrderByPositionAsc(id);

        Map<UUID, PlotFacts> plotById = plots.findAll(stored.stream().map(RecalculationLineRecord::getPlotId)
                .collect(Collectors.toSet()));
        Map<UUID, ChargeTypeFacts> typeById = chargeTypes.all().stream()
                .collect(Collectors.toMap(ChargeTypeFacts::id, Function.identity()));
        Map<UUID, BillingMonthRecord> monthById = months.findAll().stream()
                .collect(Collectors.toMap(BillingMonthRecord::getId, Function.identity()));
        Set<UUID> createdIds = new HashSet<>();
        stored.forEach(line -> createdIds.addAll(created(line)));
        Map<UUID, ChargeRecord> createdById = createdIds.isEmpty() ? Map.of() : charges.findAllById(createdIds).stream()
                .collect(Collectors.toMap(ChargeRecord::getId, Function.identity()));

        List<RecalculationLine> shown = stored.stream()
                .map(line -> line(line, plotById, typeById, monthById, createdById)).toList();
        Recalculation result = new Recalculation(false, true, record.getCause() != RecalculationRecord.Cause.MANUAL,
                cause(record), scope(record.getScope()), shown, totals(stored, typeById),
                record.getTotal().toString(), record.getPlotCount(), List.of(), record.getDigest());
        result.setId(record.getId());
        result.setCauseType(causeType(record));
        result.setReason(record.getReason());
        result.setCreatedAt(record.getCreatedAt().atOffset(ZoneOffset.UTC));
        if (record.getCreatedBy() != null) {
            result.setCreatedByName(names(List.of(record)).get(record.getCreatedBy()));
        }
        return result;
    }

    private static RecalculationLine line(RecalculationLineRecord line, Map<UUID, PlotFacts> plotById,
            Map<UUID, ChargeTypeFacts> typeById, Map<UUID, BillingMonthRecord> monthById,
            Map<UUID, ChargeRecord> createdById) {
        Money booked = line.getBookedAmount();
        Money adjustments = line.getAdjustedSoFar();
        RecalculationLine dto = new RecalculationLine(line.getPlotId(), plotById.get(line.getPlotId()).number(),
                ChargeViews.typeRef(typeById.get(line.getChargeTypeId())), line.getServiceFrom(), line.getServiceTo(),
                booked.toString(), adjustments.toString(), booked.plus(adjustments).toString(),
                line.getExpectedAmount().toString(), line.getDifference().toString(),
                RecalculationAction.fromValue(line.getAction().name()),
                line.getReasons().stream().map(RecalculationChangeReason::fromValue).toList(), created(line));
        dto.setOriginalChargeId(line.getOriginalChargeId());
        dto.setRunId(line.getRunId());
        dto.setReversalChargeId(line.getReversalChargeId());
        dto.setNewChargeId(line.getNewChargeId());
        dto.setAdjustmentChargeId(line.getAdjustmentChargeId());
        // the month of what the line booked: the adjustment, else the new charge, else the reversal
        created(line).reversed().stream().map(createdById::get).filter(Objects::nonNull).findFirst()
                .ifPresent(charge -> dto.setBookedIn(monthById.get(charge.getBillingMonthId()).yearMonth().toString()));
        return dto;
    }

    /** The charges the line booked: reversal, new charge, adjustment, in that order. */
    private static List<UUID> created(RecalculationLineRecord line) {
        return Stream.of(line.getReversalChargeId(), line.getNewChargeId(), line.getAdjustmentChargeId())
                .filter(Objects::nonNull).toList();
    }

    /** The differences per charge type, in the order of the charge types (position, then id). */
    private static List<ChargeTypeTotal> totals(List<RecalculationLineRecord> stored,
            Map<UUID, ChargeTypeFacts> typeById) {
        Map<UUID, Money> byType = new LinkedHashMap<>();
        stored.forEach(line -> byType.merge(line.getChargeTypeId(), line.getDifference(), Money::plus));
        return totals(byType, typeById);
    }

    /** The differences per charge type given, in the order of the charge types (position, then id). */
    static List<ChargeTypeTotal> totals(Map<UUID, Money> byType, Map<UUID, ChargeTypeFacts> typeById) {
        List<ChargeTypeFacts> ordered = new ArrayList<>(byType.keySet().stream().map(typeById::get).toList());
        ordered.sort(Comparator.comparingInt(ChargeTypeFacts::position).thenComparing(ChargeTypeFacts::id));
        return ordered.stream().map(type -> {
            ChargeTypeTotal total = new ChargeTypeTotal(type.id(), type.name(), type.nameCustomized(),
                    byType.get(type.id()).toString());
            if (type.code() != null) {
                total.setChargeTypeCode(ChargeTypeCode.fromValue(type.code()));
            }
            return total;
        }).toList();
    }

    /** The stored scope (ids and codes under the keys of the contract's {@code RecalculationScope}). */
    static RecalculationScope scope(Map<String, Object> stored) {
        RecalculationScope scope = new RecalculationScope(strings(stored.get("months")),
                strings(stored.get("chargeTypeIds")).stream().map(UUID::fromString).toList(),
                strings(stored.get("plotIds")).stream().map(UUID::fromString).toList());
        scope.setTariffId(uuid(stored.get("tariffId")));
        scope.setTariffVersionId(uuid(stored.get("tariffVersionId")));
        scope.setMeterId(uuid(stored.get("meterId")));
        scope.setReadingId(uuid(stored.get("readingId")));
        return scope;
    }

    private static List<String> strings(@Nullable Object value) {
        if (value instanceof Collection<?> items) {
            return items.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static @Nullable UUID uuid(@Nullable Object value) {
        return value == null ? null : UUID.fromString(value.toString());
    }

    private static RecalculationCauseKind cause(RecalculationRecord record) {
        return RecalculationCauseKind.fromValue(record.getCause().name());
    }

    /** The cause type stored in the scope; absent for a record stored without one. */
    private static @Nullable RecalculationCauseType causeType(RecalculationRecord record) {
        Object type = record.getScope().get("causeType");
        return type == null ? null : RecalculationCauseType.fromValue(type.toString());
    }

    private Map<UUID, String> names(List<RecalculationRecord> records) {
        List<UUID> authors = records.stream().map(RecalculationRecord::getCreatedBy).filter(Objects::nonNull)
                .distinct().toList();
        return authors.isEmpty() ? Map.of() : actorNames.displayNames(authors);
    }
}
