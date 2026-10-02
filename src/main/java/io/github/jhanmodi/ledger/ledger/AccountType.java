package io.github.jhanmodi.ledger.ledger;

/** The accounting type of an account, which fixes its normal side (ADR-0003). */
public enum AccountType {
    ASSET(Direction.DEBIT),
    LIABILITY(Direction.CREDIT),
    EQUITY(Direction.CREDIT),
    REVENUE(Direction.CREDIT),
    EXPENSE(Direction.DEBIT);

    private final Direction normalSide;

    AccountType(Direction normalSide) {
        this.normalSide = normalSide;
    }

    /** The side that increases this account's balance. */
    public Direction normalSide() {
        return normalSide;
    }
}
