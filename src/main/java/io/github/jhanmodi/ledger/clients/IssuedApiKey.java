package io.github.jhanmodi.ledger.clients;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * A newly issued API key: the only time the full key exists outside its owner's hands. Show {@link #plaintext()} once
 * and never log it. {@link #toString()} hides it, so logging this object by accident doesn't leak the key.
 */
public record IssuedApiKey(ClientId clientId, String keyId, Set<Scope> scopes, String plaintext) {

    public IssuedApiKey {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(plaintext, "plaintext");
        scopes = Collections.unmodifiableSet(EnumSet.copyOf(scopes));
    }

    @Override
    public String toString() {
        return "IssuedApiKey[clientId=" + clientId + ", keyId=" + keyId + ", scopes=" + scopes
                + ", plaintext=<hidden>]";
    }
}
