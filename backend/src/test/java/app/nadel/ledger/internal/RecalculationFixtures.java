package app.nadel.ledger.internal;

import app.nadel.ledger.internal.BillingCalendar.MonthState;
import app.nadel.ledger.internal.ExpectedCharges.Expected;
import app.nadel.ledger.internal.LossesCalculator.NodeResult;
import app.nadel.ledger.internal.ReadingChargePlanning.MeterInterval;
import app.nadel.ledger.internal.ReadingChargePlanning.NewCharges;
import app.nadel.ledger.internal.ReadingChargePlanning.PlannedReadingCharge;
import app.nadel.ledger.internal.RecalculationPlanner.Input;
import app.nadel.ledger.internal.RecalculationPlanner.Line;
import app.nadel.ledger.internal.RecalculationPlanner.Plan;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterNetwork.NetworkChain;
import app.nadel.meters.MeterNetwork.Point;
import app.nadel.meters.MeterNetwork.PointKind;
import app.nadel.meters.MeterResource;
import app.nadel.meters.MeterValue;
import app.nadel.meters.Quantity;
import app.nadel.money.Money;
import app.nadel.money.Rate;
import app.nadel.tariffs.Basis;
import app.nadel.tariffs.DaySet;
import app.nadel.tariffs.Frequency;
import app.nadel.tariffs.TariffBook.ChargeTypeEntry;
import app.nadel.tariffs.TariffBook.Scope;
import app.nadel.tariffs.TariffBook.Snapshot;
import app.nadel.tariffs.TariffBook.TariffEntry;
import app.nadel.tariffs.TariffBook.VersionRef;
import app.nadel.tariffs.TariffResolver.PlotFacts;
import app.nadel.tariffs.VersionTerms;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The cooperative of the recalculation planner tests (feature 012 §3): membership 50.0000 per sotka per year, a 600 m2
 * plot (25.00 a month), electricity 4.3200 per kWh, September closed and October open unless stated. Every booked
 * charge and every expected charge is produced by the 011 planners, never typed in by hand; a {@link Ledger} keeps the
 * rows a test books.
 */
final class RecalculationFixtures {

    static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-00000000a000");
    static final UUID PLOT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID PLOT_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID MEMBERSHIP = UUID.fromString("00000000-0000-0000-0000-00000000c001");
    static final UUID ELECTRICITY = UUID.fromString("00000000-0000-0000-0000-00000000c002");
    static final UUID WATER = UUID.fromString("00000000-0000-0000-0000-00000000c003");
    static final UUID COMMON_CONSUMERS = UUID.fromString("00000000-0000-0000-0000-00000000c004");
    static final UUID METER = UUID.fromString("00000000-0000-0000-0000-00000000e001");
    static final UUID NODE = UUID.fromString("00000000-0000-0000-0000-00000000e002");
    static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    static final YearMonth AUG = YearMonth.of(2026, 8);
    static final YearMonth SEP = YearMonth.of(2026, 9);
    static final YearMonth OCT = YearMonth.of(2026, 10);
    static final YearMonth NOV = YearMonth.of(2026, 11);

    /** 23.10.2026: August and September closed, October open. */
    static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
    static final List<MonthState> SEPTEMBER_CLOSED = List.of(new MonthState(AUG, true), new MonthState(SEP, true),
            new MonthState(OCT, false));

    /** 20.11.2026: September closed, October and November open. */
    static final LocalDate NOVEMBER_20 = LocalDate.of(2026, 11, 20);
    static final List<MonthState> OCTOBER_AND_NOVEMBER_OPEN = List.of(new MonthState(SEP, true),
            new MonthState(OCT, false), new MonthState(NOV, false));

    static final PlotFacts PLOT_600 = facts(PLOT, "600");

    private RecalculationFixtures() {
    }

    static PlotFacts facts(UUID plotId, String areaM2) {
        return new PlotFacts(plotId, false, false, new BigDecimal(areaM2));
    }

    static Snapshot snapshot(TariffEntry... tariffs) {
        return new Snapshot(List.of(new ChargeTypeEntry(MEMBERSHIP, "MEMBERSHIP", true, false),
                new ChargeTypeEntry(ELECTRICITY, "ELECTRICITY", true, false),
                new ChargeTypeEntry(WATER, "WATER", true, false),
                new ChargeTypeEntry(COMMON_CONSUMERS, "COMMON_CONSUMERS", true, false)), Arrays.asList(tariffs));
    }

    /** A membership tariff (all plots). */
    static TariffEntry membership(UUID tariffId, VersionRef... versions) {
        return tariff(tariffId, MEMBERSHIP, versions);
    }

    static TariffEntry tariff(UUID tariffId, UUID chargeTypeId, VersionRef... versions) {
        return new TariffEntry(tariffId, chargeTypeId, "Тариф " + tariffId.toString().substring(0, 4),
                Scope.ALL_PLOTS, Set.of(), Set.of(), List.of(versions));
    }

