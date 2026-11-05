---
id: how-principles
title: "Core principles"
description: "The core principles by which the system counts money: kopecks, rounding once, rates, reversals and the audit log."
kind: topic
routes: []
permissions: []
section: how-it-calculates
order: 10
shots: []
---

- **Only the server counts money** — The web page and the app only show amounts, debts and statuses; they never calculate them. One formula, in one place.
- **Kopecks** — Hryvnia with two decimals. Intermediate calculations are exact, never rounded. An amount is rounded to kopecks **once**, half up, only when a charge is produced.
- **A rate is not money** — A tariff rate has up to four decimals (1.4532 ₴ per m²) and is never rounded. A fifth decimal is rejected, not cut.
- **Nothing is erased** — Charges, owners and readings are never edited in place. A mistake is fixed by a new record (a reversal, a new version, a new reading); the old one stays in the history.
- **Reports calculate nothing again** — A report only selects, groups and adds up amounts already booked under these same rules, so every amount in a report can be reconciled to the kopeck with a plot account or the month screen (the page [Reports and templates](help:how-reports)).
- **Audit log** — Every board action is recorded with old and new values.
- **One language** — The interface shows only the selected language, never a mix.
- **Minimal personal data** — No tax numbers, passports or dates of birth; contacts are stored encrypted; personal data never goes to the server logs.

> **Why round once**
>
> A rate of 0.05 ₴ for 15 + 15 days of a 30-day month is exactly 0.05 ₴. Rounding each half separately gives 0.03 + 0.03 = 0.06 ₴, a kopeck more. Over thousands of plots and months such kopecks add up.
