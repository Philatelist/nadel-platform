package app.nadel.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * The event {@link InvoicesIssued} (feature 021 §2.2): a month build publishes one event with exactly the plots whose
 * invoice got version 1 or a new version, never the unchanged or skipped ones, and none when it stores nothing; an
 * invoice issued on request publishes one with its plot, and none when nothing changed; a preview publishes nothing.
 */
@RecordApplicationEvents
class InvoicesIssuedEventTests extends DocumentsTestSupport {

    @Autowired
    ApplicationEvents events;

    @Test
    void aMonthBuildPublishesThePlotsWithANewVersionOnly() throws Exception {
        String month = currentMonth();
        UUID first = plot("1", "Садова");
        UUID second = plot("2", "Садова");
        UUID nothingDue = plot("3", "Садова");
        for (UUID plot : List.of(first, second, nothingDue)) {
            own(memberId, plot);
        }
        charge(first, "MEMBERSHIP", "100.00", month);
        charge(second, "MEMBERSHIP", "100.00", month);

        buildMonth(treasurer, month, true, null);
        assertThat(events.stream(InvoicesIssued.class)).isEmpty();

        built(month, false);
        assertThat(events.stream(InvoicesIssued.class)).singleElement().satisfies(event -> {
            assertThat(event.tenantId()).isEqualTo(tenant);
            assertThat(event.month()).isEqualTo(YearMonth.parse(month));
            assertThat(event.buildId()).isNotNull();
            assertThat(event.plotIds()).containsExactlyInAnyOrder(first, second);
        });

        events.clear();
        built(month, false);
        assertThat(events.stream(InvoicesIssued.class)).isEmpty();

        charge(second, "MEMBERSHIP", "30.00", month);
        built(month, false);
        assertThat(events.stream(InvoicesIssued.class)).singleElement()
                .satisfies(event -> assertThat(event.plotIds()).containsExactly(second));
    }

    @Test
    void anInvoiceIssuedOnRequestPublishesItsPlotWhenItIsANewVersion() throws Exception {
        String month = currentMonth();
        UUID plot = plot("7", "Садова");
        own(memberId, plot);
        charge(plot, "MEMBERSHIP", "100.00", month);

        UUID invoice = idOf(issuedNewVersion(plot, month));

        assertThat(events.stream(InvoicesIssued.class)).singleElement().satisfies(event -> {
            assertThat(event.plotIds()).containsExactly(plot);
            assertThat(event.buildId()).isEqualTo(invoice);
            assertThat(event.month()).isEqualTo(YearMonth.parse(month));
        });

        events.clear();
        issuedUnchanged(plot, month);
        assertThat(events.stream(InvoicesIssued.class)).isEmpty();
    }
}
