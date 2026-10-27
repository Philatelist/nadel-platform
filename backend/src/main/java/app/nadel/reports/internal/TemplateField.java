package app.nadel.reports.internal;

import app.nadel.identity.Permission;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import nadel.contract.model.ReportTemplateField;
import nadel.contract.model.ReportTemplateFieldType;
import org.jspecify.annotations.Nullable;

/**
 * The field catalog of report templates (feature 013 §4.1 and §4.2, ADR 0015): every placeholder {@code ${key}} the
 * app fills, with its value type, its row set (none for a single value), a language-neutral example and the permission
 * it needs. The description of an entry is {@code reportTemplates.fields.<field>} in the web catalogs and the report
 * bundles: the key itself for a fixed placeholder, {@code <family>.byChargeType} for the two families that depend on
 * the cooperative's charge types.
 * <p>
 * The families {@code totals.charged.<type>} and {@code rows.plots.debt.<type>} name a charge type by its code
 * ({@code MEMBERSHIP}, ...) when it is a seeded type and by its id otherwise; {@link #catalog} lists one entry per
 * charge type of the cooperative, archived ones included, and {@link #resolve} accepts the code of a seeded type or the
 * id of any type. Names, phones and e-mails of people are never placeholders: the plots carry the number of owners,
 * never who they are; {@code generatedBy} is the person making the file, as in the header of every report file.
 * <p>
 * What each value means for a period is {@link TemplateValues}; the order of the constants is the order of the catalog.
 */
enum TemplateField {

    // ---- the cooperative (tenancy and the settings' requisites)
    COOPERATIVE_NAME("cooperative.name", ReportTemplateFieldType.TEXT, null, null),
    COOPERATIVE_OFFICIAL_NAME("cooperative.officialName", ReportTemplateFieldType.TEXT, null, null),
    COOPERATIVE_EDRPOU("cooperative.edrpou", ReportTemplateFieldType.TEXT, null, "12345678"),
    COOPERATIVE_ADDRESS("cooperative.address", ReportTemplateFieldType.TEXT, null, null),
    COOPERATIVE_BANK_NAME("cooperative.bankName", ReportTemplateFieldType.TEXT, null, null),
    COOPERATIVE_IBAN("cooperative.iban", ReportTemplateFieldType.TEXT, null, "UA213223130000026007233566001"),

    // ---- the period and the file
    PERIOD_FROM("period.from", ReportTemplateFieldType.DATE, null, "2026-01-01"),
    PERIOD_TO("period.to", ReportTemplateFieldType.DATE, null, "2026-12-31"),
    PERIOD_YEAR("period.year", ReportTemplateFieldType.INT, null, "2026"),
    GENERATED_AT("generatedAt", ReportTemplateFieldType.DATE_TIME, null, "2027-01-15T10:30"),
    GENERATED_BY("generatedBy", ReportTemplateFieldType.TEXT, null, null),

    // ---- totals
    TOTALS_LAND_TAX_EXPECTED("totals.landTax.expected", ReportTemplateFieldType.MONEY, null, "12600.00"),
    TOTALS_LAND_TAX_CHARGED("totals.landTax.charged", ReportTemplateFieldType.MONEY, null, "12390.00"),
    TOTALS_CHARGED("totals.charged.", ReportTemplateFieldType.MONEY, null, "18000.00", true),
    TOTALS_DEBT("totals.debt", ReportTemplateFieldType.MONEY, null, "45210.50"),
    TOTALS_PLOTS_COUNT("totals.plots.count", ReportTemplateFieldType.INT, null, "60"),
    TOTALS_PLOTS_AREA_SOTKA("totals.plots.areaSotka", ReportTemplateFieldType.NUMBER, null, "360.5000"),

    // ---- row set plots: plot balances as of the end of the period, joined with the land tax of its year
    PLOTS_NUMBER("rows.plots.number", ReportTemplateFieldType.TEXT, TemplateRowSet.PLOTS, "12"),
    PLOTS_STREET("rows.plots.street", ReportTemplateFieldType.TEXT, TemplateRowSet.PLOTS, null),
    PLOTS_AREA("rows.plots.area", ReportTemplateFieldType.NUMBER, TemplateRowSet.PLOTS, "600.00"),
    PLOTS_AREA_SOTKA("rows.plots.areaSotka", ReportTemplateFieldType.NUMBER, TemplateRowSet.PLOTS, "6.0000"),
    PLOTS_OWNERS_COUNT("rows.plots.ownersCount", ReportTemplateFieldType.INT, TemplateRowSet.PLOTS, "2"),
    PLOTS_DEBT_TOTAL("rows.plots.debt.total", ReportTemplateFieldType.MONEY, TemplateRowSet.PLOTS, "652.50"),
    PLOTS_DEBT("rows.plots.debt.", ReportTemplateFieldType.MONEY, TemplateRowSet.PLOTS, "600.00", true),
    PLOTS_STATUS("rows.plots.status", ReportTemplateFieldType.TEXT, TemplateRowSet.PLOTS, null),
    PLOTS_OLDEST_UNPAID("rows.plots.oldestUnpaid", ReportTemplateFieldType.DATE, TemplateRowSet.PLOTS, "2026-01-01"),
    PLOTS_DAYS_OVERDUE("rows.plots.daysOverdue", ReportTemplateFieldType.INT, TemplateRowSet.PLOTS, "120"),
    PLOTS_LAND_TAX_RATE("rows.plots.landTax.rate", ReportTemplateFieldType.NUMBER, TemplateRowSet.PLOTS, "35.0000"),
    PLOTS_LAND_TAX_EXPECTED("rows.plots.landTax.expected", ReportTemplateFieldType.MONEY, TemplateRowSet.PLOTS,
            "210.00"),
    PLOTS_LAND_TAX_CHARGED("rows.plots.landTax.charged", ReportTemplateFieldType.MONEY, TemplateRowSet.PLOTS,
            "210.00"),

