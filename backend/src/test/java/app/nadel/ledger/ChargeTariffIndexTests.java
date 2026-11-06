package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.IntegrationTest;
import org.junit.jupiter.api.Test;

/** V37: the tariff usage checks (everCharged, lastChargedDay, delete guard) look charges up by tariff through an index. */
class ChargeTariffIndexTests extends IntegrationTest {

    @Test
    void chargeHasAnIndexLeadingWithTenantAndTariff() {
        String definition = ownerJdbc().queryForObject(
                "select indexdef from pg_indexes where tablename = 'charge' and indexname = 'charge_tariff_idx'",
                String.class);
        assertThat(definition).contains("(tenant_id, tariff_id)").contains("WHERE (tariff_id IS NOT NULL)");
    }
}
