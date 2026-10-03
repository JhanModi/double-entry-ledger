package io.github.jhanmodi.ledger.transfers;

import static io.github.jhanmodi.ledger.ledger.NewEntry.credit;
import static io.github.jhanmodi.ledger.ledger.NewEntry.debit;

import io.github.jhanmodi.ledger.audit.AuditAction;
import io.github.jhanmodi.ledger.audit.AuditLog;
import io.github.jhanmodi.ledger.clients.Caller;
import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.clients.Scope;
import io.github.jhanmodi.ledger.idempotency.Claim;
import io.github.jhanmodi.ledger.idempotency.IdempotencyKey;
import io.github.jhanmodi.ledger.idempotency.IdempotencyKeys;
import io.github.jhanmodi.ledger.idempotency.IdempotentOperation;
import io.github.jhanmodi.ledger.idempotency.IdempotentResult;
import io.github.jhanmodi.ledger.ledger.Account;
import io.github.jhanmodi.ledger.ledger.AccountId;
import io.github.jhanmodi.ledger.ledger.AccountNotFoundException;
import io.github.jhanmodi.ledger.ledger.LedgerQueries;
import io.github.jhanmodi.ledger.ledger.LedgerTransactionId;
import io.github.jhanmodi.ledger.ledger.LedgerTransactionType;
import io.github.jhanmodi.ledger.ledger.PostingRequest;
import io.github.jhanmodi.ledger.ledger.PostingService;
import io.github.jhanmodi.ledger.ledger.RetryingTransactions;
import io.github.jhanmodi.ledger.money.CurrencyCode;
import io.github.jhanmodi.ledger.money.Money;
import io.github.jhanmodi.ledger.transfers.TransferAccountNotFoundException.Side;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Moves money between two accounts of the same client (ADR-0018). A transfer is one database transaction: the
 * idempotency key's claim, the ledger posting, the transfer row, and the audit row are all committed, or none are.
 *
 * <p>The key is claimed first (ADR-0023). A retry of the same request gets the original transfer back, and nothing
 * moves again; the same key with a different request is refused.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private static final String IDEMPOTENCY_KEY_CONSTRAINT = "transfers_client_idempotency_key_key";

    private static final String COLUMNS =
            "id, source_account_id, destination_account_id, currency, amount, description, idempotency_key,"
                    + " ledger_transaction_id, created_at";

    private static final RowMapper<Transfer> TRANSFER = (rs, rowNum) -> new Transfer(
            new TransferId(rs.getObject("id", UUID.class)),
            new AccountId(rs.getObject("source_account_id", UUID.class)),
            new AccountId(rs.getObject("destination_account_id", UUID.class)),
            Money.of(rs.getLong("amount"), CurrencyCode.valueOf(rs.getString("currency"))),
            rs.getString("description"),
            new IdempotencyKey(rs.getString("idempotency_key")),
            new LedgerTransactionId(rs.getObject("ledger_transaction_id", UUID.class)),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;
    private final IdempotencyKeys idempotencyKeys;
    private final LedgerQueries ledger;
    private final PostingService postings;
    private final AuditLog audit;
    private final RetryingTransactions transactions;

    public TransferService(
            JdbcClient jdbc,
            IdempotencyKeys idempotencyKeys,
            LedgerQueries ledger,
            PostingService postings,
            AuditLog audit,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.idempotencyKeys = idempotencyKeys;
        this.ledger = ledger;
        this.postings = postings;
        this.audit = audit;
        this.transactions = new RetryingTransactions(transactionManager);
    }

    /**
     * Moves the money, or replays the transfer an earlier request with the same key and body made, or throws and moves
     * nothing.
     *
     * <p>The transaction is started here rather than with {@code @Transactional}, for two reasons:
     *
     * <ul>
     *   <li>If Postgres aborts it to break a deadlock, {@link RetryingTransactions} runs the whole transfer again
     *       (ADR-0022), claim included.
     *   <li>One case needs work <em>after</em> it has rolled back: if a duplicate ever got past the claim (see
     *       {@link #transferOnce}), it fails on the transfer row's unique key, and only once this transaction is gone can
     *       the original be looked up.
     * </ul>
     *
     * @throws io.github.jhanmodi.ledger.idempotency.IdempotencyKeyReusedException the key was used for a different
     *     request
     * @throws io.github.jhanmodi.ledger.idempotency.RequestInProgressException another request with the key was still
     *     running after the lock timeout
     * @throws DuplicateRequestException the key's claim has expired, but a transfer with the key exists
     * @throws TransferAccountNotFoundException either account isn't one of the caller's
     * @throws io.github.jhanmodi.ledger.ledger.AccountBusyException another request held an account too long
     */
    public IdempotentResult<Transfer> transfer(TransferCommand command, Caller caller) {
        caller.requireScope(Scope.WRITE);
        try {
            return transactions.execute(status -> transferOnce(command, caller));
        } catch (DuplicateKeyException e) {
            if (!violates(e, IDEMPOTENCY_KEY_CONSTRAINT)) {
                throw e;
            }
            // The permanent backstop: a request with the same key committed while this one was running. This
            // transaction has rolled back, nothing it did remains, and the winner's row is now visible.
            UUID original = findByKey(caller.clientId(), command.idempotencyKey())
                    .map(transfer -> transfer.id().value())
                    .orElseThrow(() -> new IllegalStateException("unique key violated, but no original found", e));
            throw new DuplicateRequestException(original);
        }
    }

    /** The client's own transfer. Another client's, or a missing one, gives the same {@link TransferNotFoundException}. */
    public Transfer find(ClientId client, TransferId id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transfers WHERE id = :id AND client_id = :clientId")
                .param("id", id.value())
                .param("clientId", client.value())
                .query(TRANSFER)
                .optional()
                .orElseThrow(() -> new TransferNotFoundException(id));
    }

    private IdempotentResult<Transfer> transferOnce(TransferCommand command, Caller caller) {
        // First, before anything else: a retry is recognised even if something has changed since (e.g. the money has
        // been spent), and a duplicate arriving at the same time waits here, before it touches any account.
        Claim claim = idempotencyKeys.claim(
                caller.clientId(), command.idempotencyKey(), IdempotentOperation.TRANSFER, command.fingerprint());
        if (claim == Claim.REPEAT) {
            Transfer original = findByKey(caller.clientId(), command.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("a committed transfer claim has no transfer"));
            log.info("Replayed transfer {} for a repeated Idempotency-Key", original.id());
            return IdempotentResult.replay(original);
        }
        // A new claim, yet a transfer may already have this key: one whose claim expired and was deleted (ADR-0023).
        // The transfer row keeps its key for good, so such a late retry is refused before any money moves.
        Optional<Transfer> expired = findByKey(caller.clientId(), command.idempotencyKey());
        if (expired.isPresent()) {
            throw new DuplicateRequestException(expired.get().id().value());
        }

        Account source = owned(caller.clientId(), command.source(), Side.SOURCE);
        Account destination = owned(caller.clientId(), command.destination(), Side.DESTINATION);
        requireCurrency(source, command.amount());
        requireCurrency(destination, command.amount());

        // Customer accounts are liabilities: the debit lowers the source's balance and the credit raises the
        // destination's. The posting service rejects a closed account or a balance that would go below zero.
        LedgerTransactionId posted = postings.post(new PostingRequest(
                LedgerTransactionType.TRANSFER,
                command.description(),
                List.of(debit(source.id(), command.amount()), credit(destination.id(), command.amount()))));
        Transfer transfer = insert(command, caller, posted);
        audit.record(AuditAction.TRANSFER_CREATED, transfer.id().toString(), caller.auditActor());
        return IdempotentResult.firstTime(transfer);
    }

    private Account owned(ClientId client, AccountId id, Side side) {
        try {
            return ledger.accountOwnedBy(client, id);
        } catch (AccountNotFoundException e) {
            throw new TransferAccountNotFoundException(side, id);
        }
    }

    private static void requireCurrency(Account account, Money amount) {
        if (account.currency() != amount.currency()) {
            throw new WrongCurrencyException(account.currency(), amount.currency());
        }
    }

    private Transfer insert(TransferCommand command, Caller caller, LedgerTransactionId posted) {
        return jdbc.sql("""
                        INSERT INTO transfers (client_id, idempotency_key, source_account_id, destination_account_id,
                                               currency, amount, description, ledger_transaction_id, request_id)
                        VALUES (:clientId, :idempotencyKey, :source, :destination,
                                :currency, :amount, :description, :ledgerTransactionId, :requestId)
                        """ + "RETURNING " + COLUMNS)
                .param("clientId", caller.clientId().value())
                .param("idempotencyKey", command.idempotencyKey().value())
                .param("source", command.source().value())
                .param("destination", command.destination().value())
                .param("currency", command.amount().currency().name())
                .param("amount", command.amount().minorUnits())
                .param("description", command.description(), Types.VARCHAR)
                .param("ledgerTransactionId", posted.value())
                .param("requestId", caller.requestId().value())
                .query(TRANSFER)
                .single();
    }

    private Optional<Transfer> findByKey(ClientId client, IdempotencyKey key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transfers WHERE client_id = :clientId AND idempotency_key = :key")
                .param("clientId", client.value())
                .param("key", key.value())
                .query(TRANSFER)
                .optional();
    }

    /** Whether the unique violation is this constraint's, rather than some other one's. */
    static boolean violates(DuplicateKeyException e, String constraint) {
        return e.getMostSpecificCause() instanceof SQLException sql
                && String.valueOf(sql.getMessage()).contains(constraint);
    }
}
