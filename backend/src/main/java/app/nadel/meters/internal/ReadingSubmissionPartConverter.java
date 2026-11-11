package app.nadel.meters.internal;

import java.io.IOException;
import nadel.contract.model.ReadingSubmission;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the {@code reading} part of {@code submitPlotReadingWithPhoto} as JSON when it comes without a JSON content
 * type. The generated web client (orval) appends the part as a plain form field ({@code JSON.stringify}), which carries
 * no {@code Content-Type}, so Spring sees {@code application/octet-stream} and would answer 415; a part sent as
 * {@code application/json} is read by the standard JSON converter as before. Only {@link ReadingSubmission} is read,
 * nothing is ever written. Invalid JSON is 400 {@code MALFORMED_REQUEST}, as for a JSON body.
 */
@Component
class ReadingSubmissionPartConverter extends AbstractHttpMessageConverter<ReadingSubmission> {

    private final JsonMapper json;

    ReadingSubmissionPartConverter(JsonMapper json) {
        super(MediaType.APPLICATION_OCTET_STREAM, MediaType.TEXT_PLAIN);
        this.json = json;
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return ReadingSubmission.class.equals(clazz);
    }

    @Override
    public boolean canWrite(Class<?> clazz, @Nullable MediaType mediaType) {
        return false;
    }

    @Override
    protected ReadingSubmission readInternal(Class<? extends ReadingSubmission> clazz, HttpInputMessage input)
            throws IOException {
        try {
            return json.readValue(input.getBody(), ReadingSubmission.class);
        } catch (JacksonException e) {
            throw new HttpMessageNotReadableException("The reading part is not valid JSON.", input);
        }
    }

    @Override
    protected void writeInternal(ReadingSubmission value, HttpOutputMessage output) {
        throw new UnsupportedOperationException("The reading part is only read");
    }
}
