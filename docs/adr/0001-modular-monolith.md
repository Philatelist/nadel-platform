# 0001. Modular monolith on Spring Boot + Spring Modulith

**Status:** accepted

## Context
One developer working with AI agents, tens to hundreds of cooperatives, low load. We need clear boundaries between domains (money, plots, notifications) so that agents do not create everything-depends-on-everything coupling.

## Decision
A single Spring Boot service. Every top-level package `app.nadel.*` is a Spring Modulith module. Modules communicate through public APIs or domain events. `ModularityTests` verifies the boundaries in CI.

## Consequences
- One deployment, one database, simple debugging and transactions.
- Boundaries are verified automatically; a violation breaks the build.
- A module can be extracted into its own service later if there is a reason (for example, notifications).
