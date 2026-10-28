package app.nadel.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.DemoLedgerData;
import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import app.nadel.ledger.ChargesBooked;
import app.nadel.ledger.internal.CommonConsumerPlanning.ConsumerInput;
import app.nadel.ledger.internal.CommonConsumerPlanning.PlannedLine;
import app.nadel.ledger.internal.LossesCalculator.NodeResult;
import app.nadel.ledger.internal.LossesCalculator.ValueUse;
import app.nadel.ledger.internal.LossesPlanning.PlannedInput;
import app.nadel.ledger.internal.LossesPlanning.PlannedNode;
import app.nadel.ledger.internal.ReadingChargeCalculator.PricedPart;
import app.nadel.ledger.internal.ReadingChargePlanning.Failed;
import app.nadel.ledger.internal.ReadingChargePlanning.MeterInterval;
import app.nadel.ledger.internal.ReadingChargePlanning.NewCharges;
import app.nadel.ledger.internal.ReadingChargePlanning.PlannedReadingCharge;
import app.nadel.ledger.internal.TariffMonthPlanning.NewCharge;
import app.nadel.money.Money;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterNetwork;
import app.nadel.meters.MeterNetwork.MeterPoints;
import app.nadel.meters.MeterNetwork.NetworkChain;
import app.nadel.meters.MeterResource;
import app.nadel.meters.Quantity;
import app.nadel.meters.WaterConnections;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.settings.CommonConsumerSplit;
import app.nadel.settings.CooperativeSettings;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.DaySet;
import app.nadel.tariffs.TariffBook;
import app.nadel.tariffs.TariffBook.ChargeTypeEntry;
import app.nadel.tariffs.TariffBook.Snapshot;
import app.nadel.tariffs.TariffResolver;
import app.nadel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The demo ledger of {@code R__demo_data.sql} (the {@code demo ledger} block) is what the application books from the
 * same rows: every demo charge is recalculated here with the ledger's own planning code and calculators (month runs,
 * reading intervals, the losses run, the common consumers run) and compared with the stored row, its parts, its stored
 * calculation and the meter values it read; the plot debts are recomputed by the status refresher; the screens show
 * the feature (months, the queue, the map, accounts). So the SQL and the code cannot drift apart. The block is loaded
 * for each test and removed afterwards (the other tests share a demo cooperative without a ledger).
 */
class DemoLedgerDataTests extends IntegrationTest {

    private static final String ELECTRICITY_TARIFF = "ffffffff-0000-0000-0000-000000000003";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private MeterNetwork network;

    @Autowired
    private WaterConnections water;

    @Autowired
    private PlotCatalog plots;

    @Autowired
    private TariffBook book;

    @Autowired
    private CooperativeSettings settings;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PlotLedgerQueries ledgers;

    @Autowired
    private ChargeTypeCatalog chargeTypes;

    private final JdbcTemplate owner = ownerJdbc();

    @BeforeEach
    void loadTheDemoLedger() {
        DemoLedgerData.restore(owner);
        unlinkDemoMembers();
    }

    @AfterEach
    void removeTheDemoLedger() {
        try {
            awaitListeners();
        } catch (ConditionTimeoutException e) {
            // a listener that failed keeps its publication incomplete until a restart; nothing to wait for any more
        }
        DemoLedgerData.clear(owner);
        unlinkDemoMembers();
    }

    // ---- the month runs

    @Test
    void everyMonthRunChargeIsWhatTheMonthRunPlans() throws Exception {
        List<YearMonth> months = owner.queryForList("select to_char(month, 'YYYY-MM') from billing_month "
                + "where tenant_id = ? order by month", String.class, DEMO_TENANT).stream().map(YearMonth::parse)
                .toList();
        assertThat(months).hasSize(2);

        Map<String, TariffMonthPlanning.PlannedCharge> planned = inDemo(() -> {
            Snapshot snapshot = book.snapshot();
            Map<String, TariffMonthPlanning.PlannedCharge> result = new LinkedHashMap<>();
            for (PlotFacts plot : plots.findChargeable()) {
                for (ChargeTypeEntry type : snapshot.chargeTypes()) {
                    for (YearMonth month : months) {
                        if (TariffMonthPlanning.plan(snapshot, type.id(), MonthRun.facts(plot), month, DaySet.ALL,
                                key -> false) instanceof NewCharge newCharge) {
                            result.put(newCharge.charge().sourceKey(), newCharge.charge());
                        }
                    }
                }
            }
            return result;
        });

        Map<String, Map<String, Object>> stored = charges("source = 'TARIFF' and c.recalculation_id is null");
        // plot 2's membership was booked at 60 per sotka and is corrected by the demo recalculation (see
        // theDemoRecalculationIsBookedLikeARealOne); the month run plans it at the current rate of 50
        Map<String, Object> corrected = new HashMap<>();
        for (Map<String, Object> row : owner.queryForList("select c.source_key, c.amount, to_char(b.month, 'YYYY-MM') "
                + "as month from charge c join billing_month b on b.id = c.billing_month_id "
                + "join plot p on p.id = c.plot_id where c.tenant_id = ? and p.number = '2' and c.source = 'TARIFF' "
                + "and c.recalculation_id is null and c.charge_type_id = md5(? || ':CHARGE_TYPE:MEMBERSHIP')::uuid",
                DEMO_TENANT, DEMO_TENANT.toString())) {
            corrected.put((String) row.get("source_key"), row.get("amount"));
            assertAmount(row, new BigDecimal("30.00"));
            assertAmount(stored.remove((String) row.get("source_key")), new BigDecimal("30.00"));
            assertThat(planned.remove((String) row.get("source_key")).amount().value())
                    .isEqualByComparingTo("25.00");
        }
        assertThat(corrected).hasSize(2);
        assertThat(stored.keySet()).containsExactlyInAnyOrderElementsOf(planned.keySet());
        assertThat(planned).hasSizeGreaterThan(50);
        for (TariffMonthPlanning.PlannedCharge charge : planned.values()) {
            Map<String, Object> row = stored.get(charge.sourceKey());
            assertAmount(row, charge.amount().value());
            assertThat(row.get("service_from")).hasToString(charge.serviceFrom().toString());
            assertThat(row.get("service_to")).hasToString(charge.serviceTo().toString());
            assertThat(row.get("accrued_on")).hasToString(charge.accruedOn().toString());
            assertThat(row.get("month")).hasToString(charge.month().toString());
            assertCalculation(row, charge.calculation());
            List<Map<String, Object>> parts = parts((UUID) row.get("id"));
            assertThat(parts).hasSize(charge.breakdown().parts().size());
            for (int i = 0; i < parts.size(); i++) {
                ChargeCalculatorPart expected = new ChargeCalculatorPart(charge.versionIds().get(i),
                        charge.breakdown().parts().get(i).days(), charge.breakdown().parts().get(i).units(),
                        BigDecimal.ONE, charge.breakdown().parts().get(i).terms().rate().value());
                expected.assertMatches(parts.get(i));
            }
        }
    }

