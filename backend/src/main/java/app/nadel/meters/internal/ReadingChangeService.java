package app.nadel.meters.internal;

import app.nadel.identity.AccessProfile;
import app.nadel.meters.ReadingChanges;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ReadingChanges} over {@link ReadingCommands}: the same locks, rules and writes as the reading endpoints,
 * without the use check and the point events. Runs in the caller's transaction (the ledger's recalculation); the audit
 * entry is returned, never published here.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class ReadingChangeService implements ReadingChanges {

    private final ReadingCommands commands;

    ReadingChangeService(ReadingCommands commands) {
        this.commands = commands;
    }

    @Override
    public Change enter(UUID meterId, LocalDate takenOn, String value, AccessProfile actor) {
        return change(commands.enterOne(meterId, takenOn, value, actor, true));
    }

    @Override
    public Change correct(UUID readingId, LocalDate takenOn, String value, AccessProfile actor) {
        return change(commands.correctLocked(readingId, takenOn, value, actor, true));
    }

    @Override
    public Change delete(UUID readingId, AccessProfile actor) {
        return change(commands.deleteLocked(readingId, actor, true));
    }

    private static Change change(ReadingCommands.Applied applied) {
        MeterRecord meter = applied.meter();
        return new Change(meter.getId(), meter.getKind(), meter.getResource(), meter.getPlotId(),
                applied.saved() == null ? null : applied.saved().reading().getId(), applied.before(),
                applied.after(), applied.from(), applied.to(), applied.through(), applied.audit());
    }
}
