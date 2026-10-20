package app.nadel.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.json.JsonMapper;

/**
 * The size limits of the container (multipart limits, not the service's own check) answered over a real HTTP
 * connection: MockMvc does not enforce them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImportUploadLimitTests extends ImportsIntegrationTest {

    @LocalServerPort
    int port;

    private HttpResponse<String> post(int fileBytes) throws Exception {
        String boundary = "nadel-test-boundary";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"plots.xlsx\"\r\n"
                + "Content-Type: " + XLSX + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(new byte[fileBytes]);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + IMPORTS))
                .header(HttpHeaders.AUTHORIZATION, chairman())
                .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aFileJustOverTenMegabytesIsRefusedWith413AndTheFileTooLargeCode() throws Exception {
        HttpResponse<String> response = post(10 * 1024 * 1024 + 1);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(""))
                .startsWith("application/problem+json");
        assertThat(JsonMapper.builder().build().readTree(response.body()).get("code").asString())
                .isEqualTo("FILE_TOO_LARGE");
        assertThat(count("import_run")).isZero();
    }

    @Test
    void aMuchLargerFileIsRefusedTheSameWay() throws Exception {
        HttpResponse<String> response = post(15 * 1024 * 1024);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("FILE_TOO_LARGE");
    }

    @Test
    void aFileUnderTheLimitReachesTheServiceWhichRefusesItAsNotAWorkbook() throws Exception {
        HttpResponse<String> response = post(1024);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("FILE_NOT_XLSX");
    }
}
