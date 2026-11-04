---
id: imports
title: "Import from Excel"
description: "How to load plots, members, owners, meters, water connections and opening balances from one Excel file."
kind: screen
routes:
  - /imports
permissions:
  - IMPORT_RUN
section: getting-started
order: 20
shots:
  - name: imports
    user: chairman
    path: /imports
---

## What the screen is for

So that you do not have to type the cooperative in by hand, plots, members, owners, meters, water connections and opening balances can be loaded from one Excel file. The system first checks the file and saves nothing. Saving happens only after you confirm.

## Who sees it

The screen is seen by whoever has the permission "{{ui:permission.IMPORT_RUN.name}}". By default this is the chairman. The menu item "{{ui:nav.imports}}" appears only with this permission.

## Every element

At the top of the screen is the caption: "{{ui:import.description}}". Then come two panels, then the result of the check and the history.

### The "{{ui:import.templateTitle}}" panel

- The {{ui:import.downloadTemplate}} button gives an Excel file in the language of the interface. It has the sheets "{{ui:importSheets.PLOTS}}", "{{ui:importSheets.MEMBERS}}", "{{ui:importSheets.OWNERSHIP}}", "{{ui:importSheets.METERS}}" and "{{ui:importSheets.OPENING_BALANCES}}". Every sheet has a hint and an example. The system accepts sheet and column names in Ukrainian and in English, even mixed.
- Fill in the sheets. Write dates in Excel format or DD.MM.YYYY, areas in sotky or in m², shares as fractions such as 1/2.
- On the "{{ui:importSheets.MEMBERS}}" sheet you invent the "{{ui:importColumns.members.external_id}}" column yourself: the "{{ui:importSheets.OWNERSHIP}}" sheet uses it to find the member.
- The "{{ui:importSheets.OPENING_BALANCES}}" sheet carries debts over from your previous records: plot, charge type, amount, date and a reason (the reason is optional). One row per plot, charge type and date. Amounts have at most two decimal places.
- The "{{ui:importSheets.PLOTS}}" sheet has a "{{ui:importColumns.plots.water}}" column. The word "yes" connects the plot to the cooperative's water supply. The name of a common consumer's meter connects it to that source. "No" or an empty cell changes nothing. Import never disconnects a plot from water.

> [!important]
> You cannot change an owner by import. If a plot already has other owners, the system says so in the check, and the owner is changed on the [plot card](help:plot-card). Import does not overwrite an existing opening balance: reverse it first.

### The "{{ui:import.uploadTitle}}" panel

- The {{ui:import.chooseFile}} button opens the file picker. Next to it you see the name of the chosen file, and until then it says "{{ui:import.noFileChosen}}". Only a .xlsx file up to 10 MB is accepted, with up to 2,000 plots and 3,000 members.
- The {{ui:import.check}} button sends the file for checking. Nothing is saved until you confirm.

### The result of the check

The "{{ui:import.previewTitle}}" panel shows the file name and the time until which the file can be imported: a check is valid for 30 minutes. The "{{ui:import.countsCaption}}" table counts, for every kind of data, how many records fall into the columns "{{ui:import.created}}", "{{ui:import.updated}}" and "{{ui:import.unchanged}}". The kinds of data are: {{ui:import.entity.plots}}, {{ui:import.entity.members}}, {{ui:import.entity.ownerships}}, {{ui:import.entity.meters}}, {{ui:import.entity.waterConnections}} and {{ui:import.entity.openingBalances}}.

- If there are no problems, the system says: "{{ui:import.ready}}". Then the {{ui:import.run}} button saves everything together: either the whole file or nothing. Importing the same file again duplicates nothing: plots are found by number, members by e-mail (or by surname, first name and phone).
- If the file has no new or changed records, the system says: "{{ui:import.nothingToImport}}".
- If there are problems, the import button is disabled and the system says: "{{ui:import.runBlocked}}". The "{{ui:import.problemsCaption}}" table shows every problem: {{ui:import.colSheet}}, {{ui:import.colRow}}, {{ui:import.colColumn}} and the text of the problem. Fix them in the file and check it again.
- The {{ui:import.chooseAnother}} button resets the check and returns to choosing a file.

### After the import

