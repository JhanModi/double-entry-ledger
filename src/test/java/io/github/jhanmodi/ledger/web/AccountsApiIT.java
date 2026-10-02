package io.github.jhanmodi.ledger.web;

import static io.github.jhanmodi.ledger.HttpApi.bearer;
import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jhanmodi.ledger.HttpApi;
import io.github.jhanmodi.ledger.OwnerDatabase;
import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.clients.ClientService;
import io.github.jhanmodi.ledger.clients.IssuedApiKey;
import io.github.jhanmodi.ledger.clients.Scope;
import io.github.jhanmodi.ledger.ledger.AccountId;
import io.github.jhanmodi.ledger.ledger.AccountService;
import io.github.jhanmodi.ledger.ledger.AccountType;
import io.github.jhanmodi.ledger.ledger.LedgerFixtures;
import io.github.jhanmodi.ledger.ledger.LedgerQueries;
import io.github.jhanmodi.ledger.ledger.PostingService;
import io.github.jhanmodi.ledger.money.Money;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The accounts API over real HTTP, as two separate clients (tenants), Alice's and Bob's businesses. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountsApiIT {

    @Value("${local.server.port}")
    int port;

    @Autowired
    ClientService clients;

    @Autowired
    AccountService accountService;

    @Autowired
    PostingService postingService;

    @Autowired
    LedgerQueries queries;

    @Autowired
    OwnerDatabase owner;

    HttpApi api;
    LedgerFixtures ledger;
    ClientId alice;
    IssuedApiKey alicesIssuedKey;
    String aliceKey;
    String bobKey;

    @BeforeEach
    void setUp() {
        api = new HttpApi(port);
        ledger = new LedgerFixtures(accountService, postingService, clients);
        alice = clients.createClient("Alice's business");
        alicesIssuedKey = clients.issueKey(alice, EnumSet.of(Scope.READ, Scope.WRITE));
        aliceKey = bearer(alicesIssuedKey.plaintext());
        bobKey = bearer(clients.issueKey(clients.createClient("Bob's business"), EnumSet.of(Scope.READ, Scope.WRITE))
                .plaintext());
    }

    @Test
    void opensAnAccountOwnedByTheCallingClient() {
        HttpApi.Response response = api.post("/v1/accounts", aliceKey, "{\"currency\": \"USD\"}");

        assertThat(response.status()).isEqualTo(201);
        JsonNode body = response.json();
        String id = body.get("id").asString();
        assertThat(response.location()).contains("/v1/accounts/" + id);
        assertThat(body.get("currency").asString()).isEqualTo("USD");
        assertThat(body.get("status").asString()).isEqualTo("OPEN");
        assertThat(body.at("/balance/posted/amount").asLong()).isZero();
        assertThat(body.at("/balance/posted/currency").asString()).isEqualTo("USD");
        assertThat(queries.accountOwnedBy(alice, new AccountId(UUID.fromString(id))))
                .as("owned by Alice")
                .isNotNull();
    }

    @Test
    void openingAnAccountIsAuditedWithTheKeyAndTheRequestItCameIn() {
        HttpApi.Response response = api.post("/v1/accounts", aliceKey, "{\"currency\": \"USD\"}");
        String id = response.json().get("id").asString();

        Map<String, Object> audit =
                owner.jdbc().sql("""
                        SELECT action, actor_type, actor_client_id, actor_key_id, request_id::text AS request_id,
                               host(source_ip) AS source_ip
                        FROM audit_log
                        WHERE target_id = :id
                        """).param("id", id).query().singleRow();

        assertThat(audit)
                .containsEntry("action", "ACCOUNT_OPENED")
                .containsEntry("actor_type", "API_KEY")
                .containsEntry("actor_client_id", alice.value())
                .containsEntry("actor_key_id", alicesIssuedKey.keyId())
                // The same id the client got back in the X-Request-Id header: the link between its report and this row.
                .containsEntry("request_id", response.requestId().orElseThrow());
        // The test client connects over loopback, as IPv4 or IPv6 depending on how "localhost" resolves.
        assertThat(audit.get("source_ip")).isIn("127.0.0.1", "::1");
    }

    @Test
    void readsAnAccountWithItsBalance() {
        String id = openAccount(aliceKey);
        ledger.fund(ledger.bank(USD), accountId(id), Money.of(1250, USD));

        JsonNode body = api.get("/v1/accounts/" + id, aliceKey).json();

        assertThat(body.at("/balance/posted/amount").asLong()).isEqualTo(1250);
        assertThat(body.at("/balance/held/amount").asLong()).isZero();
        assertThat(body.at("/balance/available/amount").asLong()).isEqualTo(1250);
    }

    @Test
    void pagesThroughHistoryNewestFirst() {
        String id = openAccount(aliceKey);
        AccountId bank = ledger.bank(USD);
        for (long amount = 1; amount <= 3; amount++) {
            ledger.fund(bank, accountId(id), Money.of(amount, USD));
        }

        JsonNode first =
                api.get("/v1/accounts/" + id + "/entries?limit=2", aliceKey).json();
        String cursor = first.get("nextCursor").asString();
        JsonNode second = api.get("/v1/accounts/" + id + "/entries?limit=2&cursor=" + cursor, aliceKey)
                .json();

        assertThat(first.get("entries")).hasSize(2);
        assertThat(first.at("/entries/0/amount/amount").asLong()).isEqualTo(3);
        assertThat(first.at("/entries/1/amount/amount").asLong()).isEqualTo(2);
        assertThat(first.at("/entries/0/direction").asString()).isEqualTo("CREDIT");
        assertThat(second.get("entries")).hasSize(1);
        assertThat(second.at("/entries/0/amount/amount").asLong()).isEqualTo(1);
        assertThat(second.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void anotherClientsAccountLooksExactlyLikeOneThatDoesNotExist() {
        String alicesAccount = openAccount(aliceKey);

        HttpApi.Response bobReadsAlices = api.get("/v1/accounts/" + alicesAccount, bobKey);
        HttpApi.Response bobReadsAlicesHistory = api.get("/v1/accounts/" + alicesAccount + "/entries", bobKey);
        HttpApi.Response bobReadsNothing = api.get("/v1/accounts/" + UUID.randomUUID(), bobKey);

        assertThat(bobReadsAlices.status()).isEqualTo(404);
        assertThat(bobReadsAlicesHistory.status()).isEqualTo(404);
        assertThat(bobReadsNothing.status()).isEqualTo(404);
        // Same body too, so nothing reveals that Alice's account exists. Only "instance" (the path) differs.
        assertThat(withoutInstance(bobReadsAlices.json())).isEqualTo(withoutInstance(bobReadsNothing.json()));
    }

    @Test
    void systemAccountsAreNotFound() {
        AccountId bankSettlement = accountService.openSystemAccount(AccountType.ASSET, USD);

        assertThat(api.get("/v1/accounts/" + bankSettlement, aliceKey).status()).isEqualTo(404);
        assertThat(api.get("/v1/accounts/" + bankSettlement + "/entries", aliceKey)
                        .status())
                .isEqualTo(404);
    }

    @Test
    void theOwnerAlwaysComesFromTheKeyNeverFromTheBody() {
        String bobsClientId = UUID.randomUUID().toString();
        String id = api.post(
                        "/v1/accounts", aliceKey, "{\"currency\": \"USD\", \"clientId\": \"" + bobsClientId + "\"}")
                .json()
                .get("id")
                .asString();

        assertThat(queries.accountOwnedBy(alice, accountId(id))).isNotNull();
        assertThat(api.get("/v1/accounts/" + id, bobKey).status()).isEqualTo(404);
    }

    @Test
    void rejectsAnUnsupportedOrMissingCurrency() {
        assertProblem(api.post("/v1/accounts", aliceKey, "{\"currency\": \"GBP\"}"), 400);
        assertProblem(api.post("/v1/accounts", aliceKey, "{}"), 400);
        assertProblem(api.post("/v1/accounts", aliceKey, "{not json"), 400);
    }

    @Test
    void rejectsMalformedIdsAndPageSizes() {
        String id = openAccount(aliceKey);

        assertProblem(api.get("/v1/accounts/not-a-uuid", aliceKey), 400);
        assertProblem(api.get("/v1/accounts/" + id + "/entries?limit=0", aliceKey), 400);
        assertProblem(api.get("/v1/accounts/" + id + "/entries?limit=101", aliceKey), 400);
        assertProblem(api.get("/v1/accounts/" + id + "/entries?cursor=not-a-uuid", aliceKey), 400);
    }

    private void assertProblem(HttpApi.Response response, int status) {
        assertThat(response.status()).as(response.body()).isEqualTo(status);
        assertThat(response.contentType())
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.body()).doesNotContain("Exception").doesNotContain("\"trace\"");
    }

    private String openAccount(String key) {
        return api.post("/v1/accounts", key, "{\"currency\": \"USD\"}")
                .json()
                .get("id")
                .asString();
    }

    private static AccountId accountId(String id) {
        return new AccountId(UUID.fromString(id));
    }

    private static JsonNode withoutInstance(JsonNode problem) {
        ObjectNode copy = (ObjectNode) problem.deepCopy();
        copy.remove("instance");
        return copy;
    }
}
