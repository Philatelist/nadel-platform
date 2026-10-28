package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
import app.nadel.members.MemberContacts;
import app.nadel.members.MemberDirectory;
import app.nadel.members.MemberRef;
import app.nadel.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** The public member lookup used by other modules: by id, by a part of a name, and the contacts of members. */
class MemberDirectoryTests extends IntegrationTest {

    private static final UUID BONDARENKO = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
    private static final UUID MOROZ = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000007");
    private static final UUID HONCHARENKO_ARCHIVED = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000020");
    private static final UUID MELNYK_OTHER_COOPERATIVE = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

    @Autowired
    MemberDirectory directory;

    @Autowired
    TransactionTemplate tx;

    @AfterEach
    void removeCappedMembers() {
        ownerJdbc().update("delete from member where last_name like 'Capzz%'");
        ownerJdbc().update("delete from member where last_name like 'Contactzz%'");
    }

    @Test
    void findsMembersWithTheirNamePartsAndArchivedFlag() {
        Map<UUID, MemberRef> found = as(DEMO_TENANT, () -> directory.find(List.of(BONDARENKO, HONCHARENKO_ARCHIVED)));

        assertThat(found).containsOnlyKeys(BONDARENKO, HONCHARENKO_ARCHIVED);
        MemberRef bondarenko = found.get(BONDARENKO);
        assertThat(bondarenko.lastName()).isEqualTo("Бондаренко");
        assertThat(bondarenko.firstName()).isEqualTo("Андрій");
        assertThat(bondarenko.middleName()).isEqualTo("Миколайович");
        assertThat(bondarenko.archived()).isFalse();
        assertThat(bondarenko.shortName()).isEqualTo("Бондаренко Андрій");
        assertThat(found.get(HONCHARENKO_ARCHIVED).archived()).isTrue();
    }

    @Test
    void unknownIdsAndMembersOfAnotherCooperativeAreAbsent() {
        UUID unknown = UUID.randomUUID();

        Map<UUID, MemberRef> found = as(DEMO_TENANT,
                () -> directory.find(List.of(MOROZ, unknown, MELNYK_OTHER_COOPERATIVE)));

        assertThat(found).containsOnlyKeys(MOROZ);
    }

    @Test
    void noIdsGiveNothing() {
        assertThat(as(DEMO_TENANT, () -> directory.find(List.of()))).isEmpty();
    }

    @Test
    void matchesAPartOfTheLastFirstOrMiddleNameIgnoringCase() {
        assertThat(as(DEMO_TENANT, () -> directory.idsMatchingName("БОНДАР"))).containsExactly(BONDARENKO);
        assertThat(as(DEMO_TENANT, () -> directory.idsMatchingName("галин"))).containsExactly(MOROZ);
        // the patronymic of Bondarenko is also the patronymic of Chernenko (28), who has no other match
        assertThat(as(DEMO_TENANT, () -> directory.idsMatchingName("миколайов")))
                .containsExactlyInAnyOrder(BONDARENKO, UUID.fromString("aaaaaaaa-0000-0000-0000-000000000028"));
    }

    @Test
    void archivedMembersAreIncluded() {
        assertThat(as(DEMO_TENANT, () -> directory.idsMatchingName("Гончаренко")))
                .containsExactly(HONCHARENKO_ARCHIVED);
    }

    @Test
    void membersOfAnotherCooperativeAreNeverMatched() {
        assertThat(as(DEMO_TENANT, () -> directory.idsMatchingName("Мельник"))).isEmpty();
        assertThat(as(OTHER_TENANT, () -> directory.idsMatchingName("Мельник")))
                .containsExactly(MELNYK_OTHER_COOPERATIVE);
    }

    @Test
    void wildcardsAndEscapeCharactersAreMatchedLiterally() {
        for (String text : List.of("%", "_", "!", "Bond_renko", "Bond%", "!%")) {
            assertThat(as(DEMO_TENANT, () -> directory.idsMatchingName(text))).as(text).isEmpty();
        }
    }

    @Test
    void aBlankTextMatchesNobody() {
        assertThat(as(DEMO_TENANT, () -> directory.idsMatchingName(""))).isEmpty();
        assertThat(as(DEMO_TENANT, () -> directory.idsMatchingName("   "))).isEmpty();
    }

    @Test
    void theResultIsCappedAndOrdered() {
        ownerJdbc().update("insert into member (id, tenant_id, last_name, first_name) "
                + "select gen_random_uuid(), ?, 'Capzz' || lpad(n::text, 3, '0'), 'Test' "
                + "from generate_series(1, 205) as n", DEMO_TENANT);

        List<UUID> ids = as(DEMO_TENANT, () -> directory.idsMatchingName("capzz"));

        assertThat(ids).hasSize(MemberDirectory.MAX_NAME_MATCHES).doesNotHaveDuplicates();
        Set<UUID> expectedFirst = Set.copyOf(ownerJdbc().queryForList(
                "select id from member where last_name like 'Capzz%' order by last_name limit 200", UUID.class));
        assertThat(ids).containsExactlyInAnyOrderElementsOf(expectedFirst);
    }

    @Test
    void contactsGiveTheNamesPhonesAndEmailOfTheCooperativesOwnMembersOnly() {
        UUID both = contactMember(DEMO_TENANT, "Contactzz", "Олена", "Петрівна", "olena@contacts.test",
                "+380501112233", "+380671112233");
        UUID none = contactMember(DEMO_TENANT, "Contactzz-2", "Іван", null, null);
        UUID theirs = contactMember(OTHER_TENANT, "Contactzz-3", "Петро", null, "petro@contacts.test",
                "+380931112233");
        UUID unknown = UUID.randomUUID();

        Map<UUID, MemberContacts> found = inTransaction(DEMO_TENANT,
                () -> directory.contacts(List.of(both, none, theirs, unknown, both)));

        assertThat(found).containsOnlyKeys(both, none);
        MemberContacts olena = found.get(both);
        assertThat(olena.phones()).containsExactly("+380501112233", "+380671112233");
        assertThat(olena.email()).isEqualTo("olena@contacts.test");
        assertThat(olena.fullName()).isEqualTo("Contactzz Олена Петрівна");
        assertThat(olena.shortName()).isEqualTo("Contactzz Олена");
        assertThat(olena.archived()).isFalse();
        MemberContacts ivan = found.get(none);
        assertThat(ivan.phones()).isEmpty();
        assertThat(ivan.email()).isNull();
        assertThat(ivan.fullName()).isEqualTo("Contactzz-2 Іван");
        assertThat(inTransaction(OTHER_TENANT, () -> directory.contacts(List.of(both, theirs))))
                .containsOnlyKeys(theirs);
        assertThat(inTransaction(DEMO_TENANT, () -> directory.contacts(List.of()))).isEmpty();
    }

    @Test
    void contactsRunOnlyInTheCallersTransaction() {
        assertThatThrownBy(() -> as(DEMO_TENANT, () -> directory.contacts(List.of(BONDARENKO))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private UUID contactMember(UUID tenant, String lastName, String firstName, String middleName, String email,
            String... phones) {
        return newMember(tenant, lastName, firstName, middleName, email, phones);
    }

    private <T> T inTransaction(UUID tenant, java.util.function.Supplier<T> action) {
        return TenantContext.runAs(tenant, () -> tx.execute(status -> action.get()));
    }

    private static <T> T as(UUID tenant, java.util.function.Supplier<T> action) {
        return TenantContext.runAs(tenant, action);
    }
}
