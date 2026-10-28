# problem

Every error the API returns is `application/problem+json` (RFC 9457) in the shape of the `Problem` schema of
`api/openapi.yaml`: `type`, `title`, `status`, `detail`, a stable `code`, optional `errors[{field, code, message}]` and,
only for `REQUISITES_MISSING` (feature 016), `missing` (the codes of the requisites that are not set: `IBAN`, `EDRPOU`;
built with `ApiProblemException.requisitesMissing(missing)`).
Texts are fixed English sentences and never contain personal data, exception messages or SQL.

## Public API

| Type | Use |
|---|---|
| `ApiProblemException(status, code, detail, errors)` | Throw from any module for a business failure (409 `EMAIL_TAKEN` / `LAST_CHAIRMAN` / `ROLE_NAME_TAKEN` / `ROLE_IN_USE` / `BASE_ROLE_IMMUTABLE`, 404 `NOT_FOUND`, 404 `PLOT_NOT_FOUND`, ...). `ApiProblemException.notFound()` is the shortcut for 404 |
| `FieldProblem(field, code, message)` | One invalid field in `errors` |
| `ProblemCodes` | Problem codes (`VALIDATION_FAILED`, `MALFORMED_REQUEST`, `UNAUTHENTICATED`, `MEMBER_NOT_LINKED`, `PERMISSION_DENIED`, `NOT_FOUND`, `PLOT_NOT_FOUND` (404 of the plot-scoped meter endpoints a member may call: an unknown plot, one of another cooperative and one the caller does not own get the same answer), `INTERNAL_ERROR` and the 409 codes) and field codes (`REQUIRED`, `TOO_LONG`, `INVALID`, `PHONE_INVALID`, `CHANNEL_CONTACT_MISSING`). Clients translate them by code; add new codes here and to the i18n catalogs |
| `ValidationProblems.fieldProblem(FieldError)` | Maps a Bean Validation violation to its field code (`NotNull`/`NotBlank`/`NotEmpty` to `REQUIRED`, `Size`/`Max`/`Length` to `TOO_LONG` (`REQUIRED` for an empty string or list), a `Pattern` on a share to `SHARE_INVALID`, on a tariff rate to `RATE_INVALID`, on a meter reading (`*Reading`, `value`) to `METER_VALUE_INVALID`, anything else `INVALID`). The exception handler uses it, and other modules use it to validate a value exactly as the API does |
| `ConstraintViolations.violates(failure, constraint)` | Whether a persistence failure is a unique (23505) or exclusion (23P01) violation of the named constraint, also when it is the failure of a flushed JDBC batch (the error is then the `getNextException()` of a `BatchUpdateException`, not its cause) |
| `ProblemWriter` | Writes a problem straight to a servlet response. For security handlers and filters, which run before Spring MVC and cannot throw |

## Internal

`ProblemExceptionHandler` is a `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`. It maps
`ApiProblemException`, `AccessDeniedException` (403 `PERMISSION_DENIED`), Bean Validation failures
(400 `VALIDATION_FAILED` with field codes `REQUIRED`, `TOO_LONG`, `INVALID`; an empty string that violates a minimum length is `REQUIRED`), every other Spring MVC exception
(400 `MALFORMED_REQUEST`, 404, 405, 406, 415), an upload over the multipart limit (413 `FILE_TOO_LARGE`) and, as a catch-all,
any unexpected exception to 500 `INTERNAL_ERROR` without internals. The catch-all logs class names only, because persistence exception messages can contain row values.

Depends on nothing (besides the generated `Problem` model). `identity`, `members` and `audit` depend on it.
