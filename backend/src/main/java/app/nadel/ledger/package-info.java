/**
 * The ledger of the cooperative (feature 011): charges of plots, which are never changed or deleted (a mistake is
 * cancelled by a reversal), the billing months they are booked in, opening balances and the account of a plot with the
 * debt per charge type, the month run of recurring tariffs, the charges of meter readings, the proportional losses runs,
 * the common consumer runs and the "not charged" queue. Tables: {@code billing_month}, {@code charge},
 * {@code charge_part} and {@code meter_value_use} (V13), {@code not_charged_item} (V15), {@code losses_run} and
 * {@code losses_run_node} (V17), {@code common_consumer_run}, {@code common_consumer_run_line},
 * {@code common_consumer_billing} and {@code common_consumer_user} (V18), all under RLS {@code tenant_isolation};
 * {@code charge} and {@code charge_part} are append-only (privileges and a trigger, ADR 0006), a losses or common
 * consumer run changes only by its reversal (trigger {@code run_reversal_guard}).
 * <p>
 * Public API: the event {@link app.nadel.ledger.ChargesBooked}, published after every booking or reversal so that the
 * plot status can follow the debt, the import facade {@link app.nadel.ledger.OpeningBalanceImport} (the "Opening
 * balances" sheet of the Excel import) and the read facade of the reports {@link app.nadel.ledger.LedgerReadModel}
 * (aggregates in the caller's transaction, no personal data: month totals, {@link app.nadel.ledger.PlotDebts} as
 * of a day, the {@link app.nadel.ledger.CorrectionRow} of every reversal and adjustment of the billing months, the
 * effective amount charged per plot for a year, the {@link app.nadel.ledger.ChargedRow} of every electricity charge,
 * reversal and adjustment of a range of days and of every water charge of the plots asked, the open reading interval
 * items of the "not charged" queue per meter, and the booked losses and common consumer runs of a range as
 * {@link app.nadel.ledger.LossesRunNodeRow} and {@link app.nadel.ledger.CommonConsumerRunLineRow}). Everything else is
 * internal: the entities, the pure
 * rules {@code BillingCalendar} (which month a charge is booked in) and {@code DebtCalculator} (debt per charge
 * type and prepayment), the payment tables ({@code payment}, {@code allocation}, {@code receipt_counter}, V27; the
 * public {@link app.nadel.ledger.PaymentSource}) with the pure allocation rules ({@code OpenAmounts},
 * {@code AllocationPlanner}, {@code PaymentSplitter}, {@code ReleasePlanner}, {@code PaymentDigest},
 * {@code PaymentRules}), the reversal and opening balance commands, the account query, the billing months (list, check list, closing
 * in order), the month run ({@code MonthRun}, {@code TariffMonthPlanning}), the charges of plot meter intervals
 * ({@code ReadingCharges}, the listener of {@link app.nadel.meters.PlotMeterPointsChanged}; {@code IntervalCharging};
 * the pure {@code ReadingChargePlanning} and {@code ReadingChargeCalculator}), the losses runs ({@code LossesRuns}; the
 * pure {@code LossesCalculator} and {@code LossesPlanning}), the common consumer runs ({@code CommonConsumerRuns},
 * {@code CommonConsumerBillings}; the pure {@code CommonConsumerSplitCalculator} and {@code CommonConsumerPlanning}),
 * the queue ({@code NotChargedQueue}), the
 * implementations of the ports {@link app.nadel.tariffs.TariffVersionUsage} ({@code LedgerTariffVersionUsage}) and
 * {@link app.nadel.meters.ReadingUsage} ({@code LedgerReadingUsage}), the listener
 * {@code PlotStatusRefresher} (the debt behind the plot status, through {@link app.nadel.plots.PlotDebtCache}), the
 * plot guard {@code LedgerPlotGuard} (an implementation of {@link app.nadel.plots.PlotChangeGuard}: a plot with charges
 * is never deleted) and the endpoints of tags {@code charges}, {@code billing}, {@code not-charged},
 * {@code losses-runs} and {@code common-consumer-runs}. It depends on
 * {@code tariffs} (the public {@code ChargeTypeCatalog}, {@code TariffBook}, {@code TariffResolver},
 * {@code ChargeCalculator}, {@code DaySet}), {@code plots} (the public {@code PlotCatalog}, {@code PlotChangeGuard} and
 * {@code PlotDebtCache}), {@code meters} ({@code MeterNetwork}, {@code MeterCatalog}, {@code ConsumptionCalculator},
 * {@code PlotMeterPointsChanged}, {@code ReadingUsage},
 * {@code WaterConnections}: water tariffs charge only the days a plot is connected, a well pump is shared among the
 * plots connected to it),
 * {@code settings} ({@code CooperativeSettings}), {@code money}, {@code identity}, {@code audit},
 * {@code problem} and {@code tenancy}; {@code imports} depends on it (the opening balances sheet) and {@code reports}
 * reads it through {@code LedgerReadModel}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Ledger and charges")
package app.nadel.ledger;
