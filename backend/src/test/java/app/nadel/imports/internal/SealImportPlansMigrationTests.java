package app.nadel.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.NadelApplication;
import app.nadel.TestPostgis;
import app.nadel.tenancy.TenantContext;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.UUID;
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
 * The Java migration 25.1 over plaintext import previews of two cooperatives (spec 014, 2.5): on a container of its
 * own, the application migrates to V25 (the previous release's schema plus the empty {@code plan_enc}), the previews
 * are written as the previous release stored them, and the application then migrates to 25.1 and on to the latest
 * version. Spring Boot must hand the migration bean to Flyway (V26 would refuse to drop the plaintext otherwise); every
 * plan opens with its cooperative's key, in its own run, with its digest; a second run changes nothing; the log has
 * counts only.
 */
@ExtendWith(OutputCaptureExtension.class)
class SealImportPlansMigrationTests {

    static final UUID FIRST = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    static final UUID SECOND = UUID.fromString("cccccccc-0000-0000-0000-000000000002");
    static final UUID FIRST_PREVIEW = UUID.fromString("cccccccc-1111-0000-0000-000000000001");
    static final UUID EXPIRED_PREVIEW = UUID.fromString("cccccccc-1111-0000-0000-000000000002");
    static final UUID WITH_ERRORS = UUID.fromString("cccccccc-1111-0000-0000-000000000003");
    static final UUID SECOND_PREVIEW = UUID.fromString("cccccccc-2222-0000-0000-000000000001");

    static final String FIRST_DIGEST = "a".repeat(64);
    static final String SECOND_DIGEST = "b".repeat(64);

    @Test
    void sealsEveryCooperativesPendingPlanOnceAndV26DropsThePlaintext(CapturedOutput output) throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestPostgis.image())
                .withInitScript("db/test-init.sql")) {
            postgres.start();
            String key = randomKey();
            // the schema of the previous release, with the new column still empty (the Java migrations before it run)
            start(postgres, key, "25").close();
            JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword()));
            plaintextPreviews(owner);

            try (ConfigurableApplicationContext context = start(postgres, key, "25.1")) {
                assertThat(output.getAll()).contains("Import plans sealed: 3 pending previews");
                SealedPlans plans = context.getBean(SealedPlans.class);
                assertSealed(owner, plans);
                assertThat(owner.queryForObject("select count(*) from import_run where plan is not null "
                        + "or plan_digest is not null", Integer.class)).isZero();
                assertThat(owner.queryForObject("select plan_enc is null from import_run where id = ?",
                        Boolean.class, WITH_ERRORS)).isTrue();

                String before = ciphertexts(owner);
                try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                        postgres.getUsername(), postgres.getPassword())) {
                    assertThat(context.getBean(SealImportPlansMigration.class).seal(connection)).isZero();
                }
                assertThat(ciphertexts(owner)).isEqualTo(before);
            }

            try (ConfigurableApplicationContext context = start(postgres, key, "latest")) {
                assertThat(owner.queryForObject("select count(*) from information_schema.columns "
                        + "where table_name = 'import_run' and column_name in ('plan', 'plan_digest')", Integer.class))
                        .isZero();
                assertSealed(owner, context.getBean(SealedPlans.class));
            }
            assertThat(output.getAll()).doesNotContain("Plaintext25").doesNotContain("plain25@coop.example")
                    .doesNotContain("380671112525").doesNotContain(key);
        }
    }

    private static void plaintextPreviews(JdbcTemplate owner) {
        owner.update("insert into tenant (id, name) values (?, 'First'), (?, 'Second')", FIRST, SECOND);
        insert(owner, FIRST_PREVIEW, FIRST, "PREVIEW", "30 minutes", plan("Plaintext25First"), FIRST_DIGEST);
        insert(owner, EXPIRED_PREVIEW, FIRST, "PREVIEW", "-1 minute", plan("Plaintext25Expired"), FIRST_DIGEST);
        insert(owner, WITH_ERRORS, FIRST, "HAS_ERRORS", "30 minutes", null, null);
        insert(owner, SECOND_PREVIEW, SECOND, "PREVIEW", "30 minutes", plan("Plaintext25Second"), SECOND_DIGEST);
    }

    /** A stored plan as the previous release wrote it, with a member's contacts. */
    private static String plan(String lastName) {
        return """
                {"version": 1, "plots": [{"row": 2, "number": "25-1", "kind": "PLOT", "areaM2": "600"}],
                 "members": [{"row": 2, "externalId": "M-1", "lastName": "%s", "firstName": "Plain",
                              "phones": ["+380671112525"], "email": "plain25@coop.example"}],
                 "ownership": [], "meters": [], "openingBalances": []}""".formatted(lastName);
    }

    private static void insert(JdbcTemplate owner, UUID id, UUID tenant, String status, String validFor, String plan,
            String digest) {
        owner.update("insert into import_run (id, tenant_id, file_name, created_by_subject, created_by_name, "
                + "created_at, expires_at, status, plan, plan_digest) values (?, ?, 'f.xlsx', 's', 'n', "
                + "now() - interval '1 hour', now() + ?::interval, ?, ?::jsonb, ?)", id, tenant, validFor, status, plan,
                digest);
    }

    private static void assertSealed(JdbcTemplate owner, SealedPlans plans) {
        assertOpens(owner, plans, FIRST, FIRST_PREVIEW, "Plaintext25First", FIRST_DIGEST);
        assertOpens(owner, plans, FIRST, EXPIRED_PREVIEW, "Plaintext25Expired", FIRST_DIGEST);
        assertOpens(owner, plans, SECOND, SECOND_PREVIEW, "Plaintext25Second", SECOND_DIGEST);
        assertThat(owner.queryForObject("select string_agg(row_to_json(r)::text || encode(r.plan_enc, 'escape'), ' ') "
                + "from import_run r", String.class)).doesNotContain("Plaintext25").doesNotContain("plain25@")
                .doesNotContain("380671112525");
    }

    private static void assertOpens(JdbcTemplate owner, SealedPlans plans, UUID tenant, UUID run, String lastName,
            String digest) {
        byte[] sealed = owner.queryForObject("select plan_enc from import_run where id = ?", byte[].class, run);
        SealedPlans.Opened opened = TenantContext.runAs(tenant, () -> plans.open(tenant, run, sealed));
        assertThat(opened.digest()).isEqualTo(digest);
        JsonNode expected = JsonMapper.shared().readTree(plan(lastName));
        assertThat(JsonMapper.shared().<JsonNode>valueToTree(opened.plan())).isEqualTo(expected);
        // the plan, as the commit reads it back
        StoredPlan.Sheets sheets = StoredPlan.read(opened.plan());
        assertThat(sheets.members().rows().getFirst().email()).isEqualTo("plain25@coop.example");
        assertThat(sheets.plots().rows()).hasSize(1);
    }

    /** Every sealed plan, as text, to compare two states. */
    private static String ciphertexts(JdbcTemplate owner) {
        return owner.queryForObject("select string_agg(id || ':' || coalesce(encode(plan_enc, 'hex'), '-'), ',' "
                + "order by id) from import_run", String.class);
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
                        "--spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
                        "--server.port=0",
                        "--spring.main.banner-mode=off");
    }
}
