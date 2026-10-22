package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.DemoMeterData;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * A database that loaded the previous demo meters on an earlier day (simulated: the previous block, dated nine days
 * back) converges when the current block runs: the node meters share the dates of the latest plot rounds, are read for
 * real and sit 5-15 % above the total of their children.
 */
class DemoMeterConvergenceTests extends MeterTestSupport {

    private static final int AGE_DAYS = 9;

    private final JdbcTemplate owner = ownerJdbc();

    @AfterEach
    void putTheDemoBack() {
        DemoMeterData.clear(owner);
        DemoMeterData.restore(owner);
    }

    @Test
    void anOldDatabaseGetsNodeReadingsOnThePlotRoundDatesWithLosses() throws Exception {
        DemoMeterData.clear(owner);
        owner.execute(previousBlock().replace("CURRENT_DATE", "(CURRENT_DATE - " + AGE_DAYS + ")"));
        DemoMeterData.restore(owner);

        LocalDate anchor = LocalDate.now().minusDays(AGE_DAYS);
        for (int[] range : new int[][] {{41, 20}, {20, 5}}) {
            JsonNode tree = body(send(chairman(), get("/api/v1/meter-tree")
                    .param("from", anchor.minusDays(range[0]).toString())
                    .param("to", anchor.minusDays(range[1]).toString()), null).andExpect(status().isOk()));
            JsonNode substation = tree.get("roots").get(0);
            checkNode(substation);
            for (JsonNode quarter : substation.get("children")) {
                if (quarter.get("meter").get("kind").asString().equals("QUARTER")) {
                    checkNode(quarter);
                }
            }
        }
    }

    private static void checkNode(JsonNode node) {
        JsonNode total = node.get("total");
        assertThat(total.get("known").asBoolean()).isTrue();
        assertThat(total.get("calculated").asBoolean()).isFalse();
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode child : node.get("children")) {
            if (child.get("total").get("known").asBoolean()) {
                sum = sum.add(new BigDecimal(child.get("total").get("consumption").asString()));
            }
        }
        BigDecimal ratio = new BigDecimal(total.get("consumption").asString())
                .divide(sum, 4, java.math.RoundingMode.HALF_UP);
        assertThat(ratio).isBetween(new BigDecimal("1.05"), new BigDecimal("1.15"));
    }

    private static String previousBlock() throws IOException {
        try (InputStream in = DemoMeterConvergenceTests.class.getResourceAsStream("/demo_meters_before_node_losses.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
