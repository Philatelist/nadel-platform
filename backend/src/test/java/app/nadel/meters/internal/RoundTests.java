package app.nadel.meters.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * The reading round, {@code GET /readings/round} (spec 010, sections 2.5 and 2.7): plot meters in street and number
 * order, then the other meters by name; only active meters; a plot without a meter is never listed; the filters
 * {@code resource}, {@code street} and {@code noReadingSince}; who may ask. Other meters of the cooperative may be in
 * the answer, so the assertions look at the meters of the test only.
 */
class RoundTests extends MeterTestSupport {

    private static final String ROUND = READINGS + "/round";

    @Test
    void plotMetersComeInStreetAndNumberOrderThenTheOtherMetersByName() throws Exception {
        String prefix = "MT" + UUID.randomUUID().toString().substring(0, 4);
        UUID cherry10 = newNumberedPlot("MT вул. Черешнева", prefix + "-10");
        UUID cherry2 = newNumberedPlot("MT вул. Черешнева", prefix + "-2");
        UUID apricot = newNumberedPlot("MT вул. Абрикосова", prefix + "-30");
        UUID noStreet = newNumberedPlot(null, prefix + "-1");
        UUID cherry10Water = register(plotMeter(cherry10, "WATER", "MT-1", "2026-06-01", "0"));
        UUID cherry10Power = register(plotMeter(cherry10, "ELECTRICITY", "MT-2", "2026-06-01", "0"));
        UUID cherry2Power = register(plotMeter(cherry2, "ELECTRICITY", "MT-3", "2026-06-01", "0"));
        UUID apricotPower = register(plotMeter(apricot, "ELECTRICITY", "MT-4", "2026-06-01", "0"));
        UUID noStreetPower = register(plotMeter(noStreet, "ELECTRICITY", "MT-5", "2026-06-01", "0"));
        UUID lighting = register(namedMeter("COMMON_CONSUMER", "MT Освітлення", "MT-6", "2026-06-01", "0"));
        UUID well = register(namedMeter("COMMON_CONSUMER", "MT Свердловина", "MT-7", "2026-06-01", "0"));
        UUID quarter = register(namedMeter("QUARTER", "MT Квартал А", "MT-8", "2026-06-01", "0"));
        Set<UUID> ours = Set.of(cherry10Water, cherry10Power, cherry2Power, apricotPower, noStreetPower, lighting,
                well, quarter);

        List<UUID> round = ids(round(chairman(), get(ROUND)), ours);

        assertThat(round).containsExactly(apricotPower, cherry2Power, cherry10Power, cherry10Water, noStreetPower,
                quarter, lighting, well);
    }

    @Test
    void onlyActiveMetersAreListedAndAPlotWithoutAMeterNever() throws Exception {
        UUID withMeter = newPlot("MT вул. Садова");
        UUID withRemovedMeter = newPlot("MT вул. Садова");
        newPlot("MT вул. Садова"); // a plot without a meter
        UUID active = register(plotMeter(withMeter, "ELECTRICITY", "MT-1", "2026-06-01", "0"));
        UUID removed = register(plotMeter(withRemovedMeter, "ELECTRICITY", "MT-2", "2026-06-01", "0"));
        send(chairman(), post(METERS + "/" + removed + "/remove"), Map.of("removedOn", "2026-09-01",
                "finalReading", "10")).andExpect(status().isOk());

        JsonNode round = round(chairman(), get(ROUND).param("street", "MT вул. Садова"));

        assertThat(ids(round, null)).containsExactly(active);
        assertThat(round.get(0).get("plot").get("id").asString()).isEqualTo(withMeter.toString());
        assertThat(round.get(0).get("lastReading").get("value").asString()).isEqualTo("0.000");
    }

    @Test
    void theFiltersCombine() throws Exception {
        String street = "MT вул. Липова " + UUID.randomUUID().toString().substring(0, 4);
        UUID plot = newPlot(street);
        UUID otherStreet = newPlot("MT вул. Кленова");
        UUID power = register(plotMeter(plot, "ELECTRICITY", "MT-1", "2026-06-01", "0"));
        UUID water = register(plotMeter(plot, "WATER", "MT-2", "2026-06-01", "0"));
        UUID elsewhere = register(plotMeter(otherStreet, "ELECTRICITY", "MT-3", "2026-06-01", "0"));
        UUID named = register(namedMeter("COMMON_CONSUMER", "MT Насос", "MT-4", "2026-06-01", "0"));
        Set<UUID> ours = Set.of(power, water, elsewhere, named);

        assertThat(ids(round(chairman(), get(ROUND).param("street", "  " + street.toUpperCase() + " ")), ours))
                .containsExactly(power, water);
        assertThat(ids(round(chairman(), get(ROUND).param("resource", "WATER")), ours)).containsExactly(water);
        assertThat(ids(round(chairman(), get(ROUND).param("street", street).param("resource", "ELECTRICITY")),
                ours)).containsExactly(power);
        // "Кленова" comes before "Липова" in the Ukrainian alphabet
        assertThat(ids(round(chairman(), get(ROUND).param("resource", "ELECTRICITY")), ours))
                .containsExactly(elsewhere, power, named);
    }

