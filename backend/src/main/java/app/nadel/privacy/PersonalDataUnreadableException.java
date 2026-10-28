package app.nadel.privacy;

import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import org.springframework.http.HttpStatus;

/**
 * A sealed personal value (or the key of its cooperative) could not be opened: it is malformed, was copied from
 * another place or was sealed with another key. Answered as 500 {@code PERSONAL_DATA_UNREADABLE}; the message never
 * contains a value. Callers never turn it into an empty field, which a later save would write back.
 */
public class PersonalDataUnreadableException extends ApiProblemException {

    public PersonalDataUnreadableException() {
        super(HttpStatus.INTERNAL_SERVER_ERROR, ProblemCodes.PERSONAL_DATA_UNREADABLE,
                "Personal data could not be read.");
    }
}
