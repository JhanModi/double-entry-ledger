package io.github.jhanmodi.ledger.transfers;

import static io.github.jhanmodi.ledger.money.CurrencyCode.EUR;
import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jhanmodi.ledger.OwnerDatabase;
import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.clients.ClientService;
import io.github.jhanmodi.ledger.ledger.AccountId;
import io.github.jhanmodi.ledger.ledger.AccountService;
import io.github.jhanmodi.ledger.ledger.LedgerFixtures;
import io.github.jhanmodi.ledger.ledger.PostingService;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves each database guard on the {@code transfers} and {@code fundings} tables (V5, ADR-0018, ADR-0019) works on its
 * own, by going around the Java code with raw SQL as the database owner. The same approach as LedgerSchemaIT: these
 * guards catch mistakes by any role, even the most privileged one.
 *
 * <p>Every statement runs in a rolled-back transaction, so nothing here is ever committed, and even a missing guard
 * can't damage the shared test database. Rows that need a ledger transaction get a bare one in the same transaction;
 * the deferred balance check never fires, because nothing is committed.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MoneyMovementSchemaIT {

    @Autowired
    OwnerDatabase owner;

    @Autowired
    AccountService accountService;

    @Autowired
    PostingService postingService;

    @Autowired
    ClientService clientService;

    JdbcClient jdbc;
    TransactionTemplate tx;
    LedgerFixtures ledger;

    ClientId alice;
    ClientId bob;
    AccountId aliceUsd;
    AccountId aliceSecondUsd;
    AccountId aliceEur;
    AccountId bobUsd;
    AccountId bankSettlementUsd;

    @BeforeEach
    void setUp() {
        jdbc = owner.jdbc();
        tx = owner.transactions();
        ledger = new LedgerFixtures(accountService, postingService, clientService);
        alice = clientService.createClient("alice");
        bob = clientService.createClient("bob");
        aliceUsd = ledger.customer(alice, USD);
        aliceSecondUsd = ledger.customer(alice, USD);
        aliceEur = ledger.customer(alice, EUR);
        bobUsd = ledger.customer(bob, USD);
        bankSettlementUsd =
                new AccountId(jdbc.sql("SELECT id FROM accounts WHERE purpose = 'BANK_SETTLEMENT' AND currency = 'USD'")
                        .query(UUID.class)
                        .single());
    }

    // --- Transfers: whose accounts, and in which currency ---

    @Test
    void aValidTransferIsAccepted() {
        // The baseline: without it, the rejections below could be failing for some other reason.
        assertAccepted(() -> insertTransfer(transfer()));
    }

    @Test
    void theSourceMustBelongToTheTransfersClient() {
        Map<String, Object> row = transfer();
        row.put("source", bobUsd.value());

        assertRejected(() -> insertTransfer(row), "transfers_source_account_fkey");
    }

    @Test
    void theDestinationMustBelongToTheTransfersClient() {
        Map<String, Object> row = transfer();
        row.put("destination", bobUsd.value());

        assertRejected(() -> insertTransfer(row), "transfers_destination_account_fkey");
    }

    @Test
    void aSystemAccountCanNeverBeEitherSide() {
        // A system account has no client, so it can't match the transfer's client.
        Map<String, Object> fromBank = transfer();
        fromBank.put("source", bankSettlementUsd.value());
        Map<String, Object> toBank = transfer();
        toBank.put("destination", bankSettlementUsd.value());

        assertRejected(() -> insertTransfer(fromBank), "transfers_source_account_fkey");
        assertRejected(() -> insertTransfer(toBank), "transfers_destination_account_fkey");
    }

    @Test
    void bothAccountsMustBeInTheTransfersCurrency() {
        Map<String, Object> row = transfer();
        row.put("source", aliceEur.value());

        assertRejected(() -> insertTransfer(row), "transfers_source_account_fkey");
    }

    @Test
    void theSourceAndDestinationMustDiffer() {
        Map<String, Object> row = transfer();
        row.put("destination", row.get("source"));

        assertRejected(() -> insertTransfer(row), "transfers_distinct_accounts");
    }

    // --- Transfers: amount, description, and links ---

    @Test
    void aTransferAmountMustBePositive() {
        for (long amount : new long[] {0, -1}) {
            Map<String, Object> row = transfer();
            row.put("amount", amount);

            assertRejected(() -> insertTransfer(row), "transfers_amount_check");
        }
    }

    @Test
    void aTransferDescriptionIsAtMost500Characters() {
        Map<String, Object> longest = transfer();
        longest.put("description", "x".repeat(500));
        Map<String, Object> tooLong = transfer();
        tooLong.put("description", "x".repeat(501));

        assertAccepted(() -> insertTransfer(longest));
        assertRejected(() -> insertTransfer(tooLong), "transfers_description_length");
    }

    @Test
    void eachLedgerTransactionBacksAtMostOneTransfer() {
        assertRejected(
                () -> {
                    Map<String, Object> first = transfer();
                    insertTransfer(first);
                    Map<String, Object> second = transfer();
                    second.put("ledgerTransactionId", first.get("ledgerTransactionId"));
                    insertTransfer(second);
                },
                "transfers_ledger_transaction_id_key");
    }

    // --- Transfers: idempotency keys (ADR-0019) ---

    @Test
    void aClientCanUseAnIdempotencyKeyOnlyOnce() {
        assertRejected(
                () -> {
                    Map<String, Object> first = transfer();
                    insertTransfer(first);
                    Map<String, Object> retry = transfer();
                    retry.put("idempotencyKey", first.get("idempotencyKey"));
                    insertTransfer(retry);
                },
                "transfers_client_idempotency_key_key");
    }

    @Test
    void differentClientsMayUseTheSameIdempotencyKey() {
        AccountId bobSecondUsd = ledger.customer(bob, USD);

        assertAccepted(() -> {
            Map<String, Object> alices = transfer();
            insertTransfer(alices);
            Map<String, Object> bobs = transfer();
            bobs.put("clientId", bob.value());
            bobs.put("source", bobUsd.value());
            bobs.put("destination", bobSecondUsd.value());
            bobs.put("idempotencyKey", alices.get("idempotencyKey"));
            insertTransfer(bobs);
        });
    }

    @Test
    void anIdempotencyKeyIs1To255SafeCharacters() {
        for (String valid : new String[] {"a", "order-123:attempt_2.v1", "k".repeat(255)}) {
            Map<String, Object> row = transfer();
            row.put("idempotencyKey", valid);

            assertAccepted(() -> insertTransfer(row));
        }
        for (String invalid : new String[] {"", "has space", "café", "line\nbreak", "k".repeat(256)}) {
            Map<String, Object> row = transfer();
            row.put("idempotencyKey", invalid);

            assertRejected(() -> insertTransfer(row), "transfers_idempotency_key_format");
        }
    }

    // --- Transfers are append-only ---

    @Test
    void transfersCannotBeUpdatedDeletedOrTruncated() {
        assertRejected(
                () -> {
                    Map<String, Object> row = transfer();
                    insertTransfer(row);
                    jdbc.sql("UPDATE transfers SET amount = amount + 1 WHERE idempotency_key = :key")
                            .param("key", row.get("idempotencyKey"))
                            .update();
                },
                "money movements are append-only");
        assertRejected(
                () -> {
                    Map<String, Object> row = transfer();
                    insertTransfer(row);
                    jdbc.sql("DELETE FROM transfers WHERE idempotency_key = :key")
                            .param("key", row.get("idempotencyKey"))
                            .update();
                },
                "money movements are append-only");
        assertRejected(() -> jdbc.sql("TRUNCATE transfers").update(), "money movements are append-only");
    }

    // --- Fundings ---

    @Test
    void aValidFundingIsAccepted() {
        assertAccepted(() -> insertFunding(funding()));
    }

    @Test
    void aFundingCreditsOnlyTheClientsOwnAccount() {
        Map<String, Object> othersAccount = funding();
        othersAccount.put("account", bobUsd.value());
        Map<String, Object> systemAccount = funding();
        systemAccount.put("account", bankSettlementUsd.value());

        assertRejected(() -> insertFunding(othersAccount), "fundings_account_fkey");
        assertRejected(() -> insertFunding(systemAccount), "fundings_account_fkey");
    }

    @Test
    void aFundingMustBeInItsAccountsCurrency() {
        Map<String, Object> row = funding();
        row.put("currency", "EUR");

        assertRejected(() -> insertFunding(row), "fundings_account_fkey");
    }

    @Test
    void aFundingAmountMustBePositive() {
        for (long amount : new long[] {0, -1}) {
            Map<String, Object> row = funding();
            row.put("amount", amount);

            assertRejected(() -> insertFunding(row), "fundings_amount_check");
        }
    }

    @Test
    void aFundingNeedsAnExternalReferenceOf1To100Characters() {
        Map<String, Object> longest = funding();
        longest.put("externalReference", "r".repeat(100));
        Map<String, Object> empty = funding();
        empty.put("externalReference", "");
        Map<String, Object> tooLong = funding();
        tooLong.put("externalReference", "r".repeat(101));

        assertAccepted(() -> insertFunding(longest));
        assertRejected(() -> insertFunding(empty), "fundings_external_reference_length");
        assertRejected(() -> insertFunding(tooLong), "fundings_external_reference_length");
    }

    @Test
    void aFundingIdempotencyKeyIsUsedOncePerClientAndWellFormed() {
        assertRejected(
                () -> {
                    Map<String, Object> first = funding();
                    insertFunding(first);
                    Map<String, Object> retry = funding();
                    retry.put("idempotencyKey", first.get("idempotencyKey"));
                    insertFunding(retry);
                },
                "fundings_client_idempotency_key_key");

        Map<String, Object> malformed = funding();
        malformed.put("idempotencyKey", "has space");
        assertRejected(() -> insertFunding(malformed), "fundings_idempotency_key_format");
    }

    @Test
    void eachLedgerTransactionBacksAtMostOneFunding() {
        assertRejected(
                () -> {
                    Map<String, Object> first = funding();
                    insertFunding(first);
                    Map<String, Object> second = funding();
                    second.put("ledgerTransactionId", first.get("ledgerTransactionId"));
                    insertFunding(second);
                },
                "fundings_ledger_transaction_id_key");
    }

    @Test
    void fundingsCannotBeUpdatedDeletedOrTruncated() {
        assertRejected(
                () -> {
                    Map<String, Object> row = funding();
                    insertFunding(row);
                    jdbc.sql("UPDATE fundings SET amount = amount + 1 WHERE idempotency_key = :key")
                            .param("key", row.get("idempotencyKey"))
                            .update();
                },
                "money movements are append-only");
        assertRejected(
                () -> {
                    Map<String, Object> row = funding();
                    insertFunding(row);
                    jdbc.sql("DELETE FROM fundings WHERE idempotency_key = :key")
                            .param("key", row.get("idempotencyKey"))
                            .update();
                },
                "money movements are append-only");
        assertRejected(() -> jdbc.sql("TRUNCATE fundings").update(), "money movements are append-only");
    }

    // --- helpers ---

    /** A transfer row that every guard accepts: Alice moves 1.00 USD between her two USD accounts. */
    private Map<String, Object> transfer() {
        Map<String, Object> row = new HashMap<>();
        row.put("clientId", alice.value());
        row.put("idempotencyKey", "key-" + UUID.randomUUID());
        row.put("source", aliceUsd.value());
        row.put("destination", aliceSecondUsd.value());
        row.put("currency", "USD");
        row.put("amount", 100L);
        row.put("description", "rent");
        return row;
    }

    /** A funding row that every guard accepts: 1.00 USD into Alice's USD account. */
    private Map<String, Object> funding() {
        Map<String, Object> row = new HashMap<>();
        row.put("clientId", alice.value());
        row.put("idempotencyKey", "key-" + UUID.randomUUID());
        row.put("account", aliceUsd.value());
        row.put("currency", "USD");
        row.put("amount", 100L);
        row.put("externalReference", "BANK-REF-1");
        return row;
    }

    private void insertTransfer(Map<String, Object> row) {
        withLedgerTransactionAndRequest(row);
        jdbc.sql("""
                        INSERT INTO transfers (client_id, idempotency_key, source_account_id, destination_account_id,
                                               currency, amount, description, ledger_transaction_id, request_id)
                        VALUES (:clientId, :idempotencyKey, :source, :destination,
                                :currency, :amount, :description, :ledgerTransactionId, :requestId)
                        """).params(row).update();
    }

    private void insertFunding(Map<String, Object> row) {
        withLedgerTransactionAndRequest(row);
        jdbc.sql("""
                        INSERT INTO fundings (client_id, idempotency_key, account_id, currency, amount,
                                              external_reference, ledger_transaction_id, request_id)
                        VALUES (:clientId, :idempotencyKey, :account, :currency, :amount,
                                :externalReference, :ledgerTransactionId, :requestId)
                        """).params(row).update();
    }

    /** Gives the row a ledger transaction (created in the current, rolled-back transaction) and a request id. */
    private void withLedgerTransactionAndRequest(Map<String, Object> row) {
        row.computeIfAbsent(
                "ledgerTransactionId",
                key -> jdbc.sql(
                                "INSERT INTO ledger_transactions (type, effective_date) VALUES ('TRANSFER', current_date) RETURNING id")
                        .query(UUID.class)
                        .single());
        row.putIfAbsent("requestId", UUID.randomUUID());
    }

    private void assertAccepted(Runnable work) {
        assertThatCode(() -> rollbackOnly(work)).doesNotThrowAnyException();
    }

    /** The work fails, and the database's error names the expected constraint or reason. */
    private void assertRejected(Runnable work, String expected) {
        assertThatThrownBy(() -> rollbackOnly(work)).rootCause().hasMessageContaining(expected);
    }

    /** Runs the work, then rolls back whatever happened. */
    private void rollbackOnly(Runnable work) {
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            work.run();
        });
    }
}
