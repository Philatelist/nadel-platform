---
id: plots
title: "Plots"
description: "The list of the cooperative's plots with search, filters by kind and status, and the debt colour."
kind: screen
routes:
  - /plots
permissions:
  - PLOTS_VIEW
  - member
section: plots-members
order: 10
shots:
  - name: plots
    user: chairman
    path: /plots
  - name: plots-member
    user: member
    path: /plots
---

## What the screen is for

This is the register of all plots of the cooperative. Here you search for a plot by number, street or cadastral number, filter by kind and status, and go to the [plot card](help:plot-card). The status colour shows whether the plot has a debt.

## Who sees it

The full register is seen by those who have the permission "{{ui:permission.PLOTS_VIEW.name}}": the chairman and the treasurer, and any custom role with this permission. The "{{ui:plots.add}}" button is seen by whoever also has "{{ui:permission.PLOTS_EDIT.name}}".

A member who owns a plot also sees the screen, but a shorter one. It has only four columns: {{ui:plots.columns.number}}, {{ui:plots.columns.street}}, {{ui:plots.columns.kind}} and {{ui:plots.columns.status}}. The member does not see areas, cadastral numbers or owners, there is no "{{ui:plots.showArchived}}" switch and no add button. Search works only by number or street.

> [!important]
> A member sees the status colour of other people's plots only when the [settings](help:settings) say "{{ui:settings.visibility.ALL_PLOTS.label}}". Normally it says "{{ui:settings.visibility.OWN_ONLY.label}}", and instead of the colour of another plot an empty neutral circle is shown. Amounts, owners and personal data of other people's plots are never shown to a member.

## Every element

### The "{{ui:plots.add}}" button

On the right in the header. Opens the [new plot form](help:plot-form).

### Search and filters

- The search field: "{{ui:plots.searchPlaceholder}}". For a member it says: "{{ui:plots.searchPlaceholderRestricted}}". The list updates as soon as you stop typing. Letter case does not matter.
- The "{{ui:plots.filterKind}}" filter: "{{ui:plots.allKinds}}", "{{ui:plot.kind.PLOT}}" or "{{ui:plot.kind.COMMON}}". Common land (roads, shared territory) has no owners, tariffs or plot meters.
- The "{{ui:plots.filterStatus}}" filter: "{{ui:plots.allStatuses}}" or one of the statuses described below.
- The "{{ui:plots.showArchived}}" switch adds archived plots to the list. They are marked "{{ui:plots.archived}}".

Filters are kept in the page address, so a link to a filtered list can be shared. If nothing is found, the system says: "{{ui:plots.nothingFound}}". When there are no plots at all, it says: "{{ui:plots.empty}}".

### Table columns

| Column | What is in it |
|---|---|
| {{ui:plots.columns.number}} | The plot number. It is a link to the card. The order is natural: 2 before 10, and "12а" after 12. |
| {{ui:plots.columns.street}} | The street. A dash if not set. |
| {{ui:plots.columns.area}} | The area in sotky. |
| {{ui:plots.columns.cadastralNumber}} | The cadastral number, if there is one. |
| {{ui:plots.columns.kind}} | "{{ui:plot.kind.PLOT}}" or "{{ui:plot.kind.COMMON}}". |
| {{ui:plots.columns.status}} | A colour and a word: the debt status, see below. |
| {{ui:plots.columns.owners}} | The owners. Two are shown, the rest as a number such as "+1". |
| {{ui:plots.columns.archived}} | The mark "{{ui:plots.archived}}" for archived plots. |

At the bottom there is a page switch: a page holds twenty plots.

### Plot status (colour)

| Colour | Meaning |
|---|---|
| Green: "{{ui:status.GREEN}}" | No debt. The same when the plot has a prepayment. |
| Yellow: "{{ui:status.YELLOW}}" | There is a debt. |
| Red: "{{ui:status.RED}}" | The debt has reached the threshold: the amount or age the chairman sets in the [settings](help:settings). |
| Grey: "{{ui:status.GREY_PLOT}}" or "{{ui:status.GREY_COMMON}}" | A plot without an owner, or common land. |

The colour is calculated from the debt after payments: a paid charge is not a debt. More about debt and colour: [How the system allocates payments](help:how-payments-allocate).

## Worked example

The treasurer wants to check the debtors on Sadova street.

1. Open "{{ui:nav.plots}}" and type "Садова" in the search.
2. In the "{{ui:plots.filterStatus}}" filter choose "{{ui:status.YELLOW}}". The list keeps the plots of Sadova street that have a debt.
3. To see those closer to the threshold, choose "{{ui:status.RED}}".
4. Click the number of the plot you need, for example 9 (15 sotky, owner Lytvynenko), to open the [plot card](help:plot-card) and see what the debt consists of.

The member Bondarenko, who owns plots 7 and 15, sees numbers, streets and kinds of all plots in the same list, but the colour of only his two. The other plots have an empty circle.

## Screenshots

![The plot register with search and filters](shot:plots)

![The plot list as a member sees it](shot:plots-member)

## Related pages

- [Plot card](help:plot-card)
- [New plot and editing](help:plot-form)
- [Import from Excel](help:imports)
- [Cooperative settings](help:settings)
- [My account](help:account)
