package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.money.Money;
import app.nadel.reports.internal.TemplateField.Placeholder;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import nadel.contract.model.ReportTemplateField;
import nadel.contract.model.ReportTemplateFieldType;
import nadel.contract.model.ReportTemplateRowSet;
import nadel.contract.model.ReportTemplateValueReason;
import nadel.contract.model.ReportTemplateWarningCode;
import org.junit.jupiter.api.Test;

/**
 * The field catalog of report templates ({@link TemplateField}, feature 013 §4.2): keys are unique and well-formed,
 * hold no personal data, belong to the row set their prefix names, have their examples in the format of their type and
 * a description in both languages; the charge type families are expanded and resolved by code and by id. The texts of
 * the warnings of a generated file exist in both languages too. (That the bundles say what the web catalogs say is
 * checked on the web side, {@code web/src/reportBundles.test.ts}.)
 */
class TemplateFieldTests {

    private static final ChargeTypeFacts MEMBERSHIP = type("MEMBERSHIP", false);
    private static final ChargeTypeFacts LAND_TAX = type("LAND_TAX", false);
    private static final ChargeTypeFacts GUARD = type(null, false);
    private static final ChargeTypeFacts OLD = type(null, true);
    private static final List<ChargeTypeFacts> TYPES = List.of(MEMBERSHIP, LAND_TAX, GUARD, OLD);

    /** Parts of a key that would name or reach a person. */
    private static final List<String> PERSONAL = List.of("phone", "email", "mail", "contact", "owner.", "ownername",
            "owners.", "firstname", "lastname", "middlename", "fullname", "member", "person", "birth", "passport",
            "tax.id", "taxid", "rnokpp");

    @Test
    void everyKeyIsUniqueWellFormedAndItsDescriptionCodeIsUnique() {
        List<Placeholder> catalog = TemplateField.catalog(TYPES);

        assertThat(catalog).extracting(Placeholder::key).doesNotHaveDuplicates()
                .allMatch(key -> TemplateField.KEY.matcher(key).matches(), "matches " + TemplateField.KEY);
        assertThat(catalog.stream().filter(entry -> entry.chargeType() == null).map(entry -> entry.field().field()))
                .doesNotHaveDuplicates();
        assertThat(TemplateField.values()).extracting(TemplateField::field).doesNotHaveDuplicates();
    }

    @Test
    void noKeyNamesOrReachesAPerson() {
        // a family's key ends in a charge type's code or id, not in a name of the catalog
        for (TemplateField field : TemplateField.values()) {
            String key = field.key().toLowerCase(Locale.ROOT) + (field.family() ? "" : ".");
            assertThat(PERSONAL).as(field.key()).noneMatch(key::contains);
        }
        // the plots carry how many owners there are, never who they are
        assertThat(TemplateField.PLOTS_OWNERS_COUNT.type()).isEqualTo(ReportTemplateFieldType.INT);
    }

    @Test
    void aKeyBelongsToTheRowSetItsPrefixNamesAndTheCatalogKeepsTheOrderOfTheSets() {
        for (TemplateField field : TemplateField.values()) {
            assertThat(TemplateRowSet.of(field.family() ? field.key() + "X" : field.key()).orElse(null))
                    .as(field.key()).isEqualTo(field.rowSet());
        }
        assertThat(TemplateField.catalog(TYPES).stream().map(entry -> entry.field().rowSet())
                .filter(set -> set != null).distinct().toList())
                .containsExactly(TemplateRowSet.PLOTS, TemplateRowSet.MONTHS, TemplateRowSet.METERS,
                        TemplateRowSet.WATER, TemplateRowSet.PAYMENTS);
    }

    @Test
    void everyExampleIsInTheFormatOfItsType() {
        for (TemplateField field : TemplateField.values()) {
            String example = field.example();
            if (example == null) {
                assertThat(field.type()).as(field.key()).isEqualTo(ReportTemplateFieldType.TEXT);
                continue;
            }
            Object parsed = switch (field.type()) {
                case TEXT -> example;
                case NUMBER, QUANTITY -> new BigDecimal(example);
                case MONEY -> Money.parse(example);
                case INT -> Long.valueOf(example);
                case DATE -> LocalDate.parse(example);
                case MONTH -> YearMonth.parse(example);
                case DATE_TIME -> LocalDateTime.parse(example);
            };
            assertThat(parsed).as(field.key()).isNotNull();
            if (field.type() == ReportTemplateFieldType.QUANTITY) {
                assertThat(new BigDecimal(example).scale()).as(field.key()).isEqualTo(3);
            }
        }
    }

