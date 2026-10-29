package app.nadel.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.settings.Requisites;
import org.junit.jupiter.api.Test;

/**
 * The requisites an invoice needs (feature 016 §2.2, worked example 6 of the requirement §6: «IBAN missing in
 * requisites → 409 REQUISITES_MISSING with iban»): the IBAN of the settings and the ЄДРПОУ of the cooperative.
 */
class RequisitesCheckTests {

    private static final String IBAN = "UA850000000000000000000123456";

    @Test
    void example6WithoutTheIbanTheIbanIsMissing() {
        Requisites requisites = new Requisites("СТ «Сад»", "вул. Садова, 1", "АТ «Банк»", null);

        assertThat(RequisitesCheck.missing(requisites, "12345678")).containsExactly("IBAN");
    }

    @Test
    void withoutTheEdrpouTheEdrpouIsMissing() {
        Requisites requisites = new Requisites(null, null, null, IBAN);

        assertThat(RequisitesCheck.missing(requisites, null)).containsExactly("EDRPOU");
        assertThat(RequisitesCheck.missing(requisites, "  ")).containsExactly("EDRPOU");
    }

    @Test
    void withNeitherBothAreMissingInThisOrder() {
        assertThat(RequisitesCheck.missing(Requisites.NONE, null)).containsExactly("IBAN", "EDRPOU");
        assertThat(RequisitesCheck.missing(new Requisites(null, null, null, " "), "")).containsExactly("IBAN",
                "EDRPOU");
    }

    @Test
    void theIbanAndTheEdrpouAreEnoughTheOtherRequisitesAreOptional() {
        assertThat(RequisitesCheck.missing(new Requisites(null, null, null, IBAN), "12345678")).isEmpty();
    }
}
