package com.kuropay.wallet;

import com.kuropay.wallet.infrastructure.web.WalletController.WalletResponse;
import com.kuropay.wallet.support.TestJwtConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may do what with a wallet, end to end: the identity comes from a real signed token that
 * goes through the real decoder, not from a mocked authentication.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TestJwtConfiguration.class)
class WalletOwnershipIntegrationTest {

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
    void new_wallet_should_belong_to_the_token_subject_even_if_the_body_names_someone_else() {
        String caller = "alice-" + UUID.randomUUID();
        String victim = "bob-" + UUID.randomUUID();

        ResponseEntity<WalletResponse> response = restClient.post()
                .uri("/api/wallets")
                .headers(headers -> headers.setBearerAuth(TestJwtConfiguration.validToken(caller)))
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"ownerId": "%s", "currency": "JPY"}
                        """.formatted(victim))
                .retrieve()
                .toEntity(WalletResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ownerId()).isEqualTo(caller);
        assertThat(walletsOwnedBy(caller)).isEqualTo(1);
        assertThat(walletsOwnedBy(victim)).isZero();
    }

    private int walletsOwnedBy(String ownerId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM wallets WHERE owner_id = ?", Integer.class, ownerId);
        return count == null ? 0 : count;
    }
}
