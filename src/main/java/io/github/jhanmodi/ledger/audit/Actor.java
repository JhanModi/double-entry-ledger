package io.github.jhanmodi.ledger.audit;

import java.util.Objects;
import java.util.UUID;

/**
 * Who performed an audited action, and where it came from (ADR-0020). There are exactly two kinds, mirroring the
 * {@code audit_log_actor_details} constraint: an API key, acting in an HTTP request, or the operator at the command
 * line. Because the interface is sealed, code that handles an actor must handle both, and the compiler checks it.
 *
 * <p>Ids are plain UUIDs here rather than {@code ClientId}: the audit module is shared infrastructure, and the clients
 * module depends on it to audit client creation, so depending back on clients would create a cycle.
 */
public sealed interface Actor {

    /** The operator at the command line ({@code clients create}). No client, key, request, or network address. */
    Actor OPERATOR_CLI = new OperatorCli();

    /** An API key, used in one HTTP request. {@code keyId} is the public half of the key, never the secret. */
    record ApiKey(UUID clientId, String keyId, RequestId requestId, String sourceIp) implements Actor {

        public ApiKey {
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(keyId, "keyId");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(sourceIp, "sourceIp");
        }
    }

    /** Use {@link Actor#OPERATOR_CLI}. */
    record OperatorCli() implements Actor {}
}