The "{{ui:import.resultTitle}}" panel with the "{{ui:import.resultCaption}}" table shows what was saved. The {{ui:import.viewPlots}} and {{ui:import.viewMembers}} buttons lead to the [plot list](help:plots) and the [member list](help:members). The import is written to the [change log](help:audit). It cannot be undone in one step: correct the records on their cards.

### Import history

Below you see the "{{ui:importHistory.title}}" table: {{ui:importHistory.colDate}}, {{ui:importHistory.colFile}}, {{ui:importHistory.colBy}}, {{ui:importHistory.colStatus}}, {{ui:importHistory.colCounts}} and {{ui:importHistory.colProblems}}. The status can be "{{ui:importHistory.status.PREVIEW}}", "{{ui:importHistory.status.HAS_ERRORS}}", "{{ui:importHistory.status.COMMITTED}}", "{{ui:importHistory.status.STALE}}" or "{{ui:importHistory.status.EXPIRED}}". If no files have been uploaded yet, it says "{{ui:importHistory.empty}}".

### Error messages

| What is shown | What to do |
|---|---|
| {{ui:importError.FILE_TOO_LARGE}} | Make the file smaller or split it into several. |
| {{ui:importError.FILE_NOT_XLSX}} | Save the table as an Excel workbook (.xlsx). |
| {{ui:importError.IMPORT_LIMIT_EXCEEDED}} | Upload the file in parts. |
| {{ui:importError.IMPORT_HAS_ERRORS}} | Fix the problems from the table and check the file again. |
| {{ui:importError.IMPORT_NOTHING_TO_COMMIT}} | Nothing to do: everything is already in the system. |
| {{ui:importError.IMPORT_STALE}} | Somebody changed the data between the check and the import. Press "{{ui:import.uploadAgain}}" and check the file again. |
| {{ui:importError.IMPORT_EXPIRED}} | 30 minutes have passed. Upload the file again. |
| {{ui:importError.IMPORT_ALREADY_COMMITTED}} | This file is already imported; no need to repeat. |

The texts of the problems in the check table explain what is wrong in a row. The most common ones:

| What is shown | What to do |
|---|---|
| {{ui:importProblem.DATE_INVALID}} | Fix the date in the row. |
| {{ui:importProblem.PHONE_INVALID}} | Write the number in the format +380501234567. |
| {{ui:importProblem.CHANNEL_CONTACT_MISSING}} | Add an e-mail, a Telegram name or a Viber phone depending on the chosen delivery channel, or choose another channel. |
| {{ui:importProblem.UNKNOWN_PLOT}} | Add the plot to the "{{ui:importSheets.PLOTS}}" sheet or fix its number. |
| {{ui:importProblem.SHARES_NOT_ONE}} | Fix the shares: together they make one. |
| "The plot … already has other owners" | Change the owner on the plot card, not by import. |
| {{ui:importProblem.PLOT_HAS_OWNERSHIP_HISTORY}} | Change this value on the plot card. |
| "The plot already has an opening balance for this charge type" | Reverse the existing opening balance first. |

## Worked example

A cooperative moves from Excel to the system. The file must load plot 9 on Sadova street, its owner and the starting debt.

1. Press {{ui:import.downloadTemplate}} and open the file.
2. On the "{{ui:importSheets.PLOTS}}" sheet write: number 9, street "вул. Садова", area in sotky 15, has a building "yes".
3. On the "{{ui:importSheets.MEMBERS}}" sheet write the owner: Lytvynenko Svitlana Mykhailivna, e-mail.
4. On the "{{ui:importSheets.OWNERSHIP}}" sheet write plot 9, the key of this member, share 1 and date 01.06.2025.
5. On the "{{ui:importSheets.OPENING_BALANCES}}" sheet write plot 9, charge type "Membership fee", amount 600.00 and date 01.01.2026.
6. Save the file, press {{ui:import.chooseFile}} and {{ui:import.check}}.
7. The system shows: plots new 1, members new 1, owners new 1, opening balances new 1, no problems. Press {{ui:import.run}}.
8. Plot 9 has appeared in the plot list, and its account shows a debt of 600.00.

If you now load the same file again, the check will show "unchanged" in all rows, and there will be nothing to import.

## Screenshots

![The import screen: template, file choice and history](shot:imports)

## Related pages

- [Plots](help:plots)
- [Members](help:members)
- [Plot card: changing the owner](help:plot-card)
- [Change log](help:audit)
- [Getting started](help:getting-started)
