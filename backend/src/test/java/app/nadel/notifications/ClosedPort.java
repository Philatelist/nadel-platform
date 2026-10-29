package app.nadel.notifications;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;

/**
 * A local port with nothing listening on it, for the tests of feature 017 that check how an unreachable mail server or
 * Bot API is handled. The operating system hands out a free loopback port, the channel bound to it is closed at once,
 * so a connection to the port is refused.
 */
public final class ClosedPort {

    private ClosedPort() {
    }

    /** A loopback port that refuses connections. */
    public static int next() {
        try (ServerSocketChannel channel = ServerSocketChannel.open()) {
            channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return ((InetSocketAddress) channel.getLocalAddress()).getPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
