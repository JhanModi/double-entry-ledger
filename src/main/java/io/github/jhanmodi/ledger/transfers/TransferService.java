package io.github.jhanmodi.ledger.transfers;

import static io.github.jhanmodi.ledger.ledger.NewEntry.credit;
import static io.github.jhanmodi.ledger.ledger.NewEntry.debit;

import io.github.jhanmodi.ledger.audit.AuditAction;
import io.github.jhanmodi.ledger.audit.AuditLog;
import io.github.jhanmodi.ledger.clients.Caller;
import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.clients.Scope;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Moves money between two accounts of the same client (ADR-0018). A transfer is one database transaction: the ledger
 * posting, the transfer row, and the audit row are all committed, or none are.
 *
 * <p>Retries are guarded by the client's idempotency key (ADR-0019). Until M6, a duplicate is rejected with the
 * original's id rather than replayed.
 */
@Service
public class TransferService {

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
    private final LedgerQueries ledger;
    private final PostingService postings;
    private final AuditLog audit;
    private final RetryingTransactions transactions;

    public TransferService(
            JdbcClient jdbc,
            LedgerQueries ledger,
            PostingService postings,
            AuditLog audit,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.postings = postings;
        this.audit = audit;
        this.transactions = new RetryingTransactions(transactionManager);
    }

    /**
     * Moves the money, or throws and moves nothing.
     *
     * <p>The transaction is started here rather than with {@code @Transactional}, for two reasons:
     *
     * <ul>
     *   <li>If Postgres aborts it to break a deadlock, {@link RetryingTransactions} runs the whole transfer again
     *       (ADR-0022).
     *   <li>One case needs work <em>after</em> it has rolled back: when another request with the same key wins the race,
     *       this one fails on the unique key, and only once this transaction is gone can the winner be looked up.
     * </ul>
     *
     * @throws DuplicateRequestException this client already used the key
     * @throws TransferAccountNotFoundException either account isn't one of the caller's
     * @throws io.github.jhanmodi.ledger.ledger.AccountBusyException another request held an account too long
     */
    public Transfer transfer(TransferCommand command, Caller caller) {
        caller.requireScope(Scope.WRITE);
        try {
            return transactions.execute(status -> transferOnce(command, caller));
        } catch (DuplicateKeyException e) {
            if (!violates(e, IDEMPOTENCY_KEY_CONSTRAINT)) {
                throw e;
            }
            // A request with the same key committed while this one was running. This transaction has rolled back,
            // nothing it did remains, and the winner's row is now visible.
            UUID original = findIdByKey(caller.clientId(), command.idempotencyKey())
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

    private Transfer transferOnce(TransferCommand command, Caller caller) {
        // First, so a retry is recognised even if something else has changed since (e.g. the money has been spent).
        Optional<UUID> original = findIdByKey(caller.clientId(), command.idempotencyKey());
        if (original.isPresent()) {
            throw new DuplicateRequestException(original.get());
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
        return transfer;
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

    private Optional<UUID> findIdByKey(ClientId client, IdempotencyKey key) {
        return jdbc.sql("SELECT id FROM transfers WHERE client_id = :clientId AND idempotency_key = :key")
                .param("clientId", client.value())
                .param("key", key.value())
                .query(UUID.class)
                .optional();
    }

    /** Whether the unique violation is this constraint's, rather than some other one's. */
    static boolean violates(DuplicateKeyException e, String constraint) {
        return e.getMostSpecificCause() instanceof SQLException sql
                && String.valueOf(sql.getMessage()).contains(constraint);
    }
}
