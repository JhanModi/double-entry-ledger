package io.github.jhanmodi.ledger.transfers;

import static io.github.jhanmodi.ledger.DatabaseLocks.awaitASessionWaitingForALock;
import static io.github.jhanmodi.ledger.DatabaseLocks.lockAccount;
import static io.github.jhanmodi.ledger.money.CurrencyCode.EUR;
import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jhanmodi.ledger.HeldTransaction;
import io.github.jhanmodi.ledger.OwnerDatabase;
import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import io.github.jhanmodi.ledger.clients.Caller;
import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.clients.ClientService;
import io.github.jhanmodi.ledger.clients.Scope;
import io.github.jhanmodi.ledger.clients.ScopeRequiredException;
import io.github.jhanmodi.ledger.idempotency.IdempotencyKey;
import io.github.jhanmodi.ledger.idempotency.IdempotencyKeyReusedException;
import io.github.jhanmodi.ledger.idempotency.IdempotentResult;
import io.github.jhanmodi.ledger.ledger.AccountId;
import io.github.jhanmodi.ledger.ledger.AccountNotFoundException;
import io.github.jhanmodi.ledger.ledger.AccountPurpose;
import io.github.jhanmodi.ledger.ledger.AccountService;
import io.github.jhanmodi.ledger.ledger.LedgerFixtures;
import io.github.jhanmodi.ledger.ledger.LedgerQueries;
import io.github.jhanmodi.ledger.ledger.PostingService;
import io.github.jhanmodi.ledger.money.CurrencyCode;
import io.github.jhanmodi.ledger.money.Money;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Funding against a real database (ADR-0018): it credits only the caller's own account, debits the bank-settlement
 * account for that currency, needs the admin scope, and a retry replays the original and never funds twice (ADR-0023).
 * The same-key races are in TransferServiceIT: both services claim their key through the same code.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FundingServiceIT {

    @Autowired
    FundingService fundings;

    @Autowired
    TransferService transfers;

    @Autowired
    AccountService accountService;

    @Autowired
    PostingService postingService;

    @Autowired
    ClientService clientService;

    @Autowired
    LedgerQueries queries;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OwnerDatabase owner;

    LedgerFixtures ledger;
    ClientId client;
    AccountId account;

    @BeforeEach
    void setUp() {
        ledger = new LedgerFixtures(accountService, postingService, clientService);
        client = ledger.client();
        account = ledger.customer(USD);
    }

    @Test
    void creditsTheClientsAccountFromTheBankSettlementAccount() {
        IdempotencyKey key = key();
        Caller admin = admin();

        IdempotentResult<Funding> result =
                fundings.fund(new FundingCommand(account, usd(2500), "BANK-REF-1", key), admin);

        Funding funding = result.value();
        AccountId settlement =
                queries.systemAccount(AccountPurpose.BANK_SETTLEMENT, USD).id();
        assertThat(result.replayed()).isFalse();
        assertThat(queries.balance(account).posted()).isEqualTo(usd(2500));
        assertThat(funding.account()).isEqualTo(account);
        assertThat(funding.amount()).isEqualTo(usd(2500));
        assertThat(funding.externalReference()).isEqualTo("BANK-REF-1");
        assertThat(funding.idempotencyKey()).isEqualTo(key);
        // The bank holds more cash (a debit raises an asset); the platform owes the customer more.
        assertThat(entries(funding))
                .containsExactlyInAnyOrder("DEBIT " + settlement + " 2500", "CREDIT " + account + " 2500");
        assertThat(queries.history(account, Optional.empty(), 1)
                        .entries()
                        .getFirst()
                        .description())
                .isEqualTo("Deposit BANK-REF-1");
        assertThat(auditedIn(admin)).containsExactly("FUNDING_CREATED " + funding.id());
        assertThat(claimsFor(key)).containsExactly("FUNDING");
    }

    @ParameterizedTest
    @EnumSource(CurrencyCode.class)
    void eachCurrencyIsFundedFromItsOwnSettlementAccount(CurrencyCode currency) {
        AccountId inCurrency = ledger.customer(currency);

        Funding funding = fundings.fund(
                        new FundingCommand(inCurrency, Money.of(1, currency), "BANK-REF-2", key()), admin())
                .value();

        AccountId settlement =
                queries.systemAccount(AccountPurpose.BANK_SETTLEMENT, currency).id();
        assertThat(entries(funding)).contains("DEBIT " + settlement + " 1");
    }

    @Test
    void fundingNeedsTheAdminScope() {
        Caller writer = ledger.caller(client, EnumSet.of(Scope.READ, Scope.WRITE));
        IdempotencyKey key = key();

        assertThatThrownBy(() -> fundings.fund(new FundingCommand(account, usd(100), "BANK-REF-3", key), writer))
                .isInstanceOf(ScopeRequiredException.class);
        assertThat(queries.balance(account).posted()).isEqualTo(usd(0));
        assertThat(auditedIn(writer)).isEmpty();
        assertThat(claimsFor(key)).as("refused before the key was claimed").isEmpty();
    }

    @Test
    void onlyTheClientsOwnAccountCanBeFunded() {
        AccountId othersAccount = ledger.customer(clientService.createClient("someone else"), USD);
        AccountId settlement =
                queries.systemAccount(AccountPurpose.BANK_SETTLEMENT, USD).id();

        for (AccountId target : List.of(othersAccount, settlement, new AccountId(UUID.randomUUID()))) {
            IdempotencyKey key = key();
            assertThatThrownBy(() -> fundings.fund(new FundingCommand(target, usd(100), "BANK-REF-4", key), admin()))
                    .isInstanceOf(AccountNotFoundException.class);
            assertThat(claimsFor(key)).as("the claim rolled back with the rest").isEmpty();
        }
        assertThat(queries.balance(othersAccount).posted()).isEqualTo(usd(0));
    }

    @Test
    void theAmountMustBeInTheAccountsCurrency() {
        assertThatThrownBy(() ->
                        fundings.fund(new FundingCommand(account, Money.of(100, EUR), "BANK-REF-5", key()), admin()))
                .isInstanceOf(WrongCurrencyException.class);
        assertThat(queries.balance(account).posted()).isEqualTo(usd(0));
    }

    // --- Retries (ADR-0023) ---

    @Test
    void aRetryOfTheSameFundingReplaysTheOriginalAndFundsOnce() {
        IdempotencyKey key = key();
        Funding original = fundings.fund(new FundingCommand(account, usd(100), "BANK-REF-6", key), admin())
                .value();
        Caller retrying = admin();

        IdempotentResult<Funding> retry =
                fundings.fund(new FundingCommand(account, usd(100), "BANK-REF-6", key), retrying);

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.value()).isEqualTo(original);
        assertThat(queries.balance(account).posted()).isEqualTo(usd(100));
        assertThat(fundingsWithKey(key)).isOne();
        assertThat(auditedIn(retrying)).isEmpty();
    }

    @Test
    void theSameKeyWithADifferentFundingIsRefusedAndFundsNothing() {
        IdempotencyKey key = key();
        fundings.fund(new FundingCommand(account, usd(100), "BANK-REF-7", key), admin());

        assertThatThrownBy(() -> fundings.fund(new FundingCommand(account, usd(100), "BANK-REF-8", key), admin()))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(queries.balance(account).posted()).isEqualTo(usd(100));
    }

    @Test
    void aKeyAlreadyUsedForATransferIsRefusedForAFunding() {
        // Keys are per client, across operations (ADR-0023): until M6, a transfer and a funding could share one.
        AccountId savings = ledger.customer(USD);
        fundings.fund(new FundingCommand(account, usd(100), "BANK-REF-9", key()), admin());
        IdempotencyKey transfersKey = key();
        transfers.transfer(new TransferCommand(account, savings, usd(40), null, transfersKey), ledger.caller(client));

        assertThatThrownBy(
                        () -> fundings.fund(new FundingCommand(account, usd(40), "BANK-REF-10", transfersKey), admin()))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(queries.balance(account).posted()).isEqualTo(usd(60));
        assertThat(fundingsWithKey(transfersKey)).isZero();
    }

    @Test
    void aRetryAfterItsClaimWasDeletedIsRefusedWithTheOriginalsIdAndFundsNothing() {
        IdempotencyKey key = key();
        Funding original = fundings.fund(new FundingCommand(account, usd(100), "BANK-REF-11", key), admin())
                .value();
        deleteClaim(key);

        // The funding row's key is checked before the account is touched. To show that, another transaction holds the
        // account's lock throughout: a retry that skipped the check would wait for it, and give up as "busy".
        try (HeldTransaction other = HeldTransaction.start(owner, sql -> lockAccount(sql, account))) {
            assertThatThrownBy(() -> fundings.fund(new FundingCommand(account, usd(100), "BANK-REF-11", key), admin()))
                    .isInstanceOfSatisfying(
                            DuplicateRequestException.class,
                            e -> assertThat(e.originalId())
                                    .isEqualTo(original.id().value()));
        }
        assertThat(queries.balance(account).posted()).isEqualTo(usd(100));
        assertThat(fundingsWithKey(key)).isOne();
    }

    // --- Deadlocks (ADR-0022) ---

    @Test
    void aFundingThatPostgresAbortsToBreakADeadlockIsRetriedAndFundsOnce() throws Exception {
        IdempotencyKey key = key();
        Caller admin = admin();
        ExecutorService fundingThread = Executors.newSingleThreadExecutor();
        try (HeldTransaction other = HeldTransaction.start(owner, sql -> lockAccount(sql, account))) {
            // The funding has already claimed its key and read the fundings table (looking for a row with the key whose
            // claim has expired), which keeps a light lock on the table until its transaction ends. Now it waits for
            // the account, which the other transaction holds.
            Future<IdempotentResult<Funding>> funding = fundingThread.submit(
                    () -> fundings.fund(new FundingCommand(account, usd(100), "BANK-REF-12", key), admin));
            awaitASessionWaitingForALock(owner);

            // A funding only ever locks one account, so two fundings can't deadlock. A schema change can: the other
            // transaction asks for the whole table, which waits for the funding, which waits for it. The funding has
            // waited longer, so Postgres aborts it (SQLSTATE 40P01).
            Future<Void> otherGetsTheTable = other.then(sql ->
                    sql.sql("LOCK TABLE fundings IN ACCESS EXCLUSIVE MODE").update());
            // Only the funding's rollback can free the table, so this completing proves the first attempt was aborted.
            HeldTransaction.await(otherGetsTheTable);
            other.rollback();

            assertThat(funding.get(30, SECONDS).value().amount()).isEqualTo(usd(100));
        } finally {
            fundingThread.shutdownNow();
        }
        assertThat(queries.balance(account).posted()).isEqualTo(usd(100));
        assertThat(fundingsWithKey(key)).isOne();
        assertThat(claimsFor(key)).hasSize(1);
    }

    // --- helpers ---

    /** The client making a new request with a key that has every scope, admin included. */
    private Caller admin() {
        return ledger.caller(client, EnumSet.allOf(Scope.class));
    }

    /** The ledger entries behind a funding, as "DIRECTION account amount". */
    private List<String> entries(Funding funding) {
        return jdbc.sql(
                        "SELECT direction || ' ' || account_id || ' ' || amount FROM entries WHERE transaction_id = :id")
                .param("id", funding.ledgerTransactionId().value())
                .query(String.class)
                .list();
    }

    private long fundingsWithKey(IdempotencyKey key) {
        return jdbc.sql("SELECT count(*) FROM fundings WHERE client_id = :clientId AND idempotency_key = :key")
                .param("clientId", client.value())
                .param("key", key.value())
                .query(Long.class)
                .single();
    }

    /** The operation of the client's claim on this key, if there is one. */
    private List<String> claimsFor(IdempotencyKey key) {
        return jdbc.sql("SELECT operation FROM idempotency_keys WHERE client_id = :clientId AND idempotency_key = :key")
                .param("clientId", client.value())
                .param("key", key.value())
                .query(String.class)
                .list();
    }

    /** Deletes the client's claim on this key, as the owner: what the cleanup does once a claim has expired. */
    private void deleteClaim(IdempotencyKey key) {
        assertThat(owner.jdbc()
                        .sql("DELETE FROM idempotency_keys WHERE client_id = :clientId AND idempotency_key = :key")
                        .param("clientId", client.value())
                        .param("key", key.value())
                        .update())
                .isOne();
    }

    /** What was audited in this caller's request, as "ACTION target", read as the owner. */
    private List<String> auditedIn(Caller caller) {
        return owner.jdbc()
                .sql("SELECT action || ' ' || target_id FROM audit_log WHERE request_id = :requestId")
                .param("requestId", caller.requestId().value())
                .query(String.class)
                .list();
    }

    private static IdempotencyKey key() {
        return new IdempotencyKey("test-" + UUID.randomUUID());
    }

    private static Money usd(long cents) {
        return Money.of(cents, USD);
    }
}
