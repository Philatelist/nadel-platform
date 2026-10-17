# Review log — 001-members-access

## Baseline failures

- Base commit: `39380d2` (`main`), command: `cd backend && ./mvnw -B verify`
- Result: 0 failing (7 tests: ModularityTests 2, TenantIsolationTests 5; BUILD SUCCESS, coverage checks met). Run with JDK 25 (`JAVA_HOME=/opt/homebrew/opt/openjdk@25/...`). First attempt with the default JDK 17 could not compile (`release version 25 not supported`).
