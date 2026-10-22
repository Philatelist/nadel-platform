package app.nadel.imports.internal;

/** The columns of the Meters sheet, in template order. All five are required. */
enum MeterColumn implements SheetColumn {
    PLOT_NUMBER("plot_number"),
    RESOURCE("resource"),
    SERIAL("serial"),
    INSTALLED_ON("installed_on"),
    INITIAL_READING("initial_reading");

    private final String key;

    MeterColumn(String key) {
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
