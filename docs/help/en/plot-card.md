---
id: plot-card
title: "Plot card"
description: "All data about one plot: owners and ownership history, account, tariffs, meters, water, invoices, certificates and the reconciliation act."
kind: screen
routes:
  - /plots/:id
permissions:
  - PLOTS_VIEW
  - member
section: plots-members
order: 20
shots:
  - name: plot-card
    user: chairman
    path: /plots
    open: { css: 'main a[href^="/plots/"]', text: '9' }
  - name: plot-card-change-owner
    user: chairman
    path: /plots
    open: { css: 'main a[href^="/plots/"]', text: '9' }
    steps:
      - { click: Change owner }
  - name: plot-card-member
    user: member
    path: /plots
    open: { css: 'main a[href^="/plots/"]', text: '7' }
---

## What the screen is for

The card gathers everything about one plot: its data, the owners with the ownership history, the account with charges and payments, the tariffs for the chosen month, meters, the water connection, invoices, the certificate and the reconciliation act. Here you also edit and archive the plot, change its owner and enter a payment.

## Who sees it

- Whoever has the permission "{{ui:permission.PLOTS_VIEW.name}}" sees the card of any plot with all details.
- A member sees only the card of a plot they own. It opens from their plot list or from "{{ui:nav.account}}". The member sees the plot data, the owners, their account, tariffs, meters and invoices. The member does not see the "{{ui:plot.fields.notes}}" field or any buttons that change data.
- The buttons depend on permissions: "{{ui:permission.PLOTS_EDIT.name}}" gives editing and archiving, "{{ui:permission.OWNERSHIP_EDIT.name}}" together with "{{ui:permission.MEMBERS_VIEW.name}}" gives changing owners, "{{ui:permission.PAYMENTS_ENTER.name}}" gives the button to enter a payment.
- A member cannot open somebody else's plot: the system does not show its data.

## Every element

At the top are the link "{{ui:plot.back}}" and a title with the number, for example "Plot 9" (for common land "Common land R-1"). If the plot is archived, the mark "{{ui:plots.archived}}" stands next to it.

### Buttons in the header

- {{ui:payments.enter}}. Opens the [payment form](help:payments-enter) with the plot already chosen. It is not there for common land or archived plots.
- {{ui:plot.edit}}. Opens the [edit form](help:plot-form).
- {{ui:plot.archive}}. Hides the plot from the list but keeps all its history. When the plot is already archived, the system shows {{ui:plot.restore}} instead. A plot with an open-ended water connection or an active meter cannot be archived or turned into common land: the system refuses and asks you first to end the connection or remove the meter. The refusal texts are: "{{ui:problem.PLOT_HAS_METER}}" and "{{ui:problem.PLOT_HAS_WATER_CONNECTION}}".
- {{ui:plot.delete}}. Only for plots that are not archived. The system asks: "{{ui:plot.deleteTitle}}", and warns: "{{ui:plot.deleteText}}".

> [!important]
> Only a plot created by mistake can be deleted: one with no owners in its history, no charges and no payments. Otherwise the system refuses: "{{ui:problem.PLOT_HAS_OWNERS}}", "{{ui:problem.PLOT_HAS_OWNERSHIP_HISTORY}}", "{{ui:problem.PLOT_HAS_CHARGES}}" or "{{ui:problem.PLOT_HAS_PAYMENTS}}". For every other plot use the archive.

### Plot data

The table shows: {{ui:plot.fields.number}}, {{ui:plot.fields.street}}, {{ui:plot.fields.kind}}, {{ui:plot.fields.status}}, {{ui:plot.fields.area}} (in sotky and in m², for example "15 sotky (1,500 m²)"), {{ui:plot.fields.cadastralNumber}}, {{ui:plot.fields.building}} ("{{ui:plot.hasBuilding}}" or "{{ui:plot.noBuilding}}") and {{ui:plot.fields.notes}}. The building is for reference only: it does not affect charges. Notes are seen only by those who have "{{ui:permission.PLOTS_VIEW.name}}".

The status shows the debt colour: see [Plots](help:plots). A member always sees the status of their own plot.

### The "{{ui:plot.ownersTitle}}" panel

Owners are kept here. A plot can have several co-owners with shares, for example 1/2 and 1/3. Together the shares always make one. One member can own several plots, each with its own account. For common land it says: "{{ui:ownership.commonLand}}"

