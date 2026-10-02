package io.github.jhanmodi.ledger.ledger;

import io.github.jhanmodi.ledger.money.Money;
import java.util.Objects;

/**
 * An account's balance. For customer accounts it comes from the cached columns; for system accounts {@code posted} is
 * derived from entries and {@code held} is always zero (ADR-0004).
 */
public record AccountBalance(AccountId accountId, Money posted, Money held) {

    public AccountBalance {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(posted, "posted");
        Objects.requireNonNull(held, "held");
    }

    /** What can be spent right now: posted minus held. */
    public Money available() {
        return posted.minus(held);
    }
}