    @Test
    void theOpenMonthRunHasNothingLeftToCharge() throws Exception {
        String open = owner.queryForObject("select to_char(month, 'YYYY-MM') from billing_month "
                + "where tenant_id = ? and status = 'OPEN'", String.class, DEMO_TENANT);
        JsonNode preview = body(mvc.perform(post("/api/v1/billing-months/" + open + "/run?preview=true")
                .header(HttpHeaders.AUTHORIZATION, chairman())).andExpect(status().isOk()));
        assertThat(preview.get("plots").asInt()).isZero();
        assertThat(preview.get("total").asString()).isEqualTo("0.00");
    }

    // ---- electricity by readings

    @Test
    void everyReadingChargeAndTheQueueItemAreWhatTheIntervalsPlan() throws Exception {
        LocalDate closedMonth = owner.queryForObject("select month from billing_month where tenant_id = ? "
                + "and status = 'CLOSED'", LocalDate.class, DEMO_TENANT);
        List<UUID> meters = owner.queryForList("select id from meter where tenant_id = ? and kind = 'PLOT' "
                + "and resource = 'ELECTRICITY'", UUID.class, DEMO_TENANT);

        Map<String, PlannedReadingCharge> planned = new LinkedHashMap<>();
        Map<String, Failed> failed = new LinkedHashMap<>();
        inDemo(() -> {
            Snapshot snapshot = book.snapshot();
            UUID electricity = typeId(snapshot, "ELECTRICITY");
            for (UUID meter : meters) {
                MeterPoints points = network.lockSharedPoints(meter).orElseThrow();
                TariffResolver.PlotFacts plot = MonthRun.facts(plots.find(points.plotId()).orElseThrow());
                for (MeterInterval interval : IntervalCharging.intervals(points)) {
                    if (interval.to().on().isBefore(closedMonth)) {
                        continue;
                    }
                    switch (ReadingChargePlanning.plan(snapshot, electricity, plot, MeterResource.ELECTRICITY,
                            interval, DaySet.ALL, null, key -> false)) {
                        case NewCharges charges -> {
                            assertThat(charges.losses()).isNull();
                            planned.put(charges.consumption().sourceKey(), charges.consumption());
                        }
                        case Failed failure -> failed.put(NotChargedItemRecord.intervalKey(meter,
                                interval.to().key()), failure);
                        default -> {
                            // nothing to charge, nothing stored
                        }
                    }
                }
            }
            return null;
        });

        Map<String, Map<String, Object>> stored = charges("source = 'CONSUMPTION'");
        assertThat(stored.keySet()).containsExactlyInAnyOrderElementsOf(planned.keySet());
        assertThat(planned).hasSizeGreaterThan(50);
        for (PlannedReadingCharge charge : planned.values()) {
            Map<String, Object> row = stored.get(charge.sourceKey());
            assertAmount(row, charge.amount().value());
            assertThat(row.get("service_from")).hasToString(charge.serviceFrom().toString());
            assertThat(row.get("service_to")).hasToString(charge.serviceTo().toString());
            assertThat(row.get("accrued_on")).hasToString(charge.accruedOn().toString());
            assertThat(row.get("meter_id")).isEqualTo(charge.interval().meterId());
            assertCalculation(row, charge.calculation());
            assertPricedParts((UUID) row.get("id"), charge.versionIds(), charge.parts(), null);
            assertThat(owner.queryForList("select used_on::text from meter_value_use where charge_id = ? "
                    + "order by used_on", String.class, row.get("id"))).containsExactly(
                    charge.interval().from().on().toString(), charge.interval().to().on().toString());
        }

        // exactly one interval cannot be charged: plot 25's meter, installed before the first electricity tariff
        assertThat(failed).hasSize(1);
        Map.Entry<String, Failed> item = failed.entrySet().iterator().next();
        assertThat(item.getValue().reason()).isEqualTo(NotChargedItemRecord.Reason.NO_TARIFF_FOR_ALL_DAYS);
        assertThat(owner.queryForList("select p.number || '|' || i.reason || '|' || (i.params ->> 'toKey') from "
                + "not_charged_item i join plot p on p.id = i.plot_id where i.tenant_id = ? and i.kind = 'INTERVAL' "
                + "and i.status = 'OPEN' and i.item_key = ?", String.class, DEMO_TENANT, item.getKey()))
                .singleElement().satisfies(text -> assertThat(text).startsWith("25|NO_TARIFF_FOR_ALL_DAYS|"));
        assertThat(owner.queryForObject("select params -> 'tariffIds' ->> 0 from not_charged_item "
                + "where item_key = ?", String.class, item.getKey())).isEqualTo(ELECTRICITY_TARIFF);
    }

    // ---- the losses run

