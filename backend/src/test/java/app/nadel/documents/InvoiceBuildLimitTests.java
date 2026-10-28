package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The month build at its limit (technical considerations 2.10, ADR 0017 "Synchronous month build and its limit"): a
 * cooperative of 2,000 invoiceable plots (the size of the largest supported cooperative, as the Excel import), two
 * charge types charged in each of 12 months, half of the plots with a bank payment of the current month allocated to
 * its membership charge, a tenth of the plots shared by two owners. The first build, a rebuild without a change and an
 * on-request invoice are measured (printed with the marker {@code INVOICE_BUILD_MEASURE}) and checked against a loose
 * ceiling; one plot more is refused with 422 {@code INVOICE_BUILD_TOO_LARGE} and its {@code limit}, preview and build
 * alike, and nothing is written.
 */
class InvoiceBuildLimitTests extends DocumentsTestSupport {

    private static final int PLOTS = 2_000;
    private static final int OWNERS = 1_000;
    private static final int MONTHS = 12;

    /** Loose: a build at the limit must not come near it; the measured time goes into ADR 0017. */
    private static final Duration BUILD_CEILING = Duration.ofSeconds(60);
    private static final Duration ON_REQUEST_CEILING = Duration.ofSeconds(10);

    /** The fixture, written in bulk as the database owner (no event). */
    private void fixture(YearMonth current) {
        owner().update("insert into member (id, tenant_id, last_name, first_name, middle_name) "
                + "select gen_random_uuid(), ?, 'Власник', 'N' || g, 'Тестович' from generate_series(1, ?) g",
                tenant, OWNERS);
        owner().update("insert into plot (id, tenant_id, number, street, kind, area_m2) "
                + "select gen_random_uuid(), ?, g::text, 'Вулиця ' || (g % 20), 'PLOT', 600 "
                + "from generate_series(1, ?) g", tenant, PLOTS);
        owner().update("insert into plot_status (plot_id, tenant_id, status) "
                + "select id, tenant_id, 'GREY' from plot where tenant_id = ?", tenant);
        // plot n belongs to owner (n - 1) / 2 + 1; every tenth plot is shared half and half with the next owner
        owner().update("insert into ownership (id, tenant_id, plot_id, member_id, share_numerator, "
                + "share_denominator, valid_from) "
                + "select gen_random_uuid(), p.tenant_id, p.id, m.id, 1, "
                + "case when p.number::int % 10 = 0 then 2 else 1 end, date '2020-01-01' "
                + "from plot p join member m on m.tenant_id = p.tenant_id "
                + "and m.first_name = 'N' || ((p.number::int - 1) / 2 + 1) where p.tenant_id = ?", tenant);
        owner().update("insert into ownership (id, tenant_id, plot_id, member_id, share_numerator, "
                + "share_denominator, valid_from) "
                + "select gen_random_uuid(), p.tenant_id, p.id, m.id, 1, 2, date '2020-01-01' "
                + "from plot p join member m on m.tenant_id = p.tenant_id "
                + "and m.first_name = 'N' || (((p.number::int - 1) / 2 + 1) % ? + 1) "
                + "where p.tenant_id = ? and p.number::int % 10 = 0", OWNERS, tenant);
        owner().update("insert into billing_month (tenant_id, month) "
                + "select ?, (?::date - make_interval(months => g))::date from generate_series(0, ?) g",
                tenant, current.atDay(1).toString(), MONTHS - 1);
        owner().update("insert into charge (id, tenant_id, plot_id, charge_type_id, billing_month_id, kind, source, "
                + "amount, service_from, service_to, accrued_on, source_key) "
                + "select gen_random_uuid(), p.tenant_id, p.id, t.id, m.id, 'REGULAR', 'TARIFF', "
                + "case when t.code = 'MEMBERSHIP' then 75.00 else 120.50 end, m.month, "
                + "(m.month + interval '1 month' - interval '1 day')::date, m.month, 'LIMIT:' || gen_random_uuid() "
                + "from plot p join charge_type t on t.tenant_id = p.tenant_id "
                + "join billing_month m on m.tenant_id = p.tenant_id "
                + "where p.tenant_id = ? and t.code in ('MEMBERSHIP', 'ELECTRICITY')", tenant);
        // the even plots paid this month's membership charge by bank
        owner().update("insert into payment (id, tenant_id, plot_id, kind, source, amount, received_on, "
                + "billing_month_id, group_id) "
                + "select gen_random_uuid(), p.tenant_id, p.id, 'PAYMENT', 'BANK', 75.00, ?, m.id, gen_random_uuid() "
                + "from plot p join billing_month m on m.tenant_id = p.tenant_id and m.month = ?::date "
                + "where p.tenant_id = ? and p.number::int % 2 = 0", today(), current.atDay(1).toString(), tenant);
        owner().update("insert into allocation (tenant_id, payment_id, charge_id, plot_id, amount, origin) "
                + "select pay.tenant_id, pay.id, c.id, pay.plot_id, pay.amount, 'ENTRY' from payment pay "
                + "join charge c on c.plot_id = pay.plot_id and c.billing_month_id = pay.billing_month_id "
                + "join charge_type t on t.id = c.charge_type_id and t.code = 'MEMBERSHIP' "
                + "where pay.tenant_id = ?", tenant);
    }

