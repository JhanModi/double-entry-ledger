package io.github.jhanmodi.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the application's database login may and may not do (ADR-0015). This is the specification for the GRANT
 * statements in the migrations (V3, V4, and V5).
 *
 * <p>The rest of the integration suite proves the grants are <em>enough</em>, because all of it runs as this login.
 * These tests prove they're <em>no more</em> than enough. Each denial must fail with Postgres's "insufficient
 * privilege" error (SQLSTATE 42501), not just any error, so a trigger that happens to block the statement doesn't
 * count. Every attempt runs in a rolled-back transaction, so even an unexpected success can't change the shared test
 * database.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AppRolePrivilegesIT {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    // --- Who the application is ---

    @Test
    void theApplicationConnectsAsTheRestrictedLogin() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("ledger_service");
        assertThat(jdbc.sql("SELECT rolsuper FROM pg_roles WHERE rolname = current_user")
                        .query(Boolean.class)
                        .single())
                .as("the app's login must not be a superuser")
                .isFalse();
        assertThat(jdbc.sql("SELECT pg_has_role(current_user, 'ledger_app', 'MEMBER')")
                        .query(Boolean.class)
                        .single())
                .as("the app's login gets its privileges by being a member of ledger_app")
                .isTrue();
    }

    // --- Exactly these privileges, and no others ---

    @Test
    void ledgerAppHasExactlyTheseTablePrivileges() {
        List<String> granted = jdbc.sql("""
                        SELECT c.relname || ': ' || string_agg(acl.privilege_type, ', ' ORDER BY acl.privilege_type)
                        FROM pg_class c
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        CROSS JOIN LATERAL aclexplode(c.relacl) AS acl
                        WHERE n.nspname = 'public' AND acl.grantee = 'ledger_app'::regrole
                        GROUP BY c.relname
                        ORDER BY c.relname
                        """).query(String.class).list();

        assertThat(granted)
                .containsExactly(
                        "accounts: INSERT, SELECT",
                        "api_clients: INSERT, SELECT",
                        "api_keys: INSERT, SELECT",
                        "audit_log: INSERT",
                        "currencies: SELECT",
                        "entries: INSERT, SELECT",
                        "fundings: INSERT, SELECT",
                        "ledger_transactions: INSERT, SELECT",
                        "transfers: INSERT, SELECT");
    }

    @Test
    void onAccountsLedgerAppMayUpdateOnlyTheBalanceAndStatusColumns() {
        List<String> granted = jdbc.sql("""
                        SELECT c.relname || '.' || a.attname || ': ' || acl.privilege_type
                        FROM pg_attribute a
                        JOIN pg_class c ON c.oid = a.attrelid
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        CROSS JOIN LATERAL aclexplode(a.attacl) AS acl
                        WHERE n.nspname = 'public' AND acl.grantee = 'ledger_app'::regrole
                        ORDER BY 1
                        """).query(String.class).list();

        assertThat(granted)
                .containsExactly(
                        "accounts.held_balance: UPDATE",
                        "accounts.posted_balance: UPDATE",
                        "accounts.status: UPDATE",
                        "api_keys.revoked_at: UPDATE");
    }

    // --- Ledger history can't be changed ---

    @Test
    void cannotUpdateDeleteOrTruncateLedgerHistory() {
        assertDenied("UPDATE entries SET amount = amount + 1");
        assertDenied("UPDATE ledger_transactions SET description = 'edited'");
        assertDenied("DELETE FROM entries");
        assertDenied("DELETE FROM ledger_transactions");
        assertDenied("TRUNCATE entries");
        assertDenied("TRUNCATE ledger_transactions");
    }

    @Test
    void cannotChangeWhatAnAccountIsOrDeleteOne() {
        // "SET x = x" changes nothing, so the identity trigger would let it through. Only the missing privilege stops
        // it.
        for (String column :
                List.of("id", "kind", "type", "normal_side", "currency", "client_id", "purpose", "created_at")) {
            assertDenied("UPDATE accounts SET " + column + " = " + column);
        }
        assertDenied("DELETE FROM accounts");
        assertDenied("TRUNCATE accounts");
    }

    // --- Money movements can't be changed, and the audit log can be written but not read or changed ---

    @Test
    void cannotUpdateDeleteOrTruncateMoneyMovements() {
        for (String table : List.of("transfers", "fundings")) {
            assertDenied("UPDATE " + table + " SET amount = amount");
            assertDenied("DELETE FROM " + table);
            assertDenied("TRUNCATE " + table);
        }
    }

    @Test
    void cannotReadUpdateDeleteOrTruncateTheAuditLog() {
        // Even a compromised app can't learn what's in the audit log, or cover its tracks (ADR-0020).
        assertDenied("SELECT count(*) FROM audit_log");
        assertDenied("UPDATE audit_log SET action = action");
        assertDenied("DELETE FROM audit_log");
        assertDenied("TRUNCATE audit_log");
    }

    @Test
    void cannotRewriteClientsOrKeys() {
        // A compromised app must not be able to re-enable a disabled client, un-revoke a key, or swap in a known hash.
        assertDenied("UPDATE api_clients SET status = 'ACTIVE'");
        for (String column : List.of("client_id", "key_id", "secret_hash", "scopes", "created_at")) {
            assertDenied("UPDATE api_keys SET " + column + " = " + column);
        }
        assertDenied("DELETE FROM api_keys");
        assertDenied("DELETE FROM api_clients");
    }

    // --- The guards can't be switched off ---

    @Test
    void cannotDisableTriggers() {
        // Only a table's owner may disable its triggers. The app's login owns nothing.
        for (String table :
                List.of("entries", "ledger_transactions", "accounts", "transfers", "fundings", "audit_log")) {
            assertDenied("ALTER TABLE " + table + " DISABLE TRIGGER ALL");
        }
    }

    @Test
    void cannotSwitchTriggersOffWithSessionReplicationRole() {
        // For a superuser, "replica" mode stops ordinary triggers firing, including the balance and append-only checks.
        // It exists for replication and restores. Only superusers may set it.
        assertDenied("SET session_replication_role = replica");
    }

    @Test
    void cannotDropTablesOrConstraints() {
        assertDenied("DROP TABLE entries");
        assertDenied("ALTER TABLE accounts DROP CONSTRAINT accounts_available_balance_non_negative");
    }

    @Test
    void cannotCreateTables() {
        assertDenied("CREATE TABLE shadow_ledger (id int)");
    }

    @Test
    void cannotReadOrRewriteMigrationHistory() {
        assertDenied("SELECT count(*) FROM flyway_schema_history");
        assertDenied("UPDATE flyway_schema_history SET checksum = 0");
    }

    private void assertDenied(String sql) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Throwable thrown = catchThrowable(() -> tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            if (sql.startsWith("SELECT")) {
                jdbc.sql(sql).query().listOfRows();
            } else {
                jdbc.sql(sql).update();
            }
        }));

        assertThat(thrown).as("expected this to be denied: %s", sql).isNotNull();
        assertThat(sqlState(thrown))
                .as("%s failed, but not for lack of privilege: %s", sql, thrown.getMessage())
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    private static String sqlState(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
