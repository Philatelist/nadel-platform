package app.nadel.notifications;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mailpit in a container for the mail tests of feature 017: an SMTP server that keeps every letter and shows it over
 * its HTTP API ({@code /api/v1/messages}), so no test sends to a real mailbox. The image is the one of
 * {@code infra/docker-compose.yml}. The server asks for a sign-in ({@link #USER} / {@link #PASSWORD}, allowed without
 * TLS), so a wrong password is refused as a real mailbox would. One container per test JVM ({@link #shared()}),
 * started on first use; a test empties it with {@link #clear()}.
 */
public final class Mailpit {

    public static final String IMAGE = "axllent/mailpit:v1.27.8";
    public static final String USER = "nadel-test";
    public static final String PASSWORD = "mailpit-secret-1";
    private static final int SMTP_PORT = 1025;
    private static final int HTTP_PORT = 8025;

    private static Mailpit shared;

    private final GenericContainer<?> container;
    private final HttpClient http = HttpClient.newHttpClient();

    /** One letter as Mailpit lists it. */
    public record Letter(String id, String from, String fromName, List<String> to, String subject) {
    }

    @SuppressWarnings("resource") // stopped by Testcontainers' Ryuk when the JVM ends
    private Mailpit() {
        container = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withExposedPorts(SMTP_PORT, HTTP_PORT)
                .withEnv("MP_SMTP_AUTH", USER + ":" + PASSWORD)
                .withEnv("MP_SMTP_AUTH_ALLOW_INSECURE", "true")
                .waitingFor(Wait.forHttp("/readyz").forPort(HTTP_PORT));
        container.start();
    }

    /** The container of this test JVM, started on first use. */
    public static synchronized Mailpit shared() {
        if (shared == null) {
            shared = new Mailpit();
        }
        return shared;
    }

    /** The host to connect the SMTP client to. */
    public String host() {
        return container.getHost();
    }

    /** The SMTP port on {@link #host()}. */
    public int smtpPort() {
        return container.getMappedPort(SMTP_PORT);
    }

    /** Every letter Mailpit holds, newest first. */
    public List<Letter> letters() {
        JsonNode root = JsonMapper.shared().readTree(get("/api/v1/messages"));
        List<Letter> letters = new ArrayList<>();
        for (JsonNode message : root.path("messages")) {
            List<String> to = new ArrayList<>();
            message.path("To").forEach(address -> to.add(address.path("Address").asString()));
            letters.add(new Letter(message.path("ID").asString(), message.path("From").path("Address").asString(),
                    message.path("From").path("Name").asString(), to, message.path("Subject").asString()));
        }
        return letters;
    }

    /** The plain-text body of a letter. */
    public String text(String id) {
        return JsonMapper.shared().readTree(get("/api/v1/message/" + id)).path("Text").asString();
    }

    /** Removes every letter. */
    public void clear() {
        send(HttpRequest.newBuilder(uri("/api/v1/messages")).DELETE().build());
    }

    private String get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    private URI uri(String path) {
        return URI.create("http://" + container.getHost() + ":" + container.getMappedPort(HTTP_PORT) + path);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Mailpit answered " + response.statusCode() + " to " + request.uri());
            }
            return response.body();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
