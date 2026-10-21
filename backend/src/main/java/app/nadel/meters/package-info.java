/**
 * The cooperative's meters (electricity in kWh, water in m3), the dated tree of electricity meters, the readings taken on
 * any date and the consumption calculated from them. Tables: {@code meter}, {@code meter_parent} and
 * {@code meter_reading} (V12), all under RLS {@code tenant_isolation}.
 * <p>
 * Public API: {@link app.nadel.meters.MeterResource}, {@link app.nadel.meters.MeterKind}, the quantities
 * {@link app.nadel.meters.MeterValue} (a reading, three decimals, never rounded) and {@link app.nadel.meters.Quantity}
 * (an exact fraction, rounded once for display), the calculation {@link app.nadel.meters.ConsumptionCalculator} with its
 * records, and the port {@link app.nadel.meters.ReadingUsage} that the ledger implements so that readings a charge uses
 * are no longer corrected or deleted (ADR 0014). Entities, services and endpoints (internal) arrive with the later slices of
 * spec 010; the module then depends on {@code identity}, {@code audit}, {@code problem} and {@code plots}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Meters and readings")
package app.nadel.meters;
