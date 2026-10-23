package app.nadel.imports.internal;

/** The columns of the Opening balances sheet (feature 011), in template order. All but the reason are required. */
enum OpeningBalanceColumn implements SheetColumn {
    PLOT_NUMBER("plot_number", true),
    CHARGE_TYPE("charge_type", true),
    AMOUNT("amount", true),
    DATE("date", true),
    REASON("reason", false);

    private final String key;
    private final boolean required;

    OpeningBalanceColumn(String key, boolean required) {
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
