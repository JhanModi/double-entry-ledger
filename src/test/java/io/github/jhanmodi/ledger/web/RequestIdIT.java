package io.github.jhanmodi.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jhanmodi.ledger.HttpApi;
import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;

/**
 * Request ids over real HTTP (ADR-0021). RequestIdFilterTest covers how the filter keeps the id confined to one request.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class RequestIdIT {

    @Value("${local.server.port}")
    int port;

    HttpApi api;

    @BeforeEach
    void setUp() {
        api = new HttpApi(port);
    }

    @Test
    void everyResponseCarriesAFreshServerGeneratedId() {
        String first = requestIdOf(api.get("/actuator/health", null));
        String second = requestIdOf(api.get("/actuator/health", null));

        assertThat(UUID.fromString(first)).isNotEqualTo(UUID.fromString(second));
    }

    @Test
    void evenARejectedRequestHasOne() {
        // The filter runs before authentication, so a 401 can be traced like anything else.
        HttpApi.Response noKey = api.get("/v1/accounts/" + UUID.randomUUID(), null);
        HttpApi.Response badKey = api.get("/v1/accounts/" + UUID.randomUUID(), "Bearer not-a-key");

        assertThat(noKey.status()).isEqualTo(401);
        assertThat(badKey.status()).isEqualTo(401);
        assertThat(UUID.fromString(requestIdOf(noKey))).isNotNull();
        assertThat(UUID.fromString(requestIdOf(badKey))).isNotNull();
    }

    @Test
    void anIdChosenByTheClientIsIgnored() {
        // A well-formed UUID, the most plausible thing a client would send, and the easiest to accept by mistake.
        String chosen = UUID.randomUUID().toString();

        HttpApi.Response response = api.send("GET", "/actuator/health", null, null, Map.of("X-Request-Id", chosen));

        assertThat(requestIdOf(response)).isNotEqualTo(chosen);
        assertThat(UUID.fromString(requestIdOf(response))).isNotNull();
    }

    @Test
    void theApplicationsLogPatternPrintsTheRequestId(CapturedOutput output) {
        // Exercises the logging configuration in application.yml, which RequestIdFilterTest can't: that test runs
        // without Spring Boot, so without its log pattern. Logged from the test thread so the line is written before
        // the assertion; a server thread's line could still be in flight.
        MDC.put(RequestIdFilter.MDC_KEY, "0190a000-0000-7000-8000-00000000abcd");
        try {
            LoggerFactory.getLogger(RequestIdIT.class).info("probe line");
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }

        assertThat(output.getOut()).containsPattern("\\[0190a000-0000-7000-8000-00000000abcd\\].*probe line");
    }

    private static String requestIdOf(HttpApi.Response response) {
        return response.requestId().orElseThrow(() -> new AssertionError("no X-Request-Id header"));
    }
}
