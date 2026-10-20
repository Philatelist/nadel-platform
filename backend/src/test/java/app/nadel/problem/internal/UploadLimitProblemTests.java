package app.nadel.problem.internal;

import static org.assertj.core.api.Assertions.assertThat;

import nadel.contract.model.Problem;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** An upload over the multipart limit is answered 413 with the code the client switches on. */
class UploadLimitProblemTests {

    @Test
    void anUploadOverTheLimitIs413FileTooLarge() {
        ResponseEntity<Object> response = new ProblemExceptionHandler().handleMaxUploadSizeExceededException(
                new MaxUploadSizeExceededException(10L * 1024 * 1024), new HttpHeaders(),
                HttpStatus.CONTENT_TOO_LARGE, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        Problem problem = (Problem) response.getBody();
        assertThat(problem.getCode()).isEqualTo("FILE_TOO_LARGE");
        assertThat(problem.getStatus()).isEqualTo(413);
    }
}
