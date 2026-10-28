package app.nadel.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.NadelApplication;
import app.nadel.TestPostgis;
import app.nadel.TestSecurityConfiguration;
import app.nadel.TestTokens;
import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.privacy.FieldRef;
import app.nadel.privacy.PersonalDataCipher;
import app.nadel.tenancy.TenantContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Upgrade of existing data in one go (spec 014, 2.6): on a container of its own, Flyway stops at V20 (the release
 * before this feature), two cooperatives are filled the way that release stored them - members with all six contacts,
 * audit entries, a pending import preview and a pending message in the queue - and the application then starts with
 * every migration up to the latest. Afterwards every contact of both cooperatives is shown correctly by the API, the
 * audit log shows the old and new values to the chairman, the pending import opens with its cooperative's key, and as
 * the schema owner none of the values is found in {@code member}, {@code audit_entry}, {@code import_run} or
 * {@code event_publication}. The step tests ({@code EncryptMemberContactsMigrationTests},
 * {@code SealAuditValuesMigrationTests}, {@code SealImportPlansMigrationTests}) cover each migration alone.
 * <pre>
 * @layer: integration
 * @spec: 014-personal-data-encryption
 * @regression
 * </pre>
 */
@Tag("regression")
@ExtendWith(OutputCaptureExtension.class)
class PersonalDataUpgradeAcceptanceTests {

    static final UUID FIRST = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
    static final UUID SECOND = UUID.fromString("dddddddd-0000-0000-0000-000000000002");
    static final UUID FIRST_CHAIRMAN = UUID.fromString("dddddddd-1111-0000-0000-000000000001");
    static final UUID FIRST_MEMBER = UUID.fromString("dddddddd-1111-0000-0000-000000000002");
    static final UUID SECOND_CHAIRMAN = UUID.fromString("dddddddd-2222-0000-0000-000000000001");
    static final UUID SECOND_MEMBER = UUID.fromString("dddddddd-2222-0000-0000-000000000002");
    static final UUID FIRST_ENTRY = UUID.fromString("dddddddd-1111-1111-0000-000000000001");
    static final UUID SECOND_ENTRY = UUID.fromString("dddddddd-2222-1111-0000-000000000001");
    static final UUID FIRST_IMPORT = UUID.fromString("dddddddd-1111-2222-0000-000000000001");
    static final UUID SECOND_IMPORT = UUID.fromString("dddddddd-2222-2222-0000-000000000001");
    static final UUID PENDING_MESSAGE = UUID.fromString("dddddddd-1111-3333-0000-000000000001");

    /** One phone shared by a member of each cooperative: a search must find only its own cooperative's member. */
    static final String SHARED_PHONE = "+380671119090";

