package app.nadel.tariffs.internal;

import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.tariffs.ChargeCalculator;
import app.nadel.tariffs.ChargeCalculator.Breakdown;
import app.nadel.tariffs.ChargeCalculator.Part;
import app.nadel.tariffs.DaySet;
import app.nadel.tariffs.ExpectedAmounts;
import app.nadel.tariffs.TariffBook;
import app.nadel.tariffs.TariffBook.ChargeTypeEntry;
import app.nadel.tariffs.TariffBook.Snapshot;
import app.nadel.tariffs.TariffBook.TariffEntry;
import app.nadel.tariffs.TariffResolver;
import app.nadel.tariffs.TariffResolver.Conflict;
import app.nadel.tariffs.TariffResolver.Resolution;
import app.nadel.tariffs.TariffResolver.Single;
import app.nadel.tariffs.VersionShare;
import app.nadel.tariffs.VersionTerms;
import app.nadel.tariffs.YearExpectation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ExpectedAmounts} over the {@link TariffBook} snapshot (four queries) and the plots of {@link PlotCatalog}
 * (500 per query), in the caller's transaction. No arithmetic of its own: every amount is a
 * {@link ChargeCalculator#breakdown} (rounded once there), and the shares are sums and one difference of such amounts.
 * Which tariff charges a month mirrors the ledger's month run ({@code ledger.internal.TariffMonthPlanning.plan}): the
 * tariffs {@link TariffResolver} names for the month, of which only those above {@code 0.00} take part, and exactly one
 * of them charges.
 */
@Component
class ExpectedYearQueries implements ExpectedAmounts {

    /** Plots per {@link PlotCatalog#findAll} call. */
    private static final int CHUNK = 500;

    private final TariffBook book;
    private final PlotCatalog plots;

    ExpectedYearQueries(TariffBook book, PlotCatalog plots) {
        this.book = book;
        this.plots = plots;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, YearExpectation> yearByVersion(String chargeTypeCode, int year, Collection<UUID> plotIds) {
        Objects.requireNonNull(chargeTypeCode, "chargeTypeCode");
        List<UUID> ids = plotIds.stream().distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Snapshot snapshot = book.snapshot();
        Optional<UUID> typeId = snapshot.chargeTypes().stream().filter(type -> chargeTypeCode.equals(type.code()))
                .map(ChargeTypeEntry::id).findFirst();
        if (typeId.isEmpty()) {
            return Map.of();
        }
        Map<UUID, PlotCatalog.PlotFacts> facts = new HashMap<>();
        for (int from = 0; from < ids.size(); from += CHUNK) {
            facts.putAll(plots.findAll(ids.subList(from, Math.min(ids.size(), from + CHUNK))));
        }
        Map<UUID, YearExpectation> result = new LinkedHashMap<>();
        for (UUID id : ids) {
            PlotCatalog.PlotFacts plot = facts.get(id);
            if (plot != null) {
                expectation(snapshot, typeId.get(), plot, year).ifPresent(expected -> result.put(id, expected));
            }
        }
        return result;
    }

    private static Optional<YearExpectation> expectation(Snapshot snapshot, UUID typeId, PlotCatalog.PlotFacts plot,
            int year) {
        TariffResolver.PlotFacts facts = new TariffResolver.PlotFacts(plot.id(), plot.commonLand(), plot.archived(),
                plot.areaM2());
        Map<UUID, ShareSum> shares = new LinkedHashMap<>();
        for (Month calendarMonth : Month.values()) {
            YearMonth month = YearMonth.of(year, calendarMonth);
            List<Charging> charging = applying(snapshot, typeId, facts, month).stream()
                    .flatMap(tariff -> ChargeCalculator.breakdown(tariff.terms(), plot.areaM2(), month, DaySet.ALL)
                            .filter(breakdown -> breakdown.amount().signum() > 0)
                            .map(breakdown -> new Charging(tariff, breakdown)).stream())
                    .toList();
            if (charging.size() == 1) {
                add(shares, charging.getFirst(), plot.areaM2(), month);
            }
        }
        if (shares.isEmpty()) {
            return Optional.empty();
        }
        List<VersionShare> list = shares.values().stream().map(ShareSum::share)
                .sorted(Comparator.comparing(VersionShare::from)).toList();
        Money total = list.stream().map(VersionShare::amount).reduce(Money.ZERO, Money::plus);
        return Optional.of(new YearExpectation(plot.id(), plot.areaM2(), ChargeCalculator.sotkas(plot.areaM2()), list,
                total));
    }

    /** The tariffs of the type that apply to the plot in the month, as the month run asks the resolver. */
    private static List<TariffEntry> applying(Snapshot snapshot, UUID typeId, TariffResolver.PlotFacts plot,
            YearMonth month) {
        Resolution resolution = TariffResolver.resolve(snapshot, typeId, plot, month.atDay(1), month.atEndOfMonth());
        if (resolution instanceof Single single) {
            return List.of(single.tariff());
        }
        if (resolution instanceof Conflict conflict) {
            return conflict.tariffs();
        }
        return List.of();
    }

    /**
     * Adds a month's amount to the shares of its versions. One version takes the whole amount; in a month shared by
     * several, each but the last takes the amount {@link ChargeCalculator#breakdown} gives over its own days, and the
     * last takes the rest, so the shares add up to the month's amount (rounded once) without another rounding.
     */
    private static void add(Map<UUID, ShareSum> shares, Charging charging, BigDecimal areaM2, YearMonth month) {
        List<Part> parts = charging.breakdown().parts();
        Money rest = charging.breakdown().amount();
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            Money amount = rest;
            if (i < parts.size() - 1) {
                amount = ChargeCalculator.breakdown(charging.tariff().terms(), areaM2, month,
                        DaySet.of(part.from(), part.to())).map(Breakdown::amount).orElse(Money.ZERO);
                rest = rest.minus(amount);
            }
            UUID versionId = charging.tariff().versionStartingOn(part.terms().validFrom()).orElseThrow();
            shares.computeIfAbsent(versionId, id -> new ShareSum(charging.tariff().id(), id, part.terms()))
                    .add(part, amount);
        }
    }

    /** The tariff that charges a month, with its breakdown. */
    private record Charging(TariffEntry tariff, Breakdown breakdown) {
    }

    /** The share of one version, summed over the months. */
    private static final class ShareSum {

        private final UUID tariffId;
        private final UUID versionId;
        private final VersionTerms terms;
        private @Nullable LocalDate from;
        private @Nullable LocalDate to;
        private long days;
        private Money amount = Money.ZERO;

        ShareSum(UUID tariffId, UUID versionId, VersionTerms terms) {
            this.tariffId = tariffId;
            this.versionId = versionId;
            this.terms = terms;
        }

        void add(Part part, Money partAmount) {
            from = from == null || part.from().isBefore(from) ? part.from() : from;
            to = to == null || part.to().isAfter(to) ? part.to() : to;
            days += part.days();
            amount = amount.plus(partAmount);
        }

        VersionShare share() {
            return new VersionShare(tariffId, versionId, terms.basis(), terms.frequency(), terms.rate(),
                    Objects.requireNonNull(from), Objects.requireNonNull(to), days, amount);
        }
    }
}
