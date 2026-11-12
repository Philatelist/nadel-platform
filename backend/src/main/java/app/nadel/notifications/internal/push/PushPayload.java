package app.nadel.notifications.internal.push;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The plaintext of a push message (feature 021 §2.2, ADR 0021), encrypted for the device before it leaves:
 * {@code {"v":1,"code":"INVOICES_ISSUED","params":{"month":"2026-10"},"url":"/account#my-invoices","lang":"uk"}}.
 * The path of a code about one thing carries its id ({@link PushCode#url(String)}: an {@code ANNOUNCEMENT} opens
 * {@code /account#announcement-<id>}); an id is not personal data.
 * Codes and parameters only, so neither the push service nor the device's lock screen ever sees personal data: the
 * parameters are exactly those of the code ({@link PushCode#params}), each of a known shape ({@code month} is
 * {@code YYYY-MM}), and anything else is refused. The service worker writes the text from the catalogs of
 * {@code lang}.
 */
final class PushPayload {

    /** The version of the format, for the service worker. */
    static final int VERSION = 1;

    private static final Set<String> LANGUAGES = Set.of("uk", "en");
    private static final Map<String, Pattern> SHAPES = Map.of("month",
            Pattern.compile("\\d{4}-(0[1-9]|1[0-2])"));
    private static final JsonMapper JSON = JsonMapper.shared();

    private PushPayload() {
    }

    /**
     * The JSON bytes of the message.
     *
     * @param ref the message's reference ({@link SendPush#ref}), put into the path of a code whose path carries it
     * @throws IllegalArgumentException a parameter is missing, unknown or not of its shape, the reference of a path is
     *                                  not an id, or the language is not {@code uk} or {@code en}
     */
    static byte[] of(PushCode code, Map<String, String> params, String ref, String lang) {
        Objects.requireNonNull(code, "code");
        if (!LANGUAGES.contains(lang)) {
            throw new IllegalArgumentException("A push message is in uk or en");
        }
        if (!params.keySet().equals(Set.copyOf(code.params()))) {
            throw new IllegalArgumentException("A push message carries exactly the parameters of its code");
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("v", VERSION);
        root.put("code", code.name());
        ObjectNode values = root.putObject("params");
        for (String name : code.params()) {
            String value = params.get(name);
            Pattern shape = SHAPES.get(name);
            if (value == null || shape == null || !shape.matcher(value).matches()) {
                throw new IllegalArgumentException("A push parameter is not of its shape");
            }
            values.put(name, value);
        }
        root.put("url", code.url(ref));
        root.put("lang", lang);
        return JSON.writeValueAsBytes(root);
    }
}
