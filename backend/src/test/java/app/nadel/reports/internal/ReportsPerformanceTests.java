package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManagerFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Every report on a realistic cooperative (functional spec 2.2: 300 plots with 12 months of charges are reported
 * within 5 seconds). The fixture of a throw-away cooperative is written with a few set-based statements:
 * <pre>
 * 300 plots on 10 streets, 360 owners (60 plots with two), phones and e-mails; 12 billing months of 2025 (11 closed)
 * 12 x 300 membership, electricity and land tax charges, 12 x 40 water charges (about 11 000 regular rows), a tenth
 * of them reversed and a tenth adjusted in the next month, electricity and water charges with a tariff part
 * electricity tree: 1 substation, 10 quarters, 5 common consumers, 300 plot meters, 13 monthly readings each, none after
 * June for a tenth of the plot meters; 40 water meters with connections (half on the well)
 * runs: two losses runs and two common consumer runs booked with their charges (1 200 rows), one losses run reversed
 * 50 open items of the not-charged queue
 * </pre>
 * Each report is run through MockMvc as the chairman, as a preview and as a file, after one warm-up call; the wall time
 * of each call is logged and must stay under the 5 seconds of the spec (the measured numbers are in
 * {@code reports/README.md}). The statements of the plot balances and debtors previews are counted through Hibernate
 * statistics, to catch a per-plot query (N+1) the day somebody adds one.
 */
class ReportsPerformanceTests extends ReportsTestSupport {

    private static final Logger LOG = LoggerFactory.getLogger(ReportsPerformanceTests.class);
    private static final long LIMIT_MILLIS = 5_000;
    private static final String API = "/api/v1/reports/";
    private static final String MONTHS = "fromMonth=2025-01&toMonth=2025-12";
    private static final String DAYS = "from=2025-01-01&to=2025-12-31";

    /** The reports with their filters: charges-by-month .. corrections. */
    private static final Map<String, String> REPORTS = new LinkedHashMap<>();

    static {
        REPORTS.put("charges-by-month", MONTHS);
        REPORTS.put("plot-balances", "");
        REPORTS.put("debtors", "");
        REPORTS.put("electricity-tree", DAYS);
        REPORTS.put("water", DAYS);
        // readings are taken on the 1st: a range without it lists every meter, the worst case for this report
        REPORTS.put("missing-readings", "from=2025-09-02&to=2025-09-30&meterKinds=PLOT&meterKinds=QUARTER"
                + "&meterKinds=SUBSTATION&meterKinds=COMMON_CONSUMER");
        REPORTS.put("land-tax", "year=2025");
        REPORTS.put("corrections", MONTHS);
    }

    @Autowired
    EntityManagerFactory entityManagerFactory;

    private String t;

    /** The contacts of the 360 members, sealed as the application seals them (the names carry the number). */
    private void sealPerformanceContacts() {
        for (Map<String, Object> row : ownerJdbc().queryForList(
                "select id, last_name from member where tenant_id = ?", tenant)) {
            UUID id = (UUID) row.get("id");
            String name = (String) row.get("last_name");
            if (name.startsWith("Співвласник")) {
                String i = String.format("%07d", Integer.parseInt(name.substring("Співвласник".length())));
                insertContacts(tenant, id, "co-owner" + Integer.parseInt(i) + "@perf.test", "+38067" + i, "+38093" + i);
            } else if (name.startsWith("Власник")) {
                String i = String.format("%07d", Integer.parseInt(name.substring("Власник".length())));
                insertContacts(tenant, id, "owner" + Integer.parseInt(i) + "@perf.test", "+38050" + i);
            }
        }
    }

