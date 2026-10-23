package app.nadel.ledger.internal;

import app.nadel.settings.CommonConsumerSplit;
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
 * One common consumer of a run as it was booked (table {@code common_consumer_run_line}, V18): its split, outcome,
 * consumption (kWh rounded to three decimals for display), warning and plot shares, as codes, ids and numbers only,
 * and its name as the run showed it (user content). Written once; the only change is {@code run_status} with the
 * reversal of the run, made by a bulk update ({@link CommonConsumerRunLineRepository#markReversed}), so the entity is
 * {@link Immutable}. A booked {@code CHARGED} row keeps its days from any other run of the consumer (exclusion
 * constraint {@code common_consumer_run_line_no_overlap_ex}).
 */
@Entity
@Immutable
@Table(name = "common_consumer_run_line")
class CommonConsumerRunLineRecord {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "consumer_meter_id", nullable = false)
    private UUID consumerMeterId;

    @Column(name = "consumer_name")
    private String consumerName;

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
    private CommonConsumerSplit split;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommonConsumerPlanning.Outcome outcome;

    private BigDecimal consumption;

    @Column(nullable = false)
    private boolean calculated;

    @Column(name = "plot_days", nullable = false)
    private long plotDays;

    @Column(nullable = false)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> warning;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> plots;

    protected CommonConsumerRunLineRecord() {
    }

    static CommonConsumerRunLineRecord of(UUID tenantId, UUID runId, UUID consumerMeterId,
            @Nullable String consumerName, LocalDate from, LocalDate to, int position, CommonConsumerSplit split,
            CommonConsumerPlanning.Outcome outcome, @Nullable BigDecimal consumption, boolean calculated,
            long plotDays, BigDecimal amount, @Nullable Map<String, Object> warning,
            List<Map<String, Object>> plots) {
        CommonConsumerRunLineRecord line = new CommonConsumerRunLineRecord();
        line.id = UUID.randomUUID();
        line.tenantId = tenantId;
        line.runId = runId;
        line.consumerMeterId = consumerMeterId;
        line.consumerName = consumerName;
        line.fromDate = from;
        line.toDate = to;
        line.runStatus = CommonConsumerRunRecord.Status.BOOKED.name();
        line.position = position;
        line.split = split;
        line.outcome = outcome;
        line.consumption = consumption;
        line.calculated = calculated;
        line.plotDays = plotDays;
        line.amount = amount;
        line.warning = warning;
        line.plots = plots;
        return line;
    }

    UUID getRunId() {
        return runId;
    }

    UUID getConsumerMeterId() {
        return consumerMeterId;
    }

    @Nullable String getConsumerName() {
        return consumerName;
    }

    int getPosition() {
        return position;
    }

    CommonConsumerSplit getSplit() {
        return split;
    }

    CommonConsumerPlanning.Outcome getOutcome() {
        return outcome;
    }

    @Nullable BigDecimal getConsumption() {
        return consumption;
    }

    boolean isCalculated() {
        return calculated;
    }

    long getPlotDays() {
        return plotDays;
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
