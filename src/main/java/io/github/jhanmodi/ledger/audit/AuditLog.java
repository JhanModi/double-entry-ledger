package io.github.jhanmodi.ledger.audit;

import java.sql.Types;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the audit log (ADR-0020): who did what, to what, when, and from where.
 *
 * <p>An audit row is written in the same database transaction as the action it describes, so both are committed or
 * neither is. {@link Propagation#MANDATORY} enforces that: calling {@link #record} without a transaction already open
 * throws instead of quietly writing the row on its own.
 *
 * <p>The application can only INSERT into {@code audit_log}. It can't read the log, change it, or delete from it.
 */
@Component
public class AuditLog {

    private final JdbcClient jdbc;

    public AuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records an action, as part of the caller's transaction.
     *
     * @param targetId the target's public identifier: a UUID for accounts, transfers, fundings, and clients, and the
     *     public key id for API keys
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditAction action, String targetId, Actor actor) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(targetId, "targetId");
        ActorColumns columns = ActorColumns.of(Objects.requireNonNull(actor, "actor"));
        // No RETURNING clause: that would need SELECT on audit_log, which the application deliberately doesn't have.
        jdbc.sql("""
                        INSERT INTO audit_log
                            (action, target_id, actor_type, actor_client_id, actor_key_id, request_id, source_ip)
                        VALUES (:action, :targetId, :actorType, :clientId, :keyId, :requestId, CAST(:sourceIp AS inet))
                        """)
                .param("action", action.name())
                .param("targetId", targetId)
                .param("actorType", columns.type())
                // Explicit SQL types, because these are NULL for the operator.
                .param("clientId", columns.clientId(), Types.OTHER)
                .param("keyId", columns.keyId(), Types.VARCHAR)
                .param("requestId", columns.requestId(), Types.OTHER)
                .param("sourceIp", columns.sourceIp(), Types.VARCHAR)
                .update();
    }

    /** How each kind of actor is stored. The switch covers every kind of {@link Actor}, checked by the compiler. */
    private record ActorColumns(String type, UUID clientId, String keyId, UUID requestId, String sourceIp) {

        static ActorColumns of(Actor actor) {
            return switch (actor) {
                case Actor.ApiKey key ->
                    new ActorColumns(
                            "API_KEY",
                            key.clientId(),
                            key.keyId(),
                            key.requestId().value(),
                            key.sourceIp());
                case Actor.OperatorCli _ -> new ActorColumns("OPERATOR_CLI", null, null, null, null);
            };
        }
    }
}