    @Test
    void theLossesRunIsWhatTheLossesCalculatorWorksOut() throws Exception {
        Map<String, Object> run = owner.queryForMap("select id, from_date, to_date, total from losses_run "
                + "where tenant_id = ?", DEMO_TENANT);
        UUID runId = (UUID) run.get("id");
        LocalDate from = LocalDate.parse(run.get("from_date").toString());
        LocalDate to = LocalDate.parse(run.get("to_date").toString());
        List<Map<String, Object>> nodes = owner.queryForList("select node_meter_id, outcome, consumption, children, "
                + "losses, pct, calculated, amount, warning::text as warning, plots::text as plots "
                + "from losses_run_node where run_id = ? order by position", runId);
        assertThat(nodes).extracting(n -> n.get("outcome")).containsExactly("CHARGED", "UNKNOWN");

        record Expected(LossesPlanning.Plan plan, Map<UUID, NetworkChain> chains) {
        }
        Expected expected = inDemo(() -> {
            Snapshot snapshot = book.snapshot();
            List<NetworkChain> all = network.network(from, to).chains();
            Map<UUID, NetworkChain> chains = all.stream()
                    .collect(Collectors.toMap(NetworkChain::key, c -> c, (a, b) -> a, LinkedHashMap::new));
            Map<UUID, NodeResult> results = LossesCalculator.calculate(all, from, to).stream()
                    .collect(Collectors.toMap(NodeResult::nodeKey, r -> r));
            List<PlannedInput> inputs = nodes.stream().map(n -> (UUID) n.get("node_meter_id"))
                    .map(key -> new PlannedInput(chains.get(key), results.get(key), null)).toList();
            Set<UUID> plotIds = new HashSet<>();
            inputs.stream().filter(PlannedInput::charges).forEach(i -> plotIds.addAll(i.result().shares().keySet()));
            Map<UUID, TariffResolver.PlotFacts> facts = new HashMap<>();
            plots.findAll(plotIds).forEach((id, plot) -> facts.put(id, MonthRun.facts(plot)));
            return new Expected(LossesPlanning.plan(from, to, inputs, snapshot, typeId(snapshot, "ELECTRICITY"), facts),
                    chains);
        });

        for (int i = 0; i < nodes.size(); i++) {
            Map<String, Object> row = nodes.get(i);
            PlannedNode node = expected.plan().nodes().get(i);
            NodeResult result = node.result();
            assertThat(row.get("outcome")).isEqualTo(result.outcome().name());
            assertThat(row.get("consumption")).isEqualTo(meterValue(result.consumption()));
            assertThat(row.get("children")).isEqualTo(meterValue(result.children()));
            Quantity losses = result.losses();
            assertThat(row.get("losses")).isEqualTo(losses == null ? null
                    : new BigDecimal(LossesRunViews.kwh(losses)));
            assertThat(row.get("pct")).isEqualTo(losses == null || result.consumption() == null ? null
                    : LossesRunViews.pct(losses, result.consumption()));
            assertThat(row.get("calculated")).isEqualTo(result.calculated());
            assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo(node.amount().value());
            assertThat(json((String) row.get("warning")))
                    .isEqualTo(json(LossesRunViews.warning(node, expected.chains())));
            assertThat(sortedByPlot(json((String) row.get("plots"))))
                    .isEqualTo(sortedByPlot(json(LossesRunViews.plots(node))));
        }
        // quarter A charged with 5-15 % losses, quarter Б unknown because plot 20's meter was not read
        assertThat((BigDecimal) nodes.getFirst().get("pct")).isBetween(new BigDecimal("5"), new BigDecimal("15"));
        assertThat(owner.queryForObject("select p.number from plot p where p.id = ?::uuid", String.class,
                json((String) nodes.get(1).get("warning")).get("plotId").asString())).isEqualTo("20");

        Map<String, Map<String, Object>> stored = charges("source = 'LOSSES'");
        assertThat(stored).hasSize(expected.plan().charges().size());
        assertThat(expected.plan().charges()).hasSizeGreaterThan(10);
        for (LossesPlanning.PlannedCharge charge : expected.plan().charges()) {
            Map<String, Object> row = stored.get("LOSSES_RUN:" + runId + ":" + charge.plotId());
            assertAmount(row, charge.amount().value());
            assertThat(row.get("service_from")).hasToString(from.toString());
            assertThat(row.get("accrued_on")).hasToString(to.toString());
            assertCalculation(row, charge.calculation(runId));
            assertPricedParts((UUID) row.get("id"), charge.versionIds(),
                    charge.parts().stream().map(LossesPlanning.NodePart::part).toList(),
                    charge.parts().stream().map(LossesPlanning.NodePart::nodeKey).toList());
        }
        assertThat((BigDecimal) run.get("total")).isEqualByComparingTo(expected.plan().total().value());

        Set<ValueUse> uses = new HashSet<>();
        expected.plan().nodes().stream().filter(PlannedNode::charges).forEach(n -> uses.addAll(n.result().uses()));
        assertThat(valueUses(runId)).containsExactlyInAnyOrderElementsOf(uses);

        // the unknown node is queued, nothing else of the run
        assertThat(owner.queryForList("select item_key from not_charged_item where tenant_id = ? and "
                + "kind = 'LOSSES_NODE' and status = 'OPEN'", String.class, DEMO_TENANT)).containsExactly(
                NotChargedItemRecord.lossesNodeKey((UUID) nodes.get(1).get("node_meter_id"), from, to));
    }

    // ---- the common consumers run

    @Test
    void theCommonConsumersRunIsWhatTheSplitWorksOut() throws Exception {
        Map<String, Object> run = owner.queryForMap("select id, from_date, to_date, total from common_consumer_run "
                + "where tenant_id = ?", DEMO_TENANT);
        UUID runId = (UUID) run.get("id");
        LocalDate from = LocalDate.parse(run.get("from_date").toString());
        LocalDate to = LocalDate.parse(run.get("to_date").toString());
        List<Map<String, Object>> lines = owner.queryForList("select consumer_meter_id, consumer_name, split, "
                + "outcome, consumption, calculated, plot_days, amount, warning::text as warning, plots::text as plots "
                + "from common_consumer_run_line where run_id = ? order by position", runId);
        assertThat(lines).extracting(l -> l.get("split") + "|" + l.get("outcome"))
                .containsExactly("EQUAL_PER_PLOT|CHARGED", "CONNECTED_PLOTS|CHARGED");

        CommonConsumerPlanning.Plan plan = inDemo(() -> {
            Snapshot snapshot = book.snapshot();
            Map<UUID, NetworkChain> chains = network.network(from, to).chains().stream()
                    .filter(chain -> chain.kind() == MeterKind.COMMON_CONSUMER)
                    .collect(Collectors.toMap(NetworkChain::key, c -> c));
            List<PlotFacts> chargeable = plots.findChargeable();
            List<ConsumerInput> inputs = new ArrayList<>();
            for (Map<String, Object> line : lines) {
                NetworkChain chain = chains.get((UUID) line.get("consumer_meter_id"));
                CommonConsumerSplit split = splitOf(chain);
                inputs.add(new ConsumerInput(chain, split, LossesCalculator.measure(chain, from, to),
                        eligible(chain, split, chargeable, from, to), null));
            }
            Map<UUID, TariffResolver.PlotFacts> facts = new LinkedHashMap<>();
            chargeable.forEach(plot -> facts.put(plot.id(), MonthRun.facts(plot)));
            return CommonConsumerPlanning.plan(from, to, inputs, snapshot, facts);
        });

        assertThat(plan.noTariff()).isFalse();
        for (int i = 0; i < lines.size(); i++) {
            Map<String, Object> row = lines.get(i);
            PlannedLine line = plan.lines().get(i);
            assertThat(row.get("split")).isEqualTo(line.split().name());
            assertThat(row.get("outcome")).isEqualTo(line.outcome().name());
            assertThat(row.get("consumer_name")).isEqualTo(line.chain().name());
            assertThat(row.get("consumption")).isEqualTo(meterValue(line.consumption()));
            assertThat(row.get("calculated")).isEqualTo(line.calculated());
            assertThat(((Number) row.get("plot_days")).longValue()).isEqualTo(line.plotDays());
            assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo(line.amount().value());
            assertThat(row.get("warning")).isNull();
            assertThat(sortedByPlot(json((String) row.get("plots"))))
                    .isEqualTo(sortedByPlot(json(CommonConsumerRunViews.plots(line))));
        }
        // the well: plot 19 connected for part of the range, three plots not connected
        assertThat(json((String) lines.get(1).get("plots"))).hasSize(27);
        assertThat(json((String) lines.get(1).get("plots")).valueStream().map(p -> p.get("days").asInt())
                .collect(Collectors.toSet())).hasSize(2);

        Map<String, Map<String, Object>> stored = charges("source = 'COMMON_CONSUMER'");
        assertThat(stored).hasSize(plan.charges().size());
        for (CommonConsumerPlanning.PlannedCharge charge : plan.charges()) {
            Map<String, Object> row = stored.get("COMMON_CONSUMER_RUN:" + runId + ":" + charge.consumerKey() + ":"
                    + charge.plotId());
            assertAmount(row, charge.amount().value());
            assertCalculation(row, charge.calculation(runId));
            assertPricedParts((UUID) row.get("id"), charge.versionIds(), charge.parts(), null);
        }
        assertThat((BigDecimal) run.get("total")).isEqualByComparingTo(plan.total().value());

        Set<ValueUse> uses = new HashSet<>();
        plan.lines().stream().filter(PlannedLine::charges).forEach(l -> uses.addAll(l.uses()));
        assertThat(valueUses(runId)).containsExactlyInAnyOrderElementsOf(uses);
    }

