package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The manual recalculation of proportional losses runs and common consumer runs (feature 012 §2.1, slice 5). A run
 * whose numbers changed is, in an open month, reversed as a whole (source {@code RECALCULATION}, the run marked
 * reversed) and booked again as a new run of the same range and nodes or consumers, which does not refuse itself as an
 * overlap; in a closed month each plot line gets an adjustment in the earliest open month, and a plot new to the run a
 * charge of the original run. A second recalculation finds nothing. The numbers change by a reading corrected directly
 * in the database (no reading events). Months are relative to the current month in Europe/Kyiv: the runs cover the
 * previous month ("September"), the current one ("October") is open.
 * <p>
 * Quarter A 1 100 kWh with P1 600 and P2 400: losses 100 kWh, P1 60 kWh = 259.20, P2 40 kWh = 172.80; the quarter
 * corrected to 1 200 kWh: losses 200, P1 120 kWh = 518.40, P2 80 kWh = 345.60. Street lighting 50 kWh equally per plot
 * among three plots: 72.00 each; corrected to 80 kWh: 115.20 each; among four plots: 54.00 each.
 */
class RunRecalculationTests extends FreshCooperativeSupport {

    private static final String RECALCULATIONS = "/api/v1/recalculations";
    private static final String LOSSES_RUNS = "/api/v1/losses-runs";
    private static final String CONSUMER_RUNS = "/api/v1/common-consumer-runs";
    private static final String REASON = "Виправлено покази лічильника";

    private YearMonth september;
    private YearMonth october;
    private String installed;
    private String before;
    private String to;
    private UUID p1;
    private UUID p2;
    private UUID p3;

    @BeforeEach
    void proportionalLossesAndTariffs() {
        september = current().minusMonths(1);
        october = current();
        installed = september.minusMonths(6).atDay(1).toString();
        before = september.atDay(1).minusDays(1).toString();
        to = september.atEndOfMonth().toString();
        owner().update("insert into cooperative_settings (tenant_id, losses_mode) values (?, 'PROPORTIONAL')", tenant);
        tariff("ELECTRICITY", "Електроенергія", "PER_KWH", "MONTHLY", "4.3200", installed);
        tariff("COMMON_CONSUMERS", "Спільні споживачі", "PER_KWH", "MONTHLY", "4.3200", installed);
        p1 = plot("1", "600");
        p2 = plot("2", "600");
        p3 = plot("3", "600");
    }

    // ---- losses runs

