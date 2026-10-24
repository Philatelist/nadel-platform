/**
 * Safety checks for uploaded {@code .xlsx} workbooks, run before any workbook parser sees the file.
 * <p>
 * Public API: {@link app.nadel.xlsx.WorkbookGuard} (is the file an {@code .xlsx} and is it safe to unpack, with
 * configurable limits) and {@link app.nadel.xlsx.UnsafeWorkbookException} (the refusal, with a
 * {@link app.nadel.xlsx.UnsafeWorkbookReason}). The module has no tables, no endpoints, no HTTP semantics and no
 * dependency on another module: each caller maps the refusal to its own problem code ({@code imports} to
 * {@code FILE_NOT_XLSX}, report templates to {@code TEMPLATE_INVALID}).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Workbook safety")
package app.nadel.xlsx;
