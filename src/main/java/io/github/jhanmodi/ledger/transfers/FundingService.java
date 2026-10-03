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
import io.github.jhanmodi.ledger.ledger.AccountPurpose;
import io.github.jhanmodi.ledger.ledger.LedgerQueries;
import io.github.jhanmodi.ledger.ledger.LedgerTransactionId;
import io.github.jhanmodi.ledger.ledger.LedgerTransactionType;
import io.github.jhanmodi.ledger.ledger.PostingRequest;
import io.github.jhanmodi.ledger.ledger.PostingService;
import io.github.jhanmodi.ledger.ledger.RetryingTransactions;
import io.github.jhanmodi.ledger.money.CurrencyCode;
import io.github.jhanmodi.ledger.money.Money;
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
 * Funds one of the caller's own accounts from the bank-settlement account for its currency (ADR-0018). It stands in for
 * an inbound bank deposit until payments arrive in M9, and needs the {@code admin} scope, because it creates money the
 * platform owes its customer. One database transaction, like a transfer, with its idempotency key claimed first in the
 * same way (ADR-0023).
 */
@Service
public class FundingService {

    private static final Logger log = LoggerFactory.getLogger(FundingService.class);

    private static final String IDEMPOTENCY_KEY_CONSTRAINT = "fundings_client_idempotency_key_key";

    private static final String COLUMNS =
            "id, account_id, currency, amount, external_reference, idempotency_key, ledger_transaction_id, created_at";

    private static final RowMapper<Funding> FUNDING = (rs, rowNum) -> new Funding(
            new FundingId(rs.getObject("id", UUID.class)),
            new AccountId(rs.getObject("account_id", UUID.class)),
            Money.of(rs.getLong("amount"), CurrencyCode.valueOf(rs.getString("currency"))),
            rs.getString("external_reference"),
            new IdempotencyKey(rs.getString("idempotency_key")),
            new LedgerTransactionId(rs.getObject("ledger_transaction_id", UUID.class)),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;
    private final IdempotencyKeys idempotencyKeys;
    private final LedgerQueries ledger;
    private final PostingService postings;
    private final AuditLog audit;
    private final RetryingTransactions transactions;

    public FundingService(
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
     * Credits the account, or replays the funding an earlier request with the same key and body made, or throws and
     * moves nothing. The transaction is started here for the same reasons as {@link TransferService#transfer}: a
     * deadlock is retried, and a duplicate that got past the claim can only be identified after rolling back.
     *
     * @throws io.github.jhanmodi.ledger.idempotency.IdempotencyKeyReusedException the key was used for a different
     *     request
     * @throws io.github.jhanmodi.ledger.idempotency.RequestInProgressException another request with the key was still
     *     running after the lock timeout
     * @throws DuplicateRequestException the key's claim has expired, but a funding with the key exists
     * @throws io.github.jhanmodi.ledger.ledger.AccountNotFoundException the account isn't one of the caller's
     * @throws io.github.jhanmodi.ledger.ledger.AccountBusyException another request held the account too long
     */
    public IdempotentResult<Funding> fund(FundingCommand command, Caller caller) {
        caller.requireScope(Scope.ADMIN);
        try {
            return transactions.execute(status -> fundOnce(command, caller));
        } catch (DuplicateKeyException e) {
            if (!TransferService.violates(e, IDEMPOTENCY_KEY_CONSTRAINT)) {
                throw e;
            }
            UUID original = findByKey(caller.clientId(), command.idempotencyKey())
                    .map(funding -> funding.id().value())
                    .orElseThrow(() -> new IllegalStateException("unique key violated, but no original found", e));
            throw new DuplicateRequestException(original);
        }
    }

    private IdempotentResult<Funding> fundOnce(FundingCommand command, Caller caller) {
        // First, as for a transfer (see TransferService).
        Claim claim = idempotencyKeys.claim(
                caller.clientId(), command.idempotencyKey(), IdempotentOperation.FUNDING, command.fingerprint());
        if (claim == Claim.REPEAT) {
            Funding original = findByKey(caller.clientId(), command.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("a committed funding claim has no funding"));
            log.info("Replayed funding {} for a repeated Idempotency-Key", original.id());
            return IdempotentResult.replay(original);
        }
        // A funding whose claim expired and was deleted still keeps its key (ADR-0023).
        Optional<Funding> expired = findByKey(caller.clientId(), command.idempotencyKey());
        if (expired.isPresent()) {
            throw new DuplicateRequestException(expired.get().id().value());
        }

        // Only ever the caller's own account (D1-A): another client's, a system account, or a missing one are all 404.
        Account account = ledger.accountOwnedBy(caller.clientId(), command.account());
        if (account.currency() != command.amount().currency()) {
            throw new WrongCurrencyException(
                    account.currency(), command.amount().currency());
        }
        Account bank = ledger.systemAccount(AccountPurpose.BANK_SETTLEMENT, account.currency());

        // The bank holds more cash (an asset: the debit raises it), and the platform owes the customer more (a
        // liability: the credit raises it).
        LedgerTransactionId posted = postings.post(new PostingRequest(
                LedgerTransactionType.FUNDING,
                "Deposit " + command.externalReference(),
                List.of(debit(bank.id(), command.amount()), credit(account.id(), command.amount()))));
        Funding funding = insert(command, caller, posted);
        audit.record(AuditAction.FUNDING_CREATED, funding.id().toString(), caller.auditActor());
        return IdempotentResult.firstTime(funding);
    }

    private Funding insert(FundingCommand command, Caller caller, LedgerTransactionId posted) {
        return jdbc.sql("""
                        INSERT INTO fundings (client_id, idempotency_key, account_id, currency, amount,
                                              external_reference, ledger_transaction_id, request_id)
                        VALUES (:clientId, :idempotencyKey, :account, :currency, :amount,
                                :externalReference, :ledgerTransactionId, :requestId)
                        """ + "RETURNING " + COLUMNS)
                .param("clientId", caller.clientId().value())
                .param("idempotencyKey", command.idempotencyKey().value())
                .param("account", command.account().value())
                .param("currency", command.amount().currency().name())
                .param("amount", command.amount().minorUnits())
                .param("externalReference", command.externalReference())
                .param("ledgerTransactionId", posted.value())
                .param("requestId", caller.requestId().value())
                .query(FUNDING)
                .single();
    }

    private Optional<Funding> findByKey(ClientId client, IdempotencyKey key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM fundings WHERE client_id = :clientId AND idempotency_key = :key")
                .param("clientId", client.value())
                .param("key", key.value())
                .query(FUNDING)
                .optional();
    }
}
