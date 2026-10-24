package app.nadel.reports.internal;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The columns of a template version that lists show. Deliberately without the file ({@code content}). */
record TemplateVersionRow(UUID id, UUID templateId, int versionNo, String fileName, int sizeBytes, String sha256,
        @Nullable UUID uploadedBy, Instant uploadedAt) {
}