    @Test
    void theStaleFilterListsTheMetersNotReadSinceTheDay() throws Exception {
        String street = "MT вул. Тиха " + UUID.randomUUID().toString().substring(0, 4);
        UUID freshPlot = newPlot(street);
        UUID stalePlot = newPlot(street);
        UUID neverReadPlot = newPlot(street);
        UUID fresh = register(plotMeter(freshPlot, "ELECTRICITY", "MT-1", "2026-06-01", "0"));
        UUID stale = register(plotMeter(stalePlot, "ELECTRICITY", "MT-2", "2026-06-01", "0"));
        UUID neverRead = register(plotMeter(neverReadPlot, "ELECTRICITY", "MT-3", "2026-06-01", "0"));
        entered(fresh, kyivToday().minusDays(10).toString(), "100");
        entered(stale, "2026-07-01", "50");
        // a pending reading is not a counted one
        reading(stale, kyivToday().minusDays(1).toString(), "60", "PENDING");

        String sixtyDaysAgo = kyivToday().minusDays(60).toString();
        JsonNode round = round(chairman(), get(ROUND).param("street", street).param("noReadingSince", sixtyDaysAgo));

        assertThat(ids(round, null)).containsExactlyInAnyOrder(stale, neverRead);
        assertThat(lastReadingOf(round, stale)).isEqualTo("2026-07-01");
        assertThat(lastReadingOf(round, neverRead)).isEqualTo("2026-06-01");
        assertThat(ids(round(chairman(), get(ROUND).param("street", street)), null)).hasSize(3);
    }

    // ---- who may ask

    @Test
    void enteringReadingsIsWhatTheRoundNeedsAndTheNoteNeedsViewingMeters() throws Exception {
        String street = "MT вул. Нова " + UUID.randomUUID().toString().substring(0, 4);
        Map<String, Object> body = plotMeter(newPlot(street), "ELECTRICITY", "MT-1", "2026-06-01", "0");
        body.put("note", "Behind the gate");
        UUID meter = register(body);

        JsonNode electrician = round(customRoleToken("METERS_VIEW", "READINGS_ENTER"),
                get(ROUND).param("street", street));
        assertThat(ids(electrician, null)).containsExactly(meter);
        assertThat(electrician.get(0).get("note").asString()).isEqualTo("Behind the gate");

        JsonNode enterer = round(customRoleToken("READINGS_ENTER"), get(ROUND).param("street", street));
        assertThat(ids(enterer, null)).containsExactly(meter);
        assertThat(enterer.get(0).has("note")).isFalse();

        for (String denied : new String[] {demo("treasurer@demo.local"), demo("member@demo.local"),
                customRoleToken("METERS_VIEW", "METERS_MANAGE"), operator()}) {
            send(denied, get(ROUND), null).andExpect(status().isForbidden());
        }
        mvc.perform(get(ROUND)).andExpect(status().isUnauthorized());
    }

    @Test
    void anotherCooperativeHasItsOwnRound() throws Exception {
        UUID meter = register(plotMeter(newPlot("MT вул. Садова"), "ELECTRICITY", "MT-1", "2026-06-01", "0"));

        assertThat(ids(round(otherChairman(), get(ROUND)), null)).doesNotContain(meter);
    }

    // ---- helpers

    private JsonNode round(String bearer, MockHttpServletRequestBuilder request) throws Exception {
        return body(send(bearer, request, null).andExpect(status().isOk()));
    }

    /** The meter ids of the round in its order; only those in {@code only} when given. */
    private static List<UUID> ids(JsonNode round, Set<UUID> only) {
        List<UUID> ids = new ArrayList<>();
        round.forEach(meter -> {
            UUID id = UUID.fromString(meter.get("id").asString());
            if (only == null || only.contains(id)) {
                ids.add(id);
            }
        });
        return ids;
    }

    private static String lastReadingOf(JsonNode round, UUID meter) {
        for (JsonNode node : round) {
            if (node.get("id").asString().equals(meter.toString())) {
                return node.get("lastReading").get("takenOn").asString();
            }
        }
        throw new AssertionError("Not in the round: " + meter);
    }
}