    @Test
    void everyContactOfBothCooperativesIsReadableAndNothingIsLeftInTheClearAfterTheUpgrade(CapturedOutput output)
            throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestPostgis.image())
                .withInitScript("db/test-init.sql")) {
            postgres.start();
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").target("20").load().migrate();
            JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword()));
            beforeTheUpgrade(owner);
            String key = randomKey();

            try (ConfigurableApplicationContext context = start(postgres, key)) {
                int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));

                // the cooperatives' contacts, through the API as their chairmen
                for (Cooperative coop : List.of(first(), second())) {
                    JsonNode card = get(port, coop.chairmanToken(), "/api/v1/members/" + coop.member());
                    assertThat(card.get("phones").get(0).asString()).isEqualTo(SHARED_PHONE);
                    assertThat(card.get("email").asString()).isEqualTo(coop.email());
                    assertThat(card.get("postalAddress").asString()).isEqualTo(coop.address());
                    assertThat(card.get("telegramUsername").asString()).isEqualTo(coop.telegram());
                    assertThat(card.get("viberPhone").asString()).isEqualTo(coop.viber());
                    assertThat(card.get("notes").asString()).isEqualTo(coop.notes());
                    assertThat(card.get("lastName").asString()).isEqualTo(coop.lastName());

                    // search works on the upgraded data and sees only the own cooperative
                    for (String q : List.of(SHARED_PHONE, "9090", coop.email().toUpperCase())) {
                        JsonNode found = get(port, coop.chairmanToken(), "/api/v1/members?size=100&q="
                                + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8));
                        assertThat(found.get("items").valueStream().map(item -> item.get("id").asString()).toList())
                                .as("%s by %s", coop.lastName(), q).containsExactly(coop.member().toString());
                    }

                    // the audit entry made before the upgrade shows its values to the chairman
                    JsonNode entry = get(port, coop.chairmanToken(), "/api/v1/audit?entityId=" + coop.member())
                            .get("items").get(0);
                    JsonNode phones = entry.get("changes").get(0);
                    assertThat(phones.get("field").asString()).isEqualTo("phones");
                    assertThat(phones.get("hidden").asBoolean()).isFalse();
                    assertThat(phones.get("oldValue").asString()).isEqualTo(coop.oldPhone());
                    assertThat(phones.get("newValue").asString()).isEqualTo(SHARED_PHONE);

                    // the pending import opens with the key of its own cooperative
                    UUID run = coop.run();
                    byte[] sealed = owner.queryForObject("select plan_enc from import_run where id = ?", byte[].class,
                            run);
                    PersonalDataCipher cipher = context.getBean(PersonalDataCipher.class);
                    String envelope = TenantContext.runAs(coop.tenant(), () -> cipher.decrypt(coop.tenant(),
                            new FieldRef("import_run", "plan_enc", run), sealed));
                    assertThat(envelope).contains(coop.lastName()).contains(coop.email()).contains(coop.digest());
                }
            }

            // nothing is left readable in storage: the six values of every member, the audit values, the plans
            String stored = String.join(" ", owner.queryForList("""
                    select row_to_json(t)::text from member t
                    union all select row_to_json(t)::text from audit_entry t
                    union all select row_to_json(t)::text || coalesce(encode(t.plan_enc, 'escape'), '')
                              from import_run t
                    union all select row_to_json(t)::text from event_publication t
                    """, String.class));
            for (Cooperative coop : List.of(first(), second())) {
                assertThat(stored).doesNotContain(coop.email()).doesNotContain(coop.address())
                        .doesNotContain(coop.telegram()).doesNotContain(coop.viber()).doesNotContain(coop.notes())
                        .doesNotContain(coop.oldPhone()).doesNotContain(coop.oldPhone().substring(1))
                        .doesNotContain(coop.lastNameInEntry() + "-secret");
                // the names are readable
                assertThat(stored).contains(coop.lastName());
            }
            assertThat(stored).doesNotContain(SHARED_PHONE).doesNotContain(SHARED_PHONE.substring(1));
            assertThat(owner.queryForObject("select count(*) from information_schema.columns where "
                    + "(table_name = 'member' and column_name in ('phones', 'email', 'postal_address', "
                    + "'telegram_username', 'viber_phone', 'notes')) or (table_name = 'import_run' "
                    + "and column_name in ('plan', 'plan_digest'))", Integer.class)).isZero();
            assertThat(output.getAll()).doesNotContain("coop-one.example").doesNotContain("coop-two.example")
                    .doesNotContain(SHARED_PHONE).doesNotContain(key);
        }
    }

    // ---- the data as the release before this feature stored it ------------------------------------------------

    private record Cooperative(UUID tenant, UUID chairmanMember, UUID member, UUID entry, UUID run, String name,
            String chairmanEmail, String digest, String suffix) {

        String lastName() {
            return "Upgraded" + suffix;
        }

        String lastNameInEntry() {
            return "Upgraded" + suffix;
        }

        String email() {
            return "member@coop-" + suffix + ".example";
        }

        String address() {
            return "1 Garden Street " + suffix;
        }

        String telegram() {
            return "tg_" + suffix;
        }

        String viber() {
            return suffix.equals("one") ? "+380931110001" : "+380931110002";
        }

        String notes() {
            return "private note " + suffix;
        }

        String oldPhone() {
            return suffix.equals("one") ? "+380501110001" : "+380501110002";
        }

        String chairmanToken() {
            return TestTokens.bearer(tenant, "sub-chairman-" + suffix, chairmanEmail);
        }
    }

    private static Cooperative first() {
        return new Cooperative(FIRST, FIRST_CHAIRMAN, FIRST_MEMBER, FIRST_ENTRY, FIRST_IMPORT, "First",
                "chairman@coop-one.example", "a".repeat(64), "one");
    }

    private static Cooperative second() {
        return new Cooperative(SECOND, SECOND_CHAIRMAN, SECOND_MEMBER, SECOND_ENTRY, SECOND_IMPORT, "Second",
                "chairman@coop-two.example", "b".repeat(64), "two");
    }

    private static void beforeTheUpgrade(JdbcTemplate owner) {
        JsonMapper json = JsonMapper.shared();
        for (Cooperative coop : List.of(first(), second())) {
            owner.update("insert into tenant (id, name) values (?, ?)", coop.tenant(), coop.name());
            owner.queryForList("select seed_base_roles(?)", coop.tenant());
            owner.update("insert into member (id, tenant_id, last_name, first_name, email) values (?, ?, 'Chair', "
                    + "'Person', ?)", coop.chairmanMember(), coop.tenant(), coop.chairmanEmail());
            owner.update("insert into member_role (tenant_id, member_id, role_id) select ?, ?, id from role "
                    + "where tenant_id = ? and base_code = 'CHAIRMAN'", coop.tenant(), coop.chairmanMember(),
                    coop.tenant());
            owner.update("insert into member (id, tenant_id, last_name, first_name, phones, email, postal_address, "
                    + "delivery_channel, telegram_username, viber_phone, notes) values (?, ?, ?, 'Person', "
                    + "?::text[], ?, ?, 'TELEGRAM', ?, ?, ?)", coop.member(), coop.tenant(), coop.lastName(),
                    "{" + SHARED_PHONE + "}", coop.email(), coop.address(), coop.telegram(), coop.viber(),
                    coop.notes());
            owner.update("insert into audit_entry (id, tenant_id, occurred_at, actor_subject, action, entity_type, "
                    + "entity_id, entity_label, changes) values (?, ?, now(), 'fixture', 'MEMBER_UPDATED', 'MEMBER', "
                    + "?, ?, cast(? as jsonb))", coop.entry(), coop.tenant(), coop.member(), coop.lastName(),
                    "[{\"field\": \"phones\", \"old\": \"" + coop.oldPhone() + "\", \"new\": \"" + SHARED_PHONE
                            + "\"}]");
            owner.update("insert into import_run (id, tenant_id, file_name, created_by_subject, created_by_name, "
                    + "created_at, expires_at, status, plan, plan_digest) values (?, ?, 'f.xlsx', 's', 'n', "
                    + "now(), now() + interval '30 minutes', 'PREVIEW', ?::jsonb, ?)", coop.run(), coop.tenant(),
                    "{\"version\": 1, \"plots\": [], \"members\": [{\"row\": 2, \"externalId\": \"M-1\", "
                            + "\"lastName\": \"" + coop.lastName() + "\", \"firstName\": \"Plain\", \"phones\": ["
                            + "\"" + coop.oldPhone() + "\"], \"email\": \"" + coop.email() + "\"}], "
                            + "\"ownership\": [], \"meters\": [], \"openingBalances\": []}", coop.digest());
        }
        // a message of the queue that was not delivered before the upgrade
        AuditEvent pending = new AuditEvent(UUID.randomUUID(), FIRST, Instant.parse("2026-05-10T10:00:00Z"),
                new Actor(null, "upgrade-test", "Upgrade"), "MEMBER_UPDATED", "MEMBER", UUID.randomUUID(),
                "Pending" + first().suffix(), List.of(new FieldChange("notes", "queued old note", "queued new note")));
        owner.update("insert into event_publication (id, listener_id, event_type, serialized_event, "
                + "publication_date, status, completion_attempts) values (?, 'upgrade-test', ?, ?, now(), "
                + "'PUBLISHED', 0)", PENDING_MESSAGE,
                AuditEvent.class.getName(), json.writeValueAsString(pending));
    }

    private static JsonNode get(int port, String bearer, String path) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path)).header("Authorization", bearer).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s: %s", path, response.body()).isEqualTo(200);
        return JsonMapper.shared().readTree(response.body());
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static ConfigurableApplicationContext start(PostgreSQLContainer postgres, String masterKey) {
        return new SpringApplicationBuilder(NadelApplication.class, TestSecurityConfiguration.class)
                .profiles("prod")
                .run("--nadel.privacy.master-key=" + masterKey,
                        "--nadel.privacy.previous-master-key=",
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=nadel_app",
                        "--spring.datasource.password=nadel_app",
                        "--spring.flyway.user=" + postgres.getUsername(),
                        "--spring.flyway.password=" + postgres.getPassword(),
                        "--spring.flyway.locations=classpath:db/migration",
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
                        "--server.port=0",
                        "--spring.main.banner-mode=off");
    }
}
