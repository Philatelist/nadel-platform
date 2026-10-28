package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.JsonNode;

/**
 * Billing months through the API (feature 011 §2.2): the list from the first month to the current one with totals per
 * charge type, the month page with its check list, closing in order (409 {@code PERIOD_CLOSE_ORDER}), a closed month
 * that gets nothing more (a late charge goes to the earliest open month with its own service period and date), future
 * months, who may do what, the audit entry and the database guard of a closed month. "Received" (feature 015 §6): the
 * payments booked in the month, reversals of payments negative, a payment of a closed month in the open month. Months
 * are relative to the current month in Europe/Kyiv, so the tests do not age.
 */
class BillingMonthsTests extends PaymentApiSupport {

    private static final String MONTHS = "/api/v1/billing-months";
    private static final String MONTH = "/api/v1/billing-months/%s";
    private static final String CLOSE = "/api/v1/billing-months/%s/close";

    private final List<UUID> meters = new ArrayList<>();

    @AfterEach
    void removeMeters() {
        for (UUID meter : meters) {
            ownerJdbc().update("delete from meter_reading where meter_id = ?", meter);
            ownerJdbc().update("delete from meter where id = ?", meter);
        }
        meters.clear();
    }

    private static YearMonth current() {
        return YearMonth.now(ZoneId.of("Europe/Kyiv"));
    }

    private static String ym(int monthsAgo) {
        return current().minusMonths(monthsAgo).toString();
    }

    private JsonNode list(String bearer) throws Exception {
        return body(send(bearer, get(MONTHS), null).andExpect(status().isOk()));
    }

    private static JsonNode entry(JsonNode months, String month) {
        for (JsonNode m : months) {
            if (m.get("month").asString().equals(month)) {
                return m;
            }
        }
        throw new AssertionError("No month " + month + " in " + months);
    }

    private static JsonNode total(JsonNode month, String code) {
        for (JsonNode t : month.get("totals")) {
            if (t.has("chargeTypeCode") && t.get("chargeTypeCode").asString().equals(code)) {
                return t;
            }
        }
        throw new AssertionError("No total of " + code + " in " + month);
    }

    // ---- the list and the totals

    @Test
    void theListRunsFromTheFirstMonthToTheCurrentOneNewestFirstWithTotalsPerChargeType() throws Exception {
        UUID plot = newPlot();
        month(DEMO_TENANT, ym(3));
        tariffCharge(plot, "MEMBERSHIP", "25.00", ym(1));
        tariffCharge(plot, "LAND_TAX", "17.50", ym(1));
        tariffCharge(newPlot(), "MEMBERSHIP", "30.00", ym(1));

        JsonNode months = list(chairman());

        List<String> listed = new ArrayList<>();
        months.forEach(m -> listed.add(m.get("month").asString()));
        assertThat(listed).startsWith(ym(0), ym(1), ym(2), ym(3));
        JsonNode withCharges = entry(months, ym(1));
        assertThat(withCharges.get("status").asString()).isEqualTo("OPEN");
        assertThat(withCharges.get("runDone").asBoolean()).isFalse();
        assertThat(withCharges.get("totals")).hasSize(2);
        assertThat(withCharges.get("totals").get(0).get("chargeTypeCode").asString()).isEqualTo("MEMBERSHIP");
        assertThat(total(withCharges, "MEMBERSHIP").get("amount").asString()).isEqualTo("55.00");
        assertThat(total(withCharges, "MEMBERSHIP").get("nameCustomized").asBoolean()).isFalse();
        assertThat(total(withCharges, "LAND_TAX").get("amount").asString()).isEqualTo("17.50");
        assertThat(withCharges.get("total").asString()).isEqualTo("72.50");
        // a month without a row and the current month: open, nothing charged
        for (String empty : List.of(ym(2), ym(0))) {
            assertThat(entry(months, empty).get("status").asString()).isEqualTo("OPEN");
            assertThat(entry(months, empty).get("totals")).isEmpty();
            assertThat(entry(months, empty).get("total").asString()).isEqualTo("0.00");
        }
        // listing creates no row
        assertThat(ownerJdbc().queryForObject("select count(*) from billing_month where tenant_id = ? and month = ?",
                Integer.class, DEMO_TENANT, current().minusMonths(2).atDay(1))).isZero();
    }

    @Test
    void aReversalCountsNegativeInTheTotalsOfItsMonth() throws Exception {
        UUID charge = tariffCharge(newPlot(), "MEMBERSHIP", "25.00", ym(1));
        send(chairman(), post(REVERSE.formatted(charge)), Map.of("reason", "Помилкове нарахування"))
                .andExpect(status().isCreated());

        JsonNode month = entry(list(chairman()), ym(1));

        assertThat(total(month, "MEMBERSHIP").get("amount").asString()).isEqualTo("0.00");
        assertThat(month.get("total").asString()).isEqualTo("0.00");
    }

