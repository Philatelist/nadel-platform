package app.nadel.reports.internal;

/** A report written as a workbook: its ASCII file name {@code <code>_<from>_<to>.xlsx} and its bytes. */
record ReportFile(String fileName, byte[] content) {
}
