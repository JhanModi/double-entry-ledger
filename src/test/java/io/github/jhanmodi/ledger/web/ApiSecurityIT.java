package io.github.jhanmodi.ledger.web;

import static io.github.jhanmodi.ledger.HttpApi.bearer;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jhanmodi.ledger.HttpApi;
import io.github.jhanmodi.ledger.OwnerDatabase;
import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.clients.ClientService;
import io.github.jhanmodi.ledger.clients.IssuedApiKey;
import io.github.jhanmodi.ledger.clients.Scope;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Authentication (who is calling) and authorization (what their key may do), over real HTTP. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSecurityIT {

    private static final String SOME_ACCOUNT = "/v1/accounts/" + UUID.randomUUID();

    @Value("${local.server.port}")
    int port;

    @Autowired
    ClientService clients;

    @Autowired
    OwnerDatabase owner;

    HttpApi api;

    @BeforeEach
    void setUp() {
        api = new HttpApi(port);
    }

    @Test
    void aRequestWithoutAKeyIsUnauthorized() {
        HttpApi.Response response = api.get(SOME_ACCOUNT, null);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.wwwAuthenticate()).contains("Bearer");
        assertThat(response.contentType())
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.json().get("type").asString()).isEqualTo("/problems/unauthorized");
    }

    @Test
    void everyKindOfBadCredentialGetsTheSameAnswer() {
        ClientId client = clients.createClient("acme");
        IssuedApiKey good = clients.issueKey(client, Set.of(Scope.READ));
        IssuedApiKey revoked = clients.issueKey(client, Set.of(Scope.READ));
        clients.revokeKey(revoked.keyId());
        ClientId disabledClient = clients.createClient("disabled");
        IssuedApiKey ofDisabledClient = clients.issueKey(disabledClient, Set.of(Scope.READ));
        owner.jdbc()
                .sql("UPDATE api_clients SET status = 'DISABLED' WHERE id = :id")
                .param("id", disabledClient.value())
                .update();
        String goodKey = good.plaintext();
        String wrongSecret = goodKey.substring(0, 63) + (goodKey.charAt(63) == 'A' ? 'B' : 'A');

        List<String> badHeaders = List.of(
                "Basic dXNlcjpwYXNzd29yZA==",
                "Bearer",
                "Bearer not-a-key",
                bearer("dbl_0000000000000000_" + "A".repeat(43)),
                bearer(wrongSecret),
                bearer(revoked.plaintext()),
                bearer(ofDisabledClient.plaintext()));

        JsonNode expectedBody = withoutRequestId(api.get(SOME_ACCOUNT, null));
        for (String header : badHeaders) {
            HttpApi.Response response = api.get(SOME_ACCOUNT, header);
            assertThat(response.status()).as(header).isEqualTo(401);
            // Identical bodies apart from the request id: the response never says which check failed.
            assertThat(withoutRequestId(response)).as(header).isEqualTo(expectedBody);
            assertThat(response.json().get("requestId").asString())
                    .isEqualTo(response.requestId().orElseThrow());
        }
    }

    @Test
    void theBearerSchemeIsCaseInsensitive() {
        String key = clients.issueKey(clients.createClient("acme"), Set.of(Scope.READ))
                .plaintext();

        // RFC 7235: authentication scheme names are case-insensitive. A valid key reaches the controller: 404, not 401.
        assertThat(api.get(SOME_ACCOUNT, "bearer " + key).status()).isEqualTo(404);
        assertThat(api.get(SOME_ACCOUNT, "BEARER " + key).status()).isEqualTo(404);
    }

    @Test
    void readingNeedsTheReadScope() {
        String writeOnly = bearer(clients.issueKey(clients.createClient("acme"), Set.of(Scope.WRITE))
                .plaintext());

        HttpApi.Response response = api.get(SOME_ACCOUNT, writeOnly);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.json().get("type").asString()).isEqualTo("/problems/forbidden");
    }

    @Test
    void openingAccountsNeedsTheWriteScope() {
        String readOnly = bearer(clients.issueKey(clients.createClient("acme"), Set.of(Scope.READ))
                .plaintext());

        assertThat(api.post("/v1/accounts", readOnly, "{\"currency\": \"USD\"}").status())
                .isEqualTo(403);
    }

    @Test
    void transfersNeedTheWriteScopeAndReadingOneNeedsRead() {
        String readOnly = bearer(clients.issueKey(clients.createClient("acme"), Set.of(Scope.READ))
                .plaintext());
        String writeOnly = bearer(clients.issueKey(clients.createClient("acme"), Set.of(Scope.WRITE))
                .plaintext());

        assertThat(api.send("POST", "/v1/transfers", readOnly, "{}", Map.of("Idempotency-Key", "k1"))
                        .status())
                .isEqualTo(403);
        assertThat(api.get("/v1/transfers/" + UUID.randomUUID(), writeOnly).status())
                .isEqualTo(403);
        assertThat(api.send("POST", "/v1/transfers", null, "{}", Map.of("Idempotency-Key", "k1"))
                        .status())
                .isEqualTo(401);
    }

    @Test
    void fundingNeedsTheAdminScope() {
        String readWrite = bearer(clients.issueKey(clients.createClient("acme"), EnumSet.of(Scope.READ, Scope.WRITE))
                .plaintext());
        String admin = bearer(clients.issueKey(clients.createClient("acme"), Set.of(Scope.ADMIN))
                .plaintext());

        assertThat(api.send("POST", "/v1/fundings", readWrite, "{}", Map.of("Idempotency-Key", "k1"))
                        .status())
                .isEqualTo(403);
        // Past the security rules: the empty body is what's wrong now.
        assertThat(api.send("POST", "/v1/fundings", admin, "{}", Map.of("Idempotency-Key", "k1"))
                        .status())
                .isEqualTo(400);
    }

    @Test
    void anEndpointWithoutARuleIsDeniedEvenWithEveryScope() {
        String everything = bearer(clients.issueKey(clients.createClient("acme"), EnumSet.allOf(Scope.class))
                .plaintext());

        assertThat(api.get("/v1/something-new", everything).status()).isEqualTo(403);
        assertThat(api.send("DELETE", SOME_ACCOUNT, everything, null).status()).isEqualTo(403);
        assertThat(api.get("/actuator/env", everything).status()).isEqualTo(403);
    }

    @Test
    void healthNeedsNoKey() {
        assertThat(api.get("/actuator/health", null).status()).isEqualTo(200);
    }

    private static JsonNode withoutRequestId(HttpApi.Response response) {
        ObjectNode copy = (ObjectNode) response.json().deepCopy();
        copy.remove("requestId");
        return copy;
    }
}
