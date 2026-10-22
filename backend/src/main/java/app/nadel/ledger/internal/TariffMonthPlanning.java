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
 * {@link TariffResolver} picks the tariff over the month. One tariff: its charge is identified by the source key
 * {@code TARIFF:{tariff}:{plot}:{YYYY-MM}}; when a charge with that key exists (reversed or not) there is nothing new
 * ({@link AlreadyCharged}: a rerun never books twice and never re-creates a reversed charge); otherwise
 * {@link ChargeCalculator#breakdown} over the applicable days gives the amount, and {@code 0.00}, a tariff charged by
 * meter readings or no applicable day is {@link Nothing}. Two or more tariffs: neither is charged and the case is
 * queued ({@link Conflicting}) when at least one of them would charge the plot an amount in the month; otherwise there
 * is nothing to queue. No tariff (none in scope, excluded, inactive type, archived plot or common land): nothing, and
 * nothing is queued (a plot outside a membership tariff is not a "not charged" case).
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

    /** The tariff's charge of the month exists already (reversed or not). */
    record AlreadyCharged(String sourceKey) implements Outcome {
    }

    /** Nothing to charge and nothing to queue. */
    record Nothing() implements Outcome {
    }

    /** Two or more tariffs apply: neither is charged, the case is queued with them. Ordered by name. */
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
     * @param charged      whether a charge with the source key exists
     */
    static Outcome plan(Snapshot snapshot, UUID chargeTypeId, PlotFacts plot, YearMonth month, DaySet applicable,
            Predicate<String> charged) {
        Resolution resolution = TariffResolver.resolve(snapshot, chargeTypeId, plot, month.atDay(1),
                month.atEndOfMonth());
        if (resolution instanceof Single single) {
            TariffEntry tariff = single.tariff();
            String key = sourceKey(tariff.id(), plot.plotId(), month);
            if (charged.test(key)) {
                return new AlreadyCharged(key);
            }
            return ChargeCalculator.breakdown(tariff.terms(), plot.areaM2(), month, applicable)
                    .filter(breakdown -> breakdown.amount().signum() > 0)
                    .<Outcome>map(breakdown -> new NewCharge(new PlannedCharge(plot.plotId(), chargeTypeId, tariff,
                            month, breakdown, key)))
                    .orElse(new Nothing());
        }
        if (resolution instanceof Conflict conflict) {
            boolean wouldCharge = conflict.tariffs().stream().anyMatch(tariff -> ChargeCalculator
                    .breakdown(tariff.terms(), plot.areaM2(), month, applicable)
                    .map(breakdown -> breakdown.amount().signum() > 0).orElse(false));
            return wouldCharge ? new Conflicting(conflict.tariffs()) : new Nothing();
        }
        return new Nothing();
    }
}
