package app.nadel.plots.internal;

import app.nadel.members.MemberRef;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * The value of the {@code owners} field of an ownership audit entry: structured parameters, never a display phrase, so
 * the client builds the text in the user's language. The value is a JSON array of objects
 * {@code {"member": "Last First", "share": "1/2", "from": "2020-01-01", "to": "2026-02-28"}} sorted by member name
 * (letter case ignored), then share, then start date. {@code from} and {@code to} (ISO dates) appear only in a
 * correction, where the periods differ per owner; {@code to} is absent while the period is open. The member name is user
 * content (personal data: never logged). An empty list is null.
 */
final class OwnersAudit {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Comparator<Entry> ORDER = Comparator
            .comparing(Entry::member, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Entry::member)
            .thenComparing(Entry::share)
            .thenComparing(Entry::from, Comparator.nullsFirst(Comparator.naturalOrder()));

    /** One owner of the plot; {@code from} and {@code to} are null in a change of owners. */
    record Entry(String member, String share, @Nullable LocalDate from, @Nullable LocalDate to) {
    }

    private OwnersAudit() {
    }

    /** An owner without a period: the owners of a change of owners all start on the one effective date. */
    static Entry entry(MemberRef member, Share share) {
        return new Entry(member.shortName(), share.toString(), null, null);
    }

    /** An owner with the period {@code from} to {@code to} ({@code to} null while it is open). */
    static Entry entry(MemberRef member, Share share, LocalDate from, @Nullable LocalDate to) {
        return new Entry(member.shortName(), share.toString(), from, to);
    }

    /** The JSON array of the owners, sorted; null when there are none. */
    static @Nullable String of(Stream<Entry> owners) {
        List<Map<String, String>> sorted = owners.sorted(ORDER).map(OwnersAudit::asMap).toList();
        return sorted.isEmpty() ? null : JSON.writeValueAsString(sorted);
    }

    private static Map<String, String> asMap(Entry owner) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("member", owner.member());
        map.put("share", owner.share());
        if (owner.from() != null) {
            map.put("from", owner.from().toString());
        }
        if (owner.to() != null) {
            map.put("to", owner.to().toString());
        }
        return map;
    }
}