    @BeforeEach
    void bookThePerformanceFixture() {
        long started = System.nanoTime();
        t = tenant.toString();
        UUID membershipType = typeId("MEMBERSHIP");
        UUID electricityType = typeId("ELECTRICITY");
        UUID landTaxType = typeId("LAND_TAX");
        UUID waterType = typeId("WATER");
        UUID commonType = typeId("COMMON_CONSUMERS");
        UUID electricityTariff = perKwh(tariff(electricityType, "Електроенергія"), "4.3200", "2024-01-01");
        UUID waterTariff = perM3(tariff(waterType, "Вода"), "21.5000", "2024-01-01");
        perSotkaYearly(tariff(landTaxType, "Земельний податок"), "120.0000", "2025-01-01");
        ownerJdbc().update("insert into cooperative_settings (tenant_id, losses_mode, losses_percent) "
                + "values (?, 'FIXED_PERCENT', 5.00) on conflict (tenant_id) do nothing", tenant);

        run("""
                insert into plot (id, tenant_id, number, street, kind, area_m2)
                select md5('#S#P' || i)::uuid, #T#, i::text, 'Вулиця ' || (i % 10), 'PLOT', 600 + i
                from generate_series(1, 300) i""");
        run("""
                insert into plot_status (plot_id, tenant_id, status, debt, oldest_debt_date)
                select md5('#S#P' || i)::uuid, #T#,
                       case i % 4 when 0 then 'RED' when 1 then 'YELLOW' when 2 then 'GREEN' else 'GREY' end,
                       case when i % 4 = 2 then 0 else ((i % 7) + 1) * 150 end,
                       case when i % 4 = 2 then null else current_date - (i % 200) end
                from generate_series(1, 300) i""");
        run("""
                insert into member (id, tenant_id, last_name, first_name)
                select md5('#S#M' || i)::uuid, #T#, 'Власник' || i, 'Іван'
                from generate_series(1, 300) i""");
        run("""
                insert into member (id, tenant_id, last_name, first_name)
                select md5('#S#N' || i)::uuid, #T#, 'Співвласник' || i, 'Марія'
                from generate_series(1, 60) i""");
        sealPerformanceContacts();
        run("""
                insert into ownership (tenant_id, plot_id, member_id, share_numerator, share_denominator, valid_from)
                select #T#, md5('#S#P' || i)::uuid, md5('#S#M' || i)::uuid, 1, case when i <= 60 then 2 else 1 end,
                       date '2020-01-01'
                from generate_series(1, 300) i""");
        run("""
                insert into ownership (tenant_id, plot_id, member_id, share_numerator, share_denominator, valid_from)
                select #T#, md5('#S#P' || i)::uuid, md5('#S#N' || i)::uuid, 1, 2, date '2020-01-01'
                from generate_series(1, 60) i""");

        run("""
                insert into billing_month (tenant_id, month)
                select #T#, d::date from generate_series(date '2025-01-01', date '2025-12-01', interval '1 month') d""");
        run("update billing_month set status = 'CLOSED', closed_at = now() where tenant_id = #T# "
                + "and month < date '2025-12-01'");

        bookTheMeters();
        bookTheCharges(membershipType, electricityType, landTaxType, waterType, commonType, electricityTariff,
                waterTariff);
        bookTheRuns(electricityType, commonType);

        run("""
                insert into not_charged_item (tenant_id, item_key, kind, plot_id, charge_type_id, month, reason)
                select #T#, 'PERF:' || i, 'TARIFF_MONTH', md5('#S#P' || i)::uuid, #MEM#, date '2025-07-01',
                       'NO_TARIFF_FOR_ALL_DAYS'
                from generate_series(1, 50) i""".replace("#MEM#", "'" + membershipType + "'::uuid"));

        ownerJdbc().execute("analyze");
        // the fixture is the size the spec names, not an empty shell
        assertThat(count("plot")).isEqualTo(300);
        assertThat(count("charge")).isGreaterThan(14_000);
        assertThat(count("charge where kind = 'REVERSAL'")).isGreaterThan(1_000);
        assertThat(count("charge where kind = 'ADJUSTMENT'")).isGreaterThan(1_000);
        assertThat(count("meter_reading")).isGreaterThan(4_000);
        LOG.info("PERF fixture written in {} ms", (System.nanoTime() - started) / 1_000_000);
    }

