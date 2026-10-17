# Review log — 001-members-access

## Baseline failures

- Base commit: `39380d2` (`main`), command: `cd backend && ./mvnw -B verify`
- Result: 0 failing (7 tests: ModularityTests 2, TenantIsolationTests 5; BUILD SUCCESS, coverage checks met). Run with JDK 25 (`JAVA_HOME=/opt/homebrew/opt/openjdk@25/...`). First attempt with the default JDK 17 could not compile (`release version 25 not supported`).

## Slice 1 — Sign-in is linked to a member; My account shows own card, roles and permissions

| Round | Range / commits | Verdict | Major issues | Notes |
|---|---|---|---|---|
| 1 | `caf2a12..f76322d` | MAJOR_ISSUES | M1 member-lookup failure during sign-in likely answered 401 instead of 500 (default bearer failure handler rethrows `AuthenticationServiceException`); no test | minor: operator gets 404 not 403 on `GET /members/{id}`; operator token keeps a `tenant_id` claim and `/plots` has no `PERM_*` check (pre-existing, ADR 0008); `log.debug(..., ex)` in the catch-all may log row values; `/me` email from claim |
