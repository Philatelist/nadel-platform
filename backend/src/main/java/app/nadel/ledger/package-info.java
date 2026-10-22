/**
 * The ledger of the cooperative (feature 011): charges of plots, which are never changed or deleted (a mistake is
 * cancelled by a reversal), the billing months they are booked in, opening balances and the account of a plot with the
 * debt per charge type, the month run of recurring tariffs and the "not charged" queue. Tables: {@code billing_month},
 * {@code charge}, {@code charge_part} and {@code meter_value_use} (V13) and {@code not_charged_item} (V15), all under RLS
 * {@code tenant_isolation}; {@code charge} and {@code charge_part} are append-only (privileges and a trigger, ADR 0006).
 * <p>
 * Public API: the event {@link app.nadel.ledger.ChargesBooked}, published after every booking or reversal so that the
 * plot status can follow the debt. Everything else is internal: the entities, the pure rules {@code BillingCalendar}
 * (which month a charge is booked in) and {@code DebtCalculator} (debt per charge type), the reversal and opening
 * balance commands, the account query, the billing months (list, check list, closing in order), the month run
 * ({@code MonthRun}, {@code TariffMonthPlanning}), the queue ({@code NotChargedQueue}), the implementation of the port
 * {@link app.nadel.tariffs.TariffVersionUsage} ({@code LedgerTariffVersionUsage}), the listener
 * {@code PlotStatusRefresher} (the debt behind the plot status, through {@link app.nadel.plots.PlotDebtCache}), the
 * plot guard {@code LedgerPlotGuard} (an implementation of {@link app.nadel.plots.PlotChangeGuard}: a plot with charges
 * is never deleted) and the endpoints of tags {@code charges}, {@code billing} and {@code not-charged}. It depends on
 * {@code tariffs} (the public {@code ChargeTypeCatalog}, {@code TariffBook}, {@code TariffResolver},
 * {@code ChargeCalculator}, {@code DaySet}), {@code plots} (the public {@code PlotCatalog}, {@code PlotChangeGuard} and
 * {@code PlotDebtCache}), {@code meters} ({@code MeterNetwork}), {@code money}, {@code identity}, {@code audit},
 * {@code problem} and {@code tenancy}; no module depends on it.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Ledger and charges")
package app.nadel.ledger;
