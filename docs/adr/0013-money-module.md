# 0013. Money module: `Money` is the only place that rounds, `Rate` is not money

**Status:** accepted; introduced in phase 2 by spec 009 (`context/spec/009-charge-types-and-tariffs/`)

## Context
`docs/domain/money-rules.md` has said since the start that amounts are `BigDecimal` through a `Money` value object and that
rounding happens only there, HALF_UP to two places. No such class existed: the plots module formats an area with its own
`setScale`, and nothing computed money. Feature 009 introduces tariffs with rates of up to four decimal places
(`33.3333` UAH per sotka per year) and an expected-amount calculation; feature 011 (the ledger) and the payments feature
will reuse both. Questions:

1. Where do the value objects live, so that tariffs, the ledger and payments depend on one implementation and not on each
   other?
2. Where exactly does rounding happen, so that "rounded once, at the end" is checkable?
3. Is a tariff rate money?

## Decision
- **A new module `money`** (`app.nadel.money`) holds `Money` and `Rate` in its root package, which is the whole public
  API. It has no tables, no endpoints and no dependency on another module, so any module can use it without a cycle.
  It is a module of its own, not a package of `tariffs`, because the ledger (`charge`, `payment`, `allocation`) must not
  depend on tariffs to hold an amount.
- **`Money`** is an amount with exactly two decimals. The only operations that round are `Money.of(BigDecimal)` and
  `Money.ofQuotient(numerator, denominator)`, both HALF_UP to two places. `ofQuotient` is the intended end of a
  calculation: everything before it is exact `BigDecimal` arithmetic (multiplications, no divisions), so one division and
  one rounding produce the amount. No other code calls `setScale` with a rounding mode on an amount, and reviewers reject
  it. `Money.parse` reads the wire format strictly (`^-?\d+\.\d{2}$`, so `"1250.5"` is refused) and `toString()` is the
  plain text, so the JSON representation is the same string everywhere.
- **`Rate`** is a non-negative number with at most 10 integer digits and at most 4 decimals, held with scale 4
  (`4.32` is `4.3200`), matching `numeric(14,4)`. It is **never rounded**: a value with a fifth decimal is rejected with
  an `InvalidRateException`, not cut to four. A silent rounding of the rate would change what the general meeting decided;
  the database column would also round a fifth decimal silently, so the boundary refuses it first (the contract pattern
  `^\d{1,10}(\.\d{1,4})?$` on the API, `Rate.parse` in the service).
- **A rate is not money.** `Rate` has no `plus`, no relation to `Money` and no rounding; it becomes money only as a factor
  of a numerator that `Money.ofQuotient` divides. Keeping two types stops a rate from being displayed as an amount or an
  amount from being stored with four decimals by mistake. In JSON a rate is the string `"4.3200"`, an amount `"4.32"`.
- Existing code is not rewritten: `PlotQueries` formats an area (not money) with its own `setScale` and stays. New money
  code uses `Money`.

## Consequences
- Tariffs (spec 009), the ledger (spec 011) and payments use the same two types; the rounding rule is testable in one
  class (`MoneyTests`) and the worked examples of money-rules section 4 pass through `Money.ofQuotient`.
- A review can grep for `setScale(` and `RoundingMode` outside `app.nadel.money` to find a violation of the rule.
- A rate cannot carry more than four decimals or a sign; a future rate that needs a different precision is a new decision.
- The module is tiny and has no behaviour of its own over time, so it adds no coupling beyond a type dependency.
