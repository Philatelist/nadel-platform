package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.documents.internal.InvoiceBuildHook;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The month build of invoices ({@code POST /billing-months/{month}/invoices}, feature 016 §2.5, technical
 * considerations 2.5): the preview counts and writes nothing, and predicts exactly what the next build does (built,
 * new versions, unchanged, skipped); the build creates version 1 for every invoiceable plot
 * with something due and skips the others unless {@code includeNothingDue}; a rebuild without a change writes nothing;
 * a payment gives exactly that plot a version 2; a closed and an open month; the months the endpoint refuses; a second
 * build of the same month started while the first runs is refused with 409 {@code INVOICE_BUILD_RUNNING} and nothing
 * is built twice (the preview still answers); one
 * {@code INVOICES_ISSUED} audit entry per build; the month's list in the natural order of the plot numbers.
 */
class InvoiceMonthBuildTests extends DocumentsTestSupport {

    @Autowired
    ApplicationContext context;

    @AfterEach
    void removeHook() {
        InvoiceBuildHook.afterChunk(context, null);
    }

    /** {@code count} plots of the member on street Садова numbered from {@code first}, each charged when asked. */
    private List<UUID> ownedPlots(int first, int count, String month, String amount) {
        List<UUID> plots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID plot = plot(Integer.toString(first + i), "Садова");
            own(memberId, plot);
            if (amount != null) {
                charge(plot, "MEMBERSHIP", amount, month);
            }
            plots.add(plot);
        }
        return plots;
    }

    /** Twenty invoiceable plots, five of them with nothing due, and three plots that get no invoice. */
    private List<UUID> twentyPlots(String month) {
        List<UUID> plots = new ArrayList<>(ownedPlots(1, 15, month, "75.00"));
        plots.addAll(ownedPlots(16, 5, month, null));
        plot("90", "Садова"); // no owner
        UUID archived = ownedPlots(91, 1, month, null).getFirst();
        owner().update("update plot set archived_at = now() where id = ?", archived);
        UUID common = plot("92", "Садова");
        owner().update("update plot set kind = 'COMMON' where id = ?", common);
        return plots;
    }

    private static int count(JsonNode result, String field) {
        return result.get(field).asInt();
    }

    @Test
    void thePreviewCountsWhatTheBuildWouldDoAndWritesNothing() throws Exception {
        String month = currentMonth();
        twentyPlots(month);

        JsonNode preview = body(buildMonth(treasurer, month, true, null).andExpect(status().isOk()));
        assertThat(preview.get("preview").asBoolean()).isTrue();
        assertThat(count(preview, "invoiceable")).isEqualTo(20);
        assertThat(count(preview, "toBuild")).isEqualTo(15);
        assertThat(count(preview, "nothingDue")).isEqualTo(5);
        assertThat(count(preview, "existing")).isZero();
        assertThat(count(preview, "built")).isEqualTo(15);
        assertThat(count(preview, "newVersions")).isZero();
        assertThat(count(preview, "unchanged")).isZero();
        assertThat(count(preview, "skipped")).isEqualTo(5);

        JsonNode all = body(buildMonth(treasurer, month, true, true).andExpect(status().isOk()));
        assertThat(count(all, "toBuild")).isEqualTo(20);
        assertThat(count(all, "nothingDue")).isEqualTo(5);
        assertThat(count(all, "built")).isEqualTo(20);
        assertThat(count(all, "skipped")).isZero();

        assertThat(invoiceCount()).isZero();
    }

    @Test
    void theBuildCreatesTheInvoicesAndARebuildWithoutAChangeWritesNothing() throws Exception {
        String month = currentMonth();
        twentyPlots(month);

        JsonNode first = built(month, false);
        assertThat(first.get("preview").asBoolean()).isFalse();
        assertThat(count(first, "invoiceable")).isEqualTo(20);
        assertThat(count(first, "built")).isEqualTo(15);
        assertThat(count(first, "newVersions")).isZero();
        assertThat(count(first, "unchanged")).isZero();
        assertThat(count(first, "skipped")).isEqualTo(5);
        assertThat(first.has("toBuild")).isFalse();
        assertThat(invoiceCount()).isEqualTo(15);
        send(treasurer, get(INVOICES + "?month=" + month + "&size=100"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(15))
                .andExpect(jsonPath("$.items[0].version").value(1))
                .andExpect(jsonPath("$.items[0].due").value("75.00"));

        JsonNode again = built(month, false);
        assertThat(count(again, "built")).isZero();
        assertThat(count(again, "newVersions")).isZero();
        assertThat(count(again, "unchanged")).isEqualTo(15);
        assertThat(count(again, "skipped")).isEqualTo(5);
        assertThat(invoiceCount()).isEqualTo(15);

        JsonNode preview = body(buildMonth(treasurer, month, true, false).andExpect(status().isOk()));
        assertThat(count(preview, "existing")).isEqualTo(15);
        assertThat(count(preview, "nothingDue")).isEqualTo(5);
        assertThat(count(preview, "toBuild")).isZero();
        assertThat(count(preview, "unchanged")).isEqualTo(15);
        assertThat(count(preview, "skipped")).isEqualTo(5);
    }

    /** The counts a build answers, in the order built, newVersions, unchanged, skipped. */
    private static List<Integer> outcome(JsonNode result) {
        return List.of(count(result, "built"), count(result, "newVersions"), count(result, "unchanged"),
                count(result, "skipped"));
    }

    @Test
    void thePreviewPredictsTheBuildWhenAPlotAlreadyHasAnInvoiceOfTheMonth() throws Exception {
        String month = currentMonth();
        List<UUID> plots = twentyPlots(month);
        issued(plots.get(0), month); // on request before the build: the only plot with an invoice of the month

        JsonNode preview = body(buildMonth(treasurer, month, true, false).andExpect(status().isOk()));
        assertThat(count(preview, "invoiceable")).isEqualTo(20);
        assertThat(count(preview, "existing")).isEqualTo(1);
        assertThat(count(preview, "toBuild")).isEqualTo(14);
        assertThat(count(preview, "nothingDue")).isEqualTo(5);
        assertThat(outcome(preview)).containsExactly(14, 0, 1, 5);
        assertThat(invoiceCount()).isEqualTo(1);

        JsonNode result = built(month, false);
        assertThat(outcome(result)).isEqualTo(outcome(preview));
        assertThat(count(result, "invoiceable")).isEqualTo(count(preview, "invoiceable"));
        assertThat(invoiceCount()).isEqualTo(15);

        // a payment on a plot: the preview predicts its new version, and the build agrees
        pay(plots.get(1), "CASH", "75.00", null);
        JsonNode next = body(buildMonth(treasurer, month, true, false).andExpect(status().isOk()));
        assertThat(count(next, "existing")).isEqualTo(15);
        assertThat(outcome(next)).containsExactly(0, 1, 14, 5);
        assertThat(outcome(built(month, false))).isEqualTo(outcome(next));
        assertThat(invoiceCount()).isEqualTo(16);
    }

    @Test
    void aPaymentAfterTheBuildGivesOnlyThatPlotAVersion2() throws Exception {
        String month = currentMonth();
        List<UUID> plots = twentyPlots(month);
        built(month, false);

        UUID paid = plots.get(3);
        pay(paid, "CASH", "75.00", null);
        JsonNode again = built(month, false);

        assertThat(count(again, "newVersions")).isEqualTo(1);
        assertThat(count(again, "unchanged")).isEqualTo(14);
        assertThat(count(again, "built")).isZero();
        assertThat(owner().queryForObject("select max(version) from invoice where plot_id = ?", Integer.class, paid))
                .isEqualTo(2);
        assertThat(owner().queryForObject("select due::text from invoice where plot_id = ? and version = 2",
                String.class, paid)).isEqualTo("0.00");
        assertThat(owner().queryForObject("select count(*) from invoice where tenant_id = ? and version > 1",
                Integer.class, tenant)).isEqualTo(1);
    }

    @Test
    void withIncludeNothingDuePlotsWithNothingDueGetAnInvoiceWithoutTheQrCode() throws Exception {
        String month = currentMonth();
        List<UUID> plots = twentyPlots(month);

        JsonNode result = built(month, true);

        assertThat(count(result, "built")).isEqualTo(20);
        assertThat(count(result, "skipped")).isZero();
        UUID nothingDue = owner().queryForObject("select id from invoice where plot_id = ?", UUID.class,
                plots.get(17));
        JsonNode snapshot = body(invoice(treasurer, nothingDue).andExpect(status().isOk()));
        assertThat(snapshot.get("totals").get("due").asString()).isEqualTo("0.00");
        assertThat(snapshot.has("qr")).isFalse();
        UUID due = owner().queryForObject("select id from invoice where plot_id = ?", UUID.class, plots.get(0));
        assertThat(body(invoice(treasurer, due)).has("qr")).isTrue();

        // a plot that has an invoice of the month is compared, whatever it owes
        JsonNode again = built(month, false);
        assertThat(count(again, "unchanged")).isEqualTo(20);
        assertThat(count(again, "skipped")).isZero();
    }

    @Test
    void aClosedMonthAndAnOpenMonthAreBuiltWithTheirStatus() throws Exception {
        String previous = previousMonth();
        String current = currentMonth();
        UUID plot = ownedPlots(9, 1, previous, "75.00").getFirst();
        charge(plot, "MEMBERSHIP", "75.00", current);
        closeMonth(previous);

        assertThat(count(built(previous, false), "built")).isEqualTo(1);
        assertThat(count(built(current, false), "built")).isEqualTo(1);

        UUID closed = owner().queryForObject("select id from invoice where plot_id = ? and month = ?::date",
                UUID.class, plot, previous + "-01");
        UUID open = owner().queryForObject("select id from invoice where plot_id = ? and month = ?::date",
                UUID.class, plot, current + "-01");
        JsonNode closedSnapshot = body(invoice(treasurer, closed));
        assertThat(closedSnapshot.get("monthStatus").asString()).isEqualTo("CLOSED");
        assertThat(closedSnapshot.get("totals").get("endDebt").asString()).isEqualTo("75.00");
        JsonNode openSnapshot = body(invoice(treasurer, open));
        assertThat(openSnapshot.get("monthStatus").asString()).isEqualTo("OPEN");
        assertThat(openSnapshot.get("totals").get("endDebt").asString()).isEqualTo("150.00");
        assertThat(count(built(previous, false), "unchanged")).isEqualTo(1);
    }

    @Test
    void aMonthThatIsNotListedOrMissingRequisitesIsRefusedAndNothingIsWritten() throws Exception {
        String month = currentMonth();
        ownedPlots(9, 1, month, "75.00");

        buildMonth(treasurer, YearMonth.parse(month).plusMonths(1).toString(), false, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERIOD_IN_FUTURE"));
        buildMonth(treasurer, YearMonth.parse(month).plusMonths(1).toString(), true, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERIOD_IN_FUTURE"));
        buildMonth(treasurer, YearMonth.parse(month).minusMonths(1).toString(), false, null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        buildMonth(treasurer, "2026-13", false, null).andExpect(status().isBadRequest());

        owner().update("update cooperative_settings set iban = null where tenant_id = ?", tenant);
        buildMonth(treasurer, month, false, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUISITES_MISSING"))
                .andExpect(jsonPath("$.missing[0]").value("IBAN"));
        buildMonth(treasurer, month, true, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUISITES_MISSING"));
        assertThat(invoiceCount()).isZero();
    }

    @Test
    void aSecondBuildOfTheMonthWhileTheFirstRunsIsToldSoAndNothingIsBuiltTwice() throws Exception {
        String month = currentMonth();
        ownedPlots(1, 3, month, "75.00");
        AtomicBoolean started = new AtomicBoolean();
        AtomicReference<JsonNode> second = new AtomicReference<>();
        AtomicReference<Integer> secondStatus = new AtomicReference<>();
        AtomicReference<JsonNode> previewMeanwhile = new AtomicReference<>();
        InvoiceBuildHook.afterChunk(context, chunk -> {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            // the first build holds the month's lock and has not committed: another user starts a build and a preview
            try {
                CompletableFuture.runAsync(() -> {
                    try {
                        var response = buildMonth(chairman, month, false, null).andReturn().getResponse();
                        secondStatus.set(response.getStatus());
                        second.set(json.readTree(response.getContentAsString()));
                        previewMeanwhile.set(body(buildMonth(chairman, month, true, null)
                                .andExpect(status().isOk())));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).get(30, TimeUnit.SECONDS); // refused at once, it does not wait for the lock
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        JsonNode first = built(month, false);

        assertThat(secondStatus.get()).isEqualTo(409);
        assertThat(second.get().get("code").asString()).isEqualTo("INVOICE_BUILD_RUNNING");
        assertThat(previewMeanwhile.get().get("invoiceable").asInt()).isEqualTo(3); // the preview takes no lock
        assertThat(count(first, "built")).isEqualTo(3);
        assertThat(invoiceCount()).isEqualTo(3);
        assertThat(owner().queryForObject("select count(*) from invoice where tenant_id = ? and version > 1",
                Integer.class, tenant)).isZero();

        // once the first build has finished, the next one finds nothing changed
        InvoiceBuildHook.afterChunk(context, null);
        JsonNode after = built(month, false);
        assertThat(count(after, "unchanged")).isEqualTo(3);
        assertThat(invoiceCount()).isEqualTo(3);
    }

    @Test
    void everyBuildIsOneAuditEntryWithItsCounts() throws Exception {
        String month = currentMonth();
        twentyPlots(month);

        built(month, false);
        built(month, false);

        await().atMost(Duration.ofSeconds(10)).until(() -> owner().queryForObject(
                "select count(*) from audit_entry where tenant_id = ? and action = 'INVOICES_ISSUED'", Integer.class,
                tenant) == 2);
        assertThat(owner().queryForObject("select count(distinct entity_type || '|' || entity_id || '|' || "
                + "entity_label) from audit_entry where tenant_id = ? and action = 'INVOICES_ISSUED'", Integer.class,
                tenant)).isEqualTo(1);
        assertThat(owner().queryForObject("select entity_type || '|' || entity_label from audit_entry "
                + "where tenant_id = ? and action = 'INVOICES_ISSUED' limit 1", String.class, tenant))
                .isEqualTo("BILLING_MONTH|" + month);
        List<String> changes = owner().queryForList("select changes::text from audit_entry where tenant_id = ? "
                + "and action = 'INVOICES_ISSUED' order by occurred_at", String.class, tenant);
        assertThat(changes.get(0)).contains("\"built\"", "\"15\"", "\"skipped\"", "\"5\"", "\"newVersions\"",
                "\"unchanged\"", month).doesNotContain("Шевченко");
        assertThat(changes.get(1)).contains("\"unchanged\"", "\"15\"");
        assertThat(owner().queryForObject("select count(*) from audit_entry where tenant_id = ? "
                + "and action = 'INVOICE_REISSUED'", Integer.class, tenant)).isZero();
    }

    @Test
    void theMonthListIsInTheNaturalOrderOfThePlotNumbers() throws Exception {
        String month = currentMonth();
        ownedPlots(9, 3, month, "75.00"); // 9, 10, 11
        UUID hundred = ownedPlots(100, 1, month, "75.00").getFirst();
        UUID twelveA = plot("12а", "Садова");
        own(memberId, twelveA);
        charge(twelveA, "MEMBERSHIP", "75.00", month);
        built(month, false);

        send(treasurer, get(INVOICES + "?month=" + month), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(5))
                .andExpect(jsonPath("$.items[0].plotNumber").value("9"))
                .andExpect(jsonPath("$.items[1].plotNumber").value("10"))
                .andExpect(jsonPath("$.items[2].plotNumber").value("11"))
                .andExpect(jsonPath("$.items[3].plotNumber").value("12а"))
                .andExpect(jsonPath("$.items[4].plotId").value(hundred.toString()));
        send(treasurer, get(INVOICES + "?month=" + month + "&size=2&page=1"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].plotNumber").value("11"));
    }
}
