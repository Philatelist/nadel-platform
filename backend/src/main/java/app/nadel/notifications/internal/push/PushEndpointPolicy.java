package app.nadel.notifications.internal.push;

import app.nadel.notifications.internal.mail.SmtpHostPolicy;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Which push endpoints the server may send to (feature 021, ADR 0021). An endpoint is a URL the browser hands over, so
 * without a limit a member could make the server post to any address (its own network, a metadata service). An
 * endpoint is allowed when it is an absolute {@code https} URL without user information whose host is one of
 * {@code nadel.webpush.allowed-hosts} (a name, or {@code *.} and a domain for any of its subdomains) and resolves
 * only to public addresses (the rule of {@link SmtpHostPolicy#isPublic}: no loopback, link-local, private, shared or
 * reserved address); or when its origin is one of {@code nadel.webpush.extra-allowed-origins}, which may be {@code http} and
 * private (development's fake push service, the tests' in-JVM fake). Checked when a device is registered
 * ({@link #syntaxAllowed}) and before every send ({@link #check}, with the lookup). Nothing here is logged.
 */
@Component
class PushEndpointPolicy {

    /** Resolves a host name to its addresses; {@link InetAddress#getAllByName} outside the tests. */
    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** What {@link #check} found. */
    enum Verdict {
        /** The endpoint may be sent to. */
        ALLOWED,
        /** Not an allowed push service, or it resolves to an address that is not public. */
        NOT_ALLOWED,
        /** The host does not resolve now: try again later. */
        UNRESOLVED
    }

    private final List<String> hosts;
    private final Set<String> extraOrigins;
    private final Resolver resolver;

    @Autowired
    PushEndpointPolicy(WebPushProperties properties) {
        this(properties, InetAddress::getAllByName);
    }

    PushEndpointPolicy(WebPushProperties properties, Resolver resolver) {
        this.hosts = properties.allowedHosts().stream().map(host -> host.strip().toLowerCase(Locale.ROOT)).toList();
        this.extraOrigins = properties.extraAllowedOrigins().stream()
                .map(PushEndpointPolicy::parse)
                .flatMap(Optional::stream)
                .map(VapidSigner::origin)
                .collect(Collectors.toUnmodifiableSet());
        this.resolver = resolver;
    }

    /** The endpoint as a URI when it is well-formed and allowed by its text alone (no lookup). */
    Optional<URI> syntaxAllowed(String endpoint) {
        return parse(endpoint).filter(uri -> extra(uri) || ("https".equals(lower(uri.getScheme()))
                && knownHost(lower(uri.getHost()))));
    }

    /** The full check before a send: the text, then every address the host resolves to. */
    Verdict check(URI endpoint) {
        Optional<URI> allowed = syntaxAllowed(endpoint.toString());
        if (allowed.isEmpty()) {
            return Verdict.NOT_ALLOWED;
        }
        if (extra(endpoint)) {
            return Verdict.ALLOWED;
        }
        try {
            InetAddress[] addresses = resolver.resolve(endpoint.getHost());
            if (addresses.length == 0) {
                return Verdict.UNRESOLVED;
            }
            for (InetAddress address : addresses) {
                if (!SmtpHostPolicy.isPublic(address)) {
                    return Verdict.NOT_ALLOWED;
                }
            }
            return Verdict.ALLOWED;
        } catch (UnknownHostException e) {
            return Verdict.UNRESOLVED;
        }
    }

    private boolean extra(URI uri) {
        return extraOrigins.contains(VapidSigner.origin(uri));
    }

    private boolean knownHost(String host) {
        for (String allowed : hosts) {
            if (allowed.startsWith("*.")) {
                String domain = allowed.substring(1); // ".push.services.mozilla.com"
                if (host.endsWith(domain) && host.length() > domain.length()) {
                    return true;
                }
            } else if (allowed.equals(host)) {
                return true;
            }
        }
        return false;
    }

    /** An absolute http(s) URL with a host, no user information and no fragment; empty otherwise. */
    private static Optional<URI> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(text.strip());
            String scheme = lower(uri.getScheme());
            if (!uri.isAbsolute() || uri.isOpaque() || !("https".equals(scheme) || "http".equals(scheme))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
                return Optional.empty();
            }
            return Optional.of(uri);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
