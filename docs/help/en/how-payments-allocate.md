---
id: how-payments-allocate
title: "How the system allocates payments"
description: "How the system allocates a payment to charges, what happens with a prepayment and a reversal, and how debt and plot colour are counted."
kind: topic
routes: []
permissions: []
section: how-it-calculates
order: 100
shots: []
---

A payment is a row in the ledger that erases nothing: a mistake is corrected by a reversal, and the allocation to charges changes only by new rows. How to work with the screens is the page [Payments](help:payments); here are the rules and the examples.

- **The allocation order** — The oldest debt first (by the day it counts from); for the same day, in the order of the cooperative's charge types (membership, electricity, land tax - as on the charge types page); then by the start of the service period and the booking order. Each charge receives at most its open amount; the rest is prepayment if the plot has no open debt (while automatic allocation is on, the free remainder goes at once to the oldest debts).
- **The treasurer's change** — The proposal can be changed before saving: the lines may not add up to more than the payment, and a line may not exceed the charge's open amount. At the bottom you see "Allocated X of Y"; if a line exceeds the balance, the line "More than the remaining {amount}" with the balance stands above the amount field. With automatic allocation on, the unallocated remainder goes to the oldest debts; with it off, or when no debt is open, it becomes prepayment.
- **Taking money back from a charge** — When money is taken off a charge (it is reversed, an adjustment lowers it, or the payment is reversed), this is a separate "release" record. The released sum becomes the plot's prepayment and, if automatic allocation is on, goes to the other open charges.
- **Reversing a payment** — The chairman only, with a reason. Counted in the payment's own month while it is open, otherwise in the earliest open month. A payment is reversed once; a part of a group on its own.
- **A payment of a closed month** — Counted in the earliest open month and keeps its date received; the closed month does not change.
- **Splitting between plots** — In proportion to the debts, kopecks rounded down and the leftover kopecks to the largest remainders (a tie goes to the plot listed first). This is the only place of rounding in allocation; the parts always add up to the payment.
- **One at a time** — Every change of a plot (payment, allocation, release) first locks the plot's ledger, so two treasurers cannot allocate the same money twice; at the end the database checks it too and refuses to allocate more than a payment holds or a charge is worth.

## Worked examples

Order of charge types: membership, electricity, land tax.

> **Example 1. A payment smaller than the debt**
>
> Debts: January membership 600.00, February electricity 250.00. Payment 700.00. The oldest first: membership 600.00, then electricity 100.00. Electricity has 150.00 open, prepayment 0.00. The plot's debt is 150.00.

> **Example 2. A payment larger than the debt**
>
> The same debts, payment 1,000.00: membership 600.00 and electricity 250.00 are paid in full, prepayment 150.00. There is no debt, so the plot is green although there is a prepayment.

> **Example 4. Prepayment goes to a new charge**
>
> Example 2, then a land tax charge of 120.00 is booked. Automatic allocation is on: 120.00 goes from the prepayment to the land tax and the prepayment becomes 30.00. With the switch off, the land tax would stay a debt of 120.00 and the prepayment 150.00 until the treasurer presses {{ui:charges.allocatePrepayment}}.

> **Example 19. A payment with a remainder while debts are open**
>
> Debts: membership 600.00 (January), electricity 250.00 (February). Payment 700.00; the treasurer allocates only the electricity 250.00. Automatic allocation is on: the remaining 450.00 is placed at once on the membership, 150.00 of membership stays open, the prepayment is 0.00.

> **Example 20. The same with automatic allocation off**
>
> The same case with the switch off: electricity 250.00, the remaining 450.00 stays as prepayment and the membership stays open at 600.00. The treasurer can press {{ui:charges.allocatePrepayment}}.

> **Example 6. A charge is reversed**
>
> Example 1 (payment 700.00: membership 600.00 and electricity 100.00), then the chairman reverses the January membership. 600.00 is taken back from it - that is a prepayment of 600.00. Automatic allocation moves 150.00 to the open electricity; the prepayment stays 450.00 and there is no debt.

> **Example 12. One payment for two plots**
>
> A member has plot 7 with a debt of 300.00 and plot 15 with a debt of 100.00 and pays 200.00. The parts in proportion to the debts: 200 × 300 / 400 = 150.00 for plot 7 and 50.00 for plot 15. These are two payments of one group; cash has one receipt number. If the member paid 500.00, plot 7 would get 300.00 and another 100.00 as prepayment (the excess goes to the first plot), and plot 15 would get 100.00.

## Debt and colour after payments

- **The plot's debt** is the sum of the open amounts of its charges, that is charged minus allocated. A payment lowers the debt only by what is allocated to charges.
- **The oldest unpaid** is the oldest debt day among charges with an open amount above zero; a charge paid in full does not count, a partly paid one counts with its open part.
- **The colour** uses the same thresholds as before, but over the debt after payments: red if the debt exceeds the threshold or the oldest unpaid day is older than the threshold in days; yellow for any other debt; green when there is no debt, **also when there is a prepayment**.
- The colour is refreshed after every payment, reversal, allocation and release - within seconds. Example: a plot with a debt of 1,200.00 (red with a threshold of 1,000.00) after a payment of 900.00 has a debt of 300.00 and is yellow.
- An adjustment upward (for example +50.00 to a paid membership) leaves 50.00 open, so the plot is no longer green. An adjustment downward (−100.00 to a paid membership) takes 100.00 back from the latest allocation and it becomes prepayment, not a "negative debt".
