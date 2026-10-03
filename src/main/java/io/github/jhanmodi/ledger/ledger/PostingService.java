package io.github.jhanmodi.ledger.ledger;

import io.github.jhanmodi.ledger.money.CurrencyMismatchException;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts ledger transactions. The only code that writes entries or balances (ADR-0001). A posting is one database
 * transaction: either the transaction row, all its entries, and all balance changes are committed, or none are.
 *
 * <p>Safe under concurrency by locking first (ADR-0005): the customer accounts are locked in ascending id order, their
 * status and balances are checked under the lock, and only then is anything written. Two postings on the same account
 * take turns, and no lock wait lasts longer than the lock timeout (ADR-0022).
 */
@Service
public class PostingService {

    private final JdbcClient jdbc;
    private final AccountRepository accounts;
    private final Clock clock;
    private final Duration lockTimeout;

    public PostingService(
            JdbcClient jdbc,
            AccountRepository accounts,
            Clock clock,
            @Value("${ledger.lock-timeout}") Duration lockTimeout) {
        // Zero would mean "wait forever" to Postgres, which is exactly what the timeout exists to prevent.
        if (lockTimeout.toMillis() <= 0) {
            throw new IllegalArgumentException("ledger.lock-timeout must be at least 1ms, got " + lockTimeout);
        }
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.clock = clock;
        this.lockTimeout = lockTimeout;
    }

    /**
     * Posts the transaction, or throws and writes nothing.
     *
     * <ol>
     *   <li>Limit lock waits, for the rest of this transaction.
     *   <li>Read the accounts: each must exist and be in its entry's currency. Those facts never change, so they need no
     *       lock.
     *   <li>Lock the customer accounts in ascending id order, re-reading their status and balances under the lock.
     *   <li>Check them: every account open, none below zero.
     *   <li>Write the ledger transaction, its entries, and the balance changes.
     * </ol>
     *
     * @throws AccountNotFoundException an entry names an account that doesn't exist
     * @throws AccountClosedException an account is closed
     * @throws InsufficientFundsException a customer account's available balance would go below zero
     * @throws AccountBusyException another transaction held an account for longer than the lock timeout
     */
    @Transactional
    public LedgerTransactionId post(PostingRequest request) {
        limitLockWaits();
        Map<AccountId, Account> involved = loadAccounts(request);
        BalanceChanges changes = BalanceChanges.of(request.entries(), involved);
        changes.requirePostable(lock(changes.accountsToLock()));

        UUID transactionId = insertTransaction(request);
        for (NewEntry entry : request.entries()) {
            insertEntry(transactionId, entry);
        }
        applyBalanceChanges(changes);
        return new LedgerTransactionId(transactionId);
    }

    /**
     * Sets Postgres's {@code lock_timeout}: the longest one statement may wait for a lock before failing with SQLSTATE
     * 55P03. {@code set_config(…, true)} is {@code SET LOCAL} with a bind parameter: it lasts until this transaction
     * ends, so it never carries over to the next user of the pooled connection. It also bounds lock waits the caller
     * makes after posting, such as a transfer's insert waiting on another request with the same idempotency key.
     */
    private void limitLockWaits() {
        jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
                .param("timeout", lockTimeout.toMillis() + "ms")
                .query(String.class)
                .single();
    }

    /**
     * The accounts the entries name. What's checked here is fixed for an account's lifetime (a trigger rejects any
     * change), so it can't change before the posting is written. A customer account's status can change, so it's
     * checked later, under the lock. A system account is never locked, so its status is checked here.
     */
    private Map<AccountId, Account> loadAccounts(PostingRequest request) {
        List<AccountId> ids =
                request.entries().stream().map(NewEntry::accountId).distinct().toList();
        Map<AccountId, Account> found =
                accounts.findAll(ids).stream().collect(Collectors.toMap(Account::id, Function.identity()));
        for (NewEntry entry : request.entries()) {
            Account account = found.get(entry.accountId());
            if (account == null) {
                throw new AccountNotFoundException(entry.accountId());
            }
            if (account.currency() != entry.amount().currency()) {
                throw new CurrencyMismatchException(
                        account.currency(), entry.amount().currency());
            }
            if (account.kind() == AccountKind.SYSTEM && account.status() != AccountStatus.OPEN) {
                throw new AccountClosedException(account.id());
            }
        }
        return found;
    }

    private List<LockedAccount> lock(Collection<AccountId> customerAccounts) {
        try {
            return accounts.lockForPosting(customerAccounts);
        } catch (DataAccessException e) {
            if (SqlState.is(e, SqlState.LOCK_NOT_AVAILABLE)) {
                throw AccountBusyException.lockTimedOut(e);
            }
            throw e;
        }
    }

    private UUID insertTransaction(PostingRequest request) {
        return jdbc.sql("""
                        INSERT INTO ledger_transactions (type, description, effective_date)
                        VALUES (:type, :description, :effectiveDate)
                        RETURNING id
                        """)
                .param("type", request.type().name())
                .param("description", request.description(), Types.VARCHAR)
                // The business date in UTC, from the injected clock: never the server's local time zone.
                .param("effectiveDate", LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))
                .query(UUID.class)
                .single();
    }

    private void insertEntry(UUID transactionId, NewEntry entry) {
        jdbc.sql("""
                        INSERT INTO entries (transaction_id, account_id, currency, direction, amount)
                        VALUES (:transactionId, :accountId, :currency, :direction, :amount)
                        """)
                .param("transactionId", transactionId)
                .param("accountId", entry.accountId().value())
                .param("currency", entry.amount().currency().name())
                .param("direction", entry.direction().name())
                .param("amount", entry.amount().minorUnits())
                .update();
    }

    /**
     * Applies each customer account's net change as one SQL delta ({@code posted_balance + :delta}), never as a balance
     * computed in Java and written back (ADR-0004). System accounts have no cached balance to update.
     *
     * <p>The {@code CHECK} on available balances is still the backstop. Its error isn't translated: the check under the
     * lock has already passed, so a violation here would mean that check, or the locking, is wrong. That's a bug on our
     * side and should surface as one (a 500, logged), not as a routine "insufficient funds".
     */
    private void applyBalanceChanges(BalanceChanges changes) {
        changes.deltas().forEach((accountId, delta) -> {
            if (delta != 0) {
                jdbc.sql("UPDATE accounts SET posted_balance = posted_balance + :delta WHERE id = :id")
                        .param("delta", delta)
                        .param("id", accountId.value())
                        .update();
            }
        });
    }
}