    @Test
    void theFamiliesAreListedOncePerChargeTypeByCodeOrByIdInTheConfiguredOrder() {
        List<String> charged = TemplateField.catalog(TYPES).stream()
                .filter(entry -> entry.field() == TemplateField.TOTALS_CHARGED).map(Placeholder::key).toList();
        assertThat(charged).containsExactly("totals.charged.MEMBERSHIP", "totals.charged.LAND_TAX",
                "totals.charged." + GUARD.id(), "totals.charged." + OLD.id());

        ReportTemplateField guard = TemplateField.catalog(TYPES).stream()
                .filter(entry -> entry.key().equals("rows.plots.debt." + GUARD.id())).findFirst().orElseThrow()
                .toDto();
        assertThat(guard.getField()).isEqualTo("rows.plots.debt.byChargeType");
        assertThat(guard.getPattern()).isEqualTo("rows.plots.debt.<type>");
        assertThat(guard.getChargeTypeId()).isEqualTo(GUARD.id());
        assertThat(guard.getType()).isEqualTo(ReportTemplateFieldType.MONEY);
        assertThat(guard.getRowSet()).isEqualTo(ReportTemplateRowSet.PLOTS);
        assertThat(guard.getPermission()).isEqualTo(nadel.contract.model.Permission.REPORTS_VIEW);

        ReportTemplateField name = TemplateField.catalog(TYPES).getFirst().toDto();
        assertThat(name.getKey()).isEqualTo("cooperative.name");
        assertThat(name.getField()).isEqualTo("cooperative.name");
        assertThat(name.getRowSet()).isNull();
        assertThat(name.getPattern()).isNull();
        assertThat(name.getChargeTypeId()).isNull();
        assertThat(name.getExample()).isNull();
    }

    @Test
    void aKeyResolvesToItsFieldAndAChargeTypeByCodeOrIdAndAnythingElseIsUnknown() {
        assertThat(TemplateField.resolve("totals.debt", TYPES).orElseThrow().field())
                .isEqualTo(TemplateField.TOTALS_DEBT);
        Placeholder byCode = TemplateField.resolve("totals.charged.LAND_TAX", TYPES).orElseThrow();
        assertThat(byCode.field()).isEqualTo(TemplateField.TOTALS_CHARGED);
        assertThat(byCode.chargeType()).isEqualTo(LAND_TAX);
        assertThat(TemplateField.resolve("totals.charged." + MEMBERSHIP.id(), TYPES).orElseThrow().chargeType())
                .isEqualTo(MEMBERSHIP);
        assertThat(TemplateField.resolve("rows.plots.debt." + OLD.id().toString().toUpperCase(Locale.ROOT), TYPES)
                .orElseThrow().chargeType()).isEqualTo(OLD);

        assertThat(TemplateField.resolve("nonsense.field", TYPES)).isEmpty();
        assertThat(TemplateField.resolve("totals.charged.WATER", TYPES)).as("a type the cooperative lacks").isEmpty();
        assertThat(TemplateField.resolve("totals.charged.", TYPES)).isEmpty();
        assertThat(TemplateField.resolve("totals.charged.membership", TYPES)).isEmpty();
        assertThat(TemplateField.resolve("rows.plots.debt.1-1-1-1-1", TYPES)).isEmpty();
        assertThat(TemplateField.resolve("cooperative.name.x", TYPES)).isEmpty();
        assertThat(TemplateField.resolve("rows.plots.debt.total", TYPES).orElseThrow().field())
                .isEqualTo(TemplateField.PLOTS_DEBT_TOTAL);
    }

    @Test
    void everyFieldHasADescriptionInBothLanguages() {
        for (ReportTexts texts : List.of(ReportTexts.of(ReportLanguage.UK), ReportTexts.of(ReportLanguage.EN))) {
            for (TemplateField field : TemplateField.values()) {
                String key = "reportTemplates.fields." + field.field();
                assertThat(texts.keys()).as(texts.language() + " " + key).contains(key);
                if (field.family()) {
                    assertThat(texts.text(key)).as(key).contains("{chargeType}");
                }
            }
        }
    }

    @Test
    void theWarningsOfAGeneratedFileHaveTheirTextsInBothLanguages() {
        for (ReportTexts texts : List.of(ReportTexts.of(ReportLanguage.UK), ReportTexts.of(ReportLanguage.EN))) {
            for (ReportTemplateWarningCode code : ReportTemplateWarningCode.values()) {
                assertThat(texts.keys()).contains("reportTemplates.warnings." + code.getValue());
            }
            for (ReportTemplateValueReason reason : ReportTemplateValueReason.values()) {
                assertThat(texts.keys()).contains("reportTemplates.valueReasons." + reason.getValue());
            }
            for (String column : List.of("sheet", "cell", "placeholder", "warning", "reason")) {
                assertThat(texts.keys()).contains("reportTemplates.warningsSheet.columns." + column);
            }
        }
        assertThat(ReportTexts.of(ReportLanguage.UK).text("reportTemplates.warningsSheet.name"))
                .isEqualTo("Попередження");
        assertThat(ReportTexts.of(ReportLanguage.EN).text("reportTemplates.warningsSheet.name")).isEqualTo("Warnings");
    }

    private static ChargeTypeFacts type(String code, boolean archived) {
        return new ChargeTypeFacts(UUID.randomUUID(), code, code == null ? "Охорона" : code, code == null, !archived,
                archived, 0);
    }
}
