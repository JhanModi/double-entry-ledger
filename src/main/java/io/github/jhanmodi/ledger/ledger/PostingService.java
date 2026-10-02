package io.github.jhanmodi.ledger.ledger;

import io.github.jhanmodi.ledger.money.CurrencyMismatchException;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts ledger transactions. The only code that writes entries or balances (ADR-0001). A posting is one database
 * transaction: either the transaction row, all its entries, and all balance changes are committed, or none are.
 */
@Service
public class PostingService {

    private static final String CHECK_VIOLATION = "23514";
    private static final String AVAILABLE_BALANCE_CONSTRAINT = "accounts_available_balance_non_negative";

    private final JdbcClient jdbc;
    private final AccountRepository accounts;
    private final Clock clock;

    public PostingService(JdbcClient jdbc, AccountRepository accounts, Clock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional
    public LedgerTransactionId post(PostingRequest request) {
        Map<AccountId, Account> involved = loadAccountsThatCanBePosted(request);
        UUID transactionId = insertTransaction(request);
        for (NewEntry entry : request.entries()) {
            insertEntry(transactionId, entry);
        }
        applyBalanceChanges(involved, request.entries());
        return new LedgerTransactionId(transactionId);
    }

    private Map<AccountId, Account> loadAccountsThatCanBePosted(PostingRequest request) {
        List<AccountId> ids =
                request.entries().stream().map(NewEntry::accountId).distinct().toList();
        Map<AccountId, Account> found =
                accounts.findAll(ids).stream().collect(Collectors.toMap(Account::id, Function.identity()));
        for (NewEntry entry : request.entries()) {
            Account account = found.get(entry.accountId());
            if (account == null) {
                throw new AccountNotFoundException(entry.accountId());
            }
            if (account.status() != AccountStatus.OPEN) {
                throw new AccountClosedException(account.id());
            }
            if (account.currency() != entry.amount().currency()) {
                throw new CurrencyMismatchException(
                        account.currency(), entry.amount().currency());
            }
        }
        return found;
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
     * Applies each customer account's net change as one SQL delta, in ascending account id order.
     *
     * <p>A delta ({@code posted_balance + :delta}) can't lose a concurrent update the way writing back a balance
     * computed in Java can (ADR-0004). A fixed order means two postings touching the same accounts always lock them in
     * the same order, so they can't deadlock (ADR-0005). System accounts have no cached balance to update.
     */
    private void applyBalanceChanges(Map<AccountId, Account> involved, List<NewEntry> entries) {
        SortedMap<AccountId, Long> deltas = new TreeMap<>();
        for (NewEntry entry : entries) {
            Account account = involved.get(entry.accountId());
            if (account.kind() == AccountKind.CUSTOMER) {
                long amount = entry.amount().minorUnits();
                long change = entry.direction() == account.type().normalSide() ? amount : Math.negateExact(amount);
                deltas.merge(account.id(), change, Math::addExact);
            }
        }
        deltas.forEach((accountId, delta) -> {
            if (delta != 0) {
                updatePostedBalance(accountId, delta);
            }
        });
    }

    private void updatePostedBalance(AccountId accountId, long delta) {
        try {
            jdbc.sql("UPDATE accounts SET posted_balance = posted_balance + :delta WHERE id = :id")
                    .param("delta", delta)
                    .param("id", accountId.value())
                    .update();
        } catch (DataIntegrityViolationException e) {
            // The overdraft backstop fired (ADR-0005). The exception rolls back the whole posting, entries included.
            // From M5, a lock-then-check rejects this earlier, before any entries are written.
            if (violatesAvailableBalanceCheck(e)) {
                throw new InsufficientFundsException(accountId);
            }
            throw e;
        }
    }

    private static boolean violatesAvailableBalanceCheck(DataIntegrityViolationException e) {
        return e.getMostSpecificCause() instanceof SQLException sql
                && CHECK_VIOLATION.equals(sql.getSQLState())
                && String.valueOf(sql.getMessage()).contains(AVAILABLE_BALANCE_CONSTRAINT);
    }
}
