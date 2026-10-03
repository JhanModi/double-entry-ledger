package io.github.jhanmodi.ledger.ledger;

import static io.github.jhanmodi.ledger.ledger.NewEntry.credit;
import static io.github.jhanmodi.ledger.ledger.NewEntry.debit;

import io.github.jhanmodi.ledger.audit.RequestId;
import io.github.jhanmodi.ledger.clients.AuthenticatedClient;
import io.github.jhanmodi.ledger.clients.Caller;
import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.clients.ClientService;
import io.github.jhanmodi.ledger.clients.IssuedApiKey;
import io.github.jhanmodi.ledger.clients.Scope;
import io.github.jhanmodi.ledger.money.CurrencyCode;
import io.github.jhanmodi.ledger.money.Money;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shorthand for setting up ledger state in integration tests. Everything goes through the real services. Each test
 * creates its own accounts, because append-only data can't be cleaned up between tests. Customer accounts belong to a
 * client created for this fixtures instance, unless a test passes its own.
 *
 * <p>Opening a customer account is audited, so it needs a {@link Caller}, as if the client were making an API request.
 * The fixtures issue each client a real key the first time it's needed.
 */
public final class LedgerFixtures {

    /** Where test callers' requests "come from": a range reserved for documentation (RFC 5737), never a real host. */
    public static final String TEST_SOURCE_IP = "203.0.113.10";

    private final AccountService accounts;
    private final PostingService postings;
    private final ClientService clients;
    private final Map<KeyFor, AuthenticatedClient> keys = new HashMap<>();
    private ClientId client;

    public LedgerFixtures(AccountService accounts, PostingService postings, ClientService clients) {
        this.accounts = accounts;
        this.postings = postings;
        this.clients = clients;
    }

    /** The client that owns customer accounts created by {@link #customer(CurrencyCode)}. */
    public ClientId client() {
        if (client == null) {
            client = clients.createClient("test client");
        }
        return client;
    }

    public AccountId customer(CurrencyCode currency) {
        return customer(client(), currency);
    }

    public AccountId customer(ClientId owner, CurrencyCode currency) {
        return accounts.openCustomerAccount(caller(owner), currency);
    }

    /** This client making a new request with a read and write key: a fresh request id, from {@link #TEST_SOURCE_IP}. */
    public Caller caller(ClientId owner) {
        return caller(owner, EnumSet.of(Scope.READ, Scope.WRITE));
    }

    /** As {@link #caller(ClientId)}, with a key that has exactly these scopes (issued once per client and scopes). */
    public Caller caller(ClientId owner, Set<Scope> scopes) {
        AuthenticatedClient authenticated = keys.computeIfAbsent(new KeyFor(owner, Set.copyOf(scopes)), want -> {
            IssuedApiKey key = clients.issueKey(want.owner(), want.scopes());
            return new AuthenticatedClient(want.owner(), key.keyId(), key.scopes());
        });
        return new Caller(authenticated, new RequestId(UUID.randomUUID()), TEST_SOURCE_IP);
    }

    private record KeyFor(ClientId owner, Set<Scope> scopes) {}

    /** A bank-settlement account: an asset, so debits increase it. */
    public AccountId bank(CurrencyCode currency) {
        return accounts.openSystemAccount(AccountType.ASSET, currency);
    }

    /** Money arriving from outside: the bank holds more cash, and the platform owes the customer more. */
    public LedgerTransactionId fund(AccountId bank, AccountId customer, Money amount) {
        return postings.post(new PostingRequest(
                LedgerTransactionType.FUNDING, "test funding", List.of(debit(bank, amount), credit(customer, amount))));
    }

    public LedgerTransactionId transfer(AccountId from, AccountId to, Money amount) {
        return postings.post(new PostingRequest(
                LedgerTransactionType.TRANSFER, null, List.of(debit(from, amount), credit(to, amount))));
    }
}
