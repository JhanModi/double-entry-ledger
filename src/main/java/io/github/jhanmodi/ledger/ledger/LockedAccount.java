package io.github.jhanmodi.ledger.ledger;

import java.util.Objects;

/**
 * A customer account's status and cached balances, read while this transaction holds the account's row lock. No other
 * transaction can change them until this one ends, so checks made on these values still hold when the posting is
 * written (ADR-0005).
 */
record LockedAccount(AccountId id, AccountStatus status, long postedBalance, long heldBalance) {

    LockedAccount {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
    }
}
