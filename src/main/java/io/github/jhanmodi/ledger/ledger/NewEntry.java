package io.github.jhanmodi.ledger.ledger;

import io.github.jhanmodi.ledger.money.Money;
import java.util.Objects;

/** One line of a {@link PostingRequest}: an account, a side, and a positive amount (ADR-0003). */
public record NewEntry(AccountId accountId, Direction direction, Money amount) {

    public NewEntry {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("entry amounts must be positive, got " + amount);
        }
    }

    public static NewEntry debit(AccountId accountId, Money amount) {
        return new NewEntry(accountId, Direction.DEBIT, amount);
    }

    public static NewEntry credit(AccountId accountId, Money amount) {
        return new NewEntry(accountId, Direction.CREDIT, amount);
    }
}
