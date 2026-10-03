package io.github.jhanmodi.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A connection as the database owner, separate from the application's restricted login. Only for tests that need what
 * the app's login can't do: showing that the database's guards catch a mistake even when the owner makes it, or reading
 * the audit log. Deliberately not a {@code DataSource} bean: if it were, Spring Boot would hand it to the application
 * too.
 */
public final class OwnerDatabase {

    private final PostgreSQLContainer postgres;
    private JdbcClient jdbc;
    private TransactionTemplate transactions;

    OwnerDatabase(PostgreSQLContainer postgres) {
        this.postgres = postgres;
    }

    public JdbcClient jdbc() {
        connect();
        return jdbc;
    }

    /** Transactions on the owner connection, for {@link #jdbc()}. */
    public TransactionTemplate transactions() {
        connect();
        return transactions;
    }

    // Connects on first use: the container's address is only known once it has started.
    private synchronized void connect() {
        if (jdbc == null) {
            DriverManagerDataSource dataSource =
                    new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            jdbc = JdbcClient.create(dataSource);
            transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        }
    }
}
