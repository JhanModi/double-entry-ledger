package io.github.jhanmodi.ledger.ledger;

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

    private static final RowMapper<Account> ACCOUNT = (rs, rowNum) -> new Account(
            new AccountId(rs.getObject("id", UUID.class)),
            AccountKind.valueOf(rs.getString("kind")),
            AccountType.valueOf(rs.getString("type")),
            CurrencyCode.valueOf(rs.getString("currency")),
            AccountStatus.valueOf(rs.getString("status")));

    private final JdbcClient jdbc;

    AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    AccountId insert(AccountKind kind, AccountType type, CurrencyCode currency) {
        // Customer accounts start with cached balances of zero; system accounts have none (ADR-0004).
        Long initialBalance = kind == AccountKind.CUSTOMER ? 0L : null;
        UUID id = jdbc.sql("""
                        INSERT INTO accounts (kind, type, normal_side, currency, posted_balance, held_balance)
                        VALUES (:kind, :type, :normalSide, :currency, :balance, :balance)
                        RETURNING id
                        """)
                .param("kind", kind.name())
                .param("type", type.name())
                .param("normalSide", type.normalSide().name())
                .param("currency", currency.name())
                .param("balance", initialBalance, Types.BIGINT)
                .query(UUID.class)
                .single();
        return new AccountId(id);
    }

    Optional<Account> find(AccountId id) {
        return jdbc.sql("SELECT id, kind, type, currency, status FROM accounts WHERE id = :id")
                .param("id", id.value())
                .query(ACCOUNT)
                .optional();
    }

    List<Account> findAll(Collection<AccountId> ids) {
        return jdbc.sql("SELECT id, kind, type, currency, status FROM accounts WHERE id IN (:ids)")
                .param("ids", ids.stream().map(AccountId::value).toList())
                .query(ACCOUNT)
                .list();
    }
}
