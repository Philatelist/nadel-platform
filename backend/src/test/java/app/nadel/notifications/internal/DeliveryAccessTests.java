package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Who may send invoices and see the delivery screen (feature 017 §2.4, ADR 0009): only a holder of
 * {@code INVOICES_ISSUE} (the treasurer and the chairman by default); a member, a role without it and the platform
 * operator get 403 and change nothing; another cooperative neither sees nor sends nor retries this one's deliveries.
 */
class DeliveryAccessTests extends DeliveryTestSupport {

    private UUID invoice;
    private UUID delivery;

    @BeforeEach
    void oneFailedDelivery() throws Exception {
        mailbox("wrong-password");
        UUID onEmail = member("Пошта", "EMAIL", "mail@delivery.test");
        UUID plot = plot("10", onEmail);
        charge(plot, "100.00");
        buildInvoices();
        sentMonth(false, false);
        dispatch("EMAIL");
        invoice = invoiceOf(plot);
        delivery = (UUID) deliveries().getFirst().get("id");
        assertThat(deliveries().getFirst()).containsEntry("status", "FAILED");
    }

    @Test
    void theTreasurerAndTheChairmanMaySendAndSeeTheDeliveries() throws Exception {
        for (String bearer : new String[] {treasurer, chairman}) {
            sendMonth(bearer, true, false).andExpect(status().isOk());
            sendOne(bearer, invoice).andExpect(status().isOk());
            listDeliveries(bearer, null).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1));
            printDone(bearer).andExpect(status().isOk());
        }
        retry(treasurer, delivery).andExpect(status().isNoContent());
    }

    @Test
    void aMemberARoleWithoutTheRightAndTheOperatorAreRefused() throws Exception {
        String member = person("MEMBER", "Член", "Простий", "plain-" + UUID.randomUUID() + "@delivery.test");
        String viewer = customRole("CHARGES_VIEW", "MEMBERS_VIEW", "SETTINGS_MANAGE");
        String operator = TestTokens.token().email("operator@nadel.local").realmRoles("PLATFORM_OPERATOR").bearer();

        for (String bearer : new String[] {member, viewer, operator}) {
            denied(sendMonth(bearer, false, false));
            denied(sendMonth(bearer, true, false));
            denied(sendOne(bearer, invoice));
            denied(listDeliveries(bearer, null));
            denied(retry(bearer, delivery));
            denied(printDone(bearer));
        }
        assertThat(deliveries()).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("resends")).isEqualTo(0);
        });
    }

    @Test
    void anotherCooperativeNeitherSeesNorSendsNorRetriesThisOnesDeliveries() throws Exception {
        String email = "other-chairman-" + UUID.randomUUID() + "@other.local";
        UUID other = newMember(OTHER_TENANT, email);
        giveBaseRole(OTHER_TENANT, other, "CHAIRMAN");
        String otherChairman = TestTokens.bearer(OTHER_TENANT, "sub-" + email, email);

        listDeliveries(otherChairman, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + delivery + "')]").isEmpty());
        retry(otherChairman, delivery).andExpect(status().isNotFound());
        sendOne(otherChairman, invoice).andExpect(status().isNotFound());
        sendMonth(otherChairman, false, false).andExpect(status().isOk());

        assertThat(deliveries()).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("resends")).isEqualTo(0);
        });
        assertThat(ownerJdbc().queryForObject("select count(*) from delivery where tenant_id = ?", Integer.class,
                OTHER_TENANT)).isZero();
    }

    private static void denied(ResultActions call) throws Exception {
        call.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    /** A member whose only role is a custom role with the permissions; returns a token. */
    private String customRole(String... permissions) {
        String token = person("MEMBER", "Мельник", "Петро", "custom-" + UUID.randomUUID() + "@delivery.test");
        UUID role = UUID.randomUUID();
        ownerJdbc().update("delete from member_role where member_id = ?", lastPerson());
        ownerJdbc().update("insert into role (id, tenant_id, name) values (?, ?, ?)", role, tenant, "Role " + role);
        for (String permission : permissions) {
            ownerJdbc().update("insert into role_permission (tenant_id, role_id, permission) values (?, ?, ?)",
                    tenant, role, permission);
        }
        ownerJdbc().update("insert into member_role (tenant_id, member_id, role_id) values (?, ?, ?)", tenant,
                lastPerson(), role);
        return token;
    }
}