    @Test
    void aCooperativeWithoutMonthsListsNothing() throws Exception {
        body(send(otherChairman(), get(MONTHS), null).andExpect(status().isOk()));
        assertThat(list(otherChairman())).isEmpty();
    }

    // ---- received (feature 015 §6)

    @Test
    void receivedIsWhatThePaymentsOfTheMonthBroughtWithReversalsNegative() throws Exception {
        UUID plot = newPlot();
        tariffCharge(plot, "MEMBERSHIP", "25.00", ym(2));
        String day = current().minusMonths(1).atDay(10).toString();
        saved(treasurer(), payment(plot, "CASH", "100.00", day, null));
        UUID bank = saved(treasurer(), payment(plot, "BANK", "30.00", day, null));
        reverse(chairman(), bank, "Помилковий платіж").andExpect(status().isCreated());

        JsonNode months = list(chairman());

        assertThat(entry(months, ym(1)).get("received").asString()).isEqualTo("100.00");
        assertThat(entry(months, ym(1)).get("total").asString()).isEqualTo("0.00"); // charged nothing
        // charged, nothing received; and a month without a row
        assertThat(entry(months, ym(2)).get("received").asString()).isEqualTo("0.00");
        assertThat(entry(months, ym(2)).get("total").asString()).isEqualTo("25.00");
        assertThat(entry(months, ym(0)).get("received").asString()).isEqualTo("0.00");
        JsonNode page = body(send(treasurer(), get(MONTH.formatted(ym(1))), null).andExpect(status().isOk()));
        assertThat(page.get("received").asString()).isEqualTo("100.00");
        assertThat(body(send(treasurer(), get(MONTH.formatted(ym(0))), null).andExpect(status().isOk()))
                .get("received").asString()).isEqualTo("0.00");
    }

    @Test
    void aLatePaymentOfAClosedMonthIsReceivedInTheOpenMonthItIsBookedIn() throws Exception {
        UUID plot = newPlot();
        tariffCharge(plot, "MEMBERSHIP", "25.00", ym(2));
        send(chairman(), post(CLOSE.formatted(ym(2))), null).andExpect(status().isOk());

        UUID late = saved(treasurer(), payment(plot, "CASH", "25.00", current().minusMonths(2).atDay(20).toString(),
                null));

        JsonNode months = list(chairman());
        assertThat(entry(months, ym(2)).get("received").asString()).isEqualTo("0.00");
        assertThat(entry(months, ym(1)).get("received").asString()).isEqualTo("25.00");
        assertThat(body(card(treasurer(), late).andExpect(status().isOk())).get("billingMonth").asString())
                .isEqualTo(ym(1));
        // the reversal is booked in the open month too and nets it
        reverse(chairman(), late, "Помилка").andExpect(status().isCreated());
        assertThat(entry(list(chairman()), ym(1)).get("received").asString()).isEqualTo("0.00");
    }

    // ---- the month page and its check list

    @Test
    void theMonthPageShowsTheTotalsAndTheCheckList() throws Exception {
        UUID plot = newPlot();
        tariffCharge(plot, "MEMBERSHIP", "25.00", ym(1));
        LocalDate today = kyivToday();
        // installed long ago, never read: on the list with its installation date
        UUID stale = plotMeter(plot, "ELECTRICITY", today.minusDays(200));
        // read 10 days ago: not on the list
        UUID read = plotMeter(newPlot(), "ELECTRICITY", today.minusDays(200));
        reading(read, today.minusDays(10), "100");
        // installed 30 days ago: not on the list
        UUID young = plotMeter(newPlot(), "WATER", today.minusDays(30));

        JsonNode month = body(send(chairman(), get(MONTH.formatted(ym(0))), null).andExpect(status().isOk()));

        assertThat(month.get("month").asString()).isEqualTo(ym(0));
        assertThat(month.get("status").asString()).isEqualTo("OPEN");
        JsonNode checklist = month.get("checklist");
        assertThat(checklist.get("runDone").asBoolean()).isFalse();
        assertThat(checklist.get("notChargedCount").asInt()).isZero();
        List<String> staleIds = new ArrayList<>();
        checklist.get("staleMeters").forEach(m -> staleIds.add(m.get("meterId").asString()));
        assertThat(staleIds).contains(stale.toString()).doesNotContain(read.toString(), young.toString());
        for (JsonNode meter : checklist.get("staleMeters")) {
            if (meter.get("meterId").asString().equals(stale.toString())) {
                assertThat(meter.get("plotId").asString()).isEqualTo(plot.toString());
                assertThat(meter.get("plotNumber").asString()).isEqualTo(plotNumber(plot));
                assertThat(meter.get("resource").asString()).isEqualTo("ELECTRICITY");
                assertThat(meter.get("lastReadingOn").asString()).isEqualTo(today.minusDays(200).toString());
            }
        }

        // a past month is checked as of its last day: the meter read 10 days ago was stale then
        LocalDate lastDayAgo = current().minusMonths(1).atEndOfMonth();
        JsonNode previous = body(send(chairman(), get(MONTH.formatted(ym(1))), null).andExpect(status().isOk()));
        assertThat(total(previous, "MEMBERSHIP").get("amount").asString()).isEqualTo("25.00");
        List<String> previousIds = new ArrayList<>();
        previous.get("checklist").get("staleMeters").forEach(m -> previousIds.add(m.get("meterId").asString()));
        assertThat(previousIds).contains(stale.toString());
        if (today.minusDays(10).isAfter(lastDayAgo)) {
            assertThat(previousIds).contains(read.toString());
        }
    }

