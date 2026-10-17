/**
 * Problem Details (RFC 9457) for every error the API returns.
 * <p>
 * Public API: {@link app.nadel.problem.ApiProblemException} (throw it from any module),
 * {@link app.nadel.problem.FieldProblem} and {@link app.nadel.problem.ProblemWriter} (for servlet filters and
 * security handlers, which run outside Spring MVC). The exception handler is internal.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Error responses")
package app.nadel.problem;
