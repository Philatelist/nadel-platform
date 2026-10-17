package app.nadel.identity.internal;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nadel.IntegrationTest;
import app.nadel.TestTokens;
import app.nadel.identity.AccessProfileSource;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A failure of the member lookup during sign-in (for example the database is down) is a server error, not an
 * expired session: the answer is 500 {@code INTERNAL_ERROR} without any detail, and no {@code WWW-Authenticate}
 * challenge that would make a client drop the token.
 */
class MemberLookupFailureTests extends IntegrationTest {

    private static final String SECRET_DETAIL = "jdbc:postgresql://db-internal-host/secret-detail";

    @Autowired
    MockMvc mvc;

    @MockitoSpyBean
    AccessProfileSource source;

    @Test
    void databaseFailureDuringSignInIsAServerErrorNotAnExpiredSession() throws Exception {
        doThrow(new DataAccessResourceFailureException(SECRET_DETAIL))
                .when(source).resolve(any(UUID.class), anyString(), any(), anyBoolean());
        String bearer = TestTokens.bearer(DEMO_TENANT, "sub-lookup-failure", "chairman@demo.local");

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("DataAccessResourceFailureException"))))
                .andExpect(content().string(not(containsString("AuthenticationServiceException"))))
                .andExpect(content().string(not(containsString("secret-detail"))))
                .andExpect(content().string(not(containsString("Could not resolve the member"))));
    }
}
