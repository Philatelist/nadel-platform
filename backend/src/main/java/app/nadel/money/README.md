# money

Value objects for amounts and rates (ADR 0013). No tables, no endpoints, no dependencies on other modules.

| Type | What it is |
|---|---|
| `Money` | An amount in UAH with exactly two decimals. `of(BigDecimal)` and `ofQuotient(numerator, denominator)` round HALF_UP and are the **only** places in the platform that round money (money-rules section 1). `parse` reads the wire format `^-?\d+\.\d{2}$` strictly; `toString()` is the plain text `"1250.50"`; `ZERO`, `plus`, `minus`, `negate` (the amount of a reversal) and `signum`, all exact. |
| `Rate` | A tariff rate: not negative, at most 10 integer digits, at most 4 decimals, held with scale 4 (`4.32` is `4.3200`). **Never rounded**: a fifth decimal is rejected (`InvalidRateException`), not cut. `parse` reads the wire format `^\d{1,10}(\.\d{1,4})?$`; `value()` gives the `BigDecimal`. |

A rate is not money. A calculation multiplies rates by quantities in exact `BigDecimal` arithmetic and turns the
result into `Money` once, with `Money.ofQuotient`. Nothing else calls `setScale` with a rounding mode on an amount.

Used by `tariffs` and `ledger` now, and by payments later.
