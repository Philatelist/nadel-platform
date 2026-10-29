package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.documents.internal.InvoiceBuildHook;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * One month build reads every figure from one database snapshot (owner decision 28.10.2026, technical considerations
 * 2.5, ADR 0017 "One consistent read per build"): a payment and a charge committed by other transactions while the
 * build is between two chunks are in none of that build's invoices, neither on a plot of the chunk already written nor
 * on a plot of the chunk still to come; the next build picks them up.
 * <p>
 * The rows committed meanwhile carry a {@code created_at} before the build began, as the rows of a transaction that
 * began before the build and committed during it do ({@code now()} is the start of a transaction): the {@code asOf}
 * filter alone would let them in, so only the snapshot keeps them out.
 */
class InvoiceBuildConsistencyTests extends DocumentsTestSupport {

    /** More than one chunk of 50 plots. */
    private static final int PLOTS = 60;

    @Autowired
    ApplicationContext context;

    @AfterEach
    void removeHook() {
        InvoiceBuildHook.afterChunk(context, null);
    }

    @Test
    void aPaymentAndAChargeCommittedBetweenTwoChunksAreInNoneOfTheBuildsInvoices() throws Exception {
        String month = currentMonth();
        Map<String, UUID> plots = new HashMap<>();
        Map<String, UUID> charges = new HashMap<>();
        for (int i = 1; i <= PLOTS; i++) {
            UUID plot = plot(Integer.toString(i), "Садова");
            own(memberId, plot);
            plots.put(Integer.toString(i), plot);
            charges.put(Integer.toString(i), charge(plot, "MEMBERSHIP", "100.00", month));
        }
        AtomicInteger hooked = new AtomicInteger();
        InvoiceBuildHook.afterChunk(context, chunk -> {
            if (chunk != 0 || hooked.getAndIncrement() > 0) {
                return;
            }
            // plots 1 and 2 are in the chunk just written, 55 and 56 in the one still to be read
            paid(plots.get("1"), charges.get("1"), month);
            paid(plots.get("55"), charges.get("55"), month);
            charged(plots.get("2"), month);
            charged(plots.get("56"), month);
        });

        JsonNode first = built(month, false);

        assertThat(hooked.get()).isEqualTo(1);
        assertThat(first.get("built").asInt()).isEqualTo(PLOTS);
        for (String number : new String[] {"1", "2", "55", "56"}) {
            assertThat(due(plots.get(number), 1)).as("plot %s", number).isEqualTo("100.00");
        }
        assertThat(owner().queryForObject("select count(*) from invoice where tenant_id = ? and due <> 100.00",
                Integer.class, tenant)).isZero();

        InvoiceBuildHook.afterChunk(context, null);
        JsonNode next = built(month, false);

        assertThat(next.get("newVersions").asInt()).isEqualTo(4);
        assertThat(next.get("unchanged").asInt()).isEqualTo(PLOTS - 4);
        assertThat(due(plots.get("1"), 2)).isEqualTo("0.00");
        assertThat(due(plots.get("55"), 2)).isEqualTo("0.00");
        assertThat(due(plots.get("2"), 2)).isEqualTo("150.00");
        assertThat(due(plots.get("56"), 2)).isEqualTo("150.00");
    }

    private String due(UUID plot, int version) {
        return owner().queryForObject("select due::text from invoice where plot_id = ? and version = ?", String.class,
                plot, version);
    }

    private UUID monthId(String month) {
        return owner().queryForObject("select id from billing_month where tenant_id = ? and month = ?::date",
                UUID.class, tenant, month + "-01");
    }

    /**
     * A bank payment of the plot's charge, allocated to it, committed now with the {@code created_at} of a transaction
     * that began a minute ago.
     */
    private void paid(UUID plot, UUID charge, String month) {
        UUID payment = UUID.randomUUID();
        // each statement commits on its own (autocommit), before the build reads its next chunk
        owner().update("insert into payment (id, tenant_id, plot_id, kind, source, amount, received_on, "
                + "billing_month_id, group_id, created_at) values (?, ?, ?, 'PAYMENT', 'BANK', ?, ?, ?, ?, "
                + "now() - interval '1 minute')", payment, tenant, plot, new BigDecimal("100.00"), today(),
                monthId(month), payment);
        owner().update("insert into allocation (tenant_id, payment_id, charge_id, plot_id, amount, origin, "
                + "created_at) values (?, ?, ?, ?, ?, 'ENTRY', now() - interval '1 minute')", tenant, payment,
                charge, plot, new BigDecimal("100.00"));
    }

    /** An electricity charge of 50.00 in the month, committed now with a {@code created_at} a minute ago. */
    private void charged(UUID plot, String month) {
        UUID id = UUID.randomUUID();
        LocalDate first = LocalDate.parse(month + "-01");
        owner().update("insert into charge (id, tenant_id, plot_id, charge_type_id, "
                + "billing_month_id, kind, source, amount, service_from, service_to, accrued_on, source_key, "
                + "created_at) values (?, ?, ?, ?, ?, 'REGULAR', 'TARIFF', ?, ?, ?, ?, ?, now() - interval '1 minute')",
                id, tenant, plot, typeId("ELECTRICITY"), monthId(month), new BigDecimal("50.00"), first,
                first.withDayOfMonth(first.lengthOfMonth()), first, "TEST:" + id);
    }
}
