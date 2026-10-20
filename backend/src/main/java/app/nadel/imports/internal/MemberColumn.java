package app.nadel.imports.internal;

/** The columns of the Members sheet, in template order. */
enum MemberColumn implements SheetColumn {
    EXTERNAL_ID("external_id", true),
    LAST_NAME("last_name", true),
    FIRST_NAME("first_name", true),
    MIDDLE_NAME("middle_name", false),
    PHONES("phones", false),
    EMAIL("email", false),
    POSTAL_ADDRESS("postal_address", false),
    DELIVERY_CHANNEL("delivery_channel", false),
    TELEGRAM_USERNAME("telegram_username", false),
    VIBER_PHONE("viber_phone", false);

    private final String key;
    private final boolean required;

    MemberColumn(String key, boolean required) {
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