    /** Electricity 4.3200 per kWh for every plot since 01.01.2026. */
    static TariffEntry electricityTariff() {
        return tariff(UUID.randomUUID(), ELECTRICITY, perKwh(UUID.randomUUID(), "2026-01-01", "4.3200"));
    }

    /** A version per sotka per year. */
    static VersionRef perSotka(UUID versionId, String validFrom, String rate) {
        return new VersionRef(versionId, new VersionTerms(LocalDate.parse(validFrom), Basis.PER_AREA_SOTKA,
                Frequency.YEARLY, Rate.parse(rate), null));
    }

    /** A version per kWh. */
    static VersionRef perKwh(UUID versionId, String validFrom, String rate) {
        return new VersionRef(versionId, new VersionTerms(LocalDate.parse(validFrom), Basis.PER_KWH, Frequency.MONTHLY,
                Rate.parse(rate), null));
    }

    /** A version per m3. */
    static VersionRef perM3(UUID versionId, String validFrom, String rate) {
        return new VersionRef(versionId, new VersionTerms(LocalDate.parse(validFrom), Basis.PER_M3, Frequency.MONTHLY,
                Rate.parse(rate), null));
    }

    static Point point(String on, String value, String key) {
        return new Point(LocalDate.parse(on), MeterValue.parse(value), key, PointKind.READING);
    }

    static MeterInterval interval(Point from, Point to) {
        return new MeterInterval(METER, from, to);
    }

    /** The month run's plan of the membership type for the plot, as 011 would book it now, per month. */
    static List<Expected> expectedMembership(Snapshot snapshot, PlotFacts plot, YearMonth... months) {
        List<Expected> expected = new ArrayList<>();
        for (YearMonth month : months) {
            expected.addAll(ExpectedCharges.ofTariffMonth(
                    TariffMonthPlanning.plan(snapshot, MEMBERSHIP, plot, month, DaySet.ALL, key -> false),
                    plot.plotId(), MEMBERSHIP, month));
        }
        return expected;
    }

    static ReadingChargePlanning.Outcome planInterval(Snapshot snapshot, UUID chargeTypeId, PlotFacts plot,
            MeterResource resource, MeterInterval interval, DaySet applicable, @Nullable BigDecimal lossesPercent) {
        return ReadingChargePlanning.plan(snapshot, chargeTypeId, plot, resource, interval, applicable, lossesPercent,
                key -> false);
    }

    /** What 011 would charge for an electricity interval of the plot now. */
    static List<Expected> expectedElectricity(Snapshot snapshot, MeterInterval interval,
            @Nullable BigDecimal lossesPercent) {
        return ExpectedCharges.ofInterval(planInterval(snapshot, ELECTRICITY, PLOT_600, MeterResource.ELECTRICITY,
                interval, DaySet.ALL, lossesPercent), interval, PLOT, ELECTRICITY);
    }

    /** A proportional losses node whose losses are shared as given (kWh per plot). */
    static LossesPlanning.Plan lossesPlan(LocalDate from, LocalDate to, Snapshot snapshot,
            Map<UUID, PlotFacts> plots, Map<UUID, String> shares) {
        Map<UUID, Quantity> kwh = new LinkedHashMap<>();
        Quantity losses = Quantity.ZERO;
        for (Map.Entry<UUID, String> share : shares.entrySet()) {
            Quantity quantity = Quantity.of(MeterValue.parse(share.getValue()));
            kwh.put(share.getKey(), quantity);
            losses = losses.plus(quantity);
        }
        Quantity children = Quantity.of(MeterValue.parse("100"));
        NodeResult node = new NodeResult(NODE, LossesCalculator.Outcome.CHARGED, children.plus(losses), children,
                false, null, null, kwh, Set.of());
        NetworkChain chain = new NetworkChain(NODE, MeterKind.QUARTER, null, NODE, "Квартал А", List.of(), List.of(),
                List.of());
        return LossesPlanning.plan(from, to, List.of(new LossesPlanning.PlannedInput(chain, node, null)), snapshot,
                ELECTRICITY, plots);
    }

    static Plan plan(List<BookedCharge> booked, List<Expected> expected, List<MonthState> months, LocalDate today,
            RecalculationRecord.Cause cause) {
        return plan(booked, expected, months, today, cause, Set.of());
    }

    static Plan plan(List<BookedCharge> booked, List<Expected> expected, List<MonthState> months, LocalDate today,
            RecalculationRecord.Cause cause, Set<String> openQueueKeys) {
        return RecalculationPlanner.plan(new Input(booked, expected, months, today, openQueueKeys, cause,
                Set.of(WATER), runMonths(booked)));
    }

