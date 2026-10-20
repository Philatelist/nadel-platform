# Roadmap

The map comes late on purpose: plots exist as data from phase 1 (number, street, area, cadastral number, owners), while their polygons and the map view arrive in phase 6, once the source of cadastral data is clear. Until then debt status is shown in the plots list.

| Phase | Scope | Status |
|---|---|---|
| 0. Foundation | Monorepo, CI, Keycloak, RLS, web and mobile shells, CLAUDE.md, ADRs, AWOS product and architecture | done |
| 1. Plots and members | Plots as data without polygons (number, street, area, cadastral number), members, co-owners with shares, ownership history, configurable admin roles, Excel import of plots and members | done |
| 2. Tariffs and charges | 009 charge types and tariffs (chairman sets tariffs, start dates, bases, which plots they apply to); 010 meters and readings (meter tree, replacement, electrician screen on the web); 011 ledger, charges and period closing (losses per node, common consumers split); 012 retroactive recalculation; 013 reports for the board and report templates (tax report placeholder) | — |
| 3. Payments | Manual entry, cash desk receipts, bank statement import (CSV/XLSX), matching hints, allocation, prepayments, debt status in the plots list | — |
| 4. Invoices and delivery | PDF + NBU QR, debt certificate, email, Telegram, Viber, delivery statuses | — |
| 5. Member account | Web account, mobile apps, meter reading submission with photo, push | — |
| 6. Map | Import of plot polygons (DXF, State Land Cadastre XML, GeoJSON, KML, Shapefile) with coordinate system conversion (UCS-2000, SK-63, local systems to WGS84), import preview, matching polygons to plots, polygon editor, cooperative boundary, georeferenced image underlay, map colored by debt | — |
| 6a. Deployment | Staging and production servers, release and rollback workflows, backups, monitoring, runbook (`docs/features/backlog/deployment.md`, ADR 0010). Done last: the product is finished locally first | — |
| 7. SaaS | Cooperative onboarding, subscriptions and billing, privacy policy (backups and monitoring arrive with 6a) | — |
| 8. Automation | Bank API and auto-matching, online payment, debtor reminders, Telegram bot, meter photo OCR, smart meters, meeting reports and voting, AI treasurer assistant | — |