    @Test
    void aLossesRunOfAnOpenMonthIsReversedAsAWholeAndBookedAgainForTheSameRange() throws Exception {
        UUID quarter = quarter();
        UUID run = bookLossesRun();
        Map<UUID, UUID> originals = runCharges(run);
        correct(quarter, to, "6200");

        JsonNode preview = preview(scope("ELECTRICITY"));

        // the rebooked run is planned without overlapping itself: both plots are charged again
        assertThat(runLines(preview, run, "plotNumber", "action", "booked", "expected", "difference", "bookedIn"))
                .containsExactly("1|REVERSE_AND_RECHARGE|259.20|518.40|259.20|" + september,
                        "2|REVERSE_AND_RECHARGE|172.80|345.60|172.80|" + september);
        assertThat(runLines(preview, run, "reasons")).allSatisfy(reasons -> assertThat(reasons).contains("RUN"));

        JsonNode booked = book(scope("ELECTRICITY", preview.get("digest").asString()));

        UUID recalculation = UUID.fromString(booked.get("id").asString());
        // the original run: reversed as a whole with the reason, its nodes freed
        assertThat(owner().queryForMap("select status, reverse_reason from losses_run where id = ?", run))
                .containsEntry("status", "REVERSED").containsEntry("reverse_reason", REASON);
        assertThat(owner().queryForList("select distinct run_status from losses_run_node where run_id = ?",
                String.class, run)).containsExactly("REVERSED");
        assertThat(owner().queryForList("select amount::text || '|' || source || '|' || reason || '|' "
                + "|| recalculation_id || '|' || to_char(m.month, 'YYYY-MM') from charge c join billing_month m "
                + "on m.id = c.billing_month_id where c.kind = 'REVERSAL' and c.run_id = ? order by amount",
                String.class, run)).containsExactly(
                        "-259.20|RECALCULATION|" + REASON + "|" + recalculation + "|" + september,
                        "-172.80|RECALCULATION|" + REASON + "|" + recalculation + "|" + september);
        // a new run of the same range with the new numbers, its charges booked by the recalculation
        UUID rebooked = owner().queryForObject("select id from losses_run where tenant_id = ? and status = 'BOOKED'",
                UUID.class, tenant);
        assertThat(rebooked).isNotEqualTo(run);
        assertThat(owner().queryForMap("select from_date::text as from_date, to_date::text as to_date, "
                + "total::text as total from losses_run where id = ?", rebooked))
                .containsEntry("from_date", september.atDay(1).toString()).containsEntry("to_date", to)
                .containsEntry("total", "864.00");
        assertThat(owner().queryForList("select c.plot_id::text || '|' || c.amount || '|' || c.source_key || '|' "
                + "|| c.recalculation_id || '|' || to_char(m.month, 'YYYY-MM') from charge c join billing_month m "
                + "on m.id = c.billing_month_id where c.run_id = ? order by c.amount desc", String.class, rebooked))
                .containsExactly(
                        p1 + "|518.40|LOSSES_RUN:" + rebooked + ":" + p1 + "|" + recalculation + "|" + september,
                        p2 + "|345.60|LOSSES_RUN:" + rebooked + ":" + p2 + "|" + recalculation + "|" + september);
        assertThat(owner().queryForObject("select losses::text from losses_run_node where run_id = ? "
                + "and outcome = 'CHARGED'", String.class, rebooked)).isEqualTo("200.000");
        // the new run reads the values of its nodes, as 011 writes them
        assertThat(owner().queryForList("select distinct meter_id::text || '@' || used_on from meter_value_use "
                + "where run_id = ?", String.class, rebooked)).contains(quarter + "@" + before, quarter + "@" + to);
        // the lines name the original run and the charges written
        JsonNode line = runLine(booked, run, "1");
        assertThat(line.get("originalChargeId").asString()).isEqualTo(originals.get(p1).toString());
        assertThat(line.has("reversalChargeId")).isTrue();
        assertThat(line.get("newChargeId").asString()).isEqualTo(owner().queryForObject(
                "select id::text from charge where run_id = ? and plot_id = ?", String.class, rebooked, p1));

        // the same recalculation again: no changes
        JsonNode again = preview(scope("ELECTRICITY"));
        assertThat(again.get("lines")).isEmpty();
        send(chairman, post(RECALCULATIONS).param("preview", "false"),
                scope("ELECTRICITY", REASON, again.get("digest").asString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOTHING_TO_RECALCULATE"));
    }

    @Test
    void aLossesRunOfAClosedMonthGetsAnAdjustmentPerPlotInTheEarliestOpenMonth() throws Exception {
        UUID quarter = quarter();
        UUID run = bookLossesRun();
        Map<UUID, UUID> originals = runCharges(run);
        closeMonth(september.toString());
        correct(quarter, to, "6200");

        JsonNode preview = preview(scope("ELECTRICITY"));

        assertThat(runLines(preview, run, "plotNumber", "action", "booked", "expected", "difference", "bookedIn"))
                .containsExactly("1|ADJUST|259.20|518.40|259.20|" + october,
                        "2|ADJUST|172.80|345.60|172.80|" + october);

        JsonNode booked = book(scope("ELECTRICITY", preview.get("digest").asString()));

        UUID recalculation = UUID.fromString(booked.get("id").asString());
        // the run stays booked; each plot's charge is adjusted in October with the run's period and debt date
        assertThat(owner().queryForObject("select status from losses_run where id = ?", String.class, run))
                .isEqualTo("BOOKED");
        assertThat(owner().queryForObject("select count(*) from losses_run where tenant_id = ?", Integer.class,
                tenant)).isEqualTo(1);
        UUID adjustment = UUID.fromString(runLine(booked, run, "1").get("adjustmentChargeId").asString());
        assertThat(owner().queryForMap("select c.kind, c.source, c.amount::text as amount, c.adjusts_charge_id, "
                + "c.run_id, c.recalculation_id, c.reason, c.service_from::text as service_from, "
                + "c.accrued_on::text as accrued_on, to_char(m.month, 'YYYY-MM') as billing_month from charge c "
                + "join billing_month m on m.id = c.billing_month_id where c.id = ?", adjustment))
                .containsEntry("kind", "ADJUSTMENT").containsEntry("source", "RECALCULATION")
                .containsEntry("amount", "259.20").containsEntry("adjusts_charge_id", originals.get(p1))
                .containsEntry("run_id", run).containsEntry("recalculation_id", recalculation)
                .containsEntry("reason", REASON).containsEntry("service_from", september.atDay(1).toString())
                .containsEntry("accrued_on", to).containsEntry("billing_month", october.toString());
        // the parts of the expected charge, and the values of the node it shares in
        assertThat(owner().queryForList("select node_meter_id from charge_part where charge_id = ?", UUID.class,
                adjustment)).containsOnly(quarter);
        assertThat(owner().queryForList("select meter_id::text || '@' || used_on from meter_value_use "
                + "where charge_id = ?", String.class, adjustment)).contains(quarter + "@" + before,
                        quarter + "@" + to);
        // September keeps its charges
        assertThat(owner().queryForObject("select count(*) from charge where run_id = ? and kind = 'REVERSAL'",
                Integer.class, run)).isZero();

        assertThat(preview(scope("ELECTRICITY")).get("lines")).isEmpty();
    }

    // ---- common consumer runs

    @Test
    void aCommonConsumerRunOfAnOpenMonthIsReversedAndBookedAgain() throws Exception {
        UUID lighting = lighting();
        UUID run = bookConsumerRun();
        correct(lighting, to, "1080");

        JsonNode preview = preview(scope("COMMON_CONSUMERS"));

        assertThat(lines(preview.get("lines"), "plotNumber", "action", "booked", "expected", "runId"))
                .containsExactly("1|REVERSE_AND_RECHARGE|72.00|115.20|" + run,
                        "2|REVERSE_AND_RECHARGE|72.00|115.20|" + run, "3|REVERSE_AND_RECHARGE|72.00|115.20|" + run);
        assertThat(preview.get("total").asString()).isEqualTo("129.60");

        JsonNode booked = book(scope("COMMON_CONSUMERS", preview.get("digest").asString()));

        UUID recalculation = UUID.fromString(booked.get("id").asString());
        assertThat(owner().queryForObject("select status from common_consumer_run where id = ?", String.class, run))
                .isEqualTo("REVERSED");
        assertThat(owner().queryForList("select distinct run_status from common_consumer_run_line where run_id = ?",
                String.class, run)).containsExactly("REVERSED");
        assertThat(owner().queryForList("select source from charge where kind = 'REVERSAL' and run_id = ?",
                String.class, run)).containsOnly("RECALCULATION").hasSize(3);
        UUID rebooked = owner().queryForObject("select id from common_consumer_run where tenant_id = ? "
                + "and status = 'BOOKED'", UUID.class, tenant);
        assertThat(owner().queryForList("select amount::text || '|' || source_key || '|' || recalculation_id "
                + "from charge where run_id = ? and plot_id = ?", String.class, rebooked, p3)).containsExactly(
                        "115.20|COMMON_CONSUMER_RUN:" + rebooked + ":" + lighting + ":" + p3 + "|" + recalculation);
        assertThat(owner().queryForList("select meter_id::text || '@' || used_on from meter_value_use "
                + "where run_id = ?", String.class, rebooked)).containsExactlyInAnyOrder(lighting + "@" + before,
                        lighting + "@" + to);

        assertThat(preview(scope("COMMON_CONSUMERS")).get("lines")).isEmpty();
    }

    @Test
    void aCommonConsumerRunOfAClosedMonthGetsAnAdjustmentPerPlot() throws Exception {
        UUID lighting = lighting();
        UUID run = bookConsumerRun();
        closeMonth(september.toString());
        correct(lighting, to, "1080");

        JsonNode preview = preview(scope("COMMON_CONSUMERS"));

        assertThat(lines(preview.get("lines"), "plotNumber", "action", "difference", "bookedIn", "runId"))
                .containsExactly("1|ADJUST|43.20|" + october + "|" + run, "2|ADJUST|43.20|" + october + "|" + run,
                        "3|ADJUST|43.20|" + october + "|" + run);

        JsonNode booked = book(scope("COMMON_CONSUMERS", preview.get("digest").asString()));

        assertThat(owner().queryForObject("select status from common_consumer_run where id = ?", String.class, run))
                .isEqualTo("BOOKED");
        UUID adjustment = UUID.fromString(booked.get("lines").get(0).get("adjustmentChargeId").asString());
        assertThat(owner().queryForMap("select kind, amount::text as amount, run_id from charge where id = ?",
                adjustment)).containsEntry("kind", "ADJUSTMENT").containsEntry("amount", "43.20")
                .containsEntry("run_id", run);
        assertThat(owner().queryForList("select meter_id::text || '@' || used_on from meter_value_use "
                + "where charge_id = ?", String.class, adjustment)).containsExactlyInAnyOrder(
                        lighting + "@" + before, lighting + "@" + to);
    }

    @Test
    void aPlotNewToARunOfAClosedMonthGetsAChargeOfTheOriginalRun() throws Exception {
        UUID lighting = lighting();
        UUID run = bookConsumerRun();
        closeMonth(september.toString());
        UUID p4 = plot("4", "600");

        JsonNode preview = preview(scope("COMMON_CONSUMERS"));

        // 50 kWh among four plots now: 54.00 each
        assertThat(lines(preview.get("lines"), "plotNumber", "action", "booked", "expected", "difference",
                "bookedIn", "runId")).containsExactly(
                        "1|ADJUST|72.00|54.00|-18.00|" + october + "|" + run,
                        "2|ADJUST|72.00|54.00|-18.00|" + october + "|" + run,
                        "3|ADJUST|72.00|54.00|-18.00|" + october + "|" + run,
                        "4|NEW|0.00|54.00|54.00|" + october + "|" + run);
        assertThat(preview.get("total").asString()).isEqualTo("0.00");

        JsonNode booked = book(scope("COMMON_CONSUMERS", preview.get("digest").asString()));

        UUID recalculation = UUID.fromString(booked.get("id").asString());
        UUID created = UUID.fromString(booked.get("lines").get(3).get("newChargeId").asString());
        assertThat(owner().queryForMap("select c.kind, c.source, c.amount::text as amount, c.run_id, c.source_key, "
                + "c.recalculation_id, c.service_from::text as service_from, c.accrued_on::text as accrued_on, "
                + "to_char(m.month, 'YYYY-MM') as billing_month from charge c join billing_month m "
                + "on m.id = c.billing_month_id where c.id = ?", created))
                .containsEntry("kind", "REGULAR").containsEntry("source", "COMMON_CONSUMER")
                .containsEntry("amount", "54.00").containsEntry("run_id", run)
                .containsEntry("source_key", "COMMON_CONSUMER_RUN:" + run + ":" + lighting + ":" + p4)
                .containsEntry("recalculation_id", recalculation)
                .containsEntry("service_from", september.atDay(1).toString()).containsEntry("accrued_on", to)
                .containsEntry("billing_month", october.toString());
        assertThat(owner().queryForObject("select count(*) from charge_part where charge_id = ?", Integer.class,
                created)).isEqualTo(1);
        assertThat(owner().queryForList("select meter_id::text || '@' || used_on from meter_value_use "
                + "where charge_id = ?", String.class, created)).containsExactlyInAnyOrder(lighting + "@" + before,
                        lighting + "@" + to);

        // the new plot's charge belongs to the run now: a second recalculation finds nothing
        assertThat(preview(scope("COMMON_CONSUMERS")).get("lines")).isEmpty();
    }

    // ---- fixtures

    /** Quarter A 1 100 kWh with P1 600 and P2 400 and street lighting 0, read before and at the end of the range. */
    private UUID quarter() {
        UUID quarter = meter("QUARTER", "Квартал А", null, null, 5000, 6100);
        meter("PLOT", null, p1, quarter, 1000, 1600);
        meter("PLOT", null, p2, quarter, 2000, 2400);
        meter("COMMON_CONSUMER", "Освітлення", null, quarter, 300, 300);
        return quarter;
    }

    /** Street lighting 50 kWh, shared equally per plot. */
    private UUID lighting() throws Exception {
        UUID lighting = meter("COMMON_CONSUMER", "Освітлення", null, null, 1000, 1050);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("split", "EQUAL_PER_PLOT");
        body.put("servicePlotIds", List.of());
        send(chairman, put("/api/v1/common-consumers/" + lighting + "/billing"), body).andExpect(status().isOk());
        return lighting;
    }

    private UUID meter(String kind, String name, UUID plot, UUID parent, int beforeValue, int toValue) {
        UUID id = UUID.randomUUID();
        owner().update("insert into meter (id, tenant_id, kind, resource, serial, name, plot_id, installed_on, "
                + "initial_value) values (?, ?, ?, 'ELECTRICITY', ?, ?, ?, ?::date, 0)", id, tenant, kind,
                "RR-" + id.toString().substring(0, 8), name, plot, installed);
        if (parent != null) {
            owner().update("insert into meter_parent (tenant_id, meter_id, parent_meter_id, valid_from) "
                    + "values (?, ?, ?, ?::date)", tenant, id, parent, installed);
        }
        reading(id, before, beforeValue);
        reading(id, to, toValue);
        return id;
    }

    private void reading(UUID meter, String on, int value) {
        owner().update("insert into meter_reading (tenant_id, meter_id, taken_on, value, source, status) "
                + "values (?, ?, ?::date, ?, 'ENTERED', 'APPROVED')", tenant, meter, on, new BigDecimal(value));
    }

    /** Corrects a reading in the database, as a cause-driven correction will (no reading event). */
    private void correct(UUID meter, String on, String value) {
        owner().update("update meter_reading set value = ? where meter_id = ? and taken_on = ?::date",
                new BigDecimal(value), meter, on);
    }

    private UUID bookLossesRun() throws Exception {
        return bookRun(LOSSES_RUNS);
    }

    private UUID bookConsumerRun() throws Exception {
        return bookRun(CONSUMER_RUNS);
    }

    private UUID bookRun(String path) throws Exception {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("from", september.atDay(1).toString());
        range.put("to", to);
        return UUID.fromString(body(send(chairman, post(path + "?preview=false"), range)
                .andExpect(status().isOk())).get("runId").asString());
    }

    /** The run's charges by plot. */
    private Map<UUID, UUID> runCharges(UUID run) {
        Map<UUID, UUID> byPlot = new LinkedHashMap<>();
        owner().queryForList("select plot_id, id from charge where run_id = ? and kind = 'REGULAR'", run)
                .forEach(row -> byPlot.put((UUID) row.get("plot_id"), (UUID) row.get("id")));
        return byPlot;
    }

    private Map<String, Object> scope(String typeCode) {
        return scope(typeCode, null, null);
    }

    private Map<String, Object> scope(String typeCode, String digest) {
        return scope(typeCode, REASON, digest);
    }

    private Map<String, Object> scope(String typeCode, String reason, String digest) {
        Map<String, Object> manual = new LinkedHashMap<>();
        manual.put("from", september.toString());
        manual.put("to", october.toString());
        manual.put("chargeTypeIds", List.of(typeId(typeCode)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cause", Map.of("type", "MANUAL", "manual", manual));
        if (reason != null) {
            body.put("reason", reason);
        }
        if (digest != null) {
            body.put("digest", digest);
        }
        return body;
    }

    private JsonNode preview(Map<String, Object> body) throws Exception {
        return body(send(chairman, post(RECALCULATIONS).param("preview", "true"), body).andExpect(status().isOk()));
    }

    private JsonNode book(Map<String, Object> body) throws Exception {
        return body(send(chairman, post(RECALCULATIONS).param("preview", "false"), body)
                .andExpect(status().isOk()));
    }

    /** The lines of the run (an electricity scope also has the plot meters' own intervals). */
    private static List<String> runLines(JsonNode result, UUID run, String... fields) {
        List<JsonNode> of = new ArrayList<>();
        result.get("lines").forEach(line -> {
            if (line.has("runId") && line.get("runId").asString().equals(run.toString())) {
                of.add(line);
            }
        });
        return lines(of, fields);
    }

    private static JsonNode runLine(JsonNode result, UUID run, String plotNumber) {
        for (JsonNode line : result.get("lines")) {
            if (line.has("runId") && line.get("runId").asString().equals(run.toString())
                    && line.get("plotNumber").asString().equals(plotNumber)) {
                return line;
            }
        }
        throw new AssertionError("No line of plot " + plotNumber + " of the run");
    }

    /** The given fields of every element joined by {@code |}; an array field as its values joined by {@code ,}. */
    private static List<String> lines(Iterable<JsonNode> array, String... fields) {
        List<String> lines = new ArrayList<>();
        for (JsonNode element : array) {
            List<String> values = new ArrayList<>();
            for (String field : fields) {
                JsonNode value = element.get(field);
                if (value == null || value.isNull()) {
                    values.add("");
                } else if (value.isArray()) {
                    values.add(String.join(",", value.valueStream().map(JsonNode::asString).toList()));
                } else {
                    values.add(value.asString());
                }
            }
            lines.add(String.join("|", values));
        }
        return lines;
    }
}
