package io.github.jhanmodi.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Boots the whole application against a real Postgres container and checks the walking skeleton end to end. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationIT {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    @Test
    void healthEndpointReportsUp() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void actuatorEndpointsOtherThanHealthAreNotReachable() throws Exception {
        // Two layers: Actuator exposes only health (application.yml), and the security rules deny everything that
        // has no explicit rule. Without a key, the security layer answers first, with 401.
        assertThat(get("/actuator/env").statusCode()).isEqualTo(401);
        assertThat(get("/actuator/heapdump").statusCode()).isEqualTo(401);
    }

    @Test
    void flywayCreatedAndSeededTheCurrenciesTable() {
        Map<String, Integer> exponents = jdbc
                .sql("SELECT code, exponent FROM currencies")
                .query((rs, rowNum) -> Map.entry(rs.getString("code"), rs.getInt("exponent")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(exponents).containsExactlyInAnyOrderEntriesOf(Map.of("USD", 2, "EUR", 2, "JPY", 0, "KWD", 3));
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
