package io.github.jhanmodi.ledger.transfers;

/** A transfer named the same account as its source and its destination. Nothing was moved. */
public final class SameAccountException extends RuntimeException {

    SameAccountException() {
        super("a transfer's source and destination must be different accounts");
    }
}
