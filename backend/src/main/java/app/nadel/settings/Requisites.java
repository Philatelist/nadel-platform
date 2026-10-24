package app.nadel.settings;

import org.jspecify.annotations.Nullable;

/**
 * The legal details of a cooperative, printed on reports and filled into templates. Public data of the legal entity,
 * not personal data. Every field is null when not set. The IBAN is a valid Ukrainian IBAN, without spaces and in upper
 * case. The ЄДРПОУ code is not here: it belongs to the cooperative itself ({@code tenancy.TenantSummary#edrpou}).
 */
public record Requisites(@Nullable String officialName, @Nullable String legalAddress, @Nullable String bankName,
        @Nullable String iban) {

    /** A cooperative that never entered its requisites. */
    public static final Requisites NONE = new Requisites(null, null, null, null);
}
