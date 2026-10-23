package app.nadel.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.nadel.audit.Actor;
import app.nadel.ledger.ChargesBooked;
import app.nadel.ledger.OpeningBalanceImport.Action;
import app.nadel.ledger.OpeningBalanceImport.Item;
import app.nadel.ledger.OpeningBalanceImport.Plan;
import app.nadel.ledger.OpeningBalanceImport.Problem;
import app.nadel.ledger.OpeningBalanceImport.Row;
import app.nadel.money.Money;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.plots.PlotImportPlan;
import app.nadel.tariffs.ChargeTypeCatalog;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The ledger's side of the "Opening balances" sheet without a database: a commit that books balances on more plots than
 * one {@link ChargesBooked} may carry splits them over several events, and a row that clashes with several standing
 * balances of the same charge type and date names the amount of the earliest booked one, whatever order the ledger
 * returns them in.
 */
class OpeningBalanceImportServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-23T09:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 1, 1);
    private static final ChargeTypeFacts TYPE = new ChargeTypeFacts(UUID.randomUUID(), null, "Membership fee", true,
            true, false, 1);

    private final PlotCatalog plots = mock(PlotCatalog.class);
    private final ChargeTypeCatalog chargeTypes = mock(ChargeTypeCatalog.class);
    private final ChargeRepository charges = mock(ChargeRepository.class);
    private final OpeningBalances openingBalances = mock(OpeningBalances.class);
    private final BillingMonths billingMonths = mock(BillingMonths.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final OpeningBalanceImportService service = new OpeningBalanceImportService(plots, chargeTypes, charges,
            openingBalances, billingMonths, mock(LedgerAuditPublisher.class), events,
            mock(EntityManager.class), Clock.fixed(NOW, ZoneId.of("Europe/Kyiv")));

    @Test
    void aCommitOnMoreThanFiveHundredPlotsPublishesTheBookedPlotsInSeveralEvents() {
        int plotCount = TariffChargeWriter.PLOTS_PER_EVENT + 1;
        List<Row> rows = new ArrayList<>();
        List<Item> items = new ArrayList<>();
        Set<UUID> plotIds = new HashSet<>();
        for (int i = 0; i < plotCount; i++) {
            UUID plotId = UUID.randomUUID();
            plotIds.add(plotId);
            rows.add(new Row(i + 2, "P-" + i, TYPE.name(), new BigDecimal("100.00"), DATE, null));
            items.add(new Item(i + 2, Action.NEW, plotId, TYPE.id(), List.of()));
        }
        when(chargeTypes.all()).thenReturn(List.of(TYPE));
        when(plots.findAll(anyCollection())).thenReturn(Map.of());
        when(openingBalances.book(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> new OpeningBalances.Booked(ChargeRecord.opening(TENANT, call.getArgument(1),
                        TYPE.id(), UUID.randomUUID(), call.getArgument(3), call.getArgument(4), null,
                        call.getArgument(6), Map.of(), null, NOW), List.of()));

        service.apply(TENANT, UUID.randomUUID(), rows, new Plan(items), new Actor(null, "subject", "Actor"));

        ArgumentCaptor<ChargesBooked> published = ArgumentCaptor.forClass(ChargesBooked.class);
        verify(events, times(2)).publishEvent(published.capture());
        assertThat(published.getAllValues()).extracting(event -> event.plotIds().size())
                .containsExactly(TariffChargeWriter.PLOTS_PER_EVENT, 1);
        assertThat(published.getAllValues()).allSatisfy(event -> assertThat(event.tenantId()).isEqualTo(TENANT));
        Set<UUID> all = new HashSet<>();
        published.getAllValues().forEach(event -> all.addAll(event.plotIds()));
        assertThat(all).isEqualTo(plotIds);
    }

    @Test
    void aRowThatClashesWithSeveralStandingBalancesNamesTheAmountOfTheEarliestBookedOne() {
        UUID plotId = UUID.randomUUID();
        ChargeRecord earlier = standing(plotId, "100.00", NOW.minusSeconds(3600));
        ChargeRecord later = standing(plotId, "200.00", NOW.minusSeconds(60));
        when(plots.findActiveByNumbers(anyCollection()))
                .thenReturn(List.of(new PlotFacts(plotId, "A-1", null, false, false, BigDecimal.TEN)));
        when(chargeTypes.findByNames(anyCollection())).thenReturn(Map.of(TYPE.name(), List.of(TYPE)));
        List<Row> rows = List.of(new Row(2, "A-1", TYPE.name(), new BigDecimal("300.00"), DATE, null));

        for (List<ChargeRecord> order : List.of(List.of(later, earlier), List.of(earlier, later))) {
            when(charges.findStandingOfKind(anyCollection(), eq(ChargeKind.OPENING))).thenReturn(order);

            Plan plan = service.plan(TENANT, List.of(), new PlotImportPlan(List.of()), rows);

            assertThat(plan.items()).singleElement().satisfies(item -> {
                assertThat(item.action()).isNull();
                assertThat(item.problems()).containsExactly(
                        new Problem("amount", "OPENING_BALANCE_EXISTS", Map.of("amount", "100.00")));
            });
        }
    }

    @Test
    void aRowDatedInAClosedMonthIsBookedInTheMonthTheCalendarGivesAndEachMonthIsAskedOnce() {
        // the calendar maps January to March (the earliest open month) and February to April
        BillingMonthRecord march = mock(BillingMonthRecord.class);
        BillingMonthRecord april = mock(BillingMonthRecord.class);
        when(billingMonths.openForBooking(eq(TENANT), any()))
                .thenAnswer(call -> ((LocalDate) call.getArgument(1)).getMonthValue() == 1 ? march : april);
        when(chargeTypes.all()).thenReturn(List.of(TYPE));
        when(plots.findAll(anyCollection())).thenReturn(Map.of());
        when(openingBalances.book(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> new OpeningBalances.Booked(ChargeRecord.opening(TENANT, call.getArgument(1),
                        TYPE.id(), UUID.randomUUID(), call.getArgument(3), call.getArgument(4), null,
                        call.getArgument(6), Map.of(), null, NOW), List.of()));
        LocalDate[] dates = {LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 3), LocalDate.of(2026, 1, 20)};
        List<Row> rows = new ArrayList<>();
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < dates.length; i++) {
            rows.add(new Row(i + 2, "P-" + i, TYPE.name(), new BigDecimal("100.00"), dates[i], null));
            items.add(new Item(i + 2, Action.NEW, UUID.randomUUID(), TYPE.id(), List.of()));
        }

        service.apply(TENANT, UUID.randomUUID(), rows, new Plan(items), new Actor(null, "subject", "Actor"));

        ArgumentCaptor<LocalDate> bookedDates = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<BillingMonthRecord> bookedMonths = ArgumentCaptor.forClass(BillingMonthRecord.class);
        verify(openingBalances, times(3)).book(any(), any(), any(), any(), bookedDates.capture(), any(), any(), any(),
                bookedMonths.capture());
        // each charge keeps its own date and goes to the month the calendar chose for the month of that date
        assertThat(bookedDates.getAllValues()).containsExactly(dates);
        assertThat(bookedMonths.getAllValues()).containsExactly(march, april, march);
        // the month of a date is asked once however many rows it has
        verify(billingMonths, times(2)).openForBooking(eq(TENANT), any());
    }

    @Test
    void aTieOnTheTimeOfBookingFallsBackToTheSmallerIdWhateverTheOrder() {
        UUID plotId = UUID.randomUUID();
        ChargeRecord a = standing(plotId, "100.00", NOW.minusSeconds(60));
        ChargeRecord b = standing(plotId, "200.00", NOW.minusSeconds(60));
        assertThat(a.getId()).isNotEqualTo(b.getId());
        ChargeRecord smaller = a.getId().compareTo(b.getId()) < 0 ? a : b;
        when(plots.findActiveByNumbers(anyCollection()))
                .thenReturn(List.of(new PlotFacts(plotId, "A-1", null, false, false, BigDecimal.TEN)));
        when(chargeTypes.findByNames(anyCollection())).thenReturn(Map.of(TYPE.name(), List.of(TYPE)));
        List<Row> rows = List.of(new Row(2, "A-1", TYPE.name(), new BigDecimal("300.00"), DATE, null));

        for (List<ChargeRecord> order : List.of(List.of(a, b), List.of(b, a))) {
            when(charges.findStandingOfKind(anyCollection(), eq(ChargeKind.OPENING))).thenReturn(order);

            Plan plan = service.plan(TENANT, List.of(), new PlotImportPlan(List.of()), rows);

            assertThat(plan.items()).singleElement().satisfies(item -> assertThat(item.problems()).containsExactly(
                    new Problem("amount", "OPENING_BALANCE_EXISTS",
                            Map.of("amount", smaller.getAmount().value().toPlainString()))));
        }
    }

    private static ChargeRecord standing(UUID plotId, String amount, Instant createdAt) {
        return ChargeRecord.opening(TENANT, plotId, TYPE.id(), UUID.randomUUID(), Money.of(new BigDecimal(amount)),
                DATE, null, null, Map.of(), null, createdAt);
    }
}
