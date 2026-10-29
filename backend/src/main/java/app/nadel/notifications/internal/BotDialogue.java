package app.nadel.notifications.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Where each chat stands in a conversation with its cooperative's bot (feature 017 §2.5). Kept in memory on the
 * instance that holds the bot's lease, for 10 minutes after the chat's last step; after a take-over or a restart the
 * member taps the menu again. A state holds no message text, only the step and what the step needs.
 * <p>
 * Steps now: {@link Step#IDLE} and {@link Step#CONFIRM_MOVE} (a chat linked to another member asked to move to the
 * member of a code). Slice 5 adds the reading dialogue ({@code PICK_PLOT → PICK_METER → ENTER_VALUE}), whose state
 * carries the plot and the meter; {@link State} grows with it.
 */
@Component
class BotDialogue {

    static final Duration TTL = Duration.ofMinutes(10);

    /** A step of the dialogue. */
    enum Step {
        IDLE,
        /** Waiting for «Так, це я» or «Ні»; the state holds the hash of the code that would move the chat. */
        CONFIRM_MOVE
    }

    /**
     * The state of a chat.
     *
     * @param codeHash the hash of the pending link code, for {@link Step#CONFIRM_MOVE}
     */
    record State(Step step, byte @Nullable [] codeHash, Instant at) {

        static final State IDLE = new State(Step.IDLE, null, Instant.EPOCH);
    }

    private record Key(UUID tenantId, long chatId) {

        /** Never prints the chat id. */
        @Override
        public String toString() {
            return "Key[" + tenantId + "]";
        }
    }

    private final Map<Key, State> states = new ConcurrentHashMap<>();
    private final Clock clock;

    BotDialogue(Clock clock) {
        this.clock = clock;
    }

    /** The chat's state; {@link State#IDLE} when it has none or its last step is older than {@link #TTL}. */
    State get(UUID tenantId, long chatId) {
        Key key = new Key(tenantId, chatId);
        State state = states.get(key);
        if (state == null) {
            return State.IDLE;
        }
        if (state.at().plus(TTL).isBefore(clock.instant())) {
            states.remove(key, state);
            return State.IDLE;
        }
        return state;
    }

    /** Waits for the answer to the move question of the code. */
    void confirmMove(UUID tenantId, long chatId, byte[] codeHash) {
        purgeExpired();
        states.put(new Key(tenantId, chatId), new State(Step.CONFIRM_MOVE, codeHash.clone(), clock.instant()));
    }

    /** Back to {@link Step#IDLE}. */
    void reset(UUID tenantId, long chatId) {
        states.remove(new Key(tenantId, chatId));
    }

    /** Forgets every chat of the cooperative (its bot stopped on this instance). */
    void forget(UUID tenantId) {
        states.keySet().removeIf(key -> key.tenantId().equals(tenantId));
    }

    private void purgeExpired() {
        Instant oldest = clock.instant().minus(TTL);
        states.values().removeIf(state -> state.at().isBefore(oldest));
    }
}
