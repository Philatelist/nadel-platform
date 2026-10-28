package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Payments in a cooperative of its own (feature 015): a test that books charges through the real paths (the month
 * run, a reversal, a run, a recalculation) publishes {@link ChargesBooked} for its plots, and the automatic allocation
 * of prepayment answers it after the commit; a cooperative of its own keeps the setting
 * {@code autoAllocatePrepayment} and the asynchronous listeners to the test. Payments are entered through the API by
 * the treasurer; charges the test only needs to exist are written as the database owner (no event).
 */
abstract class FreshPaymentSupport extends FreshCooperativeSupport {

    static final String PAYMENTS = "/api/v1/payments";
    static final String OPENING = "/api/v1/plots/%s/opening-balances";
    static final String RECALCULATIONS = "/api/v1/recalculations";

    /** Switches the cooperative's automatic allocation of prepayment on or off (as the database owner). */
    void autoAllocation(boolean on) {
        owner().update("insert into cooperative_settings (tenant_id, auto_allocate_prepayment) values (?, ?) "
                + "on conflict (tenant_id) do update set auto_allocate_prepayment = excluded.auto_allocate_prepayment",
                tenant, on);
    }

    /**
     * Waits until every event publication of the cooperative is complete: the plot status and the automatic
     * allocation have answered every {@code ChargesBooked} and {@code PaymentsChanged} published so far (a listener
     * that publishes registers its own publication before its trigger's completes, so no publication is missed).
     */
    void awaitListeners() {
        await().atMost(Duration.ofSeconds(15)).until(() -> owner().queryForObject(
                "select count(*) from event_publication where completion_date is null and serialized_event like ?",
                Integer.class, "%" + tenant + "%") == 0);
    }

    /** The member behind a base role of the cooperative (one each). */
    UUID memberOf(String baseRole) {
        return owner().queryForObject("select m.id from member m join member_role mr on mr.member_id = m.id "
                + "join role r on r.id = mr.role_id where m.tenant_id = ? and r.base_code = ?", UUID.class, tenant,
                baseRole);
    }

