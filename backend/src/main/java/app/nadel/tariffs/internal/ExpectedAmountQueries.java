package app.nadel.tariffs.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.identity.OwnedPlot;
import app.nadel.identity.OwnedPlotsSource;
import app.nadel.identity.Permission;
import app.nadel.meters.MeterCatalog;
import app.nadel.meters.MeterResource;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import app.nadel.tariffs.ChargeCalculator;
import app.nadel.tariffs.ExpectedCharge;
import app.nadel.tariffs.VersionTerms;
import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import nadel.contract.model.Basis;
import nadel.contract.model.ChargeTypeCode;
import nadel.contract.model.ExpectedChargeStatus;
import nadel.contract.model.Frequency;
import nadel.contract.model.PlotTariffItem;
import nadel.contract.model.PlotTariffs;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The expected amounts of one plot for one month: which tariffs apply and what each would charge, worked out by
 * {@link ChargeCalculator}. Nothing is charged yet; this is the preview of feature 009.
 * <p>
 * Who may ask: a caller with {@code TARIFFS_VIEW} for any plot of the cooperative, anyone else only for a plot they own
 * now. Every other case, including an unknown plot and a plot of another cooperative (RLS), is the same 404. The data of
 * the cooperative is bulk-loaded once (charge types, their tariffs, all versions, the scope rows that name the plot), so
 * the number of queries does not grow with the number of tariffs; whether the plot has a meter is asked once per
 * resource ({@link MeterCatalog}).
 * <p>
 * A tariff charged by meter ({@code PER_KWH}, {@code PER_M3}) is {@code BY_READINGS} when the plot had a meter of that
 * resource working on a day of the month, and {@code NO_METER} otherwise: such a tariff reaches only plots with a
 * meter. Neither has an amount or adds to the total.
 */
@Service
class ExpectedAmountQueries {

    private final PlotCatalog plots;
    private final OwnedPlotsSource ownedPlots;
    private final ChargeTypeRepository chargeTypes;
    private final TariffRepository tariffs;
    private final TariffVersionRepository versions;
    private final TariffScopePlotRepository scopePlots;
    private final MeterCatalog meters;
    private final Clock clock;

    ExpectedAmountQueries(PlotCatalog plots, OwnedPlotsSource ownedPlots, ChargeTypeRepository chargeTypes,
            TariffRepository tariffs, TariffVersionRepository versions, TariffScopePlotRepository scopePlots,
            MeterCatalog meters, Clock clock) {
        this.plots = plots;
        this.ownedPlots = ownedPlots;
        this.chargeTypes = chargeTypes;
        this.tariffs = tariffs;
        this.versions = versions;
        this.scopePlots = scopePlots;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * @param monthText {@code YYYY-MM}; null means the current month in the cooperative's time zone (Europe/Kyiv)
     * @throws ApiProblemException 403 for the platform operator, 404 when the plot is unknown or not the caller's to see
     */
    @Transactional(readOnly = true)
    PlotTariffs forPlot(UUID plotId, @Nullable String monthText, AccessProfile viewer) {
        if (viewer.platformOperator() || viewer.tenantId() == null) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, ProblemCodes.PERMISSION_DENIED,
                    "The platform operator has no access to cooperative data.");
        }
        YearMonth month = monthText == null ? YearMonth.now(clock) : YearMonth.parse(monthText);
        PlotFacts plot = plots.find(plotId).orElseThrow(ApiProblemException::notFound);
        if (!mayAsk(viewer, plotId)) {
            throw ApiProblemException.notFound();
        }
        if (plot.commonLand() || plot.archived()) {
            return result(month, List.of(), Money.ZERO);
        }

