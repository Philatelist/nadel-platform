package app.nadel.ledger.internal;

import app.nadel.ledger.internal.NotChargedItemRecord.Reason;
import app.nadel.ledger.internal.ReadingChargeCalculator.Losses;
import app.nadel.ledger.internal.ReadingChargeCalculator.NotCovered;
import app.nadel.ledger.internal.ReadingChargeCalculator.Priced;
import app.nadel.ledger.internal.ReadingChargeCalculator.PricedPart;
import app.nadel.ledger.internal.ReadingChargeCalculator.Result;
import app.nadel.meters.MeterNetwork.Point;
import app.nadel.meters.MeterResource;
import app.nadel.meters.Quantity;
import app.nadel.money.Money;
import app.nadel.tariffs.Basis;
import app.nadel.tariffs.DaySet;
import app.nadel.tariffs.TariffBook.Snapshot;
import app.nadel.tariffs.TariffBook.TariffEntry;
import app.nadel.tariffs.TariffResolver;
import app.nadel.tariffs.TariffResolver.Conflict;
import app.nadel.tariffs.TariffResolver.None;
import app.nadel.tariffs.TariffResolver.PlotFacts;
import app.nadel.tariffs.TariffResolver.Resolution;
import app.nadel.tariffs.TariffResolver.Single;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * What the ledger does with one interval of a plot meter (feature 011 §2.4): the consumption between two neighbouring
 * points. Pure: no Spring, no database, no clock; the listener of reading changes, the retry of a queue item and the
 * month run all call it, so they never disagree.
 * <p>
 * The charge of an interval is identified by {@code CONSUMPTION:{fromKey}:{toKey}} (the keys of its two points, see
 * {@code MeterNetwork.Point}); when a charge with that key exists (reversed or not) there is nothing new
 * ({@link AlreadyCharged}): a redelivered event never books twice and a reversed interval is not re-created (its
 * correction is a new reading, which has new keys). Otherwise {@link TariffResolver} picks the tariff of the meter's
 * charge type ({@code ELECTRICITY} or {@code WATER}) over the days of the interval:
 * <ul>
 * <li>one tariff: {@link ReadingChargeCalculator} prices it. A positive amount is a {@link NewCharges}; {@code 0.00}
 * (no consumption) or a tariff not charged by the meter on any applicable day is {@link Nothing}; a tariff that does
 * not cover every applicable day is {@link Failed} with {@code NO_TARIFF_FOR_ALL_DAYS}, naming it;</li>
 * <li>two or more: only the tariffs that would charge the interval by the meter take part (those that price it above
 * {@code 0.00} or would, but miss some days); one of them is treated as the only tariff, two or more are
 * {@code CONFLICTING_TARIFFS}, none is {@link Nothing};</li>
 * <li>none: the charge type inactive or archived is {@link Nothing} (nothing is queued, as for water before it is
 * switched on); no tariff valid in the interval is {@code NO_TARIFF_FOR_ALL_DAYS}; the plot outside every scope is
 * {@code OUTSIDE_SCOPE}, excluded {@code EXCLUDED}, naming the tariffs of the type.</li>
 * </ul>
 * An interval without an applicable day (a water meter of a plot not connected to water on any of its days) is
 * {@link Nothing} before any tariff is looked at, so it is never queued.
 * <p>
 * With fixed-percentage losses (electricity only, a percentage above zero) the same interval also gets a {@code LOSSES}
 * charge, key {@code LOSSES:{fromKey}:{toKey}}, over the same parts, when its amount is above {@code 0.00}.
 */
final class ReadingChargePlanning {

    private ReadingChargePlanning() {
    }

    /** The outcome for one interval. */
    sealed interface Outcome {
    }

    /** A consumption charge to book, and its losses charge when there is one. */
    record NewCharges(PlannedReadingCharge consumption, @Nullable PlannedReadingCharge losses) implements Outcome {
    }

    /** The interval's charge exists already (reversed or not). */
    record AlreadyCharged() implements Outcome {
    }

    /** Nothing to charge and nothing to queue. */
    record Nothing() implements Outcome {
    }

    /** Not charged for a reason the board can fix; the tariffs to look at (the link to fix), ordered by name. */
    record Failed(Reason reason, List<UUID> tariffIds) implements Outcome {

        Failed {
            tariffIds = List.copyOf(tariffIds);
        }
    }

