package app.nadel.meters.internal;

import java.io.IOException;
import java.util.Set;
import nadel.contract.model.ReadingEntry;
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
 * Reads the {@code reading} part of {@code submitPlotReadingWithPhoto} ({@link ReadingSubmission}) and of
 * {@code enterReadingWithPhoto} ({@link ReadingEntry}) as JSON when it comes without a JSON content type. The generated
 * web client (orval) appends the part as a plain form field ({@code JSON.stringify}), which carries no
 * {@code Content-Type}, so Spring sees {@code application/octet-stream} and would answer 415; a part sent as
 * {@code application/json} is read by the standard JSON converter as before. Only these two types are read, nothing is
 * ever written. Invalid JSON is 400 {@code MALFORMED_REQUEST}, as for a JSON body.
 */
@Component
class ReadingPartConverter extends AbstractHttpMessageConverter<Object> {

    private static final Set<Class<?>> PARTS = Set.of(ReadingSubmission.class, ReadingEntry.class);

    private final JsonMapper json;

    ReadingPartConverter(JsonMapper json) {
        super(MediaType.APPLICATION_OCTET_STREAM, MediaType.TEXT_PLAIN);
        this.json = json;
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return PARTS.contains(clazz);
    }

    @Override
    public boolean canWrite(Class<?> clazz, @Nullable MediaType mediaType) {
        return false;
    }

    @Override
    protected Object readInternal(Class<?> clazz, HttpInputMessage input) throws IOException {
        try {
            return json.readValue(input.getBody(), clazz);
        } catch (JacksonException e) {
            throw new HttpMessageNotReadableException("The reading part is not valid JSON.", input);
        }
    }

    @Override
    protected void writeInternal(Object value, HttpOutputMessage output) {
        throw new UnsupportedOperationException("The reading part is only read");
    }
}
