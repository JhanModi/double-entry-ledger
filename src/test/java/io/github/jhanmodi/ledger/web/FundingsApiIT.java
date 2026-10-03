package io.github.jhanmodi.ledger.web;

import static io.github.jhanmodi.ledger.HttpApi.bearer;
import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jhanmodi.ledger.HttpApi;
import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.clients.ClientService;
import io.github.jhanmodi.ledger.clients.Scope;
import io.github.jhanmodi.ledger.ledger.AccountId;
import io.github.jhanmodi.ledger.ledger.AccountPurpose;
import io.github.jhanmodi.ledger.ledger.AccountService;
import io.github.jhanmodi.ledger.ledger.LedgerFixtures;
import io.github.jhanmodi.ledger.ledger.LedgerQueries;
import io.github.jhanmodi.ledger.ledger.PostingService;
import io.github.jhanmodi.ledger.money.Money;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

/** Funding over real HTTP (ADR-0018): an admin key funds its own client's account, and nothing else. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FundingsApiIT {

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

    HttpApi api;
    LedgerFixtures ledger;
    ClientId client;
    String adminKey;
    AccountId account;

    @BeforeEach
    void setUp() {
        api = new HttpApi(port);
        ledger = new LedgerFixtures(accountService, postingService, clients);
        client = clients.createClient("Acme");
        adminKey = bearer(clients.issueKey(client, EnumSet.allOf(Scope.class)).plaintext());
        account = ledger.customer(client, USD);
    }

    @Test
    void anAdminKeyFundsItsOwnClientsAccount() {
        String key = newKey();

        HttpApi.Response response = fund(adminKey, key, body(account, "2500", "\"BANK-REF-1\""));

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        JsonNode body = response.json();
        assertThat(body.get("accountId").asString()).isEqualTo(account.toString());
        assertThat(body.at("/amount/amount").asLong()).isEqualTo(2500);
        assertThat(body.get("externalReference").asString()).isEqualTo("BANK-REF-1");
        assertThat(body.get("idempotencyKey").asString()).isEqualTo(key);
        assertThat(balance(account)).isEqualTo(usd(2500));
    }

    @Test
    void aKeyWithoutAdminCannotFund() {
        String writeKey = bearer(
                clients.issueKey(client, EnumSet.of(Scope.READ, Scope.WRITE)).plaintext());

        HttpApi.Response response = fund(writeKey, newKey(), body(account, "2500", "\"BANK-REF-2\""));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.json().get("type").asString()).isEqualTo("/problems/forbidden");
        assertThat(balance(account)).isEqualTo(usd(0));
    }

    @Test
    void anotherClientsAccountOrASystemAccountIsNotFound() {
        AccountId others = ledger.customer(clients.createClient("Someone else"), USD);
        AccountId settlement =
                queries.systemAccount(AccountPurpose.BANK_SETTLEMENT, USD).id();

        for (AccountId target : List.of(others, settlement)) {
            HttpApi.Response response = fund(adminKey, newKey(), body(target, "100", "\"BANK-REF-3\""));

            assertThat(response.status()).as(response.body()).isEqualTo(404);
            assertThat(response.json().get("type").asString()).isEqualTo("/problems/account-not-found");
        }
        assertThat(balance(others)).isEqualTo(usd(0));
    }

    @Test
    void theExternalReferenceIsRequiredAndAtMost100Characters() {
        for (String reference : List.of("null", "\"   \"", "\"" + "r".repeat(101) + "\"")) {
            HttpApi.Response response = fund(adminKey, newKey(), body(account, "100", reference));

            assertThat(response.status()).as(reference).isEqualTo(400);
            assertThat(fieldsNamedIn(response)).containsExactly("externalReference");
        }
        assertThat(balance(account)).isEqualTo(usd(0));
    }

    @Test
    void aRetryWithTheSameKeyFundsOnce() {
        String key = newKey();
        String originalId = fund(adminKey, key, body(account, "100", "\"BANK-REF-4\""))
                .json()
                .get("id")
                .asString();

        HttpApi.Response retry = fund(adminKey, key, body(account, "100", "\"BANK-REF-4\""));

        assertThat(retry.status()).isEqualTo(409);
        assertThat(retry.json().get("originalId").asString()).isEqualTo(originalId);
        assertThat(balance(account)).isEqualTo(usd(100));
    }

    private HttpApi.Response fund(String authorization, String idempotencyKey, String body) {
        return api.send("POST", "/v1/fundings", authorization, body, Map.of("Idempotency-Key", idempotencyKey));
    }

    private static String body(AccountId account, String amount, String externalReferenceJson) {
        return """
                {"accountId": "%s", "amount": {"amount": %s, "currency": "USD"}, "externalReference": %s}
                """.formatted(account, amount, externalReferenceJson);
    }

    private static List<String> fieldsNamedIn(HttpApi.Response response) {
        List<String> named = new ArrayList<>();
        response.json()
                .get("errors")
                .forEach(error -> named.add(error.get("field").asString()));
        return named;
    }

    private Money balance(AccountId account) {
        return queries.balance(account).posted();
    }

    private static String newKey() {
        return "test-" + UUID.randomUUID();
    }

    private static Money usd(long cents) {
        return Money.of(cents, USD);
    }
}
