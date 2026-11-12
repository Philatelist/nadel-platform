package app.nadel.notifications.internal.push;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a push message says (feature 021 §2.2): a code that the service worker turns into the text of the device's
 * language from the bundled catalogs, the only parameters it may carry and the screen a tap opens (a path of the web
 * app, with the message's reference in place of {@value #REF} when the screen is about one thing: then the reference is
 * an id). Never a name, an amount, a plot or an address: the text shows on a locked phone. A code may also have its own
 * time to live (instead of {@code nadel.webpush.ttl}), a {@code Topic} header (the push service keeps only the newest
 * message of a topic for an offline device) and a throttle (at most one push of the code per member in that rolling
 * window, {@link PushThrottle}).
 */
enum PushCode {

    /** «Новий рахунок за {місяць}»: the member's invoices on the account page. */
    INVOICES_ISSUED(List.of("month"), "/account#my-invoices", null, null, null),

    /** «Платіж зараховано»: the member's charges and payments on the account page. */
    PAYMENT_RECORDED(List.of(), "/account#my-charges", null, null, null),

    /** «Показ лічильника прийнято»: the member's meters and readings on the account page. */
    READING_APPROVED(List.of(), "/account#my-meters", null, null, null),

    /** «Показ лічильника відхилено»: the member's meters and readings on the account page. */
    READING_REJECTED(List.of(), "/account#my-meters", null, null, null),

    /**
     * «Нове оголошення від правління»: the announcement in the member's «Оголошення» on the account page (feature 021
     * follow-up); the reference is the announcement's id.
     */
    ANNOUNCEMENT(List.of(), "/account#announcement-{ref}", null, null, null),

    /**
     * «Нові покази на перевірку»: the approvals screen; at most one per hour per user, kept by the push service for an
     * hour only and replaced there by a newer one.
     */
    READINGS_TO_APPROVE(List.of(), "/readings/approvals", Duration.ofHours(1), "readings-approvals",
            Duration.ofHours(1));

    /** The place of the message's reference in a {@link #url} template. */
    static final String REF = "{ref}";

    private final List<String> params;
    private final String url;
    private final @Nullable Duration ttl;
    private final @Nullable String topic;
    private final @Nullable Duration throttle;

    PushCode(List<String> params, String url, @Nullable Duration ttl, @Nullable String topic,
            @Nullable Duration throttle) {
        this.params = params;
        this.url = url;
        this.ttl = ttl;
        this.topic = topic;
        this.throttle = throttle;
    }

    /** The names of the parameters the message carries, all of them required. */
    List<String> params() {
        return params;
    }

    /** The path a tap opens; a template with {@value #REF} when {@link #urlHasRef}. */
    String url() {
        return url;
    }

    /** Whether the path a tap opens carries the message's reference. */
    boolean urlHasRef() {
        return url.contains(REF);
    }

    /**
     * The path a tap opens for the message about {@code ref}.
     *
     * @throws IllegalArgumentException the path carries the reference and {@code ref} is not an id (a UUID in its
     *                                  canonical lower-case form)
     */
    String url(String ref) {
        if (!urlHasRef()) {
            return url;
        }
        UUID id;
        try {
            id = UUID.fromString(ref);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("A push reference in a path is an id", e);
        }
        if (!id.toString().equals(ref)) {
            throw new IllegalArgumentException("A push reference in a path is an id");
        }
        return url.replace(REF, ref);
    }

    /** How long the message may still go out after its event; null for {@code nadel.webpush.ttl}. */
    @Nullable Duration ttl() {
        return ttl;
    }

    /** The {@code Topic} header (RFC 8030 §5.4); null for none. */
    @Nullable String topic() {
        return topic;
    }

    /** The rolling window of at most one push per member; null when the code is not throttled. */
    @Nullable Duration throttle() {
        return throttle;
    }
}
