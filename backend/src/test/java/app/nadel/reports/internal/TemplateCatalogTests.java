package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The field catalog through the API ({@code GET /report-templates/catalog}, feature 013 §4.2) in a cooperative of its
 * own with a custom charge type and an archived one: the charge type families are listed once per type (seeded types
 * by code, custom ones by id), each entry with its type, row set, example and permission; the catalog is open to
 * {@code REPORTS_VIEW} or {@code REPORT_TEMPLATES_MANAGE} and to nobody else.
 */
class TemplateCatalogTests extends ReportsTestSupport {

    private static final String CATALOG = "/api/v1/report-templates/catalog";

    @Test
    void theChargeTypeFamiliesAreListedOncePerTypeOfTheCooperativeByCodeOrById() throws Exception {
        UUID guard = UUID.randomUUID();
        UUID old = UUID.randomUUID();
        ownerJdbc().update("insert into charge_type (id, tenant_id, name, name_customized, position) "
                + "values (?, ?, 'Охорона', true, 8)", guard, tenant);
        ownerJdbc().update("insert into charge_type (id, tenant_id, name, name_customized, position, archived_at) "
                + "values (?, ?, 'Сміття', true, 9, now())", old, tenant);

        JsonNode catalog = body(send(chairman, get(CATALOG)).andExpect(status().isOk()));

        assertThat(keys(catalog, "totals.charged.")).containsExactly("totals.charged.MEMBERSHIP",
                "totals.charged.ELECTRICITY", "totals.charged.LAND_TAX", "totals.charged.WATER",
                "totals.charged.COMMON_CONSUMERS", "totals.charged." + guard, "totals.charged." + old);
        assertThat(keys(catalog, "rows.plots.debt.")).containsExactly("rows.plots.debt.total",
                "rows.plots.debt.MEMBERSHIP", "rows.plots.debt.ELECTRICITY", "rows.plots.debt.LAND_TAX",
                "rows.plots.debt.WATER", "rows.plots.debt.COMMON_CONSUMERS", "rows.plots.debt." + guard,
                "rows.plots.debt." + old);

        JsonNode custom = entry(catalog, "rows.plots.debt." + guard);
        assertThat(custom.get("field").asString()).isEqualTo("rows.plots.debt.byChargeType");
        assertThat(custom.get("pattern").asString()).isEqualTo("rows.plots.debt.<type>");
        assertThat(custom.get("chargeTypeId").asString()).isEqualTo(guard.toString());
        assertThat(custom.get("type").asString()).isEqualTo("MONEY");
        assertThat(custom.get("rowSet").asString()).isEqualTo("PLOTS");
        assertThat(custom.get("example").asString()).isEqualTo("600.00");
        assertThat(custom.get("permission").asString()).isEqualTo("REPORTS_VIEW");

        JsonNode first = catalog.get(0);
        assertThat(first.get("key").asString()).isEqualTo("cooperative.name");
        assertThat(first.get("field").asString()).isEqualTo("cooperative.name");
        assertThat(first.get("type").asString()).isEqualTo("TEXT");
        assertThat(first.has("rowSet") && !first.get("rowSet").isNull()).isFalse();
        assertThat(first.has("example") && !first.get("example").isNull()).isFalse();
        JsonNode landTax = entry(catalog, "totals.landTax.expected");
        assertThat(landTax.get("example").asString()).isEqualTo("12600.00");
        assertThat(entry(catalog, "rows.meters.endValue").get("type").asString()).isEqualTo("QUANTITY");
        assertThat(entry(catalog, "rows.months.month").get("type").asString()).isEqualTo("MONTH");
        // three families of seven charge types each
        assertThat(catalog.size()).isEqualTo(TemplateField.values().length - 3 + 3 * 7);

        // feature 015: the paid totals per type, the prepayment and the payments rows need PAYMENTS_VIEW
        assertThat(keys(catalog, "totals.paid.")).containsExactly("totals.paid.MEMBERSHIP", "totals.paid.ELECTRICITY",
                "totals.paid.LAND_TAX", "totals.paid.WATER", "totals.paid.COMMON_CONSUMERS", "totals.paid." + guard,
                "totals.paid." + old);
        JsonNode paid = entry(catalog, "totals.paid.MEMBERSHIP");
        assertThat(paid.get("field").asString()).isEqualTo("totals.paid.byChargeType");
        assertThat(paid.get("pattern").asString()).isEqualTo("totals.paid.<type>");
        assertThat(paid.get("permission").asString()).isEqualTo("PAYMENTS_VIEW");
        assertThat(entry(catalog, "totals.prepayment").get("permission").asString()).isEqualTo("PAYMENTS_VIEW");
        assertThat(keys(catalog, "rows.payments.")).containsExactly("rows.payments.receivedOn",
                "rows.payments.billingMonth", "rows.payments.source", "rows.payments.document",
                "rows.payments.number", "rows.payments.street", "rows.payments.amount", "rows.payments.allocated",
                "rows.payments.prepayment", "rows.payments.reversed");
        catalog.forEach(entry -> {
            String key = entry.get("key").asString();
            boolean payment = key.startsWith("totals.paid.") || key.equals("totals.prepayment")
                    || key.startsWith("rows.payments.");
            assertThat(entry.get("permission").asString()).as(key)
                    .isEqualTo(payment ? "PAYMENTS_VIEW" : "REPORTS_VIEW");
            if (key.startsWith("rows.payments.")) {
                assertThat(entry.get("rowSet").asString()).isEqualTo("PAYMENTS");
            }
        });
        // the payer and the payer note are never placeholders
        assertThat(keys(catalog, "rows.payments.")).noneMatch(key -> key.contains("payer") || key.contains("note"));
    }

    @Test
    void theCatalogIsOpenToViewersOfReportsAndToTemplateManagersOnly() throws Exception {
        send(treasurer, get(CATALOG)).andExpect(status().isOk());
        send(personWithPermissions("Шаблони", "REPORT_TEMPLATES_MANAGE"), get(CATALOG)).andExpect(status().isOk());
        send(personWithPermissions("Звіти", "REPORTS_VIEW"), get(CATALOG)).andExpect(status().isOk());

        for (String caller : List.of(member, personWithPermissions("Електрик", "METERS_VIEW", "READINGS_ENTER"))) {
            send(caller, get(CATALOG)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        send(null, get(CATALOG)).andExpect(status().isUnauthorized());
    }

    private static List<String> keys(JsonNode catalog, String prefix) {
        List<String> keys = new ArrayList<>();
        catalog.forEach(entry -> {
            String key = entry.get("key").asString();
            if (key.startsWith(prefix)) {
                keys.add(key);
            }
        });
        return keys;
    }

    private static JsonNode entry(JsonNode catalog, String key) {
        for (JsonNode entry : catalog) {
            if (entry.get("key").asString().equals(key)) {
                return entry;
            }
        }
        throw new AssertionError("No entry " + key);
    }
}
