/**
 * Checks and cleaning of uploaded files (receipts in {@code treasury}, meter photos in {@code meters}), run before
 * anything is stored.
 * <p>
 * Public API: {@link app.nadel.files.FileKind} (JPEG, PNG, PDF with their media types),
 * {@link app.nadel.files.FileSniffer} (the type by the first bytes), {@link app.nadel.files.ImageMetadata} (metadata
 * stripped without re-encoding), {@link app.nadel.files.MalformedImageException} (an image that cannot be read) and
 * {@link app.nadel.files.UploadLimits}. The JPEG and PNG strippers are internal. The module has no tables, no
 * endpoints, no HTTP semantics and no dependency on another module: each caller maps a refusal to its own problem code
 * and field.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Uploaded files")
package app.nadel.files;
