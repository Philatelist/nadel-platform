package app.nadel.reports.internal;

/** The stored file of one template version, as the download sends it. */
record TemplateFile(String fileName, byte[] content) {
}
