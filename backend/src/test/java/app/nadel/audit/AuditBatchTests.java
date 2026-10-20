package app.nadel.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AuditBatchTests {

    private static final UUID TENANT = UUID.randomUUID();

    @Test
    void anEmptyBatchIsRefused() {
        assertThatThrownBy(() -> new AuditBatch(UUID.randomUUID(), TENANT, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBatchHoldsAtMostTheLimit() {
        assertThat(new AuditBatch(UUID.randomUUID(), TENANT, events(TENANT, AuditBatch.MAX_ENTRIES)).entries())
                .hasSize(500);
        assertThatThrownBy(() -> new AuditBatch(UUID.randomUUID(), TENANT, events(TENANT, AuditBatch.MAX_ENTRIES + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEntryOfAnotherCooperativeCannotJoin() {
        List<AuditEvent> entries = new ArrayList<>(events(TENANT, 2));
        entries.add(events(UUID.randomUUID(), 1).getFirst());

        assertThatThrownBy(() -> new AuditBatch(UUID.randomUUID(), TENANT, entries))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventsAreSplitIntoFullBatchesAndOneRemainderInOrder() {
        List<AuditEvent> events = events(TENANT, 1203);

        List<AuditBatch> batches = AuditBatch.of(TENANT, events);

        assertThat(batches).extracting(b -> b.entries().size()).containsExactly(500, 500, 203);
        assertThat(batches.stream().flatMap(b -> b.entries().stream()).toList()).isEqualTo(events);
        assertThat(batches).extracting(AuditBatch::batchId).doesNotHaveDuplicates();
        assertThat(batches).allSatisfy(b -> assertThat(b.tenantId()).isEqualTo(TENANT));
    }

    @Test
    void noEventsGiveNoBatch() {
        assertThat(AuditBatch.of(TENANT, List.of())).isEmpty();
    }

    @Test
    void theBatchKeepsAnImmutableCopyOfItsEntries() {
        List<AuditEvent> entries = new ArrayList<>(events(TENANT, 1));
        AuditBatch batch = new AuditBatch(UUID.randomUUID(), TENANT, entries);
        entries.clear();

        assertThat(batch.entries()).hasSize(1);
        assertThatThrownBy(() -> batch.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    static List<AuditEvent> events(UUID tenant, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> AuditEvent.now(tenant, new Actor(null, "sub-batch", "Batch Actor"), "PLOT_CREATED",
                        "PLOT", UUID.randomUUID(), "B-" + i, List.of(new FieldChange("number", null, "B-" + i))))
                .toList();
    }
}