    @Test
    void aMonthThatIsNotListedIsNotFound() throws Exception {
        tariffCharge(newPlot(), "MEMBERSHIP", "25.00", ym(1));

        send(chairman(), get(MONTH.formatted(ym(2))), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        send(chairman(), get(MONTH.formatted(current().plusMonths(1).toString())), null)
                .andExpect(status().isNotFound());
        send(chairman(), get(MONTH.formatted("2026-13")), null).andExpect(status().isBadRequest());
        send(chairman(), get(MONTH.formatted("10.2026")), null).andExpect(status().isBadRequest());
    }

    // ---- closing

    @Test
    void monthsCloseOnlyInOrder() throws Exception {
        UUID plot = newPlot();
        tariffCharge(plot, "MEMBERSHIP", "25.00", ym(2));
        tariffCharge(plot, "MEMBERSHIP", "25.00", ym(1));

        // §2.2: an earlier month is open
        send(chairman(), post(CLOSE.formatted(ym(1))), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSE_ORDER"));
        // a month without a row in between counts as open too
        send(chairman(), post(CLOSE.formatted(ym(0))), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSE_ORDER"));
        assertThat(monthStatus(ym(1))).isEqualTo("OPEN");

        JsonNode closed = body(send(chairman(), post(CLOSE.formatted(ym(2))), null).andExpect(status().isOk()));
        assertThat(closed.get("month").asString()).isEqualTo(ym(2));
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(closed.has("closedAt")).isTrue();
        assertThat(closed.get("closedByName").asString()).isNotBlank();
        assertThat(monthStatus(ym(2))).isEqualTo("CLOSED");

        send(chairman(), post(CLOSE.formatted(ym(1))), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        // the current month has no row yet: it is created and closed
        send(chairman(), post(CLOSE.formatted(ym(0))), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        assertThat(monthStatus(ym(0))).isEqualTo("CLOSED");

        JsonNode months = list(treasurer());
        assertThat(entry(months, ym(2)).get("status").asString()).isEqualTo("CLOSED");
        assertThat(entry(months, ym(2)).has("closedAt")).isTrue();
    }

    @Test
    void aClosedMonthIsNotClosedAgain() throws Exception {
        tariffCharge(newPlot(), "MEMBERSHIP", "25.00", ym(1));
        send(chairman(), post(CLOSE.formatted(ym(1))), null).andExpect(status().isOk());

        send(chairman(), post(CLOSE.formatted(ym(1))), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));
    }

    @Test
    void aFutureMonthOrOneBeforeTheFirstIsNotClosed() throws Exception {
        tariffCharge(newPlot(), "MEMBERSHIP", "25.00", ym(1));

        send(chairman(), post(CLOSE.formatted(current().plusMonths(1).toString())), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_IN_FUTURE"));
        send(chairman(), post(CLOSE.formatted(ym(2))), null).andExpect(status().isNotFound());
        send(otherChairman(), post(CLOSE.formatted(ym(1))), null).andExpect(status().isNotFound());
        assertThat(ownerJdbc().queryForObject("select count(*) from billing_month where tenant_id = ? and month in "
                + "(?, ?)", Integer.class, DEMO_TENANT, current().plusMonths(1).atDay(1),
                current().minusMonths(2).atDay(1))).isZero();
    }

    @Test
    void aClosedMonthGetsNothingAndALateChargeGoesToTheEarliestOpenMonthWithItsOwnDays() throws Exception {
        UUID plot = newPlot();
        UUID charge = tariffCharge(plot, "MEMBERSHIP", "25.00", ym(2));
        tariffCharge(plot, "LAND_TAX", "17.50", ym(1));
        send(chairman(), post(CLOSE.formatted(ym(2))), null).andExpect(status().isOk());

        // a reversal of a charge of the closed month: refused (an adjustment, feature 012)
        send(chairman(), post(REVERSE.formatted(charge)), Map.of("reason", "Помилка"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHARGE_IN_CLOSED_MONTH"));
        // §2.2: a charge about a day of the closed month is booked in the next open month, keeping its own days
        LocalDate lateDay = current().minusMonths(2).atDay(28);
        JsonNode late = body(send(chairman(), post(OPENING.formatted(plot)),
                opening(typeId(DEMO_TENANT, "MEMBERSHIP"), "40.00", lateDay.toString(), null))
                .andExpect(status().isCreated()));

        assertThat(late.get("billingMonth").asString()).isEqualTo(ym(1));
        assertThat(late.get("serviceFrom").asString()).isEqualTo(lateDay.toString());
        assertThat(late.get("serviceTo").asString()).isEqualTo(lateDay.toString());
        assertThat(late.get("accruedOn").asString()).isEqualTo(lateDay.toString());
        JsonNode months = list(chairman());
        assertThat(entry(months, ym(2)).get("total").asString()).isEqualTo("25.00");
        assertThat(entry(months, ym(1)).get("total").asString()).isEqualTo("57.50");
    }

    @Test
    void aLateChargeGoesToTheMonthAfterTheLastClosedOneEvenWhenItHasNoRowAndALaterMonthIsOpen() throws Exception {
        // two months ago CLOSED, last month without a row, the current month OPEN
        UUID plot = newPlot();
        tariffCharge(plot, "MEMBERSHIP", "25.00", ym(2));
        tariffCharge(plot, "LAND_TAX", "17.50", ym(0));
        send(chairman(), post(CLOSE.formatted(ym(2))), null).andExpect(status().isOk());
        assertThat(ownerJdbc().queryForObject("select count(*) from billing_month where tenant_id = ? and month = ?",
                Integer.class, DEMO_TENANT, current().minusMonths(1).atDay(1))).isZero();

        LocalDate lateDay = current().minusMonths(2).atDay(28);
        JsonNode late = body(send(chairman(), post(OPENING.formatted(plot)),
                opening(typeId(DEMO_TENANT, "MEMBERSHIP"), "40.00", lateDay.toString(), null))
                .andExpect(status().isCreated()));

        // the month without a row is open (months close in order): it is opened for the charge
        assertThat(late.get("billingMonth").asString()).isEqualTo(ym(1));
        assertThat(late.get("accruedOn").asString()).isEqualTo(lateDay.toString());
        assertThat(monthStatus(ym(1))).isEqualTo("OPEN");
        JsonNode months = list(chairman());
        assertThat(entry(months, ym(1)).get("total").asString()).isEqualTo("40.00");
        assertThat(entry(months, ym(0)).get("total").asString()).isEqualTo("17.50");
    }

    @Test
    void whenEveryMonthUpToNowIsClosedALateChargeOpensTheNextMonth() throws Exception {
        UUID plot = newPlot();
        tariffCharge(plot, "MEMBERSHIP", "25.00", ym(0));
        send(chairman(), post(CLOSE.formatted(ym(0))), null).andExpect(status().isOk());

        JsonNode late = body(send(chairman(), post(OPENING.formatted(plot)),
                opening(typeId(DEMO_TENANT, "MEMBERSHIP"), "40.00", kyivToday().toString(), null))
                .andExpect(status().isCreated()));

        assertThat(late.get("billingMonth").asString()).isEqualTo(current().plusMonths(1).toString());
        assertThat(late.get("accruedOn").asString()).isEqualTo(kyivToday().toString());
        // the opened month is listed, after the current one
        assertThat(list(chairman()).get(0).get("month").asString()).isEqualTo(current().plusMonths(1).toString());
    }

    @Test
    void closingIsAudited() throws Exception {
        tariffCharge(newPlot(), "MEMBERSHIP", "25.00", ym(1));
        send(chairman(), post(CLOSE.formatted(ym(1))), null).andExpect(status().isOk());
        UUID monthId = ownerJdbc().queryForObject("select id from billing_month where tenant_id = ? and month = ?",
                UUID.class, DEMO_TENANT, current().minusMonths(1).atDay(1));

        var entries = awaitEntries(monthId, "BILLING_MONTH_CLOSED", 1);
        try {
            assertThat(entries.getFirst().get("entity_type")).isEqualTo("BILLING_MONTH");
            assertThat(entries.getFirst().get("entity_label")).isEqualTo(ym(1));
            assertThat(entries.getFirst().get("actor_member_id")).isEqualTo(demoMember("chairman@demo.local"));
            assertThat(changes(entries.getFirst())).containsExactly(new Change("status", "OPEN", "CLOSED"));
        } finally {
            ownerJdbc().update("delete from audit_entry where entity_id = ?", monthId);
        }
    }

    // ---- who may do what

    @Test
    void theMonthsNeedChargesViewAndClosingNeedsPeriodsClose() throws Exception {
        tariffCharge(newPlot(), "MEMBERSHIP", "25.00", ym(1));

        send(treasurer(), get(MONTHS), null).andExpect(status().isOk());
        send(treasurer(), get(MONTH.formatted(ym(1))), null).andExpect(status().isOk());
        send(treasurer(), post(CLOSE.formatted(ym(1))), null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        for (String bearer : List.of(member(), operator(), customRoleToken("PERIODS_CLOSE"))) {
            send(bearer, get(MONTHS), null).andExpect(status().isForbidden());
            send(bearer, get(MONTH.formatted(ym(1))), null).andExpect(status().isForbidden());
        }
        for (String bearer : List.of(member(), operator(), customRoleToken("CHARGES_VIEW"))) {
            send(bearer, post(CLOSE.formatted(ym(1))), null).andExpect(status().isForbidden());
        }
        send(null, get(MONTHS), null).andExpect(status().isUnauthorized());
        assertThat(monthStatus(ym(1))).isEqualTo("OPEN");

        send(customRoleToken("PERIODS_CLOSE"), post(CLOSE.formatted(ym(1))), null).andExpect(status().isOk());
        assertThat(monthStatus(ym(1))).isEqualTo("CLOSED");
    }

    @Test
    void anotherCooperativeNeverSeesTheMonths() throws Exception {
        tariffCharge(newPlot(), "MEMBERSHIP", "25.00", ym(1));

        assertThat(list(otherChairman())).isEmpty();
        send(otherChairman(), get(MONTH.formatted(ym(1))), null).andExpect(status().isNotFound());
    }

    // ---- the database keeps a closed month closed (trigger billing_month_guard, V14)

    @Test
    void aClosedMonthNeverChangesAndNoMonthIsDeleted() {
        UUID open = month(DEMO_TENANT, ym(1));
        UUID closed = closedMonth(DEMO_TENANT, ym(2));

        for (String statement : List.of("update billing_month set status = 'OPEN', closed_at = null where id = ?",
                "update billing_month set run_at = now() where id = ?", "delete from billing_month where id = ?")) {
            assertThatThrownBy(() -> ownerJdbc().update(statement, closed)).as(statement)
                    .isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("billing month");
        }
        assertThatThrownBy(() -> ownerJdbc().update("delete from billing_month where id = ?", open))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> ownerJdbc().update("update billing_month set month = month - interval '1 month' "
                + "where id = ?", open)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> sql.update(
                "update billing_month set status = 'OPEN', closed_at = null where id = ?", closed)))
                .isInstanceOf(DataAccessException.class);
        // an open month may still change (run_at, closing)
        Integer updated = inTenant(DEMO_TENANT, sql -> sql.update(
                "update billing_month set run_at = now() where id = ?", open));
        assertThat(updated).isEqualTo(1);
        assertThat(monthStatus(ym(2))).isEqualTo("CLOSED");
    }

    // ---- helpers

    private String monthStatus(String month) {
        return ownerJdbc().queryForObject("select status from billing_month where tenant_id = ? and month = ?",
                String.class, DEMO_TENANT, YearMonth.parse(month).atDay(1));
    }

    private UUID plotMeter(UUID plot, String resource, LocalDate installedOn) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into meter (id, tenant_id, kind, resource, serial, plot_id, installed_on, "
                + "initial_value) values (?, ?, 'PLOT', ?, ?, ?, ?, 0)", id, DEMO_TENANT, resource,
                "BM-" + id.toString().substring(0, 8), plot, installedOn);
        meters.add(id);
        return id;
    }

    private void reading(UUID meter, LocalDate takenOn, String value) {
        ownerJdbc().update("insert into meter_reading (tenant_id, meter_id, taken_on, value, source, status) "
                + "values (?, ?, ?, ?::numeric, 'ENTERED', 'APPROVED')", DEMO_TENANT, meter, takenOn, value);
    }
}
