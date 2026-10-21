/**
 * Money and rates as value objects (ADR 0013). {@link app.nadel.money.Money} is an amount in hryvnias with two decimal
 * places and the only place in the platform that rounds money (HALF_UP). {@link app.nadel.money.Rate} is a tariff rate
 * with up to four decimal places that is never rounded. The module has no tables, no endpoints and no dependencies.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Money and rates")
package app.nadel.money;