    /**
     * An interval of a plot meter.
     *
     * @param meterId the meter
     * @param from    the previous point
     * @param to      the point that closes the interval
     */
    record MeterInterval(UUID meterId, Point from, Point to) {

        MeterInterval {
            Objects.requireNonNull(meterId, "meterId");
            if (!to.on().isAfter(from.on())) {
                throw new IllegalArgumentException("An interval ends after its first point");
            }
        }

        Quantity consumption() {
            return Quantity.of(to.value()).minus(Quantity.of(from.value()));
        }

        String consumptionKey() {
            return "CONSUMPTION:" + from.key() + ":" + to.key();
        }

        String lossesKey() {
            return "LOSSES:" + from.key() + ":" + to.key();
        }

        /** The first day of the interval: the day after the previous point. */
        LocalDate firstDay() {
            return from.on().plusDays(1);
        }
    }

    /**
     * A charge of an interval to book.
     *
     * @param source     {@code CONSUMPTION} or {@code LOSSES}
     * @param parts      the versions behind it with their quantities (the losses quantities for a losses charge)
     * @param quantity   the quantity charged (the consumption of the applicable days, or the losses)
     * @param percent    the losses percentage of a losses charge; null for a consumption charge
     */
    record PlannedReadingCharge(UUID plotId, UUID chargeTypeId, MeterResource resource, MeterInterval interval,
            TariffEntry tariff, ChargeSource source, Money amount, List<PricedPart> parts, Quantity quantity,
            @Nullable BigDecimal percent, String sourceKey) {

        PlannedReadingCharge {
            parts = List.copyOf(parts);
        }

        /** The first day the parts count for. */
        LocalDate serviceFrom() {
            return parts.getFirst().from();
        }

        /** The last day the parts count for. */
        LocalDate serviceTo() {
            return parts.getLast().to();
        }

        /** The debt counts from the reading date. */
        LocalDate accruedOn() {
            return interval.to().on();
        }

        /** The version id of each part, in the order of the parts. */
        List<UUID> versionIds() {
            return parts.stream().map(part -> tariff.versionStartingOn(part.terms().validFrom()).orElseThrow())
                    .toList();
        }

        /**
         * The calculation stored with the charge: codes and numbers only (the {@code ChargeCalculation} of the
         * contract). One part per version with its quantity (kWh or m3, three decimals for display; the amount was
         * calculated from the exact quantity), rate, days and the first and last day; the two points of the interval as
         * readings; for a losses charge the percentage and the consumption it is taken of.
         */
        Map<String, Object> calculation() {
            List<UUID> ids = versionIds();
            List<Map<String, Object>> lines = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                PricedPart part = parts.get(i);
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("tariffVersionId", ids.get(i).toString());
                line.put("basis", part.terms().basis().name());
                line.put("frequency", part.terms().frequency().name());
                line.put("quantity", part.quantity().toMeterValue().toString());
                line.put("unit", resource == MeterResource.WATER ? "M3" : "KWH");
                line.put("rate", part.terms().rate().toString());
                line.put("days", part.days());
                line.put("from", part.from().toString());
                line.put("to", part.to().toString());
                lines.add(line);
            }
            Map<String, Object> calculation = new LinkedHashMap<>();
            calculation.put("amount", amount.toString());
            calculation.put("parts", lines);
            calculation.put("readings", List.of(reading(interval.from()), reading(interval.to())));
            if (percent != null) {
                calculation.put("lossesPercent", percent.toPlainString());
                calculation.put("consumption", interval.consumption().toMeterValue().toString());
            }
            return calculation;
        }

