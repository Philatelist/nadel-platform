package app.nadel.imports.internal;

import static app.nadel.imports.internal.ImportWorkbooks.member;
import static app.nadel.imports.internal.ImportWorkbooks.owner;
import static app.nadel.imports.internal.ImportWorkbooks.plot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A file at the limits (2,000 plots, 3,000 members, 2,000 ownership rows) is committed in one request. Tagged
 * {@code large} so a slow machine can leave it out with {@code -DexcludedGroups=large}; it takes seconds on a normal
 * one. The size also shows that inserts are batched: one statement per row would be far slower.
 */
@Tag("large")
class ImportLargeCommitTests extends ImportCommitSupport {

    private static final int PLOTS = 2000;
    private static final int MEMBERS = 3000;

    @AfterEach
    void waitForTheAuditEntriesBeforeTheCleanup() {
        // about 7,000 entries are written one by one by the asynchronous listener
        await().atMost(Duration.ofMinutes(5)).pollInterval(Duration.ofMillis(250)).until(
                ImportCommitSupport::noPublicationIsOutstanding);
    }

    @Test
    void aFileAtTheLimitsIsCommitted() throws Exception {
        List<List<?>> plots = new ArrayList<>();
        for (int i = 1; i <= PLOTS; i++) {
            plots.add(plot(String.format("LRG-%04d", i), "Large Street " + (i % 20), "PLOT", 500 + i % 300, null,
                    null, i % 3 == 0 ? "yes" : null, null));
        }
        List<List<?>> members = new ArrayList<>();
        for (int i = 1; i <= MEMBERS; i++) {
            members.add(member(String.format("L%04d", i), String.format("Fixt%04d", i), "Large", null,
                    String.format("+38067%07d", i), String.format("lrg.m%04d@import.example.com", i), null, null, null,
                    null));
        }
        List<List<?>> ownership = new ArrayList<>();
        for (int i = 1; i <= PLOTS; i++) {
            ownership.add(owner(String.format("LRG-%04d", i), String.format("L%04d", i), "1",
                    LocalDate.of(2019, 5, 1)));
        }
        byte[] file = workbook(plots, members, ownership);

        long uploadStart = System.nanoTime();
        String id = preview(file).get("id").asString();
        long uploadMillis = Duration.ofNanos(System.nanoTime() - uploadStart).toMillis();

        long commitStart = System.nanoTime();
        commit(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.plots.created").value(PLOTS))
                .andExpect(jsonPath("$.counts.members.created").value(MEMBERS))
                .andExpect(jsonPath("$.counts.ownerships.created").value(PLOTS));
        long commitMillis = Duration.ofNanos(System.nanoTime() - commitStart).toMillis();
        System.out.printf("LARGE IMPORT: %d plots, %d members, %d ownership rows; file %d bytes; upload+preview %d ms;"
                + " commit %d ms%n", PLOTS, MEMBERS, PLOTS, file.length, uploadMillis, commitMillis);

        assertThat(plotsLike("LRG-")).isEqualTo(PLOTS);
        assertThat(membersLike("Fixt")).isEqualTo(MEMBERS);
        assertThat(ownershipOfPlotsLike("LRG-")).isEqualTo(PLOTS);
        assertThat(ownerJdbc().queryForObject("select count(*) from plot_status s join plot p on p.id = s.plot_id "
                + "where p.number like 'LRG-%' and s.status = 'GREEN'", Integer.class)).isEqualTo(PLOTS);
    }
}
