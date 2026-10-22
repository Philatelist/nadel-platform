package app.nadel.ledger.internal;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface ChargeRepository extends JpaRepository<ChargeRecord, UUID> {

    /** The sum of the charges of one charge type booked in one billing month (reversals count negative). */
    record MonthTypeTotal(UUID billingMonthId, UUID chargeTypeId, BigDecimal amount) {
    }

    /** Every charge of the plot, reversals included, in no particular order. */
    List<ChargeRecord> findByPlotId(UUID plotId);

    /** Every charge of the plots, reversals included, in no particular order. */
    List<ChargeRecord> findByPlotIdIn(Collection<UUID> plotIds);

    /** The totals of every billing month per charge type; months and types without a charge are absent. */
    @Query("select new app.nadel.ledger.internal.ChargeRepository$MonthTypeTotal(c.billingMonthId, c.chargeTypeId, "
            + "sum(c.amount)) from ChargeRecord c group by c.billingMonthId, c.chargeTypeId")
    List<MonthTypeTotal> totalsPerMonthAndType();

    /** The totals of one billing month per charge type; types without a charge are absent. */
    @Query("select new app.nadel.ledger.internal.ChargeRepository$MonthTypeTotal(c.billingMonthId, c.chargeTypeId, "
            + "sum(c.amount)) from ChargeRecord c where c.billingMonthId = :monthId "
            + "group by c.billingMonthId, c.chargeTypeId")
    List<MonthTypeTotal> totalsOfMonth(@Param("monthId") UUID monthId);

    /** Whether a reversal of the charge exists (a charge is reversed at most once). */
    boolean existsByReversesChargeId(UUID chargeId);

    /**
     * The source keys among {@code keys} that a charge has (a reversed charge keeps its key, so it is never booked
     * again). Reversals have no key.
     */
    @Query("select c.sourceKey from ChargeRecord c where c.sourceKey in :keys")
    List<String> existingSourceKeys(@Param("keys") Collection<String> keys);

    /** The plots among {@code plotIds} that have at least one charge of any kind. */
    @Query("select distinct c.plotId from ChargeRecord c where c.plotId in :plotIds")
    List<UUID> plotIdsWithCharges(@Param("plotIds") Collection<UUID> plotIds);
}
