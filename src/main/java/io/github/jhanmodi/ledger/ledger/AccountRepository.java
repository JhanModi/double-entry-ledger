package io.github.jhanmodi.ledger.ledger;

import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.money.CurrencyCode;
import java.sql.Types;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** SQL for the accounts table. Package-private: other modules go through the ledger's services. */
@Component
class AccountRepository {

    private static final String COLUMNS = "id, kind, type, currency, status, client_id";

    private static final RowMapper<Account> ACCOUNT = (rs, rowNum) -> {
        UUID clientId = rs.getObject("client_id", UUID.class);
        return new Account(
                new AccountId(rs.getObject("id", UUID.class)),
                AccountKind.valueOf(rs.getString("kind")),
                AccountType.valueOf(rs.getString("type")),
                CurrencyCode.valueOf(rs.getString("currency")),
                AccountStatus.valueOf(rs.getString("status")),
                clientId == null ? null : new ClientId(clientId));
    };

    private final JdbcClient jdbc;

    AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    AccountId insert(AccountKind kind, AccountType type, CurrencyCode currency, ClientId owner) {
        // Customer accounts start with cached balances of zero; system accounts have none (ADR-0004).
        Long initialBalance = kind == AccountKind.CUSTOMER ? 0L : null;
        UUID id = jdbc.sql("""
                        INSERT INTO accounts (kind, type, normal_side, currency, client_id, posted_balance, held_balance)
                        VALUES (:kind, :type, :normalSide, :currency, :clientId, :balance, :balance)
                        RETURNING id
                        """)
                .param("kind", kind.name())
                .param("type", type.name())
                .param("normalSide", type.normalSide().name())
                .param("currency", currency.name())
                .param("clientId", owner == null ? null : owner.value(), Types.OTHER)
                .param("balance", initialBalance, Types.BIGINT)
                .query(UUID.class)
                .single();
        return new AccountId(id);
    }

    Optional<Account> find(AccountId id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id = :id")
                .param("id", id.value())
                .query(ACCOUNT)
                .optional();
    }

    /**
     * The account, only if this client owns it. The ownership check is in the SQL itself, so a caller can't forget it,
     * and an account owned by someone else is indistinguishable from one that doesn't exist (ADR-0017).
     */
    Optional<Account> findOwnedBy(ClientId owner, AccountId id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id = :id AND client_id = :clientId")
                .param("id", id.value())
                .param("clientId", owner.value())
                .query(ACCOUNT)
                .optional();
    }

    Optional<Account> findSystemAccount(AccountPurpose purpose, CurrencyCode currency) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM accounts WHERE kind = 'SYSTEM' AND purpose = :purpose AND currency = :currency")
                .param("purpose", purpose.name())
                .param("currency", currency.name())
                .query(ACCOUNT)
                .optional();
    }

    List<Account> findAll(Collection<AccountId> ids) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id IN (:ids)")
                .param("ids", ids.stream().map(AccountId::value).toList())
                .query(ACCOUNT)
                .list();
    }

    /**
     * Locks these customer accounts' rows until the transaction ends, and returns their status and balances as of the
     * lock (ADR-0005). Waits for any other transaction holding one of them, up to the lock timeout.
     *
     * <ul>
     *   <li><b>In ascending id order:</b> Postgres sorts the rows ({@code ORDER BY}) before it locks them, so every
     *       posting takes its locks in the same order and two postings can't deadlock. Ids never change (a trigger
     *       rejects it), so the order can't shift while a lock is being waited for.
     *   <li><b>{@code FOR NO KEY UPDATE}</b> rather than {@code FOR UPDATE}: it doesn't block the lighter
     *       {@code KEY SHARE} locks that foreign keys take when entries, transfers, and fundings that point at the
     *       account are inserted.
     *   <li><b>Customer accounts only:</b> a system account is never locked, even if one is passed by mistake. Every
     *       funding in a currency touches the same settlement account, so locking it would make them all wait in line.
     * </ul>
     */
    List<LockedAccount> lockForPosting(Collection<AccountId> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT id, status, posted_balance, held_balance
                        FROM accounts
                        WHERE id IN (:ids) AND kind = 'CUSTOMER'
                        ORDER BY id
                        FOR NO KEY UPDATE
                        """)
                .param("ids", ids.stream().map(AccountId::value).toList())
                .query((rs, rowNum) -> new LockedAccount(
                        new AccountId(rs.getObject("id", UUID.class)),
                        AccountStatus.valueOf(rs.getString("status")),
                        rs.getLong("posted_balance"),
                        rs.getLong("held_balance")))
                .list();
    }
}
