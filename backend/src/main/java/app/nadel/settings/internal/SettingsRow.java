package app.nadel.settings.internal;

import app.nadel.settings.CommonConsumerSplit;
import app.nadel.settings.LossesMode;
import app.nadel.settings.PlotStatusVisibility;
import app.nadel.settings.Requisites;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The settings row of a cooperative; the primary key is the cooperative. Written only by {@link SettingsRepository}. */
@Entity
@Table(name = "cooperative_settings")
class SettingsRow {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "member_plot_status_visibility", nullable = false)
    private PlotStatusVisibility memberPlotStatusVisibility;

    @Enumerated(EnumType.STRING)
    @Column(name = "losses_mode", nullable = false)
    private LossesMode lossesMode;

    @Column(name = "losses_percent", nullable = false)
    private BigDecimal lossesPercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "common_consumer_default_split", nullable = false)
    private CommonConsumerSplit commonConsumerDefaultSplit;

    @Column(name = "red_amount", nullable = false)
    private BigDecimal redAmount;

    @Column(name = "red_days", nullable = false)
    private int redDays;

    @Column(name = "official_name")
    private String officialName;

    @Column(name = "legal_address")
    private String legalAddress;

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "iban")
    private String iban;

    @Column(name = "auto_allocate_prepayment", nullable = false)
    private boolean autoAllocatePrepayment;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SettingsRow() {
    }

    /** The stored values. */
    SettingsValues values() {
        return new SettingsValues(memberPlotStatusVisibility, lossesMode, lossesPercent, commonConsumerDefaultSplit,
                redAmount, redDays, new Requisites(officialName, legalAddress, bankName, iban),
                autoAllocatePrepayment);
    }
}
