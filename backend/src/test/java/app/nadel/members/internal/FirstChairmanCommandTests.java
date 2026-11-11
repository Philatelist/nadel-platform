package app.nadel.members.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.DemoLedgerData;
import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import app.nadel.identity.FakeSignInAccounts;
import app.nadel.problem.ApiProblemException;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.mockito.Mockito;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The first-chairman command (feature 020, technical considerations 2.1): the runner {@link FirstChairmanCommand} is
 * built here with the context's beans and arguments in a {@link MockEnvironment}, and its exit code is recorded
 * instead of stopping the JVM. The Keycloak accounts are the in-memory fake ({@link FakeSignInAccounts}). Every test
 * uses a fresh e-mail, so a fresh cooperative, removed afterwards.
 * <p>
 * The printed link is checked on standard output ({@link OutputCaptureExtension}: the log lines go there too, so
 * "exactly once" covers them) and against every log event (a Logback {@link ListAppender} on the root logger), the
 * audit log and the outbox.
 */
@ExtendWith(OutputCaptureExtension.class)
class FirstChairmanCommandTests extends IntegrationTest {

    private static final Pattern LINK = Pattern.compile("(\\S+/set-password\\?token=([A-Za-z0-9_-]+))");
    private static final String PASSWORD = "first-chairman-password";

    @Autowired
    FirstChairman work;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    FakeSignInAccounts accounts;

    @Autowired
    MockMvc mvc;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<Integer> exitCodes = new ArrayList<>();
    private final List<UUID> cooperatives = new ArrayList<>();
    private InvitationRequests http;

    @BeforeEach
    void setUp() {
        http = new InvitationRequests(mvc);
        logs.start();
        root().addAppender(logs);
    }

    @AfterEach
    void cleanUp() {
        root().detachAppender(logs);
        logs.stop();
        await().atMost(Duration.ofSeconds(10)).until(() -> ownerJdbc().queryForObject(
                "select count(*) from event_publication where completion_date is null", Integer.class) == 0);
        accounts.reset();
        for (UUID tenant : cooperatives) {
            removeCooperative(tenant);
        }
    }

    private static Logger root() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    private static String freshEmail() {
        return "chairman-" + UUID.randomUUID() + "@coop.example";
    }

