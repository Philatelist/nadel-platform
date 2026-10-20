package app.nadel.imports.internal;

/** The columns of the Plots sheet, in template order. */
enum PlotColumn implements SheetColumn {
    NUMBER("number", true),
    STREET("street", false),
    KIND("kind", false),
    AREA_M2("area_m2", false),
    AREA_SOTKY("area_sotky", false),
    CADASTRAL_NUMBER("cadastral_number", false),
    HAS_BUILDING("has_building", false),
    NOTES("notes", false);

    private final String key;
    private final boolean required;

    PlotColumn(String key, boolean required) {
        this.key = key;
        this.required = required;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public boolean required() {
        return required;
    }
}
