package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What a member search query stands for: names always, and at most an exact e-mail, a full phone or last 4 digits. */
class MemberSearchTermsTests {

    @Test
    void aQueryWithAnAtIsTheWholeEmailNormalised() {
        MemberSearchTerms terms = MemberSearchTerms.parse("  Olena.Petrenko@Example.COM ");

        assertThat(terms.text()).isEqualTo("Olena.Petrenko@Example.COM");
        assertThat(terms.email()).isEqualTo("olena.petrenko@example.com");
        assertThat(terms.phone()).isNull();
        assertThat(terms.last4()).isNull();
    }

    @Test
    void exactlyFourDigitsAreTheLastFourDigitsOfAPhone() {
        MemberSearchTerms terms = MemberSearchTerms.parse(" 4567 ");

        assertThat(terms.last4()).isEqualTo("4567");
        assertThat(terms.email()).isNull();
        assertThat(terms.phone()).isNull();
        assertThat(MemberSearchTerms.parse("456").last4()).isNull();
        assertThat(MemberSearchTerms.parse("45678").last4()).isNull();
        assertThat(MemberSearchTerms.parse("45 67").last4()).isNull();
    }

    @Test
    void aFullPhoneInAnyUsualSpellingIsItsInternationalForm() {
        for (String query : new String[] {"+380 50 123 45 67", "0501234567", "380501234567", "(050) 123-45-67"}) {
            assertThat(MemberSearchTerms.parse(query).phone()).as(query).isEqualTo("+380501234567");
        }
        assertThat(MemberSearchTerms.parse("050123").phone()).as("digits from the middle").isNull();
    }

    @Test
    void namesAndBlankQueriesStandForNoContact() {
        MemberSearchTerms name = MemberSearchTerms.parse("Шевченко");
        MemberSearchTerms blank = MemberSearchTerms.parse("   ");
        MemberSearchTerms none = MemberSearchTerms.parse(null);

        assertThat(name.text()).isEqualTo("Шевченко");
        assertThat(blank.text()).isEmpty();
        assertThat(none.text()).isEmpty();
        for (MemberSearchTerms terms : new MemberSearchTerms[] {name, blank, none}) {
            assertThat(terms.email()).isNull();
            assertThat(terms.phone()).isNull();
            assertThat(terms.last4()).isNull();
        }
    }
}
