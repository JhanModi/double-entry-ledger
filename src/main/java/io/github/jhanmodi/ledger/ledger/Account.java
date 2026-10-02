package io.github.jhanmodi.ledger.ledger;

import io.github.jhanmodi.ledger.clients.ClientId;
import io.github.jhanmodi.ledger.money.CurrencyCode;
import java.util.Objects;

/**
 * What an account is. Everything here except {@code status} is fixed for the account's lifetime. {@code clientId} is
 * the owning client for customer accounts and null for system accounts, which belong to the platform.
 */
public record Account(
        AccountId id,
        AccountKind kind,
        AccountType type,
        CurrencyCode currency,
        AccountStatus status,
        ClientId clientId) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(status, "status");
        if ((kind == AccountKind.CUSTOMER) != (clientId != null)) {
            throw new IllegalArgumentException("customer accounts have an owning client; system accounts have none");
        }
    }

    public boolean isOwnedBy(ClientId client) {
        return clientId != null && clientId.equals(client);
    }
}
