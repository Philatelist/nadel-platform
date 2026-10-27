package app.nadel.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.tenancy.TenantContext;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The open reading interval items of the "not charged" queue per meter ({@link LedgerReadModel#openIntervalItems}, the
 * missing readings report of feature 013 §3.6), on a cooperative of its own: meter 1 has two open interval items and a
 * resolved one, meter 2 an open item of another kind (a common consumer), meter 3 none.
 */
class LedgerOpenIntervalItemsTests extends FreshCooperativeSupport {

    @Autowired
    LedgerReadModel readModel;

    @Autowired
    TransactionTemplate tx;

    private UUID m1;
    private UUID m2;
    private UUID m3;
    private UUID otherItem;

    @BeforeEach
    void fillTheQueue() {
        UUID p1 = plot("1", "600");
        UUID p2 = plot("2", "600");
        UUID p3 = plot("3", "600");
        m1 = meter(p1);
        m2 = meter(p2);
        m3 = meter(p3);
        UUID electricity = typeId("ELECTRICITY");
        item("INTERVAL", p1, electricity, m1, "2025-09-01", "2025-09-30", "OPEN");
        item("INTERVAL", p1, electricity, m1, "2025-10-01", "2025-10-31", "OPEN");
        item("INTERVAL", p1, electricity, m1, "2025-08-01", "2025-08-31", "RESOLVED");
        item("COMMON_CONSUMER", null, null, m2, "2025-10-01", "2025-10-31", "OPEN");
    }

    @AfterEach
    void removeOtherCooperativeRows() {
        if (otherItem != null) {
            UUID item = otherItem;
            asLedgerOwner(sql -> sql.update("delete from not_charged_item where id = ?", item));
            otherItem = null;
        }
    }

    @Test
    void everyMeterAskedHasTheNumberOfItsOpenIntervalItems() {
        Map<UUID, Integer> items = openItems(tenant, List.of(m3, m1, m2, m1));

        assertThat(items).containsExactly(Map.entry(m3, 0), Map.entry(m1, 2), Map.entry(m2, 0));
        assertThat(openItems(tenant, List.of())).isEmpty();
    }

    @Test
    void theItemsOfAnotherCooperativeAreNeverCounted() {
        Side other = side(OTHER_TENANT);
        otherItem = UUID.randomUUID();
        ownerJdbc().update("insert into not_charged_item (id, tenant_id, item_key, kind, plot_id, charge_type_id, "
                + "meter_id, from_date, to_date, reason) values (?, ?, ?, 'INTERVAL', ?, ?, ?, date '2025-10-01', "
                + "date '2025-10-31', 'NO_TARIFF_FOR_ALL_DAYS')", otherItem, OTHER_TENANT, "TEST:" + otherItem,
                other.plot(), other.chargeType(), other.meter());

        assertThat(openItems(tenant, List.of(other.meter(), m1))).containsExactly(Map.entry(other.meter(), 0),
                Map.entry(m1, 2));
        assertThat(openItems(OTHER_TENANT, List.of(other.meter(), m1))).containsExactly(Map.entry(other.meter(), 1),
                Map.entry(m1, 0));
    }

    @Test
    void itRunsOnlyInTheCallersTransaction() {
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> readModel.openIntervalItems(List.of(m1))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---- fixture

    private Map<UUID, Integer> openItems(UUID cooperative, Collection<UUID> meters) {
        return TenantContext.runAs(cooperative, () -> tx.execute(status -> readModel.openIntervalItems(meters)));
    }

    private UUID meter(UUID plot) {
        UUID id = UUID.randomUUID();
        owner().update("insert into meter (id, tenant_id, kind, resource, serial, plot_id, installed_on, "
                + "initial_value) values (?, ?, 'PLOT', 'ELECTRICITY', ?, ?, date '2025-01-01', 0)", id, tenant,
                "NC-" + id.toString().substring(0, 8), plot);
        return id;
    }

    private void item(String kind, UUID plot, UUID chargeType, UUID meter, String from, String to, String status) {
        UUID id = UUID.randomUUID();
        owner().update("insert into not_charged_item (id, tenant_id, item_key, kind, plot_id, charge_type_id, "
                + "meter_id, from_date, to_date, reason, status, resolved_at) values (?, ?, ?, ?, ?, ?, ?, ?::date, "
                + "?::date, ?, ?, case when ? = 'OPEN' then null else now() end)", id, tenant, "TEST:" + id, kind,
                plot, chargeType, meter, from, to,
                "INTERVAL".equals(kind) ? "NO_TARIFF_FOR_ALL_DAYS" : "UNKNOWN_CONSUMER_VALUE", status, status);
    }
}
