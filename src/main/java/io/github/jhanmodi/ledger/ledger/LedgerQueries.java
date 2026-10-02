package io.github.jhanmodi.ledger.ledger;

import io.github.jhanmodi.ledger.money.CurrencyCode;
import io.github.jhanmodi.ledger.money.Money;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only views of the ledger: accounts, balances, and history. */
@Service
public class LedgerQueries {

    static final int MAX_PAGE_SIZE = 100;

    // Keyset pagination: "entries older than the cursor", newest first. Served by the (account_id, id) index, so a
    // page costs the same however deep into the history it is. UUIDv7 ids sort in creation order (ADR-0014).
    private static final String FIRST_PAGE_SQL = """
            SELECT e.id, e.transaction_id, t.type, t.description, t.effective_date, t.created_at, e.direction, e.amount
            FROM entries e
            JOIN ledger_transactions t ON t.id = e.transaction_id
            WHERE e.account_id = :accountId
            ORDER BY e.id DESC
            LIMIT :limit
            """;

    private static final String NEXT_PAGE_SQL = """
            SELECT e.id, e.transaction_id, t.type, t.description, t.effective_date, t.created_at, e.direction, e.amount
            FROM entries e
            JOIN ledger_transactions t ON t.id = e.transaction_id
            WHERE e.account_id = :accountId
              AND e.id < :before
            ORDER BY e.id DESC
            LIMIT :limit
            """;

    private final JdbcClient jdbc;
    private final AccountRepository accounts;

    public LedgerQueries(JdbcClient jdbc, AccountRepository accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    public Account account(AccountId id) {
        return accounts.find(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public AccountBalance balance(AccountId id) {
        Account account = account(id);
        return switch (account.kind()) {
            case CUSTOMER -> cachedBalance(account);
            case SYSTEM -> derivedBalance(account);
        };
    }

    /**
     * Up to {@code limit} entries for the account, newest first. Pass {@link EntryPage#nextCursor()} as {@code before}
     * to get the next page.
     */
    @Transactional(readOnly = true)
    public EntryPage history(AccountId id, Optional<EntryId> before, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE + ", got " + limit);
        }
        Account account = account(id);
        // Ask for one extra row: if it comes back, there's another page.
        JdbcClient.StatementSpec query = jdbc.sql(before.isPresent() ? NEXT_PAGE_SQL : FIRST_PAGE_SQL)
                .param("accountId", id.value())
                .param("limit", limit + 1);
        if (before.isPresent()) {
            query = query.param("before", before.get().value());
        }
        List<EntryView> rows = query.query(entryView(account.currency())).list();

        boolean hasMore = rows.size() > limit;
        List<EntryView> page = hasMore ? rows.subList(0, limit) : rows;
        Optional<EntryId> nextCursor = hasMore ? Optional.of(page.getLast().entryId()) : Optional.empty();
        return new EntryPage(page, nextCursor);
    }

    private AccountBalance cachedBalance(Account account) {
        return jdbc.sql("SELECT posted_balance, held_balance FROM accounts WHERE id = :id")
                .param("id", account.id().value())
                .query((rs, rowNum) -> new AccountBalance(
                        account.id(),
                        Money.of(rs.getLong("posted_balance"), account.currency()),
                        Money.of(rs.getLong("held_balance"), account.currency())))
                .single();
    }

    /** System accounts have no cached balance (ADR-0004), so total each side of their entries. */
    private AccountBalance derivedBalance(Account account) {
        BigInteger[] totals = {BigInteger.ZERO, BigInteger.ZERO};
        jdbc.sql("SELECT direction, sum(amount) AS total FROM entries WHERE account_id = :id GROUP BY direction")
                .param("id", account.id().value())
                .query(rs -> {
                    boolean normalSide = Direction.valueOf(rs.getString("direction"))
                            == account.type().normalSide();
                    totals[normalSide ? 0 : 1] = rs.getBigDecimal("total").toBigIntegerExact();
                });
        long balance = totals[0].subtract(totals[1]).longValueExact();
        return new AccountBalance(account.id(), Money.of(balance, account.currency()), Money.zero(account.currency()));
    }

    private static RowMapper<EntryView> entryView(CurrencyCode currency) {
        return (rs, rowNum) -> new EntryView(
                new EntryId(rs.getObject("id", UUID.class)),
                new LedgerTransactionId(rs.getObject("transaction_id", UUID.class)),
                LedgerTransactionType.valueOf(rs.getString("type")),
                rs.getString("description"),
                rs.getObject("effective_date", LocalDate.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                Direction.valueOf(rs.getString("direction")),
                Money.of(rs.getLong("amount"), currency));
    }
}
