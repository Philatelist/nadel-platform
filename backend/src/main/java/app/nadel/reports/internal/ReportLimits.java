package app.nadel.reports.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The size limits of a report. A sheet of more than {@link #rowCap()} rows is refused (422 {@code REPORT_TOO_LARGE});
 * the preview shows the first {@value #PREVIEW_ROWS} rows of each sheet. The cap is 20 000 (functional spec 2.2) and
 * can be set with the property {@code nadel.reports.row-cap}; the integration tests lower it for one test with
 * {@link #rowCap(int)} instead of starting another application context.
 */
@Component
class ReportLimits {

    static final int PREVIEW_ROWS = 200;
    static final int DEFAULT_ROW_CAP = 20_000;

    private volatile int rowCap;

    ReportLimits(@Value("${nadel.reports.row-cap:" + DEFAULT_ROW_CAP + "}") int rowCap) {
        rowCap(rowCap);
    }

    int rowCap() {
        return rowCap;
    }

    /** Sets the cap; for tests, which restore {@link #DEFAULT_ROW_CAP} afterwards. */
    void rowCap(int rowCap) {
        if (rowCap < 1) {
            throw new IllegalArgumentException("The row cap is at least 1");
        }
        this.rowCap = rowCap;
    }
}
