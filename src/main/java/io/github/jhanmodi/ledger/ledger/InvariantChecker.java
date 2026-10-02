package io.github.jhanmodi.ledger.ledger;

import io.github.jhanmodi.ledger.money.CurrencyCode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves the ledger is internally consistent (docs/design.md, section 6). Both queries run in one read-only
 * REPEATABLE READ transaction, so they see a single snapshot of the database even while postings continue.
 */
@Service
public class InvariantChecker {

    /**
     * One row for every CUSTOMER account whose cached {@code posted_balance} differs from the balance derived from its
     * entries. An account with no entries has a derived balance of 0. Columns, in any order:
     *
     * <ul>
     *   <li>{@code account_id}: the account's id
     *   <li>{@code cached_balance}: its {@code posted_balance}
     *   <li>{@code derived_balance}: the balance its entries add up to (ADR-0003: normal-side entries minus
     *       opposite-side entries)
     * </ul>
     *
     * <p>Returns no rows when every cached balance is correct. System accounts are never reported (they have no
     * cached balance).
     */
    static final String CACHED_BALANCE_MISMATCHES_SQL = """
            WITH derived AS (
                SELECT a.id             AS account_id,
                       a.posted_balance AS cached_balance,
                       -- An entry on the normal side adds to the balance; one on the opposite side subtracts.
                       -- coalesce: an account with no entries sums to NULL, but its real balance is 0.
                       coalesce(sum(CASE WHEN e.direction = a.normal_side THEN e.amount ELSE -e.amount END), 0)
                                        AS derived_balance
                FROM accounts a
                -- LEFT JOIN keeps accounts that have no entries; an inner join would silently skip them.
                LEFT JOIN entries e ON e.account_id = a.id
                -- Only customers cache a balance; system accounts' balance columns are NULL by design.
                WHERE a.kind = 'CUSTOMER'
                GROUP BY a.id
            )
            SELECT account_id, cached_balance, derived_balance
            FROM derived
            WHERE cached_balance <> derived_balance
            """;

    /**
     * One row for every currency whose total debits differ from its total credits, across all entries in the ledger.
     * Columns, in any order:
     *
     * <ul>
     *   <li>{@code currency}: the currency code
     *   <li>{@code total_debits}: the sum of all DEBIT entry amounts in that currency (0 if there are none)
     *   <li>{@code total_credits}: the sum of all CREDIT entry amounts in that currency (0 if there are none)
     * </ul>
     *
     * <p>Returns no rows when every currency balances.
     */
    static final String UNBALANCED_CURRENCIES_SQL = """
            WITH totals AS (
                SELECT currency,
                       -- coalesce: a currency with no credits (or no debits) would otherwise total NULL,
                       -- and comparing with NULL is never true, so the broken currency would vanish.
                       coalesce(sum(amount) FILTER (WHERE direction = 'DEBIT'), 0)  AS total_debits,
                       coalesce(sum(amount) FILTER (WHERE direction = 'CREDIT'), 0) AS total_credits
                FROM entries
                -- Per currency: 100 USD of debits and 100 EUR of credits are two problems, not a balance.
                GROUP BY currency
            )
            SELECT currency, total_debits, total_credits
            FROM totals
            WHERE total_debits <> total_credits
            """;

    private final JdbcClient jdbc;

    public InvariantChecker(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InvariantReport check() {
        List<BalanceMismatch> balanceMismatches = jdbc.sql(written(CACHED_BALANCE_MISMATCHES_SQL))
                .query((rs, rowNum) -> new BalanceMismatch(
                        new AccountId(rs.getObject("account_id", UUID.class)),
                        rs.getLong("cached_balance"),
                        wholeNumber(rs.getBigDecimal("derived_balance"), "derived_balance")))
                .list();
        List<CurrencyImbalance> currencyImbalances = jdbc.sql(written(UNBALANCED_CURRENCIES_SQL))
                .query((rs, rowNum) -> new CurrencyImbalance(
                        CurrencyCode.valueOf(rs.getString("currency")),
                        wholeNumber(rs.getBigDecimal("total_debits"), "total_debits"),
                        wholeNumber(rs.getBigDecimal("total_credits"), "total_credits")))
                .list();
        return new InvariantReport(balanceMismatches, currencyImbalances);
    }

    /** Postgres returns SUM over BIGINT as NUMERIC, which can exceed a long, so it's read as an exact whole number. */
    private static BigInteger wholeNumber(BigDecimal value, String column) {
        if (value == null) {
            throw new IllegalStateException(column + " came back NULL; the query must return a number in every row");
        }
        return value.toBigIntegerExact();
    }

    private static String written(String sql) {
        if (sql.contains("TODO(owner")) {
            throw new UnsupportedOperationException(
                    "the invariant checker's SQL hasn't been written yet (M3a exercise)");
        }
        return sql;
    }
}