    // ---- row set months: the cooperative's totals per billing month (charges by month)
    MONTHS_MONTH("rows.months.month", ReportTemplateFieldType.MONTH, TemplateRowSet.MONTHS, "2026-01"),
    MONTHS_STATUS("rows.months.status", ReportTemplateFieldType.TEXT, TemplateRowSet.MONTHS, null),
    MONTHS_REGULAR("rows.months.regular", ReportTemplateFieldType.MONEY, TemplateRowSet.MONTHS, "18000.00"),
    MONTHS_OPENING("rows.months.opening", ReportTemplateFieldType.MONEY, TemplateRowSet.MONTHS, "0.00"),
    MONTHS_REVERSALS("rows.months.reversals", ReportTemplateFieldType.MONEY, TemplateRowSet.MONTHS, "-30.00"),
    MONTHS_ADJUSTMENTS("rows.months.adjustments", ReportTemplateFieldType.MONEY, TemplateRowSet.MONTHS, "-10.00"),
    MONTHS_EFFECTIVE("rows.months.effective", ReportTemplateFieldType.MONEY, TemplateRowSet.MONTHS, "17960.00"),

    // ---- row set meters: the electricity tree's consumption sheet
    METERS_LEVEL("rows.meters.level", ReportTemplateFieldType.INT, TemplateRowSet.METERS, "1"),
    METERS_NAME("rows.meters.name", ReportTemplateFieldType.TEXT, TemplateRowSet.METERS, null),
    METERS_SERIAL("rows.meters.serial", ReportTemplateFieldType.TEXT, TemplateRowSet.METERS, "S-1234"),
    METERS_KIND("rows.meters.kind", ReportTemplateFieldType.TEXT, TemplateRowSet.METERS, null),
    METERS_START_VALUE("rows.meters.startValue", ReportTemplateFieldType.QUANTITY, TemplateRowSet.METERS,
            "10000.000"),
    METERS_END_VALUE("rows.meters.endValue", ReportTemplateFieldType.QUANTITY, TemplateRowSet.METERS, "12300.000"),
    METERS_CONSUMPTION("rows.meters.consumption", ReportTemplateFieldType.QUANTITY, TemplateRowSet.METERS,
            "2300.000"),
    METERS_CHILDREN_CONSUMPTION("rows.meters.childrenConsumption", ReportTemplateFieldType.QUANTITY,
            TemplateRowSet.METERS, "2200.000"),
    METERS_LOSSES_KWH("rows.meters.lossesKwh", ReportTemplateFieldType.QUANTITY, TemplateRowSet.METERS, "100.000"),
    METERS_LOSSES_PERCENT("rows.meters.lossesPercent", ReportTemplateFieldType.NUMBER, TemplateRowSet.METERS,
            "4.35"),
    METERS_PLOTS_FED("rows.meters.plotsFed", ReportTemplateFieldType.INT, TemplateRowSet.METERS, "40"),

    // ---- row set water: the water report's plots, one row per plot and source
    WATER_NUMBER("rows.water.number", ReportTemplateFieldType.TEXT, TemplateRowSet.WATER, "12"),
    WATER_STREET("rows.water.street", ReportTemplateFieldType.TEXT, TemplateRowSet.WATER, null),
    WATER_SOURCE("rows.water.source", ReportTemplateFieldType.TEXT, TemplateRowSet.WATER, null),
    WATER_CONNECTED_DAYS("rows.water.connectedDays", ReportTemplateFieldType.INT, TemplateRowSet.WATER, "31"),
    WATER_SERIAL("rows.water.serial", ReportTemplateFieldType.TEXT, TemplateRowSet.WATER, "WS-12"),
    WATER_CUBIC_METRES("rows.water.cubicMetres", ReportTemplateFieldType.QUANTITY, TemplateRowSet.WATER, "30.000"),
    WATER_TOTAL("rows.water.total", ReportTemplateFieldType.MONEY, TemplateRowSet.WATER, "175.00");

