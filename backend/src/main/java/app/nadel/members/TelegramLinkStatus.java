package app.nadel.members;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * SPI: which members have linked Telegram through the cooperative's bot (feature 017 §2.2), so the member card shows
 * «Telegram: підключено / не підключено» and never the member's Telegram account details. Implemented by
 * {@code notifications}; this module reads it through an {@code ObjectProvider} and, without an implementation, treats
 * every member as not linked. Called in the caller's transaction, with the cooperative bound.
 */
public interface TelegramLinkStatus {

    /** Of the given members, those whose Telegram is linked now; members of other cooperatives are never included. */
    Set<UUID> linkedAmong(Collection<UUID> memberIds);
}
