package app.nadel.tariffs;

import app.nadel.money.Money;
import app.nadel.money.Rate;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * What one tariff version is expected to charge one plot over a year ({@link ExpectedAmounts#yearByVersion}): the
 * version's rate, the days of the year it charges the plot and its part of the yearly amount.
 *
 * @param tariffId  the tariff
 * @param versionId the version
 * @param basis     the version's basis; for land tax {@link Basis#PER_AREA_SOTKA}, so {@code rate} is per sotka
 * @param frequency the version's frequency; for land tax {@link Frequency#YEARLY}, so {@code rate} is per year
 * @param rate      the rate exactly as entered (never rounded)
 * @param from      the first day of the year the version charges the plot
 * @param to        the last such day
 * @param days      the number of days of the year the version charges the plot (the versions of a tariff that covers
 *                  the whole year add up to the days of the year)
 * @param amount    the version's part of the yearly amount (see {@link ExpectedAmounts#yearByVersion} for how a month
 *                  shared by two versions is split); the shares of a plot add up to its expected yearly amount exactly
 */
public record VersionShare(UUID tariffId, UUID versionId, Basis basis, Frequency frequency, Rate rate, LocalDate from,
        LocalDate to, long days, Money amount) {

    public VersionShare {
        Objects.requireNonNull(tariffId, "tariffId");
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(frequency, "frequency");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(amount, "amount");
    }
}
