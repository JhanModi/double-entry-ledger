package io.github.jhanmodi.ledger.clients;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** The client a request was made by, after its API key was verified. Carries no secret. */
public record AuthenticatedClient(ClientId clientId, String keyId, Set<Scope> scopes) {

    public AuthenticatedClient {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(keyId, "keyId");
        scopes = Collections.unmodifiableSet(EnumSet.copyOf(scopes));
    }
}