    @Test
    void everyReportPreviewAndFileRunsUnderFiveSeconds() throws Exception {
        for (Map.Entry<String, String> report : REPORTS.entrySet()) {
            String code = report.getKey();
            String filters = report.getValue();
            // one warm-up call of each kind: class loading, connection, plan cache
            JsonNode warm = body(send(chairman, request(API + code, filters)).andExpect(status().isOk()));
            send(chairman, request(API + code + ".xlsx", filters)).andExpect(status().isOk());

            long previewStart = System.nanoTime();
            JsonNode preview = body(send(chairman, request(API + code, filters)).andExpect(status().isOk()));
            long previewMillis = (System.nanoTime() - previewStart) / 1_000_000;

            long fileStart = System.nanoTime();
            byte[] file = send(chairman, request(API + code + ".xlsx", filters)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            long fileMillis = (System.nanoTime() - fileStart) / 1_000_000;

            int rows = 0;
            for (JsonNode sheet : preview.get("sheets")) {
                rows += sheet.get("rowCount").asInt();
            }
            LOG.info("PERF {} rows={} preview={} ms file={} ms ({} bytes)", code, rows, previewMillis, fileMillis,
                    file.length);
            assertThat(warm.get("sheets")).isNotEmpty();
            assertThat(rows).as("%s has rows to report", code).isPositive();
            assertThat(previewMillis).as("%s preview ms", code).isLessThan(LIMIT_MILLIS);
            assertThat(fileMillis).as("%s file ms", code).isLessThan(LIMIT_MILLIS);
        }
    }

    @Test
    void thePlotBalancesAndDebtorsPreviewsRunAFixedNumberOfStatementsWhateverThePlotCount() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            for (String code : new String[] {"plot-balances", "debtors"}) {
                send(chairman, request(API + code, "")).andExpect(status().isOk());
                statistics.clear();
                send(chairman, request(API + code, "")).andExpect(status().isOk());
                long statements = statistics.getPrepareStatementCount();
                LOG.info("PERF {} statements={}", code, statements);
                // 300 plots: a per-plot query would be 300 or more
                assertThat(statements).as("%s statements for 300 plots", code).isLessThan(STATEMENT_BOUND);
            }
        } finally {
            statistics.setStatisticsEnabled(enabled);
        }
    }

    /** About twice what a preview runs today; far under one statement per plot. */
    private static final long STATEMENT_BOUND = 40;

    private MockHttpServletRequestBuilder request(String path, String filters) {
        return get(path + (filters.isEmpty() ? "" : "?" + filters));
    }

    // ---- the fixture

    private long count(String tableAndFilter) {
        return ownerJdbc().queryForObject("select count(*) from " + tableAndFilter
                + (tableAndFilter.contains(" where ") ? " and" : " where") + " tenant_id = ?", Long.class, tenant);
    }

    private void run(String sql) {
        ownerJdbc().update(sql.replace("#S#", t).replace("#T#", "'" + t + "'::uuid"));
    }

    private UUID perKwh(UUID tariff, String rate, String validFrom) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into tariff_version (id, tenant_id, tariff_id, valid_from, basis, frequency, rate) "
                + "values (?, ?, ?, ?::date, 'PER_KWH', 'MONTHLY', ?)", id, tenant, tariff, validFrom,
                new java.math.BigDecimal(rate));
        return id;
    }

    private UUID perM3(UUID tariff, String rate, String validFrom) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into tariff_version (id, tenant_id, tariff_id, valid_from, basis, frequency, rate) "
                + "values (?, ?, ?, ?::date, 'PER_M3', 'MONTHLY', ?)", id, tenant, tariff, validFrom,
                new java.math.BigDecimal(rate));
        return id;
    }

    /** The electricity tree, the water meters and connections and their monthly readings. */
    private void bookTheMeters() {
        run("""
                insert into meter (id, tenant_id, kind, resource, serial, name, installed_on, initial_value)
                values (md5('#S#SS')::uuid, #T#, 'SUBSTATION', 'ELECTRICITY', 'S-TP1', 'ТП-1', date '2024-01-01', 0),
                       (md5('#S#W')::uuid, #T#, 'COMMON_CONSUMER', 'ELECTRICITY', 'S-WELL', 'Свердловина',
                        date '2024-01-01', 0)""");
        run("""
                insert into meter (id, tenant_id, kind, resource, serial, name, installed_on, initial_value)
                select md5('#S#Q' || q)::uuid, #T#, 'QUARTER', 'ELECTRICITY', 'S-Q' || q, 'Квартал ' || q,
                       date '2024-01-01', 0
                from generate_series(1, 10) q""");
        run("""
                insert into meter (id, tenant_id, kind, resource, serial, name, installed_on, initial_value)
                select md5('#S#C' || c)::uuid, #T#, 'COMMON_CONSUMER', 'ELECTRICITY', 'S-C' || c, 'Споживач ' || c,
                       date '2024-01-01', 0
                from generate_series(1, 5) c""");
        run("""
                insert into meter (id, tenant_id, kind, resource, serial, plot_id, installed_on, initial_value)
                select md5('#S#PM' || i)::uuid, #T#, 'PLOT', 'ELECTRICITY', 'E-' || i, md5('#S#P' || i)::uuid,
                       date '2024-01-01', 0
                from generate_series(1, 300) i""");
        run("""
                insert into meter (id, tenant_id, kind, resource, serial, plot_id, installed_on, initial_value)
                select md5('#S#WM' || i)::uuid, #T#, 'PLOT', 'WATER', 'W-' || i, md5('#S#P' || i)::uuid,
                       date '2024-01-01', 0
                from generate_series(1, 40) i""");
        run("""
                insert into meter_parent (tenant_id, meter_id, parent_meter_id, valid_from)
                select #T#, md5('#S#Q' || q)::uuid, md5('#S#SS')::uuid, date '2024-01-01'
                from generate_series(1, 10) q
                union all
                select #T#, md5('#S#C' || c)::uuid, md5('#S#Q' || c)::uuid, date '2024-01-01'
                from generate_series(1, 5) c
                union all
                select #T#, md5('#S#PM' || i)::uuid, md5('#S#Q' || ((i % 10) + 1))::uuid, date '2024-01-01'
                from generate_series(1, 300) i""");
        // thirteen monthly readings (1 January 2025 .. 1 January 2026); a tenth of the plot meters stop after June
        run("""
                insert into meter_reading (tenant_id, meter_id, taken_on, value, source, status)
                select #T#, md5('#S#PM' || i)::uuid, (date '2025-01-01' + make_interval(months => m))::date,
                       m * 100 + i, 'ENTERED', 'APPROVED'
                from generate_series(1, 300) i, generate_series(0, 12) m
                where i % 10 <> 5 or m <= 5""");
        run("""
                insert into meter_reading (tenant_id, meter_id, taken_on, value, source, status)
                select #T#, md5('#S#WM' || i)::uuid, (date '2025-01-01' + make_interval(months => m))::date,
                       m * 10 + i, 'ENTERED', 'APPROVED'
                from generate_series(1, 40) i, generate_series(0, 12) m""");
        run("""
                insert into meter_reading (tenant_id, meter_id, taken_on, value, source, status)
                select #T#, md5('#S#Q' || q)::uuid, (date '2025-01-01' + make_interval(months => m))::date,
                       m * 3100, 'ENTERED', 'APPROVED'
                from generate_series(1, 10) q, generate_series(0, 12) m""");
        run("""
                insert into meter_reading (tenant_id, meter_id, taken_on, value, source, status)
                select #T#, md5('#S#C' || c)::uuid, (date '2025-01-01' + make_interval(months => m))::date,
                       m * 50, 'ENTERED', 'APPROVED'
                from generate_series(1, 5) c, generate_series(0, 12) m""");
        run("""
                insert into meter_reading (tenant_id, meter_id, taken_on, value, source, status)
                select #T#, md5('#S#SS')::uuid, (date '2025-01-01' + make_interval(months => m))::date,
                       m * 31500, 'ENTERED', 'APPROVED'
                from generate_series(0, 12) m""");
        run("""
                insert into plot_water_connection (tenant_id, plot_id, source_meter_id, valid_from)
                select #T#, md5('#S#P' || i)::uuid, case when i % 2 = 0 then md5('#S#W')::uuid end, date '2024-01-01'
                from generate_series(1, 40) i""");
    }

    /** About 11 000 regular charges with their reversals, adjustments and tariff parts. */
    private void bookTheCharges(UUID membershipType, UUID electricityType, UUID landTaxType, UUID waterType,
            UUID commonType, UUID electricityTariff, UUID waterTariff) {
        String charge = """
                insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, amount,
                                    service_from, service_to, accrued_on, source_key, meter_id, created_at)
                select gen_random_uuid(), #T#, p.id, '#TYPE#'::uuid, bm.id, 'REGULAR', '#SOURCE#', #AMOUNT#,
                       bm.month, (bm.month + interval '1 month - 1 day')::date, #ACCRUED#,
                       'F:' || gen_random_uuid(), #METER#, bm.month + interval '25 days'
                from plot p
                cross join billing_month bm
                #JOIN#
                where p.tenant_id = #T# and bm.tenant_id = #T# #WHERE#""";
        run(fill(charge, membershipType, "TARIFF", "30 + (p.number::int % 5)", "bm.month", "null", "", ""));
        run(fill(charge, electricityType, "CONSUMPTION", "100.50 + (p.number::int * 7 % 100)",
                "(bm.month + interval '1 month - 1 day')::date", "m.id",
                "join meter m on m.tenant_id = #T# and m.plot_id = p.id and m.resource = 'ELECTRICITY'", ""));
        run(fill(charge, landTaxType, "TARIFF", "125", "bm.month", "null", "", ""));
        run(fill(charge, waterType, "CONSUMPTION", "20", "(bm.month + interval '1 month - 1 day')::date", "m.id",
                "join meter m on m.tenant_id = #T# and m.plot_id = p.id and m.resource = 'WATER'", ""));

        run("""
                insert into charge_part (tenant_id, charge_id, tariff_version_id, days, units_num, units_den, rate)
                select #T#, c.id, case when c.charge_type_id = '#ELE#'::uuid then '#EV#'::uuid else '#WV#'::uuid end,
                       30, 100, 1, 4.3200
                from charge c
                where c.tenant_id = #T# and c.source = 'CONSUMPTION'"""
                .replace("#ELE#", electricityType.toString()).replace("#EV#", electricityTariff.toString())
                .replace("#WV#", waterTariff.toString()));

        UUID recalculation = UUID.randomUUID();
        ownerJdbc().update("insert into recalculation (id, tenant_id, cause, reason, total, line_count, plot_count, "
                + "digest) values (?, ?, 'MANUAL', 'Виправлено ставку', -100.00, 1000, 300, 'perf-digest')",
                recalculation, tenant);
        // a tenth of the tariff and consumption charges reversed in their own month, another tenth adjusted in the next
        run("""
                insert into charge (tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, amount,
                                    service_from, service_to, accrued_on, reverses_charge_id, reason, meter_id,
                                    created_at)
                select c.tenant_id, c.plot_id, c.charge_type_id, c.billing_month_id, 'REVERSAL', 'MANUAL_REVERSAL',
                       -c.amount, c.service_from, c.service_to, c.accrued_on, c.id, 'Помилкове нарахування',
                       c.meter_id, c.created_at + interval '1 day'
                from charge c
                join plot p on p.tenant_id = c.tenant_id and p.id = c.plot_id
                join billing_month bm on bm.tenant_id = c.tenant_id and bm.id = c.billing_month_id
                where c.tenant_id = #T# and c.kind = 'REGULAR'
                  and (p.number::int + extract(month from bm.month)::int) % 10 = 0""");
        run("""
                insert into charge (tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, amount,
                                    service_from, service_to, accrued_on, source_key, adjusts_charge_id,
                                    recalculation_id, reason, meter_id, created_at)
                select c.tenant_id, c.plot_id, c.charge_type_id, nb.id, 'ADJUSTMENT', 'RECALCULATION', -5.00,
                       c.service_from, c.service_to, c.accrued_on, 'ADJ:' || c.id, c.id, '#REC#'::uuid,
                       'Виправлено ставку', c.meter_id, nb.month + interval '10 days'
                from charge c
                join plot p on p.tenant_id = c.tenant_id and p.id = c.plot_id
                join billing_month bm on bm.tenant_id = c.tenant_id and bm.id = c.billing_month_id
                join billing_month nb on nb.tenant_id = c.tenant_id and nb.month = (bm.month + interval '1 month')::date
                where c.tenant_id = #T# and c.kind = 'REGULAR'
                  and (p.number::int + extract(month from bm.month)::int) % 10 = 1""".replace("#REC#",
                recalculation.toString()));
    }

    /** Two losses runs (June, December) and a reversed one (March); two common consumer runs, with their charges. */
    private void bookTheRuns(UUID electricityType, UUID commonType) {
        run("""
                insert into losses_run (id, tenant_id, from_date, to_date, status, billing_month_id, total, created_at,
                                        reversed_at, reverse_reason)
                select md5('#S#LR' || d)::uuid, #T#, d::date, (d + interval '1 month - 1 day')::date,
                       case when d = date '2025-03-01' then 'REVERSED' else 'BOOKED' end, bm.id, 975.00,
                       d + interval '28 days',
                       case when d = date '2025-03-01' then d + interval '29 days' end,
                       case when d = date '2025-03-01' then 'Помилковий розрахунок' end
                from (values (date '2025-03-01'), (date '2025-06-01'), (date '2025-12-01')) v(d)
                join billing_month bm on bm.tenant_id = #T# and bm.month = d""");
        run("""
                insert into losses_run_node (tenant_id, run_id, node_meter_id, node_kind, node_name, from_date, to_date,
                                             run_status, position, outcome, consumption, children, losses, pct,
                                             amount)
                select #T#, r.id, n.meter_id, n.kind, n.name, r.from_date, r.to_date, r.status, n.position, 'CHARGED',
                       3100, 3000, 100, 3.2, 90
                from losses_run r
                cross join (select md5('#S#SS')::uuid meter_id, 'SUBSTATION' kind, 'ТП-1' name, 0 position
                            union all
                            select md5('#S#Q' || q)::uuid, 'QUARTER', 'Квартал ' || q, q from generate_series(1, 10) q)
                     n
                where r.tenant_id = #T#""");
        run("""
                insert into common_consumer_run (id, tenant_id, from_date, to_date, status, billing_month_id, total,
                                                 created_at)
                select md5('#S#CR' || d)::uuid, #T#, d::date, (d + interval '1 month - 1 day')::date, 'BOOKED', bm.id,
                       1620.00, d + interval '28 days'
                from (values (date '2025-06-01'), (date '2025-12-01')) v(d)
                join billing_month bm on bm.tenant_id = #T# and bm.month = d""");
        run("""
                insert into common_consumer_run_line (tenant_id, run_id, consumer_meter_id, consumer_name, from_date,
                                                      to_date, position, split, outcome, consumption, plot_days,
                                                      amount, plots)
                select #T#, r.id, md5('#S#C' || c)::uuid, 'Споживач ' || c, r.from_date, r.to_date, c,
                       'EQUAL_PER_PLOT', 'CHARGED', 50, 9000, 324,
                       (select jsonb_agg(jsonb_build_object('plotId', md5('#S#P' || i)::uuid)) from generate_series(1, 300) i)
                from common_consumer_run r, generate_series(1, 5) c
                where r.tenant_id = #T#""");
        String runCharge = """
                insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, amount,
                                    service_from, service_to, accrued_on, source_key, meter_id, run_id, created_at)
                select gen_random_uuid(), #T#, p.id, '#TYPE#'::uuid, r.billing_month_id, 'REGULAR', '#SOURCE#', #AMOUNT#,
                       r.from_date, r.to_date, r.to_date, 'F:' || gen_random_uuid(), #METER#, r.id,
                       r.created_at
                from #RUN# r
                cross join plot p
                #JOIN#
                where r.tenant_id = #T# and p.tenant_id = #T# #WHERE#""";
        run(runCharge.replace("#TYPE#", electricityType.toString()).replace("#SOURCE#", "LOSSES")
                .replace("#AMOUNT#", "3.25").replace("#METER#", "m.id").replace("#RUN#", "losses_run")
                .replace("#JOIN#", "join meter m on m.tenant_id = #T# and m.plot_id = p.id and m.resource = 'ELECTRICITY'")
                .replace("#WHERE#", "and r.status = 'BOOKED'"));
        run(runCharge.replace("#TYPE#", commonType.toString()).replace("#SOURCE#", "COMMON_CONSUMER")
                .replace("#AMOUNT#", "5.40").replace("#METER#", "null").replace("#RUN#", "common_consumer_run")
                .replace("#JOIN#", "").replace("#WHERE#", ""));
    }

    private static String fill(String template, UUID type, String source, String amount, String accrued, String meter,
            String join, String where) {
        return template.replace("#TYPE#", type.toString()).replace("#SOURCE#", source).replace("#AMOUNT#", amount)
                .replace("#ACCRUED#", accrued).replace("#METER#", meter).replace("#JOIN#", join)
                .replace("#WHERE#", where);
    }
}
