package app.nadel.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.notifications.internal.BotDialogue.Purpose;
import app.nadel.notifications.internal.BotDialogue.State;
import app.nadel.notifications.internal.DeliveryTestSupport.MovableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Where a chat stands in the bot's dialogue (feature 017 §2.5): a step is kept for ten minutes after the last answer,
 * asking again keeps it for another ten, and a chat silent for longer starts afresh; the chats of one cooperative are
 * forgotten together when its bot stops here.
 */
class BotDialogueTests {

    private static final UUID TENANT = UUID.fromString("33333333-0000-0000-0000-000000000017");
    private static final UUID OTHER = UUID.fromString("44444444-0000-0000-0000-000000000017");

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-30T10:00:00Z"));
    private final BotDialogue dialogue = new BotDialogue(clock);

    @Test
    void aStepIsKeptTenMinutesAndAChatSilentForLongerStartsAfresh() {
        dialogue.pickPlot(TENANT, 1L, Purpose.INVOICE);

        clock.advance(Duration.ofMinutes(10));
        State kept = dialogue.get(TENANT, 1L);
        assertThat(kept.step()).isEqualTo(BotDialogue.Step.PICK_PLOT);
        assertThat(kept.purpose()).isEqualTo(Purpose.INVOICE);

        clock.advance(Duration.ofSeconds(1));
        assertThat(dialogue.get(TENANT, 1L)).isEqualTo(State.IDLE);
        assertThat(dialogue.get(TENANT, 1L)).isEqualTo(State.IDLE);
    }

    @Test
    void askingAgainKeepsTheStepForAnotherTenMinutes() {
        UUID plot = UUID.randomUUID();
        dialogue.pickMeter(TENANT, 1L, plot);
        clock.advance(Duration.ofMinutes(8));

        dialogue.touch(TENANT, 1L, dialogue.get(TENANT, 1L));
        clock.advance(Duration.ofMinutes(8));

        assertThat(dialogue.get(TENANT, 1L).step()).isEqualTo(BotDialogue.Step.PICK_METER);
        assertThat(dialogue.get(TENANT, 1L).plotId()).isEqualTo(plot);
    }

    @Test
    void theChatsOfACooperativeAreForgottenTogetherAndOthersStay() {
        dialogue.pickPlot(TENANT, 1L, Purpose.READINGS);
        dialogue.pickPlot(TENANT, 2L, Purpose.INVOICE);
        dialogue.pickPlot(OTHER, 1L, Purpose.INVOICE);

        dialogue.forget(TENANT);

        assertThat(dialogue.get(TENANT, 1L)).isEqualTo(State.IDLE);
        assertThat(dialogue.get(TENANT, 2L)).isEqualTo(State.IDLE);
        assertThat(dialogue.get(OTHER, 1L).step()).isEqualTo(BotDialogue.Step.PICK_PLOT);
    }
}
