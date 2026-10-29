package app.nadel.documents;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * The requests of the debt certificates (feature 016 §2.7) on top of the documents' throw-away cooperative: issue,
 * register, PDF and annulment.
 */
abstract class CertificateTestSupport extends DocumentsTestSupport {

    static final String PLOT_CERTIFICATES = "/api/v1/plots/%s/certificates";
    static final String CERTIFICATES = "/api/v1/certificates";

    /** The year of the issue in Kyiv, the {@code YYYY} of {@code Д-YYYY-N}. */
    static int kyivYear() {
        return LocalDate.now(ZoneId.of("Europe/Kyiv")).getYear();
    }

    /** {@code POST /plots/{id}/certificates}, with {@code {asOf}} when it is not null, else without a body. */
    ResultActions issueCertificate(String bearer, UUID plot, LocalDate asOf) throws Exception {
        return send(bearer, post(PLOT_CERTIFICATES.formatted(plot)), asOf == null ? null
                : Map.of("asOf", asOf.toString()));
    }

    /** A certificate issued by the chairman as of the day (today when null); returns its register row. */
    JsonNode certificate(UUID plot, LocalDate asOf) throws Exception {
        return body(issueCertificate(chairman, plot, asOf).andExpect(status().isCreated()));
    }

    /** {@code GET /certificates} with the query string (without the {@code ?}), or none when null. */
    ResultActions register(String bearer, String query) throws Exception {
        return send(bearer, get(CERTIFICATES + (query == null ? "" : "?" + query)), null);
    }

    ResultActions certificatePdf(String bearer, UUID certificate) throws Exception {
        return send(bearer, get(CERTIFICATES + "/" + certificate + "/pdf"), null);
    }

    /** {@code POST /certificates/{id}/annul} with {@code {reason}}; an empty body when the reason is null. */
    ResultActions annul(String bearer, UUID certificate, String reason) throws Exception {
        return send(bearer, post(CERTIFICATES + "/" + certificate + "/annul"),
                reason == null ? Map.of() : Map.of("reason", reason));
    }

    /** The bytes of the certificate's PDF for the chairman. */
    byte[] pdfOf(UUID certificate) throws Exception {
        return certificatePdf(chairman, certificate).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray();
    }

    /** The text of the certificate's PDF, no-break spaces as plain spaces. */
    String pdfText(UUID certificate) throws Exception {
        return text(pdfOf(certificate)).replace(' ', ' ');
    }

    /** The last value of the cooperative's certificate counter of the year; 0 when there is none. */
    long lastCertificate(int year) {
        Long value = owner().queryForObject("select coalesce(max(last_value), 0) from document_counter "
                + "where tenant_id = ? and kind = 'CERTIFICATE' and year = ?", Long.class, tenant, year);
        return value == null ? 0 : value;
    }

    /** A plot owned by the member. */
    UUID memberPlot(String number) {
        UUID plot = plot(number, "Садова");
        own(memberId, plot);
        return plot;
    }
}
