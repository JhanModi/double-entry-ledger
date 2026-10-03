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
import io.github.jhanmodi.ledger.ledger.AccountPurpose;
import io.github.jhanmodi.ledger.ledger.LedgerQueries;
import io.github.jhanmodi.ledger.ledger.LedgerTransactionId;
import io.github.jhanmodi.ledger.ledger.LedgerTransactionType;
import io.github.jhanmodi.ledger.ledger.PostingRequest;
import io.github.jhanmodi.ledger.ledger.PostingService;
import io.github.jhanmodi.ledger.money.CurrencyCode;
import io.github.jhanmodi.ledger.money.CurrencyMismatchException;
import io.github.jhanmodi.ledger.money.Money;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Funds one of the caller's own accounts from the bank-settlement account for its currency (ADR-0018). It stands in for
 * an inbound bank deposit until payments arrive in M9, and needs the {@code admin} scope, because it creates money the
 * platform owes its customer. One database transaction, like a transfer; retries are guarded the same way (ADR-0019).
 */
@Service
public class FundingService {

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
    private final LedgerQueries ledger;
    private final PostingService postings;
    private final AuditLog audit;
    private final TransactionTemplate transactions;

    public FundingService(
            JdbcClient jdbc,
            LedgerQueries ledger,
            PostingService postings,
            AuditLog audit,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.postings = postings;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Credits the account, or throws and moves nothing. Started with a {@link TransactionTemplate} for the same reason
     * as {@link TransferService#transfer}: a duplicate that loses the race can only be identified after rolling back.
     *
     * @throws DuplicateRequestException this client already used the key
     * @throws io.github.jhanmodi.ledger.ledger.AccountNotFoundException the account isn't one of the caller's
     */
    public Funding fund(FundingCommand command, Caller caller) {
        caller.requireScope(Scope.ADMIN);
        try {
            return transactions.execute(status -> fundOnce(command, caller));
        } catch (DuplicateKeyException e) {
            if (!TransferService.violates(e, IDEMPOTENCY_KEY_CONSTRAINT)) {
                throw e;
            }
            UUID original = findIdByKey(caller.clientId(), command.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("unique key violated, but no original found", e));
            throw new DuplicateRequestException(original);
        }
    }

    private Funding fundOnce(FundingCommand command, Caller caller) {
        Optional<UUID> original = findIdByKey(caller.clientId(), command.idempotencyKey());
        if (original.isPresent()) {
            throw new DuplicateRequestException(original.get());
        }
        // Only ever the caller's own account (D1-A): another client's, a system account, or a missing one are all 404.
        Account account = ledger.accountOwnedBy(caller.clientId(), command.account());
        if (account.currency() != command.amount().currency()) {
            throw new CurrencyMismatchException(
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
        return funding;
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

    private Optional<UUID> findIdByKey(ClientId client, IdempotencyKey key) {
        return jdbc.sql("SELECT id FROM fundings WHERE client_id = :clientId AND idempotency_key = :key")
                .param("clientId", client.value())
                .param("key", key.value())
                .query(UUID.class)
                .optional();
    }
}
