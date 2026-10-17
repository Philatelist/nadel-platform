# Review log — 001-members-access

## Baseline failures

- Base commit: `39380d2` (`main`), command: `cd backend && ./mvnw -B verify`
- Result: could not run: compilation failed before any test ran — `release version 25 not supported`; the machine has JDK 8, 17, 19–21 and 23 but no JDK 25 (`java -version` 17.0.14).
