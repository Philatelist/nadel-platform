package app.nadel.ledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.web.servlet.ResultActions;

/**
 * What the payment API tests share, on top of the ledger API fixtures: payment requests (preview and save), and a
 * cleanup that removes the allocations and payments of the test's plots (as {@code nadel_owner} with the maintenance
 * setting: the tables are append-only) and the receipt counters of both cooperatives. It runs before the cleanup of
 * {@link LedgerApiSupport}, which removes the charges, months and plots the payments point at.
 */
abstract class PaymentApiSupport extends LedgerApiSupport {

    static final String PAYMENTS = "/api/v1/payments";

    private final List<UUID> paymentPlots = new ArrayList<>();

    @Override
    UUID newPlot(UUID tenant, String kind, boolean archived) {
        UUID plot = super.newPlot(tenant, kind, archived);
        paymentPlots.add(plot);
        return plot;
    }

    @AfterEach
    void removePayments() {
        if (!paymentPlots.isEmpty()) {
            String plots = "{" + String.join(",", paymentPlots.stream().map(UUID::toString).toList()) + "}";
            asLedgerOwner(sql -> {
                // releases point at their allocation, reversals at their payment: the pointing rows first
                sql.update("delete from allocation where plot_id = any(?::uuid[]) "
                        + "and releases_allocation_id is not null", plots);
                sql.update("delete from allocation where plot_id = any(?::uuid[])", plots);
                sql.update("delete from payment where plot_id = any(?::uuid[]) and reverses_payment_id is not null",
                        plots);
                sql.update("delete from payment where plot_id = any(?::uuid[])", plots);
            });
            paymentPlots.clear();
        }
        ownerJdbc().update("delete from receipt_counter where tenant_id in (?, ?)", DEMO_TENANT, OTHER_TENANT);
    }

    /** A request body for one plot; {@code lines} null saves the proposal. */
    static Map<String, Object> payment(UUID plot, String source, String amount, String receivedOn,
            List<Map<String, Object>> lines) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plotId", plot.toString());
        body.put("source", source);
        body.put("amount", amount);
        body.put("receivedOn", receivedOn);
        if (lines != null) {
            body.put("lines", lines);
        }
        return body;
    }

    /** A cash payment received today, saving the proposal. */
    static Map<String, Object> cash(UUID plot, String amount) {
        return payment(plot, "CASH", amount, kyivToday().toString(), null);
    }

    static Map<String, Object> line(UUID charge, String amount) {
        return Map.of("chargeId", charge.toString(), "amount", amount);
    }

    static Map<String, Object> with(Map<String, Object> body, String field, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(body);
        copy.put(field, value);
        return copy;
    }

    ResultActions preview(String bearer, Map<String, Object> body) throws Exception {
        return send(bearer, post(PAYMENTS + "?preview=true"), body);
    }

    ResultActions save(String bearer, Map<String, Object> body) throws Exception {
        return send(bearer, post(PAYMENTS + "?preview=false"), body);
    }

    ResultActions card(String bearer, UUID payment) throws Exception {
        return send(bearer, get(PAYMENTS + "/" + payment), null);
    }

    ResultActions reverse(String bearer, UUID payment, Object body) throws Exception {
        return send(bearer, post(PAYMENTS + "/" + payment + "/reverse"), body);
    }

    ResultActions reverse(String bearer, UUID payment, String reason) throws Exception {
        return reverse(bearer, payment, Map.of("reason", reason));
    }

    ResultActions reallocate(String bearer, UUID payment, boolean preview, Map<String, Object> body)
            throws Exception {
        return send(bearer, post(PAYMENTS + "/" + payment + "/reallocate?preview=" + preview), body);
    }

    ResultActions allocatePrepayment(String bearer, UUID plot, boolean preview, Map<String, Object> body)
            throws Exception {
        return send(bearer, post("/api/v1/plots/" + plot + "/prepayment/allocate?preview=" + preview), body);
    }

    /** A body of a reallocation or prepayment allocation: {@code lines} null uses the proposal. */
    static Map<String, Object> lines(List<Map<String, Object>> lines, String digest) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (lines != null) {
            body.put("lines", lines);
        }
        if (digest != null) {
            body.put("digest", digest);
        }
        return body;
    }

    /** Saves the payment and returns the id of its (first) payment. */
    UUID saved(String bearer, Map<String, Object> body) throws Exception {
        return UUID.fromString(body(save(bearer, body).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()))
                .get("payments").get(0).get("id").asString());
    }

    /** The net allocations to the charge (allocations minus releases). */
    BigDecimal netAllocated(UUID charge) {
        return ownerJdbc().queryForObject("select coalesce(sum(amount), 0) from allocation where charge_id = ?",
                BigDecimal.class, charge);
    }

    /** The plot's prepayment from the ledger: payments (reversals negative) minus net allocations. */
    BigDecimal ledgerPrepayment(UUID plot) {
        return ownerJdbc().queryForObject("select (select coalesce(sum(amount), 0) from payment where plot_id = ?) "
                + "- (select coalesce(sum(amount), 0) from allocation where plot_id = ?)", BigDecimal.class, plot,
                plot);
    }

    int paymentCount(UUID plot) {
        return ownerJdbc().queryForObject("select count(*) from payment where plot_id = ?", Integer.class, plot);
    }

    int allocationCount(UUID plot) {
        return ownerJdbc().queryForObject("select count(*) from allocation where plot_id = ?", Integer.class, plot);
    }

    /** The last receipt number of the demo cooperative; null when it has no counter yet. */
    Long lastReceipt() {
        List<Long> last = ownerJdbc().queryForList("select last_number from receipt_counter where tenant_id = ?",
                Long.class, DEMO_TENANT);
        return last.isEmpty() ? null : last.get(0);
    }
}
