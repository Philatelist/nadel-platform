package app.nadel.settings.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SettingsRepository extends JpaRepository<SettingsRow, UUID> {

    /**
     * Inserts the settings row of a cooperative or updates it. An upsert, so two first saves racing each other cannot
     * fail on the primary key. RLS rejects a row of another cooperative.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            insert into cooperative_settings (tenant_id, member_plot_status_visibility, updated_at)
            values (:tenantId, :visibility, now())
            on conflict (tenant_id) do update
            set member_plot_status_visibility = excluded.member_plot_status_visibility, updated_at = now()
            """, nativeQuery = true)
    void upsert(@Param("tenantId") UUID tenantId, @Param("visibility") String visibility);
}
