package io.github.jhanmodi.ledger.ledger;

/** A posting with fewer than two entries, or whose debits and credits differ in some currency. */
public final class UnbalancedPostingException extends IllegalArgumentException {

    public UnbalancedPostingException(String message) {
        super(message);
    }
}