    /** Runs the command through the runner's {@code run} with these arguments; answers the exit code. */
    private int command(String cooperative, String email, String lastName, String firstName, boolean resend) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("nadel.command", "first-chairman")
                .withProperty("nadel.first-chairman.cooperative", cooperative)
                .withProperty("nadel.first-chairman.email", email)
                .withProperty("nadel.first-chairman.last-name", lastName)
                .withProperty("nadel.first-chairman.first-name", firstName)
                .withProperty("nadel.first-chairman.resend", Boolean.toString(resend));
        if (!email.isBlank() && !cooperatives.contains(FirstChairman.tenantIdOf(email))) {
            cooperatives.add(FirstChairman.tenantIdOf(email));
        }
        new FirstChairmanCommand(work, environment, jdbc, transactionManager, exitCodes::add)
                .run(new DefaultApplicationArguments());
        return exitCodes.getLast();
    }

    private int command(String email, boolean resend) {
        return command("СТ «Перший голова»", email, "Іваненко", "Петро", resend);
    }

    private static List<String> tokensIn(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = LINK.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group(2));
        }
        return tokens;
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            count++;
        }
        return count;
    }

    private UUID chairmanOf(UUID tenant, String email) {
        return memberIdByEmail(tenant, email);
    }

    private String state(UUID member) {
        List<String> states = ownerJdbc().queryForList("select state from member_access where member_id = ?",
                String.class, member);
        return states.isEmpty() ? null : states.getFirst();
    }

    private int count(String sql, Object... args) {
        Integer value = ownerJdbc().queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    // ---- a new cooperative -----------------------------------------------------------------------------------------

    @Test
    void aNewCooperativeAndItsChairmanAreCreatedAndTheLinkIsPrintedExactlyOnce(CapturedOutput output) {
        String email = freshEmail();
        UUID tenant = FirstChairman.tenantIdOf(email);

        assertThat(command(email, false)).isEqualTo(FirstChairmanCommand.OK);

        assertThat(ownerJdbc().queryForObject("select name from tenant where id = ?", String.class, tenant))
                .isEqualTo("СТ «Перший голова»");
        assertThat(ownerJdbc().queryForList("select base_code from role where tenant_id = ? order by base_code",
                String.class, tenant)).containsExactly("CHAIRMAN", "MEMBER", "TREASURER");
        assertThat(count("select count(*) from charge_type where tenant_id = ?", tenant)).isPositive();
        assertThat(count("select count(*) from money_account where tenant_id = ?", tenant)).isEqualTo(2);

        UUID chairman = chairmanOf(tenant, email);
        assertThat(ownerJdbc().queryForMap("select last_name, first_name from member where id = ?", chairman))
                .containsEntry("last_name", "Іваненко").containsEntry("first_name", "Петро");
        assertThat(ownerJdbc().queryForList("select r.base_code from member_role mr join role r on r.id = mr.role_id "
                + "where mr.member_id = ?", String.class, chairman)).containsExactly("CHAIRMAN");
        assertThat(state(chairman)).isEqualTo("INVITED");
        assertThat(ownerJdbc().queryForList("select sent_at from member_invitation where member_id = ?", chairman))
                .singleElement().satisfies(row -> assertThat(row.get("sent_at")).isNotNull());
        assertThat(accounts.byLogin(email)).singleElement()
                .satisfies(account -> assertThat(account.tenantId()).isEqualTo(tenant));

        List<String> tokens = tokensIn(output.getOut());
        assertThat(tokens).hasSize(1);
        String token = tokens.getFirst();
        assertThat(occurrences(output.getAll(), token)).as("the link is printed once, logs included").isEqualTo(1);
        assertThat(output.getOut()).contains("valid until").contains("Cooperative created: " + tenant);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(ownerJdbc().queryForList(
                "select action from audit_entry where entity_id = ? and actor_subject = ? and actor_member_id is null",
                String.class, chairman, FirstChairman.ACTOR))
                .containsExactlyInAnyOrder("MEMBER_CREATED", "MEMBER_ROLES_CHANGED", "MEMBER_INVITED"));
        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains(token)
                || event.getFormattedMessage().contains(email));
        assertThat(count("select count(*) from audit_entry where cast(changes as text) like ? or entity_label like ?",
                "%" + token + "%", "%" + token + "%")).isZero();
        assertThat(count("select count(*) from event_publication where serialized_event like ?", "%" + token + "%"))
                .isZero();
    }

    @Test
    void aSecondRunCreatesNothingAndPrintsNoLink(CapturedOutput output) {
        String email = freshEmail();
        UUID tenant = FirstChairman.tenantIdOf(email);
        assertThat(command(email, false)).isEqualTo(FirstChairmanCommand.OK);
        int printedBefore = output.getOut().length();
        int members = count("select count(*) from member where tenant_id = ?", tenant);
        int invitations = count("select count(*) from member_invitation where tenant_id = ?", tenant);
        int roles = count("select count(*) from role where tenant_id = ?", tenant);

        // the e-mail in another case names the same cooperative
        assertThat(command(email.toUpperCase(java.util.Locale.ROOT), false)).isEqualTo(FirstChairmanCommand.OK);

        String second = output.getOut().substring(printedBefore);
        assertThat(tokensIn(second)).isEmpty();
        assertThat(second).contains("Cooperative exists: " + tenant).contains("was invited on")
                .contains("--resend");
        assertThat(count("select count(*) from member where tenant_id = ?", tenant)).isEqualTo(members);
        assertThat(count("select count(*) from member_invitation where tenant_id = ?", tenant)).isEqualTo(invitations);
        assertThat(count("select count(*) from role where tenant_id = ?", tenant)).isEqualTo(roles);
        assertThat(count("select count(*) from tenant where id = ?", tenant)).isEqualTo(1);
    }

    @Test
    void resendPrintsANewLinkAndTheOldOneNoLongerWorks(CapturedOutput output) throws Exception {
        String email = freshEmail();
        assertThat(command(email, false)).isEqualTo(FirstChairmanCommand.OK);
        String first = tokensIn(output.getOut()).getFirst();

        assertThat(command(email, true)).isEqualTo(FirstChairmanCommand.OK);

        List<String> tokens = tokensIn(output.getOut());
        assertThat(tokens).hasSize(2);
        String second = tokens.getLast();
        assertThat(second).isNotEqualTo(first);
        assertThat(occurrences(output.getAll(), second)).isEqualTo(1);
        http.inspect(first).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        http.inspect(second).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        UUID chairman = chairmanOf(FirstChairman.tenantIdOf(email), email);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(ownerJdbc().queryForList(
                "select action from audit_entry where entity_id = ? and action = 'MEMBER_INVITE_RESENT'",
                String.class, chairman)).hasSize(1));
    }

    @Test
    void acceptingThePrintedLinkGivesTheChairmanAccess(CapturedOutput output) throws Exception {
        String email = freshEmail();
        UUID tenant = FirstChairman.tenantIdOf(email);
        assertThat(command(email, false)).isEqualTo(FirstChairmanCommand.OK);
        String token = tokensIn(output.getOut()).getFirst();
        UUID chairman = chairmanOf(tenant, email);
        String subject = subjectOf(tenant, chairman);

        http.inspect(token).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        http.accept(token, PASSWORD).andExpect(status().isNoContent());

        assertThat(state(chairman)).isEqualTo("ACTIVE");
        assertThat(accounts.account(subject).orElseThrow().password()).isEqualTo(PASSWORD);
        String bearer = TestTokens.bearer(tenant, subject, email);
        http.me(bearer).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(chairman.toString()))
                .andExpect(jsonPath("$.roles[0].baseCode").value("CHAIRMAN"))
                .andExpect(jsonPath("$.permissions", hasItem("MEMBERS_INVITE")));

        // without a mailbox the board's invitation over HTTP is still refused: only the command prints a link
        UUID another = newMember(tenant, "Коваль", "Олена", null, freshEmail());
        http.invite(bearer, another).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_CONFIGURED"));

        int printedBefore = output.getOut().length();
        assertThat(command(email, true)).isEqualTo(FirstChairmanCommand.OK);
        String again = output.getOut().substring(printedBefore);
        assertThat(again).contains("already has access");
        assertThat(tokensIn(again)).isEmpty();
    }

    // ---- refusals -------------------------------------------------------------------------------------------------

    @Test
    void invalidArgumentsExitWithANonZeroCodeAndCreateNothing(CapturedOutput output) {
        assertThat(command("СТ «Ніщо»", "not-an-email", "Іваненко", "Петро", false))
                .isEqualTo(FirstChairmanCommand.INVALID);
        assertThat(output.getErr()).contains("INVALID_ARGUMENTS").contains("email");
        assertThat(count("select count(*) from tenant where id = ?", FirstChairman.tenantIdOf("not-an-email")))
                .isZero();

        String email = freshEmail();
        assertThat(command("СТ «Ніщо»", email, "Іваненко", " ", false)).isEqualTo(FirstChairmanCommand.INVALID);
        assertThat(command(" ", email, "Іваненко", "Петро", false)).isEqualTo(FirstChairmanCommand.INVALID);
        assertThat(command("СТ «Ніщо»", "", "Іваненко", "Петро", false)).isEqualTo(FirstChairmanCommand.INVALID);
        assertThat(count("select count(*) from tenant where id = ?", FirstChairman.tenantIdOf(email))).isZero();
        assertThat(output.getErr()).doesNotContain(email);
    }

    @Test
    void anEmailThatSignsInToAnotherCooperativeFailsWithANonZeroCode(CapturedOutput output) {
        String email = freshEmail();
        accounts.add(email, OTHER_TENANT, true);

        assertThat(command(email, false)).isEqualTo(FirstChairmanCommand.FAILED);

        assertThat(output.getErr()).contains("EMAIL_IN_OTHER_COOPERATIVE");
        assertThat(tokensIn(output.getOut())).isEmpty();
        UUID chairman = chairmanOf(FirstChairman.tenantIdOf(email), email);
        assertThat(state(chairman)).isNull();
    }

    @Test
    void anActiveMemberWithTheEmailWithoutTheChairmanRoleIsRefused(CapturedOutput output) {
        String email = freshEmail();
        UUID tenant = FirstChairman.tenantIdOf(email);
        assertThat(command(email, false)).isEqualTo(FirstChairmanCommand.OK);
        UUID chairman = chairmanOf(tenant, email);
        inTenant(tenant, sql -> sql.update("delete from member_role where member_id = ?", chairman));

        assertThat(command(email, true)).isEqualTo(FirstChairmanCommand.FAILED);

        assertThat(output.getErr()).contains("MEMBER_NOT_CHAIRMAN");
        assertThat(ownerJdbc().queryForList("select role_id from member_role where member_id = ?", chairman))
                .as("the command never gives a role to an existing member").isEmpty();
    }

    @Test
    void aProblemOrAnUnexpectedErrorOfTheWorkIsAnErrorCodeAndNeverTheStackTrace(CapturedOutput output) throws Exception {
        FirstChairman failing = Mockito.mock(FirstChairman.class);
        Mockito.doThrow(ApiProblemException.notFound()).doThrow(new IllegalStateException("secret detail"))
                .when(failing).run(Mockito.any(), Mockito.any());
        MockEnvironment environment = new MockEnvironment().withProperty("nadel.command", "first-chairman");

        for (int run = 0; run < 2; run++) {
            new FirstChairmanCommand(failing, environment, jdbc, transactionManager, exitCodes::add)
                    .run(new DefaultApplicationArguments());
        }

        assertThat(exitCodes).containsExactly(FirstChairmanCommand.FAILED, FirstChairmanCommand.FAILED);
        assertThat(output.getErr()).contains("Error: IllegalStateException. See the application log.")
                .doesNotContain("secret detail");
    }

    @Test
    void theArgumentsNeverPrintTheirPersonalValues() {
        FirstChairman.Arguments arguments = new FirstChairman.Arguments("СТ «Секрет»", "private@coop.example",
                "Іваненко", "Петро", true);

        assertThat(arguments.toString()).isEqualTo("Arguments[resend=true]");
    }

    @Test
    void theCooperativeIdDependsOnTheEmailOnly() {
        assertThat(FirstChairman.tenantIdOf(" Chairman@Example.org "))
                .isEqualTo(FirstChairman.tenantIdOf("chairman@example.org"))
                .isEqualTo(UUID.nameUUIDFromBytes("nadel:cooperative:chairman@example.org"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isNotEqualTo(FirstChairman.tenantIdOf("treasurer@example.org"));
    }

    /**
     * Removes everything a run made in the cooperative (the owner bypasses RLS; the ledger seed needs maintenance). Also
     * used by {@link FirstChairmanJarCommandTests}.
     */
    static void removeCooperative(UUID tenant) {
        JdbcTemplate owner = ownerJdbc();
        owner.update("delete from member_invitation where tenant_id = ?", tenant);
        owner.update("delete from member_access where tenant_id = ?", tenant);
        DemoLedgerData.asLedgerOwner(owner, sql -> {
            sql.update("delete from money_account where tenant_id = ?", tenant);
            sql.update("delete from expense_category where tenant_id = ?", tenant);
        });
        for (String table : List.of("audit_entry", "member_phone_index", "member_role", "role_permission", "role",
                "member", "charge_type", "cooperative_settings")) {
            owner.update("delete from " + table + " where tenant_id = ?", tenant);
        }
        deleteDataKeys(tenant);
        owner.update("delete from tenant where id = ?", tenant);
    }
}
