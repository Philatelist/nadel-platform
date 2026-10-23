package app.nadel.ledger.internal;

import app.nadel.audit.ActorNames;
import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import app.nadel.money.Money;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import nadel.contract.model.Charge;
import nadel.contract.model.ChargeAdjustmentTarget;
import nadel.contract.model.ChargeCalculation;
import nadel.contract.model.ChargeTypeCode;
import nadel.contract.model.ChargeTypeRef;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shapes charges for the API: the month they are booked in, their charge type, whether the viewer may reverse them, the
 * calculation stored with them and their links (reversal, adjustments, the charge an adjustment corrects, the
 * recalculation that booked them). The months and charge types of the cooperative and the names of the authors are
 * loaded once per call, whatever the number of charges. Runs inside the caller's transaction.
 */
@Component
class ChargeViews {

    private final BillingMonthRepository months;
    private final ChargeTypeCatalog chargeTypes;
    private final ActorNames actorNames;
    private final JsonMapper json;

    ChargeViews(BillingMonthRepository months, ChargeTypeCatalog chargeTypes, ActorNames actorNames, JsonMapper json) {
        this.months = months;
        this.chargeTypes = chargeTypes;
        this.actorNames = actorNames;
        this.json = json;
    }

    /**
     * The links of a charge within the ledger of its plot.
     *
     * @param reversedBy the reversal that cancelled the charge; null when none
     * @param adjustedBy the adjustments of the charge, oldest first
     * @param adjusts    the charge an adjustment corrects; null for every other charge
     */
    record Links(@Nullable UUID reversedBy, List<UUID> adjustedBy, @Nullable ChargeRecord adjusts) {

        static final Links NONE = new Links(null, List.of(), null);

        Links {
            adjustedBy = List.copyOf(adjustedBy);
        }
    }

    /** What the views of a set of charges need, loaded once. */
    record Context(Map<UUID, BillingMonthRecord> months, Map<UUID, ChargeTypeFacts> types,
            Map<UUID, String> authorNames, AccessProfile viewer) {
    }

    /**
     * Loads the months, the charge types and, for a viewer with {@code CHARGES_VIEW}, the current names of the members
     * who booked the charges.
     */
    Context context(Collection<ChargeRecord> charges, AccessProfile viewer) {
        Map<UUID, BillingMonthRecord> monthById = months.findAll().stream()
                .collect(Collectors.toMap(BillingMonthRecord::getId, Function.identity()));
        Map<UUID, ChargeTypeFacts> typeById = chargeTypes.all().stream()
                .collect(Collectors.toMap(ChargeTypeFacts::id, Function.identity()));
        Map<UUID, String> names = Map.of();
        if (viewer.can(Permission.CHARGES_VIEW)) {
            List<UUID> authors = charges.stream().map(ChargeRecord::getCreatedBy).filter(Objects::nonNull).distinct()
                    .toList();
            names = authors.isEmpty() ? Map.of() : actorNames.displayNames(authors);
        }
        return new Context(monthById, typeById, names, viewer);
    }

    /** A charge just booked, which has no links yet, as the viewer sees it. */
    Charge view(ChargeRecord charge, Context context) {
        return view(charge, Links.NONE, context);
    }

    /**
     * The charge as the viewer sees it, with its links. Reversals and adjustments are never reversible; the
     * recalculation that booked a charge is shown only to a viewer with {@code CHARGES_VIEW}.
     */
    Charge view(ChargeRecord charge, Links links, Context context) {
        BillingMonthRecord month = context.months().get(charge.getBillingMonthId());
        ChargeTypeFacts type = context.types().get(charge.getChargeTypeId());
        UUID reversedBy = links.reversedBy();
        boolean reversible = context.viewer().can(Permission.CHARGES_REVERSE) && charge.getKind() != ChargeKind.REVERSAL
                && charge.getKind() != ChargeKind.ADJUSTMENT && reversedBy == null && !month.isClosed();
        Charge dto = new Charge(charge.getId(), charge.getPlotId(),
                nadel.contract.model.ChargeKind.fromValue(charge.getKind().name()),
                nadel.contract.model.ChargeSource.fromValue(charge.getSource().name()), typeRef(type),
                charge.getAmount().toString(), month.yearMonth().toString(), month.isClosed(), charge.getServiceFrom(),
                charge.getServiceTo(), charge.getAccruedOn(), reversible, calculation(charge),
                charge.getCreatedAt().atOffset(ZoneOffset.UTC));
        dto.setReason(charge.getReason());
        dto.setReverses(charge.getReversesChargeId());
        dto.setReversedBy(reversedBy);
        ChargeRecord adjusted = links.adjusts();
        if (adjusted != null) {
            dto.setAdjusts(new ChargeAdjustmentTarget(adjusted.getId(),
                    context.months().get(adjusted.getBillingMonthId()).yearMonth().toString(),
                    adjusted.getServiceFrom(), adjusted.getServiceTo()));
        }
        // the generated model starts with an empty list, which non_null inclusion would still write: absent when none
        dto.setAdjustedBy(links.adjustedBy().isEmpty() ? null : links.adjustedBy());
        if (charge.getRecalculationId() != null && context.viewer().can(Permission.CHARGES_VIEW)) {
            dto.setRecalculationId(charge.getRecalculationId());
        }
        if (charge.getCreatedBy() != null) {
            dto.setCreatedByName(context.authorNames().get(charge.getCreatedBy()));
        }
        return dto;
    }

    /** The stored calculation; a charge stored without one shows its amount with no parts and no readings. */
    private ChargeCalculation calculation(ChargeRecord charge) {
        Map<String, Object> stored = charge.getCalculation();
        if (stored == null) {
            return new ChargeCalculation(charge.getAmount().toString(), List.of(), List.of());
        }
        return json.convertValue(stored, ChargeCalculation.class);
    }

    static ChargeTypeRef typeRef(ChargeTypeFacts type) {
        ChargeTypeRef ref = new ChargeTypeRef(type.id(), type.name(), type.nameCustomized());
        if (type.code() != null) {
            ref.setCode(ChargeTypeCode.fromValue(type.code()));
        }
        return ref;
    }

    /**
     * The calculation stored with a charge that has no parts and no readings (an opening balance, a reversal): its
     * amount only.
     */
    static Map<String, Object> plainCalculation(Money amount) {
        return Map.of("amount", amount.toString(), "parts", List.of(), "readings", List.of());
    }
}
