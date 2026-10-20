package app.nadel.imports.internal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The columns of an import run that the history shows. Deliberately without the stored plan (personal data). */
record ImportHistoryRow(UUID id, String fileName, @Nullable UUID createdByMemberId, String createdByName,
        Instant createdAt, @Nullable Instant committedAt, Instant expiresAt, ImportStatus status,
        Map<String, Object> counts, int problemCount) {
}