    // ---- debts and screens

    @Test
    void thePlotDebtsAreWhatTheStatusRefresherRecords() {
        String snapshot = "select p.number || '|' || s.debt || '|' || coalesce(s.oldest_debt_date::text, '-') || '|' "
                + "|| s.status || '|' || s.prepayment from plot_status s join plot p on p.id = s.plot_id "
                + "where p.tenant_id = ? order by p.number";
        List<String> demo = owner.queryForList(snapshot, String.class, DEMO_TENANT);
        Set<UUID> plotIds = new HashSet<>(owner.queryForList("select id from plot where tenant_id = ?", UUID.class,
                DEMO_TENANT));

        TenantContext.runAs(DEMO_TENANT, () -> tx.execute(s -> {
            events.publishEvent(new ChargesBooked(DEMO_TENANT, plotIds));
            return null;
        }));
        awaitListeners();

        assertThat(owner.queryForList(snapshot, String.class, DEMO_TENANT)).isEqualTo(demo);
        // the colours follow the rule for today whatever today is (a debt older than the red days is red, so the
        // mix of red and yellow moves with the date); plot 12's opening balance is above the red amount on any date
        Map<String, String> expected = expectedStatuses(false);
        assertThat(owner.queryForList("select p.number || '|' || s.status from plot_status s join plot p "
                + "on p.id = s.plot_id where p.tenant_id = ? order by p.number", String.class, DEMO_TENANT))
                .isEqualTo(expected.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(e -> e.getKey() + "|" + e.getValue()).toList());
        assertThat(expected.values()).contains("RED", "GREY").doesNotContainNull();
        assertThat(expected).containsEntry("12", "RED");
    }

    /**
     * The status of every demo plot by the rule of the plot map for today (Kyiv), computed in SQL from the cached debt:
     * grey for common land and a plot without a current owner, green without a debt, red when the debt is above the red
     * amount or its oldest charge is older than the red days, yellow otherwise.
     *
     * @param onlyOnMap only the plots the map shows (active, with a boundary), so rows left by other test classes
     *                  cannot change the result
     */
    private Map<String, String> expectedStatuses(boolean onlyOnMap) {
        Map<String, String> statuses = new HashMap<>();
        owner.queryForList("""
                select p.number,
                       case when p.kind = 'COMMON' or not exists (select 1 from ownership o
                                where o.plot_id = p.id and o.valid_to is null) then 'GREY'
                            when s.debt <= 0 then 'GREEN'
                            when s.debt > coalesce(cs.red_amount, 1000.00)
                                 or ((now() at time zone 'Europe/Kyiv')::date - s.oldest_debt_date)
                                    > coalesce(cs.red_days, 90) then 'RED'
                            else 'YELLOW' end as status
                from plot p
                join plot_status s on s.plot_id = p.id
                left join cooperative_settings cs on cs.tenant_id = p.tenant_id
                where p.tenant_id = ?""" + (onlyOnMap ? " and p.archived_at is null and p.boundary is not null" : ""),
                DEMO_TENANT).forEach(row -> statuses.put((String) row.get("number"),
                (String) row.get("status")));
        return statuses;
    }

