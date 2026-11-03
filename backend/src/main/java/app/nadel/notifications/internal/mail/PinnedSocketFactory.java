package app.nadel.notifications.internal.mail;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import javax.net.SocketFactory;

/**
 * The plain socket factory of one letter's connection ({@code mail.smtp.socketFactory}): every socket it makes
 * connects to the address {@link SmtpHostPolicy} checked, whatever host name the mail library asks for, so the
 * connection cannot reach an address that a second DNS lookup would give. TLS still goes on top with the mailbox's
 * host name: the library wraps this socket for {@code SSL} and {@code STARTTLS} and checks the server's certificate
 * against that name ({@code mail.smtp.ssl.checkserveridentity}).
 */
final class PinnedSocketFactory extends SocketFactory {

    private final InetAddress address;

    PinnedSocketFactory(InetAddress address) {
        this.address = address;
    }

    /** The socket the mail library connects itself ({@code connect(new InetSocketAddress(host, port), timeout)}). */
    @Override
    public Socket createSocket() {
        return new PinnedSocket(address);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return connected(port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return connected(localHost, localPort, port);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return connected(port);
    }

    @Override
    public Socket createSocket(InetAddress host, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return connected(localAddress, localPort, port);
    }

    private Socket connected(int port) throws IOException {
        Socket socket = createSocket();
        socket.connect(new InetSocketAddress(address, port));
        return socket;
    }

    private Socket connected(InetAddress localAddress, int localPort, int port) throws IOException {
        Socket socket = createSocket();
        socket.bind(new InetSocketAddress(localAddress, localPort));
        socket.connect(new InetSocketAddress(address, port));
        return socket;
    }

    /** A socket that connects to the checked address on the port it is asked for. */
    private static final class PinnedSocket extends Socket {

        private final InetAddress address;

        PinnedSocket(InetAddress address) {
            this.address = address;
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            int port = endpoint instanceof InetSocketAddress inet ? inet.getPort() : -1;
            if (port < 0) {
                throw new IOException("Only an address with a port can be connected to.");
            }
            super.connect(new InetSocketAddress(address, port), timeout);
        }
    }
}
