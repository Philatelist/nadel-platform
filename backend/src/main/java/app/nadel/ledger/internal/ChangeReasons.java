package app.nadel.ledger.internal;

import app.nadel.ledger.internal.ExpectedCharges.Charge;
import app.nadel.ledger.internal.ExpectedCharges.Expected;
import app.nadel.ledger.internal.ExpectedCharges.NotCharged;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import nadel.contract.model.RecalculationChangeReason;
import org.jspecify.annotations.Nullable;

/**
 * Why the amount of a recalculation line changed (feature 012, functional spec 2.2): the codes of the contract's
 * {@code RecalculationChangeReason}, worked out by comparing the booked charge's stored calculation with the expected
 * one (both the {@code ChargeCalculation} of the contract, codes and numbers only). Pure.
 * <ul>
 * <li>{@code TARIFF_VERSION}: the versions behind the charge or their rates differ, or the days a version counts for
 * (not a water charge);</li>
 * <li>{@code WATER_CONNECTION}: the same versions count for other days of a water charge, or a water interval charges
 * another quantity from the same readings;</li>
 * <li>{@code READING}: the readings of the interval differ;</li>
 * <li>{@code AREA}: the sotkas charged differ;</li>
 * <li>{@code RUN}: always on a line of a losses or common consumer run;</li>
 * <li>{@code NOT_CHARGED}: the expected charge cannot be calculated.</li>
 * </ul>
 * When a line has only one side (a new charge, a charge that should no longer exist) or the comparison finds nothing,
 * the cause of the recalculation names it: a tariff version cause {@code TARIFF_VERSION}, a reading cause
 * {@code READING}, a manual one {@code TARIFF_SCOPE} for a tariff charge and {@code METER_TREE} for an interval
 * ({@code WATER_CONNECTION} for both when the charge is water). A charge that belongs to another plot now (a meter
 * moved in the tree) is a charge that goes and a new one, so it is named by the cause too.
 */
final class ChangeReasons {

    private ChangeReasons() {
    }

    /**
     * @param live     the booked charge; null for a new charge
     * @param expected what 011 would do; null when it would charge nothing
     * @param water    whether the charge type is water
     * @return the reasons in the order of the contract's enum, never empty
     */
    static List<RecalculationChangeReason> of(ChargeIdentity identity, @Nullable BookedCharge live,
            @Nullable Expected expected, RecalculationRecord.Cause cause, boolean water) {
        EnumSet<RecalculationChangeReason> reasons = EnumSet.noneOf(RecalculationChangeReason.class);
        if (expected != null && expected.result() instanceof NotCharged) {
            reasons.add(RecalculationChangeReason.NOT_CHARGED);
        }
        if (identity.kind() == ChargeIdentity.Kind.RUN) {
            reasons.add(RecalculationChangeReason.RUN);
        }
        if (live != null && expected != null && expected.result() instanceof Charge charge) {
            compare(identity, live.calculation(), charge.calculation(), water, reasons);
        }
        if (reasons.isEmpty()) {
            reasons.add(fallback(identity, cause, water));
        }
        return List.copyOf(reasons);
    }

    private static void compare(ChargeIdentity identity, Map<String, Object> booked, Map<String, Object> expected,
            boolean water, EnumSet<RecalculationChangeReason> reasons) {
        boolean sameReadings = Objects.equals(booked.get("readings"), expected.get("readings"));
        if (!sameReadings) {
            reasons.add(RecalculationChangeReason.READING);
        }
        if (!column(booked, "rate").equals(column(expected, "rate"))) {
            // another version (added, deleted, moved) or another rate
            reasons.add(RecalculationChangeReason.TARIFF_VERSION);
            return;
        }
        if (!column(booked, "days").equals(column(expected, "days"))) {
            reasons.add(water ? RecalculationChangeReason.WATER_CONNECTION : RecalculationChangeReason.TARIFF_VERSION);
        }
        if (!column(booked, "quantity").equals(column(expected, "quantity"))) {
            if (column(expected, "unit").containsValue("SOTKA")) {
                reasons.add(RecalculationChangeReason.AREA);
            } else if (identity.kind() == ChargeIdentity.Kind.INTERVAL && sameReadings && water) {
                reasons.add(RecalculationChangeReason.WATER_CONNECTION);
            }
        }
    }

    /** One field of every part, by the part's tariff version (and its node, for a losses run), as text. */
    private static Map<String, String> column(Map<String, Object> calculation, String field) {
        Map<String, String> column = new LinkedHashMap<>();
        if (calculation.get("parts") instanceof List<?> parts) {
            for (Object part : parts) {
                if (part instanceof Map<?, ?> map) {
                    String key = map.get("tariffVersionId") + "|" + map.get("nodeMeterId");
                    column.merge(key, String.valueOf(map.get(field)), (a, b) -> a + "+" + b);
                }
            }
        }
        return column;
    }

    private static RecalculationChangeReason fallback(ChargeIdentity identity, RecalculationRecord.Cause cause,
            boolean water) {
        return switch (cause) {
            case TARIFF_VERSION -> RecalculationChangeReason.TARIFF_VERSION;
            case READING -> RecalculationChangeReason.READING;
            case MANUAL -> water ? RecalculationChangeReason.WATER_CONNECTION
                    : identity.kind() == ChargeIdentity.Kind.TARIFF ? RecalculationChangeReason.TARIFF_SCOPE
                            : RecalculationChangeReason.METER_TREE;
        };
    }
}
