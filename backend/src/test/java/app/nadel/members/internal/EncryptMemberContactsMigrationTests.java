package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.NadelApplication;
import app.nadel.TestPostgis;
import app.nadel.privacy.FieldRef;
import app.nadel.privacy.IndexKind;
import app.nadel.privacy.PersonalDataCipher;
import app.nadel.tenancy.TenantContext;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The Java migration 22.1 over plaintext members of two cooperatives (spec 014, 2.2): on a container of its own,
 * Flyway stops at V22 (plaintext beside the empty {@code *_enc} columns), the members are written as the previous
 * release stored them, and the application then migrates. Spring Boot must hand the migration bean to Flyway (V23
 * would refuse to drop the plaintext otherwise); every contact opens with the cooperative's key, the e-mail and phone
 * indexes match the runtime's, a second run changes nothing, and the log has counts only.
 */
@ExtendWith(OutputCaptureExtension.class)
class EncryptMemberContactsMigrationTests {

    static final UUID FIRST = UUID.fromString("eeeeeeee-0000-0000-0000-000000000001");
    static final UUID SECOND = UUID.fromString("eeeeeeee-0000-0000-0000-000000000002");
    static final UUID FULL = UUID.fromString("eeeeeeee-1111-0000-0000-000000000001");
    static final UUID TWO_PHONES = UUID.fromString("eeeeeeee-1111-0000-0000-000000000002");
    static final UUID NOTHING = UUID.fromString("eeeeeeee-1111-0000-0000-000000000003");
    static final UUID ARCHIVED = UUID.fromString("eeeeeeee-1111-0000-0000-000000000004");
    static final UUID THEIRS = UUID.fromString("eeeeeeee-2222-0000-0000-000000000001");