        private Map<String, Object> reading(Point point) {
            Map<String, Object> reading = new LinkedHashMap<>();
            reading.put("meterId", interval.meterId().toString());
            reading.put("takenOn", point.on().toString());
            reading.put("value", point.value().toString());
            return reading;
        }
    }

    /** The basis a meter of the resource is charged by. */
    static Basis basisOf(MeterResource resource) {
        return resource == MeterResource.WATER ? Basis.PER_M3 : Basis.PER_KWH;
    }

    /**
     * @param snapshot      the charge types and tariffs of the cooperative
     * @param chargeTypeId  the charge type of the meter's resource
     * @param plot          the meter's plot
     * @param resource      what the meter measures
     * @param interval      the interval
     * @param applicable    the days the tariffs may charge the plot ({@link DaySet#ALL}; the water connection days
     *                      for water from slice 5)
     * @param lossesPercent the fixed losses percentage (electricity, {@code FIXED_PERCENT} mode), or null
     * @param charged       whether a charge with the source key exists
     */
    static Outcome plan(Snapshot snapshot, UUID chargeTypeId, PlotFacts plot, MeterResource resource,
            MeterInterval interval, DaySet applicable, @Nullable BigDecimal lossesPercent, Predicate<String> charged) {
        if (charged.test(interval.consumptionKey())) {
            return new AlreadyCharged();
        }
        if (applicable.count(interval.firstDay(), interval.to().on()) == 0) {
            // no day the tariffs may charge (a water meter of a plot not connected): nothing, and nothing is queued
            return new Nothing();
        }
        Resolution resolution = TariffResolver.resolve(snapshot, chargeTypeId, plot, interval.firstDay(),
                interval.to().on());
        List<UUID> typeTariffs = snapshot.tariffsOf(chargeTypeId).stream().map(TariffEntry::id).toList();
        BigDecimal percent = resource == MeterResource.ELECTRICITY ? lossesPercent : null;
        return switch (resolution) {
            case Single single -> priced(single.tariff(), plot, resource, interval, applicable, percent, charged);
            case Conflict conflict -> {
                List<TariffEntry> charging = conflict.tariffs().stream()
                        .filter(t -> wouldCharge(price(t, resource, interval, applicable, null))).toList();
                if (charging.size() == 1) {
                    yield priced(charging.getFirst(), plot, resource, interval, applicable, percent, charged);
                }
                yield charging.isEmpty() ? new Nothing()
                        : new Failed(Reason.CONFLICTING_TARIFFS, charging.stream().map(TariffEntry::id).toList());
            }
            case None none -> switch (none.reason()) {
                case TYPE_INACTIVE -> new Nothing();
                case NO_TARIFF -> new Failed(Reason.NO_TARIFF_FOR_ALL_DAYS, typeTariffs);
                case EXCLUDED -> new Failed(Reason.EXCLUDED, typeTariffs);
                case OUTSIDE_SCOPE -> new Failed(Reason.OUTSIDE_SCOPE, typeTariffs);
            };
        };
    }

    private static Outcome priced(TariffEntry tariff, PlotFacts plot, MeterResource resource, MeterInterval interval,
            DaySet applicable, @Nullable BigDecimal percent, Predicate<String> charged) {
        Result result = price(tariff, resource, interval, applicable, percent);
        if (result instanceof NotCovered) {
            return new Failed(Reason.NO_TARIFF_FOR_ALL_DAYS, List.of(tariff.id()));
        }
        if (!(result instanceof Priced priced) || priced.amount().signum() <= 0) {
            return new Nothing();
        }
        UUID typeId = tariff.chargeTypeId();
        PlannedReadingCharge consumption = new PlannedReadingCharge(plot.plotId(), typeId, resource, interval, tariff,
                ChargeSource.CONSUMPTION, priced.amount(), priced.parts(),
                priced.parts().stream().map(PricedPart::quantity).reduce(Quantity.ZERO, Quantity::plus), null,
                interval.consumptionKey());
        Losses losses = priced.losses();
        PlannedReadingCharge lossesCharge = null;
        if (losses != null && losses.amount().signum() > 0 && !charged.test(interval.lossesKey())) {
            lossesCharge = new PlannedReadingCharge(plot.plotId(), typeId, resource, interval, tariff,
                    ChargeSource.LOSSES, losses.amount(), losses.parts(), losses.quantity(), losses.percent(),
                    interval.lossesKey());
        }
        return new NewCharges(consumption, lossesCharge);
    }

    private static Result price(TariffEntry tariff, MeterResource resource, MeterInterval interval, DaySet applicable,
            @Nullable BigDecimal percent) {
        return ReadingChargeCalculator.price(interval.from().on(), interval.to().on(), interval.consumption(),
                tariff.terms(), basisOf(resource), applicable, percent);
    }

    /** Whether a tariff takes part in a conflict: it prices the interval above 0.00, or would but misses days. */
    private static boolean wouldCharge(Result result) {
        return result instanceof NotCovered || (result instanceof Priced priced && priced.amount().signum() > 0);
    }
}