    /** A REGULAR tariff charge of the plot for the whole month (YYYY-MM), accrued on its first day; no event. */
    UUID charge(UUID plot, String typeCode, String amount, String month) {
        owner().update("insert into billing_month (tenant_id, month) values (?, ?::date) "
                + "on conflict (tenant_id, month) do nothing", tenant, month + "-01");
        UUID monthId = owner().queryForObject("select id from billing_month where tenant_id = ? and month = ?::date",
                UUID.class, tenant, month + "-01");
        UUID id = UUID.randomUUID();
        LocalDate first = LocalDate.parse(month + "-01");
        owner().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key) values (?, ?, ?, ?, ?, 'REGULAR', "
                + "'TARIFF', ?, ?, ?, ?, ?)", id, tenant, plot, typeId(typeCode), monthId, new BigDecimal(amount),
                first, first.withDayOfMonth(first.lengthOfMonth()), first, "TEST:" + id);
        return id;
    }

    /** The charge of the plot booked by a real path for the charge type (by code) and month (YYYY-MM). */
    UUID bookedCharge(UUID plot, String typeCode, String month) {
        return owner().queryForObject("select id from charge where plot_id = ? and charge_type_id = ? "
                + "and kind = 'REGULAR' and service_from = ?::date and not exists (select 1 from charge r "
                + "where r.reverses_charge_id = charge.id)", UUID.class, plot, typeId(typeCode), month + "-01");
    }

    static LocalDate today() {
        return LocalDate.now(ZoneId.of("Europe/Kyiv"));
    }

    /** A cash payment of the plot received today by the treasurer, saving the proposal; returns its id. */
    UUID pay(UUID plot, String amount) throws Exception {
        return pay(plot, amount, null);
    }

    /** A cash payment of the plot received today with the treasurer's lines (null: the proposal). */
    UUID pay(UUID plot, String amount, List<Map<String, Object>> lines) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plotId", plot.toString());
        body.put("source", "CASH");
        body.put("amount", amount);
        body.put("receivedOn", today().toString());
        if (lines != null) {
            body.put("lines", lines);
        }
        return UUID.fromString(body(send(treasurer, post(PAYMENTS + "?preview=false"), body)
                .andExpect(status().isCreated())).get("payments").get(0).get("id").asString());
    }

    static Map<String, Object> line(UUID charge, String amount) {
        return Map.of("chargeId", charge.toString(), "amount", amount);
    }

    /** Reverses the charge by hand as the chairman. */
    void reverseCharge(UUID charge) throws Exception {
        send(chairman, post(REVERSE.formatted(charge)), Map.of("reason", "Помилкове нарахування"))
                .andExpect(status().isCreated());
    }

    /** Previews and books a manual recalculation of one charge type (by code) of one plot over the months. */
    void recalculate(UUID plot, String typeCode, String from, String to) throws Exception {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("from", from);
        scope.put("to", to);
        scope.put("chargeTypeIds", List.of(typeId(typeCode)));
        scope.put("plotIds", List.of(plot));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cause", Map.of("type", "MANUAL", "manual", scope));
        String digest = body(send(chairman, post(RECALCULATIONS).param("preview", "true"), body)
                .andExpect(status().isOk())).get("digest").asString();
        body.put("reason", "Виправлено площу ділянки");
        body.put("digest", digest);
        send(chairman, post(RECALCULATIONS).param("preview", "false"), body).andExpect(status().isOk());
    }

    // ---- the ledger as stored

    BigDecimal netAllocated(UUID charge) {
        return owner().queryForObject("select coalesce(sum(amount), 0) from allocation where charge_id = ?",
                BigDecimal.class, charge);
    }

    /** The plot's prepayment: payments (reversals negative) minus net allocations. */
    BigDecimal prepayment(UUID plot) {
        return owner().queryForObject("select (select coalesce(sum(amount), 0) from payment where plot_id = ?) "
                + "- (select coalesce(sum(amount), 0) from allocation where plot_id = ?)", BigDecimal.class, plot,
                plot);
    }

    /** What is open on the charge: its amount and adjustments, minus its net allocations (0 once reversed). */
    BigDecimal open(UUID charge) {
        return owner().queryForObject("select case when exists (select 1 from charge r where r.reverses_charge_id = "
                + "c.id) then 0 else c.amount + coalesce((select sum(a.amount) from charge a where "
                + "a.adjusts_charge_id = c.id), 0) end - (select coalesce(sum(amount), 0) from allocation "
                + "where charge_id = c.id) from charge c where c.id = ?", BigDecimal.class, charge);
    }

    /** The releases of the plot, oldest first: amount, cause, author, the allocation released and its charge. */
    List<Map<String, Object>> releases(UUID plot) {
        return owner().queryForList("select amount, release_cause, created_by, releases_allocation_id, charge_id, "
                + "payment_id from allocation where plot_id = ? and amount < 0 order by created_at, id", plot);
    }

    /** The allocations (above zero) of the plot with the origin, oldest first. */
    List<Map<String, Object>> allocations(UUID plot, String origin) {
        return owner().queryForList("select id, amount, charge_id, payment_id, created_by from allocation "
                + "where plot_id = ? and amount > 0 and origin = ? order by created_at, id", plot, origin);
    }

    int allocationCount(UUID plot) {
        return owner().queryForObject("select count(*) from allocation where plot_id = ?", Integer.class, plot);
    }

    /** No charge of the plot holds more than its effective amount and no payment more than it holds. */
    void invariantsHold(UUID plot) {
        assertThat(owner().queryForList("select c.id from charge c where c.plot_id = ? and c.kind in ('REGULAR', "
                + "'OPENING') and (select coalesce(sum(a.amount), 0) from allocation a where a.charge_id = c.id) > "
                + "case when exists (select 1 from charge r where r.reverses_charge_id = c.id) then 0 else c.amount "
                + "+ coalesce((select sum(x.amount) from charge x where x.adjusts_charge_id = c.id), 0) end", plot))
                .as("charges with more allocated than they hold").isEmpty();
        assertThat(owner().queryForList("select p.id from payment p where p.plot_id = ? and p.kind = 'PAYMENT' and "
                + "(select coalesce(sum(a.amount), 0) from allocation a where a.payment_id = p.id) > p.amount", plot))
                .as("payments with more allocated than they hold").isEmpty();
    }
}
