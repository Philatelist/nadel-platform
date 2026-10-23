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

    /** The charges of a run of the kind (the charges a run booked: {@code REGULAR}), in no particular order. */
    List<ChargeRecord> findByRunIdAndKind(UUID runId, ChargeKind kind);

    /** The charges among {@code chargeIds} that a reversal cancels. */
    @Query("select c.reversesChargeId from ChargeRecord c where c.reversesChargeId in :ids")
    List<UUID> reversedAmong(@Param("ids") Collection<UUID> chargeIds);

    /** The number of charges of the kind of each run among {@code runIds}, as pairs of run id and count. */
    @Query("select c.runId, count(c) from ChargeRecord c where c.runId in :runIds and c.kind = :kind group by c.runId")
    List<Object[]> countByRun(@Param("runIds") Collection<UUID> runIds, @Param("kind") ChargeKind kind);

    /** Whether a reversal of the charge exists (a charge is reversed at most once). */
    boolean existsByReversesChargeId(UUID chargeId);

    /**
     * The source keys among {@code keys} that a charge has (a reversed charge keeps its key, so it is never booked
     * again). Reversals have no key.
     */
    @Query("select c.sourceKey from ChargeRecord c where c.sourceKey in :keys")
    List<String> existingSourceKeys(@Param("keys") Collection<String> keys);

    /** The opening balances of the plots that no reversal cancels, in no particular order (the Excel import). */
    @Query("select c from ChargeRecord c where c.plotId in :plotIds and c.kind = :kind and not exists "
            + "(select r.id from ChargeRecord r where r.reversesChargeId = c.id)")
    List<ChargeRecord> findStandingOfKind(@Param("plotIds") Collection<UUID> plotIds,
            @Param("kind") ChargeKind kind);

    /** The charges of the kind and source of the plots and charge types, in no particular order (a recalculation). */
    @Query("select c from ChargeRecord c where c.plotId in :plotIds and c.chargeTypeId in :typeIds "
            + "and c.kind = :kind and c.source = :source")
    List<ChargeRecord> findOfPlotsAndTypes(@Param("plotIds") Collection<UUID> plotIds,
            @Param("typeIds") Collection<UUID> chargeTypeIds, @Param("kind") ChargeKind kind,
            @Param("source") ChargeSource source);

    /** The reversals and adjustments of the charges, in no particular order (a recalculation's booked chains). */
    @Query("select c from ChargeRecord c where c.reversesChargeId in :ids or c.adjustsChargeId in :ids")
    List<ChargeRecord> findReversalsAndAdjustmentsOf(@Param("ids") Collection<UUID> chargeIds);

    /** The plots among {@code plotIds} that have at least one charge of any kind. */
    @Query("select distinct c.plotId from ChargeRecord c where c.plotId in :plotIds")
    List<UUID> plotIdsWithCharges(@Param("plotIds") Collection<UUID> plotIds);
}
