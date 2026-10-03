package io.github.jhanmodi.ledger.transfers;

import io.github.jhanmodi.ledger.ledger.AccountId;
import io.github.jhanmodi.ledger.ledger.PostingRequest;
import io.github.jhanmodi.ledger.money.Money;
import java.util.Objects;

/**
 * A request to move money between two of the caller's accounts. Construction fails unless the request makes sense on
 * its own: a positive amount within the limit, two different accounts, and a description of at most 500 characters.
 * Whether the caller owns the accounts, and whether the money is there, is checked when it's carried out.
 *
 * @param description optional; shown in both accounts' history
 */
public record TransferCommand(
        AccountId source, AccountId destination, Money amount, String description, IdempotencyKey idempotencyKey) {

    public TransferCommand {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a transfer amount must be positive, got " + amount);
        }
        // Counted the way Postgres counts: length() counts UTF-16 units, so an emoji would count twice.
        if (description != null
                && description.codePointCount(0, description.length()) > PostingRequest.MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "a description is at most " + PostingRequest.MAX_DESCRIPTION_LENGTH + " characters");
        }
        if (source.equals(destination)) {
            throw new SameAccountException();
        }
        AmountLimits.requireWithinLimit(amount);
    }
}
