package app.nadel.imports.internal;

import app.nadel.members.MemberImportAction;
import app.nadel.members.MemberImportItem;
import app.nadel.members.MemberImportPlan;
import app.nadel.members.MemberImportRow;
import app.nadel.plots.OwnershipImportItem;
import app.nadel.plots.OwnershipImportPlan;
import app.nadel.plots.PlotImportAction;
import app.nadel.plots.PlotImportItem;
import app.nadel.plots.PlotImportPlan;
import app.nadel.plots.PlotImportRow;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A fingerprint of everything a commit would do: SHA-256 over a canonical text of the whole plan. At commit time the
 * plan is computed again from the stored rows and its digest must equal the one stored with the preview; any
 * difference (a plot added meanwhile, an owner changed) means the preview is stale.
 * <p>
 * The text has one line per plot row, member row and ownership row: sheet, natural key (lower case), action, the
 * matched record's id or {@code new}, the names of the changed fields and the values the file gives. Ids of records the
 * import creates are left out (they depend on the import's own id, so two previews of one file would differ); an
 * existing record is identified by its id. Every value is written with its length ({@code 3:abc;}), so no value can
 * be mistaken for a separator. The lines are sorted, so the digest does not depend on the order of the rows: the plan
 * is a set of operations. It does not read the order of the file back either, so replaying the stored rows in any
 * order gives the same digest.
 */
final class PlanDigest {

    private static final String NEW = "new";

    private PlanDigest() {
    }

    static String of(List<PlotImportRow> plotRows, PlotImportPlan plots, List<MemberImportRow> memberRows,
            MemberImportPlan members, List<OwnershipSheet.ParsedRow> ownershipRows, OwnershipImportPlan ownership) {
        Set<UUID> newPlots = new HashSet<>();
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < plotRows.size(); index++) {
            PlotImportRow row = plotRows.get(index);
            PlotImportItem item = plots.items().get(index);
            if (item.action() == PlotImportAction.NEW) {
                newPlots.add(item.plotId());
            }
            lines.add(line("plot", lower(row.number()), item.action(), ref(item.plotId(), newPlots), changed(
                    item.changedFields()), row.street(), row.kind() == null ? null : row.kind().toUpperCase(Locale.ROOT),
                    area(row.areaM2()), row.cadastralNumber(), row.hasBuilding(), row.notes()));
        }
        Set<UUID> newMembers = new HashSet<>();
        for (int index = 0; index < memberRows.size(); index++) {
            MemberImportRow row = memberRows.get(index);
            MemberImportItem item = members.items().get(index);
            if (item.action() == MemberImportAction.NEW) {
                newMembers.add(item.memberId());
            }
            lines.add(line("member", lower(row.externalId()), item.action(), ref(item.memberId(), newMembers),
                    changed(item.changedFields()), row.lastName(), row.firstName(), row.middleName(), row.phones(),
                    row.email(), row.postalAddress(), row.deliveryChannel(), row.telegramUsername(),
                    row.viberPhone()));
        }
        for (int index = 0; index < ownershipRows.size(); index++) {
            OwnershipSheet.ParsedRow row = ownershipRows.get(index);
            OwnershipImportItem item = ownership.items().get(index);
            lines.add(line("ownership", lower(row.plotNumber()), item.outcome(), ref(item.plotId(), newPlots),
                    lower(row.memberKey()), row.share(), row.validFrom()));
        }
        lines.sort(null);
        return sha256(String.join("\n", lines));
    }

    /** The id of an existing record, or {@code new} for one the import creates. */
    private static String ref(@Nullable UUID id, Set<UUID> created) {
        return id == null || created.contains(id) ? NEW : id.toString();
    }

    private static String changed(List<String> fields) {
        return String.join(",", fields.stream().sorted().toList());
    }

    private static @Nullable String lower(@Nullable String text) {
        return text == null ? null : text.strip().toLowerCase(Locale.ROOT);
    }

    /** 600, 600.0 and 6E+2 are one area. */
    private static @Nullable String area(@Nullable BigDecimal area) {
        return area == null ? null : (area.signum() == 0 ? "0" : area.stripTrailingZeros().toPlainString());
    }

    private static String line(String sheet, Object... values) {
        StringBuilder out = new StringBuilder();
        append(out, sheet);
        for (Object value : values) {
            append(out, value);
        }
        return out.toString();
    }

    private static void append(StringBuilder out, @Nullable Object value) {
        if (value instanceof List<?> list) {
            out.append('[');
            list.forEach(element -> append(out, element));
            out.append(']');
            return;
        }
        if (value == null) {
            out.append("~;");
            return;
        }
        String text = value.toString();
        out.append(text.length()).append(':').append(text).append(';');
    }

    private static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
