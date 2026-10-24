package app.nadel.reports.internal;

import app.nadel.ledger.LedgerReadModel;
import app.nadel.ledger.PlotDebts;
import app.nadel.money.Money;
import app.nadel.money.Rate;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import app.nadel.reports.internal.LandTaxReport.PlotLandTax;
import app.nadel.reports.internal.PlotBalancesReport.Balance;
import app.nadel.reports.internal.ReportResult.Cell;
import app.nadel.reports.internal.ReportResult.Column;
import app.nadel.reports.internal.ReportResult.Row;
import app.nadel.reports.internal.ReportResult.Sheet;
import app.nadel.reports.internal.TemplateField.Placeholder;
import app.nadel.settings.CooperativeSettings;
import app.nadel.settings.Requisites;
import app.nadel.tariffs.ChargeCalculator;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import app.nadel.tariffs.VersionShare;
import app.nadel.tenancy.TenantDirectory;
import app.nadel.tenancy.TenantSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import nadel.contract.model.ReportRowKind;
import nadel.contract.model.ReportTemplateFieldType;
import nadel.contract.model.ReportTemplateValueReason;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The values of the placeholders of {@link TemplateField} for one fill of a template (feature 013 §4.1, ADR 0015). The
 * module adds no money rule here either: every amount comes from the read models of the built-in reports (the same
 * beans and methods, so a template and the report always agree) or from a ledger facade, and every total is a
 * {@link Money} sum of the rows it stands for.
 * <p>
 * The period {@code from} to {@code to} (both included, days in Europe/Kyiv) means:
 * <ul>
 * <li>{@code period.from}, {@code period.to}: the days; {@code period.year}: the year of {@code to};</li>
 * <li>{@code generatedAt}: when the fill started, Europe/Kyiv wall time to the second; {@code generatedBy}: the display
 * name of the caller (the person making the file, as in the header of every report file);</li>
 * <li>{@code cooperative.*}: the name and ЄДРПОУ of the cooperative ({@code tenancy.TenantDirectory}) and the
 * requisites of its settings ({@code settings.Requisites}); a requisite or ЄДРПОУ not entered is not known
 * ({@code NOT_SET}), so the template's author sees the warning;</li>
 * <li>{@code totals.debt}, {@code totals.plots.count}, {@code totals.plots.areaSotka} and the row set {@code plots}:
 * the plot balances of every active plot without common land ({@link PlotBalancesReport#balances}, every charge type)
 * as of {@code to}, or as of today when {@code to} is later (no balance exists for a day to come); the total debt is the
 * sum of the listed plots' debts (equal to the plot balances report's total), the area the sum of their areas in sotkas
 * ({@code tariffs.ChargeCalculator.sotkas}, exact);</li>
 * <li>{@code totals.landTax.*} and {@code rows.plots.landTax.*}: the land tax report for the year of {@code to}
 * ({@link LandTaxReport#plots}): its expected and charged totals; per plot its expected and charged amounts and its
 * rate when one rate charged it all year ({@code SEVERAL_RATES} otherwise); a plot the land tax does not charge in the
 * year has these cells empty;</li>
 * <li>{@code totals.charged.<type>}: the effective amount (charges plus adjustments minus reversals; opening balances
 * left out) of the type's charges whose first service day lies in the period, booked whenever, on every plot
 * ({@code ledger.LedgerReadModel.chargedByType}, the land tax report's year rule over any range of days);</li>
 * <li>the row set {@code months}: one row per billing month from the month of {@code from} to the month of
 * {@code to} that has charges, with the cooperative's totals of that month (the subtotal rows of the charges by month
 * report's sheet by billing month);</li>
 * <li>the row set {@code meters}: the consumption sheet of the electricity report for the service days
 * ({@link ElectricityTreeReport#consumption});</li>
 * <li>the row set {@code water}: the main sheet of the water report for the service days, every source and street.</li>
 * </ul>
 * A value the report shows as not known (a meter value without a reading and everything worked out from it) is not
 * known here either, with its reason; an empty report cell is an empty value. A row set over the row cap of the reports
 * is 422 {@code REPORT_TOO_LARGE}. Texts of codes (statuses, meter kinds, water sources) are those of the report files
 * in the file's language.
 */
@Component
class TemplateValues {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    private final TenantDirectory tenants;
    private final CooperativeSettings settings;
    private final ChargeTypeCatalog chargeTypes;
    private final LedgerReadModel ledger;
    private final PlotBalancesReport plotBalances;
    private final LandTaxReport landTax;
    private final ChargesByMonthReport chargesByMonth;
    private final ElectricityTreeReport electricity;
    private final WaterReport water;
    private final ReportLimits limits;

    TemplateValues(TenantDirectory tenants, CooperativeSettings settings, ChargeTypeCatalog chargeTypes,
            LedgerReadModel ledger, PlotBalancesReport plotBalances, LandTaxReport landTax,
            ChargesByMonthReport chargesByMonth, ElectricityTreeReport electricity, WaterReport water,
            ReportLimits limits) {
        this.tenants = tenants;
        this.settings = settings;
        this.chargeTypes = chargeTypes;
        this.ledger = ledger;
        this.plotBalances = plotBalances;
        this.landTax = landTax;
        this.chargesByMonth = chargesByMonth;
        this.electricity = electricity;
        this.water = water;
        this.limits = limits;
    }

    /**
     * The values of one fill. Must be called, and the resolver used, inside the caller's transaction (the facades are
     * {@code MANDATORY}); everything is read when first asked for and kept for the fill.
     *
     * @param from        the first day of the period
     * @param to          the last day of the period, not before {@code from} (the caller has checked the range)
     * @param language    the language of the texts of codes
     * @param generatedBy the display name of the caller
     * @param generatedAt when the fill started; its day in Kyiv is "today"
     */
    Resolver resolver(LocalDate from, LocalDate to, ReportLanguage language, String generatedBy,
            Instant generatedAt) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to is before from");
        }
        LocalDate today = LocalDate.ofInstant(generatedAt, KYIV);
        ReportContext context = new ReportContext(today, limits.rowCap() + 1, chargeTypes.all());
        return new Resolver(from, to, ReportTexts.of(language), generatedBy,
                LocalDateTime.ofInstant(generatedAt, KYIV).truncatedTo(ChronoUnit.SECONDS), context);
    }

    /** The values of one fill: single values by key, row sets as rows of values by key. */
    final class Resolver implements TemplateFiller.Values {

        private final LocalDate from;
        private final LocalDate to;
        private final ReportTexts texts;
        private final String generatedBy;
        private final LocalDateTime generatedAt;
        private final ReportContext context;
        private final Map<TemplateRowSet, List<Map<String, TemplateValue>>> rowSets =
                new EnumMap<>(TemplateRowSet.class);
        private @Nullable List<Balance> balances;
        private @Nullable List<PlotLandTax> landTaxPlots;
        private @Nullable Map<UUID, Money> chargedByType;

        private Resolver(LocalDate from, LocalDate to, ReportTexts texts, String generatedBy,
                LocalDateTime generatedAt, ReportContext context) {
            this.from = from;
            this.to = to;
            this.texts = texts;
            this.generatedBy = generatedBy;
            this.generatedAt = generatedAt;
            this.context = context;
        }

        /** The placeholder of a key; empty when the app does not know it ({@code UNKNOWN_PLACEHOLDER}). */
        @Override
        public Optional<Placeholder> placeholder(String key) {
            return TemplateField.resolve(key, context.chargeTypes());
        }

        /**
         * The value of a single placeholder; empty when the app does not know the key.
         *
         * @throws IllegalArgumentException for a key of a row set: those are filled from {@link #rows}
         */
        @Override
        public Optional<TemplateValue> single(String key) {
            Optional<Placeholder> placeholder = placeholder(key);
            if (placeholder.isEmpty()) {
                return Optional.empty();
            }
            if (placeholder.get().field().rowSet() != null) {
                throw new IllegalArgumentException("A row set's placeholder is filled per row");
            }
            return Optional.of(value(placeholder.get()));
        }

        /**
         * The rows of a row set, each the value of every placeholder of the set by key (the families once per charge
         * type, under the code of a seeded type and under the id of every type).
         *
         * @throws ApiProblemException 422 {@code REPORT_TOO_LARGE} when the set has more rows than the cap
         */
        @Override
        public List<Map<String, TemplateValue>> rows(TemplateRowSet set) {
            List<Map<String, TemplateValue>> rows = rowSets.get(set);
            if (rows == null) {
                rows = switch (set) {
                    case PLOTS -> plotRows();
                    case MONTHS -> monthRows();
                    case METERS -> meterRows();
                    case WATER -> waterRows();
                };
                if (rows.size() > limits.rowCap()) {
                    throw new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, ProblemCodes.REPORT_TOO_LARGE,
                            "The report is too large. Narrow the filters.");
                }
                rows = Collections.unmodifiableList(rows);
                rowSets.put(set, rows);
            }
            return rows;
        }

        // ---- single values

        private TemplateValue value(Placeholder placeholder) {
            return switch (placeholder.field()) {
                case COOPERATIVE_NAME -> text(cooperative().name());
                case COOPERATIVE_EDRPOU -> requisite(cooperative().edrpou());
                case COOPERATIVE_OFFICIAL_NAME -> requisite(requisites().officialName());
                case COOPERATIVE_ADDRESS -> requisite(requisites().legalAddress());
                case COOPERATIVE_BANK_NAME -> requisite(requisites().bankName());
                case COOPERATIVE_IBAN -> requisite(requisites().iban());
                case PERIOD_FROM -> TemplateValue.of(ReportTemplateFieldType.DATE, from);
                case PERIOD_TO -> TemplateValue.of(ReportTemplateFieldType.DATE, to);
                case PERIOD_YEAR -> integer(to.getYear());
                case GENERATED_AT -> TemplateValue.of(ReportTemplateFieldType.DATE_TIME, generatedAt);
                case GENERATED_BY -> text(generatedBy);
                case TOTALS_LAND_TAX_EXPECTED -> money(landTaxPlots().stream()
                        .map(plot -> plot.expectation().total()).reduce(Money.ZERO, Money::plus));
                case TOTALS_LAND_TAX_CHARGED -> money(landTaxPlots().stream().map(PlotLandTax::charged)
                        .reduce(Money.ZERO, Money::plus));
                case TOTALS_CHARGED -> money(chargedByType().getOrDefault(
                        Objects.requireNonNull(placeholder.chargeType()).id(), Money.ZERO));
                case TOTALS_DEBT -> money(balances().stream().map(balance -> balance.debts().total())
                        .reduce(Money.ZERO, Money::plus));
                case TOTALS_PLOTS_COUNT -> integer(balances().size());
                case TOTALS_PLOTS_AREA_SOTKA -> TemplateValue.of(ReportTemplateFieldType.NUMBER, balances().stream()
                        .map(balance -> ChargeCalculator.sotkas(balance.plot().areaM2()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
                default -> throw new IllegalArgumentException("Not a single value: " + placeholder.field());
            };
        }

        private TenantSummary cooperative() {
            return tenants.current().orElseThrow(() -> new IllegalStateException("No cooperative is bound"));
        }

        private Requisites requisites() {
            return settings.requisites();
        }

        // ---- row set plots

        private List<Map<String, TemplateValue>> plotRows() {
            Map<UUID, PlotLandTax> landTaxByPlot = new HashMap<>();
            landTaxPlots().forEach(plot -> landTaxByPlot.put(plot.plot().id(), plot));
            List<Map<String, TemplateValue>> rows = new ArrayList<>();
            for (Balance balance : balances()) {
                PlotFacts plot = balance.plot();
                PlotDebts debts = balance.debts();
                Map<String, TemplateValue> row = new LinkedHashMap<>();
                row.put(TemplateField.PLOTS_NUMBER.key(), text(plot.number()));
                row.put(TemplateField.PLOTS_STREET.key(), text(plot.street()));
                row.put(TemplateField.PLOTS_AREA.key(), TemplateValue.of(ReportTemplateFieldType.NUMBER,
                        plot.areaM2()));
                row.put(TemplateField.PLOTS_AREA_SOTKA.key(), TemplateValue.of(ReportTemplateFieldType.NUMBER,
                        ChargeCalculator.sotkas(plot.areaM2())));
                row.put(TemplateField.PLOTS_OWNERS_COUNT.key(), integer(balance.owners()));
                row.put(TemplateField.PLOTS_DEBT_TOTAL.key(), money(debts.total()));
                for (ChargeTypeFacts type : context.chargeTypes()) {
                    putFamily(row, TemplateField.PLOTS_DEBT, type,
                            money(debts.byChargeType().getOrDefault(type.id(), Money.ZERO)));
                }
                row.put(TemplateField.PLOTS_STATUS.key(), balance.status() == null ? TemplateValue.EMPTY
                        : text(texts.text("reports.filters.options.statuses." + balance.status().name())));
                row.put(TemplateField.PLOTS_OLDEST_UNPAID.key(), TemplateValue.of(ReportTemplateFieldType.DATE,
                        debts.oldestUnpaid()));
                row.put(TemplateField.PLOTS_DAYS_OVERDUE.key(), debts.daysOverdue() == null ? TemplateValue.EMPTY
                        : integer(debts.daysOverdue()));
                PlotLandTax tax = landTaxByPlot.get(plot.id());
                row.put(TemplateField.PLOTS_LAND_TAX_RATE.key(), tax == null ? TemplateValue.EMPTY : rate(tax));
                row.put(TemplateField.PLOTS_LAND_TAX_EXPECTED.key(), tax == null ? TemplateValue.EMPTY
                        : money(tax.expectation().total()));
                row.put(TemplateField.PLOTS_LAND_TAX_CHARGED.key(), tax == null ? TemplateValue.EMPTY
                        : money(tax.charged()));
                rows.add(row);
            }
            return rows;
        }

        /** The rate per sotka a year of the plot's land tax: one rate all year, otherwise not known. */
        private TemplateValue rate(PlotLandTax tax) {
            Set<Rate> rates = new LinkedHashSet<>();
            for (VersionShare share : tax.expectation().shares()) {
                rates.add(share.rate());
            }
            return rates.size() == 1
                    ? TemplateValue.of(ReportTemplateFieldType.NUMBER, rates.iterator().next().value())
                    : TemplateValue.unknown(ReportTemplateValueReason.SEVERAL_RATES);
        }

        private void putFamily(Map<String, TemplateValue> row, TemplateField field, ChargeTypeFacts type,
                TemplateValue value) {
            row.put(field.key() + TemplateField.typeKey(type), value);
            row.put(field.key() + type.id(), value);
        }

        // ---- row sets from report sheets

        private List<Map<String, TemplateValue>> monthRows() {
            ReportFilters filters = new ReportFilters();
            filters.months(YearMonth.from(from), YearMonth.from(to));
            Sheet sheet = sheet(chargesByMonth.load(filters, context), ChargesByMonthReport.BY_BILLING_MONTH);
            return rows(sheet, row -> row.kind() == ReportRowKind.SUBTOTAL, Map.of(
                    TemplateField.MONTHS_MONTH, "month",
                    TemplateField.MONTHS_STATUS, "status",
                    TemplateField.MONTHS_REGULAR, "regular",
                    TemplateField.MONTHS_OPENING, "opening",
                    TemplateField.MONTHS_REVERSALS, "reversals",
                    TemplateField.MONTHS_ADJUSTMENTS, "adjustments",
                    TemplateField.MONTHS_EFFECTIVE, "effective"));
        }

        private List<Map<String, TemplateValue>> meterRows() {
            Sheet sheet = electricity.consumption(from, to);
            Map<TemplateField, String> columns = new EnumMap<>(TemplateField.class);
            columns.put(TemplateField.METERS_NAME, "name");
            columns.put(TemplateField.METERS_SERIAL, "serial");
            columns.put(TemplateField.METERS_KIND, "nodeKind");
            columns.put(TemplateField.METERS_START_VALUE, "start");
            columns.put(TemplateField.METERS_END_VALUE, "end");
            columns.put(TemplateField.METERS_CONSUMPTION, "consumption");
            columns.put(TemplateField.METERS_CHILDREN_CONSUMPTION, "children");
            columns.put(TemplateField.METERS_LOSSES_KWH, "losses");
            columns.put(TemplateField.METERS_LOSSES_PERCENT, "lossesPercent");
            columns.put(TemplateField.METERS_PLOTS_FED, "plotsFed");
            List<Map<String, TemplateValue>> rows = rows(sheet, row -> true, columns);
            for (int index = 0; index < rows.size(); index++) {
                rows.get(index).put(TemplateField.METERS_LEVEL.key(), integer(sheet.rows().get(index).level()));
            }
            return rows;
        }

        private List<Map<String, TemplateValue>> waterRows() {
            ReportFilters filters = new ReportFilters();
            filters.days(from, to);
            Sheet sheet = sheet(water.load(filters, context), WaterReport.MAIN);
            return rows(sheet, row -> true, Map.of(
                    TemplateField.WATER_NUMBER, "number",
                    TemplateField.WATER_STREET, "street",
                    TemplateField.WATER_SOURCE, "source",
                    TemplateField.WATER_CONNECTED_DAYS, "connectedDays",
                    TemplateField.WATER_SERIAL, "serial",
                    TemplateField.WATER_CUBIC_METRES, "cubicMetres",
                    TemplateField.WATER_TOTAL, "total"));
        }

        /** The rows of a sheet that {@code take} keeps, each the fields' values read from their columns. */
        private List<Map<String, TemplateValue>> rows(Sheet sheet, Predicate<Row> take,
                Map<TemplateField, String> columns) {
            Map<String, Integer> at = new HashMap<>();
            for (int index = 0; index < sheet.columns().size(); index++) {
                at.put(sheet.columns().get(index).key(), index);
            }
            List<Map<String, TemplateValue>> rows = new ArrayList<>();
            for (Row row : sheet.rows()) {
                if (!take.test(row)) {
                    continue;
                }
                Map<String, TemplateValue> values = new LinkedHashMap<>();
                // the catalog's order, whatever the order of the map given
                for (TemplateField field : TemplateField.values()) {
                    String column = columns.get(field);
                    if (column != null) {
                        int index = Objects.requireNonNull(at.get(column), column);
                        values.put(field.key(), cell(field, sheet.columns().get(index), row.cells().get(index)));
                    }
                }
                rows.add(values);
            }
            return rows;
        }

        /** A report cell as a value of the field: a reason stays a reason, a code becomes its text. */
        private TemplateValue cell(TemplateField field, Column column, Cell cell) {
            if (cell.reason() != null) {
                return TemplateValue.unknown(ReportTemplateValueReason.fromValue(cell.reason().getValue()));
            }
            Object value = cell.value();
            if (value == null) {
                return TemplateValue.EMPTY;
            }
            if (field.type() == ReportTemplateFieldType.TEXT) {
                return text(column.codes() == null ? value.toString() : texts.text(column.codes() + "." + value));
            }
            if (field.type() == ReportTemplateFieldType.INT) {
                return integer(((Number) value).longValue());
            }
            return TemplateValue.of(field.type(), value);
        }

        private static Sheet sheet(ReportResult result, String key) {
            return result.sheets().stream().filter(sheet -> sheet.key().equals(key)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No sheet " + key));
        }

        // ---- what several values read, read once

        /** The day of the balances: the end of the period, or today when it ends later. */
        LocalDate asOf() {
            return to.isAfter(context.today()) ? context.today() : to;
        }

        private List<Balance> balances() {
            return balances = once(balances, () -> plotBalances.balances(asOf(), List.of(), false, null, context));
        }

        private List<PlotLandTax> landTaxPlots() {
            return landTaxPlots = once(landTaxPlots, () -> landTax.plots(to.getYear()));
        }

        private Map<UUID, Money> chargedByType() {
            return chargedByType = once(chargedByType, () -> ledger.chargedByType(from, to));
        }

        private static <T> T once(@Nullable T loaded, Supplier<T> load) {
            return loaded != null ? loaded : load.get();
        }

        // ---- values

        private static TemplateValue text(@Nullable String value) {
            return TemplateValue.of(ReportTemplateFieldType.TEXT, value);
        }

        /** A requisite: not known ({@code NOT_SET}) when it was not entered. */
        private static TemplateValue requisite(@Nullable String value) {
            return value == null || value.isBlank() ? TemplateValue.unknown(ReportTemplateValueReason.NOT_SET)
                    : text(value);
        }

        private static TemplateValue money(Money value) {
            return TemplateValue.of(ReportTemplateFieldType.MONEY, value);
        }

        private static TemplateValue integer(long value) {
            return TemplateValue.of(ReportTemplateFieldType.INT, value);
        }
    }
}
