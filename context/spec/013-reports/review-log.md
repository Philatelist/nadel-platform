# Review log — 013 reports

## Baseline failures

Backend `./mvnw verify` on 6c121fb (detached worktree): green, exit 0, no failing tests. Client checks and e2e were not run for the baseline.

## Slice reviews


### Slice 1 — Permissions, requisites and tenant EDRPOU (c4057b0)
- Round 1: PASS. Backend slice tests 335 green on the reviewer side.
- Minor notes (not fixed): `RequisitesPanel.tsx:7` does not strip inner IBAN spaces in the `changed` comparison, so Save may stay enabled after a save with spaces; `SettingsService.Change` has ten positional parameters; `V20__reports.sql:33` slot CHECK passes NULL by design and could carry a comment.

