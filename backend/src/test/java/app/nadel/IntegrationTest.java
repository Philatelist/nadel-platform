package app.nadel;

import app.nadel.notifications.FakeBotApi;
import app.nadel.privacy.FieldRef;
import app.nadel.privacy.IndexKind;
import app.nadel.privacy.PersonalDataCipher;
import app.nadel.tenancy.TenantContext;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base for integration tests: real PostgreSQL + PostGIS in a container.
 * Migrations run as the container superuser (acting as owner) and the application runs as nadel_app,
 * so RLS behaves in tests exactly as in production.
 * <p>
 * HTTP tests use MockMvc with {@link TestTokens}; the real security filter chain and authentication converter run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSecurityConfiguration.class)
public abstract class IntegrationTest {

    protected static final UUID DEMO_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    protected static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestPostgis.image())
            .withInitScript("db/test-init.sql");

    /** The personal data master key of this JVM's contexts: random per run, never written anywhere. */
    protected static final String MASTER_KEY = randomMasterKey();

    /** The owner connections of {@link #ownerJdbc()}: pooled, a new connection costs a backend start and SCRAM. */
    private static final OwnerDataSource OWNER;

    static {
        POSTGRES.start();
        OWNER = new OwnerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        // Outbox listeners finish within milliseconds; the default (first check after 100 ms, then every 100 ms) made
        // each wait for an event cost at least 100 ms. The timeouts stay as each test sets them.
        Awaitility.setDefaultPollDelay(Duration.ZERO);
        Awaitility.setDefaultPollInterval(Duration.ofMillis(10));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "nadel_app");
        registry.add("spring.datasource.password", () -> "nadel_app");
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        // The demo cooperative the tests share starts without a ledger (no charges, debts or water connections);
        // DemoLedgerData loads the demo ledger block for the tests that want it
        registry.add("spring.flyway.placeholders.demoledger", () -> "off");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://localhost:0/realms/test");
        // A fresh personal data master key per JVM, shared by every context on the one container; set explicitly, so
        // the dev key file (infra/.env) is never read or created by a test
        registry.add("nadel.privacy.master-key", () -> MASTER_KEY);
        registry.add("nadel.privacy.previous-master-key", () -> "");
        // Feature 017: the Telegram Bot API is the in-JVM fake, so no test ever reaches Telegram (or the dev fake)
        registry.add("nadel.telegram.api-url", () -> FakeBotApi.shared().url());
        // No bot is polled on a schedule in tests: the bot tests drive the supervisor themselves, with short polls
        registry.add("nadel.telegram.bot-enabled", () -> "false");
        registry.add("nadel.telegram.poll-timeout", () -> "1s");
        // No JobRunr background job server: the delivery tests run the dispatcher themselves, and enqueued jobs only
        // stay in jobrunr_jobs. A test class that wants the server sets nadel.test.job-server=true (JobRunrTests).
        registry.add("jobrunr.background-job-server.enabled", () -> "${nadel.test.job-server:false}");
    }

    private static String randomMasterKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Autowired
    private TransactionTemplate fixtureTx;

    @Autowired
    private JdbcTemplate fixtureJdbc;

    @Autowired
    private PersonalDataCipher fixtureCipher;

    private final List<Fixture> createdMembers = new ArrayList<>();

    /** The running test, for the static fixtures of support classes ({@link #sealContacts}). */
    private static IntegrationTest current;

    @BeforeEach
    void rememberTheRunningTest() {
        current = this;
    }

    /**
     * {@link #insertContacts} for static fixture helpers, with the beans of the running test (the tests run one at a
     * time).
     */
    protected static void sealContacts(UUID tenant, UUID member, String email, String... phones) {
        current.insertContacts(tenant, member, email, phones);
    }

    /**
     * SQL as the container owner (superuser, bypasses RLS): for fixtures the application role cannot create, such as
     * a whole cooperative. Autocommit, over pooled connections that come back without an open transaction; the
     * caller removes what it creates.
     */
    protected static JdbcTemplate ownerJdbc() {
        return OWNER.jdbc();
    }

    /** Runs SQL as the given cooperative (RLS applies), in one transaction. */
    protected <T> T inTenant(UUID tenant, Function<JdbcTemplate, T> work) {
        return TenantContext.runAs(tenant, () -> fixtureTx.execute(status -> work.apply(fixtureJdbc)));
    }

    /** Creates an active, unlinked member with the given e-mail; removed after the test. */
    protected UUID newMember(UUID tenant, String email) {
        return newMember(tenant, "Test", "Member", null, email);
    }

    /**
     * Creates an active, unlinked member with the given name parts, e-mail and phones (all optional except the
     * names); removed after the test. Delivery channel is PAPER. The contacts are sealed as the application seals
     * them ({@link #insertContacts}).
     */
    protected UUID newMember(UUID tenant, String lastName, String firstName, String middleName, String email,
            String... phones) {
        UUID id = UUID.randomUUID();
        inTenant(tenant, sql -> sql.update(
                "insert into member (id, tenant_id, last_name, first_name, middle_name) values (?, ?, ?, ?, ?)",
                id, tenant, lastName, firstName, middleName));
        createdMembers.add(new Fixture(tenant, id));
        insertContacts(tenant, id, email, phones);
        return id;
    }

    /**
     * Seals the e-mail (with its blind index) and the phones (one JSON array, plus the {@code member_phone_index} rows
     * of the normalised phones) of an existing member, replacing what it had, as the members module does (ADR 0016).
     * A null e-mail and no phones clear them.
     */
    protected void insertContacts(UUID tenant, UUID member, String email, String... phones) {
        inTenant(tenant, sql -> {
            String normalisedEmail = email == null || email.isBlank() ? null : email.strip().toLowerCase(Locale.ROOT);
            sql.update("update member set email_enc = ?, email_bidx = ?, phones_enc = ? where id = ?",
                    seal(tenant, "email_enc", member, email),
                    normalisedEmail == null ? null : fixtureCipher.blindIndex(tenant, IndexKind.EMAIL, normalisedEmail),
                    phones.length == 0 ? null : seal(tenant, "phones_enc", member,
                            JsonMapper.shared().writeValueAsString(List.of(phones))),
                    member);
            sql.update("delete from member_phone_index where member_id = ?", member);
            Set<String> normalised = new LinkedHashSet<>();
            for (String phone : phones) {
                normalised.add(normalisedPhone(phone));
            }
            for (String phone : normalised) {
                String digits = phone.replaceAll("[^0-9]", "");
                sql.update("insert into member_phone_index (tenant_id, member_id, phone_bidx, last4_bidx) "
                        + "values (?, ?, ?, ?)", tenant, member,
                        fixtureCipher.blindIndex(tenant, IndexKind.PHONE, phone),
                        fixtureCipher.blindIndex(tenant, IndexKind.PHONE_LAST4, digits.substring(digits.length() - 4)));
            }
            return null;
        });
    }

    /**
     * Seals one text contact of a member: {@code field} is the former plaintext column ({@code postal_address},
     * {@code telegram_username}, {@code viber_phone} or {@code notes}); null clears it.
     */
    protected void setContact(UUID tenant, UUID member, String field, String value) {
        if (!Set.of("postal_address", "telegram_username", "viber_phone", "notes").contains(field)) {
            throw new IllegalArgumentException("Not a sealed text contact: " + field);
        }
        inTenant(tenant, sql -> sql.update("update member set " + field + "_enc = ? where id = ?",
                seal(tenant, field + "_enc", member, value), member));
    }

    /** The contacts of a member, opened with the key of its cooperative. */
    protected Contacts decryptedContacts(UUID tenant, UUID member) {
        return inTenant(tenant, sql -> sql.queryForObject("select phones_enc, email_enc, postal_address_enc, "
                + "telegram_username_enc, viber_phone_enc, notes_enc from member where id = ?", (rs, n) -> {
                    String phones = open(tenant, "phones_enc", member, rs.getBytes(1));
                    return new Contacts(phones == null ? List.of()
                            : JsonMapper.shared().readValue(phones, new TypeReference<List<String>>() { }),
                            open(tenant, "email_enc", member, rs.getBytes(2)),
                            open(tenant, "postal_address_enc", member, rs.getBytes(3)),
                            open(tenant, "telegram_username_enc", member, rs.getBytes(4)),
                            open(tenant, "viber_phone_enc", member, rs.getBytes(5)),
                            open(tenant, "notes_enc", member, rs.getBytes(6)));
                }, member));
    }

    /** The e-mail of a member, opened; null when it has none. */
    protected String memberEmail(UUID tenant, UUID member) {
        return decryptedContacts(tenant, member).email();
    }

    /** The members of the cooperative (archived included) whose e-mail is one of {@code emails}, by blind index. */
    protected List<UUID> memberIdsByEmail(UUID tenant, String... emails) {
        return inTenant(tenant, sql -> {
            List<UUID> ids = new ArrayList<>();
            for (String email : emails) {
                ids.addAll(sql.queryForList("select id from member where email_bidx = ?", UUID.class,
                        fixtureCipher.blindIndex(tenant, IndexKind.EMAIL, email.strip().toLowerCase(Locale.ROOT))));
            }
            return ids;
        });
    }

    /** The only member of the cooperative with this e-mail (found by its blind index). */
    protected UUID memberIdByEmail(UUID tenant, String email) {
        List<UUID> ids = memberIdsByEmail(tenant, email);
        if (ids.size() != 1) {
            throw new IllegalStateException("Expected one member with the e-mail, found " + ids.size());
        }
        return ids.getFirst();
    }

    /**
     * Unlinks the Keycloak subject of the members with these e-mails (a first sign-in links a demo member), so the
     * next test signs in as on a fresh database.
     */
    protected void unlinkByEmail(UUID tenant, String... emails) {
        List<UUID> ids = memberIdsByEmail(tenant, emails);
        inTenant(tenant, sql -> {
            for (UUID id : ids) {
                sql.update("update member set keycloak_subject = null where id = ?", id);
            }
            return null;
        });
    }

    /** Of the members with these e-mails, those that are not linked to a Keycloak subject yet. */
    protected List<UUID> unlinkedMembersByEmail(UUID tenant, String... emails) {
        List<UUID> ids = memberIdsByEmail(tenant, emails);
        return inTenant(tenant, sql -> ids.stream()
                .filter(id -> sql.queryForObject("select keycloak_subject from member where id = ?", String.class,
                        id) == null)
                .toList());
    }

    /** Unlinks the Keycloak subject of the given members. */
    protected void unlinkMembers(UUID tenant, List<UUID> members) {
        inTenant(tenant, sql -> {
            for (UUID id : members) {
                sql.update("update member set keycloak_subject = null where id = ?", id);
            }
            return null;
        });
    }

    /**
     * Removes the personal data keys of a cooperative that a test created and is about to delete (a sealed contact, a
     * sign-in by e-mail or a search creates them); the keys refer to the cooperative.
     */
    protected static void deleteDataKeys(UUID tenant) {
        ownerJdbc().update("delete from tenant_data_key where tenant_id = ?", tenant);
    }

    /** The opened contacts of a member ({@link #decryptedContacts}). */
    protected record Contacts(List<String> phones, String email, String postalAddress, String telegramUsername,
            String viberPhone, String notes) {
    }

    private byte[] seal(UUID tenant, String column, UUID member, String value) {
        return value == null ? null : fixtureCipher.encrypt(tenant, new FieldRef("member", column, member), value);
    }

    private String open(UUID tenant, String column, UUID member, byte[] sealed) {
        return sealed == null ? null : fixtureCipher.decrypt(tenant, new FieldRef("member", column, member), sealed);
    }

    /** The international form the members module indexes a phone by (a copy of its {@code PhoneNumber} rules). */
    private static String normalisedPhone(String raw) {
        String text = raw.replaceAll("[\\s\\-.()\\[\\]]", "");
        if (text.matches("0\\d{9}")) {
            return "+38" + text;
        }
        if (text.matches("380\\d{9}")) {
            return "+" + text;
        }
        return text.matches("\\+[1-9]\\d{7,14}") ? text : raw.strip();
    }

    /** Archives a member (sets archived_at). */
    protected void archiveMember(UUID tenant, UUID member) {
        inTenant(tenant, sql -> sql.update("update member set archived_at = now() where id = ?", member));
    }

    /** Gives a member one of the three base roles of its cooperative. */
    protected void giveBaseRole(UUID tenant, UUID member, String baseCode) {
        inTenant(tenant, sql -> sql.update(
                "insert into member_role (tenant_id, member_id, role_id) "
                        + "select ?, ?, id from role where base_code = ?", tenant, member, baseCode));
    }

    protected String subjectOf(UUID tenant, UUID member) {
        return inTenant(tenant, sql -> sql.queryForObject(
                "select keycloak_subject from member where id = ?", String.class, member));
    }

    @AfterEach
    void removeFixtureMembers() {
        for (Fixture fixture : createdMembers) {
            inTenant(fixture.tenant(), sql -> {
                sql.update("delete from member_role where member_id = ?", fixture.id());
                return sql.update("delete from member where id = ?", fixture.id());
            });
        }
        createdMembers.clear();
    }

    private record Fixture(UUID tenant, UUID id) {
    }
}
