package app.nadel.problem.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import nadel.contract.model.Problem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** A server-side problem with its own code (PERSONAL_DATA_UNREADABLE) is answered as such and logged by code only. */
@ExtendWith(OutputCaptureExtension.class)
class ServerProblemTests {

    @Test
    void aServerProblemKeepsItsCodeAndIsLogged(CapturedOutput output) {
        ResponseEntity<Object> response = new ProblemExceptionHandler().handleApiProblem(new ApiProblemException(
                HttpStatus.INTERNAL_SERVER_ERROR, ProblemCodes.PERSONAL_DATA_UNREADABLE,
                "Personal data could not be read."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(((Problem) response.getBody()).getCode()).isEqualTo("PERSONAL_DATA_UNREADABLE");
        assertThat(output.getAll()).contains("Answered 500 with problem code PERSONAL_DATA_UNREADABLE");
    }

    @Test
    void aClientProblemIsNotLogged(CapturedOutput output) {
        new ProblemExceptionHandler().handleApiProblem(ApiProblemException.notFound());

        assertThat(output.getAll()).doesNotContain("Answered");
    }
}
