package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The conflict warning of the tariffs page (feature 011 §2.3): two tariffs of one charge type that apply to the same
 * plot each name the other with the plots they share; worked out by the resolver the month run uses, in a cooperative
 * of the test's own. A warning only: both tariffs stay in the list.
 */
class TariffConflictWarningTests extends FreshCooperativeSupport {

    private static final String TARIFFS = "/api/v1/tariffs";

    private UUID plot12;
    private Tariff main;
    private Tariff extra;
    private Tariff landTax;

    @BeforeEach
    void twoMembershipTariffsOnPlot12() {
        plot("7", "600.00");
        plot12 = plot("12", "600.00");
        main = tariff("MEMBERSHIP", "Членський внесок", "PER_AREA_SOTKA", "YEARLY", "50.0000", "2026-01-01");
        extra = tariff("MEMBERSHIP", "Додатковий внесок", "PER_PLOT", "MONTHLY", "100.0000", "2030-01-01");
        select(extra, plot12);
        landTax = tariff("LAND_TAX", "Земельний податок", "PER_AREA_SOTKA", "YEARLY", "35.0000", "2026-01-01");
    }

    private static JsonNode byId(JsonNode tariffs, Tariff tariff) {
        for (JsonNode t : tariffs) {
            if (t.get("id").asString().equals(tariff.id().toString())) {
                return t;
            }
        }
        throw new AssertionError("No tariff " + tariff.id());
    }

    @Test
    void theListNamesTheOtherTariffAndTheSharedPlotOnBothSides() throws Exception {
        JsonNode tariffs = body(send(chairman, get(TARIFFS), null).andExpect(status().isOk()));

        assertThat(byId(tariffs, main).get("conflicts")).singleElement().satisfies(conflict -> {
            assertThat(conflict.get("otherTariffId").asString()).isEqualTo(extra.id().toString());
            assertThat(conflict.get("otherTariffName").asString()).isEqualTo("Додатковий внесок");
            assertThat(conflict.get("plotCount").asLong()).isEqualTo(1);
            assertThat(conflict.get("plots")).singleElement().satisfies(plot -> {
                assertThat(plot.get("plotId").asString()).isEqualTo(plot12.toString());
                assertThat(plot.get("number").asString()).isEqualTo("12");
            });
        });
        assertThat(byId(tariffs, extra).get("conflicts")).singleElement()
                .satisfies(conflict -> assertThat(conflict.get("otherTariffId").asString())
                        .isEqualTo(main.id().toString()));
        assertThat(byId(tariffs, landTax).get("conflicts")).isEmpty();
    }

    @Test
    void theCardShowsTheSameWarning() throws Exception {
        JsonNode card = body(send(chairman, get(TARIFFS + "/" + extra.id()), null).andExpect(status().isOk()));
        JsonNode other = body(send(chairman, get(TARIFFS + "/" + landTax.id()), null).andExpect(status().isOk()));

        assertThat(card.get("conflicts")).singleElement()
                .satisfies(conflict -> assertThat(conflict.get("plotCount").asLong()).isEqualTo(1));
        assertThat(other.get("conflicts")).isEmpty();
    }

    @Test
    void anExcludedPlotOrAnInactiveTypeIsNoConflict() throws Exception {
        exclude(main, plot12);
        tariff("WATER", "Вода 1", "PER_PLOT", "YEARLY", "300.0000", "2026-01-01");
        Tariff water = tariff("WATER", "Вода 2", "PER_PLOT", "YEARLY", "200.0000", "2026-01-01");

        JsonNode tariffs = body(send(chairman, get(TARIFFS), null).andExpect(status().isOk()));

        assertThat(byId(tariffs, main).get("conflicts")).isEmpty();
        assertThat(byId(tariffs, extra).get("conflicts")).isEmpty();
        assertThat(byId(tariffs, water).get("conflicts")).isEmpty(); // the water type is inactive
    }

    @Test
    void manySharedPlotsAreCountedAndTheFirstTenListed() throws Exception {
        Tariff second = tariff("LAND_TAX", "Земельний податок 2", "PER_AREA_SOTKA", "YEARLY", "1.0000", "2026-01-01");
        for (int i = 20; i < 32; i++) {
            plot(Integer.toString(i), "600.00");
        }

        JsonNode tariffs = body(send(chairman, get(TARIFFS), null).andExpect(status().isOk()));

        JsonNode conflict = byId(tariffs, second).get("conflicts").get(0);
        assertThat(conflict.get("plotCount").asLong()).isEqualTo(14);
        assertThat(conflict.get("plots")).hasSize(10);
        assertThat(conflict.get("plots").get(0).get("number").asString()).isEqualTo("7");
        assertThat(conflict.get("plots").get(1).get("number").asString()).isEqualTo("12");
    }
}
