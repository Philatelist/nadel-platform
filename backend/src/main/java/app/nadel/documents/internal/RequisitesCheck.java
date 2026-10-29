package app.nadel.documents.internal;

import app.nadel.settings.Requisites;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Whether the cooperative has the requisites an invoice needs (feature 016 §2.2, worked example 6): the IBAN (Settings
 * → Requisites) and the ЄДРПОУ (set by the platform operator on the cooperative). Pure. The codes are those of the
 * contract's {@code RequisiteField}, in this order.
 */
final class RequisitesCheck {

    static final String IBAN = "IBAN";
    static final String EDRPOU = "EDRPOU";

    private RequisitesCheck() {
    }

    /** The missing requisites ({@code IBAN}, {@code EDRPOU}); empty when an invoice can be built. */
    static List<String> missing(Requisites requisites, @Nullable String edrpou) {
        Objects.requireNonNull(requisites, "requisites");
        List<String> missing = new ArrayList<>(2);
        if (blank(requisites.iban())) {
            missing.add(IBAN);
        }
        if (blank(edrpou)) {
            missing.add(EDRPOU);
        }
        return List.copyOf(missing);
    }

    private static boolean blank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
