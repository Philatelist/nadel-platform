---
id: certificates
title: "Certificate register"
description: "The register of issued certificates on the state of settlements for a plot: number, date, debt, PDF, and annulling a certificate with a reason."
kind: screen
routes:
  - /documents/certificates
permissions:
  - CHARGES_VIEW
section: documents-delivery
order: 10
shots:
  - name: certificates-register
    user: chairman
    path: /documents/certificates
---

## What the screen is for

A certificate on the state of settlements says how much a plot owes the cooperative on a certain date, or that there is no debt. It is asked for, for example, when a plot is sold. This screen is the register of all issued certificates. Here you find them, open their PDF and annul wrong ones.

The certificate itself is issued on the plot card, on the "Certificate" tab, see [Plot card](help:plot-card).

## Who sees it

The register is seen by those who have "{{ui:permission.CHARGES_VIEW.name}}". Only those who have "{{ui:permission.CERTIFICATES_ISSUE.name}}" can issue and annul certificates: by default the chairman. The button "{{ui:certificates.annul}}" is only theirs. A member does not see certificates.

## Every element

### The filter "{{ui:certificates.filters.status}}"

Shows "{{ui:certificates.filters.all}}", "{{ui:certificates.filters.ISSUED}}" or "{{ui:certificates.filters.ANNULLED}}". When no certificates match, it says "{{ui:certificates.nothingFound}}", and if there are none at all, "{{ui:certificates.empty}}".

### The table

| Column | What is in it |
|---|---|
| {{ui:certificates.columns.number}} | A number like "Д-2026-1". Numbers run in sequence within the cooperative and the year, with no gaps and no repeats. |
| {{ui:certificates.columns.plot}} | The plot number; the link leads to its card. |
| {{ui:certificates.columns.asOf}} | The date as of which the certificate was made. |
| {{ui:certificates.columns.debt}} | The total debt on that date. |
| {{ui:certificates.columns.issuedBy}} and {{ui:certificates.columns.issuedAt}} | Who issued it and when. |
| {{ui:certificates.columns.status}} | "{{ui:certificates.status.ISSUED}}" or "{{ui:certificates.status.ANNULLED}}". |
| {{ui:certificates.columns.actions}} | "{{ui:certificates.download}}" (PDF) and "{{ui:certificates.annul}}". |

### What is in a certificate

A certificate contains the cooperative's details, the plot with its area and current owners, the debt by type and in total, and the prepayment on the date. These are the same figures as in the report "{{ui:reports.plot-balances.title}}". If the total debt is 0.00, it says "No debt"; otherwise "The debt is …". It has the name and position of the person who issued it, the date of issue and a place for a signature and a stamp. A certificate can be issued for a plot without an owner, but not for common land or for an archived plot. The document is always in Ukrainian, because it is an official document.

### Annulling

The button "{{ui:certificates.annul}}" opens the window "{{ui:certificates.annulTitle}}". The window warns: "{{ui:certificates.annulText}}" Enter the "{{ui:certificates.annulReasonLabel}}" (required) and press "{{ui:certificates.annulConfirm}}".

An annulled certificate stays in the register with the reason and the name of the person who annulled it ("{{ui:certificates.annulledBy}}"). Its PDF carries the mark "ANNULLED". It cannot be annulled a second time ("{{ui:problem.CERTIFICATE_ALREADY_ANNULLED}}"), and its number is not reused.

> [!important]
> An issued certificate does not change. A mistake is corrected by a new certificate, and the old one is annulled with a reason. An annulment cannot be undone.

## Worked example

The owner of plot 9 asks for a certificate. The chairman opens the card of plot 9, the "Certificate" tab, chooses the date and presses "{{ui:certificates.issue}}". The system shows "Certificate issued: Д-2026-1" and downloads the PDF. The next day it turns out that the date was wrong.

1. The chairman opens the "Certificate register" and finds certificate Д-2026-1.
2. He presses "{{ui:certificates.annul}}", writes the reason "Wrong date" and confirms.
3. The certificate has the status "{{ui:certificates.status.ANNULLED}}". The chairman issues a new certificate with the right date (its number is Д-2026-2).

## Screenshots

![The certificate register with the filter and the PDF and Annul buttons](shot:certificates-register)

## Related pages

- [Plot card](help:plot-card)
- [Reports](help:reports)
- [How the reports work](help:how-reports)