    /** The month of each run: the month its run booking wrote its charges in (the earliest of its regular rows). */
    static Map<UUID, YearMonth> runMonths(List<BookedCharge> booked) {
        Map<UUID, YearMonth> months = new HashMap<>();
        for (BookedCharge charge : booked) {
            if (charge.runId() != null && charge.kind() == ChargeKind.REGULAR) {
                months.merge(charge.runId(), charge.billingMonth(), (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        return months;
    }

    /** A line as text: action, first service day, effective -> expected, difference, the month it goes to. */
    static String describe(Line line) {
        return line.action() + " " + line.serviceFrom() + " " + line.effective() + "->" + line.expected() + " "
                + line.difference() + " in " + line.bookedIn();
    }

    static List<String> describe(Plan plan) {
        return plan.lines().stream().map(RecalculationFixtures::describe).toList();
    }

    /** The rows a test books, each in its billing month. */
    static final class Ledger {

        private final List<BookedCharge> rows = new ArrayList<>();

        List<BookedCharge> rows() {
            return List.copyOf(rows);
        }

        ChargeRecord add(ChargeRecord charge, YearMonth billingMonth) {
            rows.add(BookedCharge.of(charge, billingMonth));
            return charge;
        }

        /** The month run's charge of the membership type, as 011 books it. */
        ChargeRecord membership(Snapshot snapshot, PlotFacts plot, YearMonth month, YearMonth billingMonth) {
            return membership(snapshot, plot, month, billingMonth, "");
        }

        /** The same, under the base key plus {@code suffix} (a charge booked again by a recalculation). */
        ChargeRecord membership(Snapshot snapshot, PlotFacts plot, YearMonth month, YearMonth billingMonth,
                String suffix) {
            TariffMonthPlanning.PlannedCharge charge = ((TariffMonthPlanning.NewCharge) TariffMonthPlanning.plan(
                    snapshot, MEMBERSHIP, plot, month, DaySet.ALL, key -> false)).charge();
            return add(ChargeRecord.tariff(TENANT, charge.plotId(), charge.chargeTypeId(), UUID.randomUUID(),
                    charge.tariff().id(), charge.amount(), charge.serviceFrom(), charge.serviceTo(),
                    charge.accruedOn(), charge.sourceKey() + suffix, charge.calculation(), null, NOW), billingMonth);
        }

        /** The charges of an electricity interval of the plot (consumption, then losses), as 011 books them. */
        List<ChargeRecord> electricity(Snapshot snapshot, MeterInterval interval, @Nullable BigDecimal lossesPercent,
                YearMonth billingMonth) {
            NewCharges charges = (NewCharges) planInterval(snapshot, ELECTRICITY, PLOT_600, MeterResource.ELECTRICITY,
                    interval, DaySet.ALL, lossesPercent);
            List<ChargeRecord> written = new ArrayList<>();
            written.add(reading(charges.consumption(), billingMonth));
            if (charges.losses() != null) {
                written.add(reading(charges.losses(), billingMonth));
            }
            return written;
        }

        ChargeRecord reading(PlannedReadingCharge charge, YearMonth billingMonth) {
            return add(ChargeRecord.reading(TENANT, charge.plotId(), charge.chargeTypeId(), UUID.randomUUID(),
                    charge.source(), charge.tariff().id(), charge.interval().meterId(), charge.amount(),
                    charge.serviceFrom(), charge.serviceTo(), charge.accruedOn(), charge.sourceKey(),
                    charge.calculation(), null, NOW), billingMonth);
        }

        /** The charges of a proportional losses run, as {@code LossesRuns} books them. */
        List<ChargeRecord> lossesRun(LossesPlanning.Plan plan, UUID runId, YearMonth billingMonth) {
            List<ChargeRecord> written = new ArrayList<>();
            for (LossesPlanning.PlannedCharge charge : plan.charges()) {
                written.add(add(ChargeRecord.lossesRun(TENANT, charge.plotId(), ELECTRICITY, UUID.randomUUID(),
                        charge.tariff().id(), runId, charge.amount(), plan.from(), plan.to(),
                        "LOSSES_RUN:" + runId + ":" + charge.plotId(), charge.calculation(runId), null, NOW),
                        billingMonth));
            }
            return written;
        }

        ChargeRecord adjust(ChargeRecord original, String amount, YearMonth billingMonth) {
            return add(ChargeRecord.adjustment(original, UUID.randomUUID(), UUID.randomUUID(), Money.parse(amount),
                    "Виправлено", Map.of(), null, NOW), billingMonth);
        }

        ChargeRecord reverseByHand(ChargeRecord original, YearMonth billingMonth) {
            return add(ChargeRecord.reversalOf(original, UUID.randomUUID(), "Помилка", Map.of(), null, NOW),
                    billingMonth);
        }

        ChargeRecord reverseByRecalculation(ChargeRecord original, YearMonth billingMonth) {
            return add(ChargeRecord.recalculationReversalOf(original, UUID.randomUUID(), UUID.randomUUID(),
                    "Виправлено", Map.of(), null, NOW), billingMonth);
        }
    }
}
