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

    List<Account> findAll(Collection<AccountId> ids) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id IN (:ids)")
                .param("ids", ids.stream().map(AccountId::value).toList())
                .query(ACCOUNT)
                .list();
    }
}
