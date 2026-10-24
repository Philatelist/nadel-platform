package app.nadel.reports.internal;

import java.util.Optional;
import nadel.contract.model.ReportTemplateRowSet;

/**
 * The row sets a template row can repeat over (feature 013, section 4.1): a row whose placeholders are
 * {@code ${rows.<set>.<field>}} is copied once per row of the set. A sheet may have one repeating row, and a row may
 * not mix placeholders of two sets; {@link TemplateUploadChecks} refuses both at upload ({@code TWO_ROW_SETS}).
 */
enum TemplateRowSet {

    PLOTS("plots"),
    MONTHS("months"),
    METERS("meters"),
    WATER("water");

    /** The prefix every placeholder of a row set starts with. */
    static final String PREFIX = "rows.";

    private final String key;

    TemplateRowSet(String key) {
        this.key = key;
    }

    String key() {
        return key;
    }

    /** The row set as the API names it. */
    ReportTemplateRowSet contract() {
        return ReportTemplateRowSet.valueOf(name());
    }

    /**
     * The row set of a placeholder key ({@code rows.plots.number} is {@link #PLOTS}); empty for a single value or a
     * set the app does not know (such a placeholder is left in place as unknown when the template is filled).
     */
    static Optional<TemplateRowSet> of(String placeholderKey) {
        if (!placeholderKey.startsWith(PREFIX)) {
            return Optional.empty();
        }
        int end = placeholderKey.indexOf('.', PREFIX.length());
        if (end < 0) {
            return Optional.empty();
        }
        String set = placeholderKey.substring(PREFIX.length(), end);
        for (TemplateRowSet candidate : values()) {
            if (candidate.key.equals(set)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
