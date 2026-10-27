package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.TestPostgis;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V23 drops the plaintext member contacts. It must refuse to run while a plaintext value has no ciphertext (the Java
 * migration 22.1 did not run) and, once the ciphertext is there, replace the channel rule with one on ciphertext.
 * Runs on its own container without the Spring context: Flyway stops at V22 and the V23 script is executed by hand,
 * so the Java migration 22.1 (which would encrypt the rows) never runs between them.
 */
class MemberContactsMigrationGuardTests {

    private static final UUID TENANT = UUID.randomUUID();

    @Test
    void v23RefusesToDropPlaintextWithoutCiphertextAndSucceedsOnceEncrypted() throws IOException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestPostgis.image())
                .withInitScript("db/test-init.sql")) {
            postgres.start();
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").target("22").load().migrate();
            JdbcTemplate sql = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword()));
            sql.update("insert into tenant (id, name) values (?, 'Guard Cooperative')", TENANT);
            UUID encrypted = UUID.randomUUID();
            UUID plain = UUID.randomUUID();
            insert(sql, encrypted, "Encrypted");
            insert(sql, plain, "Plain");
            sql.update("update member set email = 'plain@example.com' where id = ?", plain);
            // the migration 22.1 did not run: the plaintext has no ciphertext
            sql.update("update member set phones = '{+380670000001}', phones_enc = '\\x01'::bytea where id = ?",
                    encrypted);

            assertThatThrownBy(() -> sql.execute(script()))
                    .rootCause().hasMessageContaining("V23 aborted: 1 member rows")
                    .hasMessageNotContaining("plain@example.com");
            assertThat(sql.queryForObject(
                    "select count(*) from information_schema.columns where table_name = 'member' and column_name = 'email'",
                    Integer.class)).isEqualTo(1);

            // the migration 22.1 encrypted the e-mail
            sql.update("update member set email_enc = '\\x02'::bytea where id = ?", plain);
            sql.execute(script());

            assertThat(sql.queryForObject("select count(*) from information_schema.columns "
                    + "where table_name = 'member' and column_name in "
                    + "('phones', 'email', 'postal_address', 'telegram_username', 'viber_phone', 'notes')",
                    Integer.class)).isZero();
            assertThat(sql.queryForObject("select count(*) from pg_indexes where indexname in "
                    + "('member_tenant_email_uq', 'member_phones_gin')", Integer.class)).isZero();
            // the channel rule now looks at the ciphertext
            assertThatThrownBy(() -> sql.update("update member set delivery_channel = 'TELEGRAM' where id = ?", plain))
                    .rootCause().hasMessageContaining("member_channel_contact_ck");
            sql.update("update member set delivery_channel = 'EMAIL' where id = ?", plain);
            assertThatThrownBy(() -> sql.update("update member set delivery_channel = 'EMAIL' where id = ?", encrypted))
                    .rootCause().hasMessageContaining("member_channel_contact_ck");
            sql.update("update member set telegram_username_enc = '\\x03'::bytea, delivery_channel = 'TELEGRAM' "
                    + "where id = ?", encrypted);
        }
    }

    private static void insert(JdbcTemplate sql, UUID id, String name) {
        sql.update("insert into member (id, tenant_id, last_name, first_name) values (?, ?, ?, 'Test')", id, TENANT,
                name);
    }

    private static String script() throws IOException {
        try (InputStream in = MemberContactsMigrationGuardTests.class
                .getResourceAsStream("/db/migration/V23__drop_member_plaintext.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