The panel has two lists. "{{ui:ownership.current}}" shows the owners now: the name (a link to the [member card](help:member-card) if you can view members), the share, the date from which they own, and the basis. "{{ui:ownership.history}}" shows previous owners with their periods. Archived members are marked "({{ui:ownership.archivedMember}})".

#### The "{{ui:ownership.changeOwner}}" button

Use it when the plot was sold, inherited, or the co-owners changed.

1. Enter the date in the "{{ui:ownership.effectiveFrom}}" field. It cannot be in the future.
2. For every owner choose a member: the "{{ui:ownership.member}}" field, search by surname ("{{ui:ownership.searchMember}}"), the {{ui:ownership.changeMember}} button changes the choice. If the new owner is not among the members yet, press {{ui:ownership.addMember}} right from the form.
3. In the "{{ui:ownership.share}}" field write the share as a fraction, for example 1/2. The {{ui:ownership.addOwner}} button adds another owner, and the remove button next to an owner's row removes them. Under the list you see the sum of shares: "{{ui:ownership.totalOk}}" when all is well. If the sum is not one, the system says why, and does not let you save.
4. Enter the basis in the "{{ui:ownership.basisField}}" field. The basis is optional.
5. Press {{ui:ownership.save}}. The system says: "{{ui:ownership.changeSaved}}"

Previous owners stay in the history until the day before the transfer date. The debt belongs to the plot, not to the person: after a sale it stays on the plot. With no owner at all the plot turns grey from that date: the system explains "{{ui:ownership.noOwnersHint}}"

Possible messages: "{{ui:validation.SHARE_INVALID}}", "{{ui:problem.SHARES_NOT_ONE}}", "{{ui:problem.DUPLICATE_OWNER}}", "{{ui:problem.OWNERSHIP_DATE_INVALID}}", "{{ui:problem.OWNERSHIP_OVERLAP}}", "{{ui:problem.MEMBER_ARCHIVED}}", "{{ui:ownership.plotArchived}}".

#### The "{{ui:ownership.correct}}" button

It stands next to every record. Use it if a record has a mistake: a wrong share, date or person. The "{{ui:ownership.correctTitle}}" form has the fields "{{ui:ownership.member}}", "{{ui:ownership.share}}", "{{ui:ownership.validFrom}}", "{{ui:ownership.validTo}}" (empty for the current owner), the basis and the required "{{ui:ownership.reason}}". If a share changes, the form also asks for the shares of the co-owners in the same period so that the total is one. After saving the system says: "{{ui:ownership.correctSaved}}" The old values stay in the [change log](help:audit). Ownership records are never deleted.

### The "{{ui:plot.tabs.account}}" tab

This tab is open at first. Its panels:

- "{{ui:charges.panelTitle}}": the charges and payments of the plot, the debt by type after payments, the prepayment.
  - Payments stand in the list together with the charges, by date; next to each charge are "{{ui:charges.charged}}", "{{ui:charges.paid}}" and "{{ui:charges.open}}". Above the list is the debt after payments by type and in total, and beside the total debt "{{ui:charges.prepayment}} X ₴" when the prepayment is above 0.00.
  - The filter "{{ui:charges.paymentsOnly}}" leaves only payments and their reversals in the list; the debts above the list do not change. Here the opening balance is entered, charges are reversed and the prepayment is allocated (for those who have these permissions). Details: [Charges](help:charges) and [Payments](help:payments).
- A link to the report "{{ui:reports.plotBalancesLink}}" (for those who see reports).
- "{{ui:tariffs.expected.panelTitle}}": the tariffs that apply to the plot in the chosen month and the expected amount. Details: [Tariffs](help:tariffs).
- "{{ui:meters.plotPanelTitle}}": the meters of the plot. Details: [Meters](help:meters).
- "{{ui:water.title}}": whether the plot is connected to water. The {{ui:water.connect}} or {{ui:water.disconnect}} button opens a form with the water source and dates. The buttons are seen by whoever has "{{ui:permission.METERS_MANAGE.name}}".

### The "{{ui:plot.tabs.invoices}}", "{{ui:plot.tabs.reconciliationAct}}" and "{{ui:plot.tabs.certificates}}" tabs

