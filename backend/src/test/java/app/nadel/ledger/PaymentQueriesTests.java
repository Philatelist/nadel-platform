package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The payments list ({@code GET /payments}, feature 015 §2.4) in a cooperative of its own, so the filters and the totals
 * are exact: newest first (received day, then entry time), the filters by received day, source, plot and billing month
 * (each alone and together), pagination, reversed payments marked and reversal rows never listed.
 */
class PaymentQueriesTests extends FreshPaymentSupport {

    private UUID plotA;
    private UUID plotB;
    private UUID p1;
    private UUID p2;
    private UUID p3;
    private UUID p4;

    /**
     * Plot A: cash 10.00 on the 5th and bank 20.00 on the 20th of last month (the bank one reversed); plot B: cash 30.00
     * on the 1st of this month and bank 40.00 today.
     */
    @BeforeEach
    void payments() throws Exception {
        plotA = plot("1", "600.00");
        plotB = plot("2", "600.00");
        p1 = enter(plotA, "CASH", "10.00", current().minusMonths(1).atDay(5).toString());
        p2 = enter(plotA, "BANK", "20.00", current().minusMonths(1).atDay(20).toString());
        p3 = enter(plotB, "CASH", "30.00", current().atDay(1).toString());
        p4 = enter(plotB, "BANK", "40.00", today().toString());
        send(chairman, post(PAYMENTS + "/" + p2 + "/reverse"), Map.of("reason", "Помилка"))
                .andExpect(status().isCreated());
    }

    private UUID enter(UUID plot, String source, String amount, String receivedOn) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plotId", plot.toString());
        body.put("source", source);
        body.put("amount", amount);
        body.put("receivedOn", receivedOn);
        return UUID.fromString(body(send(treasurer, post(PAYMENTS + "?preview=false"), body)
                .andExpect(status().isCreated())).get("payments").get(0).get("id").asString());
    }

    private JsonNode list(String query) throws Exception {
        return body(send(treasurer, get(PAYMENTS + query), null).andExpect(status().isOk()));
    }

    private static List<String> ids(JsonNode page) {
        return page.get("items").valueStream().map(item -> item.get("id").asString()).toList();
    }

    private static List<String> ids(UUID... payments) {
        return List.of(payments).stream().map(UUID::toString).toList();
    }

    @Test
    void everyPaymentNewestFirstWithoutReversalRows() throws Exception {
        JsonNode page = list("");

        assertThat(ids(page)).containsExactlyElementsOf(ids(p4, p3, p2, p1));
        assertThat(page.get("totalItems").asLong()).isEqualTo(4);
        assertThat(page.get("totalPages").asInt()).isEqualTo(1);
        assertThat(page.get("page").asInt()).isZero();
        JsonNode reversed = page.get("items").get(2);
        assertThat(reversed.get("reversed").asBoolean()).isTrue();
        assertThat(reversed.get("allocated").asString()).isEqualTo("0.00");
        assertThat(reversed.get("prepayment").asString()).isEqualTo("0.00");
        assertThat(page.get("items").get(0).get("reversed").asBoolean()).isFalse();
        assertThat(page.get("items").get(0).get("prepayment").asString()).isEqualTo("40.00");
        assertThat(page.get("items").get(3).get("billingMonth").asString()).isEqualTo(ym(1));
        assertThat(page.get("items").get(3).get("plotNumber").asString()).isEqualTo("1");
    }

    @Test
    void theReceivedDaysLimitTheList() throws Exception {
        String from = current().minusMonths(1).atDay(10).toString();
        String to = current().minusMonths(1).atEndOfMonth().toString();

        assertThat(ids(list("?from=" + from + "&to=" + to))).containsExactlyElementsOf(ids(p2));
        assertThat(ids(list("?from=" + current().atDay(1)))).containsExactlyElementsOf(ids(p4, p3));
        assertThat(ids(list("?to=" + current().minusMonths(1).atDay(5)))).containsExactlyElementsOf(ids(p1));
        send(treasurer, get(PAYMENTS + "?from=" + to + "&to=" + from), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("from"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID"));
    }

    @Test
    void theSourceThePlotAndTheBillingMonthLimitTheList() throws Exception {
        assertThat(ids(list("?source=CASH"))).containsExactlyElementsOf(ids(p3, p1));
        assertThat(ids(list("?source=BANK"))).containsExactlyElementsOf(ids(p4, p2));
        assertThat(ids(list("?plotId=" + plotB))).containsExactlyElementsOf(ids(p4, p3));
        assertThat(ids(list("?month=" + ym(1)))).containsExactlyElementsOf(ids(p2, p1));
        assertThat(ids(list("?month=" + ym(0) + "&source=CASH&plotId=" + plotB))).containsExactlyElementsOf(ids(p3));
        assertThat(list("?month=" + ym(3)).get("items")).isEmpty();
        assertThat(list("?plotId=" + UUID.randomUUID()).get("totalItems").asLong()).isZero();
    }

    @Test
    void theListIsPaged() throws Exception {
        JsonNode first = list("?page=0&size=3");
        assertThat(ids(first)).containsExactlyElementsOf(ids(p4, p3, p2));
        assertThat(first.get("size").asInt()).isEqualTo(3);
        assertThat(first.get("totalItems").asLong()).isEqualTo(4);
        assertThat(first.get("totalPages").asInt()).isEqualTo(2);

        JsonNode second = list("?page=1&size=3");
        assertThat(ids(second)).containsExactlyElementsOf(ids(p1));
        assertThat(second.get("page").asInt()).isEqualTo(1);

        assertThat(list("?page=2&size=3").get("items")).isEmpty();
        assertThat(ids(list("?page=1&size=2&source=CASH"))).isEmpty();
        assertThat(ids(list("?page=0&size=1&source=CASH"))).containsExactlyElementsOf(ids(p3));
    }
}
