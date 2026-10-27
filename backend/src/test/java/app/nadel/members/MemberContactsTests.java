package app.nadel.members;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** How a report names a member and the copy of the phones. */
class MemberContactsTests {

    @Test
    void theFullNameAddsThePatronymicOnlyWhenThereIsOne() {
        UUID id = UUID.randomUUID();

        assertThat(new MemberContacts(id, "Шевченко", "Олена", "Петрівна", List.of(), null, false).fullName())
                .isEqualTo("Шевченко Олена Петрівна");
        assertThat(new MemberContacts(id, "Шевченко", "Олена", " ", List.of(), null, false).fullName())
                .isEqualTo("Шевченко Олена");
        assertThat(new MemberContacts(id, "Шевченко", "Олена", null, List.of(), null, false).shortName())
                .isEqualTo("Шевченко Олена");
    }

    @Test
    void thePhonesAreCopiedAndMissingPhonesAreEmpty() {
        List<String> phones = new ArrayList<>(List.of("+380501112233"));
        MemberContacts contacts = new MemberContacts(UUID.randomUUID(), "Шевченко", "Олена", null, phones,
                "olena@example.test", false);
        phones.clear();

        assertThat(contacts.phones()).containsExactly("+380501112233");
        assertThat(new MemberContacts(UUID.randomUUID(), "Шевченко", "Олена", null, null, null, true).phones())
                .isEmpty();
    }
}
