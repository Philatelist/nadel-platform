package app.nadel.ledger.internal;

import app.nadel.meters.MeterKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One node of a losses run as it was booked (table {@code losses_run_node}, V17): the numbers (kWh rounded to three
 * decimals for display), the outcome, the warning and the plot shares, as codes, ids and numbers only. Written once;
 * the only change is {@code run_status} with the reversal of the run, made by a bulk update
 * ({@link LossesRunNodeRepository#markReversed}), so the entity is {@link Immutable}. A booked {@code CHARGED} row keeps
 * its days from any other run of the node (exclusion constraint {@code losses_run_node_no_overlap_ex}).
 */
@Entity
@Immutable
@Table(name = "losses_run_node")
class LossesRunNodeRecord {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "node_meter_id", nullable = false)
    private UUID nodeMeterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "node_kind", nullable = false)
    private MeterKind nodeKind;

    @Column(name = "node_name")
    private String nodeName;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Column(name = "run_status", nullable = false)
    private String runStatus;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LossesCalculator.Outcome outcome;

    private BigDecimal consumption;

    private BigDecimal children;

    private BigDecimal losses;

    private BigDecimal pct;

    @Column(nullable = false)
    private boolean calculated;

    @Column(nullable = false)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> warning;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> plots;

    protected LossesRunNodeRecord() {
    }

    static LossesRunNodeRecord of(UUID tenantId, UUID runId, UUID nodeMeterId, MeterKind nodeKind,
            @Nullable String nodeName, LocalDate from, LocalDate to, int position, LossesCalculator.Outcome outcome, @Nullable BigDecimal consumption,
            @Nullable BigDecimal children, @Nullable BigDecimal losses, @Nullable BigDecimal pct, boolean calculated,
            BigDecimal amount, @Nullable Map<String, Object> warning, List<Map<String, Object>> plots) {
        LossesRunNodeRecord node = new LossesRunNodeRecord();
        node.id = UUID.randomUUID();
        node.tenantId = tenantId;
        node.runId = runId;
        node.nodeMeterId = nodeMeterId;
        node.nodeKind = nodeKind;
        node.nodeName = nodeName;
        node.fromDate = from;
        node.toDate = to;
        node.runStatus = LossesRunRecord.Status.BOOKED.name();
        node.position = position;
        node.outcome = outcome;
        node.consumption = consumption;
        node.children = children;
        node.losses = losses;
        node.pct = pct;
        node.calculated = calculated;
        node.amount = amount;
        node.warning = warning;
        node.plots = plots;
        return node;
    }

    UUID getRunId() {
        return runId;
    }

    UUID getNodeMeterId() {
        return nodeMeterId;
    }

    MeterKind getNodeKind() {
        return nodeKind;
    }

    @Nullable String getNodeName() {
        return nodeName;
    }

    int getPosition() {
        return position;
    }

    LossesCalculator.Outcome getOutcome() {
        return outcome;
    }

    @Nullable BigDecimal getConsumption() {
        return consumption;
    }

    @Nullable BigDecimal getChildren() {
        return children;
    }

    @Nullable BigDecimal getLosses() {
        return losses;
    }

    @Nullable BigDecimal getPct() {
        return pct;
    }

    boolean isCalculated() {
        return calculated;
    }

    BigDecimal getAmount() {
        return amount;
    }

    @Nullable Map<String, Object> getWarning() {
        return warning;
    }

    List<Map<String, Object>> getPlots() {
        return plots == null ? List.of() : plots;
    }
}
