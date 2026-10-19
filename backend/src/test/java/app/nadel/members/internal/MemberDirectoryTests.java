package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.IntegrationTest;
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

/** The public member lookup used by other modules: by id, and by a part of a name. */
class MemberDirectoryTests extends IntegrationTest {

    private static final UUID BONDARENKO = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
    private static final UUID MOROZ = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000007");
    private static final UUID HONCHARENKO_ARCHIVED = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000020");
    private static final UUID MELNYK_OTHER_COOPERATIVE = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

    @Autowired
    MemberDirectory directory;

    @AfterEach
    void removeCappedMembers() {
        ownerJdbc().update("delete from member where last_name like 'Capzz%'");
    }

    @Test
    void findsMembersWithTheirNamePartsAndArchivedFlag() {
        Map<UUID, MemberRef> found = as(DEMO_TENANT, () -> directory.find(List.of(BONDARENKO, HONCHARENKO_ARCHIVED)));

        assertThat(found).containsOnlyKeys(BONDARENKO, HONCHARENKO_ARCHIVED);
        MemberRef bondarenko = found.get(BONDARENKO);
        assertThat(bondarenko.lastName()).isEqualTo("Bondarenko");
        assertThat(bondarenko.firstName()).isEqualTo("Andrii");
        assertThat(bondarenko.middleName()).isEqualTo("Mykolaiovych");
        assertThat(bondarenko.archived()).isFalse();
        assertThat(bondarenko.shortName()).isEqualTo("Bondarenko Andrii");
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
        assertThat(as(DEMO_TENANT, () -> directory.idsMatchingName("BONDAR"))).containsExactly(BONDARENKO);
        assertThat(as(DEMO_TENANT, () -> directory.idsMatchingName("halyn"))).containsExactly(MOROZ);
        // the patronymic of Bondarenko is also the patronymic of Chernenko (28), who has no other match
        assertThat(as(DEMO_TENANT, () -> directory.idsMatchingName("mykolaiov")))
                .containsExactlyInAnyOrder(BONDARENKO, UUID.fromString("aaaaaaaa-0000-0000-0000-000000000028"));
    }

    @Test
    void archivedMembersAreIncluded() {
        assertThat(as(DEMO_TENANT, () -> directory.idsMatchingName("Honcharenko")))
                .containsExactly(HONCHARENKO_ARCHIVED);
    }

    @Test
    void membersOfAnotherCooperativeAreNeverMatched() {
        assertThat(as(DEMO_TENANT, () -> directory.idsMatchingName("Melnyk"))).isEmpty();
        assertThat(as(OTHER_TENANT, () -> directory.idsMatchingName("Melnyk")))
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
        ownerJdbc().update("insert into member (id, tenant_id, last_name, first_name, phones) "
                + "select gen_random_uuid(), ?, 'Capzz' || lpad(n::text, 3, '0'), 'Test', '{}' "
                + "from generate_series(1, 205) as n", DEMO_TENANT);

        List<UUID> ids = as(DEMO_TENANT, () -> directory.idsMatchingName("capzz"));

        assertThat(ids).hasSize(MemberDirectory.MAX_NAME_MATCHES).doesNotHaveDuplicates();
        Set<UUID> expectedFirst = Set.copyOf(ownerJdbc().queryForList(
                "select id from member where last_name like 'Capzz%' order by last_name limit 200", UUID.class));
        assertThat(ids).containsExactlyInAnyOrderElementsOf(expectedFirst);
    }

    private static <T> T as(UUID tenant, java.util.function.Supplier<T> action) {
        return TenantContext.runAs(tenant, action);
    }
}
