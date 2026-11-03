package app.nadel.notifications.internal.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which mail servers a mailbox may use (feature 017 §2.1, owner review of PR #61): outside the dev profile only the
 * ports 25, 465 and 587 and a host whose every address is public; loopback, link-local with the metadata address,
 * private, unique local, shared, reserved and the IPv6 forms that carry such an IPv4 address are refused. The host is
 * resolved once and that address is the one to connect to. The dev profile allows every address and port (Mailpit on
 * localhost:1025). Resolution is faked, so no test asks DNS.
 */
class SmtpHostPolicyTests {

    private static final String PUBLIC = "203.0.113.25";

    @ParameterizedTest
    @ValueSource(strings = {
        "127.0.0.1", "127.8.9.10", "::1", // loopback
        "169.254.169.254", "169.254.1.1", "fe80::1", // link-local and the cloud metadata address
        "10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.1.10", // RFC 1918
        "fc00::1", "fd00:ec2::254", "fec0::1", // unique local (with the AWS IPv6 metadata address), site-local
        "0.0.0.0", "0.1.2.3", "::", // this network, any-local
        "100.64.0.1", "100.100.100.200", "100.127.255.254", // shared address space, with Alibaba's metadata address
        "192.0.0.192", // IETF protocol assignments, with Oracle's metadata address
        "224.0.0.1", "ff02::1", // multicast
        "240.0.0.1", "255.255.255.255", // reserved and broadcast
        "::ffff:127.0.0.1", "::ffff:169.254.169.254", "::10.0.0.1", "64:ff9b::a9fe:a9fe" // IPv4 inside IPv6
    })
    void aHostThatResolvesToANonPublicAddressIsRefused(String address) throws UnknownHostException {
        SmtpHostPolicy policy = policy(false, Map.of("smtp.coop.example", address));

        assertThat(policy.allowsSaving("smtp.coop.example", 587)).isFalse();
        assertThatThrownBy(() -> policy.connectTo("smtp.coop.example", 587))
                .isInstanceOf(SmtpHostPolicy.HostNotAllowedException.class);
        assertThat(SmtpHostPolicy.isPublic(InetAddress.getByName(address))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {PUBLIC, "8.8.8.8", "100.63.255.255", "100.128.0.1", "172.32.0.1", "192.0.2.1",
        "2a00:1450:400c:c06::6c", "64:ff9b::808:808"})
    void aPublicAddressIsAllowed(String address) throws Exception {
        SmtpHostPolicy policy = policy(false, Map.of("smtp.coop.example", address));

        assertThat(policy.allowsSaving("smtp.coop.example", 587)).isTrue();
        assertThat(policy.connectTo("smtp.coop.example", 587)).isEqualTo(InetAddress.getByName(address));
    }

    @ParameterizedTest
    @ValueSource(ints = {25, 465, 587})
    void theMailPortsAreAllowed(int port) {
        assertThat(policy(false, Map.of("smtp.gmail.com", PUBLIC)).allowsSaving("smtp.gmail.com", port)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {1025, 22, 80, 443, 2525, 6379, 8080})
    void anyOtherPortIsRefused(int port) {
        SmtpHostPolicy policy = policy(false, Map.of("smtp.gmail.com", PUBLIC));

        assertThat(policy.allowsSaving("smtp.gmail.com", port)).isFalse();
        assertThatThrownBy(() -> policy.connectTo("smtp.gmail.com", port))
                .isInstanceOf(SmtpHostPolicy.HostNotAllowedException.class);
    }

    @Test
    void oneNonPublicAddressAmongSeveralRefusesTheHost() {
        SmtpHostPolicy policy = new SmtpHostPolicy(false,
                host -> new InetAddress[] {InetAddress.getByName(PUBLIC), InetAddress.getByName("10.1.2.3")});

        assertThat(policy.allowsSaving("smtp.coop.example", 587)).isFalse();
    }

    @Test
    void theHostIsResolvedOnceAndTheCheckedAddressIsTheOneToConnectTo() throws Exception {
        int[] lookups = {0};
        SmtpHostPolicy policy = new SmtpHostPolicy(false, host -> {
            lookups[0]++;
            return new InetAddress[] {InetAddress.getByName(lookups[0] == 1 ? PUBLIC : "127.0.0.1")};
        });

        InetAddress address = policy.connectTo("rebinding.example", 587);

        assertThat(address).isEqualTo(InetAddress.getByName(PUBLIC));
        assertThat(lookups[0]).isEqualTo(1);
    }

    @Test
    void aHostThatDoesNotResolveMayBeSavedButNotConnectedTo() {
        SmtpHostPolicy policy = policy(false, Map.of());

        assertThat(policy.allowsSaving("smtp.nowhere.invalid", 587)).isTrue();
        assertThatThrownBy(() -> policy.connectTo("smtp.nowhere.invalid", 587))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void withoutAPortOnlyTheAddressIsChecked() {
        assertThat(policy(false, Map.of("smtp.gmail.com", PUBLIC)).allowsSaving("smtp.gmail.com", null)).isTrue();
        assertThat(policy(false, Map.of("localhost", "127.0.0.1")).allowsSaving("localhost", null)).isFalse();
    }

    @Test
    void theDevProfileAllowsLocalhostAndAnyPortForMailpit() throws Exception {
        SmtpHostPolicy dev = policy(true, Map.of("localhost", "127.0.0.1", "metadata", "169.254.169.254"));

        assertThat(dev.allowsSaving("localhost", 1025)).isTrue();
        assertThat(dev.connectTo("localhost", 1025)).isEqualTo(InetAddress.getByName("127.0.0.1"));
        assertThat(dev.allowsSaving("metadata", 25)).isTrue();
    }

    private static SmtpHostPolicy policy(boolean privateHosts, Map<String, String> dns) {
        return new SmtpHostPolicy(privateHosts, host -> {
            String address = dns.get(host);
            if (address == null) {
                throw new UnknownHostException(host);
            }
            return new InetAddress[] {InetAddress.getByName(address)};
        });
    }
}
