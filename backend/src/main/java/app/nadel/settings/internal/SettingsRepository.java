package app.nadel.settings.internal;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SettingsRepository extends JpaRepository<SettingsRow, UUID> {

    /**
     * Inserts the settings row of a cooperative or replaces every value of it. An upsert, so two first saves racing
     * each other cannot fail on the primary key. RLS rejects a row of another cooperative.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            insert into cooperative_settings (tenant_id, member_plot_status_visibility, losses_mode, losses_percent,
                common_consumer_default_split, red_amount, red_days, updated_at)
            values (:tenantId, :visibility, :lossesMode, :lossesPercent, :split, :redAmount, :redDays, now())
            on conflict (tenant_id) do update
            set member_plot_status_visibility = excluded.member_plot_status_visibility,
                losses_mode = excluded.losses_mode,
                losses_percent = excluded.losses_percent,
                common_consumer_default_split = excluded.common_consumer_default_split,
                red_amount = excluded.red_amount,
                red_days = excluded.red_days,
                updated_at = now()
            """, nativeQuery = true)
    void upsert(@Param("tenantId") UUID tenantId, @Param("visibility") String visibility,
            @Param("lossesMode") String lossesMode, @Param("lossesPercent") BigDecimal lossesPercent,
            @Param("split") String split, @Param("redAmount") BigDecimal redAmount, @Param("redDays") int redDays);
}
