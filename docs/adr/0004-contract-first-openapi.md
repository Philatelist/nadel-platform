# 0004. Contract-first API with OpenAPI

**Status:** accepted

## Context
Three clients (web, iOS, Android) and the server are developed by agents. Type drift between them is the main source of bugs.

## Decision
`api/openapi.yaml` is the source of truth. Server: openapi-generator produces controller interfaces and DTOs (`interfaceOnly`); controllers implement them. Clients: orval produces a typed client and TanStack Query hooks in `packages/api-client`.

## Consequences
- A contract change breaks compilation on whichever side has not been updated.
- Money in the contract is a string matching `^-?\d+\.\d{2}$`.
- Generated code is never edited by hand. CI checks that the TS client matches the contract.
