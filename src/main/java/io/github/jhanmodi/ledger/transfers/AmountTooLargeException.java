package io.github.jhanmodi.ledger.transfers;

import io.github.jhanmodi.ledger.money.Money;

/** The amount is above the maximum one request may move in its currency ({@link AmountLimits}). Nothing was moved. */
public final class AmountTooLargeException extends RuntimeException {

    private final Money maximum;

    AmountTooLargeException(Money maximum) {
        super("the most one request may move in " + maximum.currency() + " is " + maximum);
        this.maximum = maximum;
    }

    public Money maximum() {
        return maximum;
    }
}
