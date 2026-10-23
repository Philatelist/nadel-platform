package app.nadel.ledger.internal;

import app.nadel.money.Money;
import app.nadel.tariffs.Basis;
import app.nadel.tariffs.ChargeCalculator;
import app.nadel.tariffs.ChargeCalculator.Breakdown;
import app.nadel.tariffs.ChargeCalculator.Part;
import app.nadel.tariffs.DaySet;
import app.nadel.tariffs.TariffBook.Snapshot;
import app.nadel.tariffs.TariffBook.TariffEntry;
import app.nadel.tariffs.TariffResolver;
import app.nadel.tariffs.TariffResolver.Conflict;
import app.nadel.tariffs.TariffResolver.PlotFacts;
import app.nadel.tariffs.TariffResolver.Resolution;
import app.nadel.tariffs.TariffResolver.Single;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * What the month run does for one plot, one charge type and one month (feature 011 §2.3). Pure: no Spring, no
 * database, no clock; the month run and the retry of a queue item both call it, so they never disagree.
 * <p>
 * {@link TariffResolver} finds the tariffs of the type that apply to the plot over the month. A tariff's charge is
 * identified by the source key {@code TARIFF:{tariff}:{plot}:{YYYY-MM}}; when the key of any of them counts as charged
 * ({@link ChargeKeys}: a charge with it stands or was reversed by hand) there is nothing new ({@link AlreadyCharged}: a
 * rerun never books twice, never re-creates a charge reversed by hand, and a tariff that joins later does not turn a
 * charged month into a conflict; a charge only a recalculation reversed is planned again). Otherwise
 * {@link ChargeCalculator#breakdown} over the applicable days gives each tariff's amount, and <b>only the tariffs that
 * would charge more than {@code 0.00} take part</b> (money rules §4.1): exactly one is charged ({@link NewCharge}), two
 * or more are a conflict that is queued with those tariffs ({@link Conflicting}), none is {@link Nothing} ({@code 0.00},
 * a tariff charged by meter readings, no applicable day; also two one-time fees due in other months). So a one-time fee
 * is charged in its due month although a fee of the same type due in another month applies too. No tariff (none in
 * scope, excluded, inactive type, archived plot or common land): nothing, and nothing is queued (a plot outside a
 * membership tariff is not a "not charged" case).
 */
final class TariffMonthPlanning {

    private TariffMonthPlanning() {
    }

    /** The outcome for one plot, charge type and month. */
    sealed interface Outcome {
    }

    /** A new charge to book. */
    record NewCharge(PlannedCharge charge) implements Outcome {
    }

    /** The month's charge of one of the tariffs that apply counts as charged (it stands or was reversed by hand). */
    record AlreadyCharged(String sourceKey) implements Outcome {
    }

    /** Nothing to charge and nothing to queue. */
    record Nothing() implements Outcome {
    }

    /**
     * Two or more tariffs would charge the plot more than {@code 0.00}: none is charged, the case is queued with them.
     * Ordered by name.
     */
    record Conflicting(List<TariffEntry> tariffs) implements Outcome {
    }

    /**
     * A charge the run books.
     *
     * @param breakdown the amount (not zero) and the versions behind it
     */
    record PlannedCharge(UUID plotId, UUID chargeTypeId, TariffEntry tariff, YearMonth month, Breakdown breakdown,
            String sourceKey) {

        Money amount() {
            return breakdown.amount();
        }

        /** The first day the versions count for. */
        LocalDate serviceFrom() {
            return breakdown.parts().stream().map(Part::from).min(Comparator.naturalOrder()).orElseThrow();
        }

        /** The last day the versions count for. */
        LocalDate serviceTo() {
            return breakdown.parts().stream().map(Part::to).max(Comparator.naturalOrder()).orElseThrow();
        }

        /** The day the debt counts from: the due date of a one-time fee, otherwise the first day of the month. */
        LocalDate accruedOn() {
            return breakdown.parts().stream().filter(p -> p.terms().basis() == Basis.FIXED_ONE_TIME)
                    .map(p -> Objects.requireNonNull(p.terms().dueDate())).findFirst().orElse(month.atDay(1));
        }

        /** The version id of each part, in the order of the parts. */
        List<UUID> versionIds() {
            return breakdown.parts().stream()
                    .map(part -> tariff.versionStartingOn(part.terms().validFrom()).orElseThrow()).toList();
        }

        /**
         * The calculation stored with the charge: codes and numbers only (the {@code ChargeCalculation} of the
         * contract), one part per version with its basis, frequency, quantity (sotkas or one plot), rate, days and
         * the first and last day it counts for.
         */
        Map<String, Object> calculation() {
            List<UUID> ids = versionIds();
            List<Map<String, Object>> parts = new ArrayList<>();
            for (int i = 0; i < breakdown.parts().size(); i++) {
                Part part = breakdown.parts().get(i);
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("tariffVersionId", ids.get(i).toString());
                line.put("basis", part.terms().basis().name());
                line.put("frequency", part.terms().frequency().name());
                line.put("quantity", quantity(part));
                line.put("unit", part.terms().basis() == Basis.PER_AREA_SOTKA ? "SOTKA" : "PLOT");
                line.put("rate", part.terms().rate().toString());
                line.put("days", part.days());
                line.put("from", part.from().toString());
                line.put("to", part.to().toString());
                parts.add(line);
            }
            Map<String, Object> calculation = new LinkedHashMap<>();
            calculation.put("amount", amount().toString());
            calculation.put("parts", parts);
            calculation.put("readings", List.of());
            return calculation;
        }

        private static String quantity(Part part) {
            if (part.terms().basis() != Basis.PER_AREA_SOTKA) {
                return part.units().stripTrailingZeros().toPlainString();
            }
            BigDecimal sotkas = part.units().stripTrailingZeros();
            return sotkas.setScale(Math.max(2, sotkas.scale())).toPlainString();
        }
    }

    /** The source key of a tariff's charge for a plot and a month. */
    static String sourceKey(UUID tariffId, UUID plotId, YearMonth month) {
        return "TARIFF:" + tariffId + ":" + plotId + ":" + month;
    }

    /**
     * @param snapshot     the charge types and tariffs of the cooperative
     * @param chargeTypeId the charge type
     * @param plot         the plot
     * @param month        the billing month
     * @param applicable   the days the tariffs of the type may charge the plot ({@link DaySet#ALL}; the water
     *                     connection days for water from slice 5)
     * @param charged      whether the source key counts as charged ({@link ChargeKeys})
     */
    static Outcome plan(Snapshot snapshot, UUID chargeTypeId, PlotFacts plot, YearMonth month, DaySet applicable,
            Predicate<String> charged) {
        List<TariffEntry> applying = applying(snapshot, chargeTypeId, plot, month);
        for (TariffEntry tariff : applying) {
            String key = sourceKey(tariff.id(), plot.plotId(), month);
            if (charged.test(key)) {
                return new AlreadyCharged(key);
            }
        }
        List<PlannedCharge> charging = new ArrayList<>();
        for (TariffEntry tariff : applying) {
            ChargeCalculator.breakdown(tariff.terms(), plot.areaM2(), month, applicable)
                    .filter(breakdown -> breakdown.amount().signum() > 0)
                    .ifPresent(breakdown -> charging.add(new PlannedCharge(plot.plotId(), chargeTypeId, tariff, month,
                            breakdown, sourceKey(tariff.id(), plot.plotId(), month))));
        }
        if (charging.size() == 1) {
            return new NewCharge(charging.getFirst());
        }
        if (charging.size() > 1) {
            return new Conflicting(charging.stream().map(PlannedCharge::tariff).toList());
        }
        return new Nothing();
    }

    /**
     * The source keys of the charges {@link #plan} may look for: one per tariff that applies to the plot in the month
     * (so a caller can ask the ledger for all of them in one query before planning).
     */
    static List<String> sourceKeys(Snapshot snapshot, UUID chargeTypeId, PlotFacts plot, YearMonth month) {
        return applying(snapshot, chargeTypeId, plot, month).stream()
                .map(tariff -> sourceKey(tariff.id(), plot.plotId(), month)).toList();
    }

    /** The tariffs of the type that apply to the plot in the month, in the resolver's order (by name, then id). */
    private static List<TariffEntry> applying(Snapshot snapshot, UUID chargeTypeId, PlotFacts plot, YearMonth month) {
        Resolution resolution = TariffResolver.resolve(snapshot, chargeTypeId, plot, month.atDay(1),
                month.atEndOfMonth());
        if (resolution instanceof Single single) {
            return List.of(single.tariff());
        }
        if (resolution instanceof Conflict conflict) {
            return conflict.tariffs();
        }
        return List.of();
    }
}
