package app.nadel.treasury;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * What the cash book report tests (feature 018 §2.12) share on top of {@link TreasuryApiSupport}: the report requests,
 * charges written as the database owner so payments saved through the API have something to be allocated to, and the
 * clean-up of those charges (the treasury fixtures leave the charges alone).
 */
abstract class TreasuryReportsSupport extends TreasuryApiSupport {

    static final String REPORTS = "/api/v1/reports";

    @Override
    void removeCooperative(UUID tenant) {
        asNadelOwner(true, sql -> {
            sql.update("delete from allocation where tenant_id = ? and releases_allocation_id is not null", tenant);
            sql.update("delete from allocation where tenant_id = ?", tenant);
            sql.update("delete from charge where tenant_id = ? and (reverses_charge_id is not null "
                    + "or adjusts_charge_id is not null)", tenant);
            sql.update("delete from charge where tenant_id = ?", tenant);
        });
        super.removeCooperative(tenant);
    }

    // ---- requests

    /** {@code GET /reports/{code}} with the query parameters given as name, value pairs. */
    ResultActions report(String bearer, String code, String... params) throws Exception {
        return send(bearer, withParams(get(REPORTS + "/" + code), params), null);
    }

    /** {@code GET /reports/{code}.xlsx} with the query parameters given as name, value pairs. */
    ResultActions reportFile(String bearer, String code, String... params) throws Exception {
        return send(bearer, withParams(get(REPORTS + "/" + code + ".xlsx"), params), null);
    }

    /** The preview of a report for the days {@code from} to {@code to}; 200 expected. */
    JsonNode preview(String bearer, String code, LocalDate from, LocalDate to, String... params) throws Exception {
        String[] all = new String[params.length + 4];
        all[0] = "from";
        all[1] = from.toString();
        all[2] = "to";
        all[3] = to.toString();
        System.arraycopy(params, 0, all, 4, params.length);
        return body(report(bearer, code, all).andExpect(status().isOk()));
    }

    /** The codes of {@code GET /reports} for the caller. */
    List<String> listedCodes(String bearer) throws Exception {
        List<String> codes = new ArrayList<>();
        for (JsonNode report : body(send(bearer, get(REPORTS), null).andExpect(status().isOk()))) {
            codes.add(report.get("code").asString());
        }
        return codes;
    }

    private static MockHttpServletRequestBuilder withParams(MockHttpServletRequestBuilder request, String... params) {
        for (int index = 0; index + 1 < params.length; index += 2) {
            request.param(params[index], params[index + 1]);
        }
        return request;
    }

    // ---- reading a preview

    static JsonNode sheet(JsonNode preview, String key) {
        for (JsonNode sheet : preview.get("sheets")) {
            if (sheet.get("key").asString().equals(key)) {
                return sheet;
            }
        }
        throw new AssertionError("No sheet " + key);
    }

    /** The value of the cell of the column {@code key} in a row or totals line; null when empty. */
    static String cell(JsonNode sheet, JsonNode row, String key) {
        int index = 0;
        for (JsonNode column : sheet.get("columns")) {
            if (column.get("key").asString().equals(key)) {
                JsonNode value = row.get("cells").get(index).get("value");
                return value == null || value.isNull() ? null : value.asString();
            }
            index++;
        }
        throw new AssertionError("No column " + key);
    }

    static JsonNode total(JsonNode sheet, String key) {
        for (JsonNode total : sheet.get("totals")) {
            if (total.get("key").asString().equals(key)) {
                return total;
            }
        }
        throw new AssertionError("No total " + key);
    }

    /** The rows of the sheet whose column {@code key} holds {@code value}. */
    static List<JsonNode> rowsWith(JsonNode sheet, String key, String value) {
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode row : sheet.get("rows")) {
            if (value.equals(cell(sheet, row, key))) {
                rows.add(row);
            }
        }
        return rows;
    }

    /** The balance of the account on «Каса і банк» at the end of the day. */
    String balanceOn(String bearer, String kind, LocalDate day) throws Exception {
        return accountOf(body(accounts(bearer, day).andExpect(status().isOk())), kind).get("balance").asString();
    }

    // ---- ledger fixtures (superuser)

    UUID chargeType(UUID tenant, String code) {
        return ownerJdbc().queryForObject("select id from charge_type where tenant_id = ? and code = ?", UUID.class,
                tenant, code);
    }

    /** A regular charge of the plot accrued on {@code day} (its month's billing month, created when missing). */
    UUID charge(UUID tenant, UUID plot, String typeCode, String amount, LocalDate day) {
        String month = YearMonth.from(day).atDay(1).toString();
        ownerJdbc().update("insert into billing_month (tenant_id, month) values (?, ?::date) "
                + "on conflict (tenant_id, month) do nothing", tenant, month);
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, "
                + "source, amount, service_from, service_to, accrued_on, source_key) select ?, ?, ?, ?, bm.id, "
                + "'REGULAR', 'TARIFF', ?, ?, ?, ?, ? from billing_month bm where bm.tenant_id = ? "
                + "and bm.month = ?::date", id, tenant, plot, chargeType(tenant, typeCode), new BigDecimal(amount),
                day, day, day, "TEST:" + id, tenant, month);
        return id;
    }
}