    @Test
    void theScreensShowTheFeature() throws Exception {
        String open = owner.queryForObject("select to_char(month, 'YYYY-MM') from billing_month "
                + "where tenant_id = ? and status = 'OPEN'", String.class, DEMO_TENANT);
        String closed = owner.queryForObject("select to_char(month, 'YYYY-MM') from billing_month "
                + "where tenant_id = ? and status = 'CLOSED'", String.class, DEMO_TENANT);

        // "Нарахування": the closed month and the open one with totals of every kind of charge
        Map<String, JsonNode> months = new HashMap<>();
        body(mvc.perform(get("/api/v1/billing-months").header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(status().isOk())).forEach(month -> months.put(month.get("month").asString(), month));
        assertThat(months.get(closed).get("status").asString()).isEqualTo("CLOSED");
        assertThat(months.get(open).get("status").asString()).isEqualTo("OPEN");
        assertThat(months.get(open).get("runDone").asBoolean()).isTrue();
        assertThat(months.get(open).get("totals").valueStream().map(t -> t.get("chargeTypeCode").asString()))
                .contains("MEMBERSHIP", "ELECTRICITY", "LAND_TAX", "COMMON_CONSUMERS");
        assertThat(owner.queryForList("select distinct c.source from charge c join billing_month b "
                + "on b.id = c.billing_month_id where b.tenant_id = ? and b.status = 'OPEN'", String.class,
                DEMO_TENANT)).contains("TARIFF", "CONSUMPTION", "LOSSES", "COMMON_CONSUMER", "MANUAL_REVERSAL");

        // "Не нараховано": exactly one item "no tariff for every day" (plot 25), and the unknown quarter of the run
        JsonNode queue = body(mvc.perform(get("/api/v1/not-charged").header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(status().isOk()));
        assertThat(queue.valueStream().filter(i -> i.get("reason").asString().equals("NO_TARIFF_FOR_ALL_DAYS"))
                .map(i -> i.get("plotNumber").asString())).containsExactly("25");
        assertThat(queue.valueStream().map(i -> i.get("kind").asString()))
                .containsExactlyInAnyOrder("INTERVAL", "LOSSES_NODE");

        // the map: red and yellow by debt
        JsonNode map = body(mvc.perform(get("/api/v1/plots/map").header(HttpHeaders.AUTHORIZATION, chairman()))
                .andExpect(status().isOk()));
        Map<String, String> statuses = new HashMap<>();
        map.get("features").forEach(f -> statuses.put(f.get("properties").get("number").asString(),
                f.get("properties").get("status").asString()));
        // red, yellow and green by debt and age (which of them show depends on today); grey for common land
        assertThat(statuses).isEqualTo(expectedStatuses(true));
        assertThat(statuses.values()).contains("RED", "GREY");
        assertThat(statuses.get("12")).isEqualTo("RED");

        // plot 12: the opening balance; plot 14: the reversal; the account's total is the cached debt
        JsonNode twelve = account("12");
        assertThat(twelve.get("charges").valueStream().filter(c -> c.get("kind").asString().equals("OPENING"))
                .map(c -> c.get("amount").asString() + "|" + c.get("reason").asString()))
                .containsExactly("1500.00|Борг за 2025 рік");
        assertThat(twelve.get("total").asString()).isEqualTo(owner.queryForObject("select s.debt::text from "
                + "plot_status s join plot p on p.id = s.plot_id where p.tenant_id = ? and p.number = '12'",
                String.class, DEMO_TENANT));
        JsonNode fourteen = account("14");
        assertThat(fourteen.get("charges").valueStream().filter(c -> c.get("kind").asString().equals("REVERSAL"))
                .map(c -> c.get("reason").asString())).containsExactly("Помилкове нарахування");
    }

    @Test
    void theDemoRecalculationIsBookedLikeARealOne() throws Exception {
        // exactly one recalculation: 60 -> 50 per sotka on plot 2 (600 m2), two lines
        Map<String, Object> recalculation = owner.queryForMap("select id, cause, reason, total, line_count, plot_count "
                + "from recalculation where tenant_id = ?", DEMO_TENANT);
        assertThat(recalculation).containsEntry("cause", "MANUAL")
                .containsEntry("reason", "Виправлено ставку членського внеску")
                .containsEntry("line_count", 2).containsEntry("plot_count", 1);
        assertThat((BigDecimal) recalculation.get("total")).isEqualByComparingTo("-10.00");
        UUID id = (UUID) recalculation.get("id");
        assertThat(owner.queryForObject("select count(*) from recalculation_line where recalculation_id = ?",
                Integer.class, id)).isEqualTo(2);
        // the lines point at the charges the recalculation booked
        assertThat(owner.queryForObject("select count(*) from recalculation_line l where l.recalculation_id = ? "
                + "and ((l.action = 'ADJUST' and l.adjustment_charge_id is not null) "
                + "or (l.action = 'REVERSE_AND_RECHARGE' and l.reversal_charge_id is not null "
                + "and l.new_charge_id is not null))", Integer.class, id)).isEqualTo(2);
        assertThat(owner.queryForObject("select count(*) from charge where tenant_id = ? and recalculation_id = ?",
                Integer.class, DEMO_TENANT, id)).isEqualTo(3);

        // the adjustment is visible on the plot's account, and the membership is the two months at the corrected rate
        JsonNode two = account("2");
        List<JsonNode> adjustments = two.get("charges").valueStream()
                .filter(c -> c.get("kind").asString().equals("ADJUSTMENT")).toList();
        assertThat(adjustments).hasSize(1);
        assertThat(adjustments.get(0).get("amount").asString()).isEqualTo("-5.00");
        assertThat(adjustments.get(0).get("source").asString()).isEqualTo("RECALCULATION");
        assertThat(adjustments.get(0).get("reason").asString()).isEqualTo("Виправлено ставку членського внеску");
        assertThat(two.get("charges").valueStream().filter(c -> c.get("kind").asString().equals("REVERSAL"))
                .map(c -> c.get("amount").asString())).containsExactly("-30.00");
        // membership of both months at the corrected rate: 30 + 30 - 30 + 25 - 5 = 2 x 25
        assertThat(owner.queryForObject("select sum(c.amount) from charge c join plot p on p.id = c.plot_id "
                + "where c.tenant_id = ? and p.number = '2' and c.charge_type_id = md5(? || ':CHARGE_TYPE:MEMBERSHIP')"
                + "::uuid", BigDecimal.class, DEMO_TENANT, DEMO_TENANT.toString())).isEqualByComparingTo("50.00");
        assertThat(two.get("total").asString()).isEqualTo(owner.queryForObject("select s.debt::text from plot_status s "
                + "join plot p on p.id = s.plot_id where p.tenant_id = ? and p.number = '2'", String.class,
                DEMO_TENANT));
        // no other plot carries an adjustment
        assertThat(owner.queryForObject("select count(*) from charge where tenant_id = ? and kind = 'ADJUSTMENT'",
                Integer.class, DEMO_TENANT)).isEqualTo(1);
    }

    @Test
    void runningTheBlockAgainChangesNothingAndClearRemovesIt() {
        String counts = "select (select count(*) from charge where tenant_id = ?) || '|' "
                + "|| (select coalesce(sum(amount), 0) from charge where tenant_id = ?) || '|' "
                + "|| (select count(*) from charge_part where tenant_id = ?) || '|' "
                + "|| (select count(*) from meter_value_use where tenant_id = ?) || '|' "
                + "|| (select count(*) from not_charged_item where tenant_id = ?) || '|' "
                + "|| (select count(*) from plot_water_connection where tenant_id = ?) || '|' "
                + "|| (select count(*) from billing_month where tenant_id = ?) || '|' "
                + "|| (select count(*) from meter_reading where tenant_id = ?) || '|' "
                + "|| (select count(*) from recalculation where tenant_id = ?) || '|' "
                + "|| (select count(*) from recalculation_line where tenant_id = ?) || '|' "
                + "|| (select count(*) from payment where tenant_id = ?) || '|' "
                + "|| (select coalesce(sum(amount), 0) from allocation where tenant_id = ?) || '|' "
                + "|| (select coalesce(sum(last_number), 0) from receipt_counter where tenant_id = ?)";
        Object[] tenant = {DEMO_TENANT, DEMO_TENANT, DEMO_TENANT, DEMO_TENANT, DEMO_TENANT, DEMO_TENANT, DEMO_TENANT,
            DEMO_TENANT, DEMO_TENANT, DEMO_TENANT, DEMO_TENANT, DEMO_TENANT, DEMO_TENANT};
        String before = owner.queryForObject(counts, String.class, tenant);

        DemoLedgerData.restore(owner);
        assertThat(owner.queryForObject(counts, String.class, tenant)).isEqualTo(before);

        DemoLedgerData.clear(owner);
        assertThat(owner.queryForObject(counts, String.class, tenant)).isEqualTo("0|0|0|0|0|0|0|186|0|0|0|0|0");
        assertThat(owner.queryForObject("select count(*) from meter where tenant_id = ?", Integer.class,
                DEMO_TENANT)).isEqualTo(38);
        assertThat(owner.queryForObject("select count(*) from plot_status s join plot p on p.id = s.plot_id "
                + "where p.tenant_id = ? and s.debt <> 0", Integer.class, DEMO_TENANT)).isZero();
        assertThat(owner.queryForObject("select count(*) from cooperative_settings where tenant_id = ?",
                Integer.class, DEMO_TENANT)).isZero();
    }

    // ---- payments and allocations (spec 015)

    /** Every demo payment is placed on the open charges of its plot as {@code AllocationPlanner} proposes. */
    @Test
    void theDemoAllocationsAreWhatThePlannerProposesForEachPayment() {
        List<Map<String, Object>> payments = owner.queryForList("select id, plot_id, amount from payment "
                + "where tenant_id = ? and kind = 'PAYMENT' order by created_at, id", DEMO_TENANT);
        assertThat(payments).hasSizeGreaterThanOrEqualTo(10);
        Set<UUID> plotsSeen = new HashSet<>();
        inDemo(() -> {
            Map<UUID, Integer> positions = OpenAmounts.positions(chargeTypes.all());
            for (Map<String, Object> payment : payments) {
                UUID plotId = (UUID) payment.get("plot_id");
                // the demo enters one payment per plot, so the plot has no allocation before it
                assertThat(plotsSeen.add(plotId)).as("one payment per plot").isTrue();
                PlotLedgerQueries.PlotLedger ledger = ledgers.load(List.of(plotId)).get(plotId);
                PlotLedgerState before = OpenAmounts.of(ledger.charges(), List.of(), List.of(), positions);
                AllocationPlanner.Proposal proposal = AllocationPlanner.propose(
                        Money.of((BigDecimal) payment.get("amount")), before.charges());

                Map<UUID, BigDecimal> stored = new LinkedHashMap<>();
                owner.queryForList("select a.charge_id, a.amount from allocation a join charge c on c.id = a.charge_id "
                        + "where a.payment_id = ? and a.amount > 0 order by c.accrued_on, c.created_at, c.id::text "
                        + "collate \"C\"", payment.get("id")).forEach(row ->
                        stored.put((UUID) row.get("charge_id"), (BigDecimal) row.get("amount")));
                Map<UUID, BigDecimal> planned = new LinkedHashMap<>();
                proposal.lines().forEach(line -> planned.put(line.chargeId(), line.amount().value()));
                assertThat(stored.keySet()).as("charges of the payment on plot " + plotId).isEqualTo(planned.keySet());
                planned.forEach((charge, amount) -> assertThat(stored.get(charge)).isEqualByComparingTo(amount));
            }
            return null;
        });
        // the planner order itself: an earlier charge is filled before a later one gets money
        assertThat(owner.queryForObject("select count(*) from allocation a join charge c on c.id = a.charge_id "
                + "where a.tenant_id = ? and a.amount > 0 and exists (select 1 from charge e where e.plot_id = c.plot_id "
                + "and e.kind in ('REGULAR', 'OPENING') and e.accrued_on < c.accrued_on and e.amount > 0 "
                + "and not exists (select 1 from charge r where r.reverses_charge_id = e.id) "
                + "and not exists (select 1 from allocation b where b.charge_id = e.id and b.payment_id = a.payment_id))",
                Integer.class, DEMO_TENANT)).as("a later charge got money while an older one got none").isZero();
    }

    @Test
    void theDemoPaymentsAndChargesKeepTheirCaps() {
        assertThat(owner.queryForObject("select count(*) from payment p where p.tenant_id = ? and p.kind = 'PAYMENT' "
                + "and (select coalesce(sum(a.amount), 0) from allocation a where a.payment_id = p.id) > p.amount",
                Integer.class, DEMO_TENANT)).isZero();
        assertThat(owner.queryForObject("select count(*) from charge c where c.tenant_id = ? "
                + "and c.kind in ('REGULAR', 'OPENING') "
                + "and (select coalesce(sum(a.amount), 0) from allocation a where a.charge_id = c.id) "
                + "> case when exists (select 1 from charge r where r.reverses_charge_id = c.id) then 0 "
                + "else c.amount + coalesce((select sum(j.amount) from charge j where j.adjusts_charge_id = c.id), 0) end",
                Integer.class, DEMO_TENANT)).isZero();
        assertThat(owner.queryForObject("select count(*) from payment where tenant_id = ? "
                + "and received_on > (now() at time zone 'Europe/Kyiv')::date", Integer.class, DEMO_TENANT)).isZero();
        // every row is booked in the open month (a payment received in the closed month counts in the earliest open one)
        assertThat(owner.queryForObject("select count(*) from payment p join billing_month b on b.id = p.billing_month_id "
                + "where p.tenant_id = ? and b.status <> 'OPEN'", Integer.class, DEMO_TENANT)).isZero();
        assertThat(owner.queryForObject("select count(*) from payment p join billing_month b on b.id = p.billing_month_id "
                + "where p.tenant_id = ? and p.received_on < b.month", Integer.class, DEMO_TENANT)).isPositive();
        assertThat(owner.queryForObject("select count(*) from payment where tenant_id = ? and payer_note_enc is not null",
                Integer.class, DEMO_TENANT)).isZero();
        assertThat(owner.queryForObject("select last_number from receipt_counter where tenant_id = ?", Long.class,
                DEMO_TENANT)).isEqualTo(owner.queryForObject("select max(receipt_number) from payment "
                + "where tenant_id = ?", Long.class, DEMO_TENANT));
    }

    @Test
    void thePlotStatusCacheIsWhatARebuildFromTheLedgerGives() {
        List<UUID> plotIds = owner.queryForList("select id from plot where tenant_id = ?", UUID.class, DEMO_TENANT);
        inDemo(() -> {
            Map<UUID, PlotLedgerQueries.PlotLedger> all = ledgers.load(plotIds);
            for (UUID plotId : plotIds) {
                PlotLedgerQueries.PlotLedger ledger = all.get(plotId);
                DebtCalculator.Debts debts = DebtCalculator.of(ledger.charges(), ledger.allocations(),
                        ledger.payments());
                Map<String, Object> cached = owner.queryForMap("select debt, oldest_debt_date, prepayment "
                        + "from plot_status where plot_id = ?", plotId);
                assertThat((BigDecimal) cached.get("debt")).as("debt of " + plotId)
                        .isEqualByComparingTo(debts.total().value());
                assertThat((BigDecimal) cached.get("prepayment")).as("prepayment of " + plotId)
                        .isEqualByComparingTo(debts.prepayment().value());
                assertThat(cached.get("oldest_debt_date") == null ? null
                        : ((java.sql.Date) cached.get("oldest_debt_date")).toLocalDate())
                        .as("oldest debt of " + plotId).isEqualTo(debts.oldestAccrual());
            }
            return null;
        });
    }

    @Test
    void theSplitPaymentSharesOneReceiptAndOneGroup() {
        List<Map<String, Object>> parts = owner.queryForList("select p.number, y.group_id, y.receipt_number, y.source, "
                + "y.payer_member_id, y.amount from payment y join plot p on p.id = y.plot_id "
                + "where y.tenant_id = ? and y.kind = 'PAYMENT' and p.number in ('7', '15') order by p.number::int",
                DEMO_TENANT);
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).get("group_id")).isEqualTo(parts.get(1).get("group_id"));
        assertThat(parts.get(0).get("receipt_number")).isNotNull().isEqualTo(parts.get(1).get("receipt_number"));
        assertThat(parts).allSatisfy(part -> {
            assertThat(part.get("source")).isEqualTo("CASH");
            assertThat(part.get("payer_member_id")).isEqualTo(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003"));
        });
        // no other payment is in that group or has that receipt
        assertThat(owner.queryForObject("select count(*) from payment where tenant_id = ? and group_id = ?::uuid",
                Integer.class, DEMO_TENANT, parts.get(0).get("group_id").toString())).isEqualTo(2);
        // cash and bank: bank payments carry a payment order reference, cash ones a receipt number
        assertThat(owner.queryForObject("select count(*) from payment where tenant_id = ? and source = 'BANK' "
                + "and bank_reference like 'ПД-2026-%'", Integer.class, DEMO_TENANT)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void theReversedPaymentHasNoMoneyLeftOnAnyCharge() {
        Map<String, Object> reversal = owner.queryForMap("select r.id, r.reverses_payment_id, r.plot_id, r.amount, "
                + "r.reason, r.receipt_number, r.group_id from payment r where r.tenant_id = ? "
                + "and r.kind = 'PAYMENT_REVERSAL'", DEMO_TENANT);
        assertThat((String) reversal.get("reason")).isNotBlank();
        assertThat(reversal.get("receipt_number")).isNull();
        UUID payment = (UUID) reversal.get("reverses_payment_id");
        assertThat(owner.queryForObject("select count(*) from allocation where payment_id = ? and amount > 0",
                Integer.class, payment)).isPositive();
        assertThat(owner.queryForObject("select coalesce(sum(amount), 0) from allocation where payment_id = ?",
                BigDecimal.class, payment)).isEqualByComparingTo("0");
        assertThat(owner.queryForObject("select count(*) from allocation where payment_id = ? and amount < 0 "
                + "and release_cause = 'PAYMENT_REVERSED'", Integer.class, payment))
                .isEqualTo(owner.queryForObject("select count(*) from allocation where payment_id = ? and amount > 0",
                Integer.class, payment));
        // the plot owes what it did before: the payment is gone from its prepayment and from its allocations
        assertThat(owner.queryForObject("select s.prepayment from plot_status s where s.plot_id = ?", BigDecimal.class,
                reversal.get("plot_id"))).isEqualByComparingTo("0");
        assertThat(owner.queryForObject("select s.debt - (select coalesce(sum(c.amount), 0) from charge c "
                + "where c.plot_id = s.plot_id) from plot_status s where s.plot_id = ?", BigDecimal.class,
                reversal.get("plot_id"))).isEqualByComparingTo("0");
    }

    /**
     * A plot holds either a prepayment or open charges, never both: otherwise the first booking (or the republished
     * {@link ChargesBooked}) would place the prepayment automatically and the demo picture would move.
     */
    @Test
    void noPlotHasAPrepaymentAndOpenChargesAtTheSameTime() {
        List<UUID> plotIds = owner.queryForList("select id from plot where tenant_id = ?", UUID.class, DEMO_TENANT);
        List<UUID> withPrepayment = new ArrayList<>();
        inDemo(() -> {
            ledgers.load(plotIds).forEach((plotId, ledger) -> {
                PlotLedgerState state = ledger.state();
                boolean open = state.charges().stream().anyMatch(c -> c.allocatable() && c.open().signum() > 0);
                if (state.prepayment().signum() > 0) {
                    withPrepayment.add(plotId);
                    assertThat(open).as("plot " + plotId + " has a prepayment and an open charge").isFalse();
                }
            });
            return null;
        });
        assertThat(withPrepayment).as("the plot with a prepayment").hasSize(1);
    }

    @Test
    void thePaymentsLeaveAVariedMap() {
        assertThat(owner.queryForObject("select count(*) from payment y join plot p on p.id = y.plot_id "
                + "where y.tenant_id = ? and p.number = '1'", Integer.class, DEMO_TENANT)).isZero();
        Map<String, String> statuses = expectedStatuses(false);
        long red = statuses.values().stream().filter("RED"::equals).count();
        long yellow = statuses.values().stream().filter("YELLOW"::equals).count();
        long green = statuses.values().stream().filter("GREEN"::equals).count();
        assertThat(red).isGreaterThanOrEqualTo(8);
        assertThat(yellow).isGreaterThanOrEqualTo(4);
        assertThat(green).isGreaterThanOrEqualTo(4);
        assertThat(statuses).containsEntry("1", "RED");
        assertThat(owner.queryForObject("select count(distinct plot_id) from payment where tenant_id = ?",
                Integer.class, DEMO_TENANT)).isBetween(11, 14);
    }

    // ---- helpers

    /** A part of a charge as the calculators give it. */
    private record ChargeCalculatorPart(UUID versionId, long days, BigDecimal unitsNum, BigDecimal unitsDen,
            BigDecimal rate) {

        void assertMatches(Map<String, Object> row) {
            assertThat(row.get("tariff_version_id")).isEqualTo(versionId);
            assertThat(((Number) row.get("days")).longValue()).isEqualTo(days);
            assertThat((BigDecimal) row.get("units_num")).isEqualByComparingTo(unitsNum);
            assertThat((BigDecimal) row.get("units_den")).isEqualByComparingTo(unitsDen);
            assertThat((BigDecimal) row.get("rate")).isEqualByComparingTo(rate);
        }
    }

    private void assertPricedParts(UUID chargeId, List<UUID> versionIds, List<PricedPart> expected,
            List<UUID> nodes) {
        List<Map<String, Object>> parts = parts(chargeId);
        assertThat(parts).hasSize(expected.size());
        for (int i = 0; i < parts.size(); i++) {
            PricedPart part = expected.get(i);
            new ChargeCalculatorPart(versionIds.get(i), part.days(), new BigDecimal(part.quantity().numerator()),
                    new BigDecimal(part.quantity().denominator()), part.terms().rate().value())
                    .assertMatches(parts.get(i));
            assertThat(parts.get(i).get("node_meter_id")).isEqualTo(nodes == null ? null : nodes.get(i));
        }
    }

    private static void assertAmount(Map<String, Object> row, BigDecimal expected) {
        assertThat(row).as("a stored charge").isNotNull();
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo(expected);
        assertThat(expected.signum()).isPositive();
    }

    private void assertCalculation(Map<String, Object> row, Map<String, Object> expected) {
        assertThat(json((String) row.get("calculation"))).isEqualTo(json(expected));
    }

    /** The charges of the demo cooperative matching the condition, by source key. */
    private Map<String, Map<String, Object>> charges(String condition) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> row : owner.queryForList("select c.id, c.source_key, c.amount, c.service_from, "
                + "c.service_to, c.accrued_on, c.meter_id, c.calculation::text as calculation, "
                + "to_char(b.month, 'YYYY-MM') as month from charge c "
                + "join billing_month b on b.id = c.billing_month_id where c.tenant_id = ? and " + condition,
                DEMO_TENANT)) {
            assertThat(result.put((String) row.get("source_key"), row)).isNull();
        }
        return result;
    }

    private List<Map<String, Object>> parts(UUID chargeId) {
        return owner.queryForList("select tariff_version_id, days, units_num, units_den, rate, node_meter_id "
                + "from charge_part where charge_id = ? order by days desc, tariff_version_id", chargeId);
    }

    private List<ValueUse> valueUses(UUID runId) {
        return owner.query("select meter_id, used_on from meter_value_use where run_id = ?",
                (rs, i) -> new ValueUse(rs.getObject("meter_id", UUID.class), rs.getObject("used_on", LocalDate.class)),
                runId);
    }

    /** The split of a common consumer: its own, else the cooperative's default (one meter per demo consumer). */
    private CommonConsumerSplit splitOf(NetworkChain chain) {
        List<String> own = owner.queryForList("select split from common_consumer_billing where consumer_meter_id = ?",
                String.class, chain.key());
        return own.isEmpty() ? settings.commonConsumerDefaultSplit() : CommonConsumerSplit.valueOf(own.getFirst());
    }

    /** The plots that may share a consumer with their days, as the common consumer run finds them. */
    private Map<UUID, DaySet> eligible(NetworkChain chain, CommonConsumerSplit split, List<PlotFacts> chargeable,
            LocalDate from, LocalDate to) {
        Map<UUID, DaySet> eligible = new LinkedHashMap<>();
        if (split == CommonConsumerSplit.EQUAL_PER_PLOT) {
            chargeable.forEach(plot -> eligible.put(plot.id(), DaySet.of(from, to)));
        } else if (split == CommonConsumerSplit.CONNECTED_PLOTS) {
            Map<UUID, List<DaySet.Range>> connected = new HashMap<>();
            for (UUID meter : chain.meterIds()) {
                water.connectedToSource(meter, from, to).forEach((plot, periods) -> periods.forEach(period ->
                        connected.computeIfAbsent(plot, k -> new ArrayList<>())
                                .add(new DaySet.Range(period.from(), period.to()))));
            }
            chargeable.stream().filter(plot -> connected.containsKey(plot.id()))
                    .forEach(plot -> eligible.put(plot.id(), DaySet.of(connected.get(plot.id()))));
        }
        return eligible;
    }

    private static UUID typeId(Snapshot snapshot, String code) {
        return snapshot.chargeTypes().stream().filter(type -> code.equals(type.code())).map(ChargeTypeEntry::id)
                .findFirst().orElseThrow();
    }

    private static BigDecimal meterValue(Quantity quantity) {
        return quantity == null ? null : quantity.toMeterValue().value();
    }

    private JsonNode account(String number) throws Exception {
        UUID plot = owner.queryForObject("select id from plot where tenant_id = ? and number = ?", UUID.class,
                DEMO_TENANT, number);
        return body(mvc.perform(get("/api/v1/plots/" + plot + "/account").header(HttpHeaders.AUTHORIZATION,
                chairman())).andExpect(status().isOk()));
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private JsonNode json(String text) {
        return text == null ? json.nullNode() : json.readTree(text);
    }

    /** A value as JSON the way the database returns it (numbers parsed back, so int and long compare equal). */
    private JsonNode json(Object value) {
        return value == null ? json.nullNode() : json.readTree(json.writeValueAsString(value));
    }

    private static List<JsonNode> sortedByPlot(JsonNode array) {
        List<JsonNode> list = new ArrayList<>(array.valueStream().toList());
        list.sort(Comparator.comparing(node -> node.get("plotId").asString()));
        return list;
    }

    private <T> T inDemo(Supplier<T> work) {
        return TenantContext.runAs(DEMO_TENANT, () -> tx.execute(status -> work.get()));
    }

    private static String chairman() {
        return TestTokens.bearer(DEMO_TENANT, "sub-chairman@demo.local", "chairman@demo.local");
    }

    /** A first sign-in links the demo members to a subject; other tests expect them unlinked. */
    private void unlinkDemoMembers() {
        unlinkByEmail(DEMO_TENANT, "chairman@demo.local", "treasurer@demo.local", "member@demo.local",
                "electrician@demo.local");
    }

    /** Waits until the asynchronous plot status listener has handled every {@link ChargesBooked}. */
    private void awaitListeners() {
        await().atMost(Duration.ofSeconds(15)).until(() -> owner.queryForObject("select count(*) from "
                + "event_publication where completion_date is null and event_type like '%ChargesBooked'",
                Integer.class) == 0);
    }
}