        List<ChargeTypeRecord> types = chargeTypes.findActiveInOrder().stream().filter(ChargeTypeRecord::isActive)
                .toList();
        if (types.isEmpty()) {
            return result(month, List.of(), Money.ZERO);
        }
        Map<UUID, ChargeTypeRecord> typeById = types.stream()
                .collect(Collectors.toMap(ChargeTypeRecord::getId, type -> type));
        Map<UUID, Integer> typeOrder = new HashMap<>();
        for (int i = 0; i < types.size(); i++) {
            typeOrder.put(types.get(i).getId(), i);
        }
        List<TariffRecord> ordered = tariffs.findByChargeTypeIdIn(typeById.keySet()).stream()
                .sorted(Comparator.<TariffRecord>comparingInt(t -> typeOrder.get(t.getChargeTypeId()))
                        .thenComparing(t -> t.getName().toLowerCase(Locale.ROOT)).thenComparing(TariffRecord::getId))
                .toList();
        if (ordered.isEmpty()) {
            return result(month, List.of(), Money.ZERO);
        }
        List<UUID> tariffIds = ordered.stream().map(TariffRecord::getId).toList();
        Map<UUID, List<TariffVersionRecord>> versionsByTariff = versions
                .findByTariffIdInOrderByTariffIdAscValidFromAsc(tariffIds).stream()
                .collect(Collectors.groupingBy(TariffVersionRecord::getTariffId));
        Map<UUID, TariffScopePlotRecord> scopeByTariff = scopePlots.findByPlotId(plotId).stream()
                .collect(Collectors.toMap(TariffScopePlotRecord::getTariffId, row -> row));

        Map<MeterResource, Boolean> hasMeter = new EnumMap<>(MeterResource.class);
        List<PlotTariffItem> items = new ArrayList<>();
        Money total = Money.ZERO;
        for (TariffRecord tariff : ordered) {
            TariffScopePlotRecord row = scopeByTariff.get(tariff.getId());
            boolean excluded = tariff.getScopeKind() == ScopeKind.ALL_PLOTS && row != null;
            boolean selected = tariff.getScopeKind() == ScopeKind.SELECTED_PLOTS && row != null;
            if (tariff.getScopeKind() == ScopeKind.SELECTED_PLOTS && !selected) {
                continue; // the plot is not among the selected plots
            }
            List<TariffVersionRecord> records = versionsByTariff.getOrDefault(tariff.getId(), List.of());
            List<VersionTerms> terms = records.stream().map(ExpectedAmountQueries::terms).toList();
            Optional<ExpectedCharge> charge = ChargeCalculator.calculate(terms, plot.areaM2(), month);
            if (charge.isEmpty()) {
                continue; // no version applies in the month: the tariff is not listed
            }
            VersionTerms shown = ChargeCalculator.shownVersion(terms, month).orElseThrow();
            PlotTariffItem item = item(tariff, typeById.get(tariff.getChargeTypeId()), shown);
            if (excluded) {
                item.setStatus(ExpectedChargeStatus.EXCLUDED);
                item.setReason(row.getReason());
            } else if (charge.get() instanceof ExpectedCharge.Amount amount) {
                item.setStatus(ExpectedChargeStatus.AMOUNT);
                item.setAmount(amount.money().toString());
                total = total.plus(amount.money());
            } else if (charge.get() instanceof ExpectedCharge.ByReadings byReadings) {
                MeterResource resource = MeterBasis.resourceOf(byReadings.basis());
                boolean metered = hasMeter.computeIfAbsent(resource, r -> meters.hasMeterBetween(plotId, r,
                        month.atDay(1), month.atEndOfMonth()));
                item.setStatus(metered ? ExpectedChargeStatus.BY_READINGS : ExpectedChargeStatus.NO_METER);
            }
            items.add(item);
        }
        return result(month, items, total);
    }

    private boolean mayAsk(AccessProfile viewer, UUID plotId) {
        if (viewer.can(Permission.TARIFFS_VIEW)) {
            return true;
        }
        UUID memberId = viewer.memberId();
        return memberId != null
                && ownedPlots.currentPlotsOf(memberId).stream().map(OwnedPlot::plotId).anyMatch(plotId::equals);
    }

    private static VersionTerms terms(TariffVersionRecord version) {
        return new VersionTerms(version.getValidFrom(), version.getBasis(), version.getFrequency(),
                version.getRate(), version.getDueDate());
    }

    private static PlotTariffItem item(TariffRecord tariff, ChargeTypeRecord type, VersionTerms shown) {
        var item = new PlotTariffItem(tariff.getId(), tariff.getName(), type.getId(), type.getName(),
                type.isNameCustomized(), Basis.fromValue(shown.basis().name()),
                Frequency.fromValue(shown.frequency().name()), shown.rate().toString(), ExpectedChargeStatus.AMOUNT);
        if (type.getCode() != null) {
            item.setChargeTypeCode(ChargeTypeCode.fromValue(type.getCode()));
        }
        return item;
    }

    private static PlotTariffs result(YearMonth month, List<PlotTariffItem> items, Money total) {
        return new PlotTariffs(month.toString(), items, total.toString());
    }
}
