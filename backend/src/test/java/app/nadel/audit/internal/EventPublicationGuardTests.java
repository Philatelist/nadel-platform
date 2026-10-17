package app.nadel.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * event_publication is infrastructure: no tenant_id, no RLS, so resubmission at startup (no tenant) works.
 * If someone adds RLS to it, event delivery silently breaks; this guard fails first.
 */
class EventPublicationGuardTests extends IntegrationTest {

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void rowLevelSecurityIsOffForEventPublication() {
        Boolean rls = jdbc.queryForObject(
                "select relrowsecurity from pg_class where oid = 'public.event_publication'::regclass", Boolean.class);

        assertThat(rls).isFalse();
    }

    @Test
    void applicationRoleCompletesPublicationWithoutTenant() {
        UUID id = UUID.randomUUID();

        Integer inserted = tx.execute(s -> jdbc.update(
                "insert into event_publication (id, listener_id, event_type, serialized_event, publication_date)"
                        + " values (?, 'listener', 'type', '{}', now())", id));
        Integer completed = tx.execute(s -> jdbc.update(
                "update event_publication set completion_date = now() where id = ?", id));
        Integer completedRows = tx.execute(s -> jdbc.queryForObject(
                "select count(*) from event_publication where id = ? and completion_date is not null",
                Integer.class, id));

        assertThat(inserted).isEqualTo(1);
        assertThat(completed).isEqualTo(1);
        assertThat(completedRows).isEqualTo(1);
    }
}
