package io.github.jhanmodi.ledger.audit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jhanmodi.ledger.OwnerDatabase;
import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.clients.ClientService;
import io.github.jhanmodi.ledger.clients.IssuedApiKey;
import io.github.jhanmodi.ledger.clients.Scope;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves each database guard on {@code audit_log} (V5, ADR-0020) works on its own, by going around the Java code with
 * raw SQL as the database owner. Every statement runs in a rolled-back transaction, so nothing here is committed.
 *
 * <p>That the application may only INSERT into the table, and not read, change, or delete from it, is proven
 * separately, in AppRolePrivilegesIT.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditLogSchemaIT {

    @Autowired
    OwnerDatabase owner;

    @Autowired
    ClientService clientService;

    JdbcClient jdbc;
    TransactionTemplate tx;

    ClientId alice;
    ClientId bob;
    IssuedApiKey alicesKey;

    @BeforeEach
    void setUp() {
        jdbc = owner.jdbc();
        tx = owner.transactions();
        alice = clientService.createClient("alice");
        bob = clientService.createClient("bob");
        alicesKey = clientService.issueKey(alice, Set.of(Scope.WRITE));
    }

    // --- Who did it ---

    @Test
    void anActionByAnApiKeyWithAllItsDetailsIsAccepted() {
        // The baseline: without it, the rejections below could be failing for some other reason.
        assertAccepted(() -> insert(byApiKey()));
    }

    @Test
    void anActionByTheOperatorAtTheCommandLineIsAccepted() {
        assertAccepted(() -> insert(byOperator()));
    }

    @Test
    void anActionByAnApiKeyNeedsItsClientKeyRequestAndAddress() {
        for (String detail : new String[] {"clientId", "keyId", "requestId", "sourceIp"}) {
            Map<String, Object> row = byApiKey();
            row.put(detail, null);

            assertRejected(() -> insert(row), "audit_log_actor_details");
        }
    }

    @Test
    void anActionByTheOperatorHasNoClientKeyRequestOrAddress() {
        Map<String, Object> apiKeyDetails = byApiKey();
        for (String detail : new String[] {"clientId", "keyId", "requestId", "sourceIp"}) {
            Map<String, Object> row = byOperator();
            row.put(detail, apiKeyDetails.get(detail));

            assertRejected(() -> insert(row), "audit_log_actor_details");
        }
    }

    @Test
    void anUnknownKindOfActorIsRejected() {
        Map<String, Object> row = byApiKey();
        row.put("actorType", "ROBOT");

        assertRejected(() -> insert(row), "audit_log_actor_details");
    }

    @Test
    void theKeyMustBeOneOfTheStatedClientsKeys() {
        Map<String, Object> someoneElsesKey = byApiKey();
        someoneElsesKey.put("clientId", bob.value());
        Map<String, Object> noSuchKey = byApiKey();
        noSuchKey.put("keyId", "0000000000000000");

        assertRejected(() -> insert(someoneElsesKey), "audit_log_actor_key_fkey");
        assertRejected(() -> insert(noSuchKey), "audit_log_actor_key_fkey");
    }

    // --- What was done, and to what ---

    @Test
    void anUnknownActionIsRejected() {
        Map<String, Object> row = byApiKey();
        row.put("action", "BALANCE_EDITED");

        assertRejected(() -> insert(row), "audit_log_action_known");
    }

    @Test
    void aTargetIsRequired() {
        Map<String, Object> row = byApiKey();
        row.put("targetId", "");

        assertRejected(() -> insert(row), "audit_log_target_id_check");
    }

    // --- Append-only, even for the owner ---

    @Test
    void auditRowsCannotBeUpdatedDeletedOrTruncated() {
        assertRejected(
                () -> {
                    Map<String, Object> row = byApiKey();
                    insert(row);
                    jdbc.sql("UPDATE audit_log SET action = 'ACCOUNT_OPENED' WHERE target_id = :target")
                            .param("target", row.get("targetId"))
                            .update();
                },
                "the audit log is append-only");
        assertRejected(
                () -> {
                    Map<String, Object> row = byApiKey();
                    insert(row);
                    jdbc.sql("DELETE FROM audit_log WHERE target_id = :target")
                            .param("target", row.get("targetId"))
                            .update();
                },
                "the audit log is append-only");
        assertRejected(() -> jdbc.sql("TRUNCATE audit_log").update(), "the audit log is append-only");
    }

    // --- helpers ---

    /** Alice's key created a transfer, in a request from a documentation-range address (RFC 5737). */
    private Map<String, Object> byApiKey() {
        Map<String, Object> row = new HashMap<>();
        row.put("action", "TRANSFER_CREATED");
        row.put("targetId", UUID.randomUUID().toString());
        row.put("actorType", "API_KEY");
        row.put("clientId", alice.value());
        row.put("keyId", alicesKey.keyId());
        row.put("requestId", UUID.randomUUID());
        row.put("sourceIp", "203.0.113.7");
        return row;
    }

    /** The operator created a client at the command line. */
    private Map<String, Object> byOperator() {
        Map<String, Object> row = new HashMap<>();
        row.put("action", "CLIENT_CREATED");
        row.put("targetId", UUID.randomUUID().toString());
        row.put("actorType", "OPERATOR_CLI");
        row.put("clientId", null);
        row.put("keyId", null);
        row.put("requestId", null);
        row.put("sourceIp", null);
        return row;
    }

    private void insert(Map<String, Object> row) {
        jdbc.sql("""
                        INSERT INTO audit_log
                            (action, target_id, actor_type, actor_client_id, actor_key_id, request_id, source_ip)
                        VALUES (:action, :targetId, :actorType, :clientId, :keyId, :requestId, CAST(:sourceIp AS inet))
                        """)
                .param("action", row.get("action"))
                .param("targetId", row.get("targetId"))
                .param("actorType", row.get("actorType"))
                // Explicit SQL types, because several of these are NULL in some tests.
                .param("clientId", row.get("clientId"), Types.OTHER)
                .param("keyId", row.get("keyId"), Types.VARCHAR)
                .param("requestId", row.get("requestId"), Types.OTHER)
                .param("sourceIp", row.get("sourceIp"), Types.VARCHAR)
                .update();
    }

    private void assertAccepted(Runnable work) {
        assertThatCode(() -> rollbackOnly(work)).doesNotThrowAnyException();
    }

    /** The work fails, and the database's error names the expected constraint or reason. */
    private void assertRejected(Runnable work, String expected) {
        assertThatThrownBy(() -> rollbackOnly(work)).rootCause().hasMessageContaining(expected);
    }

    /** Runs the work, then rolls back whatever happened. */
    private void rollbackOnly(Runnable work) {
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            work.run();
        });
    }
}
