# problem

Every error the API returns is `application/problem+json` (RFC 9457) in the shape of the `Problem` schema of
`api/openapi.yaml`: `type`, `title`, `status`, `detail`, a stable `code` and optional `errors[{field, code, message}]`.
Texts are fixed English sentences and never contain personal data, exception messages or SQL.

## Public API

| Type | Use |
|---|---|
| `ApiProblemException(status, code, detail, errors)` | Throw from any module for a business failure (409 `EMAIL_TAKEN` / `LAST_CHAIRMAN`, 404 `NOT_FOUND`, ...). `ApiProblemException.notFound()` is the shortcut for 404 |
| `FieldProblem(field, code, message)` | One invalid field in `errors` |
| `ProblemCodes` | Codes that are shared by several modules |
| `ProblemWriter` | Writes a problem straight to a servlet response. For security handlers and filters, which run before Spring MVC and cannot throw |

## Internal

`ProblemExceptionHandler` is a `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`. It maps
`ApiProblemException`, `AccessDeniedException` (403 `PERMISSION_DENIED`), Bean Validation failures
(400 `VALIDATION_FAILED` with field codes `REQUIRED`, `TOO_LONG`, `INVALID`), every other Spring MVC exception
(400 `MALFORMED_REQUEST`, 404, 405, 406, 415) and, as a catch-all, any unexpected exception to 500 `INTERNAL_ERROR`
without internals. The catch-all logs class names only, because persistence exception messages can contain row values.

Depends on nothing (besides the generated `Problem` model). `identity` and `members` depend on it.
