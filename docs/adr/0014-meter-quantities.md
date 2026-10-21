# 0014. Meter quantities: readings are not money, consumption is exact, calculated values are rounded once for display

**Status:** accepted; introduced in phase 2 by spec 010 (`context/spec/010-meters-and-readings/`)

## Context
Feature 010 introduces meter readings (kWh of electricity, cubic metres of water) taken on any date, and the consumption
calculated from them. Three things differ from money and from tariff rates (ADR 0013):

- A reading has up to three decimals (electricity is usually whole kWh, water is read to the litre) and must be stored
  and shown as the person typed it.
- A reading is often needed on a date nobody read the meter: the value on 16.10 between 01.10: 1 000 and 31.10: 1 300 is
  interpolated linearly by days, `1 000 + 300 x 15 / 30`. With most day counts the result is a repeating decimal
  (`1/3` of a kWh), so any fixed number of decimals loses something.
- Consumption over a date range is the difference of two such values, summed over the meters of a plot (replacement,
  a counter that wraps around), split by the parent a meter had in the tree, and, in feature 011, multiplied by a tariff
  rate and rounded to kopecks. If the values were rounded on the way, the parts of a range would not add up to the whole,
  and a charge would depend on how a screen happened to round.

Questions:

1. Is a meter value money, a rate or something else, and where does it live?
2. How is consumption calculated so that parts add up to the whole?
3. Where, if anywhere, does a calculated quantity get rounded?
4. What may feature 011 use to charge?

## Decision
- **A meter value is a quantity, not money.** `MeterValue` lives in the root package of the new module `meters`
  (`app.nadel.meters`), not in `money`: it has no currency, no relation to `Money` or `Rate`, and `money` stays free of
  meter concepts. It is not negative, has at most eleven integer digits and at most three decimals, and is held with scale 3,
  matching `numeric(14,3)`. Like `Rate` it is **never rounded**: a fourth decimal is rejected with an
  `InvalidMeterValueException`, not cut, because what is stored must be what was read off the meter. The API reads it as
  `^\d{1,11}(\.\d{1,3})?$` (`MeterValue.parse` is strict: no sign, exponent, spaces or decimal comma) and writes it with
  exactly three decimals (`"8205.000"`, the stored form); clients trim the zeros for display.
- **Calculations are exact rational arithmetic.** `Quantity` is a reduced fraction of two integers. Reading values
  become quantities (`Quantity.of(MeterValue)`), interpolation is `v0 + (v1 - v0) x k / N` with whole numbers of days, and
  intervals, ranges, sums over a chain of meters and slices by parent period are sums and differences of quantities.
  Nothing in `ConsumptionCalculator` rounds, so a range split at any date adds up exactly to the whole, and the value at
  a date is the same whichever way it is reached.
- **One display rounding.** `Quantity.toMeterValue()` rounds HALF_UP to three decimals. It is the only rounding in the
  `meters` module and exists to show a calculated value (an interpolated reading, a consumption) as a `MeterValue`
  in the API; it is never an input to another calculation. A review can grep `RoundingMode` in `app.nadel.meters` and expect
  exactly this one place (`Money` stays the only place that rounds money, ADR 0013).
- **Consumption comes from exact values.** An interval or a range is computed from the stored readings (exact) and
  interpolated values (exact quantities), never from displayed `MeterValue`s of calculated points. A value on a date that
  has no reading is marked calculated (`ValueAt.Calculated`), and a range with one is `RangeConsumption.Known` with
  `calculated = true`; a range whose far end has no later reading is unknown (`Unknown`), never guessed.
- **Feature 011 must not use displayed values.** The electricity charge takes the consumption as a `Quantity` and turns it
  into money in one step: `Money.ofQuotient(rate.value() x numerator, denominator)`, where `numerator()` and
  `denominator()` are the exact fraction (as the expected amount of a tariff does with area and rate, ADR 0013). It does
  not read `toMeterValue()` and multiply, because that would round twice. The losses of a node and the shares of
  common consumers are likewise computed from `Quantity` and rounded once, at the money.
- **The question "was this used by a charge" is a port.** `ReadingUsage.isUsed(tenantId, meterId, from, to)` is defined by
  `meters` and implemented by the ledger (011), as `TariffVersionUsage` is for tariffs. It is range-based because a
  charge reads values at the ends of its period, which may be interpolated: a reading inserted or changed anywhere between
  the two readings a charge was calculated from changes that value. A default bean answers "not used" until 011.

## Consequences
- The calculation is tested in one place (`QuantityTests`, `ConsumptionCalculatorTests`), including a test that fails when an
  interpolated value is rounded before it is subtracted, and every row of the worked examples of the functional spec
  passes through `ConsumptionCalculator`.
- `Quantity` carries unbounded integers: a range over many meters multiplies denominators, which stay small in practice
  (days of a few hundred, a thousand for the three decimals) and are reduced after every operation.
- A consumer of `Quantity` that needs a fixed-point number has `toMeterValue()` (rounded, display only) and the exact
  `numerator()` and `denominator()`; a new use of either is reviewed against this ADR.
- Water is held in the same type (m3 with three decimals, litres). A resource with another precision would be a new decision.
- Clients never calculate consumption: they receive `MeterValue` strings for readings and for calculated values, marked as
  calculated, and show them.
