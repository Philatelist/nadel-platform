package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.IntegrationTest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** member_phone_index obeys RLS; the application role may select, insert and delete but never update or truncate. */
class MemberPhoneIndexTenantIsolationTests extends IntegrationTest {

    static final String INSERT = "insert into member_phone_index (tenant_id, member_id, phone_bidx, last4_bidx) "
            + "values (?, ?, ?, ?)";

    @Test
    void tenantSeesOnlyItsOwnRowsAndCannotLookUpAnotherTenantsBlindIndex() {
        UUID demoMember = newMember(DEMO_TENANT, "isolation-demo@example.com");
        UUID otherMember = newMember(OTHER_TENANT, "isolation-other@example.com");
        byte[] demoPhone = bidx(1);
        byte[] otherPhone = bidx(2);
        inTenant(DEMO_TENANT, sql -> sql.update(INSERT, DEMO_TENANT, demoMember, demoPhone, bidx(3)));
        inTenant(OTHER_TENANT, sql -> sql.update(INSERT, OTHER_TENANT, otherMember, otherPhone, bidx(4)));

        // the demo members have index rows of their own (demo contacts), so only the two fixtures are looked at
        String fixtures = "select distinct member_id from member_phone_index where member_id in (?, ?)";
        List<UUID> demo = inTenant(DEMO_TENANT, sql ->
                sql.queryForList(fixtures, UUID.class, demoMember, otherMember));
        List<UUID> other = inTenant(OTHER_TENANT, sql ->
                sql.queryForList(fixtures, UUID.class, demoMember, otherMember));
        Integer foundFromDemo = inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select count(*) from member_phone_index where phone_bidx = ? or last4_bidx = ?", Integer.class,
                otherPhone, bidx(4)));
        Integer foundOwn = inTenant(DEMO_TENANT, sql -> sql.queryForObject(
                "select count(*) from member_phone_index where phone_bidx = ?", Integer.class, demoPhone));

        assertThat(demo).containsExactly(demoMember);
        assertThat(other).containsExactly(otherMember);
        assertThat(foundFromDemo).isZero();
        assertThat(foundOwn).isEqualTo(1);
        assertThat(ownerJdbc().queryForObject("select count(*) from member_phone_index where member_id in (?, ?)",
                Integer.class, demoMember, otherMember)).isEqualTo(2);
    }

    @Test
    void anotherTenantDoesNotSeeTheRow() {
        UUID member = newMember(DEMO_TENANT, "isolation-none@example.com");
        inTenant(DEMO_TENANT, sql -> sql.update(INSERT, DEMO_TENANT, member, bidx(5), bidx(6)));

        assertThat(ownerJdbc().queryForObject("select count(*) from member_phone_index where member_id = ?",
                Integer.class, member)).isEqualTo(1);
        Integer seenByOther = inTenant(OTHER_TENANT, sql -> sql.queryForObject(
                "select count(*) from member_phone_index where member_id = ?", Integer.class, member));
        assertThat(seenByOther).isZero();
    }

    @Test
    void cannotInsertRowForAnotherTenant() {
        UUID otherMember = newMember(OTHER_TENANT, "isolation-cross@example.com");

        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql ->
                sql.update(INSERT, OTHER_TENANT, otherMember, bidx(7), bidx(8))))
                .isInstanceOf(DataAccessException.class);
        // the composite key also refuses a row of the current tenant that points at a member of another one
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql ->
                sql.update(INSERT, DEMO_TENANT, otherMember, bidx(7), bidx(8))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void applicationRoleCanDeleteButNeverUpdateOrTruncate() {
        UUID member = newMember(DEMO_TENANT, "isolation-privileges@example.com");
        inTenant(DEMO_TENANT, sql -> sql.update(INSERT, DEMO_TENANT, member, bidx(9), bidx(10)));

        assertThat(privilege("SELECT")).isTrue();
        assertThat(privilege("INSERT")).isTrue();
        assertThat(privilege("DELETE")).isTrue();
        assertThat(privilege("UPDATE")).isFalse();
        assertThat(privilege("TRUNCATE")).isFalse();
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql -> sql.update(
                "update member_phone_index set last4_bidx = ? where member_id = ?", bidx(11), member)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
        Integer deleted = inTenant(DEMO_TENANT, sql -> sql.update(
                "delete from member_phone_index where member_id = ?", member));
        assertThat(deleted).isEqualTo(1);
    }

    @Test
    void indexValuesMustBeThirtyTwoBytes() {
        UUID member = newMember(DEMO_TENANT, "isolation-length@example.com");

        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql ->
                sql.update(INSERT, DEMO_TENANT, member, new byte[31], bidx(12))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> inTenant(DEMO_TENANT, sql ->
                sql.update(INSERT, DEMO_TENANT, member, bidx(12), new byte[33])))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void deletingTheMemberRemovesItsIndexRows() {
        UUID member = UUID.randomUUID();
        ownerJdbc().update("insert into member (id, tenant_id, last_name, first_name) values (?, ?, 'Cascade', 'Test')",
                member, DEMO_TENANT);
        ownerJdbc().update(INSERT, DEMO_TENANT, member, bidx(13), bidx(14));

        ownerJdbc().update("delete from member where id = ?", member);

        assertThat(ownerJdbc().queryForObject("select count(*) from member_phone_index where member_id = ?",
                Integer.class, member)).isZero();
    }

    private Boolean privilege(String privilege) {
        return ownerJdbc().queryForObject("select has_table_privilege('nadel_app', 'member_phone_index', ?)",
                Boolean.class, privilege);
    }

    private static byte[] bidx(int seed) {
        byte[] value = new byte[32];
        Arrays.fill(value, (byte) seed);
        return value;
    }
}
