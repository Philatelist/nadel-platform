# Glossary

| Term | Code | Meaning |
|---|---|---|
| Cooperative | `Tenant` | A platform customer. All data belongs to exactly one cooperative |
| Plot | `Plot` | A land plot with a number, area and polygon on the map. Kind: plot or common land |
| Sotka | — | 100 m². Plot area is stored in m² and shown in sotkas in the UI |
| Member | `Person` | A natural person. May own several plots |
| Ownership | `Ownership` | Person–plot link with a share and a validity period (from / to) |
| Charge type | `ChargeType` | Kind of payment: membership fee, special-purpose fee, electricity, land tax |
| Tariff | `Tariff` | Calculation rule for a charge type |
| Tariff version | `TariffVersion` | Rate and calculation basis effective from a given date |
| Meter | `Meter` | Electricity meter of a plot, or the common transformer meter |
| Meter reading | `MeterReading` | Meter value on a date. Source: admin, member, photo. Status: pending / approved / rejected |
| Transformer losses | — | Common meter minus the sum of plot meters for the period |
| Billing period | `BillingPeriod` | A billing month. Open or closed |
| Charge | `Charge` | Ledger entry: amount due for a plot, charge type and period |
| Reversal | `Charge.kind = REVERSAL` | Entry that cancels an earlier charge |
| Payment | `Payment` | Money received: from a bank statement or as cash through the cash desk |
| Receipt | `Payment.receiptNumber` | Sequential per-cooperative number of a cash payment, shown to the member |
| Allocation | `Allocation` | Link of part of a payment to a specific charge |
| Prepayment | — | Unallocated remainder of a plot's payments |
| Invoice | `Invoice` | Monthly document with the amount due, bank details and an NBU payment QR code |
| Plot status | `PlotStatus` | Map color: RED, YELLOW, GREEN, GREY |
| Treasurer | `TREASURER` | Admin who allocates payments |
| Chairman | `CHAIRMAN` | Head admin of the cooperative |
| Electrician | `ELECTRICIAN` | Admin who works only with meters |
| Platform operator | `PLATFORM_OPERATOR` | Owner of the SaaS. Manages cooperatives and subscriptions, cannot see their data |
| Permission | `Permission` | A single allowed action from a fixed list, for example `PAYMENTS_ALLOCATE` or `MEMBERS_EDIT` |
| Role | `Role` | A named set of permissions. Base roles exist in every cooperative; the chairman can add custom admin roles |
