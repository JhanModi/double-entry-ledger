package io.github.jhanmodi.ledger.web;

import static io.github.jhanmodi.ledger.HttpApi.bearer;
import static io.github.jhanmodi.ledger.money.CurrencyCode.EUR;
import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jhanmodi.ledger.HttpApi;
import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.clients.ClientService;
import io.github.jhanmodi.ledger.clients.Scope;
import io.github.jhanmodi.ledger.ledger.AccountId;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * The transfers API over real HTTP, as Alice's and Bob's businesses. Every rejection is checked to have moved nothing.
 * Alice's main USD account starts each test with 10.00 USD.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransfersApiIT {

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
    String aliceKey;
    String bobKey;
    ClientId bob;
    AccountId main;
    AccountId savings;

    @BeforeEach
    void setUp() {
        api = new HttpApi(port);
        ledger = new LedgerFixtures(accountService, postingService, clients);
        ClientId alice = clients.createClient("Alice's business");
        aliceKey = bearer(
                clients.issueKey(alice, EnumSet.of(Scope.READ, Scope.WRITE)).plaintext());
        bob = clients.createClient("Bob's business");
        bobKey = bearer(
                clients.issueKey(bob, EnumSet.of(Scope.READ, Scope.WRITE)).plaintext());
        main = ledger.customer(alice, USD);
        savings = ledger.customer(alice, USD);
        ledger.fund(ledger.bank(USD), main, usd(1000));
    }

    // --- Moving money ---

    @Test
    void createsATransferAndReadsItBack() {
        String key = newKey();

        HttpApi.Response created = transfer(key, bodyWith(main, savings, "300", "USD", "\"rent\""));

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        JsonNode body = created.json();
        String id = body.get("id").asString();
        assertThat(created.location()).contains("/v1/transfers/" + id);
        assertThat(body.get("sourceAccountId").asString()).isEqualTo(main.toString());
        assertThat(body.get("destinationAccountId").asString()).isEqualTo(savings.toString());
        assertThat(body.at("/amount/amount").asLong()).isEqualTo(300);
        assertThat(body.at("/amount/currency").asString()).isEqualTo("USD");
        assertThat(body.get("description").asString()).isEqualTo("rent");
        assertThat(body.get("idempotencyKey").asString()).isEqualTo(key);
        assertThat(body.hasNonNull("ledgerTransactionId")).isTrue();
        assertThat(body.hasNonNull("createdAt")).isTrue();
        assertThat(balance(main)).isEqualTo(usd(700));
        assertThat(balance(savings)).isEqualTo(usd(300));

        HttpApi.Response read = api.get("/v1/transfers/" + id, aliceKey);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.json()).isEqualTo(body);
    }

    @Test
    void aDescriptionIsCountedInCharactersNotJavaUnits() {
        // 500 emoji: 500 characters to Postgres, 1,000 UTF-16 units in Java. The limit is 500 characters.
        String emoji = "\"" + "😀".repeat(500) + "\"";
        String tooLong = "\"" + "x".repeat(501) + "\"";

        assertThat(transfer(newKey(), bodyWith(main, savings, "1", "USD", emoji))
                        .status())
                .isEqualTo(201);
        assertInvalidFields(transfer(newKey(), bodyWith(main, savings, "1", "USD", tooLong)), "description");
    }

    // --- Retries (ADR-0019) ---

    @Test
    void aRetryWithTheSameKeyIsA409NamingTheOriginal() {
        String key = newKey();
        String originalId =
                transfer(key, body(main, savings, "300")).json().get("id").asString();

        HttpApi.Response retry = transfer(key, body(main, savings, "300"));

        assertProblem(retry, 409, "/problems/duplicate-request");
        assertThat(retry.json().get("originalId").asString()).isEqualTo(originalId);
        assertThat(balance(main)).isEqualTo(usd(700));
    }

    // --- 400: malformed input, rejected before anything else happens ---

    @Test
    void theIdempotencyKeyHeaderIsRequiredAndMustBeWellFormed() {
        HttpApi.Response missing = api.post("/v1/transfers", aliceKey, body(main, savings, "100"));
        HttpApi.Response malformed = transfer("has space", body(main, savings, "100"));

        assertInvalidFields(missing, "Idempotency-Key");
        assertInvalidFields(malformed, "Idempotency-Key");
        assertThat(balance(main)).isEqualTo(usd(1000));
    }

    @Test
    void everyKindOfInvalidInputIsTheSameProblemTypeWithAnErrorsList() {
        // Whether the framework or this API's own validation caught it, a client sees one shape for "fix your input".
        assertInvalidFields(api.post("/v1/transfers", aliceKey, body(main, savings, "1")), "Idempotency-Key");
        assertInvalidFields(api.get("/v1/transfers/not-a-uuid", aliceKey), "transferId");
        assertInvalidFields(transfer(newKey(), "{not json")); // not about one field: an empty list
        assertInvalidFields(transfer(newKey(), body(main, savings, "1.5")), "amount.amount");
        assertInvalidFields(transfer(newKey(), body(main, savings, "0")), "amount.amount");
    }

    @Test
    void anAmountMustBeAJsonInteger() {
        // Lenient parsing would turn 10.5 into 10, or "1050" into 1050: a different amount than the client sent.
        for (String amount : List.of("10.5", "1050.0", "1e3", "\"1050\"", "true")) {
            HttpApi.Response response = transfer(newKey(), body(main, savings, amount));

            assertThat(response.status()).as(amount + " -> " + response.body()).isEqualTo(400);
            assertInvalidFields(response, "amount.amount");
        }
        assertThat(balance(main)).as("nothing was moved").isEqualTo(usd(1000));
    }

    @Test
    void zeroAndNegativeAmountsAreRejectedBeforeReachingTheService() {
        for (String amount : List.of("0", "-5")) {
            HttpApi.Response response = transfer(newKey(), body(main, savings, amount));

            assertInvalidFields(response, "amount.amount");
            assertThat(response.json().at("/errors/0/message").asString()).isEqualTo("must be greater than 0");
        }
        assertThat(balance(main)).isEqualTo(usd(1000));
    }

    @Test
    void anAmountTooBigForALongIsRejectedNotWrappedAround() {
        assertInvalidFields(transfer(newKey(), body(main, savings, "9223372036854775808")), "amount.amount");
    }

    @Test
    void aFieldThisApiDoesNotDefineIsRejected() {
        // Strict parsing (ADR-0021): a misspelt or invented field is an error, not silently dropped.
        String withClientId = body(main, savings, "100").replaceFirst("\\{", "{\"clientId\": \"" + bob + "\", ");

        assertInvalidFields(transfer(newKey(), withClientId), "clientId");
        assertThat(balance(main)).isEqualTo(usd(1000));
    }

    @Test
    void missingFieldsAndUnsupportedCurrenciesAreNamed() {
        assertInvalidFields(transfer(newKey(), "{}"), "amount", "destinationAccountId", "sourceAccountId");
        assertInvalidFields(transfer(newKey(), bodyWith(main, savings, "100", "GBP", "null")), "amount.currency");
        assertInvalidFields(transfer(newKey(), "{not json"));
    }

    // --- 404: accounts and transfers the client doesn't have ---

    @Test
    void anotherClientsAccountOnEitherSideLooksExactlyLikeAMissingOne() {
        AccountId bobs = ledger.customer(bob, USD);
        AccountId missing = new AccountId(UUID.randomUUID());

        HttpApi.Response fromBobs = transfer(newKey(), body(bobs, savings, "100"));
        HttpApi.Response fromMissing = transfer(newKey(), body(missing, savings, "100"));
        HttpApi.Response toBobs = transfer(newKey(), body(main, bobs, "100"));
        HttpApi.Response toMissing = transfer(newKey(), body(main, missing, "100"));

        assertProblem(fromBobs, 404, "/problems/account-not-found");
        assertThat(fromBobs.json().get("detail").asString()).contains("sourceAccountId");
        assertThat(withoutPerRequestFields(fromBobs)).isEqualTo(withoutPerRequestFields(fromMissing));
        assertProblem(toBobs, 404, "/problems/account-not-found");
        assertThat(toBobs.json().get("detail").asString()).contains("destinationAccountId");
        assertThat(withoutPerRequestFields(toBobs)).isEqualTo(withoutPerRequestFields(toMissing));
        assertThat(balance(main)).isEqualTo(usd(1000));
        assertThat(balance(bobs)).isEqualTo(usd(0));
    }

    @Test
    void aTransferIsVisibleOnlyToTheClientThatMadeIt() {
        String id =
                transfer(newKey(), body(main, savings, "100")).json().get("id").asString();

        HttpApi.Response bobReads = api.get("/v1/transfers/" + id, bobKey);
        HttpApi.Response bobReadsNothing = api.get("/v1/transfers/" + UUID.randomUUID(), bobKey);

        assertProblem(bobReads, 404, "/problems/transfer-not-found");
        assertThat(withoutPerRequestFields(bobReads)).isEqualTo(withoutPerRequestFields(bobReadsNothing));
    }

    // --- 422: well-formed, but the business rules say no ---

    @Test
    void businessRulesAreA422WithTheirOwnTypeAndMoveNothing() {
        AccountId euros = ledger.customer(clientOf(main), EUR);

        assertProblem(transfer(newKey(), body(main, savings, "1001")), 422, "/problems/insufficient-funds");
        assertProblem(transfer(newKey(), body(main, main, "100")), 422, "/problems/same-account");
        assertProblem(transfer(newKey(), body(main, euros, "100")), 422, "/problems/currency-mismatch");

        HttpApi.Response tooLarge = transfer(newKey(), body(main, savings, "100000001"));
        assertProblem(tooLarge, 422, "/problems/amount-too-large");
        assertThat(tooLarge.json().at("/maximum/amount").asLong()).isEqualTo(100_000_000L);
        assertThat(tooLarge.json().at("/maximum/currency").asString()).isEqualTo("USD");

        assertThat(balance(main)).isEqualTo(usd(1000));
    }

    // --- Request ids (ADR-0021) ---

    @Test
    void everyErrorCarriesTheSameRequestIdAsItsResponseHeader() {
        String readOnly =
                bearer(clients.issueKey(clientOf(main), EnumSet.of(Scope.READ)).plaintext());
        String key = newKey();
        transfer(key, body(main, savings, "1"));

        List<HttpApi.Response> errors = new ArrayList<>(
                List.of(
                        api.post("/v1/transfers", aliceKey, body(main, savings, "1")), // 400: no Idempotency-Key
                        transfer(newKey(), body(main, savings, "0")), // 400: validation
                        api.send("POST", "/v1/transfers", null, body(main, savings, "1"), Map.of()), // 401
                        api.send(
                                "POST",
                                "/v1/transfers",
                                readOnly,
                                body(main, savings, "1"),
                                idempotency(newKey())), // 403
                        transfer(newKey(), body(main, new AccountId(UUID.randomUUID()), "1")), // 404
                        transfer(key, body(main, savings, "1")), // 409
                        transfer(newKey(), body(main, savings, "100000"))) // 422
                );

        for (HttpApi.Response error : errors) {
            assertThat(error.status()).as(error.body()).isGreaterThanOrEqualTo(400);
            assertThat(error.json().get("requestId").asString())
                    .as("status %d", error.status())
                    .isEqualTo(error.requestId().orElseThrow());
        }
    }

    // --- helpers ---

    private HttpApi.Response transfer(String idempotencyKey, String body) {
        return api.send("POST", "/v1/transfers", aliceKey, body, idempotency(idempotencyKey));
    }

    private static Map<String, String> idempotency(String key) {
        return Map.of("Idempotency-Key", key);
    }

    /** A transfer body in USD with no description. {@code amount} is raw JSON, so tests can send any token. */
    private static String body(AccountId source, AccountId destination, String amount) {
        return bodyWith(source, destination, amount, "USD", "null");
    }

    private static String bodyWith(
            AccountId source, AccountId destination, String amount, String currency, String descriptionJson) {
        return """
                {"sourceAccountId": "%s", "destinationAccountId": "%s",
                 "amount": {"amount": %s, "currency": "%s"}, "description": %s}
                """.formatted(source, destination, amount, currency, descriptionJson);
    }

    private void assertProblem(HttpApi.Response response, int status, String type) {
        assertThat(response.status()).as(response.body()).isEqualTo(status);
        assertThat(response.contentType())
                .hasValueSatisfying(contentType -> assertThat(contentType).startsWith("application/problem+json"));
        assertThat(response.body())
                .doesNotContain("Exception")
                .doesNotContain("io.github")
                .doesNotContain("trace");
        if (type != null) {
            assertThat(response.json().get("type").asString()).isEqualTo(type);
        }
    }

    /** A 400 that names exactly these fields (or headers) as the ones at fault. */
    private void assertInvalidFields(HttpApi.Response response, String... fields) {
        assertProblem(response, 400, "/problems/invalid-request");
        List<String> named = new ArrayList<>();
        response.json()
                .get("errors")
                .forEach(error -> named.add(error.get("field").asString()));
        assertThat(named).containsExactly(fields);
    }

    /** The body without the fields that differ on every request: the path ({@code instance}) and the request id. */
    private static JsonNode withoutPerRequestFields(HttpApi.Response response) {
        ObjectNode copy = (ObjectNode) response.json().deepCopy();
        copy.remove("instance");
        copy.remove("requestId");
        return copy;
    }

    private ClientId clientOf(AccountId account) {
        return queries.account(account).clientId();
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
