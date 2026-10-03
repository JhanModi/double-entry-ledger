package io.github.jhanmodi.ledger.transfers;

import io.github.jhanmodi.ledger.ledger.AccountId;
import java.util.Locale;

/**
 * One side of a transfer isn't an account the client owns. As everywhere else (ADR-0017), that's the same answer
 * whether the account belongs to another client, is a system account, or doesn't exist. Saying which side reveals
 * nothing: the client chose both ids.
 */
public final class TransferAccountNotFoundException extends RuntimeException {

    public enum Side {
        SOURCE,
        DESTINATION
    }

    private final Side side;

    TransferAccountNotFoundException(Side side, AccountId accountId) {
        super("the " + side.name().toLowerCase(Locale.ROOT) + " account " + accountId + " was not found");
        this.side = side;
    }

    public Side side() {
        return side;
    }
}
