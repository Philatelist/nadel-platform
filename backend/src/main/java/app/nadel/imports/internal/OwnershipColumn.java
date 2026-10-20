package app.nadel.imports.internal;

/** The columns of the Ownership sheet, in template order. All four are required. */
enum OwnershipColumn implements SheetColumn {
    PLOT_NUMBER("plot_number"),
    MEMBER_EXTERNAL_ID("member_external_id"),
    SHARE("share"),
    VALID_FROM("valid_from");

    private final String key;

    OwnershipColumn(String key) {
        this.key = key;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public boolean required() {
        return true;
    }
}
