# Glossary

| Term | Code | Meaning |
|---|---|---|
| Cooperative | `Tenant` | A platform customer. All data belongs to exactly one cooperative |
| Plot | `Plot` | A land plot with a number, area and polygon on the map. Kind: plot or common land |
| Sotka | — | 100 m². Plot area is stored in m² and shown in sotkas in the UI |
| Member | `Member` | A natural person in a cooperative's member list. May own several plots. Signs in when their Keycloak account is linked to the member record. Archived, never deleted |
| Ownership | `Ownership` | Member–plot link with a share and a validity period (from / to) |
| Charge type | `ChargeType` | Kind of payment: membership fee, special-purpose fee, electricity, land tax |
| Tariff | `Tariff` | Calculation rule for a charge type |
| Tariff version | `TariffVersion` | Rate and calculation basis effective from a given date |
| Meter | `Meter` | Electricity meter of a plot, or the common transformer meter. A plot has zero or one active meter; a replaced meter keeps its history (money-rules 6.1–6.2) |
| Meter reading | `MeterReading` | Meter value on a date. Source: admin, member, photo. Status: pending / approved / rejected |
| Transformer losses | — | Common meter minus the sum of plot meters for the period |
| Substation meter | `Meter` (kind `SUBSTATION`) | Meter at the cooperative's transformer; root of the meter tree |
| Quarter meter | `Meter` (kind `QUARTER`) | Intermediate meter for a group of plots (a street or block); its losses are shared among those plots |
| Common consumer meter | `Meter` (kind `COMMON_CONSUMER`) | Meter of a shared consumer such as a well pump or street lighting |
| Water meter | `Meter` (resource `WATER`) | Future: water meter of a plot, readings in m³ |
| Billing period | `BillingPeriod` | A billing month. Open or closed |
| Charge | `Charge` | Ledger entry: amount due for a plot, charge type and period |
| Reversal | `Charge.kind = REVERSAL` | Entry that cancels an earlier charge |
| Payment | `Payment` | Money received: from a bank statement or as cash through the cash desk |
| Receipt | `Payment.receiptNumber` | Sequential per-cooperative number of a cash payment, shown to the member |
| Allocation | `Allocation` | Link of part of a payment to a specific charge |
| Prepayment | — | Unallocated remainder of a plot's payments |
| Invoice | `Invoice` | Monthly document with the amount due, bank details and an NBU payment QR code |
| Plot status | `PlotStatus` | Map color: RED, YELLOW, GREEN, GREY |
| Treasurer | `TREASURER` | Base role. Admin who allocates payments; starts with View members, View plots and View audit log |
| Chairman | `CHAIRMAN` | Base role. Head admin of the cooperative; always has every permission. A cooperative keeps at least one active chairman |
| Member role | `MEMBER` | Base role every ordinary member holds; no permissions by default, sees only their own data |
| Electrician | — | Admin who works only with meters. Not a base role: a custom role, given meter permissions when meters arrive |
| Platform operator | `PLATFORM_OPERATOR` | Owner of the SaaS. Manages cooperatives and subscriptions, cannot see their data |
| Permission | `Permission` | A single allowed action from a fixed list, for example `PAYMENTS_ALLOCATE` or `MEMBERS_EDIT` |
| Role | `Role` | A named set of permissions stored per cooperative. Base roles (Chairman, Treasurer, Member) exist in every cooperative and cannot be renamed or deleted; custom roles are added by anyone with `ROLES_MANAGE`. A member may hold several roles (table `member_role`) and gets the union of their permissions |
| Audit entry | `AuditEvent` (table `audit_entry`) | Append-only record of an admin change: who, when, what, and old and new value of each changed field |
