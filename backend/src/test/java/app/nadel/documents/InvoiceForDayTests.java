package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.problem.ApiProblemException;
import app.nadel.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link DocumentsFacade#invoiceForDay} (feature 017 §2.5, the bot's «Рахунок»): one day, one invoice of a plot — the
 * version issued today, by whatever path, whatever changed since; the next day a new version only when the invoice
 * would differ; at most one new version a day under the month lock, also for two requests at once; the refusals of
 * {@link DocumentsFacade#invoiceForToday}.
 */
class InvoiceForDayTests extends DocumentsTestSupport {

    @Autowired
    DocumentsFacade facade;

    private UUID plot;

    @BeforeEach
    void plotWithACharge() {
        plot = plot("9", "Садова");
        own(memberId, plot);
        charge(plot, "MEMBERSHIP", "75.00", currentMonth());
    }

    private <T> T asJob(Supplier<T> work) {
        return TenantContext.runAs(tenant, work);
    }

    @Test
    void theFirstRequestIssuesTheInvoiceAndTheSameDayGivesItAgainWhateverChanged() throws Exception {
        UUID first = asJob(() -> facade.invoiceForDay(plot));
        assertThat(version(first)).isEqualTo(1);

        pay(plot, "CASH", "10.00", null);

        assertThat(asJob(() -> facade.invoiceForDay(plot))).isEqualTo(first);
        assertThat(invoiceCount()).isEqualTo(1);
        // invoiceForToday, by contrast, follows the change
        assertThat(asJob(() -> facade.invoiceForToday(plot))).isNotEqualTo(first);
    }

    @Test
    void aVersionTheMonthBuildIssuedTodayIsTheInvoiceOfTheDay() throws Exception {
        built(currentMonth(), false);
        UUID built = owner().queryForObject("select id from invoice where plot_id = ?", UUID.class, plot);

        assertThat(asJob(() -> facade.invoiceForDay(plot))).isEqualTo(built);
        assertThat(invoiceCount()).isEqualTo(1);
    }

    @Test
    void theNextDayANewVersionOnlyWhenTheInvoiceChanged() throws Exception {
        UUID first = asJob(() -> facade.invoiceForDay(plot));

        issuedADayEarlier();
        assertThat(asJob(() -> facade.invoiceForDay(plot))).isEqualTo(first);
        assertThat(invoiceCount()).isEqualTo(1);

        issuedADayEarlier();
        pay(plot, "CASH", "10.00", null);
        UUID next = asJob(() -> facade.invoiceForDay(plot));

        assertThat(next).isNotEqualTo(first);
        assertThat(version(next)).isEqualTo(2);
        assertThat(asJob(() -> facade.invoiceForDay(plot))).isEqualTo(next);
        assertThat(invoiceCount()).isEqualTo(2);
    }

    @Test
    void twoRequestsAtOnceIssueOneNewVersion() throws Exception {
        asJob(() -> facade.invoiceForDay(plot));
        issuedADayEarlier();
        pay(plot, "CASH", "10.00", null);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<UUID>> answers = List.of(
                    pool.submit(() -> {
                        start.await();
                        return asJob(() -> facade.invoiceForDay(plot));
                    }),
                    pool.submit(() -> {
                        start.await();
                        return asJob(() -> facade.invoiceForDay(plot));
                    }));
            start.countDown();

            assertThat(answers.get(0).get()).isEqualTo(answers.get(1).get());
        } finally {
            pool.shutdownNow();
        }
        assertThat(invoiceCount()).isEqualTo(2);
    }

    @Test
    void theRefusalsOfTheInvoiceForToday() {
        UUID common = plot("90", "Садова");
        owner().update("update plot set kind = 'COMMON' where id = ?", common);

        assertThatThrownBy(() -> asJob(() -> facade.invoiceForDay(common)))
                .isInstanceOf(ApiProblemException.class)
                .extracting(e -> ((ApiProblemException) e).code()).isEqualTo("PLOT_NOT_INVOICEABLE");
        assertThatThrownBy(() -> asJob(() -> facade.invoiceForDay(UUID.randomUUID())))
                .isInstanceOf(ApiProblemException.class)
                .extracting(e -> ((ApiProblemException) e).code()).isEqualTo("PLOT_NOT_FOUND");
        assertThatThrownBy(() -> TenantContext.runAs(OTHER_TENANT, () -> facade.invoiceForDay(plot)))
                .isInstanceOf(ApiProblemException.class)
                .extracting(e -> ((ApiProblemException) e).code()).isEqualTo("PLOT_NOT_FOUND");
        assertThatThrownBy(() -> facade.invoiceForDay(plot)).isInstanceOf(IllegalStateException.class);
        assertThat(invoiceCount()).isZero();
    }

    private int version(UUID invoice) {
        return owner().queryForObject("select version from invoice where id = ?", Integer.class, invoice);
    }

    /** Moves the cooperative's invoices one day back, as if they had been issued yesterday: today is the next day. */
    private void issuedADayEarlier() {
        UUID id = tenant;
        asLedgerOwner(sql -> sql.update("update invoice set issued_at = issued_at - interval '1 day' "
                + "where tenant_id = ?", id));
    }
}