    /** What a key may look like (ADR 0015): the text between {@code ${} and {@code }}. */
    static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.\\-]*");

    /** The last part of the description code of a family. */
    static final String FAMILY_FIELD = "byChargeType";

    /** Every placeholder needs this permission; generating from a template needs it too. */
    static final Permission PERMISSION = Permission.REPORTS_VIEW;

    private final String key;
    private final ReportTemplateFieldType type;
    private final @Nullable TemplateRowSet rowSet;
    private final @Nullable String example;
    private final boolean family;

    TemplateField(String key, ReportTemplateFieldType type, @Nullable TemplateRowSet rowSet,
            @Nullable String example) {
        this(key, type, rowSet, example, false);
    }

    TemplateField(String key, ReportTemplateFieldType type, @Nullable TemplateRowSet rowSet,
            @Nullable String example, boolean family) {
        this.key = key;
        this.type = type;
        this.rowSet = rowSet;
        this.example = example;
        this.family = family;
    }

    /** The key of a fixed placeholder; for a family the prefix its keys start with (ending in a dot). */
    String key() {
        return key;
    }

    ReportTemplateFieldType type() {
        return type;
    }

    /** The row set a placeholder repeats over; null for a single value. */
    @Nullable TemplateRowSet rowSet() {
        return rowSet;
    }

    @Nullable String example() {
        return example;
    }

    /** True for a family of placeholders, one per charge type. */
    boolean family() {
        return family;
    }

    /** The description code: {@code reportTemplates.fields.<field>} in the catalogs. */
    String field() {
        return family ? key + FAMILY_FIELD : key;
    }

    /**
     * One placeholder: a fixed field, or a field of a family with its charge type.
     *
     * @param key        the key as a template writes it
     * @param chargeType the charge type of a family's placeholder; null otherwise
     */
    record Placeholder(TemplateField field, String key, @Nullable ChargeTypeFacts chargeType) {

        Placeholder {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(key, "key");
            if (field.family() != (chargeType != null)) {
                throw new IllegalArgumentException("A family's placeholder, and only it, has a charge type");
            }
        }

        /** The catalog entry of the API. */
        ReportTemplateField toDto() {
            ReportTemplateField dto = new ReportTemplateField(key, field.field(), field.type(),
                    nadel.contract.model.Permission.fromValue(PERMISSION.name()));
            if (field.rowSet() != null) {
                dto.setRowSet(field.rowSet().contract());
            }
            if (field.example() != null) {
                dto.setExample(field.example());
            }
            if (chargeType != null) {
                dto.setPattern(field.key() + "<type>");
                dto.setChargeTypeId(chargeType.id());
            }
            return dto;
        }
    }

    /** The key of a charge type in a family: the code of a seeded type, the id of a custom one. */
    static String typeKey(ChargeTypeFacts type) {
        return type.code() != null ? type.code() : type.id().toString();
    }

    /**
     * The catalog: every fixed field, and each family once per charge type in the configured order, in the order of
     * the constants.
     */
    static List<Placeholder> catalog(List<ChargeTypeFacts> chargeTypes) {
        List<Placeholder> entries = new ArrayList<>();
        for (TemplateField field : values()) {
            if (!field.family) {
                entries.add(new Placeholder(field, field.key, null));
                continue;
            }
            for (ChargeTypeFacts type : chargeTypes) {
                entries.add(new Placeholder(field, field.key + typeKey(type), type));
            }
        }
        return entries;
    }

    /**
     * The placeholder of a key; empty when the app does not know it (not in the catalog, or a family's charge type the
     * cooperative does not have). A family's charge type is named by the code of a seeded type or the id of any type
     * of the cooperative, archived ones included.
     */
    static Optional<Placeholder> resolve(String key, List<ChargeTypeFacts> chargeTypes) {
        for (TemplateField field : values()) {
            if (!field.family) {
                if (field.key.equals(key)) {
                    return Optional.of(new Placeholder(field, key, null));
                }
            } else if (key.startsWith(field.key) && key.length() > field.key.length()) {
                String token = key.substring(field.key.length());
                Optional<ChargeTypeFacts> type = chargeType(token, chargeTypes);
                if (type.isPresent()) {
                    return Optional.of(new Placeholder(field, key, type.get()));
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<ChargeTypeFacts> chargeType(String token, List<ChargeTypeFacts> chargeTypes) {
        for (ChargeTypeFacts type : chargeTypes) {
            if (token.equals(type.code())) {
                return Optional.of(type);
            }
        }
        // the id as written, ignoring case; UUID.fromString would also take short forms such as 1-1-1-1-1
        return chargeTypes.stream().filter(type -> type.id().toString().equalsIgnoreCase(token)).findFirst();
    }
}
