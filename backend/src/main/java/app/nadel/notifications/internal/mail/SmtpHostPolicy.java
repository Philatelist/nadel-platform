package app.nadel.notifications.internal.mail;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which mail servers a cooperative's mailbox may connect to (feature 017 §2.1). The host and the port are typed by a
 * chairman, so without a limit the test letter would open a connection from the server to any address: the server
 * itself, its private network or a cloud metadata service. Outside the dev profile a mailbox may use only the mail
 * ports {@value #PORTS_TEXT}, and every address its host resolves to must be public: not loopback, link-local
 * (169.254.0.0/16 with the metadata address, fe80::/10), private (RFC 1918, fc00::/7, fec0::/10), any-local,
 * multicast, shared (100.64.0.0/10), IETF (192.0.0.0/24) or reserved (0.0.0.0/8, 240.0.0.0/4). The dev profile
 * ({@code nadel.delivery.smtp-private-hosts}) allows every address and port, so Mailpit on localhost works.
 * <p>
 * {@link #connectTo} resolves the host once and returns the checked address; the connection goes to that address
 * ({@link PinnedSocketFactory}), so no second lookup can answer differently between the check and the connection.
 */
@Component
public class SmtpHostPolicy {

    static final Set<Integer> PORTS = Set.of(25, 465, 587);
    private static final String PORTS_TEXT = "25, 465 and 587";

    /** Resolves a host name to its addresses; {@link InetAddress#getAllByName} outside the tests. */
    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** The host or the port of a mailbox is not one the server may connect to. */
    public static final class HostNotAllowedException extends Exception {

        HostNotAllowedException() {
            super("The mail server's host or port is not allowed.", null, false, false);
        }
    }

    private final boolean privateHosts;
    private final Resolver resolver;

    @Autowired
    SmtpHostPolicy(@Value("${nadel.delivery.smtp-private-hosts:false}") boolean privateHosts) {
        this(privateHosts, InetAddress::getAllByName);
    }

    SmtpHostPolicy(boolean privateHosts, Resolver resolver) {
        this.privateHosts = privateHosts;
        this.resolver = resolver;
    }

    /**
     * Whether a mailbox with this host and port may be saved: the port is a mail port and the host resolves only to
     * public addresses. A host that does not resolve now may be saved; its letters fail as {@code CONNECTION_FAILED}.
     *
     * @param port the port; null when the mailbox has none yet
     */
    public boolean allowsSaving(String host, Integer port) {
        try {
            check(host, port);
            return true;
        } catch (HostNotAllowedException e) {
            return false;
        } catch (UnknownHostException e) {
            return true;
        }
    }

    /**
     * The address to connect to for this host and port: resolved once and checked.
     *
     * @throws HostNotAllowedException the port is not a mail port or the host resolves to an address that is not public
     * @throws UnknownHostException    the host does not resolve
     */
    public InetAddress connectTo(String host, int port) throws HostNotAllowedException, UnknownHostException {
        return check(host, port);
    }

    private InetAddress check(String host, Integer port) throws HostNotAllowedException, UnknownHostException {
        if (!privateHosts && port != null && !PORTS.contains(port)) {
            throw new HostNotAllowedException();
        }
        InetAddress[] addresses = resolver.resolve(host);
        if (addresses.length == 0) {
            throw new UnknownHostException();
        }
        if (!privateHosts) {
            for (InetAddress address : addresses) {
                if (!isPublic(address)) {
                    throw new HostNotAllowedException();
                }
            }
        }
        return addresses[0];
    }

    /**
     * Whether the address is a public unicast address of the Internet. Also the address rule of the push endpoints
     * ({@code internal.push.PushEndpointPolicy}, feature 021).
     */
    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first != 0 // 0.0.0.0/8, "this network"
                    && !(first == 100 && (second & 0xc0) == 64) // 100.64.0.0/10, shared (100.100.100.200 metadata)
                    && !(first == 192 && second == 0 && bytes[2] == 0) // 192.0.0.0/24, IETF (192.0.0.192 metadata)
                    && first < 240; // 240.0.0.0/4 reserved and the broadcast address
        }
        if ((bytes[0] & 0xfe) == 0xfc) {
            return false; // fc00::/7, unique local (fd00:ec2::254 metadata)
        }
        if (address instanceof Inet6Address v6 && (v6.isIPv4CompatibleAddress() || nat64(bytes))) {
            return isPublic(embeddedIpv4(bytes));
        }
        return true;
    }

    /** 64:ff9b::/96, the NAT64 prefix that carries an IPv4 address in its last four bytes. */
    private static boolean nat64(byte[] bytes) {
        if (bytes[0] != 0 || bytes[1] != 0x64 || (bytes[2] & 0xff) != 0xff || (bytes[3] & 0xff) != 0x9b) {
            return false;
        }
        for (int i = 4; i < 12; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static InetAddress embeddedIpv4(byte[] bytes) {
        try {
            return InetAddress.getByAddress(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e); // four bytes are always an IPv4 address
        }
    }
}
