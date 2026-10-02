package io.github.jhanmodi.ledger.ledger;

import io.github.jhanmodi.ledger.money.CurrencyCode;
import java.util.Objects;

/** What an account is. Everything here except {@code status} is fixed for the account's lifetime. */
public record Account(AccountId id, AccountKind kind, AccountType type, CurrencyCode currency, AccountStatus status) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(status, "status");
    }
}
