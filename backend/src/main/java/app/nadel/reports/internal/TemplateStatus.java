package app.nadel.reports.internal;

/**
 * The status of a stored report template ({@code report_template.status}). {@code NOT_ADDED} of the contract is the
 * empty tax report slot, which is never stored.
 */
enum TemplateStatus {
    /** Generated from and takes new versions. */
    ACTIVE,
    /** Hidden from generation, takes no new version; its versions stay downloadable. */
    ARCHIVED
}
