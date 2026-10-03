package io.github.jhanmodi.ledger.transfers;

/** The client has no transfer with this id: it doesn't exist, or it's another client's. Both look the same. */
public final class TransferNotFoundException extends RuntimeException {

    TransferNotFoundException(TransferId id) {
        super("transfer " + id + " was not found");
    }
}
