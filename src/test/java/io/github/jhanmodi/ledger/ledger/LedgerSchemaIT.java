package io.github.jhanmodi.ledger.ledger;

import static io.github.jhanmodi.ledger.money.CurrencyCode.EUR;
import static io.github.jhanmodi.ledger.money.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jhanmodi.ledger.OwnerDatabase;
import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import io.github.jhanmodi.ledger.clients.ClientService;
import io.github.jhanmodi.ledger.money.CurrencyCode;
import io.github.jhanmodi.ledger.money.Money;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves each database guard in V2__create_ledger.sql works on its own, by going around the Java code with raw SQL.
 *
 * <p>The raw SQL runs as the database <em>owner</em>, the most privileged role there is, to show that the constraints
 * and triggers catch mistakes made by any role: a bug, a hand-written fix, a wrong migration. They aren't a defense
 * against a malicious owner, who could disable triggers or drop constraints. That's why the owner is used for
 * migrations only, and the application connects as a restricted login instead (ADR-0015, AppRolePrivilegesIT).
 *
 * <p>Raw SQL here only ever commits balanced transactions on system accounts. Anything else would leave the shared
 * test database in a state the invariant checker would rightly complain about. Tests that expect a failure run
 * rollback-only, so even a missing guard can't damage the shared data.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerSchemaIT {

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

    @BeforeEach
    void setUp() {
        jdbc = owner.jdbc();
        tx = owner.transactions();
        ledger = new LedgerFixtures(accountService, postingService, clientService);
    }

    // --- Ledger transactions must balance (deferred constraint triggers, checked at COMMIT) ---

    @Test
    void aTransactionWhoseDebitsAndCreditsDifferIsRejectedAtCommit() {
        AccountId bankA = ledger.bank(USD);
        AccountId bankB = ledger.bank(USD);

        assertThatThrownBy(() -> commit(() -> {
                    UUID txn = insertTransaction();
                    insertEntry(txn, bankA, USD, "DEBIT", 100);
                    insertEntry(txn, bankB, USD, "CREDIT", 90);
                }))
                .rootCause()
                .hasMessageContaining("is not balanced in USD");
    }

    @Test
    void aTransactionThatOnlyBalancesAcrossCurrenciesIsRejectedAtCommit() {
        AccountId usdBank = ledger.bank(USD);
        AccountId eurBank = ledger.bank(EUR);

        assertThatThrownBy(() -> commit(() -> {
                    UUID txn = insertTransaction();
                    insertEntry(txn, usdBank, USD, "DEBIT", 100);
                    insertEntry(txn, eurBank, EUR, "CREDIT", 100);
                }))
                .rootCause()
                .hasMessageContaining("is not balanced in");
    }

    @Test
    void aTransactionWithNoEntriesIsRejectedAtCommit() {
        assertThatThrownBy(() -> commit(this::insertTransaction))
                .rootCause()
                .hasMessageContaining("has 0 entries; at least 2 are required");
    }

    @Test
    void aTransactionWithOneEntryIsRejectedAtCommit() {
        AccountId bank = ledger.bank(USD);

        assertThatThrownBy(() -> commit(() -> insertEntry(insertTransaction(), bank, USD, "DEBIT", 100)))
                .rootCause()
                .hasMessageContaining("has 1 entries; at least 2 are required");
    }

    @Test
    void aBalancedTransactionWrittenByHandCommits() {
        AccountId bankA = ledger.bank(USD);
        AccountId bankB = ledger.bank(USD);

        UUID txn = tx.execute(status -> {
            UUID id = insertTransaction();
            insertEntry(id, bankA, USD, "DEBIT", 100);
            insertEntry(id, bankB, USD, "CREDIT", 60);
            insertEntry(id, bankB, USD, "CREDIT", 40);
            return id;
        });

        assertThat(jdbc.sql("SELECT count(*) FROM entries WHERE transaction_id = :id")
                        .param("id", txn)
                        .query(Long.class)
                        .single())
                .isEqualTo(3);
    }

    // --- Entries ---

    @Test
    void entryAmountsMustBePositive() {
        AccountId bank = ledger.bank(USD);

        assertThatThrownBy(() -> rollbackOnly(() -> insertEntry(insertTransaction(), bank, USD, "DEBIT", 0)))
                .rootCause()
                .hasMessageContaining("entries_amount_check");
        assertThatThrownBy(() -> rollbackOnly(() -> insertEntry(insertTransaction(), bank, USD, "DEBIT", -5)))
                .rootCause()
                .hasMessageContaining("entries_amount_check");
    }

    @Test
    void anEntryMustBeInItsAccountsCurrency() {
        AccountId usdBank = ledger.bank(USD);

        assertThatThrownBy(() -> rollbackOnly(() -> insertEntry(insertTransaction(), usdBank, EUR, "DEBIT", 100)))
                .rootCause()
                .hasMessageContaining("entries_account_currency_fkey");
    }

    // --- Append-only ---

    @Test
    void entriesCannotBeUpdatedDeletedOrTruncated() {
        LedgerTransactionId txn = ledger.fund(ledger.bank(USD), ledger.customer(USD), Money.of(100, USD));
        UUID entry = jdbc.sql("SELECT id FROM entries WHERE transaction_id = :id LIMIT 1")
                .param("id", txn.value())
                .query(UUID.class)
                .single();

        assertAppendOnly("UPDATE entries SET amount = amount + 1 WHERE id = '" + entry + "'");
        assertAppendOnly("DELETE FROM entries WHERE id = '" + entry + "'");
        assertAppendOnly("TRUNCATE entries");
    }

    @Test
    void ledgerTransactionsCannotBeUpdatedDeletedOrTruncated() {
        LedgerTransactionId txn = ledger.fund(ledger.bank(USD), ledger.customer(USD), Money.of(100, USD));

        assertAppendOnly("UPDATE ledger_transactions SET description = 'edited' WHERE id = '" + txn + "'");
        assertAppendOnly("DELETE FROM ledger_transactions WHERE id = '" + txn + "'");
        assertAppendOnly("TRUNCATE ledger_transactions CASCADE");
    }

    // --- Accounts ---

    @Test
    void whatAnAccountIsNeverChanges() {
        AccountId customer = ledger.customer(USD);
        String anotherClient = clientService.createClient("another client").toString();

        for (String change : List.of(
                "currency = 'EUR'",
                "kind = 'SYSTEM'",
                "type = 'EQUITY'",
                "created_at = now() - interval '1 day'",
                "client_id = '" + anotherClient + "'",
                "client_id = NULL",
                "purpose = 'BANK_SETTLEMENT'")) {
            assertThatThrownBy(() -> rollbackOnly(() -> jdbc.sql("UPDATE accounts SET " + change + " WHERE id = :id")
                            .param("id", customer.value())
                            .update()))
                    .as(change)
                    .rootCause()
                    .hasMessageContaining("never change");
        }
    }

    @Test
    void accountsAreNeverDeletedOrTruncated() {
        AccountId customer = ledger.customer(USD);

        assertThatThrownBy(() -> rollbackOnly(() -> jdbc.sql("DELETE FROM accounts WHERE id = :id")
                        .param("id", customer.value())
                        .update()))
                .rootCause()
                .hasMessageContaining("accounts are closed, never deleted");
        assertThatThrownBy(() ->
                        rollbackOnly(() -> jdbc.sql("TRUNCATE accounts CASCADE").update()))
                .rootCause()
                .hasMessageContaining("accounts are closed, never deleted");
    }

    @Test
    void customerAccountsMustBeLiabilities() {
        UUID owner = ledger.client().value();

        assertThatThrownBy(() -> rollbackOnly(() -> insertAccount("CUSTOMER", "ASSET", "DEBIT", "USD", 0L, owner)))
                .rootCause()
                .hasMessageContaining("accounts_customer_is_liability");
    }

    @Test
    void customerAccountsBelongToAClientAndSystemAccountsToNone() {
        UUID someClient = ledger.client().value();

        assertThatThrownBy(() -> rollbackOnly(() -> insertAccount("CUSTOMER", "LIABILITY", "CREDIT", "USD", 0L, null)))
                .rootCause()
                .hasMessageContaining("accounts_customer_has_client");
        assertThatThrownBy(() -> rollbackOnly(() -> insertAccount("SYSTEM", "ASSET", "DEBIT", "USD", null, someClient)))
                .rootCause()
                .hasMessageContaining("accounts_customer_has_client");
    }

    @Test
    void onlyCustomerAccountsHaveCachedBalances() {
        UUID owner = ledger.client().value();

        assertThatThrownBy(() -> rollbackOnly(() -> insertAccount("SYSTEM", "ASSET", "DEBIT", "USD", 0L, null)))
                .rootCause()
                .hasMessageContaining("accounts_only_customers_cache_balances");
        assertThatThrownBy(
                        () -> rollbackOnly(() -> insertAccount("CUSTOMER", "LIABILITY", "CREDIT", "USD", null, owner)))
                .rootCause()
                .hasMessageContaining("accounts_only_customers_cache_balances");
    }

    @Test
    void theNormalSideMustMatchTheType() {
        assertThatThrownBy(() -> rollbackOnly(() -> insertAccount("SYSTEM", "ASSET", "CREDIT", "USD", null, null)))
                .rootCause()
                .hasMessageContaining("accounts_normal_side_matches_type");
    }

    @Test
    void theCurrencyMustBeOneTheLedgerSupports() {
        assertThatThrownBy(() -> rollbackOnly(() -> insertAccount("SYSTEM", "ASSET", "DEBIT", "GBP", null, null)))
                .rootCause()
                .hasMessageContaining("accounts_currency_fkey");
    }

    @Test
    void aCustomersAvailableBalanceCannotGoNegative() {
        AccountId customer = ledger.customer(USD);

        assertThatThrownBy(() -> rollbackOnly(() -> jdbc.sql("UPDATE accounts SET posted_balance = -1 WHERE id = :id")
                        .param("id", customer.value())
                        .update()))
                .rootCause()
                .hasMessageContaining("accounts_available_balance_non_negative");
    }

    // --- System accounts with a purpose (V5, ADR-0018) ---

    @Test
    void everyCurrencyHasExactlyOneBankSettlementAccount() {
        // A migration that adds a currency must add its settlement account too. This is what catches one that doesn't.
        List<String> settlementCurrencies = jdbc.sql("""
                        SELECT currency FROM accounts
                        WHERE purpose = 'BANK_SETTLEMENT' AND kind = 'SYSTEM' AND type = 'ASSET'
                        """).query(String.class).list();

        assertThat(settlementCurrencies)
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(CurrencyCode.values()).map(Enum::name).toList());
    }

    @Test
    void aCurrencyCannotHaveASecondAccountWithTheSamePurpose() {
        assertThatThrownBy(() -> rollbackOnly(() -> insertSystemAccount("ASSET", "DEBIT", "USD", "BANK_SETTLEMENT")))
                .rootCause()
                .hasMessageContaining("accounts_purpose_currency_key");
    }

    @Test
    void aBankSettlementAccountMustBeAnAsset() {
        assertThatThrownBy(
                        () -> rollbackOnly(() -> insertSystemAccount("LIABILITY", "CREDIT", "USD", "BANK_SETTLEMENT")))
                .rootCause()
                .hasMessageContaining("accounts_bank_settlement_is_asset");
    }

    @Test
    void anUnknownPurposeIsRejected() {
        assertThatThrownBy(() -> rollbackOnly(() -> insertSystemAccount("REVENUE", "CREDIT", "USD", "FEES")))
                .rootCause()
                .hasMessageContaining("accounts_purpose_known");
    }

    @Test
    void aCustomerAccountCannotHaveAPurpose() {
        UUID owner = ledger.client().value();

        // Two rules reject this today: only system accounts have a purpose, and a bank-settlement account must be an
        // asset while a customer account is a liability. Either may be the one reported. The system-only rule is the
        // one that will matter once a purpose exists that a liability could have.
        assertThatThrownBy(() -> rollbackOnly(
                        () -> jdbc.sql("""
                                INSERT INTO accounts
                                    (kind, type, normal_side, currency, posted_balance, held_balance, client_id, purpose)
                                VALUES ('CUSTOMER', 'LIABILITY', 'CREDIT', 'EUR', 0, 0, :clientId, 'BANK_SETTLEMENT')
                                """).param("clientId", owner).update()))
                .rootCause()
                .message()
                .containsAnyOf("accounts_purpose_only_on_system_accounts", "accounts_bank_settlement_is_asset");
    }

    // --- Java and Postgres must agree on id order (see AccountId#compareTo) ---

    @Test
    void javaOrdersAccountIdsTheWayPostgresDoes() {
        List<UUID> ids = new ArrayList<>(List.of(
                UUID.fromString("80000000-0000-0000-0000-000000000000"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
                UUID.fromString("00000000-0000-0000-8000-000000000000"),
                UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff")));
        for (int i = 0; i < 50; i++) {
            ids.add(UUID.randomUUID());
        }
        String asArray = ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));

        List<UUID> postgresOrder = jdbc.sql("SELECT id FROM unnest(CAST(:ids AS uuid[])) AS t(id) ORDER BY id")
                .param("ids", asArray)
                .query(UUID.class)
                .list();
        List<UUID> javaOrder =
                ids.stream().map(AccountId::new).sorted().map(AccountId::value).toList();

        assertThat(javaOrder).isEqualTo(postgresOrder);
    }

    // --- helpers ---

    private void assertAppendOnly(String sql) {
        assertThatThrownBy(() -> rollbackOnly(() -> jdbc.sql(sql).update()))
                .as(sql)
                .rootCause()
                .hasMessageContaining("ledger records are append-only");
    }

    /** Runs the work and commits, so deferred triggers fire. */
    private void commit(Runnable work) {
        tx.executeWithoutResult(status -> work.run());
    }

    /** Runs the work, then rolls back whatever happened. */
    private void rollbackOnly(Runnable work) {
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            work.run();
        });
    }

    private UUID insertTransaction() {
        return jdbc.sql(
                        "INSERT INTO ledger_transactions (type, effective_date) VALUES ('TRANSFER', current_date) RETURNING id")
                .query(UUID.class)
                .single();
    }

    private void insertEntry(
            UUID transactionId, AccountId account, CurrencyCode currency, String direction, long amount) {
        jdbc.sql("""
                        INSERT INTO entries (transaction_id, account_id, currency, direction, amount)
                        VALUES (:transactionId, :accountId, :currency, :direction, :amount)
                        """)
                .param("transactionId", transactionId)
                .param("accountId", account.value())
                .param("currency", currency.name())
                .param("direction", direction)
                .param("amount", amount)
                .update();
    }

    private void insertAccount(
            String kind, String type, String normalSide, String currency, Long balance, UUID clientId) {
        jdbc.sql("""
                        INSERT INTO accounts (kind, type, normal_side, currency, posted_balance, held_balance, client_id)
                        VALUES (:kind, :type, :normalSide, :currency, :balance, :balance, :clientId)
                        """)
                .param("kind", kind)
                .param("type", type)
                .param("normalSide", normalSide)
                .param("currency", currency)
                .param("balance", balance, java.sql.Types.BIGINT)
                .param("clientId", clientId, java.sql.Types.OTHER)
                .update();
    }

    private void insertSystemAccount(String type, String normalSide, String currency, String purpose) {
        jdbc.sql("""
                        INSERT INTO accounts (kind, type, normal_side, currency, purpose)
                        VALUES ('SYSTEM', :type, :normalSide, :currency, :purpose)
                        """)
                .param("type", type)
                .param("normalSide", normalSide)
                .param("currency", currency)
                .param("purpose", purpose)
                .update();
    }
}
