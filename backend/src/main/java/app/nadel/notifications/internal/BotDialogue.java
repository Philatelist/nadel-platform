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
 * member taps the menu again. A state holds no message text, only the step and what the step needs (ids, the hash of a
 * pending link code).
 * <p>
 * Steps: {@link Step#IDLE}; {@link Step#CONFIRM_MOVE} (a chat linked to another member asked to move to the member of
 * a code); the plot choice {@link Step#PICK_PLOT} of «Рахунок» or «Показання» ({@link Purpose}); and the reading
 * dialogue {@code PICK_PLOT → PICK_METER → ENTER_VALUE}, whose state carries the plot and the meter.
 */
@Component
class BotDialogue {

    static final Duration TTL = Duration.ofMinutes(10);

    /** A step of the dialogue. */
    enum Step {
        IDLE,
        /** Waiting for «Так, це я» or «Ні»; the state holds the hash of the code that would move the chat. */
        CONFIRM_MOVE,
        /** Waiting for a plot button; the state holds what the plot is for. */
        PICK_PLOT,
        /** Waiting for a meter button of the state's plot. */
        PICK_METER,
        /** Waiting for the value of the state's meter. */
        ENTER_VALUE
    }

    /** What a chosen plot is for. */
    enum Purpose {
        INVOICE,
        READINGS
    }

    /**
     * The state of a chat.
     *
     * @param codeHash the hash of the pending link code, for {@link Step#CONFIRM_MOVE}
     * @param purpose  what the plot is chosen for, for {@link Step#PICK_PLOT}
     * @param plotId   the chosen plot, for {@link Step#PICK_METER} and {@link Step#ENTER_VALUE}
     * @param meterId  the chosen meter, for {@link Step#ENTER_VALUE}
     */
    record State(Step step, byte @Nullable [] codeHash, @Nullable Purpose purpose, @Nullable UUID plotId,
            @Nullable UUID meterId, Instant at) {

        static final State IDLE = new State(Step.IDLE, null, null, null, null, Instant.EPOCH);

        /** Never prints the ids of the member's plot and meter. */
        @Override
        public String toString() {
            return "State[" + step + "]";
        }
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
        put(tenantId, chatId, Step.CONFIRM_MOVE, codeHash.clone(), null, null, null);
    }

    /** Waits for a plot button, for an invoice or for a reading. */
    void pickPlot(UUID tenantId, long chatId, Purpose purpose) {
        put(tenantId, chatId, Step.PICK_PLOT, null, purpose, null, null);
    }

    /** Waits for a meter button of the plot. */
    void pickMeter(UUID tenantId, long chatId, UUID plotId) {
        put(tenantId, chatId, Step.PICK_METER, null, Purpose.READINGS, plotId, null);
    }

    /** Waits for the value of the plot's meter. */
    void enterValue(UUID tenantId, long chatId, UUID plotId, UUID meterId) {
        put(tenantId, chatId, Step.ENTER_VALUE, null, Purpose.READINGS, plotId, meterId);
    }

    /** Keeps the chat at its step for another {@link #TTL} (it is asked again). */
    void touch(UUID tenantId, long chatId, State state) {
        put(tenantId, chatId, state.step(), state.codeHash(), state.purpose(), state.plotId(), state.meterId());
    }

    /** Back to {@link Step#IDLE}. */
    void reset(UUID tenantId, long chatId) {
        states.remove(new Key(tenantId, chatId));
    }

    /** Forgets every chat of the cooperative (its bot stopped on this instance). */
    void forget(UUID tenantId) {
        states.keySet().removeIf(key -> key.tenantId().equals(tenantId));
    }

    private void put(UUID tenantId, long chatId, Step step, byte @Nullable [] codeHash, @Nullable Purpose purpose,
            @Nullable UUID plotId, @Nullable UUID meterId) {
        purgeExpired();
        states.put(new Key(tenantId, chatId), new State(step, codeHash, purpose, plotId, meterId, clock.instant()));
    }

    private void purgeExpired() {
        Instant oldest = clock.instant().minus(TTL);
        states.values().removeIf(state -> state.at().isBefore(oldest));
    }
}
