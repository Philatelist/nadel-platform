package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ledger's answer to the tariffs port {@code TariffVersionUsage} (feature 011 §2.11): once the month run has charged
 * from a version, correcting or deleting it is refused with the 009 code {@code TARIFF_VERSION_IN_USE}; after the
 * charges that used it are reversed it is accepted again. A version no charge used stays free.
 */
class TariffVersionUsageTests extends FreshCooperativeSupport {

    private static final String VERSION = "/api/v1/tariffs/%s/versions/%s";

    private UUID plot7;
    private Tariff membership;

    @BeforeEach
    void membershipOfPlot7() {
        plot7 = plot("7", "600.00");
        membership = tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "50.0000", "2026-01-01");
    }

    private static Map<String, Object> terms(String validFrom, String rate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("validFrom", validFrom);
        body.put("basis", "PER_AREA_SOTKA");
        body.put("frequency", "YEARLY");
        body.put("rate", rate);
        return body;
    }

    private void runMonth() throws Exception {
        send(chairman, post(RUN.formatted(ym(0), false)), null).andExpect(status().isOk());
    }

    @Test
    void aVersionTheRunUsedIsNoLongerCorrectedOrDeleted() throws Exception {
        UUID later = version(membership.id(), "PER_AREA_SOTKA", "YEARLY", "60.0000", "2099-01-01", null);
        runMonth();

        send(chairman, put(VERSION.formatted(membership.id(), membership.version())), terms("2026-01-01", "55"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TARIFF_VERSION_IN_USE"));
        send(chairman, delete(VERSION.formatted(membership.id(), membership.version())), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TARIFF_VERSION_IN_USE"));
        assertThat(owner().queryForObject("select rate::text from tariff_version where id = ?", String.class,
                membership.version())).isEqualTo("50.0000");

        // the later version charged nothing and stays free
        send(chairman, put(VERSION.formatted(membership.id(), later)), terms("2099-01-01", "65"))
                .andExpect(status().isOk());
    }

    @Test
    void afterTheReversalOfItsChargesTheVersionIsCorrectedAgain() throws Exception {
        runMonth();
        UUID charge = (UUID) charges(plot7).getFirst().get("id");
        send(chairman, post(REVERSE.formatted(charge)), Map.of("reason", "Помилковий тариф"))
                .andExpect(status().isCreated());

        send(chairman, put(VERSION.formatted(membership.id(), membership.version())), terms("2026-01-01", "55"))
                .andExpect(status().isOk());

        assertThat(owner().queryForObject("select rate::text from tariff_version where id = ?", String.class,
                membership.version())).isEqualTo("55.0000");
    }

    @Test
    void beforeAnyChargeTheVersionIsFree() throws Exception {
        send(chairman, put(VERSION.formatted(membership.id(), membership.version())), terms("2026-01-01", "55"))
                .andExpect(status().isOk());
    }
}
