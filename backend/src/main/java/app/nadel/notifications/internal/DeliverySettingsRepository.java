package app.nadel.notifications.internal;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DeliverySettingsRepository extends JpaRepository<DeliverySettingsRow, UUID> {

    /** The row of the cooperative, locked until the end of the transaction ({@code select ... for update}). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DeliverySettingsRow r where r.tenantId = :tenantId")
    Optional<DeliverySettingsRow> lockById(@Param("tenantId") UUID tenantId);

    /**
     * Creates the row of a cooperative with the defaults, unless it exists: two first saves racing each other cannot
     * fail on the primary key. RLS refuses a row of another cooperative.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "insert into delivery_settings (tenant_id) values (:tenantId) on conflict (tenant_id) do nothing",
            nativeQuery = true)
    void insertDefaults(@Param("tenantId") UUID tenantId);

    /**
     * Keeps the bot's @name Telegram reported, only while the token it was asked with is still the saved one (same
     * settings version). Returns the rows changed (0 or 1).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update delivery_settings set bot_username = :username
            where tenant_id = :tenantId and settings_version = :version and bot_token_enc is not null
              and bot_username is distinct from :username
            """, nativeQuery = true)
    int rememberBotUsername(@Param("tenantId") UUID tenantId, @Param("version") int version,
            @Param("username") String username);
}
