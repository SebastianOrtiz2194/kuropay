package com.kuropay.wallet;

import com.kuropay.wallet.support.TestJwtConfiguration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real HTTP, real security filter chain, real JWT validation: only the identity provider is
 * replaced (see {@link TestJwtConfiguration}). A slice test with a mocked authentication could
 * not prove that a forged or expired token is actually rejected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TestJwtConfiguration.class)
class ApiAuthenticationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void request_without_a_token_should_return_401_problem_and_ask_for_a_bearer_token() {
        ResponseEntity<ProblemDetail> response = getWallet(null);

        assertUnauthorizedProblem(response);
        // No error code: the client did nothing wrong yet, it just has not authenticated.
        // (Spring Security 7 also appends an RFC 9728 resource_metadata hint, hence startsWith.)
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .startsWith("Bearer")
                .doesNotContain("error");
    }

    @Test
    void expired_token_should_return_401() {
        ResponseEntity<ProblemDetail> response = getWallet(TestJwtConfiguration.expiredToken("alice"));

        assertUnauthorizedProblem(response);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("invalid_token");
    }

    @Test
    void token_signed_by_an_untrusted_key_should_return_401() {
        ResponseEntity<ProblemDetail> response =
                getWallet(TestJwtConfiguration.tokenSignedByAnUntrustedKey("alice"));

        assertUnauthorizedProblem(response);
    }

    @Test
    void token_from_another_issuer_should_return_401_even_if_the_signature_is_valid() {
        ResponseEntity<ProblemDetail> response = getWallet(TestJwtConfiguration.tokenFromAnotherIssuer("alice"));

        assertUnauthorizedProblem(response);
    }

    @Test
    void token_issued_for_another_service_should_return_401_even_if_issuer_and_signature_are_valid() {
        ResponseEntity<ProblemDetail> response =
                getWallet(TestJwtConfiguration.tokenForAnotherAudience("alice"));

        assertUnauthorizedProblem(response);
        // Proves it was rejected for its audience, not for some other flaw in the test token.
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("aud");
    }

    @Test
    void valid_token_should_get_past_authentication_and_reach_the_controller() {
        ResponseEntity<ProblemDetail> response = getWallet(TestJwtConfiguration.validToken("alice"));

        // The wallet id is random, so "not found" can only come from the controller itself.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Wallet Not Found");
    }

    @Test
    void unauthenticated_write_should_be_rejected_before_it_touches_the_database() {
        Integer walletsBefore = countWallets();

        ResponseEntity<ProblemDetail> response = restClient.post()
                .uri("/api/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"currency": "JPY"}
                        """)
                .retrieve()
                .onStatus(status -> true, (req, res) -> {})
                .toEntity(ProblemDetail.class);

        assertUnauthorizedProblem(response);
        assertThat(countWallets()).isEqualTo(walletsBefore);
    }

    private @Nullable Integer countWallets() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM wallets", Integer.class);
    }

    private ResponseEntity<ProblemDetail> getWallet(@Nullable String bearerToken) {
        return restClient.get()
                .uri("/api/wallets/{id}", UUID.randomUUID())
                .headers(headers -> {
                    if (bearerToken != null) {
                        headers.setBearerAuth(bearerToken);
                    }
                })
                .retrieve()
                .onStatus(status -> true, (req, res) -> {})
                .toEntity(ProblemDetail.class);
    }

    private static void assertUnauthorizedProblem(ResponseEntity<ProblemDetail> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Unauthorized");
        assertThat(response.getBody().getStatus()).isEqualTo(401);
        assertThat(response.getBody().getType().toString()).endsWith("/errors/unauthorized");
        assertThat(response.getBody().getInstance()).isNotNull();
    }
}
