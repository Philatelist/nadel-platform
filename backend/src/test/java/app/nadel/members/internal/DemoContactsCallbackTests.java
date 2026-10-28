package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import app.nadel.privacy.IndexKind;
import app.nadel.privacy.PersonalDataCipher;
import app.nadel.tenancy.TenantContext;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The demo members get their contacts from {@code db/demo/demo-contacts.json} through {@link DemoContactsCallback}
 * right after {@code R__demo_data.sql}: sealed, with the e-mail blind index the demo users sign in by, the delivery
 * channel and the phone index. The shared test database was migrated with the {@code db/demo} location, so the
 * callback has run on it. The test that writes the contacts again runs last, so the others see what the migration
 * wrote.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoContactsCallbackTests extends IntegrationTest {

    static final UUID DEMO_CHAIRMAN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @Autowired
    DemoContactsCallback callback;

    @Autowired
    PersonalDataCipher cipher;

    @Autowired
    MockMvc mvc;

    @Test
    @Order(1)
    void everyDemoMemberHoldsTheContactsAndChannelOfTheFile() {
        List<DemoContactsCallback.DemoContacts> entries = DemoContactsCallback.read();

        assertThat(entries).hasSize(37);
        for (DemoContactsCallback.DemoContacts entry : entries) {
            UUID tenant = ownerJdbc().queryForObject("select tenant_id from member where id = ?", UUID.class,
                    entry.memberId());
            Contacts contacts = decryptedContacts(tenant, entry.memberId());
            assertThat(contacts.phones()).as("phones of %s", entry.memberId())
                    .isEqualTo(entry.phones() == null ? List.of() : entry.phones());
            assertThat(contacts.email()).as("email of %s", entry.memberId()).isEqualTo(entry.email());
            assertThat(contacts.postalAddress()).isEqualTo(entry.postalAddress());
            assertThat(contacts.telegramUsername()).isEqualTo(entry.telegramUsername());
            assertThat(contacts.viberPhone()).isEqualTo(entry.viberPhone());
            assertThat(contacts.notes()).isEqualTo(entry.notes());
            assertThat(ownerJdbc().queryForObject("select delivery_channel from member where id = ?", String.class,
                    entry.memberId())).isEqualTo(entry.deliveryChannel());
            byte[] emailIndex = ownerJdbc().queryForObject("select email_bidx from member where id = ?",
                    byte[].class, entry.memberId());
            assertThat(emailIndex).isEqualTo(entry.email() == null ? null
                    : TenantContext.runAs(tenant, () -> cipher.blindIndex(tenant, IndexKind.EMAIL, entry.email())));
            assertThat(ownerJdbc().queryForObject("select count(*) from member_phone_index where member_id = ?",
                    Integer.class, entry.memberId()))
                    .isEqualTo(entry.phones() == null ? 0 : entry.phones().size());
        }
    }

    @Test
    @Order(2)
    void theDemoChairmanSignsInByEmail() throws Exception {
        String chairman = TestTokens.bearer(DEMO_TENANT, "sub-demo-contacts-" + UUID.randomUUID(),
                "Chairman@Demo.Local");
        // a first sign-in: the member is found by the blind index of the e-mail, not by an earlier link
        inTenant(DEMO_TENANT, sql -> sql.update("update member set keycloak_subject = null where id = ?",
                DEMO_CHAIRMAN));
        try {
            mvc.perform(get("/api/v1/members/" + DEMO_CHAIRMAN).header(HttpHeaders.AUTHORIZATION, chairman))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("chairman@demo.local"))
                    .andExpect(jsonPath("$.phones[0]").value("+380501110001"))
                    .andExpect(jsonPath("$.deliveryChannel").value("EMAIL"));
        } finally {
            inTenant(DEMO_TENANT, sql -> sql.update("update member set keycloak_subject = null where id = ?",
                    DEMO_CHAIRMAN));
        }
    }

    @Test
    @Order(4)
    void writingAgainGivesTheSameContactsAndSkipsUnknownMembers() {
        UUID unknown = UUID.randomUUID();
        List<DemoContactsCallback.DemoContacts> again = new java.util.ArrayList<>(DemoContactsCallback.read());
        again.add(new DemoContactsCallback.DemoContacts(unknown, List.of("+380501119999"), "nobody@demo.local",
                null, null, null, null, null));

        Integer written = ownerJdbc().execute((ConnectionCallback<Integer>) connection ->
                callback.write(connection, again));

        assertThat(written).isEqualTo(37);
        assertThat(decryptedContacts(DEMO_TENANT, DEMO_CHAIRMAN).email()).isEqualTo("chairman@demo.local");
        assertThat(ownerJdbc().queryForObject("select count(*) from member where id = ?", Integer.class, unknown))
                .isZero();
        everyDemoMemberHoldsTheContactsAndChannelOfTheFile();
    }

    @Test
    @Order(3)
    void itRunsOnlyAfterTheDemoDataScript() {
        MigrationInfo demo = mock(MigrationInfo.class);
        when(demo.getScript()).thenReturn("R__demo_data.sql");
        MigrationInfo other = mock(MigrationInfo.class);
        when(other.getScript()).thenReturn("V23__drop_member_plaintext.sql");

        assertThat(callback.supports(Event.AFTER_EACH_MIGRATE, context(demo))).isTrue();
        assertThat(callback.supports(Event.AFTER_EACH_MIGRATE, context(other))).isFalse();
        assertThat(callback.supports(Event.AFTER_EACH_MIGRATE, context(null))).isFalse();
        assertThat(callback.supports(Event.AFTER_MIGRATE, context(demo))).isFalse();
        assertThat(callback.supports(Event.AFTER_EACH_MIGRATE, null)).isFalse();
        assertThat(callback.canHandleInTransaction(Event.AFTER_EACH_MIGRATE, context(demo))).isTrue();
        assertThat(callback.getCallbackName()).isEqualTo("demo-member-contacts");
    }

    @Test
    @Order(5)
    void failuresAreReportedWithoutTheirValues() throws Exception {
        Connection broken = mock(Connection.class);
        when(broken.prepareStatement(anyString())).thenThrow(new SQLException("refused by the test"));
        Context context = context(null);
        when(context.getConnection()).thenReturn(broken);

        assertThatThrownBy(() -> callback.handle(Event.AFTER_EACH_MIGRATE, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The demo member contacts could not be written")
                .hasCauseInstanceOf(SQLException.class);
        assertThatThrownBy(() -> DemoContactsCallback.read("db/demo/no-such-file.json"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("The demo member contacts could not be read");
    }

    private static Context context(MigrationInfo migration) {
        Context context = mock(Context.class);
        when(context.getMigrationInfo()).thenReturn(migration);
        return context;
    }
}
