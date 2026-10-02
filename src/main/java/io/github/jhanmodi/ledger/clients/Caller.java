package io.github.jhanmodi.ledger.clients;

import io.github.jhanmodi.ledger.audit.Actor;
import io.github.jhanmodi.ledger.audit.RequestId;
import java.util.Objects;

/**
 * An API client making one request: the client its key was verified as, plus where the request came from. The web
 * layer builds one for each request that changes something, and passes it to the service that does the work and audits
 * it (ADR-0020).
 */
public record Caller(AuthenticatedClient client, RequestId requestId, String sourceIp) {

    public Caller {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(sourceIp, "sourceIp");
    }

    public ClientId clientId() {
        return client.clientId();
    }

    /** This caller as the audit log records it. */
    public Actor auditActor() {
        return new Actor.ApiKey(client.clientId().value(), client.keyId(), requestId, sourceIp);
    }
}