    @Test
    void encryptsEveryCooperativesContactsOnceAndV23DropsThePlaintext(CapturedOutput output) throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestPostgis.image())
                .withInitScript("db/test-init.sql")) {
            postgres.start();
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").target("22").load().migrate();
            JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword()));
            plaintextMembers(owner);
            String key = randomKey();

            try (ConfigurableApplicationContext context = start(postgres, key, "22.1")) {
                assertThat(output.getAll())
                        .contains("Member contacts encrypted: 4 of 5 members sealed, 4 phone index rows written");
                PersonalDataCipher cipher = context.getBean(PersonalDataCipher.class);
                assertSealed(owner, cipher);

                Map<UUID, String> before = ciphertexts(owner);
                try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                        postgres.getUsername(), postgres.getPassword())) {
                    EncryptMemberContactsMigration.Counts again = context
                            .getBean(EncryptMemberContactsMigration.class).encrypt(connection);
                    assertThat(again.read).isEqualTo(5);
                    assertThat(again.sealed).isZero();
                    assertThat(again.indexRows).isZero();
                }
                assertThat(ciphertexts(owner)).isEqualTo(before);
                assertThat(owner.queryForObject("select count(*) from member_phone_index", Integer.class))
                        .isEqualTo(4);
            }

            try (ConfigurableApplicationContext context = start(postgres, key, "latest")) {
                assertThat(owner.queryForObject("select count(*) from information_schema.columns "
                        + "where table_name = 'member' and column_name in ('phones', 'email', 'postal_address', "
                        + "'telegram_username', 'viber_phone', 'notes')", Integer.class)).isZero();
                assertSealed(owner, context.getBean(PersonalDataCipher.class));
            }
            assertThat(output.getAll()).doesNotContain("@coop.example").doesNotContain("380671112")
                    .doesNotContain("Garden").doesNotContain("secret").doesNotContain(key);
        }
    }

    private static void plaintextMembers(JdbcTemplate owner) {
        owner.update("insert into tenant (id, name) values (?, 'First'), (?, 'Second')", FIRST, SECOND);
        insert(owner, FULL, FIRST, "{+380671112201}", "full@coop.example", "1 Garden St", "TELEGRAM", "@full",
                "+380671112201", "secret note", false);
        insert(owner, TWO_PHONES, FIRST, "{+380671112202,+380931112202}", null, null, "PAPER", null, null, null,
                false);
        insert(owner, NOTHING, FIRST, "{}", null, null, "PAPER", null, null, "", false);
        insert(owner, ARCHIVED, FIRST, "{}", "full@coop.example", null, "EMAIL", null, null, null, true);
        insert(owner, THEIRS, SECOND, "{+380671112201}", "full@coop.example", null, "EMAIL", null, null, null,
                false);
    }

    private static void insert(JdbcTemplate owner, UUID id, UUID tenant, String phones, String email, String address,
            String channel, String telegram, String viber, String notes, boolean archived) {
        owner.update("insert into member (id, tenant_id, last_name, first_name, phones, email, postal_address, "
                + "delivery_channel, telegram_username, viber_phone, notes, archived_at) "
                + "values (?, ?, 'Plain', 'Member', ?::text[], ?, ?, ?, ?, ?, ?, " + (archived ? "now()" : "null")
                + ")", id, tenant, phones, email, address, channel, telegram, viber, notes);
    }

    private static void assertSealed(JdbcTemplate owner, PersonalDataCipher cipher) {
        assertThat(open(owner, cipher, FIRST, FULL, "phones_enc")).isEqualTo("[\"+380671112201\"]");
        assertThat(open(owner, cipher, FIRST, FULL, "email_enc")).isEqualTo("full@coop.example");
        assertThat(open(owner, cipher, FIRST, FULL, "postal_address_enc")).isEqualTo("1 Garden St");
        assertThat(open(owner, cipher, FIRST, FULL, "telegram_username_enc")).isEqualTo("@full");
        assertThat(open(owner, cipher, FIRST, FULL, "viber_phone_enc")).isEqualTo("+380671112201");
        assertThat(open(owner, cipher, FIRST, FULL, "notes_enc")).isEqualTo("secret note");
        assertThat(open(owner, cipher, FIRST, TWO_PHONES, "phones_enc"))
                .isEqualTo("[\"+380671112202\",\"+380931112202\"]");
        assertThat(open(owner, cipher, FIRST, ARCHIVED, "email_enc")).isEqualTo("full@coop.example");
        assertThat(open(owner, cipher, SECOND, THEIRS, "email_enc")).isEqualTo("full@coop.example");
        // nothing to seal stays null; a blank note is no note
        assertThat(owner.queryForObject("select num_nonnulls(phones_enc, email_enc, postal_address_enc, "
                + "telegram_username_enc, viber_phone_enc, notes_enc, email_bidx) from member where id = ?",
                Integer.class, NOTHING)).isZero();

        // the blind indexes are the runtime's: the same e-mail in two cooperatives has two different indexes
        byte[] firstEmail = index(cipher, FIRST, IndexKind.EMAIL, "full@coop.example");
        assertThat(bytes(owner, "select email_bidx from member where id = ?", FULL)).isEqualTo(firstEmail);
        assertThat(bytes(owner, "select email_bidx from member where id = ?", ARCHIVED)).isEqualTo(firstEmail);
        assertThat(bytes(owner, "select email_bidx from member where id = ?", THEIRS))
                .isEqualTo(index(cipher, SECOND, IndexKind.EMAIL, "full@coop.example"))
                .isNotEqualTo(firstEmail);
        assertThat(phoneIndex(owner, TWO_PHONES)).containsExactlyInAnyOrder(
                hex(index(cipher, FIRST, IndexKind.PHONE, "+380671112202")) + "/"
                        + hex(index(cipher, FIRST, IndexKind.PHONE_LAST4, "2202")),
                hex(index(cipher, FIRST, IndexKind.PHONE, "+380931112202")) + "/"
                        + hex(index(cipher, FIRST, IndexKind.PHONE_LAST4, "2202")));
        assertThat(phoneIndex(owner, THEIRS)).containsExactly(
                hex(index(cipher, SECOND, IndexKind.PHONE, "+380671112201")) + "/"
                        + hex(index(cipher, SECOND, IndexKind.PHONE_LAST4, "2201")));
    }

    private static String open(JdbcTemplate owner, PersonalDataCipher cipher, UUID tenant, UUID member,
            String column) {
        byte[] sealed = bytes(owner, "select " + column + " from member where id = ?", member);
        return TenantContext.runAs(tenant, () -> cipher.decrypt(tenant, new FieldRef("member", column, member),
                sealed));
    }

    private static byte[] index(PersonalDataCipher cipher, UUID tenant, IndexKind kind, String value) {
        return TenantContext.runAs(tenant, () -> cipher.blindIndex(tenant, kind, value));
    }

    private static byte[] bytes(JdbcTemplate owner, String sql, UUID id) {
        return owner.queryForObject(sql, byte[].class, id);
    }

    private static Set<String> phoneIndex(JdbcTemplate owner, UUID member) {
        return owner.query("select phone_bidx, last4_bidx from member_phone_index where member_id = ?",
                (rs, n) -> hex(rs.getBytes(1)) + "/" + hex(rs.getBytes(2)), member).stream()
                .collect(Collectors.toSet());
    }

    /** Every sealed value and blind index of every member, as text, to compare two states. */
    private static Map<UUID, String> ciphertexts(JdbcTemplate owner) {
        List<Map<String, Object>> rows = owner.queryForList("select id, concat_ws('/', encode(phones_enc, 'hex'), "
                + "encode(email_enc, 'hex'), encode(postal_address_enc, 'hex'), encode(telegram_username_enc, 'hex'), "
                + "encode(viber_phone_enc, 'hex'), encode(notes_enc, 'hex'), encode(email_bidx, 'hex')) as sealed "
                + "from member");
        return rows.stream().collect(Collectors.toMap(row -> (UUID) row.get("id"), row -> (String) row.get("sealed")));
    }

    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static ConfigurableApplicationContext start(PostgreSQLContainer postgres, String masterKey,
            String target) {
        return new SpringApplicationBuilder(NadelApplication.class)
                .profiles("prod")
                .run("--nadel.privacy.master-key=" + masterKey,
                        "--nadel.privacy.previous-master-key=",
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=nadel_app",
                        "--spring.datasource.password=nadel_app",
                        "--spring.flyway.user=" + postgres.getUsername(),
                        "--spring.flyway.password=" + postgres.getPassword(),
                        "--spring.flyway.locations=classpath:db/migration",
                        "--spring.flyway.target=" + target,
                        // the entities map the latest schema (import_run.plan_enc of V25), so a context stopped at an
                        // earlier version cannot validate it
                        "--spring.jpa.hibernate.ddl-auto=" + ("latest".equals(target) ? "validate" : "none"),
                        // JobRunr's tables come only from V30 (jobrunr.database.skip-create), so a context stopped at
                        // an earlier version keeps its jobs in memory; the latest run uses the real tables
                        "--jobrunr.database.type=" + ("latest".equals(target) ? "sql" : "mem"),
                        "--spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
                        "--server.port=0",
                        "--spring.main.banner-mode=off");
    }
}