    private static long millisSince(long start) {
        return Duration.ofNanos(System.nanoTime() - start).toMillis();
    }

    @Test
    void aCooperativeAtTheLimitIsBuiltWithinTheCeilingAndOnePlotMoreIsRefused() throws Exception {
        YearMonth current = YearMonth.parse(currentMonth());
        fixture(current);
        String month = current.toString();

        long firstStart = System.nanoTime();
        JsonNode first = built(month, false);
        long firstMillis = millisSince(firstStart);

        long rebuildStart = System.nanoTime();
        JsonNode rebuild = built(month, false);
        long rebuildMillis = millisSince(rebuildStart);

        UUID plot = owner().queryForObject("select id from plot where tenant_id = ? and number = '1000'", UUID.class,
                tenant);
        long onRequestStart = System.nanoTime();
        JsonNode onRequest = issued(plot, month);
        long onRequestMillis = millisSince(onRequestStart);

        System.out.printf("INVOICE_BUILD_MEASURE plots=%d months=%d firstBuildMs=%d rebuildMs=%d onRequestMs=%d%n",
                PLOTS, MONTHS, firstMillis, rebuildMillis, onRequestMillis);

        assertThat(first.get("invoiceable").asInt()).isEqualTo(PLOTS);
        assertThat(first.get("built").asInt()).isEqualTo(PLOTS);
        assertThat(rebuild.get("unchanged").asInt()).isEqualTo(PLOTS);
        assertThat(rebuild.get("newVersions").asInt()).isZero();
        assertThat(onRequest.get("version").asInt()).isEqualTo(2);
        assertThat(invoiceCount()).isEqualTo(PLOTS + 1);
        assertThat(Duration.ofMillis(firstMillis)).isLessThan(BUILD_CEILING);
        assertThat(Duration.ofMillis(rebuildMillis)).isLessThan(BUILD_CEILING);
        assertThat(Duration.ofMillis(onRequestMillis)).isLessThan(ON_REQUEST_CEILING);

        // an odd plot owes both charges of 12 months, an even one has paid this month's membership charge
        assertThat(owner().queryForObject("select due::text from invoice i join plot p on p.id = i.plot_id "
                + "where p.tenant_id = ? and p.number = '7'", String.class, tenant))
                .isEqualTo("2346.00"); // 12 × (75.00 + 120.50)
        assertThat(owner().queryForObject("select due::text from invoice i join plot p on p.id = i.plot_id "
                + "where p.tenant_id = ? and p.number = '8'", String.class, tenant)).isEqualTo("2271.00");

        UUID extra = plot(Integer.toString(PLOTS + 1), "Вулиця 1");
        own(memberId, extra);
        for (boolean preview : new boolean[] {true, false}) {
            buildMonth(treasurer, month, preview, null).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("INVOICE_BUILD_TOO_LARGE"))
                    .andExpect(jsonPath("$.limit").value(PLOTS));
        }
        assertThat(invoiceCount()).isEqualTo(PLOTS + 1);
    }
}