- "{{ui:plot.tabs.invoices}}": the invoices of the plot by month with the number, the version and the amount "due". The {{ui:invoices.download}} button downloads the PDF. The latest version of each month is shown; older ones lie under "{{ui:invoices.earlierVersions}}". The {{ui:invoices.issueToday}} button builds an invoice as of today for the current accounting month and downloads its PDF at once (an indicator is on the button while it is prepared); it is for those who may issue invoices. A new version appears only when something changed since the latest one (a payment, a charge, the owners, the requisites); otherwise the PDF of the latest version is downloaded and nothing new is created. The invoices of a month are built on the [month page](help:charges-month), sending is described on [Delivery](help:deliveries). What is in an invoice: [How to read my invoice](help:member-bill).
- "{{ui:plot.tabs.reconciliationAct}}": the "{{ui:reconciliationAct.formTitle}}" form. Choose a period ("{{ui:reconciliationAct.from}}" and "{{ui:reconciliationAct.to}}") and press {{ui:reconciliationAct.download}}. You get a PDF in Ukrainian for the plot and the period: the requisites of the cooperative, the plot and its current owners, the period, the opening balance (a debt, an overpayment or nothing), all charges, corrections, reversals and payments by date (columns "{{ui:charges.charged}}" and "{{ui:charges.paid}}"), the turnover totals, the closing balance and the sentence "According to the cooperative's records the debt as of 31.10.2026 is …" (or "the overpayment is …", or "there is no debt"). At the bottom are two signature blocks: "From the cooperative" (with the treasurer's name, or an empty line if there is no treasurer) and "Member of the cooperative". The period cannot start before the first charge of the plot or end after today: otherwise "{{ui:validation.INVALID_from}}". Without the requisites of the cooperative the act is not built, just like invoices. The act is not stored: it is built on request, and the same period always gives the same act. It can be sent like the other documents. For those who have "{{ui:permission.CHARGES_VIEW.name}}".
- "{{ui:plot.tabs.certificates}}": the "{{ui:certificates.formTitle}}" form. Choose the "{{ui:certificates.asOf}}" date and press {{ui:certificates.issue}}. The system says the certificate is issued and gives its number. The certificate can be downloaded with the {{ui:certificates.download}} button. All issued certificates are collected in the [certificate register](help:certificates). For those who have "{{ui:permission.CERTIFICATES_ISSUE.name}}".

If there is only one tab, the tab bar is not shown.

### The member's view

The owner of a plot sees the same data, but without "{{ui:plot.fields.notes}}" and without the buttons that change data. The owner sees the owners and the history, their account, the expected charges, the meters of the plot and its invoices. The owner has no "{{ui:plot.tabs.reconciliationAct}}" and "{{ui:plot.tabs.certificates}}" tabs: they ask the board for these or download the act in "{{ui:nav.account}}".

## Worked example

Plot 9, the corner plot by the gate: 15 sotky (1,500 m²), has a building, the note says "Кутова ділянка біля в’їзних воріт" (corner plot by the entrance gate). In 2025 it was sold: until 31.05.2025 it was owned by Honcharenko Mykola (now archived), and since 01.06.2025 by Lytvynenko Svitlana. Basis: "Договір купівлі-продажу від 20.05.2025" (sale contract of 20.05.2025).

How to record such a transfer:

1. Open the card of plot 9 and press {{ui:ownership.changeOwner}}.
2. In the "{{ui:ownership.effectiveFrom}}" field enter 01.06.2025.
3. In the "{{ui:ownership.member}} 1" field find Lytvynenko, share 1.
4. Write the basis and press {{ui:ownership.save}}.
5. "{{ui:ownership.current}}" now shows Lytvynenko from 01.06.2025, and "{{ui:ownership.history}}" shows Honcharenko from 01.06.2015 to 31.05.2025. The debts that were on the plot have not gone anywhere.

Plot 7 belongs to two people: Bondarenko 1/2 and Moroz 1/2. Their shares together make 1. If the form said 1/2 and 1/3, the system would show that the sum is not one and would not let you save.

## Screenshots

![Card of plot 9: owners, history, tabs](shot:plot-card)

![The change owner form](shot:plot-card-change-owner)

![Card of plot 7 as its owner sees it](shot:plot-card-member)

## Related pages

- [Plots](help:plots)
- [Editing a plot](help:plot-form)
- [Member card](help:member-card)
- [Enter a payment](help:payments-enter)
- [Charges](help:charges)
- [Tariffs](help:tariffs)
- [Meters](help:meters)
- [Certificate register](help:certificates)
- [Delivery](help:deliveries)
- [Change log](help:audit)
